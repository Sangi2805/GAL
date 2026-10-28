import DeviceActivity
import Foundation
import UserNotifications

/// Woken by iOS when time in the picked apps passes another "nag me after" mark, and when the "Open anyway"
/// window ends. It runs for a moment with very little memory, so it only reads one line and posts it.
class MonitorExtension: DeviceActivityMonitor {

    override func eventDidReachThreshold(_ event: DeviceActivityEvent.Name, activity: DeviceActivityName) {
        super.eventDidReachThreshold(event, activity: activity)
        guard activity == .galDaily, GALSettings.roastsOn, let number = Monitoring.cardNumber(of: event) else { return }
        let minutes = number * GALSettings.thresholdMinutes
        let last = number == Monitoring.maxCards
        postRoast(Phrases.card(last: last), minutes: minutes)
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        if activity == .galPause { Shields.endPause() }
    }

    private func postRoast(_ text: String, minutes: Int) {
        let content = UNMutableNotificationContent()
        content.title = "GAL · \(Self.describe(minutes)) today"
        content.body = text
        content.sound = .default
        content.threadIdentifier = "gal.roasts"
        let request = UNNotificationRequest(identifier: "gal.roast.\(minutes)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    static func describe(_ minutes: Int) -> String {
        if minutes < 60 { return "\(minutes) min" }
        if minutes % 60 == 0 { return "\(minutes / 60) h" }
        return "\(minutes / 60) h \(minutes % 60) min"
    }
}
