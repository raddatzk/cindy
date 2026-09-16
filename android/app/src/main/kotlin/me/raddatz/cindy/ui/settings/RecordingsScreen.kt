package me.raddatz.cindy.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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

/** Hands a recording to the share sheet through the app's FileProvider. */
fun Context.shareCsv(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startSafely(Intent.createChooser(send, file.name))
}
