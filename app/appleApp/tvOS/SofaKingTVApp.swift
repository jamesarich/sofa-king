import SwiftUI

@main
struct SofaKingTVApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            // No app-wide tint: tvOS fills an unfocused button with the tint and draws its label in it too, which left
            // the guide's Search and On later as blank pills. Amber goes where a view sets it.
            ContentView().environment(model).preferredColorScheme(.dark)
        }
    }
}
