package me.raddatz.cindy.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Big text that shrinks to fit on one line instead of wrapping (iOS `.lineLimit(1)` with
 * `.minimumScaleFactor`). Scales with the font size up to [maxSize].
 */
@Composable
fun FittingText(
    text: String,
    maxSize: TextUnit,
    modifier: Modifier = Modifier,
    minScale: Float = 0.4f,
    weight: FontWeight = FontWeight.Black,
    color: Color = LocalContentColor.current,
) {
    BasicText(
        text,
        modifier = modifier,
        maxLines = 1,
        style = TextStyle(fontWeight = weight, color = color, textAlign = TextAlign.Center),
        autoSize = TextAutoSize.StepBased(minFontSize = maxSize * minScale, maxFontSize = maxSize),
    )
}

/** A countdown digit: fades between values unless animations are removed. */
@Composable
fun BigNumber(value: Int, modifier: Modifier = Modifier, maxSize: TextUnit = 160.sp) {
    val reduceMotion = rememberReduceMotion()
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None else fadeIn() togetherWith fadeOut()
        },
        label = "countdown",
    ) { number ->
        FittingText(number.toString(), maxSize)
    }
}
