package com.example.flac2mp3

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

enum class OutputFormat(
    val shortLabel: String,
    val description: String,
    val ext: String,
    val mime: String,
) {
    MP3_320("MP3 320", "MP3, 320 kbps constant bitrate", "mp3", "audio/mpeg"),
    MP3_V0("MP3 V0", "MP3, V0 variable bitrate (about 245 kbps)", "mp3", "audio/mpeg"),
    FLAC("FLAC", "FLAC, lossless", "flac", "audio/flac"),
}

data class Item(
    val id: Long,
    val uri: Uri,
    val name: String,
    val cueUri: Uri? = null,
    val cueName: String? = null,
    val status: Status = Status.QUEUED,
    /** 0..1, or null when the duration is unknown (indeterminate). */
    val progress: Float? = 0f,
    /** Short second line: current track while running, result summary when done. */
    val detail: String? = null,
    val error: String? = null,
)

data class AppState(
    val items: List<Item> = emptyList(),
    val outputDir: Uri? = null,
    val running: Boolean = false,
    val format: OutputFormat = OutputFormat.MP3_320,
    val split: Boolean = false,
)

data class AddResult(val added: Int, val cuesAttached: Int, val skipped: Int)

/** Process-wide state shared between the UI and the foreground service. */
object ConverterState {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var nextId = 0L

    private const val PREFS = "prefs"
    private const val KEY_OUTPUT = "output_uri"
    private const val KEY_FORMAT = "format"
    private const val KEY_SPLIT = "split"

    /** Loads saved settings once per process. */
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val format = prefs.getString(KEY_FORMAT, null)
            ?.let { name -> OutputFormat.entries.firstOrNull { it.name == name } }
        val split = prefs.getBoolean(KEY_SPLIT, false)
        val saved = prefs.getString(KEY_OUTPUT, null)?.let { Uri.parse(it) }
        val granted = saved != null &&
            context.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
        _state.update {
            it.copy(
                format = format ?: it.format,
                split = split,
                outputDir = if (granted) saved else it.outputDir,
            )
        }
    }

    fun setOutputDir(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OUTPUT, uri?.toString()).apply()
        _state.update { it.copy(outputDir = uri) }
    }

    fun setFormat(context: Context, format: OutputFormat) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FORMAT, format.name).apply()
        _state.update { it.copy(format = format) }
    }

    fun setSplit(context: Context, split: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SPLIT, split).apply()
        _state.update { it.copy(split = split) }
    }

    fun setRunning(running: Boolean) = _state.update { it.copy(running = running) }

    private fun isAudio(name: String) =
        name.endsWith(".flac", ignoreCase = true) || name.endsWith(".wav", ignoreCase = true)

    private fun isCue(name: String) = name.endsWith(".cue", ignoreCase = true)

    private fun stripAudioExt(base: String): String {
        val lower = base.lowercase()
        return when {
            lower.endsWith(".flac") -> base.dropLast(5)
            lower.endsWith(".wav") -> base.dropLast(4)
            else -> base
        }
    }

    /**
     * Adds FLAC/WAV files and pairs any .cue files picked with them
     * (same base name, or the only audio file without a CUE sheet).
     */
    fun addFiles(context: Context, uris: List<Uri>): AddResult {
        val audio = mutableListOf<Pair<Uri, String>>()
        val cues = mutableListOf<Pair<Uri, String>>()
        var skipped = 0
        for (uri in uris) {
            val name = displayName(context, uri)
            when {
                isAudio(name) -> audio += uri to name
                isCue(name) -> cues += uri to name
                else -> skipped++
            }
        }

        val current = _state.value.items
        val newItems = mutableListOf<Item>()
        for ((uri, name) in audio) {
            if (current.any { it.uri == uri } || newItems.any { it.uri == uri }) {
                skipped++
                continue
            }
            newItems += Item(id = nextId++, uri = uri, name = name)
        }

        val items = (current + newItems).toMutableList()
        val newIds = newItems.map { it.id }.toSet()
        var attached = 0
        for ((cueUri, cueName) in cues) {
            val cueBase = stripAudioExt(cueName.substringBeforeLast('.'))
            var idx = items.indexOfFirst {
                it.cueUri == null && it.name.substringBeforeLast('.').equals(cueBase, ignoreCase = true)
            }
            if (idx < 0 && cues.size == 1) {
                val candidates = items.indices.filter {
                    items[it].cueUri == null && (newIds.isEmpty() || items[it].id in newIds)
                }
                if (candidates.size == 1) idx = candidates[0]
            }
            if (idx >= 0) {
                items[idx] = items[idx].copy(cueUri = cueUri, cueName = cueName)
                attached++
            } else {
                skipped++
            }
        }

        if (newItems.isNotEmpty() || attached > 0) _state.update { it.copy(items = items) }
        return AddResult(newItems.size, attached, skipped)
    }

    fun attachCue(context: Context, id: Long, uri: Uri): Boolean {
        val name = displayName(context, uri)
        if (!isCue(name)) return false
        update(id) { it.copy(cueUri = uri, cueName = name, status = Status.QUEUED, error = null) }
        return true
    }

    fun remove(id: Long) = _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }

    fun clearFinished() = _state.update { s -> s.copy(items = s.items.filterNot { it.status == Status.DONE }) }

    fun update(id: Long, transform: (Item) -> Item) = _state.update { s ->
        s.copy(items = s.items.map { if (it.id == id) transform(it) else it })
    }

    /** Called at the start of a run: everything not finished goes back to the queue. */
    fun requeueUnfinished() = _state.update { s ->
        s.copy(items = s.items.map {
            if (it.status == Status.DONE) it
            else it.copy(status = Status.QUEUED, progress = 0f, detail = null, error = null)
        })
    }

    /** Called when a run is cancelled. */
    fun cancelPending() = _state.update { s ->
        s.copy(items = s.items.map {
            if (it.status == Status.RUNNING || it.status == Status.QUEUED) {
                it.copy(status = Status.CANCELLED, detail = null)
            } else it
        })
    }

    fun failAll(message: String) = _state.update { s ->
        s.copy(items = s.items.map {
            if (it.status == Status.DONE) it else it.copy(status = Status.FAILED, error = message)
        })
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
}
