import SwiftUI
import SofaKingCore

/// The server address and token. The address must be a usable URL before the lineup loads.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var url = ""
    @State private var token = ""

    var body: some View {
        Form {
            Section("Server") {
                TextField("Server address", text: $url)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                SecureField("API token", text: $token)
            }
            Button("Save") { model.core.saveServer(url: url, apiToken: token) }
        }
        .navigationTitle("Settings")
        .onAppear {
            url = model.settings?.serverUrl ?? ""
            token = model.settings?.apiToken ?? ""
        }
    }
}
