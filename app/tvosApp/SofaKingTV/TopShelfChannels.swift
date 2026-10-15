import Foundation

/// A channel as the Top Shelf shows it: the app writes the recent channels, the extension reads them.
struct TopShelfChannel: Codable, Equatable {
    let guideId: String
    let number: String
    let name: String
    let logo: String?
}

/// The recent channels, shared with the Top Shelf extension through the app group's defaults, and the `sofaking://`
/// links the Top Shelf opens the app with. This file is compiled into both targets, so it uses Foundation only.
enum TopShelfChannels {
    static let appGroup = "group.dev.jamesrich.sofaking"
    static let scheme = "sofaking"
    private static let key = "recentChannels"

    static func save(_ channels: [TopShelfChannel]) {
        guard let defaults = UserDefaults(suiteName: appGroup), let data = try? JSONEncoder().encode(channels) else { return }
        defaults.set(data, forKey: key)
    }

    static func load() -> [TopShelfChannel] {
        guard let data = UserDefaults(suiteName: appGroup)?.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([TopShelfChannel].self, from: data)) ?? []
    }

    /// `sofaking://channel/<guideId>`, which the app answers by playing that channel.
    static func url(for guideId: String) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "channel"
        components.path = "/" + guideId
        return components.url
    }

    static func guideId(from url: URL) -> String? {
        guard url.scheme == scheme, url.host == "channel" else { return nil }
        let id = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return id.isEmpty ? nil : id
    }
}
