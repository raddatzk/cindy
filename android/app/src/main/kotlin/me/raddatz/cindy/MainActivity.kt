package me.raddatz.cindy

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import me.raddatz.cindy.app.AppModel
import me.raddatz.cindy.app.CindyApp
import me.raddatz.cindy.app.currentAppLanguage
import me.raddatz.cindy.app.nightMode

/**
 * The only activity. Appearance and language are applied through AppCompat, so the configuration
 * — and with it Compose, the system bars and every resource lookup — follows the in-app setting.
 */
class MainActivity : AppCompatActivity() {
    private val model: AppModel by viewModels { AppModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = (application as CindyApplication).container.settings
        // Not persisted by AppCompat; set before the delegate applies the configuration.
        AppCompatDelegate.setDefaultNightMode(settings.appTheme.nightMode)
        super.onCreate(savedInstanceState)
        // From Android 13 the language can also be changed in the system settings; keep the
        // stored value (used outside this activity) in step with what is applied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val applied = currentAppLanguage()
            if (settings.appLanguage != applied) settings.appLanguage = applied
        }
        enableEdgeToEdge()
        setContent { CindyApp(model) }
    }
}
