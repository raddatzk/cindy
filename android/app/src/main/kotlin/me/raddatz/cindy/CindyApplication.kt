package me.raddatz.cindy

import android.app.Application
import me.raddatz.cindy.reminder.ReminderNotifications

class CindyApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.createChannel(this)
        // A pending reminder cannot re-evaluate itself; the periodic refresh moves it.
        container.reminderScheduler.ensurePeriodicRefresh()
    }
}
