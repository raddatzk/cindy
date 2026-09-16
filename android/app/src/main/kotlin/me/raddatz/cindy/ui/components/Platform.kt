package me.raddatz.cindy.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Keeps the display on while [enabled] (iOS `isIdleTimerDisabled`), for as long as the calling
 * screen is shown.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * The system "Remove animations" setting (Settings › Accessibility), Android's Reduce Motion:
 * an animator duration scale of 0. Read again whenever the screen resumes.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember { mutableStateOf(isReduceMotion(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduceMotion = isReduceMotion(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduceMotion
}

private fun isReduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Runs [block] every time the screen resumes (iOS `onAppear` after returning from the system settings). */
@Composable
fun OnResume(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** The app's page in the system settings (permissions). */
fun Context.openAppSettings() {
    startSafely(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

fun Context.openUrl(url: String) {
    startSafely(Intent(Intent.ACTION_VIEW, url.toUri()))
}

fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
