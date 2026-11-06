import AVKit
import SwiftUI
import SofaKingCore
#if os(tvOS)
import TVServices
#endif

/// The words the Compose screens share with this app, kept in core so the two cannot drift apart.
let strings = TvCopy.shared
let recordingStrings = TvRecordingCopy.shared
let searchStrings = TvSearchCopy.shared
let spokenStrings = TvSpokenCopy.shared

/// Core's lineup, settings and playback session as SwiftUI state. Kotlin calls back on the main thread.
@MainActor
@Observable
final class AppModel {
    let core: SofaKingTv
    /// The one live player: the guide's preview plays on it, and the full screen player takes its stream over.
    let livePlayer: AVPlayer = AppPlayer()
    private(set) var state: LineupState = LineupStateLoading.shared
    private(set) var settings: AppSettings?
    private(set) var capabilities: ServerCapabilities?
    private(set) var libraryHome: LibraryHomeState = LibraryHomeStateLoading.shared
    private(set) var recordings: RecordingsState = RecordingsStateLoading.shared
    /// Which guide programs are set to record; empty on a server that does not record.
    private(set) var recordingMarks: RecordingMarks?
    /// What the household's watchlist holds; `offered` is false on a server without one.
    private(set) var watchlist: WatchlistMarks?
    /// The library item the player shows, from whichever library page asked for it.
    var vod: VodRequest?
    /// The music, which plays apart from any page.
    let music: MusicPlayer
    /// Whether Picture in Picture is playing the full screen player's stream; always false on the Apple TV.
    var pictureInPicture = false
    /// Whether Now playing is open over the tabs.
    var nowPlayingShown = false
    /// The players' sleep timer, which lasts through Up next until the viewer leaves the player.
    var sleep = SleepTimer()
    /// The items Up next played by itself since the viewer last pressed anything, for "Still watching?".
    var autoplays = 0
    /// Whether the one cold-start decision, opening the last channel, has been made.
    var launchDecided = false
    /// A channel a `sofaking://channel/<guideId>` link (the Top Shelf) asked for; the list or the player takes it and clears it.
    var requestedChannel: String?
    /// A library item a `sofaking://vod/<id>` link (the Top Shelf's Continue watching, or a shortcut) asked for; the
    /// Library tab plays it once its home lists it.
    var requestedItem: String?
    /// Where that link starts the item, in milliseconds; negative to resume where the household stopped.
    var requestedItemStartMs: Int64 = -1
    /// A library item a `sofaking://item/<id>` link (the Top Shelf's select) asked to show the page of; the Library
    /// tab opens it and clears it.
    var requestedDetails: String?
    /// A channel number or name a `sofaking://live/<number>` link asked for, until the lineup is in to find it.
    private(set) var requestedLive: String?
    /// Why the last `sofaking://pair` link failed to pair; an alert shows it once.
    var pairingFailure: String?
    /// A pairing link for another server than the saved one, waiting for the viewer's Switch or Cancel.
    var pairingAsk: PairingRequest?
    /// A pairing link that arrived before the settings loaded.
    private var pairingWaiting: PairingRequest?
    /// Whether the PIN opened Settings; leaving the Settings tab or the app locks it again.
    var settingsUnlocked = false
    /// Turning on kids mode asks for the Live tab; the tab bar takes it and clears it.
    var liveRequested = false
    /// Wrong PINs in a row and the wait the fifth starts, which last as long as the process; `pinWaitEnds` is when the
    /// wait ends, for the lock page to read again then.
    @ObservationIgnored private let pinThrottle = PinThrottle()
    private(set) var pinWaitEnds: Date?
    private var watches: [Watch] = []
    #if os(tvOS)
    private var published: [TopShelfChannel] = []
    private var publishedContinue: [TopShelfItem]?
    #endif

