import FamilyControls
import Foundation

/// The app and its extensions run in separate processes. They share settings through the App Group.
enum AppGroup {
    static let id = "group.com.sangar.gal"
    static var defaults: UserDefaults { UserDefaults(suiteName: id) ?? .standard }
}

/// Which set of lines the roast cards use, the same two as on Android.
enum PhrasePack: String, CaseIterable, Identifiable {
    case spicy
    case cry

    var id: String { rawValue }

    var title: String {
        switch self {
        case .spicy: return "Spicy"
        case .cry: return "You may cry"
        }
    }
}

/// Everything GAL remembers. Small values, read by the app and by the extensions.
enum GALSettings {
    private static var d: UserDefaults { AppGroup.defaults }

    private enum Key {
        static let setupDone = "setup_done"
        static let introQuipShown = "intro_quip_shown"
        static let roastsOn = "roasts_on"
        static let sayNoOn = "say_no_on"
        static let threshold = "threshold_minutes"
        static let pack = "phrase_pack"
        static let selection = "selection"
        static let pausedUntil = "paused_until"
    }

    static let thresholdRange = 5...240
    static let defaultThreshold = 30

    static var setupDone: Bool {
        get { d.bool(forKey: Key.setupDone) }
        set { d.set(newValue, forKey: Key.setupDone) }
    }

    static var introQuipShown: Bool {
        get { d.bool(forKey: Key.introQuipShown) }
        set { d.set(newValue, forKey: Key.introQuipShown) }
    }

    /// Roast notifications after every "nag me after" stretch of time in the picked apps.
    static var roastsOn: Bool {
        get { d.object(forKey: Key.roastsOn) as? Bool ?? true }
        set { d.set(newValue, forKey: Key.roastsOn) }
    }

    /// The elephant's "Nooo" screen in front of the picked apps.
    static var sayNoOn: Bool {
        get { d.object(forKey: Key.sayNoOn) as? Bool ?? true }
        set { d.set(newValue, forKey: Key.sayNoOn) }
    }

    static var thresholdMinutes: Int {
        get {
            let stored = d.integer(forKey: Key.threshold)
            return stored == 0 ? defaultThreshold : min(max(stored, thresholdRange.lowerBound), thresholdRange.upperBound)
        }
        set { d.set(newValue, forKey: Key.threshold) }
    }

    static var pack: PhrasePack {
        get { PhrasePack(rawValue: d.string(forKey: Key.pack) ?? "") ?? .spicy }
        set { d.set(newValue.rawValue, forKey: Key.pack) }
    }

    /// The apps and categories that eat your time, as picked in Apple's own app picker. GAL never sees which
    /// apps they are, only opaque tokens.
    static var selection: FamilyActivitySelection {
        get {
            guard let data = d.data(forKey: Key.selection),
                  let decoded = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)
            else { return FamilyActivitySelection() }
            return decoded
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) { d.set(data, forKey: Key.selection) }
        }
    }

    static var hasSelection: Bool {
        let s = selection
        return !(s.applicationTokens.isEmpty && s.categoryTokens.isEmpty && s.webDomainTokens.isEmpty)
    }

    /// After "Open anyway", the "Nooo" screen is off until this time.
    static var pausedUntil: Date? {
        get {
            let t = d.double(forKey: Key.pausedUntil)
            return t > Date().timeIntervalSince1970 ? Date(timeIntervalSince1970: t) : nil
        }
        set { d.set(newValue?.timeIntervalSince1970 ?? 0, forKey: Key.pausedUntil) }
    }
}
