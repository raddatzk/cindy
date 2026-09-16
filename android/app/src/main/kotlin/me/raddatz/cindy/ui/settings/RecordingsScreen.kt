package me.raddatz.cindy.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.ui.components.BackTopBar
import me.raddatz.cindy.ui.components.OnResume
import me.raddatz.cindy.ui.components.startSafely
import me.raddatz.cindy.ui.history.EmptyState
import me.raddatz.cindy.ui.text.Formats
import java.io.File

/** CSV recordings in `filesDir/DebugLogs`, newest first, with share and delete (iOS `RecordingsView`). */
@Composable
fun RecordingsScreen(model: AppModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val directory = model.container.debugLogsDirectory
    val saveCsv = rememberCsvSaver()
    var files by remember { mutableStateOf(csvFiles(directory)) }
    OnResume { files = csvFiles(directory) }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_recordings), onBack) }) { padding ->
        if (files.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.recordings_empty_title),
                body = stringResource(R.string.recordings_empty_body),
                icon = Icons.Filled.MonitorHeart,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(files, key = { it.name }) { file ->
                    ListItem(
                        headlineContent = {
                            Text(
                                file.name,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        },
                        supportingContent = { Text(Formats.fileSize(context, file.length())) },
                        trailingContent = {
                            androidx.compose.foundation.layout.Row {
                                IconButton(onClick = { saveCsv(file) }) {
                                    Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.recordings_save))
                                }
                                IconButton(onClick = { context.shareCsv(file) }) {
                                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.recordings_share))
                                }
                                IconButton(onClick = {
                                    file.delete()
                                    files = csvFiles(directory)
                                }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.recordings_delete))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

fun csvFiles(directory: File): List<File> =
    directory.listFiles { file -> file.extension == "csv" }.orEmpty().sortedByDescending { it.name }

/**
 * Hands a recording to the share sheet through the app's FileProvider.
 *
 * The URI goes into ClipData as well as EXTRA_STREAM: the read grant travels with ClipData, and
 * without it some share targets only get the name and store an empty file.
 */
fun Context.shareCsv(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    startSafely(Intent.createChooser(send, file.name))
}

/**
 * Returns a function that saves a recording wherever the user picks in the system's file dialog,
 * e.g. Downloads, from where it can be copied over USB. Some phones offer no file manager as a
 * share target, and the app's own files are invisible over USB. Needs no storage permission.
 */
@Composable
fun rememberCsvSaver(): (File) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    var pending by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val file = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        val saved = runCatching {
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }.isSuccess
        val message = if (saved) resources.getString(R.string.recordings_saved, file.name) else resources.getString(R.string.recordings_save_failed)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    return { file ->
        pending = file
        launcher.launch(file.name)
    }
}
