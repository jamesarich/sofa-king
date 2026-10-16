import SwiftUI
import SofaKingCore

/// The server address and token. The address must be a usable URL before the lineup loads.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var token = ""
    @State private var code = ""
    @State private var pairing = false
    @State private var pairingError: String?
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
            // Saving a changed address or token reloads the lineup by itself.
            Button("Save") {
                model.core.saveServer(url: url, apiToken: token)
                dismiss()
            }
            Section("Pair with a code") {
                TextField("Pairing code", text: $code)
                    .keyboardType(.numberPad)
                Button(pairing ? "Pairing…" : "Pair") {
                    pairing = true
                    pairingError = nil
                    Task {
                        let failure = try? await model.core.pair(url: url, code: code)
                        pairing = false
                        switch failure {
                        case .none: dismiss()
                        case .invalidCode: pairingError = "That code was not accepted. Codes last ten minutes and work once; show a new one in the admin portal."
                        case .olderServer: pairingError = "This server does not pair yet; enter the API token instead."
                        case .unreachable, .some: pairingError = "No Sofa King server answered at that address."
                        }
                    }
                }
                .disabled(pairing || url.isEmpty || code.count < 6)
                if let pairingError { Text(pairingError).foregroundStyle(.red).font(.caption) }
                Text("In the admin portal, open Viewers and choose Show a pairing code; enter the server address above and the code here instead of the token.")
                    .font(.caption).foregroundStyle(.secondary)
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
