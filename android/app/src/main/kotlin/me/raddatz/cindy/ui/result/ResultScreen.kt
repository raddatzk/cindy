package me.raddatz.cindy.ui.result

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.core.Exercise
import me.raddatz.cindy.core.ExerciseSet
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.workout.ProgressionAdvisor
import me.raddatz.cindy.ui.components.BackTopBar
import me.raddatz.cindy.ui.components.BrandButton
import me.raddatz.cindy.ui.components.PanelCard
import me.raddatz.cindy.ui.components.RoundChart
import me.raddatz.cindy.ui.components.SecondaryButton
import me.raddatz.cindy.ui.text.summaryWithPlankText
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.theme.CindyTheme

/**
 * The result of a finished or stopped workout (iOS `ResultView`): score, comparison, round chart
 * and the progression advice. Nothing is saved until "Save"; back does nothing, because leaving
 * this screen means deciding between saving and discarding.
 */
@Composable
fun ResultScreen(model: AppModel, record: WorkoutRecord, onClose: () -> Unit) {
    // Read once: after saving, this record would be its own predecessor.
    val previous = remember { model.lastCompletedRecord }
    val recommendation = remember(record) { ProgressionAdvisor().recommend(record, previous) }
    var adopted by rememberSaveable { mutableStateOf(false) }

    BackHandler {}

    Scaffold(topBar = { BackTopBar(stringResource(R.string.result_title), onBack = null) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(if (record.completed) R.string.result_time else R.string.result_stopped),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(record.score.notation, fontSize = 96.sp, fontWeight = FontWeight.Black)
            Text(
                stringResource(
                    R.string.result_score_summary,
                    pluralStringResource(R.plurals.result_rounds, record.rounds, record.rounds),
                    pluralStringResource(R.plurals.result_reps, record.extraReps, record.extraReps),
                    pluralStringResource(R.plurals.result_total_reps, record.score.totalReps, record.score.totalReps),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            record.plankSeconds?.let { seconds ->
                // Held after the AMRAP, outside the score.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ExerciseSet(Exercise.PLANK, seconds).label.text.text(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (previous != null) {
                val diff = record.score.totalReps - previous.score.totalReps
                PanelCard {
                    Text(
                        stringResource(R.string.result_previous, previous.score.notation),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    // The sign and the arrow say better or worse; the color only repeats it.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (diff >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                            contentDescription = null,
                            tint = if (diff >= 0) CindyTheme.colors.success else CindyTheme.colors.danger,
                        )
                        Text(
                            if (diff >= 0) {
                                pluralStringResource(R.plurals.result_reps_gained, diff, diff)
                            } else {
                                pluralStringResource(R.plurals.result_reps, diff, diff)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.result_first_saved), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            RoundChart(record)

            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null)
                    Text(
                        stringResource(R.string.result_next_time),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Text(recommendation.reason.text.text(), style = MaterialTheme.typography.bodyMedium)
                if (recommendation.changesPlan) {
                    Text(
                        stringResource(
                            R.string.plan_duration_summary,
                            recommendation.plan.durationMinutes,
                            recommendation.plan.summaryWithPlankText.text(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryButton(
                        text = stringResource(if (adopted) R.string.result_adopted else R.string.result_adopt),
                        icon = if (adopted) Icons.Filled.Check else Icons.Filled.Tune,
                        enabled = !adopted,
                        onClick = {
                            model.setPlan(recommendation.plan)
                            adopted = true
                        },
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.result_discard),
                    onClick = onClose,
                    destructive = true,
                )
                BrandButton(
                    text = stringResource(R.string.result_save),
                    onClick = {
                        model.save(record)
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
