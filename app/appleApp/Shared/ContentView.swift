import SwiftUI
import SofaKingCore

struct ContentView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = AppTab.live
    /// Whether the start has picked its tab, once the settings are in.
    @State private var tabRestored = false
    /// The tab the start reopened, which stays in the bar until the server says whether it offers it.
    @State private var restored: AppTab?

    var body: some View {
        @Bindable var model = model
        // The tab bar waits for the start's tab: UIKit can fail on a bar that gains a tab and selects it at once, or
        // loses the tab it is leaving, with a navigation bar assertion.
        Group {
            if tabRestored { tabs } else { ProgressView() }
        }
        .background(Color.sofaPlum.ignoresSafeArea())
        .onChange(of: model.settings == nil, initial: true) { _, missing in
            guard !missing, !tabRestored else { return }
            if model.requestedItem != nil || model.requestedDetails != nil {
                // A cold start from a Top Shelf item opens on the Library tab, which plays it or shows its page.
                tab = .library
                restored = .library
            } else if let start = model.startTab, model.requestedChannel == nil, model.requestedLive == nil {
                tab = start
                restored = start
            }
            tabRestored = true
        }
        .onOpenURL { url in
            #if os(tvOS)
            if let guideId = TopShelfContent.guideId(from: url) {
                model.requestedChannel = guideId
                return
            }
            #endif
            if let link = AppLinkKt.parseAppLink(link: url.absoluteString) {
                // A Top Shelf item, or a shortcut's sofaking://vod/<id>?t=<seconds> or sofaking://live/<number>.
                model.open(link)
            } else if let request = PairingRequestKt.parsePairingLink(link: url.absoluteString) {
                model.pair(request)
            }
        }
        .fullScreenCover(isPresented: $model.nowPlayingShown) { NowPlayingView() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.settingsUnlocked = false }
        }
        .confirmationDialog(
            spokenStrings.pairingSwitchTitle(server: PairingDecisionKt.serverLabel(url: model.pairingAsk?.serverUrl ?? "")),
            isPresented: Binding(get: { model.pairingAsk != nil }, set: { if !$0 { model.pairingAsk = nil } }),
            titleVisibility: .visible,
            presenting: model.pairingAsk
        ) { request in
            Button(spokenStrings.pairingSwitchCancel, role: .cancel) { model.pairingAsk = nil }
            Button(spokenStrings.pairingSwitch) { model.pairNow(request) }
        } message: { _ in
            Text(spokenStrings.pairingSwitchMessage(server: PairingDecisionKt.serverLabel(url: model.settings?.serverUrl ?? "")))
        }
        .alert("Pairing failed", isPresented: Binding(get: { model.pairingFailure != nil }, set: { if !$0 { model.pairingFailure = nil } })) {
            Button("OK") {}
        } message: {
            Text(model.pairingFailure ?? "")
        }
    }

    private var libraryInBar: Bool { model.libraryShown || (restored == .library && !model.offerKnown(.library)) }
    private var recordingsInBar: Bool { model.recordingsShown || (restored == .recordings && !model.offerKnown(.recordings)) }

    private var tabs: some View {
        TabView(selection: $tab) {
            live.tabItem { Label(strings.tabLive, systemImage: "tv") }.tag(AppTab.live)
            if libraryInBar {
                LibraryHomeView().tabItem { Label(strings.tabLibrary, systemImage: "film.stack") }.tag(AppTab.library)
            }
            if recordingsInBar {
                RecordingsView().tabItem { Label(strings.tabDvr, systemImage: "record.circle") }.tag(AppTab.recordings)
            }
            NavigationStack { SettingsGate() }.tabItem { Label(strings.tabSettings, systemImage: "gear") }.tag(AppTab.settings)
        }
        .onChange(of: model.requestedChannel) { _, requested in
            // A Top Shelf channel plays from the Live tab.
            if requested != nil { tab = .live }
        }
        .onChange(of: model.liveRequested) { _, requested in
            guard requested else { return }
            model.liveRequested = false
            tab = .live
        }
        .onChange(of: model.requestedItem) { _, requested in
            // A Top Shelf item plays from the Library tab, whose player cover it opens in.
            if requested != nil { tab = .library }
        }
        .onChange(of: model.requestedDetails) { _, requested in
            if requested != nil { tab = .library }
        }
        .onChange(of: tab) { _, picked in
            if picked != .settings { model.settingsUnlocked = false }
            if let remembered = picked.remembered { model.core.setLastTab(tab: remembered) }
        }
        // Once the server has answered, the start's tab stays only if the server offers it, as after any other start.
        .onChange(of: restored.map { model.offerKnown($0) } ?? false) { _, known in
            if known { restored = nil }
        }
        .onChange(of: libraryInBar) { _, shown in
            if !shown && tab == .library { tab = .live }
        }
        .onChange(of: model.libraryHome is LibraryHomeStateUnavailable) { _, gone in
            if gone && tab == .library { tab = .live }
        }
        .onChange(of: recordingsInBar) { _, shown in
            if !shown && tab == .recordings { tab = .live }
        }
        .onChange(of: model.recordings is RecordingsStateUnavailable) { _, gone in
            if gone && tab == .recordings { tab = .live }
        }
    }

    private var live: some View {
        NavigationStack {
            switch model.state {
            case let ready as LineupStateReady:
                ChannelListView(lineup: ready.visible)
            case is LineupStateUnconfigured:
                SettingsGate()
            case let failed as LineupStateFailed:
                VStack(spacing: 32) {
                    Text(strings.loadFailed).font(.title2)
                    Text(failed.message).foregroundStyle(.secondary)
                    Button(strings.retry) { model.core.refresh() }
                    NavigationLink(strings.tabSettings) { SettingsGate() }
                }
            default:
                ProgressView(strings.loading)
            }
        }
    }
}

