package com.example.flac2mp3

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Picks audio files with write access, so "Replace originals" can write over them. */
private class OpenDocumentsForWrite : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen() {
    val ctx = LocalContext.current
    val state by TagState.state.collectAsStateWithLifecycle()
    val converter by ConverterState.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            TagState.consumeMessage()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(OpenDocumentsForWrite()) { uris ->
        uris.forEach {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.recoverCatching {
                ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        TagState.addFiles(ctx, uris)
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) TagState.setCover(ctx, uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ConverterState.setOutputDir(ctx, uri)
        }
    }

    val pending = state.files.count { it.original != null && it.status != Status.DONE }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Edit tags") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!state.running) {
                ExtendedFloatingActionButton(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add files") },
                )
            }
        },
        bottomBar = {
            androidx.compose.material3.Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (state.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Button(
                            onClick = { TagState.save(ctx) },
                            enabled = pending > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (pending == 1) "Save 1 file" else "Save $pending files")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutputCard(
                    replace = state.replaceOriginals,
                    folderLabel = converter.outputDir?.let { DocumentFile.fromTreeUri(ctx, it)?.name ?: "Selected folder" },
                    enabled = !state.running,
                    onReplace = { TagState.setReplace(it) },
                    onChooseFolder = { pickFolder.launch(null) },
                )
            }
            if (state.files.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 8.dp)) {
                        Text("No files yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap Add files and pick songs to edit. Several songs from one album can be changed together.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item {
                    BatchCard(
                        count = state.files.size,
                        batch = state.batch,
                        coverName = state.coverName,
                        removeCover = state.removeCover,
                        enabled = !state.running,
                        onChange = { f, v -> TagState.setBatch(f, v) },
                        onPickCover = { pickCover.launch(arrayOf("image/*")) },
                        onClearCover = { TagState.setCover(ctx, null) },
                        onRemoveCover = { TagState.setRemoveCover(it) },
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${state.files.size} file(s)",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        if (!state.running) TextButton(onClick = { TagState.clear() }) { Text("Clear all") }
                    }
                }
                items(state.files, key = { it.id }) { file ->
                    TagFileCard(file, enabled = !state.running)
                }
            }
        }
    }
}

@Composable
private fun OutputCard(
    replace: Boolean,
    folderLabel: String?,
    enabled: Boolean,
    onReplace: (Boolean) -> Unit,
    onChooseFolder: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Replace originals", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (replace) "Songs are changed in place. The original is restored if writing fails."
                        else "Edited copies are saved to the output folder. Originals stay as they are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = replace, onCheckedChange = onReplace, enabled = enabled)
            }
            if (!replace) {
                OutlinedButton(onClick = onChooseFolder, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(folderLabel?.let { "Output folder: $it" } ?: "Choose output folder")
                }
            }
        }
    }
}

@Composable
private fun BatchCard(
    count: Int,
    batch: Map<TagField, String>,
    coverName: String?,
    removeCover: Boolean,
    enabled: Boolean,
    onChange: (TagField, String) -> Unit,
    onPickCover: () -> Unit,
    onClearCover: () -> Unit,
    onRemoveCover: (Boolean) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(count > 1) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Apply to all $count file(s)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Anything filled in here replaces that tag in every file. Leave blank to keep each file's own.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { open = !open }) {
                    Icon(
                        if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (open) "Collapse" else "Expand",
                    )
                }
            }
            if (open) {
                val fields = TagField.entries.filter { it.batch }
                fields.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { f ->
                            OutlinedTextField(
                                value = batch[f].orEmpty(),
                                onValueChange = { onChange(f, it) },
                                label = { Text(f.label) },
                                singleLine = true,
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickCover, enabled = enabled && !removeCover) {
                        Text(if (coverName == null) "Choose cover" else "Cover: ${coverName.take(18)}")
                    }
                    if (coverName != null) {
                        IconButton(onClick = onClearCover, enabled = enabled) {
                            Icon(Icons.Default.Close, contentDescription = "Clear cover choice")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remove cover art", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = removeCover, onCheckedChange = onRemoveCover, enabled = enabled)
                }
            }
        }
    }
}

@Composable
private fun TagFileCard(file: TagFile, enabled: Boolean) {
    var open by rememberSaveable(file.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        file.edited[TagField.TITLE]?.takeIf { it.isNotBlank() } ?: file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(file.edited[TagField.ARTIST], file.edited[TagField.ALBUM])
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    Text(
                        when {
                            file.original == null && file.status != Status.FAILED -> "Reading tags…"
                            file.status == Status.FAILED || file.status == Status.DONE -> file.detail ?: ""
                            else -> sub.ifBlank { file.name }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (file.status == Status.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (file.original != null) {
                    IconButton(onClick = { open = !open }) {
                        Icon(
                            if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (open) "Collapse" else "Edit tags",
                        )
                    }
                }
                IconButton(onClick = { TagState.remove(file.id) }, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Remove ${file.name}")
                }
            }
            if (open && file.original != null) {
                Column(
                    Modifier.padding(end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TagField.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { f ->
                                OutlinedTextField(
                                    value = file.edited[f].orEmpty(),
                                    onValueChange = { TagState.edit(file.id, f, it) },
                                    label = { Text(f.label) },
                                    singleLine = true,
                                    enabled = enabled,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Text(
                        if (file.hasCover) "Has cover art" else "No cover art",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
