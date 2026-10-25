import SwiftUI
import SofaKingCore

/// The server address and token, and what covers live ads. The address must be a usable URL before the lineup loads.
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
    @State private var browser = ServerBrowser()

    var body: some View {
        Form {
            // Choosing a server found on the network fills the address; pair with a code below.
            if !browser.servers.isEmpty {
                Section("Servers on this network") {
                    ForEach(browser.servers) { server in
                        Button("\(server.name) at \(server.url.replacingOccurrences(of: "http://", with: ""))") { url = server.url }
                    }
                }
            }
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
                        if let failure { pairingError = failure.message } else { dismiss() }
                    }
                }
                .disabled(pairing || url.isEmpty || code.count < 6)
                if let pairingError { Text(pairingError).foregroundStyle(.red).font(.caption) }
                Text("In the admin portal, open Players › Devices and choose Show a pairing code, then enter the server address above and the code here. No token needed.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section {
                Picker("During ads", selection: Binding(
                    get: { model.settings?.liveBreaks ?? .play },
                    set: { model.core.setLiveBreaks(mode: $0) }
                )) {
                    Text("Show them").tag(LiveBreaks.play)
                    Text("Logo, muted").tag(LiveBreaks.screenMuted)
                    Text("Logo, sound on").tag(LiveBreaks.screen)
                }
            } header: {
                Text("Live TV")
            } footer: {
                Text("On streaming channels that mark their ads, such as Pluto TV and Tubi, a bouncing logo covers them. Show picture brings the picture back.")
            }
            Section {
                SkipModePicker(title: "Skip intros", kind: .intros)
                SkipModePicker(title: "Skip credits", kind: .credits)
                Toggle("Hide spoilers", isOn: Binding(
                    get: { model.settings?.hideSpoilers ?? false },
                    set: { model.core.setHideSpoilers(enabled: $0) }
                ))
            } header: {
                Text("Library")
            } footer: {
                Text("Automatically, Skip intro or Skip credits counts down 5 seconds and then skips; pausing stops it. With a button, it waits for a press. Off, intros and credits play. Hide spoilers keeps back the summaries of episodes you have not started and blurs their pictures.")
            }
            Section("About") {
                // TMDB's terms ask apps that show its data to say so.
                Text("Movie and show details and images can come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Settings")
        .onAppear { browser.start() }
        .onDisappear { browser.stop() }
        .onAppear {
            guard !filled else { return }
            filled = true
            url = model.settings?.serverUrl ?? ""
            token = model.settings?.apiToken ?? ""
        }
    }
}

/// How the players meet `kind`: skipped by itself after a countdown, offered on a button, or neither.
struct SkipModePicker: View {
    @Environment(AppModel.self) private var model
    let title: String
    let kind: SkipKind

    var body: some View {
        Picker(title, selection: Binding(
            get: { model.settings?.skips.of(kind: kind) ?? .button },
            set: { model.core.setSkip(kind: kind, mode: $0) }
        )) {
            Text("Automatically").tag(SkipMode.auto_)
            Text("With a button").tag(SkipMode.button)
            Text("Off").tag(SkipMode.off)
        }
        // The default style in a List is a bare row of segments that drops the title.
        .pickerStyle(.navigationLink)
    }
}
