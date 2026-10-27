import AVKit
import Combine
import MediaAccessibility
import SofaKingCore

/// A live channel's or a recording's closed captions on AVPlayer, on or off as the app's CC setting says, as on the
/// other apps. The system draws them in the look set in the Apple TV's Settings › Accessibility › Subtitles and
/// Captioning; turning them on or off in the transport bar's Subtitles panel becomes the setting. The panel's On and
/// Off show and set the system's caption display type rather than the item's selection, so the setting goes to the
/// display type too: On is always on, and Off takes back an always on the app set.
@MainActor
enum StreamCaptions {
    /// Applies the setting to `item` once it is ready, and saves the viewer's picks after; keep the observers as long
    /// as the item plays. `player` stops choosing tracks by the system's own criteria, which would override the setting.
    static func follow(_ item: AVPlayerItem, on player: AVPlayer, model: AppModel) -> [AnyCancellable] {
        player.appliesMediaSelectionCriteriaAutomatically = false
        let legible = Legible()
        return [
            item.publisher(for: \.status).filter { $0 == .readyToPlay }.first().sink { _ in
                Task { @MainActor in
                    guard let group = try? await item.asset.loadMediaSelectionGroup(for: .legible) else { return }
                    legible.group = group
                    let on = model.settings?.captionsEnabled ?? false
                    item.select(on ? group.options.first : nil, in: group)
                    show(on)
                }
            },
            // The panel's On changes only the display type, so it selects the captions here.
            NotificationCenter.default.publisher(for: captionSettingsChanged).sink { _ in
                Task { @MainActor in
                    guard let group = legible.group else { return }
                    let on = MACaptionAppearanceGetDisplayType(.user) == .alwaysOn
                    guard on != (model.settings?.captionsEnabled ?? false) else { return }
                    item.select(on ? group.options.first : nil, in: group)
                }
            },
            NotificationCenter.default.publisher(for: AVPlayerItem.mediaSelectionDidChangeNotification, object: item).sink { _ in
                Task { @MainActor in
                    guard let group = legible.group else { return }
                    let on = item.currentMediaSelection.selectedMediaOption(in: group) != nil
                    if on != (model.settings?.captionsEnabled ?? false) { model.core.setCaptionsEnabled(enabled: on) }
                }
            },
        ]
    }

    private static let captionSettingsChanged = Notification.Name(kMACaptionAppearanceSettingsChangedNotification as String)

    /// Sets the system's display type to match `on`, as the Subtitles panel shows it.
    private static func show(_ on: Bool) {
        let type = MACaptionAppearanceGetDisplayType(.user)
        if on, type != .alwaysOn {
            MACaptionAppearanceSetDisplayType(.user, .alwaysOn)
        } else if !on, type == .alwaysOn {
            MACaptionAppearanceSetDisplayType(.user, .automatic)
        }
    }

    /// The item's caption group, once loaded.
    private final class Legible {
        var group: AVMediaSelectionGroup?
    }
}
