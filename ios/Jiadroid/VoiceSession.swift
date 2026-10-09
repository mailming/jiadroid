import AVFoundation
import Foundation
import Speech

/// Listens with the phone microphone and speaks the reply. Speech is turned into
/// text on the phone by Apple's recognizer, so short talk works without a network
/// when on-device recognition is available. Listening pauses while the phone talks.
final class VoiceSession: NSObject, AVSpeechSynthesizerDelegate {
    /// When nil, replies come from the phone's own short list.
    var brain: Brain?
    var robotName: String = defaultRobotName

    private let seeing: () -> String
    private let onLine: (String) -> Void
    private let onTurn: (String, String) -> Void
    private let onHearing: (String) -> Void
    private let onEmotion: (Emotion) -> Void

    private let audioEngine = AVAudioEngine()
    private let synthesizer = AVSpeechSynthesizer()
    private let thinker = DispatchQueue(label: "dev.jiadroid.voice.think")
    private let micLock = NSLock()
    private var recognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var alive = false
    private var speaking = false
    private var speakGeneration = 0
    private var reportedDrop = false
    private var tapInstalled = false
    private var restartWork: DispatchWorkItem?
    private let attention = AttentionSession()

    init(
        seeing: @escaping () -> String,
        onLine: @escaping (String) -> Void,
        onTurn: @escaping (String, String) -> Void,
        onHearing: @escaping (String) -> Void,
        onEmotion: @escaping (Emotion) -> Void
    ) {
        self.seeing = seeing
        self.onLine = onLine
        self.onTurn = onTurn
        self.onHearing = onHearing
        self.onEmotion = onEmotion
        super.init()
        synthesizer.delegate = self
        recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    }

    func start() {
        if alive { return }
        alive = true
        requestAccessThenListen()
    }

    func stop() {
        alive = false
        speaking = false
        speakGeneration += 1
        attention.clear()
        restartWork?.cancel()
        restartWork = nil
        closeMic()
        synthesizer.stopSpeaking(at: .immediate)
    }

    /// Drop the current recording and open the microphone again. Debug mode can reset the mic.
    func reopen() {
        if !alive { return }
        reportedDrop = false
        closeMic()
        if !speaking { listen() }
    }

    func destroy() {
        stop()
        brain = nil
    }

