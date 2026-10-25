import Foundation

/// A channel as the Top Shelf shows it: the app writes the recent channels, the extension reads them.
struct TopShelfChannel: Codable, Equatable {
    let guideId: String
    let number: String
    let name: String
    let logo: String?
}

/// A Continue watching item as the Top Shelf shows it: its title, the line under it, its picture and how far in it is.
struct TopShelfItem: Codable, Equatable {
    let id: String
    let title: String
    let image: String?
    /// How far in, from 0 to 1; nil for the next episode of a show, not started yet.
    let progress: Double?
}

/// What the Top Shelf shows, shared with its extension through the app group's defaults: the library's Continue
/// watching, then the recent channels; and the `sofaking://` links it opens the app with. This file is compiled into
/// both targets, so it uses Foundation only.
enum TopShelfContent {
    static let appGroup = "group.dev.jamesrich.sofaking"
    static let scheme = "sofaking"
    private static let channelsKey = "recentChannels"
    private static let continueKey = "continueWatching"

    static func save(_ channels: [TopShelfChannel]) {
        write(channels, forKey: channelsKey)
    }

    static func load() -> [TopShelfChannel] {
        read([TopShelfChannel].self, forKey: channelsKey) ?? []
    }

    static func saveContinue(_ items: [TopShelfItem]) {
        write(items, forKey: continueKey)
    }

    static func loadContinue() -> [TopShelfItem] {
        read([TopShelfItem].self, forKey: continueKey) ?? []
    }

    /// `sofaking://channel/<guideId>`, which the app answers by playing that channel.
    static func url(for guideId: String) -> URL? {
        link(host: "channel", id: guideId)
    }

    static func guideId(from url: URL) -> String? {
        id(in: url, host: "channel")
    }

    /// `sofaking://vod/<id>`, which the app answers by playing that library item where the household stopped.
    static func vodUrl(for itemId: String) -> URL? {
        link(host: "vod", id: itemId)
    }

    private static func link(host: String, id: String) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.path = "/" + id
        return components.url
    }

    private static func id(in url: URL, host: String) -> String? {
        guard url.scheme == scheme, url.host == host else { return nil }
        let id = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return id.isEmpty ? nil : id
    }

    private static func write<T: Encodable>(_ value: T, forKey key: String) {
        guard let defaults = UserDefaults(suiteName: appGroup), let data = try? JSONEncoder().encode(value) else { return }
        defaults.set(data, forKey: key)
    }

    private static func read<T: Decodable>(_ type: T.Type, forKey key: String) -> T? {
        guard let data = UserDefaults(suiteName: appGroup)?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
