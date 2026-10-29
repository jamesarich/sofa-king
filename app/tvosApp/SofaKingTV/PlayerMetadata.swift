import AVFoundation
import CoreMedia
import Foundation

/// The title, subtitle, description and artwork the system player's info panel, Control Center and Siri show for an item.
func playerMetadata(title: String, subtitle: String?, summary: String?, artwork: Data?) -> [AVMetadataItem] {
    func entry(_ identifier: AVMetadataIdentifier, _ value: NSCopying & NSObjectProtocol, type: String? = nil) -> AVMetadataItem {
        let item = AVMutableMetadataItem()
        item.identifier = identifier
        item.value = value
        item.extendedLanguageTag = "und"
        if let type { item.dataType = type }
        return item.copy() as! AVMetadataItem
    }
    var items = [entry(.commonIdentifierTitle, title as NSString)]
    if let subtitle, !subtitle.isEmpty { items.append(entry(.iTunesMetadataTrackSubTitle, subtitle as NSString)) }
    if let summary, !summary.isEmpty { items.append(entry(.commonIdentifierDescription, summary as NSString)) }
    if let artwork {
        let png = artwork.starts(with: [0x89, 0x50, 0x4E, 0x47])
        items.append(entry(
            .commonIdentifierArtwork, artwork as NSData,
            type: (png ? kCMMetadataBaseDataType_PNG : kCMMetadataBaseDataType_JPEG) as String
        ))
    }
    return items
}

/// Artwork bytes by address, loaded once for the player's metadata; a failed load is not remembered, so it can retry.
@MainActor
enum ArtworkData {
    private static var cache: [String: Data] = [:]

    static func load(_ address: String?) async -> Data? {
        guard let address, let url = URL(string: address) else { return nil }
        if let known = cache[address] { return known }
        guard let loaded = try? await URLSession.shared.data(from: url), !loaded.0.isEmpty else { return nil }
        cache[address] = loaded.0
        return loaded.0
    }
}
