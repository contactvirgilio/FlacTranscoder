package com.example.flac2mp3

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** A tag the editor can change. [aliases] are other spellings the same tag may already have. */
enum class TagField(
    val label: String,
    val key: String,
    val aliases: List<String> = emptyList(),
    /** Whether the "apply to all files" section can set it (a title or track number is per file). */
    val batch: Boolean = true,
) {
    TITLE("Title", "title", batch = false),
    ARTIST("Artist", "artist"),
    ALBUM("Album", "album"),
    ALBUM_ARTIST("Album artist", "album_artist", listOf("ALBUMARTIST", "albumartist")),
    DATE("Year", "date", listOf("YEAR", "year")),
    GENRE("Genre", "genre"),
    TRACK("Track", "track", listOf("TRACKNUMBER", "tracknumber"), batch = false),
    DISC("Disc", "disc", listOf("DISCNUMBER", "discnumber")),
}

/** What the editor knows about a file before it is changed. */
class TagInfo(val tags: Map<TagField, String>, val hasCover: Boolean)

/** What to change in one file. */
class TagChange(
    /** The new value for each field that changed. An empty value removes the tag. */
    val fields: Map<TagField, String>,
    /** A new cover image to embed (copied to the cache), or null. */
    val coverFile: File?,
    val removeCover: Boolean,
    /** Whether the file has a cover now. */
    val hasCover: Boolean,
)

object TagEditor {

    val EXTENSIONS = listOf("flac", "mp3", "m4a", "ogg", "opus", "wav")

    fun isSupported(name: String) = EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())

    private fun mimeFor(ext: String) = when (ext) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/x-wav"
    }

    // ---- reading ---------------------------------------------------------------------------

    suspend fun read(context: Context, uri: Uri, name: String): TagInfo = withContext(Dispatchers.IO) {
        // Probe straight from the document; fall back to a cached copy if that finds nothing.
        val direct = runCatching {
            probe(com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(context, uri))
        }.getOrNull()
        if (direct != null) return@withContext direct

        val tmp = File(context.cacheDir, "probe_${System.nanoTime()}.${name.substringAfterLast('.', "bin")}")
        try {
            context.contentResolver.openInputStream(uri)?.use { src ->
                tmp.outputStream().use { src.copyTo(it) }
            } ?: throw IOException("Cannot open $name")
            probe(tmp.absolutePath) ?: throw IOException("Cannot read the tags of $name")
        } finally {
            tmp.delete()
        }
    }

    private fun probe(path: String): TagInfo? {
        val session = FFprobeKit.executeWithArguments(
            arrayOf("-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", path)
        )
        val text = session.allLogsAsString.orEmpty()
        val start = text.indexOf('{')
        if (start < 0) return null
        val root = runCatching { JSONObject(text.substring(start)) }.getOrNull() ?: return null

        val all = linkedMapOf<String, String>()
        var hasCover = false
        val streams = root.optJSONArray("streams")
        if (streams != null) {
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                when (s.optString("codec_type")) {
                    "audio" -> readInto(s.optJSONObject("tags"), all)
                    "video" -> if (s.optJSONObject("disposition")?.optInt("attached_pic") == 1) hasCover = true
                }
            }
        }
        readInto(root.optJSONObject("format")?.optJSONObject("tags"), all) // format-level tags win
        if (root.optJSONObject("format") == null && streams == null) return null

        val tags = linkedMapOf<TagField, String>()
        for (field in TagField.entries) {
            val names = listOf(field.key) + field.aliases
            val value = names.firstNotNullOfOrNull { n ->
                all.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            }
            if (value != null) tags[field] = value
        }
        return TagInfo(tags, hasCover)
    }

    private fun readInto(obj: JSONObject?, into: MutableMap<String, String>) {
        obj ?: return
        for (k in obj.keys()) into[k] = obj.optString(k)
    }

    // ---- writing ---------------------------------------------------------------------------

    /**
     * Rewrites the tags of one file without touching the audio, and either saves the result into
     * [outDir] (keeping the file name) or, when [outDir] is null, over the original.
     * The original is restored if writing over it fails halfway.
     */
    suspend fun write(
        context: Context,
        uri: Uri,
        name: String,
        change: TagChange,
        outDir: DocumentFile?,
    ): String = withContext(Dispatchers.IO) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val tmpIn = File(context.cacheDir, "tag_in_${System.nanoTime()}.$ext")
        val tmpOut = File(context.cacheDir, "tag_out_${System.nanoTime()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { src ->
                tmpIn.outputStream().use { src.copyTo(it) }
            } ?: throw IOException("Cannot open $name")

            val args = buildArgs(tmpIn, tmpOut, ext, change)
            val session = run(args)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                val log = session.allLogsAsString.orEmpty().trim().takeLast(300)
                throw IOException(log.ifBlank { "FFmpeg failed (code ${session.returnCode})" })
            }
            if (!tmpOut.exists() || tmpOut.length() == 0L) throw IOException("FFmpeg wrote an empty file")

            if (outDir != null) {
                // An existing file with the same name is never overwritten; the provider renames.
                val target = outDir.createFile(mimeFor(ext), name)
                    ?: throw IOException("Cannot create the output file")
                context.contentResolver.openOutputStream(target.uri)?.use { dst ->
                    tmpOut.inputStream().use { it.copyTo(dst) }
                } ?: throw IOException("Cannot write to the output folder")
                "Saved to the output folder"
            } else {
                try {
                    val out = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("No write access to this file. Save to a folder instead.")
                    out.use { dst -> tmpOut.inputStream().use { it.copyTo(dst) } }
                } catch (e: Exception) {
                    // Put the original back so a failed write never loses the song.
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { dst ->
                            tmpIn.inputStream().use { it.copyTo(dst) }
                        }
                    }
                    throw IOException(e.message ?: "Could not write over the original")
                }
                "Updated in place"
            }
        } finally {
            tmpIn.delete()
            tmpOut.delete()
        }
    }

    private fun buildArgs(input: File, output: File, ext: String, change: TagChange): Array<String> {
        val canHaveCover = ext != "wav"
        val newCover = if (canHaveCover) change.coverFile else null
        val a = mutableListOf("-y", "-i", input.absolutePath)
        if (newCover != null) a += listOf("-i", newCover.absolutePath)

        a += listOf("-map", "0:a")
        when {
            newCover != null -> a += listOf("-map", "1:v")
            canHaveCover && change.hasCover && !change.removeCover -> a += listOf("-map", "0:v:0")
        }
        a += listOf("-map_metadata", "0", "-map_chapters", "0", "-c", "copy")

        for ((field, value) in change.fields) {
            // Clear other spellings first so the file never ends up with the tag twice.
            for (alias in field.aliases) a += listOf("-metadata", "$alias=")
            a += listOf("-metadata", "${field.key}=$value")
        }

        if (newCover != null || (canHaveCover && change.hasCover && !change.removeCover)) {
            a += listOf("-disposition:v:0", "attached_pic")
        }
        if (newCover != null) {
            a += listOf("-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)")
        }
        when (ext) {
            "mp3" -> a += listOf("-id3v2_version", "3", "-write_id3v1", "1")
            "m4a" -> a += listOf("-movflags", "+use_metadata_tags")
        }
        a += output.absolutePath
        return a.toTypedArray()
    }

    private suspend fun run(args: Array<String>) = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(args) { s -> if (cont.isActive) cont.resume(s) }
        cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
}
