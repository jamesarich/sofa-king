import AVKit
import MediaPlayer
import UIKit

/// Up and Down clicks on the live player's picture, as steps through the channel ring: Up is the channel above
/// (-1), Down the one below (+1), as in the Compose app.
///
/// The system player owns Up and Down while its transport bar is up (they move focus along it), so the recognizers
/// decline to begin then and the click reaches the bar. The bar's visibility comes from the player's delegate;
/// the clicks of a hidden bar would otherwise do nothing.
@MainActor
final class ZapGestures: NSObject, @preconcurrency AVPlayerViewControllerDelegate, UIGestureRecognizerDelegate {
    var onZap: (Int) -> Void = { _ in }
    private var barVisible = false
    private var attached = false

    func attach(to controller: AVPlayerViewController) {
        guard !attached else { return }
        attached = true
        controller.delegate = self
        for (press, action) in [(UIPress.PressType.upArrow, #selector(up)), (.downArrow, #selector(down))] {
            let tap = UITapGestureRecognizer(target: self, action: action)
            tap.allowedPressTypes = [NSNumber(value: press.rawValue)]
            // Only a click of the remote's edge: a touch on the surface is the system's swipe.
            tap.allowedTouchTypes = []
            tap.delegate = self
            controller.view.addGestureRecognizer(tap)
        }
    }

    @objc private func up() { onZap(-1) }

    @objc private func down() { onZap(1) }

    func playerViewController(
        _ playerViewController: AVPlayerViewController,
        willTransitionToVisibilityOfTransportBar visible: Bool,
        with coordinator: AVPlayerViewControllerAnimationCoordinator
    ) {
        barVisible = visible
        coordinator.addCoordinatedAnimations(nil) { [weak self] finished in
            // A transition that did not complete leaves the bar as it was.
            if !finished { MainActor.assumeIsolated { self?.barVisible = !visible } }
        }
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool { !barVisible }
}

/// The system's next and previous track as channel up and down, for a CEC television remote or the iPhone's remote,
/// for as long as the live player is up.
@MainActor
final class ZapCommands {
    private var targets: [(command: MPRemoteCommand, target: Any, wasEnabled: Bool)] = []

    func install(_ step: @escaping @MainActor (Int) -> Void) {
        remove()
        let center = MPRemoteCommandCenter.shared()
        for (command, direction) in [(center.nextTrackCommand, 1), (center.previousTrackCommand, -1)] {
            let wasEnabled = command.isEnabled
            command.isEnabled = true
            let target = command.addTarget { _ in
                MainActor.assumeIsolated {
                    step(direction)
                    return .success
                }
            }
            targets.append((command, target, wasEnabled))
        }
    }

    func remove() {
        for entry in targets {
            entry.command.removeTarget(entry.target)
            entry.command.isEnabled = entry.wasEnabled
        }
        targets = []
    }
}
