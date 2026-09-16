package me.raddatz.cindy.health

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import me.raddatz.cindy.R
import me.raddatz.cindy.core.AppLanguage
import me.raddatz.cindy.settings.SettingsStore

/**
 * Shown by Health Connect when the user asks why Cindy wants its data (the permission rationale,
 * and "view permission usage" on Android 14+). Opens the privacy policy in the app's language and
 * closes; without a browser it shows the short explanation and the link instead.
 */
class HealthPermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = privacyPolicyUrl()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            finish()
        } catch (_: ActivityNotFoundException) {
            setTitle(R.string.health_rationale_title)
            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            val text = TextView(this).apply {
                setPadding(padding, padding, padding, padding)
                setTextIsSelectable(true)
                text = getString(R.string.health_rationale_body, url)
            }
            setContentView(ScrollView(this).apply { addView(text) })
        }
    }

    private fun privacyPolicyUrl(): String {
        val language = SettingsStore(this).appLanguage.languageCode
            ?: resources.configuration.locales[0].language
        return if (language == AppLanguage.GERMAN.languageCode) PRIVACY_POLICY_GERMAN else PRIVACY_POLICY_ENGLISH
    }

    companion object {
        const val PRIVACY_POLICY_ENGLISH = "https://github.com/raddatzk/cindy/blob/main/PRIVACY.md#english"
        const val PRIVACY_POLICY_GERMAN = "https://github.com/raddatzk/cindy/blob/main/PRIVACY.md#deutsch"
    }
}