    init() {
        let core = SofaKingTv()
        self.core = core
        music = MusicPlayer(core: core)
        #if DEBUG
        // `xcrun simctl launch booted dev.jamesrich.sofaking -serverUrl http://host:port -apiToken TOKEN` (or the
        // `.tv` bundle) saves a server without typing on the simulator's keyboard; see `LaunchSettings` for `-muted`.
        let args = UserDefaults.standard
        if let url = args.string(forKey: "serverUrl") {
            core.saveServer(url: url, apiToken: args.string(forKey: "apiToken") ?? "")
        }
        #endif
        watches.append(core.watchState { [weak self] in
            self?.state = $0
            self?.publishTopShelf()
            self?.openRequestedLive()
        })
        watches.append(core.watchSettings { [weak self] in
            self?.settings = $0
            self?.publishTopShelf()
            if let waiting = self?.pairingWaiting {
                self?.pairingWaiting = nil
                self?.pair(waiting)
            }
        })
        watches.append(core.watchCapabilities { [weak self] in self?.capabilities = $0 })
        watches.append(core.watchLibraryHome { [weak self] in
            self?.libraryHome = $0
            self?.publishContinueWatching()
        })
        watches.append(core.watchRecordings { [weak self] in self?.recordings = $0 })
        watches.append(core.watchRecordingMarks { [weak self] in self?.recordingMarks = $0 })
        watches.append(core.watchWatchlist { [weak self] in self?.watchlist = $0 })
    }

    /** The lineup as this device shows it: without the channels it hides, the household's included. */
    var lineup: Lineup? { (state as? LineupStateReady)?.visible }

    /// Plays the songs among `tracks` from `start` (the first when nil), shuffled with `shuffled`, and opens Now playing.
    func playMusic(_ tracks: [MediaItem], from start: MediaItem?, shuffled: Bool = false) {
        guard let queue = core.musicQueue(tracks: tracks, startId: start?.id, shuffled: shuffled) else { return }
        music.play(queue)
        nowPlayingShown = true
    }

    /// Pairs with the server a `sofaking://pair` link names, after a question when it is not the saved one; success
    /// shows as the lineup loading, failure as an alert.
    func pair(_ request: PairingRequest) {
        // A link that opens a cold start waits for the saved settings, which say whether it names another server.
        guard let settings else {
            pairingWaiting = request
            return
        }
        switch PairingDecisionKt.decidePairing(configuredServer: settings.serverUrl, request: request) {
        case .ask: pairingAsk = request
        case .pair: pairNow(request)
        default: break
        }
    }

    /// Pairs without asking: the link names the saved server, or there is none.
    func pairNow(_ request: PairingRequest) {
        pairingAsk = nil
        Task {
            let failure = try? await core.pair(url: request.serverUrl, code: request.code)
            pairingFailure = failure.map(\.message)
        }
    }

    /// The Library tab shows when the server serves libraries and has at least one. It waits for the first list, so
    /// a server without libraries never shows the tab only to take it away again.
    var libraryShown: Bool {
        guard capabilities?.library == true else { return false }
        switch libraryHome {
        case is LibraryHomeStateUnavailable, is LibraryHomeStateLoading: return false
        case let ready as LibraryHomeStateReady: return !ready.home.libraries.isEmpty
        default: return true
        }
    }

    /// The DVR tab shows when the server records, outside kids mode.
    var recordingsShown: Bool { capabilities?.recordings == true && settings?.kidsMode != true }

    /// Whether the lock makes the viewer wait before the next PIN.
    var pinWaiting: Bool { pinWaitEnds != nil && pinThrottle.waiting }

    /// A wrong PIN; the fifth in a row makes the next try wait 30 seconds, and the count starts over.
    func pinMissed() {
        pinThrottle.missed()
        pinWaitEnds = pinThrottle.waiting ? Date().addingTimeInterval(Double(pinThrottle.remainingMs) / 1000) : nil
    }

    /// The right PIN, or the lock removed.
    func pinPassed() {
        pinThrottle.passed()
        pinWaitEnds = nil
    }

