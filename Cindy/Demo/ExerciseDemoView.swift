import SwiftUI

/// Shows what one exercise looks like, picking whichever renderer can actually
/// draw it: the bundled USDZ through RealityKit, otherwise the stick figure.
struct ExerciseDemoView: View {
    let exercise: Exercise
    /// Set while the view is off screen so the animation stops costing frames.
    var isPaused: Bool = false
    @State private var modelFailed = false

    var body: some View {
        let demo = exercise.demo
        if let url = Self.modelURL(for: demo), !modelFailed {
            RealityDemoView(url: url, perspective: demo.perspective) { modelFailed = true }
                .aspectRatio(1, contentMode: .fit)
        } else {
            StickFigureDemoView(demo: demo, isPaused: isPaused)
        }
    }

    /// The bundled animation for this exercise, if one has been added.
    ///
    /// Nothing bundled means the stick figure — that is the case for any
    /// exercise whose model has not been built yet, not an error. Built with
    /// `tools/build_exercise_usdz.py`.
    static func modelURL(for demo: ExerciseDemo) -> URL? {
        return Bundle.main.url(forResource: demo.modelName, withExtension: "usdz")
    }
}

/// The demo plus the form cues, as a sheet.
struct ExerciseDemoSheet: View {
    let exercise: Exercise
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    ExerciseDemoView(exercise: exercise)
                        .frame(maxWidth: .infinity)
                        .frame(maxHeight: 320)
                        .background(Color.panelBackground, in: RoundedRectangle(cornerRadius: 16))

                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(Array(exercise.demo.cues.enumerated()), id: \.offset) { index, cue in
                            cueRow(number: index + 1, cue)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding()
            }
            .navigationTitle(exercise.singularName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Done")) { dismiss() }
                }
            }
        }
    }

    private func cueRow(number: Int, _ cue: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number.formatted(.number.locale(Localization.locale)))
                .font(.caption.bold())
                .foregroundStyle(.onBrand)
                .frame(width: 22, height: 22)
                .background(Color.brand, in: Circle())
            Text(cue)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Opens `ExerciseDemoSheet`. Used wherever an exercise is named and the user
/// may not know what it is supposed to look like.
struct ExerciseDemoButton: View {
    enum Style {
        /// A full-width bordered button with a title.
        case prominent
        /// A bare icon, for list rows that are already busy.
        case icon
    }

    let exercise: Exercise
    var style: Style = .prominent
    @State private var showingDemo = false

    var body: some View {
        Button {
            showingDemo = true
        } label: {
            switch style {
            case .prominent:
                Label(L("Show me the movement"), systemImage: "figure.strengthtraining.traditional")
            case .icon:
                Image(systemName: "questionmark.circle")
            }
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L("Show the \(exercise.singularName) movement"))
        .sheet(isPresented: $showingDemo) {
            ExerciseDemoSheet(exercise: exercise)
        }
    }
}

#Preview {
    ExerciseDemoSheet(exercise: .pushUp)
}
