import SwiftUI
import SofaKingUi

@main
struct SofaKingApp: App {
    init() {
        MainViewControllerKt.initSofaKingIos()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
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
