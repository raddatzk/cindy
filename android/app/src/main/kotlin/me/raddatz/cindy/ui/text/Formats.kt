package me.raddatz.cindy.ui.text

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil

/**
 * Numbers, dates and sizes in the app language (iOS `Localization.swift`). The locale is the one
 * of the screen's configuration, which follows the in-app language, never the bare system locale.
 */
object Formats {
    fun decimal(value: Double, fractionDigits: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }.format(value)

    fun percent(ratio: Double, locale: Locale): String =
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(ratio)

    fun integer(value: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value.toLong())

    /** iOS `formatted(date: .abbreviated, time: .shortened)`. */
    fun dateTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.atZone(zone))

    /** iOS `formatted(date: .long, time: .shortened)`. */
    fun longDateTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.atZone(zone))

    /** "mm:ss", truncating (history, iOS `WorkoutScreenTime.format`). */
    fun clock(seconds: Double): String {
        val total = seconds.toInt()
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }

    /** "mm:ss", rounding up so the timer never shows 00:00 before the end (iOS `WorkoutScreen.format`). */
    fun countdownClock(seconds: Double): String {
        val total = ceil(seconds).toInt()
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }

    /** "Pull-ups, Push-ups and Squats" (iOS `ListFormatter`). */
    fun list(items: List<String>, locale: Locale): String =
        android.icu.text.ListFormatter.getInstance(locale).format(items)

    /** File size in the app language: "4,5 MB" in German, "4.5 MB" in English. */
    fun fileSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
}

/** The locale of the current screen, i.e. the app language. */
val appLocale: Locale
    @Composable @ReadOnlyComposable
    get() = LocalConfiguration.current.locales.get(0)
