package com.example.flac2mp3

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

object Converter {

    /**
     * Encodes that may run at the same time, shared by every file and every split track.
     * The encoders are single-threaded, so this is what uses the phone's other cores.
     * Half the cores, because the other half are usually the slow ones.
     */
    val parallelism: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    private val slots = Semaphore(parallelism)
    private val folderLock = Mutex()

    private const val MAX_COVER_BYTES = 4L * 1024 * 1024
    private val TEMP_PREFIXES = listOf("in_", "out_", "cover_", "meta_")

    private class Chapter(val start: Double, val end: Double, val title: String?)

    private class Cover(val codec: String, val width: Int, val height: Int)

    private class Probe(
        val durationSec: Double?,
        val sampleRate: Int?,
        val tags: Map<String, String>,
        val chapters: List<Chapter>,
        val cover: Cover?,
    ) {
        fun tag(key: String): String? =
            tags.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
    }

    /** Where FFmpeg reads the audio from: the picked document itself, or a copy in the cache dir. */
    private class Source(private val context: Context, private val uri: Uri, private val copy: File?) {
        /** A fresh input argument for one FFmpeg or FFprobe run. Document arguments are single-use. */
        fun arg(): String = copy?.absolutePath ?: FFmpegKitConfig.getSafParameterForRead(context, uri)
    }

    private class TrackJob(
        val track: CueTrack,
        val duration: Double?,
        val fileName: String,
        val meta: Map<String, String>,
    )

    /** Removes temp files left behind if the app was killed mid-conversion. */
    fun clearTemp(context: Context) {
        context.cacheDir.listFiles()?.forEach { f ->
            if (TEMP_PREFIXES.any { f.name.startsWith(it) }) f.delete()
        }
    }

    /**
     * Converts one FLAC or WAV file. Whole file: one output next to the others in [outDir].
     * Split: one output per CUE track, written to a subfolder of [outDir].
     * Returns a short summary for the UI. [onProgress] may be called from any thread.
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
            throw IOException("Already FLAC. Pick another format, or turn on splitting.")
        }

        val tmpIn = File(context.cacheDir, "in_${item.id}.${if (isFlac) "flac" else "wav"}")
        val metaFile = File(context.cacheDir, "meta_${item.id}.txt")
        try {
            val (source, probe) = openSource(context, item, tmpIn)
            // Ogg cannot take the cover as a picture stream; it goes in as a tag, prepared once per file.
            val pictureFile = if (format == OutputFormat.VORBIS) {
                writePictureTag(context, source, probe, item.id, metaFile)
            } else null

            if (split) {
                splitAndEncode(context, item, source, pictureFile, outDir, format, probe, onProgress)
            } else {
                val name = sanitize(item.name.substringBeforeLast('.')) + "." + format.ext
                encode(
                    context, source, pictureFile, File(context.cacheDir, "out_${item.id}.${format.ext}"),
                    outDir, name, format, probe.sampleRate,
                    start = null, duration = null, meta = null,
                    progressMs = probe.durationSec?.let { (it * 1000).toLong() },
                ) { p -> onProgress(p, null) }
                "Saved as ${format.shortLabel}"
            }
        } finally {
            tmpIn.delete()
            metaFile.delete()
        }
    }

    // ---- input -----------------------------------------------------------------------------

    /**
     * Reads the document in place when it is an ordinary seekable file and FFmpeg can open it.
     * Otherwise (cloud providers, pipes) falls back to copying it into the cache dir first.
     */
    private fun openSource(context: Context, item: Item, tmpIn: File): Pair<Source, Probe> {
        if (isSeekable(context, item.uri)) {
            val direct = Source(context, item.uri, null)
            val probed = try {
                probe(direct.arg())
            } catch (_: Exception) {
                null
            }
            if (probed?.sampleRate != null) return direct to probed
        }
        val input = context.contentResolver.openInputStream(item.uri)
            ?: throw IOException("Cannot open ${item.name}")
        input.use { src -> tmpIn.outputStream().use { src.copyTo(it) } }
        return Source(context, item.uri, tmpIn) to probe(tmpIn.absolutePath)
    }

