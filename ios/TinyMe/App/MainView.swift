import SwiftUI
import UIKit

/// The signed-in app: Home and History under one tab bar, with chat, settings and the entry panel on top.
struct MainView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase
    @State private var router = MainRouter()
    @State private var home: HomeModel
    @State private var upcoming: UpcomingModel
    @State private var history: EntryListModel

    private let api: APIClient

    init(api: APIClient) {
        self.api = api
        _home = State(initialValue: HomeModel(api: api))
        _upcoming = State(initialValue: UpcomingModel(api: api))
        _history = State(initialValue: EntryListModel(api: api, source: .history(kind: nil, query: "")))
    }

    var body: some View {
        @Bindable var router = router

        Group {
            switch router.tab {
            case .home:
                HomeView(model: home, api: api)
            case .upcoming:
                UpcomingView(model: upcoming) { draft in router.openChat(draft: draft) }
            case .history:
                HistoryView(model: history, api: api)
            }
        }
        .sheet(isPresented: $router.showsEntry) {
            if let entry = router.entry {
                EntryDetailSheet(entry: entry, onClose: { router.showsEntry = false }) { draft in
                    router.openChat(draft: draft)
                }
            }
        }
        .sheet(item: $router.placeTarget) { target in
            PlaceDetailSheet(api: api, placeId: target.id, onChanged: {
                home.invalidate()
            }, onRemoved: {
                router.placeTarget = nil
            })
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .safeAreaInset(edge: .bottom) {
            // A drill-down is its own screen: the bar comes back when you return to Home.
            if router.tab != .home || router.homePath.isEmpty {
                HomeTabBar(
                    selection: tabBarSelection,
                    onSelect: { tab in
                        switch tab {
                        case .home: router.tab = .home
                        case .upcoming: router.tab = .upcoming
                        case .history: router.tab = .history
                        }
                    },
                    onNewChat: { router.openChat() }
                )
            }
        }
        .environment(router)
        .fullScreenCover(item: $router.chat) { draft in
            ChatView(
                api: api, outbox: environment.outbox, draft: draft.text, serverName: serverName,
                onOpenPlan: { planId in
                    router.chat = nil
                    router.tab = .upcoming
                    upcoming.focus(planId: planId)
                },
                onOpenPlace: { placeId in router.openPlaceFromChat(placeId) },
                onRequestLocationPermission: {
                    await environment.session.requestLocationPermission()
                },
                onOpenLocationSettings: {
                    if let settings = URL(string: UIApplication.openSettingsURLString) {
                        Task { await UIApplication.shared.open(settings) }
                    }
                }
            ) { [home, upcoming, router] in
                home.invalidate()
                upcoming.invalidate()
                router.historyIsStale = true
            }
        }
        .onChange(of: router.chat?.id) { _, chatID in
            if chatID != nil { environment.session.warmUpLocation() }
            if chatID == nil {
                router.presentPendingPlace()
                if let api = environment.session.api {
                    Task { await environment.nearbyPlaceAlerts.refresh(api: api) }
                }
            }
        }
        .overlay {
            SettingsSheet(isPresented: $router.showsSettings, session: environment.session) { [home, router] in
                // Everything on the server is gone: drop what this phone still holds and start again on Home.
                router.showsSettings = false
                router.showsEntry = false
                router.homePath = []
                router.tab = .home
                router.historyIsStale = true
                Task { await environment.outbox.clear() }
                environment.health.reset()
                environment.healthSync.disconnected()   // stop watching Health; its entries are gone too
                WidgetSnapshot.clearAndReload()
                home.invalidate()
                upcoming.invalidate()
                home.announce("All your data was deleted.")
            }
        }
        .onAppear {
            // Foreground is caught below; this is the launch.
            environment.session.warmUpLocation()
            environment.healthSync.start(api: environment.session.api)
        }
        .task {
            // The outbox sends with chat closed too (a message queued offline): reload what a turn changed.
            let (_, events) = await environment.outbox.subscribe()
            for await event in events {
                guard case .finished(_, let outcome, _) = event, outcome != .completed(changedData: false) else { continue }
                home.invalidate()
                upcoming.invalidate()
                router.historyIsStale = true
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                environment.session.warmUpLocation()   // so chat never waits on GPS
                Task { await environment.nearbyPlaceAlerts.appBecameActive(api: environment.session.api) }
                Task { await environment.outbox.wake(retryNow: true) }
                Task { await environment.health.pushIfNeeded(api: environment.session.api) }
                environment.healthSync.start(api: environment.session.api)
            }
        }
        .task {
            await environment.nearbyPlaceAlerts.appBecameActive(api: environment.session.api)
        }
        .onChange(of: environment.healthSync.changes) {
            // New Health numbers: the cards and History reload.
            home.invalidate()
            router.historyIsStale = true
        }
        .onOpenURL { url in
            // The widget's "Journal something?" bar.
            if url == WidgetSnapshot.chatURL, router.chat == nil {
                router.openChat()
            }
        }
    }

    private var serverName: String {
        if case .signedIn(let server, _) = environment.session.state {
            return server.host() ?? server.absoluteString
        }
        return ""
    }

    private var tabBarSelection: HomeTabBar.Tab {
        switch router.tab {
        case .home: .home
        case .upcoming: .upcoming
        case .history: .history
        }
    }
}
