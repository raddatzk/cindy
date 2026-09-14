import SwiftUI

/// Appearance setting: follow the system or force one scheme.
enum AppTheme: String, CaseIterable, Identifiable, Sendable {
    case system
    case light
    case dark

    var id: String { rawValue }

    /// `nil` hands the decision back to the system.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    var title: String {
        switch self {
        case .system: return L("Automatic")
        case .light: return L("Light")
        case .dark: return L("Dark")
        }
    }
}
