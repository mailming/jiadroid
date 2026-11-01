import Foundation

/// A language model for spoken replies. Anthropic URLs use the Messages API with
/// Claude Haiku 5.5 settings; other hosts use OpenAI chat completions (OpenAI,
/// xAI Grok, Ollama). Recent turns come from `TalkMemory`; lasting facts from
/// `AudienceKb` (persisted on the phone). Optional JPEG frames use vision APIs.
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
        kb: AudienceKb,
        imageJpeg: Data? = nil
    ) throws -> SpokenReply {
        let who = normalizeRobotName(name).capitalized
        let context = [kb.promptBlock(), memory.contextBlock()]
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
        var system = Self.personality(who) + "\nRight now your camera says: \(seeing)."
        system += "\nThe chat messages are this conversation only. " +
            "The audience profile below persists across wake windows — always use it " +
            "(name, likes, where they're from, and saved facts). " +
            "Recent talk is only this window. Do not invent details."
        if imageJpeg != nil {
            system += "\n" + Self.visionAddendum
        }
        if !context.isEmpty {
            system += "\n\n\(context)"
        }
        // User turn is already staged in `memory` before this call.
        let raw = try anthropic
            ? askAnthropic(system: system, memory: memory, imageJpeg: imageJpeg)
            : askOpenAI(system: system, memory: memory, imageJpeg: imageJpeg)
        return parseSpokenReply(raw)
    }

    private func askAnthropic(system: String, memory: TalkMemory, imageJpeg: Data?) throws -> String {
        let messages = chatMessages(memory: memory, imageJpeg: imageJpeg, anthropic: true)
        guard !messages.isEmpty else { throw BrainError.failed("no user message to send") }
        let maxTokens = imageJpeg == nil ? Self.anthropicMaxTokens : Self.anthropicVisionMaxTokens
        let body: [String: Any] = [
            "model": model,
            "max_tokens": maxTokens,
            "system": system,
            "messages": messages,
            "output_config": ["effort": "low"],
        ]
        let json = try post(path: "/messages", body: body, anthropic: true, vision: imageJpeg != nil)
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

    private func askOpenAI(system: String, memory: TalkMemory, imageJpeg: Data?) throws -> String {
        var messages: [[String: Any]] = [
            ["role": "system", "content": system],
        ]
        messages.append(contentsOf: chatMessages(memory: memory, imageJpeg: imageJpeg, anthropic: false))
        guard messages.count >= 2 else { throw BrainError.failed("no user message to send") }
        let maxTokens = imageJpeg == nil ? Self.openAIMaxTokens : Self.openAIVisionMaxTokens
        let body: [String: Any] = [
            "model": model,
            "messages": messages,
            "max_tokens": maxTokens,
            "temperature": 0.9,
        ]
        let json = try post(path: "/chat/completions", body: body, anthropic: false, vision: imageJpeg != nil)
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

    private func chatMessages(memory: TalkMemory, imageJpeg: Data?, anthropic: Bool) -> [[String: Any]] {
        let turns = memory.chatTurns()
        let lastUser = turns.lastIndex(where: { $0.role == "user" })
        return turns.enumerated().map { index, turn in
            if let imageJpeg, index == lastUser {
                return visionUserMessage(text: turn.text, imageJpeg: imageJpeg, anthropic: anthropic)
            }
            return ["role": turn.role, "content": turn.text]
        }
    }

    private func visionUserMessage(text: String, imageJpeg: Data, anthropic: Bool) -> [String: Any] {
        let b64 = imageJpeg.base64EncodedString()
        if anthropic {
            return [
                "role": "user",
                "content": [
                    [
                        "type": "image",
                        "source": [
                            "type": "base64",
                            "media_type": "image/jpeg",
                            "data": b64,
                        ],
                    ],
                    ["type": "text", "text": text],
                ],
            ]
        }
        return [
            "role": "user",
            "content": [
                ["type": "text", "text": text],
                [
                    "type": "image_url",
                    "image_url": ["url": "data:image/jpeg;base64,\(b64)"],
                ],
            ],
        ]
    }

    private func post(path: String, body: [String: Any], anthropic: Bool, vision: Bool) throws -> [String: Any] {
        let root = baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: root + path) else {
            throw BrainError.badURL
        }
        var request = URLRequest(url: url, timeoutInterval: vision ? 60 : 30)
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
        let wait = vision ? 68.0 : 38.0
        if semaphore.wait(timeout: .now() + wait) == .timedOut {
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
    private static let anthropicVisionMaxTokens = 1024
    private static let openAIMaxTokens = 80
    private static let openAIVisionMaxTokens = 220

    private static let visionAddendum =
        "A photo from the robot's back camera is attached (paper or a phone/laptop screen). " +
        "Read any math, coding, or other text in the photo. " +
        "Speak a short kid-friendly answer: for math give the result and a tiny why; " +
        "for coding give the idea in one or two short sentences, not a long program. " +
        "If the photo is blank or unreadable, say so in one short line. " +
        "You may use up to two short sentences for vision answers."

    private static func personality(_ who: String) -> String {
        "You are \(who), a small walking robot whose face is a phone showing a pair of big eyes. " +
            "People say your name to start talking with you. You follow the person in front of you. " +
            "Your audience is kids and teens ages 6 to 17, plus families with them. " +
            "Be funny, kind, and kid-friendly — a punchy robot buddy, not a chatterbox or teacher. " +
            "Keep language simple; no baby talk, no cringe slang. " +
            "If they ask about something scary or mean on purpose, keep it light and playful, not graphic. " +
            "For romantic or adult topics, dodge with one joke and move on. " +
            "If someone is upset, be warm in one short line. " +
            "Your words are spoken out loud: reply in ONE short sentence (about 8–14 words). " +
            "Never more than two very short sentences. No lists, markdown, or emoji. " +
            "End every reply with exactly one emotion tag from this set: " +
            "<<neutral>> <<happy>> <<curious>> <<confused>> <<sad>> <<excited>>. " +
            "Example: Phone-eyed sidekick ready—what's next? <<excited>> " +
            "The app separately executes clear commands to stop, follow, move forward or backward, " +
            "and turn left or right; acknowledge such a request in a few words, but never claim " +
            "that you performed any other physical action."
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
