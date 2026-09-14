import SwiftUI
import UIKit

/// Semantic colors so every screen — including the workout canvas, which used
/// to hardcode black — resolves correctly in light and dark mode.
extension ShapeStyle where Self == Color {
    /// Brand accent. Darker in light mode to stay legible on white.
    /// Reads the asset directly rather than `Color.accentColor`, which would
    /// resolve to the environment tint this color is itself used to set.
    static var brand: Color { Color("AccentColor", bundle: .main) }

    /// Text and icons drawn on top of `.brand`. Fixed: the accent is light in
    /// both schemes, so this must not follow the color scheme.
    static var onBrand: Color { .black }

    /// Full-screen background of the workout and countdown screens.
    static var screenBackground: Color { Color(uiColor: .systemBackground) }

    /// Slightly raised panel on top of `screenBackground` (signal sparkline).
    static var panelBackground: Color { Color(uiColor: .secondarySystemBackground) }
}
