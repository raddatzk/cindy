import SwiftUI

struct HistoryView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if model.history.isEmpty {
                ContentUnavailableView(L("No workouts yet"), systemImage: "clock.arrow.circlepath",
                                       description: Text(L("Saved results show up here.")))
            } else {
                List {
                    ForEach(model.history) { record in
                        NavigationLink(value: record) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(record.date.formatted(date: .abbreviated, time: .shortened, in: Localization.locale))
                                Text(record.completed ? WorkoutScreenTime.format(record.plannedDuration)
                                     : L("stopped after \(WorkoutScreenTime.format(record.durationSeconds))"))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(record.score.notation)
                                .font(.title2.bold().monospacedDigit())
                        }
                        }
                    }
                    .onDelete { offsets in
                        for index in offsets {
                            model.delete(model.history[index])
                        }
                    }
                }
            }
        }
        .navigationTitle(L("History"))
        .navigationDestination(for: WorkoutRecord.self) { record in
            WorkoutDetailView(record: record)
        }
        .onAppear { model.reload() }
    }
}

struct WorkoutDetailView: View {
    let record: WorkoutRecord

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text(record.score.notation)
                    .font(.system(size: 72, weight: .black, design: .rounded).monospacedDigit())
                Text(record.date.formatted(date: .long, time: .shortened, in: Localization.locale))
                    .foregroundStyle(.secondary)
                if let plan = record.plan {
                    Text(L("\(plan.durationMinutes) min · \(plan.summary)"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                RoundChart(record: record)
            }
            .padding()
        }
        .navigationTitle(L("Workout"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

enum WorkoutScreenTime {
    static func format(_ seconds: TimeInterval) -> String {
        let total = Int(seconds)
        return String(format: "%02d:%02d", total / 60, total % 60)
    }
}
