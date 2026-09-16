package me.raddatz.cindy.ui.readiness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.raddatz.cindy.R
import me.raddatz.cindy.core.health.Readiness
import me.raddatz.cindy.core.reminder.NextSession
import me.raddatz.cindy.ui.components.PanelCard
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.adviceRes
import me.raddatz.cindy.ui.text.appLocale
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.text.titleRes
import me.raddatz.cindy.ui.theme.CindyTheme

/**
 * Readiness for the next session (iOS `ReadinessCard`): the score, what it means, and the two or
 * three measurements that drove it.
 */
@Composable
fun ReadinessCard(readiness: Readiness, nextSession: NextSession?, modifier: Modifier = Modifier) {
    val locale = appLocale
    val colors = CindyTheme.colors
    val bandColor = when (readiness.band) {
        Readiness.Band.REST -> colors.danger
        Readiness.Band.EASY -> colors.warning
        Readiness.Band.READY -> colors.brand
        Readiness.Band.PRIMED -> colors.success
    }
    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                stringResource(R.string.readiness_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
            )
            // Not in the band color: the bar carries it, and the band's name is written out below.
            val description = stringResource(R.string.readiness_score_description, readiness.score)
            Text(
                Formats.integer(readiness.score, locale),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            )
        }
        LinearProgressIndicator(
            progress = { readiness.score / 100f },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            color = bandColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            stringResource(readiness.band.titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(readiness.band.adviceRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (component in readiness.reasons()) {
                val detail = component.detail ?: continue
                Text(
                    "· " + detail.text.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (nextSession != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.readiness_next_session, Formats.dateTime(nextSession.date, locale)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!readiness.usesHealthData) {
            Text(
                stringResource(R.string.readiness_history_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
