import Foundation

/// The three movements of the "Cindy" WOD, in the order they are performed.
enum Exercise: String, Codable, CaseIterable, Identifiable, Sendable {
    case pullUp
    case pushUp
    case squat
    /// Time-based hold on a timer the athlete starts; the camera measures nothing.
    case plank

    var id: String { rawValue }

    /// true for exercises measured in seconds instead of reps.
    var isHold: Bool { self == .plank }

    /// Holds run on a timer the athlete starts, so only rep exercises are calibrated.
    var needsCalibration: Bool { !isHold }

    /// Default target per round: reps for movements, seconds for holds.
    var defaultTarget: Int {
        switch self {
        case .pullUp: return 5
        case .pushUp: return 10
        case .squat: return 15
        case .plank: return 30
        }
    }

    /// Unit abbreviation shown next to a target.
    var unit: String { isHold ? L("unit.seconds") : L("unit.reps") }

    /// User-facing name, plural form.
    var displayName: String {
        switch self {
        case .pullUp: return L("exercise.pullUp.plural")
        case .pushUp: return L("exercise.pushUp.plural")
        case .squat: return L("exercise.squat.plural")
        case .plank: return L("exercise.plank.plural")
        }
    }

    /// Name matching `count` — "1 pull-up" vs "5 pull-ups". German and English
    /// both get by with these two forms; a language with more plural categories
    /// would need per-exercise rules in Localizable.stringsdict.
    func name(for count: Int) -> String {
        count == 1 ? singularName : displayName
    }

    /// User-facing name, singular form (a single rep during calibration).
    var singularName: String {
        switch self {
        case .pullUp: return L("exercise.pullUp.singular")
        case .pushUp: return L("exercise.pushUp.singular")
        case .squat: return L("exercise.squat.singular")
        case .plank: return L("exercise.plank.singular")
        }
    }

    /// Expected direction of the primary (face) signal relative to the rest position.
    /// Used when no calibration is available (debug mode with hard-coded thresholds).
    /// Calibration measures the real direction and overrides this.
    var defaultRepDirection: RepDirection {
        switch self {
        case .pullUp: return .trough // hanging = face closest → largest area; chin over bar = smaller
        case .pushUp: return .peak   // top = face far → small area; bottom = close → large
        case .squat: return .peak    // standing = far; bottom of squat = closer
        case .plank: return .peak    // unused: holds run on a timer
        }
    }
}
