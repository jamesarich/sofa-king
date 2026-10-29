import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// One channel full screen on AVPlayer, through a server watch session that core opens, renews and releases.
/// Menu dismisses it; leaving releases the session, and backgrounding releases it until the app returns.
/// A stream that fails or stalls is reopened through the same session after a growing pause, as the Compose app does.
/// The transport bar's menu records the program on now, stars the channel and switches to another: recent, favorites
/// or any channel. While the program on now is recording, Watch from start plays the recording from its start over the
/// live player, and Menu from it comes back to the channel live. With Settings › During ads on a logo, the break screen covers the ads a streaming channel marks, as
/// the server places them from the segments AVPlayer fetched; Show picture over it brings the picture back for that break.
/// The menu's Sleep timer leaves the player when it runs out, a number of minutes on or at the end of the program on now.
struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @State private var channel: Channel
    @State private var failure: String?
    @State private var replaced: Watch?
    @State private var itemObservers: [AnyCancellable] = []
    @State private var reconnect: Task<Void, Never>?
    @State private var attempts = 0
    /// False once the player has left: a tune or reconnect still running then must not play or open anything.
    @State private var shown = false
    /// The ad break playing now, as the server last said; nil outside one.
    @State private var adBreak: AdBreakNow?
    /// Show picture was chosen during this break; cleared once it ends.
    @State private var breakDismissed = false
    /// The line this break screen was dealt, and the rest of the shuffled lines, so none comes back until all have shown.
    @State private var breakQuip = ""
    @State private var quipDeck: [String] = []
    /// When the break screen came up, for Ads dodged today; nil while it is down.
    @State private var coveredSince: Date?
    /// What TMDB says about the program titled `factsTitle`, for the break screen; nil when nothing is known.
    @State private var programFacts: ProgramFacts?
    @State private var factsTitle: String?
    /// The recording of the program on now, played from its start over the live player; the channel waits under it.
    @State private var fromStart: VodRequest?
    /// The multiview opened from this channel; the channel waits under it, and tunes again when it closes.
    @State private var multiview: Channel?
    /// The channel the multiview's click picked to watch full screen.
    @State private var multiviewPick: Channel?

    init(channel: Channel) {
        _channel = State(initialValue: channel)
    }

    var body: some View {
        ZStack {
            PlayerController(player: player, menuItems: menuItems, actions: breakActions, breakScreen: breakScreen).ignoresSafeArea()
            if let failure {
                Text(failure).padding().background(.thinMaterial, in: .rect(cornerRadius: 16))
            }
        }
        .fullScreenCover(item: $fromStart, onDismiss: { Task { await tune() } }) { request in
            VodPlayerView(request: request)
        }
        .fullScreenCover(item: $multiview, onDismiss: returnFromMultiview) { from in
            MultiviewView(from: from) { multiviewPick = $0 }
        }
        .task { await tune() }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if model.sleep.due() {
                    model.sleep = SleepTimer()
                    dismiss()
                    return
                }
            }
        }
        .task(id: "\(channel.guideId) \(liveBreaks.name)") { await watchAdBreaks() }
        .onChange(of: breakScreen != nil) { _, up in
            applyBreakSound()
            if up { startCovering() } else { stopCovering() }
        }
        .onAppear {
            shown = true
            // A channel ends the music.
            model.music.stop()
            player.isMuted = false
            replaced = model.core.watchReplaced { stream in play(stream) }
        }
        .onDisappear {
            shown = false
            model.sleep = SleepTimer()
            stopCovering()
            replaced?.cancel()
            reconnect?.cancel()
            itemObservers.removeAll()
            player.replaceCurrentItem(with: nil)
            model.core.stopWatching()
        }
        .onChange(of: scenePhase) { _, phase in
            // A cover over the channel owns the shared player slot and its own session.
            guard fromStart == nil, multiview == nil else { return }
            switch phase {
            case .background:
                stopCovering()
                reconnect?.cancel()
                player.replaceCurrentItem(with: nil)
                Task { try? await model.core.release() }
            case .active where player.currentItem == nil:
                Task {
                    if let stream = try? await model.core.reopen() { play(stream) }
                }
            default:
                break
            }
        }
        .onChange(of: model.requestedChannel) { _, requested in
            // A Top Shelf link while the player is up switches it to that channel.
            guard let requested, let next = model.lineup?.channels.first(where: { $0.guideId == requested }) else { return }
            model.requestedChannel = nil
            switchTo(next)
        }
    }

    /// The app's one live player, which the guide's preview may already be playing this channel on.
    private var player: AVPlayer { model.livePlayer }

    private var liveBreaks: LiveBreaks { model.settings?.liveBreaks ?? .play }

    /// The break screen to draw over the picture now, or nil. It stays up under a failure card, so a reconnect
    /// inside a break never uncovers the ad.
    private var breakScreen: BreakScreen? {
        guard let adBreak, !breakDismissed else { return nil }
        return BreakScreen(
            remainingMs: adBreak.remainingMs?.int64Value,
            quip: breakQuip,
            watching: watching,
            facts: breakFacts,
            dodgedBeforeMs: model.settings?.adsDodged.on(day: BreakScreen.today()) ?? 0,
            coveredSince: coveredSince ?? .now
        )
    }

    /// The program on now, for the break screen: `You're watching: Forensic Files`.
    private var watching: String? {
        guard let lineup = model.lineup, let now = model.core.nowAndNext(lineup: lineup, channel: channel).now else { return nil }
        return "You're watching: \(now.title)"
    }

    /// The facts the break screen turns through: the episode, what TMDB says about the program on now, and what is next.
    private var breakFacts: [String] {
        guard let lineup = model.lineup else { return [] }
        let nowNext = model.core.nowAndNext(lineup: lineup, channel: channel)
        let facts = nowNext.now?.title == factsTitle ? programFacts : nil
        return model.core.breakFacts(now: nowNext.now, next: nowNext.next, facts: facts).map(PlayerView.words)
    }

    /// One fact in words, as the Compose app puts it.
    private static func words(_ fact: BreakFact) -> String {
        func names(_ names: [String]) -> String {
            names.count < 2 ? names.joined() : names.dropLast().joined(separator: ", ") + " and " + names.last!
        }
        switch fact {
        case let episode as BreakFactEpisode:
            return "This episode: " + [episode.label, episode.title].compactMap { $0 }.joined(separator: ", ")
        case let aired as BreakFactAired:
            let on = aired.studio.map { aired.movie ? " by \($0)" : " on \($0)" } ?? ""
            if aired.movie { return "Released in \(aired.year)\(on)" }
            if let end = aired.endYear { return "Aired from \(aired.year) to \(end.intValue)\(on)" }
            return "First aired in \(aired.year)\(on)"
        case let run as BreakFactRun:
            let seasons = run.seasons.map { $0.intValue == 1 ? "1 season" : "\($0.intValue) seasons" }
            let episodes = run.episodes.map { $0.intValue == 1 ? "1 episode" : "\($0.intValue) episodes" }
            return [seasons, episodes].compactMap { $0 }.joined(separator: ", ")
        case let starring as BreakFactStarring:
            return "Starring " + names(starring.names)
        case let madeBy as BreakFactMadeBy:
            return (madeBy.movie ? "Directed by " : "Created by ") + names(madeBy.names)
        case let tagline as BreakFactTagline:
            return "“\(tagline.text)”"
        case let rating as BreakFactRating:
            return "Rated \(rating.score) out of 10 on TMDB"
        case let next as BreakFactNext:
            let start = Date(timeIntervalSince1970: Double(next.startMs) / 1000).formatted(date: .omitted, time: .shortened)
            return "Next at \(start): \(next.title)"
        default:
            return ""
        }
    }

    /// Asks once per program what TMDB says about the program on now, for the break screen's facts.
    private func lookUpFacts() {
        guard let lineup = model.lineup, let now = model.core.nowAndNext(lineup: lineup, channel: channel).now,
              now.title != factsTitle else { return }
        Task {
            programFacts = try? await model.core.programFacts(program: now)
            factsTitle = now.title
        }
    }

    /// Deals the break screen its line and starts counting the time it covers.
    private func startCovering() {
        lookUpFacts()
        if quipDeck.isEmpty {
            quipDeck = BreakScreen.quips.shuffled()
            if quipDeck.first == breakQuip { quipDeck.append(quipDeck.removeFirst()) }
        }
        breakQuip = quipDeck.removeFirst()
        coveredSince = .now
    }

    /// Adds the time the break screen covered to Ads dodged today, once it goes.
    private func stopCovering() {
        guard let since = coveredSince else { return }
        coveredSince = nil
        model.core.addAdsDodged(day: BreakScreen.today(), ms: Int64(Date.now.timeIntervalSince(since) * 1000))
    }

    /// Show picture while the break screen is up, which the system shows over it as it does Skip intro.
    private var breakActions: [UIAction] {
        guard breakScreen != nil else { return [] }
        return [UIAction(title: "Show picture", image: UIImage(systemName: "eye")) { _ in breakDismissed = true }]
    }

    /// Asks the server every second whether an ad is playing, while Settings covers them.
    private func watchAdBreaks() async {
        adBreak = nil
        breakDismissed = false
        guard liveBreaks.covers else { return }
        // Rides out a reading that loses the break for a moment, and keeps Show picture until the break is over.
        let watch = BreakWatch()
        while !Task.isCancelled {
            var wait: Duration = .seconds(1)
            var found: AdBreakNow?
            // With nothing loaded past the picture the picture is stalled, and the server can't place it.
            let ahead = loadedAheadMs()
            if let ahead {
                do {
                    found = try await model.core.adBreak(aheadMs: ahead)
                } catch {
                    // A server from before the break screen: ask again in a while.
                    wait = .seconds(15)
                }
            }
            adBreak = ahead == nil ? watch.stalled() : watch.shown(found: found)
            if watch.over { breakDismissed = false }
            try? await Task.sleep(for: wait)
        }
    }

    /// The end of the loaded range the picture is in, less the picture's time: what AVPlayer has loaded past it.
    private func loadedAheadMs() -> Int64? {
        guard let item = player.currentItem else { return nil }
        return model.core.loadedAheadMs(nowSeconds: player.currentTime().seconds, ranges: timeSpans(item.loadedTimeRanges))?.int64Value
    }

    private func applyBreakSound() {
        player.isMuted = breakScreen != nil && liveBreaks.muted
    }

    /// The transport bar's custom items: Record, the favorite toggle and the channel list, grouped as the guide's chips are.
    private var menuItems: [UIMenuElement] {
        let favorite = model.favorites.contains(channel.guideId)
        let star = UIAction(
            title: favorite ? "Remove from favorites" : "Add to favorites",
            image: UIImage(systemName: favorite ? "star.fill" : "star"),
            state: favorite ? .on : .off
        ) { [guideId = channel.guideId] _ in model.core.toggleFavorite(guideId: guideId) }
        let all = model.lineup?.channels ?? []
        let groups: [UIMenu] = [
            channelMenu(strings.filterRecent, model.recentChannels, inline: true),
            channelMenu(strings.filterFavorites, all.filter { model.favorites.contains($0.guideId) }, inline: true),
            channelMenu("All channels", all, inline: false),
        ].compactMap { $0 }
        let channels = UIMenu(title: "Channels", image: UIImage(systemName: "list.bullet"), children: groups)
        let programEnd = model.lineup.flatMap { model.core.nowAndNext(lineup: $0, channel: channel).now }.map {
            Date(timeIntervalSince1970: Double(model.core.endEpochMs(program: $0)) / 1000)
        }
        let sleep = model.sleepMenu(endTitle: programEnd == nil ? nil : strings.sleepEndProgram, endsAt: programEnd)
        let items: [UIMenuElement?] = [fromStartAction, recordAction, star, channels, multiviewAction, sleep]
        return items.compactMap { $0 }
    }

    /// Record, or Cancel recording, for the program on now, on a server that records; as in the program's details.
    private var recordAction: UIAction? {
        guard model.recordingsShown, let marks = model.recordingMarks, let lineup = model.lineup,
              let now = model.core.nowAndNext(lineup: lineup, channel: channel).now,
              model.core.recordable(program: now)
        else { return nil }
        let recording = model.core.recordingMark(marks: marks, program: now).recording
        return UIAction(
            title: recording ? "Cancel recording" : "Record",
            image: UIImage(systemName: recording ? "xmark.circle" : "record.circle")
        ) { _ in
            Task { _ = try? await model.core.toggleRecording(program: now) }
        }
    }

    /// Watch from start, while the program on now is being recorded: the live stream stops and the recording plays.
    private var fromStartAction: UIAction? {
        guard model.recordingsShown, let marks = model.recordingMarks, let lineup = model.lineup,
              let now = model.core.nowAndNext(lineup: lineup, channel: channel).now,
              let recording = model.core.runningRecording(marks: marks, program: now)
        else { return nil }
        return UIAction(title: "Watch from start", image: UIImage(systemName: "backward.end")) { _ in
            reconnect?.cancel()
            reconnect = nil
            stopCovering()
            itemObservers.removeAll()
            player.replaceCurrentItem(with: nil)
            fromStart = VodRequest(recording: recording, fromStart: true)
        }
    }

    /// Multiview, on a server that draws it: the live stream stops and the multiview's list opens.
    private var multiviewAction: UIAction? {
        guard model.capabilities?.multiview == true, (model.lineup?.channels.count ?? 0) >= 2 else { return nil }
        return UIAction(title: "Multiview", image: UIImage(systemName: "rectangle.split.2x2")) { [channel] _ in
            reconnect?.cancel()
            reconnect = nil
            stopCovering()
            itemObservers.removeAll()
            player.replaceCurrentItem(with: nil)
            multiview = channel
        }
    }

    /// Back from the multiview: the channel its click picked, or the one it was opened on, plays again.
    private func returnFromMultiview() {
        if let pick = multiviewPick { channel = pick }
        multiviewPick = nil
        attempts = 0
        failure = nil
        Task { await tune() }
    }

    private func channelMenu(_ title: String, _ channels: [Channel], inline: Bool) -> UIMenu? {
        guard !channels.isEmpty else { return nil }
        let actions = channels.map { other in
            let action = UIAction(
                title: "\(other.number.formatted()) \(other.name)",
                state: other.guideId == channel.guideId ? .on : .off
            ) { _ in switchTo(other) }
            if let lineup = model.lineup, let now = model.core.nowAndNext(lineup: lineup, channel: other).now {
                action.subtitle = now.title
            }
            return action
        }
        return UIMenu(title: title, options: inline ? [.displayInline] : [], children: actions)
    }

    /// Tunes another channel; the server releases the held session and opens the next, as the Compose app's zap does.
    private func switchTo(_ next: Channel) {
        guard next.guideId != channel.guideId else { return }
        reconnect?.cancel()
        reconnect = nil
        attempts = 0
        failure = nil
        channel = next
        Task { await tune() }
    }

    private func tune() async {
        do {
            let stream = try await model.core.watchChannel(channelId: channel.guideId)
            // Left while the session opened: the view is gone, and leaving released the session. A switch or a
            // reconnect runs in a task of its own that leaving does not cancel, so the view's presence decides too.
            guard !Task.isCancelled, shown, fromStart == nil, multiview == nil else { return }
            if let url = URL(string: stream.url) { await StreamStart.settled(url, core: model.core) }
            guard !Task.isCancelled, shown, fromStart == nil, multiview == nil else { return }
            play(stream)
        } catch {
            guard !Task.isCancelled, shown else { return }
            failure = error.localizedDescription
            scheduleReconnect()
        }
    }

    /// Plays `stream` and watches its item: a failure or a stall reopens the channel.
    private func play(_ stream: PlaybackStream) {
        // The recording played from its start holds the session; the channel tunes again when it closes.
        guard fromStart == nil, multiview == nil, let url = URL(string: stream.url) else { return }
        // The preview's item, when it plays this stream already, carries on without reloading.
        let current = player.currentItem.flatMap { ($0.asset as? AVURLAsset)?.url == url ? $0 : nil }
        let item = current ?? AVPlayerItem(url: url)
        itemObservers = [
            item.publisher(for: \.status).filter { $0 == .failed }.sink { _ in scheduleReconnect() },
            NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item).sink { _ in scheduleReconnect() },
            NotificationCenter.default.publisher(for: .AVPlayerItemPlaybackStalled, object: item).sink { _ in scheduleReconnect() },
            item.publisher(for: \.status).filter { $0 == .readyToPlay }.sink { _ in
                failure = nil
                attempts = 0
            },
        ] + StreamCaptions.follow(item, on: player, model: model)
        if item !== player.currentItem { player.replaceCurrentItem(with: item) }
        player.play()
    }

    /// Reopens the channel after 2, 4, 8 and 15 seconds, then every 30, as every client does; the server hands back the
    /// held session or a new one when the old stream is gone.
    private func scheduleReconnect() {
        guard shown, fromStart == nil, multiview == nil, reconnect == nil || reconnect?.isCancelled == true else { return }
        let delay = Double(model.core.reconnectDelayMs(attempt: Int32(attempts))) / 1000
        attempts += 1
        reconnect = Task {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            reconnect = nil
            await tune()
        }
    }
}

