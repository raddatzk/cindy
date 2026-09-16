package me.raddatz.cindy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.raddatz.cindy.R
import me.raddatz.cindy.core.persistence.WorkoutRecord
import me.raddatz.cindy.core.signal.RepThresholds
import me.raddatz.cindy.core.workout.ProgressionAdvisor
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.appLocale
import me.raddatz.cindy.ui.theme.CindyTheme

/** A bar per round: its duration in seconds (iOS `RoundChart`). */
@Composable
fun RoundChart(record: WorkoutRecord, modifier: Modifier = Modifier) {
    val values = ProgressionAdvisor.roundDurations(record)
    val locale = appLocale
    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.chart_round_times),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            val reserve = ProgressionAdvisor.reserveRatio(record)
            if (reserve != null) {
                // The icon carries the verdict so the color does not have to.
                val good = reserve >= 0.85
                Icon(
                    if (good) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (good) CindyTheme.colors.success else CindyTheme.colors.brand,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.chart_reserve, Formats.percent(reserve, locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (values.isEmpty()) {
            Text(
                stringResource(R.string.chart_no_round_times),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val brand = CindyTheme.colors.brand
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            val measurer = rememberTextMeasurer()
            val fontScale = LocalDensity.current.fontScale
            // Grows with the text size, so the bar labels scale without squashing the bars.
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height((110 * fontScale).dp)
                    .clearAndSetSemantics {},
            ) {
                val labelHeight = 14.dp.toPx() * fontScale
                val maxValue = maxOf(values.max(), 1.0)
                val gap = 3.dp.toPx()
                val barWidth = maxOf((size.width - gap * (values.size - 1)) / values.size, 2f)
                values.forEachIndexed { index, value ->
                    val height = (value / maxValue).toFloat() * (size.height - labelHeight)
                    val x = index * (barWidth + gap)
                    drawRoundRect(
                        brand,
                        topLeft = Offset(x, size.height - labelHeight - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    if (barWidth >= 14.dp.toPx()) {
                        drawCentered(measurer, value.toInt().toString(), TextStyle(fontSize = 9.sp, color = labelColor),
                            Offset(x + barWidth / 2, size.height - labelHeight / 2))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.chart_average, (values.sum() / values.size).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.chart_fastest_slowest, values.min().toInt(), values.max().toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCentered(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    center: Offset,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/** Line chart of the smoothed signal with the two thresholds dashed (iOS `SignalSparkline`). */
@Composable
fun SignalSparkline(values: List<Float>, thresholds: RepThresholds?, modifier: Modifier = Modifier) {
    val brand = CindyTheme.colors.brand
    val low = CindyTheme.colors.info
    val high = CindyTheme.colors.danger
    Canvas(modifier.background(CindyTheme.colors.panel, RoundedCornerShape(8.dp)).clearAndSetSemantics {}) {
        if (values.size < 2) return@Canvas
        var minValue = values.min()
        var maxValue = values.max()
        if (thresholds != null) {
            minValue = minOf(minValue, thresholds.low)
            maxValue = maxOf(maxValue, thresholds.high)
        }
        val range = maxOf(maxValue - minValue, 1e-6f)
        fun y(v: Float) = size.height - (v - minValue) / range * size.height
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            if (index == 0) path.moveTo(0f, y(value)) else path.lineTo(index * step, y(value))
        }
        drawPath(path, brand, style = Stroke(width = 2.dp.toPx()))
        if (thresholds != null) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            for ((value, color) in listOf(thresholds.low to low, thresholds.high to high)) {
                drawLine(
                    color.copy(alpha = 0.8f),
                    start = Offset(0f, y(value)),
                    end = Offset(size.width, y(value)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
        }
    }
}
