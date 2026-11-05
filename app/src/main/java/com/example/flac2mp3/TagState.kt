package com.example.flac2mp3

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TagFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    /** Null until the file has been read. */
    val original: Map<TagField, String>? = null,
    /** What the fields currently say; starts as the original tags. */
    val edited: Map<TagField, String> = emptyMap(),
    val hasCover: Boolean = false,
    val status: Status = Status.QUEUED,
    val detail: String? = null,
)

data class TagsUiState(
    val files: List<TagFile> = emptyList(),
    /** Values that replace the field in every file when not blank. */
    val batch: Map<TagField, String> = emptyMap(),
    val coverName: String? = null,
    val removeCover: Boolean = false,
    val replaceOriginals: Boolean = false,
    val running: Boolean = false,
    val message: String? = null,
)

/** Process-wide state of the Tags tab. */
object TagState {
    private val _state = MutableStateFlow(TagsUiState())
    val state: StateFlow<TagsUiState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var nextId = 0L
    private var coverFile: File? = null
    private var job: Job? = null

    fun addFiles(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        var skipped = 0
        val added = mutableListOf<TagFile>()
        for (uri in uris) {
            val name = ConverterState.displayName(app, uri)
            val known = _state.value.files.any { it.uri == uri } || added.any { it.uri == uri }
            if (known || !TagEditor.isSupported(name)) {
                skipped++
                continue
            }
            added += TagFile(nextId++, uri, name)
        }
        if (added.isNotEmpty()) _state.update { it.copy(files = it.files + added) }
        if (skipped > 0) {
            _state.update {
                it.copy(message = "Skipped $skipped file(s) that were not new FLAC, MP3, M4A, OGG, OPUS or WAV files.")
            }
        }
        for (file in added) {
            scope.launch {
                try {
                    val info = TagEditor.read(app, file.uri, file.name)
                    updateFile(file.id) {
                        it.copy(original = info.tags, edited = info.tags, hasCover = info.hasCover)
                    }
                } catch (e: Exception) {
                    updateFile(file.id) {
                        it.copy(status = Status.FAILED, detail = e.message ?: "Cannot read this file")
                    }
                }
            }
        }
    }

    fun edit(id: Long, field: TagField, value: String) =
        updateFile(id) { it.copy(edited = it.edited + (field to value), status = Status.QUEUED, detail = null) }

    fun setBatch(field: TagField, value: String) =
        _state.update { it.copy(batch = it.batch + (field to value)) }

    fun remove(id: Long) = _state.update { s -> s.copy(files = s.files.filterNot { it.id == id }) }

    fun clear() {
        coverFile?.delete()
        coverFile = null
        _state.update { TagsUiState(replaceOriginals = it.replaceOriginals) }
    }

    fun setReplace(replace: Boolean) = _state.update { it.copy(replaceOriginals = replace) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setCover(context: Context, uri: Uri?) {
        coverFile?.delete()
        coverFile = null
        if (uri == null) {
            _state.update { it.copy(coverName = null) }
            return
        }
        val app = context.applicationContext
        scope.launch {
            val name = ConverterState.displayName(app, uri)
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val ext = name.substringAfterLast('.', "jpg").lowercase().take(5)
                    val f = File(app.cacheDir, "tag_cover_${System.nanoTime()}.$ext")
                    app.contentResolver.openInputStream(uri)?.use { src -> f.outputStream().use { src.copyTo(it) } }
                    f.takeIf { it.length() > 0 }
                }.getOrNull()
            }
            if (copy == null) {
                _state.update { it.copy(message = "Cannot read that image") }
            } else {
                coverFile = copy
                _state.update { it.copy(coverName = name, removeCover = false) }
            }
        }
    }

    fun setRemoveCover(remove: Boolean) {
        if (remove) {
            coverFile?.delete()
            coverFile = null
        }
        _state.update { it.copy(removeCover = remove, coverName = if (remove) null else it.coverName) }
    }

    /** The tags that would be written for [file]: its own edits, overridden by the batch values. */
    private fun finalFields(file: TagFile, batch: Map<TagField, String>): Map<TagField, String> {
        val original = file.original ?: return emptyMap()
        val changes = linkedMapOf<TagField, String>()
        for (field in TagField.entries) {
            val wanted = batch[field]?.takeIf { field.batch && it.isNotBlank() }?.trim()
                ?: file.edited[field].orEmpty().trim()
            if (wanted != original[field].orEmpty()) changes[field] = wanted
        }
        return changes
    }

    fun save(context: Context) {
        val app = context.applicationContext
        val s = _state.value
        if (s.running) return
        val ready = s.files.filter { it.original != null && it.status != Status.DONE }
        if (ready.isEmpty()) {
            _state.update { it.copy(message = "Nothing to save") }
            return
        }
        val outDir = if (s.replaceOriginals) null else {
            val uri = ConverterState.state.value.outputDir
            if (uri == null) {
                _state.update { it.copy(message = "Choose an output folder, or turn on Replace originals") }
                return
            }
            DocumentFile.fromTreeUri(app, uri)
        }
        if (!s.replaceOriginals && outDir == null) {
            _state.update { it.copy(message = "Cannot open the output folder") }
            return
        }

        val cover = coverFile
        job = scope.launch {
            _state.update { it.copy(running = true) }
            var ok = 0
            var failed = 0
            for (file in ready) {
                updateFile(file.id) { it.copy(status = Status.RUNNING, detail = null) }
                val fields = finalFields(file, s.batch)
                val coverChange = cover != null || (s.removeCover && file.hasCover)
                if (fields.isEmpty() && !coverChange) {
                    updateFile(file.id) { it.copy(status = Status.DONE, detail = "No changes") }
                    continue
                }
                try {
                    val change = TagChange(fields, cover, s.removeCover, file.hasCover)
                    val detail = TagEditor.write(app, file.uri, file.name, change, outDir)
                    ok++
                    updateFile(file.id) {
                        it.copy(
                            status = Status.DONE,
                            detail = detail,
                            original = (it.original.orEmpty() + fields.filterValues { v -> v.isNotEmpty() }) -
                                fields.filterValues { v -> v.isEmpty() }.keys,
                            hasCover = cover != null || (it.hasCover && !s.removeCover),
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    updateFile(file.id) { it.copy(status = Status.FAILED, detail = e.message ?: "Failed") }
                }
            }
            _state.update {
                it.copy(
                    running = false,
                    message = if (failed == 0) "Saved $ok file(s)" else "Saved $ok, $failed failed",
                )
            }
        }
    }

    private fun updateFile(id: Long, transform: (TagFile) -> TagFile) = _state.update { s ->
        s.copy(files = s.files.map { if (it.id == id) transform(it) else it })
    }
}
