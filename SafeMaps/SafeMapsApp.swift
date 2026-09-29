import SwiftUI

@main
struct SafeMapsApp: App {
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .tint(Palette.pink)
                .preferredColorScheme(.light)
        }
    }
}

/// Home (real GPS rides) · Activity · Contacts · Demo — same tabs as the Android app.
struct RootView: View {
    @EnvironmentObject var app: AppState
    @StateObject private var home = RideViewModel(realMotion: true)
    /// True while a ride is being navigated: the tab bar and status bar hide so the map gets the screen.
    @State private var homeFullScreen = false
    @State private var demoFullScreen = false

    var body: some View {
        TabView {
            // iOS 16 only hides the tab bar from inside a navigation container.
            NavigationStack {
                RideView(model: home, showsSos: true, fullScreen: $homeFullScreen)
                    .toolbar(.hidden, for: .navigationBar)
                    .toolbar(homeFullScreen ? .hidden : .visible, for: .tabBar)
            }
            .tabItem { Label("Home", systemImage: "house.fill") }

            ActivityView()
                .tabItem { Label("Activity", systemImage: "list.bullet") }

            ContactsView()
                .tabItem { Label("Contacts", systemImage: "person.2.fill") }

            NavigationStack {
                DemoView(fullScreen: $demoFullScreen)
                    .toolbar(.hidden, for: .navigationBar)
                    .toolbar(demoFullScreen ? .hidden : .visible, for: .tabBar)
            }
            .tabItem { Label("Demo", systemImage: "play.circle.fill") }
        }
        .statusBarHidden(homeFullScreen || demoFullScreen)
        .sheet(item: $app.smsDraft) { draft in
            MessageComposer(draft: draft) { app.smsDraft = nil }.ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $app.fakeCallRinging) {
            FakeCallView { app.fakeCallRinging = false }
        }
        #if DEBUG
        .onAppear {
            // e.g. -autoDestination "Shoreline Park" -autoStart — see RideViewModel.debugAutoRide.
            if let query = UserDefaults.standard.string(forKey: "autoDestination") {
                home.debugAutoRide(query: query, start: UserDefaults.standard.bool(forKey: "autoStart"), app: app)
            }
        }
        #endif
    }
}
