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
    private(set) var capabilities: ServerCapabilities?
    private(set) var libraryHome: LibraryHomeState = LibraryHomeStateLoading.shared
    private(set) var recordings: RecordingsState = RecordingsStateLoading.shared
    /// Which guide programs are set to record; empty on a server that does not record.
    private(set) var recordingMarks: RecordingMarks?
    /// The library item the player shows, from whichever library page asked for it.
    var vod: VodRequest?
    /// The players' sleep timer, which lasts through Up next until the viewer leaves the player.
    var sleep = SleepTimer()
    /// The items Up next played by itself since the viewer last pressed anything, for "Still watching?".
    var autoplays = 0
    /// Whether the one cold-start decision, opening the last channel, has been made.
    var launchDecided = false
    /// A channel a `sofaking://channel/<guideId>` link (the Top Shelf) asked for; the list or the player takes it and clears it.
    var requestedChannel: String?
    /// Why the last `sofaking://pair` link failed to pair; an alert shows it once.
    var pairingFailure: String?
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
        watches.append(core.watchCapabilities { [weak self] in self?.capabilities = $0 })
        watches.append(core.watchLibraryHome { [weak self] in self?.libraryHome = $0 })
        watches.append(core.watchRecordings { [weak self] in self?.recordings = $0 })
        watches.append(core.watchRecordingMarks { [weak self] in self?.recordingMarks = $0 })
    }

    var lineup: Lineup? { (state as? LineupStateReady)?.lineup }

    /// Pairs with the server a `sofaking://pair` link names; success shows as the lineup loading, failure as an alert.
    func pair(_ request: PairingRequest) {
        Task {
            let failure = try? await core.pair(url: request.serverUrl, code: request.code)
            pairingFailure = failure.map(\.message)
        }
    }

    /// The Library tab shows when the server serves libraries and has at least one.
    var libraryShown: Bool {
        guard capabilities?.library == true else { return false }
        switch libraryHome {
        case is LibraryHomeStateUnavailable: return false
        case let ready as LibraryHomeStateReady: return !ready.home.libraries.isEmpty
        default: return true
        }
    }

    /// The Recordings tab shows when the server records.
    var recordingsShown: Bool { capabilities?.recordings == true }

    /// The tab a cold start opens instead of Live, as the Compose app decides: where the viewer left off when that
    /// was Library or Recordings, or Library when Open on start says so. Nothing plays by itself there.
    var startTab: AppTab? {
        guard let settings, !settings.serverUrl.isEmpty else { return nil }
        if settings.startup == .library { return .library }
        guard settings.startup == .resume else { return nil }
        switch settings.lastTab {
        case .library: return .library
        case .recordings: return .recordings
        default: return nil
        }
    }

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

/// The top-level tabs, in the tvOS tab bar along the top.
enum AppTab: Hashable {
    case live, library, recordings, settings

    /// The name the settings remember the tab by; Settings is not remembered.
    var remembered: TopLevelTab? {
        switch self {
        case .live: .live
        case .library: .library
        case .recordings: .recordings
        case .settings: nil
        }
    }
}

struct ContentView: View {
    @Environment(AppModel.self) private var model
    @State private var tab = AppTab.live
    /// Whether the start has picked its tab, once the settings are in.
    @State private var tabRestored = false

    var body: some View {
        TabView(selection: $tab) {
            live.tabItem { Label("Live", systemImage: "tv") }.tag(AppTab.live)
            // A start on Library or Recordings shows the tab before the server says it offers it.
            if model.libraryShown || tab == .library {
                LibraryHomeView().tabItem { Label("Library", systemImage: "film.stack") }.tag(AppTab.library)
            }
            if model.recordingsShown || tab == .recordings {
                RecordingsView().tabItem { Label("Recordings", systemImage: "record.circle") }.tag(AppTab.recordings)
            }
            NavigationStack { SettingsView() }.tabItem { Label("Settings", systemImage: "gear") }.tag(AppTab.settings)
        }
        .onChange(of: model.requestedChannel) { _, requested in
            // A Top Shelf channel plays from the Live tab.
            if requested != nil { tab = .live }
        }
        .onChange(of: model.settings == nil, initial: true) { _, missing in
            guard !missing, !tabRestored else { return }
            tabRestored = true
            if let start = model.startTab { tab = start }
        }
        .onChange(of: tab) { _, picked in
            if let remembered = picked.remembered { model.core.setLastTab(tab: remembered) }
        }
        .onChange(of: model.libraryShown) { _, shown in
            if !shown && tab == .library { tab = .live }
        }
        .onChange(of: model.libraryHome is LibraryHomeStateUnavailable) { _, gone in
            if gone && tab == .library { tab = .live }
        }
        .onChange(of: model.recordingsShown) { _, shown in
            if !shown && tab == .recordings { tab = .live }
        }
        .onChange(of: model.recordings is RecordingsStateUnavailable) { _, gone in
            if gone && tab == .recordings { tab = .live }
        }
        .onOpenURL { url in
            if let guideId = TopShelfChannels.guideId(from: url) {
                model.requestedChannel = guideId
            } else if let request = PairingLinkKt.parsePairingLink(link: url.absoluteString) {
                model.pair(request)
            }
        }
        .alert("Pairing failed", isPresented: Binding(get: { model.pairingFailure != nil }, set: { if !$0 { model.pairingFailure = nil } })) {
            Button("OK") {}
        } message: {
            Text(model.pairingFailure ?? "")
        }
    }

    private var live: some View {
        NavigationStack {
            switch model.state {
            case let ready as LineupStateReady:
                ChannelListView(lineup: ready.lineup)
            case is LineupStateUnconfigured:
                SettingsView()
            case let failed as LineupStateFailed:
                VStack(spacing: 32) {
                    Text("Couldn't reach the Sofa King server").font(.title2)
                    Text(failed.message).foregroundStyle(.secondary)
                    Button("Try again") { model.core.refresh() }
                    NavigationLink("Settings") { SettingsView() }
                }
            default:
                ProgressView("Sofa King close, loading channels…")
            }
        }
    }
}

extension PairingFailure {
    /// What to tell the viewer when pairing fails this way.
    var message: String {
        switch self {
        case .invalidCode: "That code didn't work. Codes last ten minutes and work once; show a new one in the admin portal."
        case .olderServer: "This server is too old to pair with a code; enter its API token instead."
        default: "No Sofa King server answered at that address."
        }
    }
}
