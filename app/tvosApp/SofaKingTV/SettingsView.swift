import SwiftUI
import SofaKingCore

/// The server address, the pairing code and the token, what covers live ads, and whether the favorites follow the household's. The address must be a usable URL before the lineup loads.
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
                Section(strings.discoveredServers) {
                    ForEach(browser.servers) { server in
                        Button(strings.discoveredServer(name: server.name, address: server.url.replacingOccurrences(of: "http://", with: ""))) { url = server.url }
                    }
                }
            }
            Section(strings.serverSection) {
                TextField(strings.serverUrl, text: $url)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            Section(strings.pairWithCode) {
                TextField(strings.pairingCodeLabel, text: $code)
                    .keyboardType(.numberPad)
                Button(pairing ? strings.pairing : strings.pair) {
                    pairing = true
                    pairingError = nil
                    Task {
                        let failure = try? await model.core.pair(url: url, code: code)
                        pairing = false
                        if let failure { pairingError = failure.message } else { dismiss() }
                    }
                }
                .disabled(pairing || url.isEmpty || code.count < 6)
                if let pairingError { Text(pairingError).foregroundStyle(Color.sofaError).font(.caption) }
                Text(strings.pairingHelp)
                    .font(.caption).foregroundStyle(.secondary)
            }
            // Saving a changed address or token reloads the lineup by itself.
            Section {
                SecureField(strings.apiToken, text: $token)
                Button(strings.settingsSave) {
                    model.core.saveServer(url: url, apiToken: token)
                    dismiss()
                }
            } footer: {
                Text(strings.apiTokenHelp)
            }
            Section {
                Picker(strings.liveBreaks, selection: Binding(
                    get: { model.settings?.liveBreaks ?? .play },
                    set: { model.core.setLiveBreaks(mode: $0) }
                )) {
                    Text(strings.liveBreaksPlay).tag(LiveBreaks.play)
                    Text(strings.liveBreaksScreenMuted).tag(LiveBreaks.screenMuted)
                    Text(strings.liveBreaksScreen).tag(LiveBreaks.screen)
                }
            } header: {
                Text(strings.livePage)
            } footer: {
                Text(strings.liveBreaksHelp)
            }
            Section {
                Picker(strings.autoOpen, selection: Binding(
                    get: { model.settings?.autoOpenDelay ?? .after10Seconds },
                    set: { model.core.setAutoOpenDelay(delay: $0) }
                )) {
                    Text(strings.autoOpen10).tag(AutoOpenDelay.after10Seconds)
                    Text(strings.autoOpen30).tag(AutoOpenDelay.after30Seconds)
                    Text(strings.autoOpenOff).tag(AutoOpenDelay.off)
                }
                .pickerStyle(.navigationLink)
            } footer: {
                Text(strings.autoOpenHelp)
            }
            Section {
                Toggle(strings.previewSound, isOn: Binding(
                    get: { model.settings?.previewSound ?? true },
                    set: { model.core.setPreviewSound(enabled: $0) }
                ))
            } footer: {
                Text(strings.previewSoundHelp)
            }
            Section {
                Toggle("Captions", isOn: Binding(
                    get: { model.settings?.captionsEnabled ?? false },
                    set: { model.core.setCaptionsEnabled(enabled: $0) }
                ))
            } footer: {
                Text(strings.captionsHelp)
            }
            if model.capabilities?.household == true {
                Section {
                    Toggle(strings.shareFavorites, isOn: Binding(
                        get: { model.settings?.shareFavorites ?? false },
                        set: { model.core.setShareFavorites(on: $0) }
                    ))
                } header: {
                    Text(strings.channelsSection)
                } footer: {
                    Text(strings.shareFavoritesHelp)
                }
            }
            Section {
                SkipModePicker(title: strings.skipIntros, kind: .intros)
            } header: {
                Text(strings.libraryPage)
            } footer: {
                Text(strings.skipIntrosHelp)
            }
            Section {
                SkipModePicker(title: strings.skipCredits, kind: .credits)
            } footer: {
                Text(strings.skipCreditsHelp)
            }
            Section {
                Toggle(strings.hideSpoilers, isOn: Binding(
                    get: { model.settings?.hideSpoilers ?? false },
                    set: { model.core.setHideSpoilers(enabled: $0) }
                ))
            } footer: {
                Text(strings.hideSpoilersHelp)
            }
            ParentalControlsSection()
            Section(strings.aboutSection) {
                // TMDB's terms ask apps that show its data to say so.
                Text(strings.tmdbNotice)
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(strings.settingsTitle)
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
            Text(strings.skipModeAuto).tag(SkipMode.auto_)
            Text(strings.skipModeButton).tag(SkipMode.button)
            Text(strings.skipModeOff).tag(SkipMode.off)
        }
        // The default style in a List is a bare row of segments that drops the title.
        .pickerStyle(.navigationLink)
    }
}
