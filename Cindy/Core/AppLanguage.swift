import Foundation

/// Language setting: follow the system or force one of the bundled languages.
enum AppLanguage: String, CaseIterable, Identifiable, Sendable {
    case system
    case english
    case german

    var id: String { rawValue }

    /// Language code of the `.lproj` to load; `nil` follows the system.
    var languageCode: String? {
        switch self {
        case .system: return nil
        case .english: return "en"
        case .german: return "de"
        }
    }

    /// Locale used for date, number and measurement formatting.
    var locale: Locale {
        guard let languageCode else { return .autoupdatingCurrent }
        return Locale(identifier: languageCode)
    }

    /// Shown in the picker. Language names stay in their own language; only
    /// the system option is translated.
    var title: String {
        switch self {
        case .system: return L("Automatic")
        case .english: return "English"
        case .german: return "Deutsch"
        }
    }
}
