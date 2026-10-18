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
    /// The library item the player shows, from whichever library page asked for it.
    var vod: VodRequest?
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
    case live, library, settings
}

struct ContentView: View {
    @Environment(AppModel.self) private var model
    @State private var tab = AppTab.live

    var body: some View {
        TabView(selection: $tab) {
            live.tabItem { Label("Live", systemImage: "tv") }.tag(AppTab.live)
            if model.libraryShown {
                LibraryHomeView().tabItem { Label("Library", systemImage: "film.stack") }.tag(AppTab.library)
            }
            NavigationStack { SettingsView() }.tabItem { Label("Settings", systemImage: "gear") }.tag(AppTab.settings)
        }
        .onChange(of: model.requestedChannel) { _, requested in
            // A Top Shelf channel plays from the Live tab.
            if requested != nil { tab = .live }
        }
        .onChange(of: model.libraryShown) { _, shown in
            if !shown && tab == .library { tab = .live }
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

extension PairingFailure {
    /// What to tell the viewer when pairing fails this way.
    var message: String {
        switch self {
        case .invalidCode: "That code was not accepted. Codes last ten minutes and work once; show a new one in the admin portal."
        case .olderServer: "This server does not pair yet; enter the API token instead."
        default: "No Sofa King server answered at that address."
        }
    }
}
