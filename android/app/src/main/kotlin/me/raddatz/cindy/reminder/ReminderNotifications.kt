package me.raddatz.cindy.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.raddatz.cindy.MainActivity
import me.raddatz.cindy.R
import me.raddatz.cindy.core.reminder.NextSessionReason

/**
 * The runtime permission Android 13+ needs before a notification can be posted. The UI asks;
 * this only reports the state.
 */
object NotificationPermission {
    /** The permission to request, `null` below Android 13 where none is needed. */
    val permission: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    fun isGranted(context: Context): Boolean {
        val permission = permission ?: return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether a reminder would actually show: permission granted and notifications not switched
     * off for the app (iOS `WorkoutReminder.isAuthorized`).
     */
    fun canPost(context: Context): Boolean =
        isGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The system screen where notifications can be switched back on for Cindy. */
    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** The single reminder notification (iOS: the content of `WorkoutReminder.schedule`). */
object ReminderNotifications {
    const val CHANNEL_ID: String = "reminder"

    /** One id, so a new nudge replaces a visible old one. */
    const val NOTIFICATION_ID: Int = 1
    const val NOTIFICATION_TAG: String = "nextWorkout"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.reminder_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun post(context: Context, reason: NextSessionReason) {
        if (!NotificationPermission.canPost(context)) return
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = context.getString(bodyRes(reason))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun bodyRes(reason: NextSessionReason): Int = when (reason) {
        NextSessionReason.EXTRA_DAY -> R.string.reminder_notification_body_extra_day
        NextSessionReason.LATER_THAN_USUAL -> R.string.reminder_notification_body_later_than_usual
        NextSessionReason.RECOVERED -> R.string.reminder_notification_body_recovered
    }
}
