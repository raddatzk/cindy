import SwiftUI

/// Edit the round: which exercises, how many reps / seconds, order and duration.
struct PlanEditorView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        List {
            Section(L("Duration")) {
                Stepper(value: $model.plan.durationMinutes, in: 1...60) {
                    Text(L("AMRAP \(model.plan.durationMinutes) min"))
                }
            }
            Section {
                ForEach(model.plan.sets) { set in
                    row(for: set)
                }
                .onMove { from, to in
                    model.plan.sets.move(fromOffsets: from, toOffset: to)
                }
            } header: {
                Text(L("Exercises per round"))
            } footer: {
                Text(L("Drag to reorder. The plank is measured in seconds and counts as a single unit towards the score."))
            }
            Section(L("Not in the plan")) {
                ForEach(Exercise.allCases.filter { !model.plan.contains($0) }) { exercise in
                    Button {
                        model.plan.setEnabled(exercise, true)
                    } label: {
                        Label(L("Add \(exercise.displayName)"), systemImage: "plus.circle")
                    }
                }
            }
            Section {
                Button(L("Reset to the original Cindy")) { model.plan = .cindy }
                    .disabled(model.plan == .cindy)
            }
        }
        .navigationTitle(L("Edit workout"))
        .toolbar { EditButton() }
    }

    private func row(for set: ExerciseSet) -> some View {
        @Bindable var model = model
        return HStack {
            Stepper(
                value: Binding(
                    get: { model.plan.target(for: set.exercise) },
                    set: { model.plan.setTarget($0, for: set.exercise) }
                ),
                in: 1...300,
                step: set.exercise.isHold ? 5 : 1
            ) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(set.exercise.displayName)
                    Text(verbatim: "\(set.target) \(set.exercise.unit)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            ExerciseDemoButton(exercise: set.exercise, style: .icon)
                .foregroundStyle(.secondary)
            Button(role: .destructive) {
                model.plan.setEnabled(set.exercise, false)
            } label: {
                Image(systemName: "minus.circle")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("Remove \(set.exercise.displayName)"))
            .disabled(model.plan.sets.count == 1)
        }
    }
}
