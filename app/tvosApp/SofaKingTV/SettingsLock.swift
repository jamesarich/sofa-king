import SwiftUI
import SofaKingCore

/// Settings, behind the PIN while one is set, as on the other apps. Once unlocked it stays open until the viewer
/// leaves the Settings tab or the app goes to the background (`AppModel.settingsUnlocked`).
struct SettingsGate: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.settings?.settingsLocked == true && !model.settingsUnlocked {
            SettingsLockView()
        } else {
            SettingsView()
        }
    }
}

/// The page in front of Settings while it is locked: the PIN and Unlock, and under Forgot the PIN the server's API
/// token as the way out, since the admin can read it in the portal and the device already holds it. Five wrong PINs
/// in a row make the next try wait 30 seconds, for as long as the app runs.
private struct SettingsLockView: View {
    @Environment(AppModel.self) private var model
    @State private var pin = ""
    @State private var wrongPin = false
    @State private var forgot = false
    @State private var token = ""
    @State private var wrongToken = false
    /// Read again when the wait ends, so Unlock comes back by itself.
    @State private var waiting = false

    var body: some View {
        Form {
            Section {
                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .onSubmit(unlock)
                Button("Unlock", action: unlock).disabled(!SettingsPin.shared.isValid(pin: pin) || waiting)
                if waiting {
                    Text("Too many tries; wait 30 seconds.").font(.caption).foregroundStyle(.red)
                } else if wrongPin {
                    Text("That's not the PIN.").font(.caption).foregroundStyle(.red)
                }
            } header: {
                Text("Settings are locked")
            } footer: {
                Text("Enter the PIN to open Settings.")
            }
            Section {
                Button("Forgot the PIN") { forgot.toggle() }
                if forgot { recovery }
            }
        }
        .navigationTitle("Settings")
        .task(id: model.pinWaitEnds) {
            waiting = model.pinWaiting
            guard let ends = model.pinWaitEnds, waiting else { return }
            try? await Task.sleep(for: .seconds(max(ends.timeIntervalSinceNow, 0)))
            waiting = model.pinWaiting
        }
    }

    @ViewBuilder
    private var recovery: some View {
        let apiToken = model.settings?.apiToken ?? ""
        if apiToken.isEmpty {
            Text("This server runs without an API token, so the only way to remove the PIN is to delete the app and install it again.")
                .font(.caption).foregroundStyle(.secondary)
        } else {
            Text("Enter the server's API token instead. The admin portal shows it under Settings › Advanced: Reveal the API token. The right token removes the PIN.")
                .font(.caption).foregroundStyle(.secondary)
            SecureField("API token", text: $token).onSubmit(remove)
            Button("Remove the PIN", action: remove).disabled(ServerAddress.shared.normalizeToken(raw: token) == nil)
            if wrongToken {
                Text("That's not the server's API token.").font(.caption).foregroundStyle(.red)
            }
        }
    }

    private func unlock() {
        guard let stored = model.settings?.settingsPin, SettingsPin.shared.isValid(pin: pin), !model.pinWaiting else { return }
        if SettingsPin.shared.matches(pin: pin, stored: stored) {
            model.pinPassed()
            model.settingsUnlocked = true
        } else {
            model.pinMissed()
            waiting = model.pinWaiting
            wrongPin = true
            pin = ""
        }
    }

    private func remove() {
        let apiToken = model.settings?.apiToken ?? ""
        guard !apiToken.isEmpty, let entered = ServerAddress.shared.normalizeToken(raw: token) else { return }
        if entered == apiToken {
            model.pinPassed()
            model.core.setSettingsPin(pin: nil)
            model.settingsUnlocked = true
        } else {
            wrongToken = true
            token = ""
        }
    }
}

/// Settings › Parental controls: Set a PIN asks for four digits twice; with a PIN set, Remove the PIN takes it off,
/// and kids mode with it, and kids mode turns on and off.
struct ParentalControlsSection: View {
    @Environment(AppModel.self) private var model
    @State private var setting = false
    @State private var first = ""
    @State private var again = ""

    private var canSet: Bool { SettingsPin.shared.isValid(pin: first) && first == again }

    var body: some View {
        Section {
            if model.settings?.settingsLocked == true {
                Button("Remove the PIN") { model.core.setSettingsPin(pin: nil) }
                let kids = model.settings?.kidsMode == true
                Button(kids ? "Turn off kids mode" : "Turn on kids mode") {
                    model.core.setKidsMode(on: !kids)
                    // As on the other apps, kids mode starts locked, on the channels.
                    if !kids {
                        model.settingsUnlocked = false
                        model.liveRequested = true
                    }
                }
            } else if setting {
                SecureField("PIN", text: $first).keyboardType(.numberPad)
                if !first.isEmpty && !SettingsPin.shared.isValid(pin: first) {
                    Text("Enter four digits.").font(.caption).foregroundStyle(.red)
                }
                SecureField("PIN again", text: $again).keyboardType(.numberPad)
                if !again.isEmpty && SettingsPin.shared.isValid(pin: first) && again != first {
                    Text("The PINs don't match.").font(.caption).foregroundStyle(.red)
                }
                Button("Lock settings") {
                    model.core.setSettingsPin(pin: first)
                    // This visit stays open; the lock takes hold the next time Settings opens.
                    model.settingsUnlocked = true
                    clear()
                }
                .disabled(!canSet)
                Button("Cancel", action: clear)
            } else {
                Button("Set a PIN") { setting = true }
            }
        } header: {
            Text("Parental controls")
        } footer: {
            Text(footer)
        }
    }

    private var footer: String {
        guard let settings = model.settings, settings.settingsLocked else {
            return "A PIN keeps Settings closed until it is entered. Watching and switching channels are not affected."
        }
        if settings.kidsMode {
            return "Kids mode is on. Turn it off here to show every channel and title again."
        }
        return "Settings now asks for the PIN. Forgot it? Choose Forgot the PIN on the lock screen and enter the server's API token. Kids mode keeps the channels to the lineup's kids' channels and the library to titles rated G, PG, TV-Y, TV-Y7, TV-G or TV-PG, and hides the DVR tab. Leaving it takes the PIN."
    }

    private func clear() {
        setting = false
        first = ""
        again = ""
    }
}
