package me.raddatz.cindy.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.raddatz.cindy.R
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.app.currentAppLanguage
import me.raddatz.cindy.core.AppLanguage
import me.raddatz.cindy.core.AppTheme
import me.raddatz.cindy.health.HealthAvailability
import me.raddatz.cindy.health.HealthException
import me.raddatz.cindy.health.HealthPermissionsRationaleActivity
import me.raddatz.cindy.health.message
import me.raddatz.cindy.reminder.NotificationPermission
import me.raddatz.cindy.ui.components.BackTopBar
import me.raddatz.cindy.ui.components.OnResume
import me.raddatz.cindy.ui.components.SectionFooter
import me.raddatz.cindy.ui.components.SectionHeader
import me.raddatz.cindy.ui.components.openUrl
import me.raddatz.cindy.ui.components.startSafely
import me.raddatz.cindy.ui.text.Formats
import me.raddatz.cindy.ui.text.appLocale
import me.raddatz.cindy.ui.text.text
import me.raddatz.cindy.ui.text.title
import me.raddatz.cindy.ui.text.titleRes
import me.raddatz.cindy.ui.theme.CindyTheme
import java.time.Instant

private const val SUPPORT_ENGLISH = "https://github.com/raddatzk/cindy/blob/main/SUPPORT.md#english"
private const val SUPPORT_GERMAN = "https://github.com/raddatzk/cindy/blob/main/SUPPORT.md#deutsch"

/** Rough CSV size of one 20-minute session. */
private const val RECORDING_BYTES_PER_SESSION = 4_500_000L

/** App settings (iOS `SettingsView`): display, intro, Health Connect, reminder, privacy, recordings, version. */
@Composable
fun SettingsScreen(model: AppModel, onBack: () -> Unit, onShowIntro: () -> Unit, onShowRecordings: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = appLocale
    val isGerman = locale.language == AppLanguage.GERMAN.languageCode

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Display
            SectionHeader(stringResource(R.string.settings_display))
            val language = remember(settings.language) { currentAppLanguage() }
            ChoiceRow(
                title = stringResource(R.string.settings_language),
                options = AppLanguage.entries,
                selected = language,
                label = { it.title.text() },
                onSelect = model::setLanguage,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_appearance),
                options = AppTheme.entries,
                selected = settings.theme,
                label = { stringResource(it.titleRes) },
                onSelect = model::setTheme,
            )
            SectionFooter(stringResource(R.string.settings_display_footer))

            // Intro
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_how_cindy_works)) },
                leadingContent = { BrandIcon(Icons.AutoMirrored.Outlined.HelpOutline) },
                modifier = Modifier.clickable(onClick = onShowIntro),
            )
            SectionFooter(stringResource(R.string.settings_how_cindy_works_footer))

            HealthSection(model, settings.syncToHealth)
            ReminderSection(model, settings.remindersEnabled)

            // Privacy
            SectionHeader(stringResource(R.string.settings_privacy))
            PrivacyRow(
                Icons.Outlined.VideocamOff,
                stringResource(R.string.settings_privacy_no_recording_title),
                stringResource(R.string.settings_privacy_no_recording_body),
            )
            PrivacyRow(
                Icons.Outlined.WifiOff,
                stringResource(R.string.settings_privacy_nothing_sent_title),
                stringResource(R.string.settings_privacy_nothing_sent_body),
            )
            PrivacyRow(
                Icons.Outlined.Storage,
                stringResource(R.string.settings_privacy_local_title),
                stringResource(R.string.settings_privacy_local_body),
            )
            LinkRow(stringResource(R.string.settings_privacy_policy)) {
                context.openUrl(
                    if (isGerman) {
                        HealthPermissionsRationaleActivity.PRIVACY_POLICY_GERMAN
                    } else {
                        HealthPermissionsRationaleActivity.PRIVACY_POLICY_ENGLISH
                    },
                )
            }
            LinkRow(stringResource(R.string.settings_support)) {
                context.openUrl(if (isGerman) SUPPORT_GERMAN else SUPPORT_ENGLISH)
            }

            // Signal recordings
            SectionHeader(stringResource(R.string.settings_recordings_header))
            SwitchRow(
                title = stringResource(R.string.settings_record_workouts),
                checked = settings.recordWorkouts,
                onCheckedChange = model::setRecordWorkouts,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_recordings)) },
                modifier = Modifier.clickable(onClick = onShowRecordings),
            )
            SectionFooter(
                stringResource(R.string.settings_recordings_footer, Formats.fileSize(context, RECORDING_BYTES_PER_SESSION)),
            )

            HorizontalDivider(Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                trailingContent = { Text(remember { versionString(context) }) },
            )
        }
    }
}

