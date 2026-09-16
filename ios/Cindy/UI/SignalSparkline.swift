import SwiftUI

/// Tiny line chart of the smoothed signal with the two thresholds drawn as lines.
struct SignalSparkline: View {
    var values: [Float]
    var thresholds: RepThresholds?

    var body: some View {
        Canvas { context, size in
            guard values.count > 1 else { return }
            var minValue = values.min() ?? 0
            var maxValue = values.max() ?? 1
            if let thresholds {
                minValue = min(minValue, thresholds.low)
                maxValue = max(maxValue, thresholds.high)
            }
            let range = max(maxValue - minValue, 1e-6)
            func y(_ v: Float) -> CGFloat {
                size.height - CGFloat((v - minValue) / range) * size.height
            }
            let step = size.width / CGFloat(values.count - 1)
            var path = Path()
            for (index, value) in values.enumerated() {
                let point = CGPoint(x: CGFloat(index) * step, y: y(value))
                if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            context.stroke(path, with: .color(.brand), lineWidth: 2)

            if let thresholds {
                for (value, color) in [(thresholds.low, Color.blue), (thresholds.high, Color.red)] {
                    var line = Path()
                    line.move(to: CGPoint(x: 0, y: y(value)))
                    line.addLine(to: CGPoint(x: size.width, y: y(value)))
                    context.stroke(line, with: .color(color.opacity(0.8)), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                }
            }
        }
        .background(Color.panelBackground, in: RoundedRectangle(cornerRadius: 8))
    }
}
