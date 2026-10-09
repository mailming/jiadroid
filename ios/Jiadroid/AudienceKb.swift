import Foundation

/// Persistent on-phone audience knowledge base.
///
/// - **Pin:** `name`, `likes`, `from`, and compacted `summary` never expire or get FIFO'd.
/// - **Cap:** at most `maxFacts` freeform `fact-*` notes.
/// - **TTL:** freeform facts older than `factTtl` are dropped (default 30 days).
/// - **Compact:** when over the fact cap, oldest facts fold into `summary`.
final class AudienceKb {
    private struct Note: Equatable {
        var key: String
        var value: String
        var updatedAt: TimeInterval
        var pinned: Bool
    }

    private let loadJson: () -> String?
    private let saveJson: (String) -> Void
    private let maxFacts: Int
    private let factTtl: TimeInterval
    private let summaryMaxChars: Int
    private let clock: () -> Date
    private var notes: [String: Note] = [:]
    private let lock = NSLock()

    init(
        loadJson: @escaping () -> String?,
        saveJson: @escaping (String) -> Void,
        maxFacts: Int = 16,
        factTtl: TimeInterval = 30 * 24 * 60 * 60,
        summaryMaxChars: Int = 400,
        clock: @escaping () -> Date = Date.init
    ) {
        self.loadJson = loadJson
        self.saveJson = saveJson
        self.maxFacts = maxFacts
        self.factTtl = factTtl
        self.summaryMaxChars = summaryMaxChars
        self.clock = clock
        reload()
    }

    convenience init(defaults: UserDefaults = .standard, key: String = "jiadroid_audience_kb") {
        self.init(
            loadJson: { defaults.string(forKey: key) },
            saveJson: { defaults.set($0, forKey: key) }
        )
    }

    func reload() {
        lock.lock()
        defer { lock.unlock() }
        notes.removeAll()
        let now = clock().timeIntervalSince1970
        guard let raw = loadJson()?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return }

