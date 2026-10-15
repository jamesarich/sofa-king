import AVKit
import Combine
import SwiftUI
import SofaKingCore

/// One channel full screen on AVPlayer, through a server watch session that core opens, renews and releases.
/// Menu dismisses it; leaving releases the session, and backgrounding releases it until the app returns.
/// A stream that fails or stalls is reopened through the same session after a growing pause, as the Compose app does.
struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    let channel: Channel
    @State private var player = AVPlayer()
    @State private var failure: String?
    @State private var replaced: Watch?
    @State private var itemObservers: [AnyCancellable] = []
    @State private var reconnect: Task<Void, Never>?
    @State private var attempts = 0

    var body: some View {
        ZStack {
            PlayerController(player: player).ignoresSafeArea()
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
    }

    private func tune() async {
        do {
            play(try await model.core.watchChannel(channelId: channel.guideId))
        } catch {
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

/// The system player UI, which handles the Siri Remote's play, pause and Menu.
private struct PlayerController: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}
}
