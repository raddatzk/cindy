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