    private fun isSeekable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        } ?: false
    } catch (_: Exception) {
        false
    }

    // ---- splitting -------------------------------------------------------------------------

    private suspend fun splitAndEncode(
        context: Context,
        item: Item,
        source: Source,
        pictureFile: File?,
        outDir: DocumentFile,
        format: OutputFormat,
        probe: Probe,
        onProgress: (Float?, String?) -> Unit,
    ): String {
        val sheet = loadSheet(context, item, probe)
        val tracks = sheet.tracks

        // Validate everything before the first encode, so a wrong CUE sheet fails without writing files.
        val jobs = tracks.map { t ->
            val end = t.endSec ?: probe.durationSec
            val duration = end?.minus(t.startSec)
            if (duration != null && duration <= 0.0) {
                throw IOException("Track ${t.number} starts after the end of the audio. Is this the right CUE sheet?")
            }
            val title = t.title ?: "Track %02d".format(Locale.US, t.number)
            val meta = linkedMapOf<String, String>()
            meta["title"] = title
            (t.performer ?: sheet.performer)?.let { meta["artist"] = it }
            sheet.performer?.let { meta["album_artist"] = it }
            (sheet.album ?: probe.tag("album"))?.let { meta["album"] = it }
            meta["track"] = "${t.number}/${tracks.size}"
            (sheet.date ?: probe.tag("date") ?: probe.tag("year"))?.let { meta["date"] = it }
            (sheet.genre ?: probe.tag("genre"))?.let { meta["genre"] = it }
            TrackJob(t, duration, "%02d - %s.%s".format(Locale.US, t.number, sanitize(title), format.ext), meta)
        }

        val folderName = sanitize(
            listOfNotNull(sheet.performer, sheet.album).joinToString(" - ")
                .ifBlank { item.name.substringBeforeLast('.') }
        )
        // Locked so two albums with the same name do not race to create the folder.
        val folder = folderLock.withLock {
            outDir.findFile(folderName)?.takeIf { it.isDirectory }
                ?: outDir.createDirectory(folderName)
                ?: throw IOException("Cannot create the folder \"$folderName\"")
        }

        val perTrack = FloatArray(jobs.size)
        val finished = AtomicInteger(0)
        fun report(index: Int, value: Float) {
            val total = synchronized(perTrack) {
                perTrack[index] = value
                perTrack.sum()
            }
            onProgress(total / jobs.size, "${finished.get()} of ${jobs.size} tracks done")
        }
        onProgress(0f, "0 of ${jobs.size} tracks done")

        // Tracks are independent, so they encode side by side (bounded by the shared slots).
        coroutineScope {
            jobs.mapIndexed { i, job ->
                async {
                    try {
                        encode(
                            context, source, pictureFile,
                            File(context.cacheDir, "out_${item.id}_$i.${format.ext}"),
                            folder, job.fileName, format, probe.sampleRate,
                            start = job.track.startSec, duration = job.duration, meta = job.meta,
                            progressMs = job.duration?.let { (it * 1000).toLong() },
                        ) { p -> report(i, p ?: 0f) }
                    } catch (e: IOException) {
                        throw IOException("Track ${job.track.number}: ${e.message}")
                    }
                    finished.incrementAndGet()
                    report(i, 1f)
                }
            }.awaitAll()
        }
        onProgress(1f, null)
        return "Split into ${jobs.size} tracks in \"$folderName\""
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
        source: Source,
        pictureFile: File?,
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
    ) = slots.withPermit {
        try {
            onProgress(if (progressMs != null) 0f else null)
            tmpOut.delete()
            var session = run(
                buildArgs(source.arg(), pictureFile, tmpOut, format, sampleRate, true, start, duration, meta),
                progressMs, onProgress,
            )
            if (!ReturnCode.isSuccess(session.returnCode) && !ReturnCode.isCancel(session.returnCode)) {
                // Some odd cover formats break the mux; retry audio-only.
                tmpOut.delete()
                session = run(
                    buildArgs(source.arg(), pictureFile, tmpOut, format, sampleRate, false, start, duration, meta),
                    progressMs, onProgress,
                )
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
            onProgress(1f)
        } finally {
            tmpOut.delete()
        }
    }

    private fun buildArgs(
        input: String,
        pictureFile: File?,
        output: File,
        format: OutputFormat,
        sampleRate: Int?,
        withCover: Boolean,
        start: Double?,
        duration: Double?,
        meta: Map<String, String>?,
    ): Array<String> {
        val vorbis = format == OutputFormat.VORBIS
        // Ogg takes the cover from the metadata file; every other format maps the picture stream.
        val pictureInput = if (vorbis && withCover) pictureFile else null
        val coverStream = withCover && !vorbis

        val a = mutableListOf("-y")
        // Seeking before -i is sample-accurate when the audio is re-encoded, which it always is here.
        if (start != null) a += listOf("-ss", seconds(start))
        if (duration != null) a += listOf("-t", seconds(duration))
        a += listOf("-i", input)
        if (pictureInput != null) a += listOf("-i", pictureInput.absolutePath)
        a += listOf("-map", "0:a:0")
        if (coverStream) a += listOf("-map", "0:v:0?")
        // Embedded chapters would otherwise become ID3 CHAP frames in the MP3.
        a += listOf("-map_chapters", "-1")

        if (meta == null) {
            // Whole-file conversion: keep the original tags, drop the embedded CUE text.
            a += listOf("-map_metadata", "0")
            if (pictureInput != null) a += listOf("-map_metadata", "1")
            a += listOf("-metadata", "CUESHEET=", "-metadata", "cuesheet=")
        } else {
            a += listOf("-map_metadata", if (pictureInput != null) "1" else "-1")
            for ((k, v) in meta) a += listOf("-metadata", "$k=$v")
        }

        when (format) {
            OutputFormat.MP3_320 -> a += listOf("-c:a", "libmp3lame", "-b:a", "320k") // CBR
            OutputFormat.MP3_V0 -> a += listOf("-c:a", "libmp3lame", "-q:a", "0")     // VBR V0
            OutputFormat.MP3_V2 -> a += listOf("-c:a", "libmp3lame", "-q:a", "2")     // VBR V2
            OutputFormat.VORBIS -> a += listOf("-c:a", "libvorbis", "-q:a", "8")
            OutputFormat.FLAC -> a += listOf("-c:a", "flac", "-compression_level", "8")
        }

        // Lossy outputs stay at 48 kHz or below (MP3 cannot go higher).
        // 88.2/176.4 kHz sources go to 44.1 so they stay in the same sample-rate family.
        if (format.lossy && sampleRate != null && sampleRate > 48000) {
            a += listOf("-ar", if (sampleRate % 44100 == 0) "44100" else "48000")
        }

        if (coverStream) {
            a += listOf(
                "-c:v", "copy",
                "-disposition:v:0", "attached_pic",
                "-metadata:s:v", "title=Album cover",
                "-metadata:s:v", "comment=Cover (front)",
            )
        }
        if (format.ext == "mp3") a += listOf("-id3v2_version", "3", "-write_id3v1", "1")
        a += output.absolutePath
        return a.toTypedArray()
    }

    /**
     * Ogg Vorbis stores cover art as a METADATA_BLOCK_PICTURE tag: a base64 FLAC picture block.
     * Extracts the source's cover and writes that tag to an FFmpeg metadata file.
     * Returns null (no cover) if there is none or anything goes wrong; the audio still converts.
     */
    private suspend fun writePictureTag(
        context: Context,
        source: Source,
        probe: Probe,
        id: Long,
        metaFile: File,
    ): File? {
        val cover = probe.cover ?: return null
        val (ext, mime) = when (cover.codec) {
            "mjpeg" -> "jpg" to "image/jpeg"
            "png" -> "png" to "image/png"
            else -> return null
        }
        val image = File(context.cacheDir, "cover_$id.$ext")
        return try {
            val session = run(
                arrayOf(
                    "-y", "-i", source.arg(), "-an", "-map", "0:v:0", "-c:v", "copy",
                    "-frames:v", "1", "-update", "1", image.absolutePath,
                ),
                null,
            ) {}
            if (!ReturnCode.isSuccess(session.returnCode)) return null
            if (!image.exists() || image.length() == 0L || image.length() > MAX_COVER_BYTES) return null

            val data = image.readBytes()
            val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
            // type, mime length, mime, description length, width, height, depth, colours, data length, data
            val block = ByteBuffer.allocate(32 + mimeBytes.size + data.size)
                .putInt(3) // front cover
                .putInt(mimeBytes.size).put(mimeBytes)
                .putInt(0)
                .putInt(cover.width).putInt(cover.height).putInt(24).putInt(0)
                .putInt(data.size).put(data)
            val value = Base64.encodeToString(block.array(), Base64.NO_WRAP).replace("=", "\\=")
            metaFile.writeText(";FFMETADATA1\nMETADATA_BLOCK_PICTURE=$value\n", Charsets.US_ASCII)
            metaFile
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            image.delete()
        }
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

    private fun probe(input: String): Probe {
        return try {
            val session = FFprobeKit.executeWithArguments(
                arrayOf(
                    "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", "-show_chapters",
                    input,
                )
            )
            val text = session.allLogsAsString.orEmpty()
            val root = JSONObject(text.substring(text.indexOf('{')))

            val tags = linkedMapOf<String, String>()
            val streams = root.optJSONArray("streams")
            var sampleRate: Int? = null
            var cover: Cover? = null
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    when (s.optString("codec_type")) {
                        "audio" -> {
                            if (sampleRate == null) sampleRate = s.optString("sample_rate").toIntOrNull()
                            readTags(s.optJSONObject("tags"), tags)
                        }
                        "video" -> if (cover == null) {
                            cover = Cover(s.optString("codec_name"), s.optInt("width"), s.optInt("height"))
                        }
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
            Probe(duration, sampleRate, tags, chapters, cover)
        } catch (_: Exception) {
            Probe(null, null, emptyMap(), emptyList(), null)
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
