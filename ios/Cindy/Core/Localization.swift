import Foundation

/// Resolves user-facing strings against the language picked in Settings,
/// which may differ from the system language.
///
/// Held globally because strings are needed outside the view tree too
/// (error descriptions, progression advice). Written only from
/// the main actor when the setting changes; reads elsewhere see either the old
/// or the new bundle, never a torn value.
enum Localization {
    nonisolated(unsafe) private(set) static var bundle = Bundle.main
    nonisolated(unsafe) private(set) static var locale = Locale.autoupdatingCurrent
    /// Language actually in use, resolved for the system option as well.
    nonisolated(unsafe) private(set) static var languageCode = Localization.systemLanguageCode

    /// Supported languages, in the order they appear in the bundle.
    static let supportedLanguageCodes = ["en", "de"]

    static func apply(_ language: AppLanguage) {
        let code = language.languageCode ?? systemLanguageCode
        languageCode = code
        locale = language.locale
        bundle = Bundle.main.path(forResource: code, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
    }

    /// The bundled language the system would pick on its own.
    private static var systemLanguageCode: String {
        let preferred = Bundle.main.preferredLocalizations.first ?? "en"
        return supportedLanguageCodes.contains(preferred) ? preferred : "en"
    }
}

/// Localizes `key` with the language selected in Settings.
///
/// Used instead of `Text("…")`'s implicit lookup so that in-app language
/// switching and non-view strings go through the same bundle.
func L(_ key: String.LocalizationValue) -> String {
    String(localized: key, bundle: Localization.bundle, locale: Localization.locale)
}

extension Double {
    /// Decimal string in the app language — German writes "1,5", English "1.5".
    /// Use instead of `String(format: "%.1f", …)`, which is always locale-agnostic.
    func formattedDecimal(_ fractionDigits: Int) -> String {
        formatted(.number.precision(.fractionLength(fractionDigits)).locale(Localization.locale))
    }

    /// `self` as a ratio (0…1) rendered as a percentage in the app language.
    var formattedPercent: String {
        formatted(.percent.precision(.fractionLength(0)).locale(Localization.locale))
    }
}

extension Date {
    /// Date and time in the app language. `formatted(date:time:)` would always
    /// use the system locale, which is wrong once a language is picked in Settings.
    func formatted(date: Date.FormatStyle.DateStyle, time: Date.FormatStyle.TimeStyle,
                   in locale: Locale) -> String {
        formatted(Date.FormatStyle(date: date, time: time).locale(locale))
    }
}

extension Int64 {
    /// File size in the app language — German writes "4,5 MB", English "4.5 MB".
    var formattedFileSize: String {
        formatted(.byteCount(style: .file).locale(Localization.locale))
    }
}
