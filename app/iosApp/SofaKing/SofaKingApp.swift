import SwiftUI
import SofaKingUi

@main
struct SofaKingApp: App {
    init() {
        MainViewControllerKt.startSofaKingIos()
        #if DEBUG
        // `xcrun simctl launch booted dev.jamesrich.sofaking -serverUrl http://host:port -apiToken TOKEN -muted YES`
        // saves a server and mutes the player without touching the simulator; the UI tests launch the app this way.
        let args = UserDefaults.standard
        let serverUrl = args.string(forKey: "serverUrl")
        if serverUrl != nil || args.bool(forKey: "muted") {
            MainViewControllerKt.applySofaKingLaunchSettings(serverUrl: serverUrl, apiToken: args.string(forKey: "apiToken"), muted: args.bool(forKey: "muted"))
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                // A `sofaking://pair` link, from the admin portal's QR code, pairs the app with that server.
                .onOpenURL { MainViewControllerKt.openSofaKingLink(link: $0.absoluteString) }
        }
    }
}

/// The whole app, drawn by Compose Multiplatform.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
