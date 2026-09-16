package me.raddatz.cindy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.raddatz.cindy.R
import me.raddatz.cindy.ui.theme.CindyTheme

/** iOS `brandProminentButtonStyle()` at `.controlSize(.large)`: the one filled action of a screen. */
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = CindyTheme.colors.brand,
            contentColor = CindyTheme.colors.onBrand,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        ButtonContent(text, icon)
    }
}

/** iOS `.buttonStyle(.bordered)`. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.outlinedButtonColors(contentColor = CindyTheme.colors.danger)
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = CindyTheme.colors.brand)
        },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** iOS `.background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))`. */
@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** An icon in the brand color next to a paragraph (iOS `bullet`). */
@Composable
fun IconBullet(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = CindyTheme.colors.brand, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Uppercase-free section title of a settings-like list. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = CindyTheme.colors.brand,
    )
}

/** Explanation under a section (iOS section footer). */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A top app bar with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        },
        actions = actions,
    )
}

/**
 * iOS `Stepper`: minus and plus around a label. Both buttons are 48 dp targets and name what
 * they change for TalkBack.
 */
@Composable
fun Stepper(
    value: Int,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit,
    subject: String,
    modifier: Modifier = Modifier,
    label: @Composable RowScope.() -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        label()
        FilledTonalIconButton(
            onClick = { onValueChange(maxOf(value - step, range.first)) },
            enabled = value - step >= range.first,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.plan_decrease) + ", " + subject)
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = { onValueChange(minOf(value + step, range.last)) },
            enabled = value + step <= range.last,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.plan_increase) + ", " + subject)
        }
    }
}

/** A vertical stack that gives every child the height of the tallest one (iOS `EqualHeightVStack`). */
@Composable
fun EqualHeightColumn(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val rowHeight = measurables.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0
        val placeables = measurables.map { it.measure(Constraints.fixed(width, rowHeight)) }
        val gap = spacing.roundToPx()
        val height = rowHeight * placeables.size + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            for (placeable in placeables) {
                placeable.place(0, y)
                y += rowHeight + gap
            }
        }
    }
}
