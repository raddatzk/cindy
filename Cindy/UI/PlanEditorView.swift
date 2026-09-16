import SwiftUI

/// Edit the round: which exercises, how many reps / seconds, order and duration.
struct PlanEditorView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        PlanEditorList(plan: $model.plan) {
            Section {
                Button(L("Reset to the original Cindy")) { model.plan = .cindy }
                    .disabled(model.plan == .cindy)
            }
        }
        .navigationTitle(L("Edit workout"))
    }
}

/// The editing list, shared by the start screen's editor and the workout's pause screen.
///
/// Never in edit mode: the reorder handles and the remove buttons are always shown, and rows
/// drag after a long press. VoiceOver gets move actions instead of dragging.
struct PlanEditorList<Trailing: View>: View {
    @Binding var plan: WorkoutPlan
    /// Exercises on offer to add; during a workout only the calibrated ones.
    let addable: [Exercise]
    /// Shortest duration on offer; during a workout the clock may be past the shorter ones.
    let minimumMinutes: Int
    let trailing: Trailing

    init(plan: Binding<WorkoutPlan>, addable: [Exercise] = Exercise.allCases,
         minimumMinutes: Int = WorkoutPlan.durationChoices.first ?? 5,
         @ViewBuilder trailing: () -> Trailing) {
        _plan = plan
        self.addable = addable
        self.minimumMinutes = minimumMinutes
        self.trailing = trailing()
    }

    var body: some View {
        List {
            Section(L("Duration")) {
                Stepper(value: $plan.durationMinutes,
                        in: minimumMinutes...max(minimumMinutes, WorkoutPlan.durationChoices.last ?? 20), step: 5) {
                    Text(L("AMRAP \(plan.durationMinutes) min"))
                }
            }
            Section {
                ForEach(plan.sets) { set in
                    row(for: set)
                }
                .onMove { from, to in
                    plan.sets.move(fromOffsets: from, toOffset: to)
                }
            } header: {
                Text(L("Exercises per round"))
            } footer: {
                Text(L("Drag to reorder. The plank is measured in seconds and counts as a single unit towards the score."))
            }
            let missing = addable.filter { !plan.contains($0) }
            if !missing.isEmpty {
                Section(L("Not in the plan")) {
                    ForEach(missing) { exercise in
                        Button {
                            plan.setEnabled(exercise, true)
                        } label: {
                            Label(L("Add \(exercise.displayName)"), systemImage: "plus.circle")
                        }
                    }
                }
            }
            trailing
        }
    }

    private func row(for set: ExerciseSet) -> some View {
        let canRemove = plan.sets.count > 1
        return HStack {
            Stepper(
                value: Binding(
                    get: { plan.target(for: set.exercise) },
                    set: { plan.setTarget($0, for: set.exercise) }
                ),
                in: WorkoutPlan.targetRange(for: set.exercise),
                step: set.exercise.isHold ? 5 : 1
            ) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(set.exercise.displayName)
                    Text(verbatim: "\(set.target) \(set.exercise.unit)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            ExerciseDemoButton(exercise: set.exercise, style: .icon, renderer: .stickFigure)
            Button(role: .destructive) {
                plan.setEnabled(set.exercise, false)
            } label: {
                Image(systemName: "minus.circle")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("Remove \(set.exercise.displayName)"))
            .disabled(!canRemove)
            Image(systemName: "line.3.horizontal")
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .accessibilityActions {
            if !plan.isFirst(set.exercise) {
                Button(L("Move up")) { plan.move(set.exercise, by: -1) }
            }
            if !plan.isLast(set.exercise) {
                Button(L("Move down")) { plan.move(set.exercise, by: 1) }
            }
        }
    }
}
