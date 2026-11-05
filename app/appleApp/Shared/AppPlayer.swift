import AVFoundation

/// What a debug launch asks for, as `xcrun simctl launch booted <bundle id> -serverUrl http://host:port -apiToken TOKEN -muted YES`
/// does: the touch and remote tests launch the app this way. A release build reads none of it.
enum LaunchSettings {
    /// Whether every player stays silent, so a test run on a desk makes no sound.
    static let muted: Bool = {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "muted")
        #else
        false
        #endif
    }()
}

/// An `AVPlayer` that honors `-muted YES` on a debug launch.
final class AppPlayer: AVPlayer {
    override init() {
        super.init()
        super.isMuted = LaunchSettings.muted
    }

    override var isMuted: Bool {
        get { super.isMuted }
        set { super.isMuted = LaunchSettings.muted || newValue }
    }
}
