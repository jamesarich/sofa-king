import Foundation
import SofaKingCore

/// Waits for a live stream to have enough segments for AVPlayer to start it.
enum StreamStart {
    /// Returns once the playlist at `url` lists enough segments, or at once when it is not a live media playlist;
    /// `core` decides and polls, as the iOS app does. Canceling the task stops the polling.
    static func settled(_ url: URL, core: SofaKingTv) async {
        let pending = PendingWatch()
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                pending.set(core.awaitLiveStart(url: url.absoluteString) { continuation.resume() })
            }
        } onCancel: {
            pending.cancel()
        }
    }
}

/// Holds the `Watch` of a wait so a task canceled before or after the wait began cancels it either way.
private final class PendingWatch: @unchecked Sendable {
    private let lock = NSLock()
    private var watch: Watch?
    private var canceled = false

    func set(_ watch: Watch) {
        lock.lock()
        defer { lock.unlock() }
        if canceled { watch.cancel() } else { self.watch = watch }
    }

    func cancel() {
        lock.lock()
        defer { lock.unlock() }
        canceled = true
        watch?.cancel()
    }
}
