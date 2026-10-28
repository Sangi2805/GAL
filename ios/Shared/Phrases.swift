import Foundation

/// One of the line files made by tools/ios/prepare_assets.py from the Android app's phrases.json.
enum PhraseFile: String {
    case spicy
    case cry
    case giveupSpicy = "giveup_spicy"
    case giveupCry = "giveup_cry"
    case appRoast = "app_roast"
}

/// Picks a random line without loading the whole file. The extensions that show roasts have very little memory,
/// so we jump to a random byte, skip to the next line break and read that one line.
enum Phrases {
    static let fallback = "Your phone misses you less than you think."
    static let fallbackAppRoast = "Are you married to {app}?"

    static func random(_ file: PhraseFile, bundle: Bundle = .main) -> String? {
        guard let url = bundle.url(forResource: file.rawValue, withExtension: "txt"),
              let handle = try? FileHandle(forReadingFrom: url)
        else { return nil }
        defer { try? handle.close() }
        guard let size = try? handle.seekToEnd(), size > 2 else { return nil }
        for _ in 0..<4 {
            if let line = line(in: handle, after: UInt64.random(in: 0..<(size - 1))) { return line }
        }
        // Near the end there may be no whole line left after the random spot: take the first line instead.
        return line(in: handle, after: 0)
    }

    /// A roast card's text: the give-up line for the last card of the day, a normal roast otherwise.
    static func card(last: Bool, pack: PhrasePack = GALSettings.pack) -> String {
        let file: PhraseFile
        switch (pack, last) {
        case (.spicy, false): file = .spicy
        case (.spicy, true): file = .giveupSpicy
        case (.cry, false): file = .cry
        case (.cry, true): file = .giveupCry
        }
        return random(file) ?? fallback
    }

    /// A line for the "Nooo" screen, with the app's name filled in.
    static func appRoast(appName: String?) -> String {
        let line = random(.appRoast) ?? fallbackAppRoast
        return line.replacingOccurrences(of: "{app}", with: appName ?? "this")
    }

    private static func line(in handle: FileHandle, after offset: UInt64) -> String? {
        do {
            try handle.seek(toOffset: offset)
            guard let chunk = try handle.read(upToCount: 1024), !chunk.isEmpty else { return nil }
            let bytes = [UInt8](chunk)
            guard let start = bytes.firstIndex(of: 10) else { return nil }
            let rest = bytes[(start + 1)...]
            guard let end = rest.firstIndex(of: 10) else { return nil }
            let text = String(decoding: bytes[(start + 1)..<end], as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : text
        } catch {
            return nil
        }
    }
}
