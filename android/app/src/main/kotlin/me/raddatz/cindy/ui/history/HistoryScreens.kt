package me.raddatz.cindy.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.ui.components.BackTopBar
import me.raddatz.cindy.ui.components.OnResume
import me.raddatz.cindy.ui.components.RoundChart
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.appLocale
import me.raddatz.cindy.ui.text.summaryText
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.theme.CindyTheme

/** Saved workouts, newest first; swipe a row away to delete it (iOS `HistoryView`). */
@Composable
fun HistoryScreen(model: AppModel, onBack: () -> Unit, onOpen: (WorkoutRecord) -> Unit) {
    val history by model.history.collectAsStateWithLifecycle()
    OnResume { model.reload() }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.history_title), onBack) }) { padding ->
        if (history.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(history, key = { it.id }) { record ->
                    HistoryRow(record, onOpen = { onOpen(record) }, onDelete = { model.delete(record) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: WorkoutRecord, onOpen: () -> Unit, onDelete: () -> Unit) {
    val locale = appLocale
    val state = rememberSwipeToDismissBoxState()
    val deleteLabel = stringResource(R.string.common_delete)
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(CindyTheme.colors.danger).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
            }
        },
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true }) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(Formats.dateTime(record.date, locale), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (record.completed) {
                            Formats.clock(record.plannedDuration)
                        } else {
                            stringResource(R.string.history_stopped_after, Formats.clock(record.durationSeconds))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(record.score.notation, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** One saved workout: score, date, plan and round chart (iOS `WorkoutDetailView`). */
@Composable
fun WorkoutDetailScreen(model: AppModel, recordId: String, onBack: () -> Unit) {
    val history by model.history.collectAsStateWithLifecycle()
    val record = history.firstOrNull { it.id.toString() == recordId }
    val locale = appLocale
    Scaffold(topBar = { BackTopBar(stringResource(R.string.workout_title), onBack) }) { padding ->
        if (record == null) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(record.score.notation, fontSize = 72.sp, fontWeight = FontWeight.Black)
            Text(
                Formats.longDateTime(record.date, locale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            record.plan?.let { plan ->
                Text(
                    stringResource(R.string.plan_duration_summary, plan.durationMinutes, plan.summaryText.text()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            RoundChart(record)
        }
    }
}

/** iOS `ContentUnavailableView`. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.History,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
