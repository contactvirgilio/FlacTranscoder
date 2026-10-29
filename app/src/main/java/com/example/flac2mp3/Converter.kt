package com.example.flac2mp3

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

object Converter {

    private class Chapter(val start: Double, val end: Double, val title: String?)

    private class Probe(
        val durationSec: Double?,
        val sampleRate: Int?,
        val tags: Map<String, String>,
        val chapters: List<Chapter>,
    ) {
        fun tag(key: String): String? =
            tags.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
    }

    /**
     * Converts one FLAC or WAV file. Whole file: one output next to the others in [outDir].
     * Split: one output per CUE track, written to a subfolder of [outDir].
     * Returns a short summary for the UI.
     *
     * The input is copied to the cache dir first so FFmpeg gets a seekable file.
     */
    suspend fun convert(
        context: Context,
        item: Item,
        outDir: DocumentFile,
        format: OutputFormat,
        split: Boolean,
        onProgress: (progress: Float?, detail: String?) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val isFlac = item.name.endsWith(".flac", ignoreCase = true)
        if (!split && format == OutputFormat.FLAC && isFlac) {
            throw IOException("Already FLAC. Pick an MP3 format, or turn on splitting.")
        }

        val ext = if (isFlac) "flac" else "wav"
        val tmpIn = File(context.cacheDir, "in_${item.id}.$ext")
        val tmpOut = File(context.cacheDir, "out_${item.id}.${format.ext}")
        try {
            val input = context.contentResolver.openInputStream(item.uri)
                ?: throw IOException("Cannot open ${item.name}")
            input.use { src -> tmpIn.outputStream().use { src.copyTo(it) } }

            val probe = probe(tmpIn)
            if (split) {
                splitAndEncode(context, item, tmpIn, tmpOut, outDir, format, probe, onProgress)
            } else {
                val name = sanitize(item.name.substringBeforeLast('.')) + "." + format.ext
                encode(
                    context, tmpIn, tmpOut, outDir, name, format, probe.sampleRate,
                    start = null, duration = null, meta = null,
                    progressMs = probe.durationSec?.let { (it * 1000).toLong() },
                ) { p -> onProgress(p, null) }
                "Saved as ${format.shortLabel}"
            }
        } finally {
            tmpIn.delete()
            tmpOut.delete()
        }
    }

    // ---- splitting -------------------------------------------------------------------------

    private suspend fun splitAndEncode(
        context: Context,
        item: Item,
        tmpIn: File,
        tmpOut: File,
        outDir: DocumentFile,
        format: OutputFormat,
        probe: Probe,
        onProgress: (Float?, String?) -> Unit,
    ): String {
        val sheet = loadSheet(context, item, probe)
        val tracks = sheet.tracks
        val folderName = sanitize(
            listOfNotNull(sheet.performer, sheet.album).joinToString(" - ")
                .ifBlank { item.name.substringBeforeLast('.') }
        )
        val folder = outDir.findFile(folderName)?.takeIf { it.isDirectory }
            ?: outDir.createDirectory(folderName)
            ?: throw IOException("Cannot create the folder \"$folderName\"")

        tracks.forEachIndexed { i, t ->
            val end = t.endSec ?: probe.durationSec
            val duration = end?.minus(t.startSec)
            if (duration != null && duration <= 0.0) {
                throw IOException("Track ${t.number} starts after the end of the audio. Is this the right CUE sheet?")
            }
            val title = t.title ?: "Track %02d".format(Locale.US, t.number)
            val fileName = "%02d - %s.%s".format(Locale.US, t.number, sanitize(title), format.ext)

            val meta = linkedMapOf<String, String>()
            meta["title"] = title
            (t.performer ?: sheet.performer)?.let { meta["artist"] = it }
            sheet.performer?.let { meta["album_artist"] = it }
            (sheet.album ?: probe.tag("album"))?.let { meta["album"] = it }
            meta["track"] = "${t.number}/${tracks.size}"
            (sheet.date ?: probe.tag("date") ?: probe.tag("year"))?.let { meta["date"] = it }
            (sheet.genre ?: probe.tag("genre"))?.let { meta["genre"] = it }

            val label = "Track ${i + 1} of ${tracks.size}"
            onProgress(i.toFloat() / tracks.size, label)
            try {
                encode(
                    context, tmpIn, tmpOut, folder, fileName, format, probe.sampleRate,
                    start = t.startSec, duration = duration, meta = meta,
                    progressMs = duration?.let { (it * 1000).toLong() },
                ) { p -> onProgress(p?.let { (i + it) / tracks.size }, label) }
            } catch (e: IOException) {
                throw IOException("Track ${t.number}: ${e.message}")
            }
        }
        onProgress(1f, null)
        return "Split into ${tracks.size} tracks in \"$folderName\""
    }

