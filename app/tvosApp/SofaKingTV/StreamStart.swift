import Foundation

/// Waits for a live stream to have enough segments for AVPlayer to start it.
enum StreamStart {
    private static let segments = 3
    private static let tries = 40

    /// Returns once the playlist at `url` lists three segments, or at once when it is not a live media playlist.
    /// AVPlayer starts a live stream no nearer its end than three segments: handed one with fewer it fetches nothing
    /// and waits for good, and it gives up on a playlist request the server holds past about 20 seconds. So the app
    /// asks for the playlist itself first, and the item's own first request then answers at once.
    static func settled(_ url: URL) async {
        for _ in 0..<tries {
            guard let (data, _) = try? await URLSession.shared.data(from: url) else { return }
            let text = String(decoding: data, as: UTF8.self)
            guard text.contains("#EXTINF"), !text.contains("#EXT-X-ENDLIST") else { return }
            if text.components(separatedBy: "#EXTINF").count - 1 >= segments { return }
            guard (try? await Task.sleep(for: .seconds(1))) != nil else { return }
        }
    }
}
