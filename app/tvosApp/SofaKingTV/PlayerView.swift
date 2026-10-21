import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// One channel full screen on AVPlayer, through a server watch session that core opens, renews and releases.
/// Menu dismisses it; leaving releases the session, and backgrounding releases it until the app returns.
/// A stream that fails or stalls is reopened through the same session after a growing pause, as the Compose app does.
/// The transport bar's menu stars the channel and switches to another: recent, favorites or any channel.
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

    init(channel: Channel) {
        _channel = State(initialValue: channel)
    }

    var body: some View {
        ZStack {
            PlayerController(player: player, menuItems: menuItems).ignoresSafeArea()
            if let failure {
                Text(failure).padding().background(.thinMaterial, in: .rect(cornerRadius: 16))
            }
        }
        .task { await tune() }
        .onAppear {
            replaced = model.core.watchReplaced { stream in play(stream) }
        }
        .onDisappear {
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

    /// The transport bar's custom items: the favorite toggle and the channel list, grouped as the guide's chips are.
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
        return [star, channels]
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
            // Left while the session opened: the view is gone, and leaving released the session.
            guard !Task.isCancelled else { return }
            play(stream)
        } catch {
            guard !Task.isCancelled else { return }
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
        guard reconnect == nil || reconnect?.isCancelled == true else { return }
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

/// The system player UI, which handles the Siri Remote's play, pause and Menu, with the app's items in its transport bar.
private struct PlayerController: UIViewControllerRepresentable {
    let player: AVPlayer
    let menuItems: [UIMenuElement]

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.transportBarCustomMenuItems = menuItems
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.transportBarCustomMenuItems = menuItems
    }
}
