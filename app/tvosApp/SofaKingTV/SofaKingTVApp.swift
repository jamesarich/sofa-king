import SwiftUI
import TVServices
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
    /// Whether the one cold-start decision, opening the last channel, has been made.
    var launchDecided = false
    /// A channel a `sofaking://channel/<guideId>` link (the Top Shelf) asked for; the list or the player takes it and clears it.
    var requestedChannel: String?
    private var watches: [Watch] = []
    private var published: [TopShelfChannel] = []

    init() {
        #if DEBUG
        // `xcrun simctl launch booted dev.jamesrich.sofaking.tv -serverUrl http://host:port -apiToken TOKEN`
        // saves a server without typing on the simulator's keyboard.
        let args = UserDefaults.standard
        if let url = args.string(forKey: "serverUrl") {
            core.saveServer(url: url, apiToken: args.string(forKey: "apiToken") ?? "")
        }
        #endif
        watches.append(core.watchState { [weak self] in
            self?.state = $0
            self?.publishTopShelf()
        })
        watches.append(core.watchSettings { [weak self] in
            self?.settings = $0
            self?.publishTopShelf()
        })
    }

    var lineup: Lineup? { (state as? LineupStateReady)?.lineup }

    var favorites: Set<String> { Set(settings?.favorites.compactMap { $0 as? String } ?? []) }

    /// The channels watched most recently, newest first, as the lineup lists them now.
    var recentChannels: [Channel] {
        guard let lineup, let recent = settings?.recentChannels else { return [] }
        let byId = Dictionary(lineup.channels.map { ($0.guideId, $0) }, uniquingKeysWith: { first, _ in first })
        return recent.compactMap { ($0 as? String).flatMap { byId[$0] } }
    }

    /// Hands the recent channels to the Top Shelf extension whenever they, or the lineup behind them, change.
    private func publishTopShelf() {
        let channels = recentChannels.map {
            TopShelfChannel(guideId: $0.guideId, number: $0.number.formatted(), name: $0.name, logo: $0.logo)
        }
        guard channels != published, lineup != nil else { return }
        published = channels
        TopShelfChannels.save(channels)
        TVTopShelfContentProvider.topShelfContentDidChange()
    }
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
        .onOpenURL { url in
            if let guideId = TopShelfChannels.guideId(from: url) { model.requestedChannel = guideId }
        }
    }
}