    /** Order of preference: attached .cue file, CUESHEET tag in the file, embedded chapters. */
    private fun loadSheet(context: Context, item: Item, probe: Probe): CueSheet {
        try {
            if (item.cueUri != null) {
                val bytes = context.contentResolver.openInputStream(item.cueUri)?.use { it.readBytes() }
                    ?: throw IOException("Cannot open ${item.cueName ?: "the CUE file"}")
                return CueParser.parse(CueParser.decode(bytes))
            }
            probe.tag("cuesheet")?.let { return CueParser.parse(it) }
        } catch (e: CueException) {
            throw IOException(e.message)
        }
        if (probe.chapters.size > 1) {
            val tracks = probe.chapters.mapIndexed { i, c ->
                CueTrack(i + 1, c.title, null, c.start, c.end)
            }
            return CueSheet(probe.tag("album"), probe.tag("artist"), probe.tag("date"), probe.tag("genre"), tracks)
        }
        throw IOException("No CUE sheet found. Add the .cue file, or turn off splitting.")
    }

    // ---- encoding --------------------------------------------------------------------------

    private suspend fun encode(
        context: Context,
        input: File,
        tmpOut: File,
        outDir: DocumentFile,
        fileName: String,
        format: OutputFormat,
        sampleRate: Int?,
        start: Double?,
        duration: Double?,
        meta: Map<String, String>?,
        progressMs: Long?,
        onProgress: (Float?) -> Unit,
    ) {
        onProgress(if (progressMs != null) 0f else null)
        tmpOut.delete()
        var session = run(buildArgs(input, tmpOut, format, sampleRate, true, start, duration, meta), progressMs, onProgress)
        if (!ReturnCode.isSuccess(session.returnCode) && !ReturnCode.isCancel(session.returnCode)) {
            // Some odd cover formats break the mux; retry audio-only.
            tmpOut.delete()
            session = run(buildArgs(input, tmpOut, format, sampleRate, false, start, duration, meta), progressMs, onProgress)
        }
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val log = session.allLogsAsString.orEmpty().trim().takeLast(300)
            throw IOException(log.ifBlank { "FFmpeg failed (code ${session.returnCode})" })
        }