        if let data = raw.data(using: .utf8),
           let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let array = root["notes"] as? [[String: Any]] {
            for item in array {
                guard let key = item["key"] as? String, let value = item["value"] as? String else { continue }
                let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
                let trimmedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmedKey.isEmpty || trimmedValue.isEmpty { continue }
                let updatedAt = item["updatedAt"] as? TimeInterval ?? now
                notes[trimmedKey] = Note(
                    key: trimmedKey,
                    value: trimmedValue,
                    updatedAt: updatedAt,
                    pinned: Self.isPinnedKey(trimmedKey)
                )
            }
        } else {
            // Android-compatible line format fallback.
            for line in raw.split(whereSeparator: \.isNewline) {
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty || trimmed.hasPrefix("#") { continue }
                guard let eq = trimmed.firstIndex(of: "=") else { continue }
                let left = String(trimmed[..<eq])
                let value = String(trimmed[trimmed.index(after: eq)...])
                let pipe = left.lastIndex(of: "|")
                let key: String
                let updatedAt: TimeInterval
                if let pipe, left[left.index(after: pipe)...].allSatisfy(\.isNumber) {
                    key = String(left[..<pipe])
                    updatedAt = TimeInterval(String(left[left.index(after: pipe)...])) ?? now
                } else {
                    key = left
                    updatedAt = now
                }
                if key.isEmpty || value.isEmpty { continue }
                notes[key] = Note(key: key, value: value, updatedAt: updatedAt, pinned: Self.isPinnedKey(key))
            }
        }
        _ = maintainLocked()
        persistLocked()
    }

    func rememberFrom(_ user: String) {
        let said = user.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        if said.isEmpty { return }
        lock.lock()
        defer { lock.unlock() }
        guard absorb(said) else { return }
        _ = maintainLocked()
        persistLocked()
    }

    func clear() {
        lock.lock()
        notes.removeAll()
        persistLocked()
        lock.unlock()
    }

    func promptBlock() -> String {
        lock.lock()
        defer { lock.unlock() }
        if maintainLocked() { persistLocked() }
        if notes.isEmpty { return "" }
        let lines = orderedNotes().map { "- \($0.key): \($0.value)" }.joined(separator: "\n")
        return "Audience knowledge base (persistent on this phone):\n\(lines)"
    }

    func recallReply(heard: String) -> SpokenReply? {
        let lower = heard.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if lower.isEmpty { return nil }
        lock.lock()
        defer { lock.unlock() }
        if maintainLocked() { persistLocked() }
        if lower.range(of: #"\b(what('s| is) my name|who am i)\b"#, options: .regularExpression) != nil {
            if let name = notes["name"]?.value, !name.isEmpty {
                return SpokenReply(say: "You're \(name).", emotion: .happy)
            }
            return SpokenReply(say: "I don't know your name yet. Tell me, and I'll remember.", emotion: .curious)
        }
        if lower.range(
            of: #"\b(what do you remember|what do you know about me|what have i told you)\b"#,
            options: .regularExpression
        ) != nil {
            if notes.isEmpty {
                return SpokenReply(
                    say: "Nothing in my knowledge base yet. Say remember, or tell me your name or what you like.",
                    emotion: .curious
                )
            }
            let summary = orderedNotes().prefix(8).map { "\($0.key) \($0.value)" }.joined(separator: "; ")
            return SpokenReply(say: "I remember \(summary).", emotion: .happy)
        }
        if lower.range(of: #"\b(forget (everything|that|me)|clear (your )?memory)\b"#, options: .regularExpression) != nil {
            notes.removeAll()
            persistLocked()
            return SpokenReply(say: "Okay, I cleared what I saved about you on this phone.", emotion: .neutral)
        }
        return nil
    }

    func noteCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return notes.count
    }

    func factCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return notes.keys.filter { $0.hasPrefix("fact-") }.count
    }

    func maintainNow() {
        lock.lock()
        if maintainLocked() { persistLocked() }
        lock.unlock()
    }

    private func absorb(_ user: String) -> Bool {
        let lower = user.lowercased()
        var changed = false
        let now = clock().timeIntervalSince1970
        if let name = explicitName(lower) {
            putPinned(key: "name", value: name, at: now)
            changed = true
        }
        if let match = firstMatch(lower, pattern: #"(?:i like|i love)\s+(.+?)(?:[.!]|$)"#),
           match.count >= 2 {
            let value = match[1].trimmingCharacters(in: .whitespacesAndNewlines)
            if (2...80).contains(value.count) {
                putPinned(key: "likes", value: value, at: now)
                changed = true
            }
        }
        if let match = firstMatch(lower, pattern: #"(?:i live in|i'm from|i am from)\s+(.+?)(?:[.!]|$)"#),
           match.count >= 2 {
            let value = match[1].trimmingCharacters(in: .whitespacesAndNewlines)
            if (2...80).contains(value.count) {
                putPinned(key: "from", value: value, at: now)
                changed = true
            }
        }
        if let match = firstMatch(
            lower,
            pattern: #"(?:remember(?: that)?|don't forget(?: that)?)\s+(.+?)(?:[.!]|$)"#
        ), match.count >= 2 {
            let value = match[1].trimmingCharacters(in: .whitespacesAndNewlines)
            if (2...120).contains(value.count) {
                putFact(value, at: now)
                changed = true
            }
        }
        return changed
    }

    private func explicitName(_ lower: String) -> String? {
        let raw: String?
        if let match = firstMatch(
            lower,
            pattern: #"(?:my name is|call me)\s+([a-z][a-z'’-]*(?:\s+[a-z][a-z'’-]*)?)"#
        ), match.count >= 2 {
            raw = match[1]
        } else if let match = firstMatch(lower, pattern: #"(?:i am|i'm)\s+([a-z][a-z'’-]*)\b"#),
                  match.count >= 2 {
            raw = match[1]
        } else {
            raw = nil
        }
        guard var cleaned = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !cleaned.isEmpty else {
            return nil
        }
        if Self.nameStop.contains(cleaned) { return nil }
        cleaned = cleaned.split(whereSeparator: \.isWhitespace)
            .map { $0.capitalized }
            .joined(separator: " ")
        return cleaned
    }

    private func putPinned(key: String, value: String, at now: TimeInterval) {
        notes[key] = Note(key: key, value: value, updatedAt: now, pinned: true)
    }

    private func putFact(_ value: String, at now: TimeInterval) {
        let key = nextFactKey()
        notes[key] = Note(key: key, value: value, updatedAt: now, pinned: false)
    }

    private func maintainLocked() -> Bool {
        let before = notes
        pruneExpired(now: clock().timeIntervalSince1970)
        compactIfNeeded()
        return notes != before
    }

    private func pruneExpired(now: TimeInterval) {
        guard factTtl > 0 else { return }
        let stale = notes.values.filter { !$0.pinned && now - $0.updatedAt > factTtl }.map(\.key)
        for key in stale { notes.removeValue(forKey: key) }
    }

    private func compactIfNeeded() {
        let facts = notes.values
            .filter { !$0.pinned && $0.key.hasPrefix("fact-") }
            .sorted { $0.updatedAt < $1.updatedAt }
        guard facts.count > maxFacts else { return }
        let overflow = facts.count - maxFacts
        let foldCount = max(overflow, facts.count / 2)
        let folding = Array(facts.prefix(foldCount))
        let chunk = folding.map(\.value).joined(separator: "; ")
        let existing = notes["summary"]?.value ?? ""
        let merged = existing.isEmpty ? chunk : "\(existing); \(chunk)"
        let now = clock().timeIntervalSince1970
        notes["summary"] = Note(key: "summary", value: trimSummary(merged), updatedAt: now, pinned: true)
        for note in folding { notes.removeValue(forKey: note.key) }
    }

    private func trimSummary(_ text: String) -> String {
        guard text.count > summaryMaxChars else { return text }
        let start = text.index(text.endIndex, offsetBy: -summaryMaxChars)
        var cut = String(text[start...])
        if let range = cut.range(of: "; "),
           cut.distance(from: cut.startIndex, to: range.lowerBound) <= 40 {
            cut = String(cut[range.upperBound...])
        }
        return cut
    }

    private func orderedNotes() -> [Note] {
        let pinnedOrder = ["name", "likes", "from", "summary"]
        let pinned = pinnedOrder.compactMap { notes[$0] }
        let facts = notes.values
            .filter { !$0.pinned && $0.key.hasPrefix("fact-") }
            .sorted { $0.updatedAt < $1.updatedAt }
        return pinned + facts
    }

    private func nextFactKey() -> String {
        var n = 1
        while notes["fact-\(n)"] != nil { n += 1 }
        return "fact-\(n)"
    }

    private func persistLocked() {
        let array: [[String: Any]] = orderedNotes().map {
            [
                "key": $0.key,
                "value": $0.value,
                "updatedAt": $0.updatedAt,
                "pinned": $0.pinned,
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: ["notes": array], options: []),
              let json = String(data: data, encoding: .utf8)
        else { return }
        saveJson(json)
    }

    private func firstMatch(_ text: String, pattern: String) -> [String]? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range) else { return nil }
        return (0..<match.numberOfRanges).compactMap { index in
            guard let swiftRange = Range(match.range(at: index), in: text) else { return nil }
            return String(text[swiftRange])
        }
    }

    private static let pinnedKeys: Set<String> = ["name", "likes", "from", "summary"]

    private static func isPinnedKey(_ key: String) -> Bool {
        pinnedKeys.contains(key)
    }

    private static let nameStop: Set<String> = [
        "a", "an", "the", "here", "ready", "fine", "good", "okay", "ok", "sorry",
        "following", "lost", "hungry", "tired", "happy", "sad", "back", "home",
        "going", "coming", "done", "trying", "looking", "listening", "thinking",
    ]
}
