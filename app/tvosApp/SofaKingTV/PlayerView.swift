import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// One channel full screen on AVPlayer, through a server watch session that core opens, renews and releases.
/// Menu dismisses it; leaving releases the session, and backgrounding releases it until the app returns.
/// A stream that fails or stalls is reopened through the same session after a growing pause, as the Compose app does.
/// The transport bar's menu records the program on now, stars the channel and switches to another: recent, favorites
/// or any channel. With Settings › During ads on a logo, the break screen covers the ads a streaming channel marks, as
/// the server places them from the segments AVPlayer fetched; Show picture over it brings the picture back for that break.
struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @State private var channel: Channel
    @State private var player = AVPlayer()
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
        .task { await tune() }
        .task(id: "\(channel.guideId) \(liveBreaks.name)") { await watchAdBreaks() }
        .onChange(of: breakScreen != nil) { _, _ in applyBreakSound() }
        .onAppear {
            shown = true
            replaced = model.core.watchReplaced { stream in play(stream) }
        }
        .onDisappear {
            shown = false
            replaced?.cancel()
            reconnect?.cancel()
            itemObservers.removeAll()
            player.replaceCurrentItem(with: nil)
            model.core.stopWatching()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
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

    private var liveBreaks: LiveBreaks { model.settings?.liveBreaks ?? .play }

    /// The break screen to draw over the picture now, or nil.
    private var breakScreen: BreakScreen? {
        guard let adBreak, !breakDismissed, failure == nil else { return nil }
        return BreakScreen(remainingMs: adBreak.remainingMs?.int64Value)
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
        while !Task.isCancelled {
            var wait: Duration = .seconds(1)
            if let ahead = loadedAheadMs() {
                do {
                    adBreak = try await model.core.adBreak(aheadMs: ahead)
                } catch {
                    // A server from before the break screen: ask again in a while.
                    adBreak = nil
                    wait = .seconds(15)
                }
            } else {
                adBreak = nil
            }
            if adBreak == nil { breakDismissed = false }
            try? await Task.sleep(for: wait)
        }
    }

    /// The end of the loaded range the picture is in, less the picture's time: what AVPlayer has loaded past it.
    private func loadedAheadMs() -> Int64? {
        guard let item = player.currentItem else { return nil }
        let now = player.currentTime().seconds
        guard now.isFinite else { return nil }
        for value in item.loadedTimeRanges {
            let range = value.timeRangeValue
            if now >= range.start.seconds && now <= range.end.seconds { return Int64((range.end.seconds - now) * 1000) }
        }
        return nil
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
            channelMenu("Recent", model.recentChannels, inline: true),
            channelMenu("Favorites", all.filter { model.favorites.contains($0.guideId) }, inline: true),
            channelMenu("All channels", all, inline: false),
        ].compactMap { $0 }
        let channels = UIMenu(title: "Channels", image: UIImage(systemName: "list.bullet"), children: groups)
        let items: [UIMenuElement?] = [recordAction, star, channels]
        return items.compactMap { $0 }
    }

    /// Record, or Cancel recording, for the program on now, on a server that records; as in the program's details.
    private var recordAction: UIAction? {
        guard model.recordingsShown, let marks = model.recordingMarks, let lineup = model.lineup,
              let now = model.core.nowAndNext(lineup: lineup, channel: channel).now,
              model.core.recordable(programme: now)
        else { return nil }
        let recording = model.core.recordingMark(marks: marks, programme: now).recording
        return UIAction(
            title: recording ? "Cancel recording" : "Record",
            image: UIImage(systemName: recording ? "xmark.circle" : "record.circle")
        ) { _ in
            Task { _ = try? await model.core.toggleRecording(programme: now) }
        }
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
            guard !Task.isCancelled, shown else { return }
            play(stream)
        } catch {
            guard !Task.isCancelled, shown else { return }
            failure = error.localizedDescription
            scheduleReconnect()
        }
    }

    /// Plays `stream` and watches its item: a failure or a stall reopens the channel.
    private func play(_ stream: PlaybackStream) {
        guard let url = URL(string: stream.url) else { return }
        let item = AVPlayerItem(url: url)
        itemObservers = [
            item.publisher(for: \.status).filter { $0 == .failed }.sink { _ in scheduleReconnect() },
            NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item).sink { _ in scheduleReconnect() },
            NotificationCenter.default.publisher(for: .AVPlayerItemPlaybackStalled, object: item).sink { _ in scheduleReconnect() },
            item.publisher(for: \.status).filter { $0 == .readyToPlay }.sink { _ in
                failure = nil
                attempts = 0
            },
        ]
        player.replaceCurrentItem(with: item)
        player.play()
    }

    /// Reopens the channel after 2, 4, 8 … seconds, up to a minute between tries; the server hands back the held session
    /// or a new one when the old stream is gone.
    private func scheduleReconnect() {
        guard shown, reconnect == nil || reconnect?.isCancelled == true else { return }
        let delay = min(60, 2 << min(attempts, 5))
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