        val target = outDir.createFile(format.mime, fileName)
            ?: throw IOException("Cannot create the output file")
        val out = context.contentResolver.openOutputStream(target.uri)
            ?: throw IOException("Cannot write to the output folder")
        out.use { dst -> tmpOut.inputStream().use { it.copyTo(dst) } }
        tmpOut.delete()
        onProgress(1f)
    }

    private fun buildArgs(
        input: File,
        output: File,
        format: OutputFormat,
        sampleRate: Int?,
        withCover: Boolean,
        start: Double?,
        duration: Double?,
        meta: Map<String, String>?,
    ): Array<String> {
        val a = mutableListOf("-y")
        // Seeking before -i is sample-accurate when the audio is re-encoded, which it always is here.
        if (start != null) a += listOf("-ss", seconds(start))
        if (duration != null) a += listOf("-t", seconds(duration))
        a += listOf("-i", input.absolutePath, "-map", "0:a:0")
        if (withCover) a += listOf("-map", "0:v:0?")
        // Embedded chapters would otherwise become ID3 CHAP frames in the MP3.
        a += listOf("-map_chapters", "-1")

        if (meta == null) {
            // Whole-file conversion: keep the original tags, drop the embedded CUE text.
            a += listOf("-map_metadata", "0", "-metadata", "CUESHEET=", "-metadata", "cuesheet=")
        } else {
            a += listOf("-map_metadata", "-1")
            for ((k, v) in meta) a += listOf("-metadata", "$k=$v")
        }

        when (format) {
            OutputFormat.MP3_320 -> a += listOf("-c:a", "libmp3lame", "-b:a", "320k") // CBR
            OutputFormat.MP3_V0 -> a += listOf("-c:a", "libmp3lame", "-q:a", "0")     // VBR V0
            OutputFormat.FLAC -> a += listOf("-c:a", "flac", "-compression_level", "8")
        }

        // MP3 tops out at 48 kHz. Pick 44.1 or 48 so 88.2/176.4 kHz sources stay in the 44.1 family.
        if (format != OutputFormat.FLAC && sampleRate != null && sampleRate > 48000) {
            a += listOf("-ar", if (sampleRate % 44100 == 0) "44100" else "48000")
        }

        if (withCover) {
            a += listOf(
                "-c:v", "copy",
                "-disposition:v:0", "attached_pic",
                "-metadata:s:v", "title=Album cover",
                "-metadata:s:v", "comment=Cover (front)",
            )
        }
        if (format != OutputFormat.FLAC) a += listOf("-id3v2_version", "3", "-write_id3v1", "1")
        a += output.absolutePath
        return a.toTypedArray()
    }

    private suspend fun run(
        args: Array<String>,
        progressMs: Long?,
        onProgress: (Float?) -> Unit,
    ): FFmpegSession = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { s -> if (cont.isActive) cont.resume(s) },
            { /* logs are read from the session on failure */ },
            { stats ->
                if (progressMs != null && progressMs > 0) {
                    onProgress((stats.time.toFloat() / progressMs).coerceIn(0f, 1f))
                }
            },
        )
        cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }

    // ---- probing ---------------------------------------------------------------------------

    private fun probe(file: File): Probe {
        return try {
            val session = FFprobeKit.executeWithArguments(
                arrayOf(
                    "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", "-show_chapters",
                    file.absolutePath,
                )
            )
            val text = session.allLogsAsString.orEmpty()
            val root = JSONObject(text.substring(text.indexOf('{')))

            val tags = linkedMapOf<String, String>()
            val streams = root.optJSONArray("streams")
            var sampleRate: Int? = null
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    if (s.optString("codec_type") == "audio") {
                        if (sampleRate == null) sampleRate = s.optString("sample_rate").toIntOrNull()
                        readTags(s.optJSONObject("tags"), tags)
                    }
                }
            }
            val format = root.optJSONObject("format")
            readTags(format?.optJSONObject("tags"), tags) // format-level tags win
            val duration = format?.optString("duration")?.toDoubleOrNull()

            val chapters = mutableListOf<Chapter>()
            val ch = root.optJSONArray("chapters")
            if (ch != null) {
                for (i in 0 until ch.length()) {
                    val c = ch.getJSONObject(i)
                    val s = c.optString("start_time").toDoubleOrNull() ?: continue
                    val e = c.optString("end_time").toDoubleOrNull() ?: continue
                    chapters += Chapter(s, e, c.optJSONObject("tags")?.optString("title")?.takeIf { it.isNotBlank() })
                }
            }
            Probe(duration, sampleRate, tags, chapters)
        } catch (_: Exception) {
            Probe(null, null, emptyMap(), emptyList())
        }
    }

    private fun readTags(obj: JSONObject?, into: MutableMap<String, String>) {
        obj ?: return
        for (k in obj.keys()) into[k] = obj.optString(k)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun seconds(v: Double) = String.format(Locale.US, "%.6f", v)

    /** Safe file or folder name: no path separators or characters FAT/exFAT cards reject. */
    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(120)
        return cleaned.ifBlank { "untitled" }
    }
}
