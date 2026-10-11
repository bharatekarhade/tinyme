import AVFoundation
import Observation
import Speech

/// Hold-to-talk: turns speech into text on the phone while the mic is held.
@MainActor
@Observable
final class SpeechService {
    /// The words heard so far in the current hold. Keeps updating briefly after release, until the final text arrives.
    private(set) var transcript = ""
    private(set) var isListening = false
    /// Goes up by one each time a hold ends with its final text in `transcript`.
    private(set) var finishedCount = 0
    /// Something the user should know: permission needed, or voice not available.
    private(set) var notice: String?

    private let recorder = SpeechRecorder()
    /// True from press to release, so a start that was slow (permission prompt) doesn't record after the finger is gone.
    private var isHeld = false
    private var isStarting = false

    /// The mic was pressed. Asks for microphone and speech permission the first time.
    func press() {
        isHeld = true
        Task { await start() }
    }

    /// The mic was released. The final text follows in `transcript`, then `finishedCount` goes up.
    func release() {
        isHeld = false
        guard isListening else { return }
        isListening = false
        recorder.endAudio()
        // The recogniser normally answers within a moment; don't wait on it forever.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            self?.finish()
        }
    }

    private func start() async {
        guard !isListening, !isStarting else { return }
        isStarting = true
        defer { isStarting = false }
        notice = nil

        switch await Self.authorize() {
        case .ready:
            break
        case .justAsked:
            // The system prompts took the press. The next hold will record.
            notice = "All set. Hold the mic and talk."
            return
        case .denied:
            notice = "Allow Microphone and Speech Recognition for TinyMe in Settings to talk."
            return
        }
        guard isHeld else { return }

        transcript = ""
        do {
            try recorder.start(
                locale: .current,
                onText: { [weak self] text, isFinal in
                    Task { @MainActor in self?.heard(text, isFinal: isFinal) }
                },
                onEnd: { [weak self] in
                    Task { @MainActor in self?.finish() }
                }
            )
            isListening = true
        } catch SpeechRecorder.Failure.onDeviceUnavailable {
            notice = "Voice isn't available for your language on this phone yet."
        } catch {
            recorder.cancel()
            notice = "Couldn't start listening. Try again."
        }
    }

    private func heard(_ text: String, isFinal: Bool) {
        transcript = text
        if isFinal {
            finish()
        }
    }

    private func finish() {
        guard recorder.isActive else { return }
        recorder.cancel()
        isListening = false
        if !transcript.isEmpty {
            finishedCount += 1
        }
    }

    // MARK: - Permissions

    private enum Access {
        case ready, justAsked, denied
    }

    private static func authorize() async -> Access {
        let speech = SFSpeechRecognizer.authorizationStatus()
        let microphone = AVAudioApplication.shared.recordPermission
        if speech == .authorized, microphone == .granted {
            return .ready
        }
        if speech == .denied || speech == .restricted || microphone == .denied {
            return .denied
        }
        let microphoneGranted = microphone == .granted ? true : await AVAudioApplication.requestRecordPermission()
        let speechGranted = speech == .authorized ? true : await requestSpeechAuthorization() == .authorized
        return microphoneGranted && speechGranted ? .justAsked : .denied
    }

    /// Kept off the main actor: the system calls this handler on a background queue.
    nonisolated private static func requestSpeechAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status)
            }
        }
    }
}

/// The audio engine and recogniser. Not tied to the main actor: its callbacks arrive on audio and background queues.
private final class SpeechRecorder: @unchecked Sendable {
    enum Failure: Error {
        case onDeviceUnavailable
    }

    private let lock = NSLock()
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    var isActive: Bool {
        lock.withLock { task != nil }
    }

    func start(
        locale: Locale,
        onText: @escaping @Sendable (String, Bool) -> Void,
        onEnd: @escaping @Sendable () -> Void
    ) throws {
        guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable,
              recognizer.supportsOnDeviceRecognition else {
            throw Failure.onDeviceUnavailable
        }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true   // speech never leaves the phone
        request.addsPunctuation = true

        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            request.append(buffer)
        }
        engine.prepare()
        try engine.start()

        let task = recognizer.recognitionTask(with: request) { result, error in
            if let result {
                onText(result.bestTranscription.formattedString, result.isFinal)
            }
            if error != nil {
                onEnd()   // also how "nothing was said" arrives
            }
        }
        lock.withLock {
            self.request = request
            self.task = task
        }
    }

    /// The mic was released: stop recording and let the recogniser deliver its final text.
    func endAudio() {
        stopEngine()
        lock.withLock { request }?.endAudio()
    }

    /// Tear everything down.
    func cancel() {
        stopEngine()
        let (request, task) = lock.withLock {
            defer { self.request = nil; self.task = nil }
            return (self.request, self.task)
        }
        request?.endAudio()
        task?.cancel()
    }

    private func stopEngine() {
        if engine.isRunning {
            engine.stop()
        }
        engine.inputNode.removeTap(onBus: 0)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
