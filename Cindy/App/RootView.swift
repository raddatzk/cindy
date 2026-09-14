import SwiftUI

enum Route: Hashable {
    case calibration
    case workout
    case result(WorkoutRecord)
    case history
    case plan
    case settings
    case recordings
    case debug
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            StartView(path: $path)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .calibration:
                        CalibrationView()
                    case .workout:
                        WorkoutView { record in
                            path = [.result(record)]
                        }
                    case .result(let record):
                        ResultView(record: record) { path = [] }
                    case .history:
                        HistoryView()
                    case .plan:
                        PlanEditorView()
                    case .settings:
                        SettingsView()
                    case .recordings:
                        RecordingsView()
                    case .debug:
                        DebugRecorderView()
                    }
                }
        }
        // Strings are resolved eagerly via `L(…)`, so a language change has to
        // rebuild the stack. The path lives above this and survives.
        .id(model.language)
        .tint(.brand)
        // Driven by the model rather than shown once in `onAppear`, so a fresh
        // install gets the intro before the first navigation, and dismissing it
        // and marking it seen stay the same event.
        .fullScreenCover(isPresented: Binding(get: { !model.hasSeenIntro },
                                              set: { _ in model.markIntroSeen() })) {
            OnboardingView(onCalibrate: { path = [.calibration] })
        }
    }
}
