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
                .buttonStyle(.borderedProminent)
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
                Text(L("\(rec.plan.durationMinutes) min · \(rec.plan.summary)"))
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
                Text(diff >= 0 ? L("+\(diff) reps") : L("\(diff) reps"))
                    .font(.headline)
                    .foregroundStyle(diff >= 0 ? Color.green : Color.red)
            }
            .padding()
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        } else {
            Text(L("First saved workout"))
                .foregroundStyle(.secondary)
        }
    }
}
