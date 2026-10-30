import AVKit
import MediaPlayer
import UIKit

/// The system player UI with Up and Down clicks on its picture as steps through the channel ring: Up is the channel
/// above (-1), Down the one below (+1), as in the Compose app.
///
/// The system player shows its transport bar on those clicks, so the clicks are taken here, before it sees them,
/// while the bar is hidden. While the bar is up they go to it, where they move focus along it. The bar's visibility
/// comes from the player's delegate, which hears of a transition before the click that causes it is delivered.
final class ZapPlayerViewController: AVPlayerViewController {
    let zap = ZapPresses()
    /// The clicks taken as zaps, which the system player never sees begin, change or end.
    private var taken = Set<UIPress>()

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = zap
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let zaps = presses.filter { zap.step(for: $0) != nil }
        if !zaps.isEmpty, !zap.barVisible {
            taken.formUnion(zaps)
            for press in zaps.sorted(by: { $0.type.rawValue < $1.type.rawValue }) {
                if let step = zap.step(for: press) { zap.onZap(step) }
            }
        }
        let rest = presses.subtracting(taken)
        if !rest.isEmpty { super.pressesBegan(rest, with: event) }
    }

    override func pressesChanged(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let rest = presses.subtracting(taken)
        if !rest.isEmpty { super.pressesChanged(rest, with: event) }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let rest = presses.subtracting(taken)
        taken.subtract(presses)
        if !rest.isEmpty { super.pressesEnded(rest, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let rest = presses.subtracting(taken)
        taken.subtract(presses)
        if !rest.isEmpty { super.pressesCancelled(rest, with: event) }
    }
}

/// Which clicks are zaps, and whether the system player's transport bar is up.
@MainActor
final class ZapPresses: NSObject, @preconcurrency AVPlayerViewControllerDelegate {
    var onZap: (Int) -> Void = { _ in }
    private(set) var barVisible = false

    func step(for press: UIPress) -> Int? {
        switch press.type {
        case .upArrow: return -1
        case .downArrow: return 1
        default: return nil
        }
    }

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
