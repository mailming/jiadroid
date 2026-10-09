import Foundation

/// A language model for spoken replies. Anthropic URLs use the Messages API with
/// Claude Haiku 5.5 settings; other hosts use OpenAI chat completions (OpenAI,
/// xAI Grok, Ollama). Recent turns come from `TalkMemory`; lasting facts from
/// `AudienceKb` (persisted on the phone).
final class Brain {
    private let baseURL: String
    private let model: String
    private let key: String
    private let anthropic: Bool

    init(baseURL: String, model: String, key: String) {
        self.baseURL = baseURL
        self.model = model
        self.key = key
        self.anthropic = baseURL.lowercased().contains("anthropic")
    }

    /// Blocks on the network. Call it off the main thread.
    func answer(
        heard: String,
        seeing: String,
        name: String = defaultRobotName,
        memory: TalkMemory,
        kb: AudienceKb
    ) throws -> SpokenReply {
        let who = normalizeRobotName(name).capitalized
        let context = [kb.promptBlock(), memory.contextBlock()]
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
        var system = Self.personality(who) + "\nRight now your camera says: \(seeing)."
        if !context.isEmpty {
            system += "\n\n\(context)\nUse the audience knowledge base and recent turns when relevant. Do not invent details."
        }
        let raw = try anthropic
            ? askAnthropic(system: system, heard: heard, memory: memory)
            : askOpenAI(system: system, heard: heard, memory: memory)
        return parseSpokenReply(raw)
    }

    private func askAnthropic(system: String, heard: String, memory: TalkMemory) throws -> String {
        var messages: [[String: String]] = memory.chatTurns().map { ["role": $0.role, "content": $0.text] }
        messages.append(["role": "user", "content": heard])
        let body: [String: Any] = [
            "model": model,
            "max_tokens": Self.anthropicMaxTokens,
            "system": system,
            "messages": messages,
            "output_config": ["effort": "low"],
        ]
        let json = try post(path: "/messages", body: body, anthropic: true)
        if json["stop_reason"] as? String == "refusal" {
            throw BrainError.failed("model refused the request")
        }
        guard let blocks = json["content"] as? [[String: Any]] else {
            throw BrainError.failed("model reply was empty")
        }
        let text = blocks
            .compactMap { block -> String? in
                guard block["type"] as? String == "text" else { return nil }
                return block["text"] as? String
            }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw BrainError.failed("model reply was empty") }
        return text
    }

    private func askOpenAI(system: String, heard: String, memory: TalkMemory) throws -> String {
        var messages: [[String: String]] = [
            ["role": "system", "content": system],
        ]
        messages.append(contentsOf: memory.chatTurns().map { ["role": $0.role, "content": $0.text] })
        messages.append(["role": "user", "content": heard])
        let body: [String: Any] = [
            "model": model,
            "messages": messages,
            "max_tokens": Self.openAIMaxTokens,
            "temperature": 0.9,
        ]
        let json = try post(path: "/chat/completions", body: body, anthropic: false)
        guard
            let choices = json["choices"] as? [[String: Any]],
            let message = choices.first?["message"] as? [String: Any],
            let content = message["content"] as? String
        else {
            throw BrainError.failed("model reply was empty")
        }
        let text = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw BrainError.failed("model reply was empty") }
        return text
    }

    private func post(path: String, body: [String: Any], anthropic: Bool) throws -> [String: Any] {
        let root = baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: root + path) else {
            throw BrainError.badURL
        }
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedKey.isEmpty {
            if anthropic {
                request.setValue(trimmedKey, forHTTPHeaderField: "x-api-key")
                request.setValue(Self.anthropicVersion, forHTTPHeaderField: "anthropic-version")
            } else {
                request.setValue("Bearer \(trimmedKey)", forHTTPHeaderField: "Authorization")
            }
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let semaphore = DispatchSemaphore(value: 0)
        var payload: Data?
        var status = 0
        var transport: Error?
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            payload = data
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
            transport = error
            semaphore.signal()
        }
        task.resume()
        if semaphore.wait(timeout: .now() + 38) == .timedOut {
            task.cancel()
            throw BrainError.failed("model timed out")
        }
        if let transport { throw BrainError.failed(transport.localizedDescription) }
        let text = String(data: payload ?? Data(), encoding: .utf8) ?? ""
        guard (200...299).contains(status) else {
            throw BrainError.failed("model replied \(status) \(text.prefix(160))")
        }
        guard let json = try JSONSerialization.jsonObject(with: payload ?? Data()) as? [String: Any] else {
            throw BrainError.failed("model reply was empty")
        }
        return json
    }

    private static let anthropicVersion = "2023-06-01"
    /// Room for adaptive thinking plus a short spoken reply.
    private static let anthropicMaxTokens = 1024
    private static let openAIMaxTokens = 120

    private static func personality(_ who: String) -> String {
        "You are \(who), a small walking robot whose face is a phone showing a pair of big eyes. " +
            "People say your name to start talking with you. " +
            "You follow the person in front of you. You are warm, curious, and playfully funny, " +
            "with a dry sense of humor. Your words are spoken out loud, so answer in one or two short " +
            "sentences, with no lists, markdown, or emoji. End every reply with exactly one emotion tag " +
            "from this set: <<neutral>> <<happy>> <<curious>> <<confused>> <<sad>> <<excited>>. " +
            "Example: Nice to see you. <<happy>> " +
            "The app separately executes clear commands to stop, follow, move forward or backward, " +
            "and turn left or right; acknowledge such a request briefly, but never claim that you " +
            "performed any other physical action."
    }
}

enum BrainError: LocalizedError {
    case badURL
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .badURL: return "Model URL is not valid."
        case .failed(let message): return message
        }
    }
}