    /// Answers [heard]. Mic lines need the robot's name once to open a short conversation window;
    /// later turns keep going until ~30s of silence. Typed Debug lines pass `requireName: false`.
    func answer(_ heard: String, requireName: Bool = true) {
        if speaking { return }
        if isNoise(heard) { return }
        let name = normalizeRobotName(robotName)
        let gate = attention.consider(heard: heard, name: name, requireName: requireName)
        guard gate.addressed else { return }
        onTurn("You", heard)
        if gate.utterance.isEmpty {
            speaking = true
            closeMic()
            speak(SpokenReply(say: "Yes?", emotion: .curious))
            return
        }
        speaking = true
        closeMic()
        let seen = seeing()
        let request = gate.utterance
        guard let brain else {
            speak(reply(heard: request, seeing: seen, name: name))
            return
        }
        onEmotion(.thinking)
        onLine("Thinking")
        thinker.async { [weak self] in
            guard let self else { return }
            let spoken: SpokenReply
            do {
                spoken = try brain.answer(heard: request, seeing: seen, name: name)
            } catch {
                DispatchQueue.main.async { self.onTurn("Model", "failed: \(error.localizedDescription)") }
                spoken = reply(heard: request, seeing: seen, name: name)
            }
            DispatchQueue.main.async { self.speak(spoken) }
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { [weak self] in self?.resume() }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { [weak self] in self?.resume() }
    }

    private func requestAccessThenListen() {
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            DispatchQueue.main.async {
                guard let self, self.alive else { return }
                guard status == .authorized else {
                    self.onLine("Speech recognition permission is required to listen.")
                    return
                }
                AVAudioApplication.requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        guard self.alive else { return }
                        if granted {
                            self.listen()
                        } else {
                            self.onLine("Microphone permission is required to listen.")
                        }
                    }
                }
            }
        }
    }

    private func listen() {
        if !alive || speaking { return }
        micLock.lock()
        defer { micLock.unlock() }
        if audioEngine.isRunning { return }
        guard let recognizer, recognizer.isAvailable else {
            onLine("Speech recognition is not available.")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            if recognizer.supportsOnDeviceRecognition {
                request.requiresOnDeviceRecognition = true
            }
            recognitionRequest = request

            let input = audioEngine.inputNode
            let format = input.outputFormat(forBus: 0)
            if tapInstalled {
                input.removeTap(onBus: 0)
                tapInstalled = false
            }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                self?.recognitionRequest?.append(buffer)
            }
            tapInstalled = true

            recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
                guard let self else { return }
                DispatchQueue.main.async {
                    self.handleRecognition(result: result, error: error)
                }
            }

            audioEngine.prepare()
            try audioEngine.start()
            if reportedDrop {
                reportedDrop = false
            }
            onEmotion(.listening)
            onLine("Listening")
            scheduleRestart()
        } catch {
            recognitionTask?.cancel()
            recognitionTask = nil
            recognitionRequest = nil
            if audioEngine.isRunning { audioEngine.stop() }
            if tapInstalled {
                audioEngine.inputNode.removeTap(onBus: 0)
                tapInstalled = false
            }
            noteDrop()
            scheduleRetry()
        }
    }

    private func handleRecognition(result: SFSpeechRecognitionResult?, error: Error?) {
        if !alive || speaking { return }
        if let result {
            let text = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                if result.isFinal {
                    answer(text)
                    return
                }
                onEmotion(.listening)
                onHearing(text)
            }
        }
        if error != nil {
            noteDrop()
            closeMic()
            if alive && !speaking { scheduleRetry() }
        }
    }

    private func speak(_ spoken: SpokenReply) {
        onEmotion(spoken.emotion)
        onTurn("Me", spoken.say)
        onLine(spoken.say)
        speakGeneration += 1
        let generation = speakGeneration
        let utterance = AVSpeechUtterance(string: spoken.say)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        synthesizer.speak(utterance)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.speakLimitSeconds) { [weak self] in
            guard let self, self.speaking, self.speakGeneration == generation else { return }
            self.synthesizer.stopSpeaking(at: .immediate)
            self.resume()
        }
    }

    private func resume() {
        speaking = false
        speakGeneration += 1
        if !alive { return }
        onEmotion(.listening)
        onLine("Listening")
        listen()
    }

    private func closeMic() {
        micLock.lock()
        defer { micLock.unlock() }
        restartWork?.cancel()
        restartWork = nil
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest = nil
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
    }

    private func noteDrop() {
        if reportedDrop { return }
        reportedDrop = true
        if alive { onLine("Microphone dropped. Listening again.") }
    }

    private func scheduleRetry() {
        restartWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.alive, !self.speaking else { return }
            self.listen()
        }
        restartWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    private func scheduleRestart() {
        restartWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.alive, !self.speaking else { return }
            self.closeMic()
            self.listen()
        }
        restartWork = work
        // Apple's recognition task is not meant to run forever. Restart while idle.
        DispatchQueue.main.asyncAfter(deadline: .now() + 50, execute: work)
    }

    private func isNoise(_ heard: String) -> Bool {
        let words = heard.lowercased().split(whereSeparator: \.isWhitespace).map(String.init).filter { !$0.isEmpty }
        return words.isEmpty || words.allSatisfy { Self.noise.contains($0) }
    }

    private static let speakLimitSeconds: TimeInterval = 8
    private static let noise: Set<String> = ["huh", "uh", "um", "ah", "hmm", "mm", "hm", "mhm", "oh", "the", "a", "an"]
}
