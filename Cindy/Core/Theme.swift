import SwiftUI
import UIKit

/// Semantic colors so every screen — including the workout canvas, which used
/// to hardcode black — resolves correctly in light and dark mode.
extension ShapeStyle where Self == Color {
    /// Brand accent. Reads the asset directly rather than `Color.accentColor`,
    /// which would resolve to the environment tint this color is itself used
    /// to set.
    ///
    /// Light mode is #B35500, 5.0:1 on white, so it can carry small text; the
    /// earlier #D96B05 was 3.5:1 and only passed at headline sizes. Dark mode
    /// is #FF9F0A, 10:1 on black.
    static var brand: Color { Color("AccentColor", bundle: .main) }

    /// Text and icons drawn on top of `.brand`. White on the dark light-mode
    /// orange (5.0:1), black on the bright dark-mode one (10:1) — white there
    /// would be 2:1.
    static var onBrand: Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? .black : .white })
    }

    /// Full-screen background of the workout and countdown screens.
    static var screenBackground: Color { Color(uiColor: .systemBackground) }

    /// Slightly raised panel on top of `screenBackground` (signal sparkline).
    static var panelBackground: Color { Color(uiColor: .secondarySystemBackground) }
}

extension View {
    /// `.borderedProminent` with a label that stays legible on the brand fill.
    /// The system style labels it white in both schemes, which on the bright
    /// dark-mode orange is 2:1.
    func brandProminentButtonStyle() -> some View {
        modifier(BrandProminentButtonStyle())
    }
}

private struct BrandProminentButtonStyle: ViewModifier {
    @Environment(\.isEnabled) private var isEnabled

    /// Only an enabled button gets the label colour. A disabled one is drawn
    /// grey by the system, and forcing `onBrand` onto that grey fill left a
    /// white label on light grey.
    func body(content: Content) -> some View {
        if isEnabled {
            content.buttonStyle(.borderedProminent).foregroundStyle(.onBrand)
        } else {
            content.buttonStyle(.borderedProminent)
        }
    }
}
