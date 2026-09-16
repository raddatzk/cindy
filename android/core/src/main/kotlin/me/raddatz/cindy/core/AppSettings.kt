package me.raddatz.cindy.core

/**
 * Language setting: follow the system or force one of the bundled languages.
 * Picker titles: "Automatic" (translated), "English", "Deutsch" (always in their own language).
 */
enum class AppLanguage(val rawValue: String) {
    SYSTEM("system"),
    ENGLISH("english"),
    GERMAN("german");

    val id: String get() = rawValue

    /** Language code of the resources to load; `null` follows the system. */
    val languageCode: String?
        get() = when (this) {
            SYSTEM -> null
            ENGLISH -> "en"
            GERMAN -> "de"
        }

    companion object {
        fun fromRawValue(rawValue: String): AppLanguage? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Appearance setting: follow the system or force one scheme. Titles "Automatic", "Light", "Dark". */
enum class AppTheme(val rawValue: String) {
    /** Hands the decision back to the system. */
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    val id: String get() = rawValue

    companion object {
        fun fromRawValue(rawValue: String): AppTheme? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
