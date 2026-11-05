import AVFoundation
import SwiftUI

@main
struct SofaKingApp: App {
    @State private var model = AppModel()

    init() {
        // Playback category, so video carries on in Picture in Picture and music behind the app.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            // A `sofaking://pair` link, from the admin portal's QR code, pairs the app with that server; a
            // `sofaking://vod` or `sofaking://live` link plays that item or channel (ContentView reads them).
            ContentView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(.sofaAmber)
        }
    }
}
