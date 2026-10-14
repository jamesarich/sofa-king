import AVKit
import SwiftUI
import SofaKingCore

/// One channel full screen on AVPlayer, through a server watch session that core opens, renews and releases.
/// Menu dismisses it; leaving releases the session, and backgrounding releases it until the app returns.
struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    let channel: Channel
    @State private var player = AVPlayer()
    @State private var failure: String?
    @State private var replaced: Watch?

    var body: some View {
        ZStack {
            PlayerController(player: player).ignoresSafeArea()
            if let failure {
                Text(failure).padding().background(.thinMaterial, in: .rect(cornerRadius: 16))
            }
        }
        .task { await tune() }
        .onAppear {
            replaced = model.core.watchReplaced { [player] stream in play(stream, on: player) }
        }
        .onDisappear {
            replaced?.cancel()
            player.replaceCurrentItem(with: nil)
            model.core.stopWatching()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                player.replaceCurrentItem(with: nil)
                Task { try? await model.core.release() }
            case .active where player.currentItem == nil:
                Task {
                    if let stream = try? await model.core.reopen() { play(stream, on: player) }
                }
            default:
                break
            }
        }
    }

    private func tune() async {
        do {
            play(try await model.core.watchChannel(channelId: channel.guideId), on: player)
        } catch {
            failure = error.localizedDescription
        }
    }
}

@MainActor
private func play(_ stream: PlaybackStream, on player: AVPlayer) {
    guard let url = URL(string: stream.url) else { return }
    player.replaceCurrentItem(with: AVPlayerItem(url: url))
    player.play()
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
