import SwiftUI

/// Whether a failed load's message says the server turned the device's token away. Core hands a failure over as the
/// server's error code and text (`AUTH_REQUIRED: Bearer token required`), or `HTTP_401` and `HTTP_403` when the answer
/// had no error body; `LOOPBACK_ONLY` is a 403 for a server with no token that this device does not share a machine with.
func isTokenRejected(_ message: String) -> Bool {
    ["AUTH_REQUIRED", "LOOPBACK_ONLY", "HTTP_401", "HTTP_403"].contains { message.hasPrefix($0) }
}

/// What a page shows when its load failed: the headline and the message with Retry, or, when the server turned the
/// token away, that the device is no longer paired, with the way to Settings to pair it again. It sits in a
/// `NavigationStack`, which the Settings link needs.
struct LoadFailureView: View {
    let headline: String
    let message: String
    var settingsLink = false
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 32) {
            if isTokenRejected(message) {
                Text("This device is no longer paired").font(.title2)
                Text("The server turned this device's token away. Pair it again in Settings.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                NavigationLink(strings.tabSettings) { SettingsGate() }
                Button(strings.retry, action: retry)
            } else {
                Text(headline).font(.title2)
                Text(message).foregroundStyle(.secondary)
                Button(strings.retry, action: retry)
                if settingsLink { NavigationLink(strings.tabSettings) { SettingsGate() } }
            }
        }
    }
}
