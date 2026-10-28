import FamilyControls
import SwiftUI
import UserNotifications

/// Home: the elephant strolling about, and the few settings GAL has.
struct HomeView: View {
    @State private var roastsOn = GALSettings.roastsOn
    @State private var sayNoOn = GALSettings.sayNoOn
    @State private var threshold = GALSettings.thresholdMinutes
    @State private var pack = GALSettings.pack
    @State private var selection = GALSettings.selection
    @State private var picking = false
    @State private var pausedUntil = GALSettings.pausedUntil
    @State private var testSent = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ElephantStage(proud: testSent)
                        .frame(height: 170)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Brand.mint)
                }

                Section {
                    Toggle("Roast me", isOn: $roastsOn)
                    Stepper(value: $threshold, in: GALSettings.thresholdRange, step: 5) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("After every \(MonitorText.minutes(threshold))")
                            Text("in your picked apps today").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .disabled(!roastsOn)
                    Picker("Card style", selection: $pack) {
                        ForEach(PhrasePack.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Roasts")
                } footer: {
                    Text("Up to \(Monitoring.maxCards) a day. The last one is the elephant giving up on you.")
                }

                Section {
                    Toggle("The elephant says no", isOn: $sayNoOn)
                    if let pausedUntil {
                        HStack {
                            Text("Let in until \(pausedUntil.formatted(date: .omitted, time: .shortened))")
                            Spacer()
                            Button("Shut now") {
                                Shields.endPause()
                                self.pausedUntil = nil
                            }
                        }
                    }
                } header: {
                    Text("Nooo screen")
                } footer: {
                    Text("Opening one of your picked apps shows the elephant first. \"Open anyway\" lets you in for \(Shields.pauseMinutes) minutes.")
                }

                Section {
                    Button("Change your apps") { picking = true }
                    Button(testSent ? "Sent. Check your notifications." : "Send me a test roast") { sendTest() }
                        .disabled(testSent)
                } footer: {
                    Text("GAL has no internet access and never sees which apps you use. iOS counts the time and tells GAL when to roast.")
                }
            }
            .navigationTitle("GAL")
        }
        .familyActivityPicker(isPresented: $picking, selection: $selection)
        .onChange(of: roastsOn) { value in
            GALSettings.roastsOn = value
            Monitoring.restart()
        }
        .onChange(of: threshold) { value in
            GALSettings.thresholdMinutes = value
            Monitoring.restart()
        }
        .onChange(of: pack) { value in GALSettings.pack = value }
        .onChange(of: sayNoOn) { value in
            GALSettings.sayNoOn = value
            Shields.apply()
        }
        .onChange(of: picking) { open in
            // Saved when the picker closes.
            guard !open else { return }
            GALSettings.selection = selection
            Monitoring.restart()
            Shields.apply()
        }
        .onAppear { pausedUntil = GALSettings.pausedUntil }
    }

    private func sendTest() {
        let content = UNMutableNotificationContent()
        content.title = "GAL · test"
        content.body = Phrases.card(last: false, pack: pack)
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 3, repeats: false)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "gal.test", content: content, trigger: trigger))
        testSent = true
        Task {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            await MainActor.run { testSent = false }
        }
    }
}

enum MonitorText {
    static func minutes(_ m: Int) -> String {
        if m < 60 { return "\(m) min" }
        if m % 60 == 0 { return "\(m / 60) h" }
        return "\(m / 60) h \(m % 60) min"
    }
}

/// The elephant strolling to and fro across a strip: walk, stop and look about, walk back. Her position comes
/// from the clock, and her steps from the distance covered, so her feet never slide.
struct ElephantStage: View {
    var proud = false

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation) { timeline in
                let size: CGFloat = min(geo.size.height, 160)
                let track = max(geo.size.width - size, 1)
                let speed: CGFloat = 45
                let walkTime = Double(track / speed)
                let rest = 2.5
                let period = 2 * (walkTime + rest)
                let t = timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period)
                let state = Self.state(t: t, walkTime: walkTime, rest: rest, track: track, speed: speed)
                ElephantView(
                    mood: proud ? .proud : .none,
                    facing: state.facing,
                    walking: state.walking,
                    proud: proud ? 1 : 0,
                    walkedDistance: state.distance
                )
                .frame(width: size, height: size)
                .offset(x: state.x, y: geo.size.height - size)
            }
        }
    }

    private static func state(t: Double, walkTime: Double, rest: Double, track: CGFloat, speed: CGFloat)
        -> (x: CGFloat, facing: CGFloat, walking: Bool, distance: CGFloat) {
        if t < walkTime {
            let d = CGFloat(t) * speed
            return (d, 1, true, d)
        } else if t < walkTime + rest {
            return (track, 1, false, track)
        } else if t < 2 * walkTime + rest {
            let d = CGFloat(t - walkTime - rest) * speed
            return (track - d, -1, true, track + d)
        } else {
            return (0, -1, false, 2 * track)
        }
    }
}
