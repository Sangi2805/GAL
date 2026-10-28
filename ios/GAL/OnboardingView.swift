import FamilyControls
import SwiftUI
import UserNotifications

/// The whole first run on one screen, kept simple: the "describe yourself" joke, one button that asks iOS for
/// everything GAL needs, then picking the apps that eat your time.
struct OnboardingView: View {
    var onDone: () -> Void

    @State private var showQuip = !GALSettings.introQuipShown
    @State private var askingWord = true
    @State private var word = ""
    @State private var progress: CGFloat = 0
    @State private var authorized = AuthorizationCenter.shared.authorizationStatus == .approved
    @State private var error: String?
    @State private var picking = false
    @State private var selection = GALSettings.selection

    var body: some View {
        ZStack {
            Brand.paper.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 20) {
                    ElephantView(mood: authorized ? .proud : .smug, proud: authorized ? 1 : 0)
                        .frame(width: 220, height: 220)
                    Text("Welcome to GAL")
                        .font(.largeTitle.bold())
                        .foregroundStyle(Brand.ink)
                    Text("Our pink elephant roasts you when you have been on your phone too long. Less phone, more life. Nothing leaves your phone.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(Brand.ink.opacity(0.8))

                    if !authorized {
                        Button(action: authorize) {
                            Text("Allow").font(.headline).frame(maxWidth: .infinity, minHeight: 50)
                        }
                        .buttonStyle(.borderedProminent)
                        Text("iOS will ask about Screen Time and notifications. GAL never blocks anything for good, and never sees which apps you use, only how long.")
                            .font(.footnote)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    } else {
                        Text(GALSettings.hasSelection || hasPicked ? "Apps picked. You can change them any time." : "Now pick the apps that eat your time. Social media is a good start.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(Brand.ink)
                        Button { picking = true } label: {
                            Text(hasPicked ? "Change apps" : "Pick apps").font(.headline).frame(maxWidth: .infinity, minHeight: 50)
                        }
                        .buttonStyle(.bordered)
                        Button(action: finish) {
                            Text("Start").font(.headline).frame(maxWidth: .infinity, minHeight: 50)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(!hasPicked)
                    }
                    if let error {
                        Text(error).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
                    }
                }
                .padding(24)
            }
            if showQuip { quip }
        }
        .familyActivityPicker(isPresented: $picking, selection: $selection)
    }

    private var hasPicked: Bool {
        !(selection.applicationTokens.isEmpty && selection.categoryTokens.isEmpty && selection.webDomainTokens.isEmpty)
    }

    /// "Describe yourself in one word", cut off after 3 seconds either way. Nothing typed is kept.
    private var quip: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(spacing: 14) {
                if askingWord {
                    Text("Before we start").font(.title3.bold())
                    Text("Describe yourself in one word.")
                    TextField("One word", text: $word)
                        .textFieldStyle(.roundedBorder)
                        .submitLabel(.done)
                    ProgressView(value: progress)
                } else {
                    ElephantView(mood: .bored).frame(width: 90, height: 90)
                    Text("Never mind. We don't care.").font(.title3.bold()).multilineTextAlignment(.center)
                    Text("This app has one job: keeping you off your phone. Getting to know you would only keep you on it longer.")
                        .multilineTextAlignment(.center)
                    Button("Fair enough") {
                        GALSettings.introQuipShown = true
                        withAnimation { showQuip = false }
                    }
                    .font(.headline)
                }
            }
            .foregroundStyle(Brand.ink)
            .padding(22)
            .background(RoundedRectangle(cornerRadius: 24).fill(Color.white))
            .padding(28)
        }
        .task {
            guard askingWord else { return }
            let steps = 60
            for step in 1...steps {
                try? await Task.sleep(nanoseconds: 3_000_000_000 / UInt64(steps))
                progress = CGFloat(step) / CGFloat(steps)
            }
            word = ""
            hideKeyboard()
            withAnimation { askingWord = false }
        }
    }

    private func authorize() {
        Task {
            do {
                try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
                _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                await MainActor.run {
                    authorized = AuthorizationCenter.shared.authorizationStatus == .approved
                    error = authorized ? nil : "Screen Time access was not given. GAL cannot work without it."
                }
            } catch {
                await MainActor.run {
                    self.error = "Screen Time access was not given (\(error.localizedDescription)). Try again, or check Settings > Screen Time."
                }
            }
        }
    }

    private func finish() {
        GALSettings.selection = selection
        GALSettings.roastsOn = true
        GALSettings.sayNoOn = true
        GALSettings.setupDone = true
        Monitoring.restart()
        Shields.apply()
        onDone()
    }
}

func hideKeyboard() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}
