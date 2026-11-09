import AVKit
import SwiftUI
import SofaKingCore

extension AVPlayerViewController {
    /// The system player as the phone and tablet show it: its own scrubber, captions, AirPlay and Picture in Picture,
    /// with the player's info in Control Center and on the lock screen.
    func configureForPhone(pictureInPicture tracker: PictureInPictureTracker) {
        delegate = tracker
        showsPlaybackControls = true
        allowsPictureInPicturePlayback = true
        canStartPictureInPictureAutomaticallyFromInline = true
        updatesNowPlayingInfoCenter = true
    }
}

/// Tells the model whether Picture in Picture is up, which the players read when the app goes to the background: the
/// stream and its session stay while the small window plays, and go with the window when it closes.
@MainActor
final class PictureInPictureTracker: NSObject, AVPlayerViewControllerDelegate {
    private let model: AppModel

    init(model: AppModel) {
        self.model = model
    }

    func playerViewControllerWillStartPictureInPicture(_ controller: AVPlayerViewController) {
        model.pictureInPicture = true
    }

    func playerViewControllerDidStopPictureInPicture(_ controller: AVPlayerViewController) {
        model.pictureInPicture = false
    }

    func playerViewController(_ controller: AVPlayerViewController, failedToStartPictureInPictureWithError error: Error) {
        model.pictureInPicture = false
    }

    func playerViewControllerRestoreUserInterfaceForPictureInPictureStop(
        _ controller: AVPlayerViewController,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(true)
    }
}

/// The app's controls over the system player, which has no transport bar to hold them as the Apple TV's has: a close
/// button, the menu the Apple TV puts in its transport bar, and a button for each action the player offers (Show
/// picture, Skip intro, Next episode).
struct PlayerChrome: View {
    let menus: [UIMenuElement]
    let actions: [UIAction]
    let close: () -> Void
    /// What plays, read by the close button's accessibility value.
    var watching = ""

    var body: some View {
        VStack {
            HStack {
                Button(action: close) {
                    Image(systemName: "chevron.down").padding(12).background(.thinMaterial, in: .circle)
                }
                .accessibilityLabel("Close")
                .accessibilityValue(watching)
                .accessibilityIdentifier("player-close")
                Spacer()
                MenuButton(menus: menus)
                    .frame(width: 44, height: 44)
                    .accessibilityIdentifier("player-menu")
            }
            Spacer()
            HStack {
                ForEach(Array(actions.enumerated()), id: \.offset) { _, action in
                    Button {
                        action.performWithSender(nil, target: nil)
                    } label: {
                        Label(action.title, systemImage: "forward.end").padding(.horizontal, 16).padding(.vertical, 10)
                            .background(.thinMaterial, in: .capsule)
                    }
                    .accessibilityIdentifier("player-action")
                }
                Spacer()
            }
            .padding(.bottom, 64)
        }
        .padding(12)
        .foregroundStyle(.white)
    }
}

/// A button that opens `menus` as a UIKit menu, built when it opens so a SwiftUI update never closes it.
private struct MenuButton: UIViewRepresentable {
    let menus: [UIMenuElement]

    final class Coordinator {
        var menus: [UIMenuElement] = []
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIButton {
        var configuration = UIButton.Configuration.plain()
        configuration.image = UIImage(systemName: "ellipsis.circle")
        configuration.baseForegroundColor = .white
        let button = UIButton(configuration: configuration)
        button.showsMenuAsPrimaryAction = true
        button.accessibilityLabel = "Menu"
        let coordinator = context.coordinator
        button.menu = UIMenu(children: [UIDeferredMenuElement.uncached { completion in completion(coordinator.menus) }])
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.menus = menus
    }
}

/// The channels under the picture in portrait: the one playing is ticked, and a tap tunes another.
struct PlayerChannelList: View {
    @Environment(AppModel.self) private var model
    let current: Channel
    let pick: (Channel) -> Void

    var body: some View {
        if let lineup = model.lineup {
            List(lineup.channels) { channel in
                Button { pick(channel) } label: {
                    ChannelRow(channel: channel, lineup: lineup, playing: channel.guideId == current.guideId)
                }
                .listRowBackground(Color.sofaPlum)
            }
            .listStyle(.plain)
            .accessibilityIdentifier("player-channels")
        }
    }
}
