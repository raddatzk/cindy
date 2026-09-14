import SwiftUI

/// Readiness for the next session: the score, what it means, and the two or
/// three measurements that drove it. Shown on the start screen above the
/// actions, because it is the answer to "should I train today?".
struct ReadinessCard: View {
    let readiness: Readiness
    /// Suggested date for the next session, shown when one can be derived.
    var nextSession: NextSession?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label(L("Readiness"), systemImage: "gauge.with.dots.needle.bottom.50percent")
                    .font(.headline)
                Spacer()
                Text(readiness.score.formatted(.number.locale(Localization.locale)))
                    .font(.title2.weight(.bold).monospacedDigit())
                    .foregroundStyle(color)
                    .accessibilityLabel(L("Readiness \(readiness.score) of 100"))
            }
            ProgressView(value: Double(readiness.score), total: 100)
                .tint(color)
            Text(title)
                .font(.subheadline.weight(.semibold))
            Text(advice)
                .font(.footnote)
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                ForEach(readiness.reasons()) { component in
                    Text(verbatim: "· \(component.detail)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let nextSession {
                Label(L("Next session \(nextSession.date.formatted(date: .abbreviated, time: .shortened, in: Localization.locale))"),
                      systemImage: "calendar")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.top, 2)
            }
            if !readiness.usesHealthData {
                Text(L("Estimated from your workout history alone — connect Apple Health for sleep and heart data."))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private var color: Color {
        switch readiness.band {
        case .rest: return .red
        case .easy: return .orange
        case .ready: return .brand
        case .primed: return .green
        }
    }

    private var title: String {
        switch readiness.band {
        case .rest: return L("Rest day")
        case .easy: return L("Take it easy")
        case .ready: return L("Ready")
        case .primed: return L("Primed")
        }
    }

    private var advice: String {
        switch readiness.band {
        case .rest: return L("Your body is still working on the last session. Walk, stretch, sleep.")
        case .easy: return L("Something short is fine — cut the duration or the reps.")
        case .ready: return L("Go for your usual plan.")
        case .primed: return L("A good day to push the plan one notch.")
        }
    }
}
