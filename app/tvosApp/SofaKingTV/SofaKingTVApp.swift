import SwiftUI
import SofaKingCore

@main
struct SofaKingTVApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView().environment(model)
        }
    }
}

/// Core's lineup, settings and playback session as SwiftUI state. Kotlin calls back on the main thread.
@MainActor
@Observable
final class AppModel {
    let core = SofaKingTv()
    private(set) var state: LineupState = LineupStateLoading.shared
    private(set) var settings: AppSettings?
    private var watches: [Watch] = []

    init() {
        watches.append(core.watchState { [weak self] in self?.state = $0 })
        watches.append(core.watchSettings { [weak self] in self?.settings = $0 })
    }

    var lineup: Lineup? { (state as? LineupStateReady)?.lineup }

    func channel(id: String) -> Channel? { lineup?.channels.first { $0.guideId == id } }
}

struct ContentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            switch model.state {
            case let ready as LineupStateReady:
                ChannelListView(lineup: ready.lineup)
            case is LineupStateUnconfigured:
                SettingsView()
            case let failed as LineupStateFailed:
                VStack(spacing: 32) {
                    Text("Can't load the lineup").font(.title2)
                    Text(failed.message).foregroundStyle(.secondary)
                    Button("Try again") { model.core.refresh() }
                    NavigationLink("Settings") { SettingsView() }
                }
            default:
                ProgressView("Loading the lineup")
            }
        }
    }
}
