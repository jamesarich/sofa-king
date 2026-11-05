import AVKit
import Combine
import MediaAccessibility
import SofaKingCore

/// A live channel's or a recording's closed captions on AVPlayer, on or off as the app's CC setting says, as on the
/// other apps. The system draws them in the look set in the Apple TV's Settings › Accessibility › Subtitles and
/// Captioning. The system's "always on" display type also turns them on, and is only read here, never written: the
/// panel's On and Off set it themselves, and the app's setting follows the item's selection. The track is the one the
/// system's preferred languages and its Closed Captions + SDH choice rank first.
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
                    legible.type = MACaptionAppearanceGetDisplayType(.user)
                    let on = (model.settings?.captionsEnabled ?? false) || legible.type == .alwaysOn
                    item.select(on ? preferredOption(in: group) : nil, in: group)
                }
            },
            // The panel's On and Off change the display type, so the selection follows it; other caption look changes
            // arrive here too and leave the selection alone.
            NotificationCenter.default.publisher(for: captionSettingsChanged).sink { _ in
                Task { @MainActor in
                    guard let group = legible.group else { return }
                    let type = MACaptionAppearanceGetDisplayType(.user)
                    guard type != legible.type else { return }
                    legible.type = type
                    let on = type == .alwaysOn
                    guard on != (item.currentMediaSelection.selectedMediaOption(in: group) != nil) else { return }
                    item.select(on ? preferredOption(in: group) : nil, in: group)
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

    /// The caption track the system's languages and its SDH choice rank first, never a forced-only one.
    private static func preferredOption(in group: AVMediaSelectionGroup) -> AVMediaSelectionOption? {
        let regular = AVMediaSelectionGroup.mediaSelectionOptions(
            from: group.options, withoutMediaCharacteristics: [.containsOnlyForcedSubtitles]
        )
        let ranked = AVMediaSelectionGroup.mediaSelectionOptions(
            from: regular, filteredAndSortedAccordingToPreferredLanguages: Locale.preferredLanguages
        )
        let wanted = (MACaptionAppearanceCopyPreferredCaptioningMediaCharacteristics(.user).takeRetainedValue() as? [String] ?? [])
            .map { AVMediaCharacteristic(rawValue: $0) }
        if !wanted.isEmpty,
           let match = AVMediaSelectionGroup.mediaSelectionOptions(from: ranked, withMediaCharacteristics: wanted).first {
            return match
        }
        return ranked.first ?? regular.first ?? group.options.first
    }

    /// The item's caption group, once loaded, and the display type the last look at the system's setting found.
    private final class Legible {
        var group: AVMediaSelectionGroup?
        var type: MACaptionAppearanceDisplayType = .automatic
    }
}
