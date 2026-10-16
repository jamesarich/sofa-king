import SwiftUI
import SofaKingUi

@main
struct SofaKingApp: App {
    init() {
        MainViewControllerKt.startSofaKingIos()
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
