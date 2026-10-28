import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings

extension DeviceActivityName {
    /// Every day, all day: counts time in the picked apps and fires the roast events.
    static let galDaily = DeviceActivityName("gal.daily")

    /// A one-off window after "Open anyway". When it ends, the "Nooo" screen comes back.
    static let galPause = DeviceActivityName("gal.pause")
}

/// Screen Time monitoring. iOS counts the time, not GAL: we tell it "wake our extension after 30, 60, 90...
/// minutes in these apps today", and the extension sends the roast.
enum Monitoring {
    /// At most this many roasts a day; the last one is the give-up line, as on Android.
    static let maxCards = 12
    private static let prefix = "gal.card."

    static func eventName(_ number: Int) -> DeviceActivityEvent.Name {
        DeviceActivityEvent.Name(prefix + String(number))
    }

    static func cardNumber(of event: DeviceActivityEvent.Name) -> Int? {
        guard event.rawValue.hasPrefix(prefix) else { return nil }
        return Int(event.rawValue.dropFirst(prefix.count))
    }

    /// Starts (or restarts, after a settings change) the daily monitoring. Safe to call any time.
    static func restart() {
        let center = DeviceActivityCenter()
        center.stopMonitoring([.galDaily])
        guard GALSettings.roastsOn, GALSettings.hasSelection else { return }

        let selection = GALSettings.selection
        let threshold = GALSettings.thresholdMinutes
        var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
        for number in 1...maxCards {
            let minutes = number * threshold
            if minutes >= 24 * 60 { break }
            events[eventName(number)] = DeviceActivityEvent(
                applications: selection.applicationTokens,
                categories: selection.categoryTokens,
                webDomains: selection.webDomainTokens,
                threshold: DateComponents(hour: minutes / 60, minute: minutes % 60)
            )
        }
        let schedule = DeviceActivitySchedule(
            intervalStart: DateComponents(hour: 0, minute: 0),
            intervalEnd: DateComponents(hour: 23, minute: 59, second: 59),
            repeats: true
        )
        do {
            try center.startMonitoring(.galDaily, during: schedule, events: events)
        } catch {
            NSLog("GAL: could not start monitoring: \(error.localizedDescription)")
        }
    }

    static func stop() {
        DeviceActivityCenter().stopMonitoring([.galDaily, .galPause])
    }
}

/// The elephant's "Nooo" screen (Apple calls it a shield) in front of the picked apps.
enum Shields {
    private static let store = ManagedSettingsStore()

    /// How long "Open anyway" lets you in. Apple's minimum monitoring window is 15 minutes.
    static let pauseMinutes = 15

    /// Puts the screen up for the picked apps, unless it is switched off or paused.
    static func apply() {
        guard GALSettings.sayNoOn, GALSettings.pausedUntil == nil, GALSettings.hasSelection else {
            clear()
            return
        }
        let s = GALSettings.selection
        store.shield.applications = s.applicationTokens.isEmpty ? nil : s.applicationTokens
        store.shield.applicationCategories = s.categoryTokens.isEmpty ? nil : .specific(s.categoryTokens)
        store.shield.webDomains = s.webDomainTokens.isEmpty ? nil : s.webDomainTokens
    }

    static func clear() {
        store.shield.applications = nil
        store.shield.applicationCategories = nil
        store.shield.webDomains = nil
    }

    /// "Open anyway": take the screen down for a while, and have the monitor extension put it back.
    static func pause() {
        let now = Date()
        let end = now.addingTimeInterval(TimeInterval(pauseMinutes * 60 + 30))
        GALSettings.pausedUntil = end
        clear()
        let calendar = Calendar.current
        let parts: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        let schedule = DeviceActivitySchedule(
            intervalStart: calendar.dateComponents(parts, from: now),
            intervalEnd: calendar.dateComponents(parts, from: end),
            repeats: false
        )
        let center = DeviceActivityCenter()
        center.stopMonitoring([.galPause])
        do {
            try center.startMonitoring(.galPause, during: schedule)
        } catch {
            // If the window cannot be set up, do not leave the apps open for good: the app puts the screen back
            // the next time it opens.
            NSLog("GAL: could not schedule the pause: \(error.localizedDescription)")
        }
    }

    /// The pause is over (called by the monitor extension, and by the app on launch as a safety net).
    static func endPause() {
        GALSettings.pausedUntil = nil
        apply()
    }
}
