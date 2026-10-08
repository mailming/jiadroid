import Foundation

/// A language model reached over the OpenAI chat completions API. The same call
/// works for OpenAI, xAI Grok, and Ollama running on the laptop.
final class Brain {
    private let baseURL: String
    private let model: String
    private let key: String
    private var history: [[String: String]] = []
    private let lock = NSLock()

    init(baseURL: String, model: String, key: String) {
        self.baseURL = baseURL
        self.model = model
        self.key = key
    }

    /// Blocks on the network. Call it off the main thread.
    func answer(heard: String, seeing: String, name: String = defaultRobotName) throws -> String {
        let who = normalizeRobotName(name).capitalized
        var messages: [[String: String]] = [
            ["role": "system", "content": Self.personality(who) + "\nRight now your camera says: \(seeing)."],
        ]
        lock.lock()
        messages.append(contentsOf: history)
        lock.unlock()
        messages.append(["role": "user", "content": heard])

        let body: [String: Any] = [
            "model": model,
            "messages": messages,
            "max_tokens": 120,
            "temperature": 0.9,
        ]
        let root = baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: root + "/chat/completions") else {
            throw BrainError.badURL
        }
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
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
        guard
            let json = try JSONSerialization.jsonObject(with: payload ?? Data()) as? [String: Any],
            let choices = json["choices"] as? [[String: Any]],
            let message = choices.first?["message"] as? [String: Any],
            let content = message["content"] as? String
        else {
            throw BrainError.failed("model reply was empty")
        }
        let spoken = content.trimmingCharacters(in: .whitespacesAndNewlines)
        remember(["role": "user", "content": heard])
        remember(["role": "assistant", "content": spoken])
        return spoken
    }

    private func remember(_ turn: [String: String]) {
        lock.lock()
        history.append(turn)
        while history.count > Self.historyTurns {
            history.removeFirst()
        }
        lock.unlock()
    }

    private static let historyTurns = 12

    private static func personality(_ who: String) -> String {
        "You are \(who), a small walking robot whose face is a phone showing a pair of big eyes. " +
            "People get your attention by saying your name first. " +
            "You follow the person in front of you. You are warm, curious, and playfully funny, " +
            "with a dry sense of humor. Your words are spoken out loud, so answer in one or two short " +
            "sentences, with no lists, markdown, or emoji."
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
