import AVFoundation
import SofaKingCore

/// Reads the ad marks (`EXT-X-DATERANGE`) of one live item into core's `BreakMarks`. The collector only listens to the
/// item's metadata, so the item's `externalMetadata` and the transport bar are untouched. Its delegate runs on the main
/// queue, where the break screen reads the marks.
final class BreakMarkCollector: NSObject, AVPlayerItemMetadataCollectorPushDelegate {
    private let marks: BreakMarks
    private let adClass: String
    private let collector = AVPlayerItemMetadataCollector()
    private weak var item: AVPlayerItem?

    init(item: AVPlayerItem, core: SofaKingTv) {
        marks = core.breakMarks()
        adClass = core.adBreakClass
        self.item = item
        super.init()
        collector.setDelegate(self, queue: .main)
        item.add(collector)
    }

    func follows(_ other: AVPlayerItem) -> Bool {
        item === other
    }

    /// Stops reading the item.
    func detach() {
        item?.remove(collector)
    }

    /// The break the picture of `player` is in, or nil outside one, before the item has a date, or once `player` plays
    /// another item.
    func breakNow(on player: AVPlayer) -> AdBreakNow? {
        guard let item, player.currentItem === item, let date = item.currentDate() else { return nil }
        return marks.at(wallClockMs: Int64(date.timeIntervalSince1970 * 1000))
    }

    func metadataCollector(
        _ metadataCollector: AVPlayerItemMetadataCollector,
        didCollect metadataGroups: [AVDateRangeMetadataGroup],
        indexesOfNewGroups: IndexSet,
        indexesOfModifiedGroups: IndexSet
    ) {
        // Each callback carries every group the playlist holds now, so one it no longer lists has ended.
        marks.replaceOpen(current: metadataGroups.compactMap(mark))
    }

    private func mark(_ group: AVDateRangeMetadataGroup) -> BreakMark? {
        guard group.classifyingLabel == adClass, let id = group.uniqueID else { return nil }
        let startMs = Self.ms(group.startDate)
        if let end = group.endDate {
            return BreakMark(id: id, startMs: startMs, endMs: KotlinLong(value: Self.ms(end)), planned: false)
        }
        let planned = group.items.first { $0.keySpace == .hlsDateRange && ($0.key as? String) == "PLANNED-DURATION" }?
            .numberValue?.doubleValue
        let endMs = planned.map { KotlinLong(value: startMs + Int64($0 * 1000)) }
        return BreakMark(id: id, startMs: startMs, endMs: endMs, planned: planned != nil)
    }

    private static func ms(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }
}
