import SwiftUI

@main
struct GALApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(Brand.pink)
        }
        .onChange(of: scenePhase) { phase in
            // A safety net for the "Open anyway" pause: if iOS never woke the extension to end it, end it here.
            if phase == .active, GALSettings.setupDone, GALSettings.pausedUntil == nil {
                Shields.apply()
            }
        }
    }
}

enum Brand {
    static let pink = Color(red: 1.0, green: 0.361, blue: 0.541)
    static let ink = Color(red: 0.227, green: 0.082, blue: 0.188)
    static let mint = Color(red: 0.741, green: 0.949, blue: 0.835)
    static let paper = Color(red: 1.0, green: 0.969, blue: 0.984)
}

/// First run goes through the short setup; after that, home.
struct RootView: View {
    @State private var setupDone = GALSettings.setupDone

    var body: some View {
        if setupDone {
            HomeView()
        } else {
            OnboardingView { setupDone = true }
        }
    }
}