    /// Whether the server has said yet if [tab] belongs in the tab bar; Library also waits for the first list. The
    /// empty capabilities held until the bootstrap answers say nothing, or a start drops the tab it reopened.
    func offerKnown(_ tab: AppTab) -> Bool {
        guard let capabilities, capabilities.answered else { return false }
        return tab != .library || capabilities.library != true || !(libraryHome is LibraryHomeStateLoading)
    }

    /// The tab a cold start opens instead of Live, as the Compose app decides: where the viewer left off when that
    /// was Library or DVR, or Library when Open on start says so. Nothing plays by itself there.
    var startTab: AppTab? {
        guard let settings, !settings.serverUrl.isEmpty else { return nil }
        if settings.startup == .library { return .library }
        guard settings.startup == .resume else { return nil }
        switch settings.lastTab {
        case .library: return .library
        case .recordings: return settings.kidsMode ? nil : .recordings
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

    #if !os(tvOS)
    private func publishTopShelf() {}

    private func publishContinueWatching() {}
    #else
    /// Hands the recent channels to the Top Shelf extension whenever they, or the lineup behind them, change.
    private func publishTopShelf() {
        let channels = recentChannels.map {
            TopShelfChannel(guideId: $0.guideId, number: $0.number.formatted(), name: $0.name, logo: $0.logo)
        }
        guard channels != published, lineup != nil else { return }
        published = channels
        TopShelfContent.save(channels)
        TVTopShelfContentProvider.topShelfContentDidChange()
    }

    /// Hands the library's Continue watching to the Top Shelf extension whenever it changes; a server without
    /// libraries clears it.
    private func publishContinueWatching() {
        let items: [TopShelfItem]
        switch libraryHome {
        case let ready as LibraryHomeStateReady:
            let shelf = ready.home.shelves.first { $0.id == LibraryChoicesKt.CONTINUE_SHELF }
            items = (shelf?.items ?? []).map { item in
                let title = [item.showTitle, item.episodeLabel, item.title].compactMap { $0 }.joined(separator: " · ")
                let progress = item.progress.flatMap { $0.inProgress ? $0.fraction?.doubleValue : nil }
                return TopShelfItem(id: item.id, title: title, image: item.backdrop ?? item.poster, progress: progress)
            }
        case is LibraryHomeStateUnavailable:
            items = []
        default:
            return
        }
        guard items != publishedContinue else { return }
        publishedContinue = items
        TopShelfContent.saveContinue(items)
        TVTopShelfContentProvider.topShelfContentDidChange()
    }
    #endif

    /// Plays the item a `sofaking://vod/<id>` link asked for, once the library home lists it; one it does not list
    /// is let go.
    func openRequestedItem() {
        guard let id = requestedItem, let ready = libraryHome as? LibraryHomeStateReady else { return }
        requestedItem = nil
        let item = ready.home.shelves.lazy.flatMap { $0.items }.first { $0.id == id }
        if let item, item.kind.playable { vod = VodRequest(item: item, fromStart: false, startMs: requestedItemStartMs) }
    }

    /// Follows a `sofaking://` link: an item plays from the Library tab, a channel from Live once the lineup lists it.
    func open(_ link: AppLink) {
        switch link {
        case let item as AppLinkItem:
            requestedItemStartMs = item.startMs?.int64Value ?? -1
            requestedItem = item.itemId
        case let details as AppLinkDetails:
            requestedDetails = details.itemId
        case let live as AppLinkLive:
            requestedLive = live.channel
            openRequestedLive()
        default:
            break
        }
    }

    /// Plays the channel a `sofaking://live` link named, by number and then by name, once the lineup is in; a channel
    /// it does not list is let go.
    private func openRequestedLive() {
        guard let target = requestedLive, let lineup else { return }
        requestedLive = nil
        if let channel = AppLinkKt.linkedChannel(lineup: lineup, target: target) { requestedChannel = channel.guideId }
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

extension PairingFailure {
    /// What to tell the viewer when pairing fails this way.
    var message: String {
        strings.pairingFailure(failure: self)
    }
}