/// The system player UI, which handles the Siri Remote's play, pause and Menu, with the app's items in its transport bar,
/// Show picture over the picture, and the break screen between the picture and the system's controls.
private struct PlayerController: UIViewControllerRepresentable {
    let player: AVPlayer
    let menuItems: [UIMenuElement]
    let actions: [UIAction]
    let breakScreen: BreakScreen?

    func makeCoordinator() -> UIHostingController<BreakScreen?> {
        let hosting = UIHostingController<BreakScreen?>(rootView: nil)
        hosting.view.backgroundColor = .clear
        hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        return hosting
    }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.transportBarCustomMenuItems = menuItems
        controller.contextualActions = actions
        if let overlay = controller.contentOverlayView {
            context.coordinator.view.frame = overlay.bounds
            overlay.addSubview(context.coordinator.view)
        }
        context.coordinator.rootView = breakScreen
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.transportBarCustomMenuItems = menuItems
        controller.contextualActions = actions
        context.coordinator.rootView = breakScreen
    }
}

/// AVPlayer's time ranges as the seconds spans core's `loadedAheadMs` and `durationFromRanges` read.
func timeSpans(_ ranges: [NSValue]) -> [TimeSpan] {
    ranges.map { TimeSpan(startSeconds: $0.timeRangeValue.start.seconds, endSeconds: $0.timeRangeValue.end.seconds) }
}
