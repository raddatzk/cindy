import SwiftUI

/// Bar per round: its duration in seconds. Quarter boundaries of the workout are marked.
struct RoundChart: View {
    var record: WorkoutRecord

    private var durations: [TimeInterval] { ProgressionAdvisor.roundDurations(record) }

    var body: some View {
        let values = durations
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(L("Round times"))
                    .font(.headline)
                Spacer()
                if let reserve = ProgressionAdvisor.reserveRatio(record) {
                    Text(L("Reserve \(reserve.formattedPercent)"))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(reserve >= 0.85 ? Color.green : Color.brand)
                }
            }
            if values.isEmpty {
                Text(L("No round times recorded."))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Canvas { context, size in
                    let maxValue = max(values.max() ?? 1, 1)
                    let gap: CGFloat = 3
                    let barWidth = max((size.width - gap * CGFloat(values.count - 1)) / CGFloat(values.count), 2)
                    for (index, value) in values.enumerated() {
                        let height = CGFloat(value / maxValue) * (size.height - 14)
                        let x = CGFloat(index) * (barWidth + gap)
                        let rect = CGRect(x: x, y: size.height - 14 - height, width: barWidth, height: height)
                        context.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(.brand))
                        if barWidth >= 14 {
                            context.draw(Text(verbatim: "\(Int(value))").font(.system(size: 9)).foregroundStyle(.secondary),
                                         at: CGPoint(x: x + barWidth / 2, y: size.height - 6))
                        }
                    }
                }
                .frame(height: 110)
                HStack {
                    Text(L("Ø \(Int(values.reduce(0, +) / Double(values.count))) s per round"))
                    Spacer()
                    Text(L("fastest \(Int(values.min() ?? 0)) s · slowest \(Int(values.max() ?? 0)) s"))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}
