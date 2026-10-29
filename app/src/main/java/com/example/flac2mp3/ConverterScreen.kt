package com.example.flac2mp3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen() {
    val ctx = LocalContext.current
    val state by ConverterState.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var cueTarget by remember { mutableStateOf<Long?>(null) }

    val pending = state.items.count { it.status != Status.DONE }
    val finished = state.items.count { it.status == Status.DONE }

    fun startService() {
        ContextCompat.startForegroundService(ctx, Intent(ctx, ConversionService::class.java))
    }

    fun takeReadAccess(uris: List<android.net.Uri>) {
        uris.forEach {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startService() } // Convert either way; the notification is optional.

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        takeReadAccess(uris)
        val r = ConverterState.addFiles(ctx, uris)
        val message = buildString {
            if (r.cuesAttached > 0) append("${r.cuesAttached} CUE sheet(s) attached. ")
            if (r.skipped > 0) append("Skipped ${r.skipped} file(s) that were not new FLAC, WAV or matching CUE files.")
        }.trim()
        if (message.isNotEmpty()) scope.launch { snackbar.showSnackbar(message) }
    }

    val pickCue = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = cueTarget
        cueTarget = null
        if (uri != null && target != null) {
            takeReadAccess(listOf(uri))
            if (!ConverterState.attachCue(ctx, target, uri)) {
                scope.launch { snackbar.showSnackbar("That is not a .cue file") }
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ConverterState.setOutputDir(ctx, uri)
        }
    }

    fun convert() {
        if (state.outputDir == null) {
            scope.launch { snackbar.showSnackbar("Choose an output folder first") }
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startService()
    }

    fun cancel() {
        ctx.startService(
            Intent(ctx, ConversionService::class.java).setAction(ConversionService.ACTION_CANCEL)
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Text("Flac Transcoder") }, scrollBehavior = scrollBehavior)
        },
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
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (state.running) {
                        val current = state.items.count { it.status == Status.DONE } + 1
                        Text(
                            "Working on file $current of ${state.items.size}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        OutlinedButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    } else {
                        val verb = if (state.split) "Split and convert" else "Convert"
                        Button(
                            onClick = { convert() },
                            enabled = pending > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (pending == 1) "$verb 1 file" else "$verb $pending files")
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
                SettingsCard(
                    folderLabel = state.outputDir?.let { DocumentFile.fromTreeUri(ctx, it)?.name ?: "Selected folder" },
                    format = state.format,
                    split = state.split,
                    enabled = !state.running,
                    onChooseFolder = { pickFolder.launch(null) },
                    onFormat = { ConverterState.setFormat(ctx, it) },
                    onSplit = { ConverterState.setSplit(ctx, it) },
                )
            }

            if (state.items.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No files yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap Add files and pick FLAC or WAV files. To split a single-file album, pick its .cue file in the same selection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${state.items.size} file(s)",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        if (finished > 0 && !state.running) {
                            TextButton(onClick = { ConverterState.clearFinished() }) { Text("Clear finished") }
                        }
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    FileCard(
                        item = item,
                        split = state.split,
                        running = state.running,
                        onAddCue = {
                            cueTarget = item.id
                            pickCue.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(
    folderLabel: String?,
    format: OutputFormat,
    split: Boolean,
    enabled: Boolean,
    onChooseFolder: () -> Unit,
    onFormat: (OutputFormat) -> Unit,
    onSplit: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 16.dp, end = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        folderLabel ?: "No output folder",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Output folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                TextButton(onClick = onChooseFolder, enabled = enabled) {
                    Text(if (folderLabel == null) "Choose folder" else "Change")
                }
            }

            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                OutputFormat.entries.forEachIndexed { index, f ->
                    SegmentedButton(
                        selected = format == f,
                        onClick = { onFormat(f) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, OutputFormat.entries.size),
                    ) { Text(f.shortLabel) }
                }
            }
            Text(
                format.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Split single-file albums", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Cuts each file into tracks using its CUE sheet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Switch(checked = split, onCheckedChange = onSplit, enabled = enabled)
            }
        }
    }
}

@Composable
private fun FileCard(
    item: Item,
    split: Boolean,
    running: Boolean,
    onAddCue: () -> Unit,
) {
    val idle = item.status != Status.RUNNING && item.status != Status.DONE
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val isError = item.status == Status.FAILED
                    Text(
                        statusText(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (split && item.cueName != null) {
                        Text(
                            "CUE sheet: ${item.cueName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (item.status) {
                    Status.DONE -> Icon(
                        Icons.Default.CheckCircle, contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Status.FAILED -> Icon(
                        Icons.Default.Warning, contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    else -> Unit
                }
                if (!running || item.status != Status.RUNNING) {
                    IconButton(onClick = { ConverterState.remove(item.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ${item.name}")
                    }
                }
            }
            if (split && idle && !running) {
                TextButton(onClick = onAddCue) {
                    Text(if (item.cueName == null) "Add CUE sheet" else "Change CUE sheet")
                }
            }
            if (item.status == Status.RUNNING) {
                Box(Modifier.padding(top = 8.dp, end = 8.dp)) {
                    val p = item.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun statusText(item: Item): String = when (item.status) {
    Status.QUEUED -> "Waiting"
    Status.RUNNING -> {
        val pct = item.progress?.let { "${(it * 100).toInt()}%" }
        listOfNotNull(item.detail ?: "Converting", pct).joinToString(", ")
    }
    Status.DONE -> item.detail ?: "Done"
    Status.FAILED -> item.error ?: "Conversion failed"
    Status.CANCELLED -> "Cancelled"
}
