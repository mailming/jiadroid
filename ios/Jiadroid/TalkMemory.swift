import Foundation

/// Short-term conversation turns on the phone (this session). Sticky long-lived
/// facts live in `AudienceKb` and persist across restarts.
final class TalkMemory {
    struct TalkTurn {
        let role: String
        let text: String
    }

    private let maxMessages: Int
    private var messages: [TalkTurn] = []
    private let lock = NSLock()

    init(maxMessages: Int = 40) {
        self.maxMessages = maxMessages
    }

    func rememberExchange(user: String, assistant: String) {
        rememberUser(user)
        rememberAssistant(assistant)
    }

    /// Stage the user line before calling the model so history includes this turn.
    func rememberUser(_ user: String) {
        let you = user.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        if you.isEmpty { return }
        lock.lock()
        defer { lock.unlock() }
        if let last = messages.last, last.role == "user", last.text == you { return }
        if messages.last?.role == "user" { messages.removeLast() }
        add(role: "user", text: you)
    }

    func rememberAssistant(_ assistant: String) {
        let me = assistant.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        if me.isEmpty { return }
        lock.lock()
        defer { lock.unlock() }
        add(role: "assistant", text: me)
    }

    func chatTurns() -> [TalkTurn] {
        lock.lock()
        defer { lock.unlock() }
        return messages
    }

    func clear() {
        lock.lock()
        messages.removeAll()
        lock.unlock()
    }

    func contextBlock() -> String {
        lock.lock()
        defer { lock.unlock() }
        if messages.isEmpty { return "" }
        let lines = messages.map { turn in
            let who = turn.role == "user" ? "Them" : "You"
            return "\(who): \(turn.text)"
        }.joined(separator: "\n")
        return "Recent talk:\n\(lines)"
    }

    func recallReply(heard: String) -> SpokenReply? {
        let lower = heard.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if lower.isEmpty { return nil }
        lock.lock()
        defer { lock.unlock() }
        if lower.range(of: #"\b(what did i (just )?say|what was that|repeat that)\b"#, options: .regularExpression) != nil {
            let last = messages.last(where: { $0.role == "user" })?.text
            if let last, !last.isEmpty {
                return SpokenReply(say: "You said \(last).", emotion: .neutral)
            }
            return SpokenReply(say: "I don't have anything recent from you yet.", emotion: .curious)
        }
        return nil
    }

    private func add(role: String, text: String) {
        messages.append(TalkTurn(role: role, text: text))
        if messages.count > maxMessages {
            messages.removeFirst(messages.count - maxMessages)
        }
    }
}
