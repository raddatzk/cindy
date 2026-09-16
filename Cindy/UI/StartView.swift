import SwiftUI

struct StartView: View {
    @Environment(AppModel.self) private var model
    @Binding var path: [Route]

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                header
                if let readiness = model.readiness {
                    ReadinessCard(readiness: readiness, nextSession: model.nextSession)
                }
                actions
            }
            .padding()
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    path.append(.settings)
                } label: {
                    Image(systemName: "gearshape")
                }
                .accessibilityLabel(L("Settings"))
            }
        }
        .onAppear { model.reload() }
        .task { await model.refreshReadiness() }
    }

    private var header: some View {
        VStack(spacing: 8) {
            Text("Cindy")
                .font(.system(size: 56, weight: .black, design: .rounded))
                .onLongPressGesture(minimumDuration: 1.5) {
                    path.append(.debug)
                }
            Text(L("AMRAP \(model.plan.durationMinutes) min"))
                .font(.title3.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(model.plan.summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 16)
    }

    private var actions: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Button {
                    path.append(.workout)
                } label: {
                    Label(L("Start workout"), systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .brandProminentButtonStyle()
                .controlSize(.large)
                .disabled(!model.isCalibrated)

                // Editing the plan sits next to starting it, not in a card of
                // its own — the header already says what the plan is.
                Button {
                    path.append(.plan)
                } label: {
                    Image(systemName: "slider.horizontal.3")
                        .padding(.vertical, 8)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .accessibilityLabel(L("Edit workout"))
            }

            // Calibration always carries a note, History only once a workout exists;
            // the two rows still read as a pair, so they share the taller height.
            EqualHeightVStack(spacing: 12) {
                Button {
                    path.append(.calibration)
                } label: {
                    secondaryLabel(model.isCalibrated ? L("Recalibrate") : L("Calibrate"),
                                   systemImage: "scope",
                                   note: calibrationNote,
                                   needsAttention: !model.isCalibrated)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                Button {
                    path.append(.history)
                } label: {
                    secondaryLabel(L("History"),
                                   systemImage: "clock.arrow.circlepath",
                                   note: model.lastCompletedRecord.map { L("Last \($0.score.notation)") })
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }
        }
    }

    /// Shared shape for the secondary actions: icon and title on one line, an
    /// optional status note underneath.
    private func secondaryLabel(_ title: String, systemImage: String,
                                note: String?, needsAttention: Bool = false) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(needsAttention ? AnyShapeStyle(.brand) : AnyShapeStyle(.secondary))
                }
            }
            Spacer()
            if needsAttention {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.brand)
            }
        }
        .padding(.vertical, 8)
        // Fills the height `EqualHeightVStack` hands out, so the button background grows with it.
        .frame(maxHeight: .infinity)
    }

    /// What still has to happen before a workout can start — or when the
    /// current calibration was recorded.
    private var calibrationNote: String {
        guard let profile = model.calibration else { return L("Not calibrated") }
        let missing = profile.missingExercises(for: model.plan)
        guard missing.isEmpty else {
            let names = ListFormatter.localizedString(byJoining: missing.map(\.displayName))
            return L("Missing: \(names)")
        }
        return profile.createdAt.formatted(date: .abbreviated, time: .shortened, in: Localization.locale)
    }
}

/// A vertical stack that gives every child the height of the tallest one.
private struct EqualHeightVStack: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let width = proposal.width
        let sizes = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)) }
        let rowHeight = sizes.map(\.height).max() ?? 0
        let totalHeight = rowHeight * CGFloat(subviews.count) + spacing * CGFloat(subviews.count - 1)
        return CGSize(width: width ?? sizes.map(\.width).max() ?? 0, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard !subviews.isEmpty else { return }
        let rowHeight = (bounds.height - spacing * CGFloat(subviews.count - 1)) / CGFloat(subviews.count)
        var y = bounds.minY
        for subview in subviews {
            subview.place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading,
                          proposal: ProposedViewSize(width: bounds.width, height: rowHeight))
            y += rowHeight + spacing
        }
    }
}
