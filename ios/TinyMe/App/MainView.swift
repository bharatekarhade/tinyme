import SwiftUI

/// The signed-in app: Home and History under one tab bar, with chat, settings and the entry panel on top.
struct MainView: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var router = MainRouter()
    @State private var home: HomeModel
    @State private var history: EntryListModel

    private let api: APIClient

    init(api: APIClient) {
        self.api = api
        _home = State(initialValue: HomeModel(api: api))
        _history = State(initialValue: EntryListModel(api: api, source: .history(kind: nil, query: "")))
    }

    var body: some View {
        @Bindable var router = router

        Group {
            switch router.tab {
            case .home:
                HomeView(model: home, api: api)
            case .history:
                HistoryView(model: history, api: api)
            }
        }
        .safeAreaInset(edge: .bottom) {
            // A drill-down is its own screen: the bar comes back when you return to Home.
            if router.tab == .history || router.homePath.isEmpty {
                HomeTabBar(
                    selection: router.tab == .home ? .home : .history,
                    onSelect: { router.tab = $0 == .home ? .home : .history },
                    onNewChat: { router.openChat() }
                )
            }
        }
        .environment(router)
        .fullScreenCover(item: $router.chat) { draft in
            if let stream = environment.session.stream {
                ChatView(api: api, stream: stream, draft: draft.text, serverName: serverName) { [home, router] in
                    home.invalidate()
                    router.historyIsStale = true
                }
            }
        }
        .overlay {
            SettingsSheet(isPresented: $router.showsSettings, session: environment.session)
        }
        .overlay {
            BottomPanel(isPresented: $router.showsEntry) {
                if let entry = router.entry {
                    EntryDetailSheet(entry: entry, onClose: { router.showsEntry = false }) { draft in
                        router.openChat(draft: draft)
                    }
                }
            }
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
}
