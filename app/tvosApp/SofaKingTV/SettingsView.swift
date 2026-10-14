import SwiftUI
import SofaKingCore

/// The server address and token. The address must be a usable URL before the lineup loads.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var token = ""
    // tvOS calls onAppear again when the full-screen keyboard closes, which would put back the saved values.
    @State private var filled = false

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
            Button("Save") {
                model.core.saveServer(url: url, apiToken: token)
                model.core.refresh()
                dismiss()
            }
        }
        .navigationTitle("Settings")
        .onAppear {
            guard !filled else { return }
            filled = true
            url = model.settings?.serverUrl ?? ""
            token = model.settings?.apiToken ?? ""
        }
    }
}
