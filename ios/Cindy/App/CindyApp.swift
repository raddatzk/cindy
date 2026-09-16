import BackgroundTasks
import SwiftUI

@main
struct CindyApp: App {
    /// Background refresh, so the pending reminder can move without the app
    /// being opened — a scheduled notification cannot re-evaluate itself.
    nonisolated static let refreshTaskIdentifier = "me.raddatz.cindy.refresh"

    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(\.locale, model.language.locale)
                .preferredColorScheme(model.theme.colorScheme)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { CindyApp.scheduleRefresh() }
        }
        .backgroundTask(.appRefresh(CindyApp.refreshTaskIdentifier)) {
            await model.refreshReadiness()
            CindyApp.scheduleRefresh()
        }
    }

    /// Asks for a wake-up in half a day or later; the system decides when it
    /// really happens, and may skip it entirely.
    ///
    /// `nonisolated` because `App` is main-actor isolated and so is everything
    /// in it by default, while the `backgroundTask` closure runs off the main
    /// actor — which is a warning today and an error under Swift 6.
    /// `BGTaskScheduler` has no main-actor requirement of its own.
    private nonisolated static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTaskIdentifier)
        request.earliestBeginDate = Date().addingTimeInterval(12 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }
}