@Composable
private fun HealthSection(model: AppModel, syncToHealth: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var availability by remember { mutableStateOf(model.healthAvailability()) }
    // Writing was refused: only the Health Connect settings can undo that.
    var denied by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val locale = appLocale

    OnResume {
        availability = model.healthAvailability()
        pending = model.workoutsPendingInHealth()
    }

    val permissionLauncher = rememberLauncherForActivityResult(model.container.health.permissionContract()) {
        scope.launch {
            denied = !model.enableHealthSync()
            pending = model.workoutsPendingInHealth()
        }
    }

    when (availability) {
        HealthAvailability.UNAVAILABLE -> return
        HealthAvailability.UPDATE_REQUIRED -> {
            SectionHeader(stringResource(R.string.settings_health_header))
            LinkRow(stringResource(R.string.settings_health_install)) {
                context.startSafely(model.container.health.providerUpdateIntent())
            }
            SectionFooter(stringResource(R.string.settings_health_install_footer))
            return
        }
        HealthAvailability.AVAILABLE -> Unit
    }

    SectionHeader(stringResource(R.string.settings_health_header))
    SwitchRow(
        title = stringResource(R.string.settings_health_sync),
        checked = syncToHealth,
        onCheckedChange = { isOn ->
            error = null
            if (!isOn) {
                denied = false
                model.disableHealthSync()
            } else {
                scope.launch { permissionLauncher.launch(model.healthPermissionsToRequest()) }
            }
        },
    )
    if (syncToHealth && pending > 0) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_health_export)) },
            trailingContent = {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Text(Formats.integer(pending, locale), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.clickable(enabled = !exporting) {
                exporting = true
                error = null
                scope.launch {
                    try {
                        model.exportHistoryToHealth()
                    } catch (e: HealthException) {
                        error = e.error.message(context)
                    }
                    pending = model.workoutsPendingInHealth()
                    exporting = false
                }
            },
        )
    }
    SectionFooter(
        error ?: stringResource(if (denied) R.string.health_error_denied else R.string.settings_health_footer),
    )
}

@Composable
private fun ReminderSection(model: AppModel, remindersEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Notifications were refused: only the system settings can undo that.
    var denied by rememberSaveable { mutableStateOf(false) }
    // Read back from WorkManager rather than from the plan, so a nudge that failed to schedule
    // shows up as missing instead of looking fine.
    var pendingReminder by remember { mutableStateOf<Instant?>(null) }
    val locale = appLocale

    LaunchedEffect(remindersEnabled) { pendingReminder = model.pendingReminderDate() }

    fun enable() {
        scope.launch {
            denied = !model.enableReminders()
            pendingReminder = model.pendingReminderDate()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enable() }

    SectionHeader(stringResource(R.string.settings_reminder_header))
    SwitchRow(
        title = stringResource(R.string.settings_remind_me),
        checked = remindersEnabled,
        onCheckedChange = { isOn ->
            if (!isOn) {
                denied = false
                model.disableReminders()
                pendingReminder = null
            } else {
                val permission = NotificationPermission.permission
                if (permission != null && !NotificationPermission.isGranted(context)) {
                    permissionLauncher.launch(permission)
                } else {
                    enable()
                }
            }
        },
    )
    val next = pendingReminder
    if (remindersEnabled && next != null) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_next_reminder)) },
            trailingContent = { Text(Formats.dateTime(next, locale)) },
        )
    }
    if (denied) {
        SectionFooter(stringResource(R.string.settings_reminder_denied))
        TextButton(
            onClick = { context.startSafely(NotificationPermission.settingsIntent(context)) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.common_open_settings))
        }
    } else {
        SectionFooter(stringResource(R.string.settings_reminder_footer))
    }
}

/** A setting with a fixed set of values, picked in a dialog of radio buttons. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(label(selected)) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (option in options) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = option == selected, role = Role.RadioButton) {
                                    open = false
                                    if (option != selected) onSelect(option)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Text(label(option), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = CindyTheme.colors.brand) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun PrivacyRow(icon: ImageVector, title: String, text: String) {
    ListItem(
        leadingContent = { BrandIcon(icon) },
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(text) },
        colors = ListItemDefaults.colors(),
    )
}

@Composable
private fun BrandIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = CindyTheme.colors.brand)
}

private fun versionString(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    "${info.versionName} (${info.longVersionCode})"
} catch (_: PackageManager.NameNotFoundException) {
    "?"
}
