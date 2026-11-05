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
                SecureField(strings.pin, text: $pin)
                    .keyboardType(.numberPad)
                    .onSubmit(unlock)
                Button(strings.lockUnlock, action: unlock).disabled(!SettingsPin.shared.isValid(pin: pin) || waiting)
                if waiting {
                    Text(strings.lockWait(seconds: PinThrottle.companion.WAIT_SECONDS)).font(.caption).foregroundStyle(Color.sofaError)
                } else if wrongPin {
                    Text(strings.lockWrong).font(.caption).foregroundStyle(Color.sofaError)
                }
            } header: {
                Text(strings.lockTitle)
            } footer: {
                Text(strings.lockHelp)
            }
            Section {
                Button(strings.lockForgot) { forgot.toggle() }
                if forgot { recovery }
            }
        }
        .navigationTitle(strings.settingsTitle)
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
            Text(strings.lockForgotNoToken)
                .font(.caption).foregroundStyle(.secondary)
        } else {
            Text(strings.lockForgotHelp)
                .font(.caption).foregroundStyle(.secondary)
            SecureField(strings.apiToken, text: $token).onSubmit(remove)
            Button(strings.lockRemove, action: remove).disabled(ServerAddress.shared.normalizeToken(raw: token) == nil)
            if wrongToken {
                Text(strings.lockForgotWrong).font(.caption).foregroundStyle(Color.sofaError)
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
                Button(strings.lockRemove) { model.core.setSettingsPin(pin: nil) }
                let kids = model.settings?.kidsMode == true
                Button(kids ? strings.kidsTurnOff : strings.kidsTurnOn) {
                    model.core.setKidsMode(on: !kids)
                    // As on the other apps, kids mode starts locked, on the channels.
                    if !kids {
                        model.settingsUnlocked = false
                        model.liveRequested = true
                    }
                }
            } else if setting {
                SecureField(strings.pin, text: $first).keyboardType(.numberPad)
                if !first.isEmpty && !SettingsPin.shared.isValid(pin: first) {
                    Text(strings.pinInvalid).font(.caption).foregroundStyle(Color.sofaError)
                }
                SecureField(strings.pinAgain, text: $again).keyboardType(.numberPad)
                if !again.isEmpty && SettingsPin.shared.isValid(pin: first) && again != first {
                    Text(strings.pinMismatch).font(.caption).foregroundStyle(Color.sofaError)
                }
                Button(strings.lockConfirm) {
                    model.core.setSettingsPin(pin: first)
                    // This visit stays open; the lock takes hold the next time Settings opens.
                    model.settingsUnlocked = true
                    clear()
                }
                .disabled(!canSet)
                Button(strings.lockCancel, action: clear)
            } else {
                Button(strings.lockSet) { setting = true }
            }
        } header: {
            Text(strings.parentalControls)
        } footer: {
            Text(footer)
        }
    }

    private var footer: String {
        strings.lockFooter(locked: model.settings?.settingsLocked == true, kidsMode: model.settings?.kidsMode == true)
    }

    private func clear() {
        setting = false
        first = ""
        again = ""
    }
}
