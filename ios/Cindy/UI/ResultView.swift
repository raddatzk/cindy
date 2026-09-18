import SwiftUI

struct ResultView: View {
    @Environment(AppModel.self) private var model
    let record: WorkoutRecord
    var onClose: () -> Void
    @State private var adopted = false

    private var recommendation: ProgressionAdvisor.Recommendation {
        ProgressionAdvisor().recommend(after: record, previous: model.lastCompletedRecord)
    }

    var body: some View {
        ScrollView {
        VStack(spacing: 20) {
            Text(record.completed ? L("Time!") : L("Stopped"))
                .font(.title)
                .foregroundStyle(.secondary)
            Text(record.score.notation)
                .font(.system(size: 96, weight: .black, design: .rounded).monospacedDigit())
            Text(L("\(record.rounds) rounds + \(record.extraReps) reps · \(record.score.totalReps) total reps"))
                .foregroundStyle(.secondary)
            if let plankSeconds = record.plankSeconds {
                // Held after the AMRAP, outside the score.
                Label(ExerciseSet(exercise: .plank, target: plankSeconds).label, systemImage: "timer")
                    .foregroundStyle(.secondary)
            }
            comparison
            RoundChart(record: record)
            recommendationCard
            HStack(spacing: 16) {
                Button(L("Discard"), role: .destructive) { onClose() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                Button {
                    model.save(record)
                    onClose()
                } label: {
                    Text(L("Save"))
                        .frame(maxWidth: .infinity)
                }
                .brandProminentButtonStyle()
                .controlSize(.large)
            }
        }
        .padding()
        }
        .navigationTitle(L("Result"))
        .navigationBarBackButtonHidden(true)
    }

    private var recommendationCard: some View {
        let rec = recommendation
        return VStack(alignment: .leading, spacing: 8) {
            Label(L("Next time"), systemImage: "arrow.up.right.circle")
                .font(.headline)
            Text(rec.reason)
                .font(.subheadline)
            if rec.changesPlan {
                Text(L("\(rec.plan.durationMinutes) min · \(rec.plan.summaryWithPlank)"))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                Button {
                    model.plan = rec.plan
                    adopted = true
                } label: {
                    Label(adopted ? L("Adopted") : L("Adopt into plan"), systemImage: adopted ? "checkmark" : "slider.horizontal.3")
                }
                .buttonStyle(.bordered)
                .disabled(adopted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private var comparison: some View {
        if let last = model.lastCompletedRecord {
            let diff = record.score.totalReps - last.score.totalReps
            VStack(spacing: 4) {
                Text(L("Previous result: \(last.score.notation)"))
                // The sign and the arrow say better or worse; the colour only
                // repeats it, and green or red text would be too faint to read.
                Label {
                    Text(diff >= 0 ? L("+\(diff) reps") : L("\(diff) reps"))
                } icon: {
                    Image(systemName: diff >= 0 ? "arrow.up.right.circle.fill" : "arrow.down.right.circle.fill")
                        .foregroundStyle(diff >= 0 ? Color.green : Color.red)
                }
                .font(.headline)
            }
            .padding()
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        } else {
            Text(L("First saved workout"))
                .foregroundStyle(.secondary)
        }
    }
}
