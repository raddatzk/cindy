package me.raddatz.cindy.ui.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/**
 * A user-facing text that is resolved as late as possible, against the resources of the screen
 * that shows it — so it always follows the in-app language, also for text built outside the view
 * tree (iOS: `L(…)` against `Localization.bundle`).
 *
 * Arguments may themselves be [UiText] (an exercise name inside a sentence) or [FormattedNumber] formatting
 * requests, which are formatted in the app language.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Plural(@param:PluralsRes val id: Int, val count: Int) : UiText

    data class Verbatim(val text: String) : UiText

    /** Items joined with " · " (the plan summary). */
    data class Joined(val parts: List<UiText>, val separator: String = " · ") : UiText

    /** Items joined as a natural-language list ("Pull-ups, Push-ups and Squats"). */
    data class NaturalList(val parts: List<UiText>) : UiText
}

/** A number argument that has to be formatted in the app language. */
sealed interface FormattedNumber {
    /** iOS `formattedDecimal(_:)`: "1,5" in German, "1.5" in English. */
    data class Decimal(val value: Double, val fractionDigits: Int) : FormattedNumber

    /** iOS `formattedPercent`: a ratio 0…1 as whole percent. */
    data class Percent(val ratio: Double) : FormattedNumber
}

fun UiText.resolve(resources: Resources): String {
    val locale = resources.configuration.locales[0] ?: Locale.getDefault()
    return when (this) {
        is UiText.Res -> if (args.isEmpty()) {
            resources.getString(id)
        } else {
            resources.getString(id, *args.map { it.resolveArgument(resources, locale) }.toTypedArray())
        }
        is UiText.Plural -> resources.getQuantityString(id, count, count)
        is UiText.Verbatim -> text
        is UiText.Joined -> parts.joinToString(separator) { it.resolve(resources) }
        is UiText.NaturalList -> Formats.list(parts.map { it.resolve(resources) }, locale)
    }
}

private fun Any.resolveArgument(resources: Resources, locale: Locale): Any = when (this) {
    is UiText -> resolve(resources)
    is FormattedNumber.Decimal -> Formats.decimal(value, fractionDigits, locale)
    is FormattedNumber.Percent -> Formats.percent(ratio, locale)
    else -> this
}

/** Resolves against the current screen's resources; recomposes when the configuration changes. */
@Composable
@ReadOnlyComposable
fun UiText.text(): String {
    LocalConfiguration.current
    return resolve(LocalResources.current)
}
