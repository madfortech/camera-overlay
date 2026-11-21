import Foundation
import AVFoundation
import Photos
import UIKit

enum CameraOverlayFunctions {
    class RecordVideo: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let script = parameters["script"] as? String ?? ""
            let id = parameters["id"] as? String
            let event = parameters["event"] as? String ?? "Native\\Mobile\\Events\\Camera\\VideoRecorded"
            let maxDuration = parameters["maxDuration"] as? Int ?? 0
            let countdown = max(0, parameters["countdown"] as? Int ?? 3)

            DispatchQueue.main.async {
                CameraOverlayPresenter.shared.present(script: script, id: id, event: event, maxDuration: maxDuration, countdown: countdown)
            }

            return ["status": "success"]
        }
    }

    class SaveToGallery: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let path = parameters["path"] as? String, !path.isEmpty else {
                return ["status": "error", "message": "Missing video path"]
            }
            guard FileManager.default.fileExists(atPath: path) else {
                return ["status": "error", "message": "Video file not found"]
            }

            let photoStatus = PHPhotoLibrary.authorizationStatus(for: .add)
            if photoStatus != .authorized && photoStatus != .limited {
                guard photoStatus == .notDetermined else {
                    return ["status": "error", "message": "Photo library access denied"]
                }
                let authSemaphore = DispatchSemaphore(value: 0)
                var granted = false
                PHPhotoLibrary.requestAuthorization(for: .add) { status in
                    granted = (status == .authorized || status == .limited)
                    authSemaphore.signal()
                }
                _ = authSemaphore.wait(timeout: .now() + 15)
                guard granted else {
                    return ["status": "error", "message": "Photo library access denied"]
                }
            }

            let url = URL(fileURLWithPath: path)
            var created = false
            var creationError: Error?
            let saveSemaphore = DispatchSemaphore(value: 0)

            PHPhotoLibrary.shared().performChanges({
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: .video, fileURL: url, options: nil)
            }) { success, error in
                created = success
                creationError = error
                saveSemaphore.signal()
            }
            _ = saveSemaphore.wait(timeout: .now() + 30)

            guard created else {
                return ["status": "error", "message": creationError?.localizedDescription ?? "Could not save video"]
            }
            return ["status": "success"]
        }
    }
}

final class CameraOverlayPresenter {
    static let shared = CameraOverlayPresenter()

    func present(script: String, id: String?, event: String, maxDuration: Int, countdown: Int) {
        guard let top = topViewController() else {
            dispatchCancel(id: id)
            return
        }

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            requestMicAndPresent(from: top, script: script, id: id, event: event, maxDuration: maxDuration, countdown: countdown)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted {
                        self.requestMicAndPresent(from: top, script: script, id: id, event: event, maxDuration: maxDuration, countdown: countdown)
                    } else {
                        self.dispatchCancel(id: id)
                    }
                }
            }
        default:
            dispatchCancel(id: id)
        }
    }

    private func requestMicAndPresent(from presenter: UIViewController, script: String, id: String?, event: String, maxDuration: Int, countdown: Int) {
        let show = {
            let vc = CameraOverlayRecorderViewController(script: script, id: id, event: event, maxDuration: maxDuration, countdown: countdown)
            vc.modalPresentationStyle = .fullScreen
            presenter.present(vc, animated: true)
        }

        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            show()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                DispatchQueue.main.async { show() }
            }
        default:
            show()
        }
    }

    private func topViewController(base: UIViewController? = UIApplication.shared.connectedScenes
        .compactMap { ($0 as? UIWindowScene)?.keyWindow }
        .first?.rootViewController) -> UIViewController? {
        if let nav = base as? UINavigationController {
            return topViewController(base: nav.visibleViewController)
        }
        if let tab = base as? UITabBarController, let selected = tab.selectedViewController {
            return topViewController(base: selected)
        }
        if let presented = base?.presentedViewController {
            return topViewController(base: presented)
        }
        return base
    }

    private func dispatchCancel(id: String?) {
        var payload: [String: Any?] = ["cancelled": true]
        if let id = id { payload["id"] = id }
        LaravelBridge.shared.send?("Native\\Mobile\\Events\\Camera\\VideoCancelled", payload)
    }
}

final class CameraOverlayRecorderViewController: UIViewController, AVCaptureFileOutputRecordingDelegate {
    private let script: String
    private let recordingId: String?
    private let event: String
    private let maxDuration: Int
    private let countdown: Int

    private let session = AVCaptureSession()
    private let movieOutput = AVCaptureMovieFileOutput()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var overlayLabel: UILabel?
    private var statusLabel: UILabel?
    private var stopButton: UIButton?
    private var chunks: [String] = []
    private var chunkIndex = 0
    private var scrollTimer: Timer?
    private var durationTimer: Timer?
    private var countdownTimer: Timer?
    private var elapsedTimer: Timer?
    private var recordingStartDate: Date?
    private var finished = false
    private var outputURL: URL?

    init(script: String, id: String?, event: String, maxDuration: Int, countdown: Int) {
        self.script = script
        self.recordingId = id
        self.event = event
        self.maxDuration = maxDuration
        self.countdown = countdown
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        chunks = Self.buildChunks(from: script)
        setupUI()
        setupSession()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.startRunning()
                DispatchQueue.main.async { self?.startCountdownThenRecording() }
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func setupUI() {
        let label = UILabel()
        label.textColor = .white
        label.font = UIFont.boldSystemFont(ofSize: 22)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = chunks.first ?? ""
        view.addSubview(label)
        overlayLabel = label

        let status = UILabel()
        status.text = "Recording..."
        status.textColor = .white
        status.font = UIFont.systemFont(ofSize: 14)
        status.textAlignment = .center
        status.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(status)
        statusLabel = status

        let button = UIButton(type: .system)
        button.setTitle("STOP", for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = UIFont.boldSystemFont(ofSize: 18)
        button.backgroundColor = UIColor(red: 0x0f/255.0, green: 0x76/255.0, blue: 0x6e/255.0, alpha: 0.85)
        button.layer.cornerRadius = 8
        button.contentEdgeInsets = UIEdgeInsets(top: 12, left: 28, bottom: 12, right: 28)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addTarget(self, action: #selector(stopTapped), for: .touchUpInside)
        view.addSubview(button)
        stopButton = button

        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),

            button.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            button.centerXAnchor.constraint(equalTo: view.centerXAnchor),

            status.bottomAnchor.constraint(equalTo: button.topAnchor, constant: -12),
            status.centerXAnchor.constraint(equalTo: view.centerXAnchor),
        ])
    }

    private func setupSession() {
        session.beginConfiguration()
        session.sessionPreset = .high

        if let videoDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let videoInput = try? AVCaptureDeviceInput(device: videoDevice),
           session.canAddInput(videoInput) {
            session.addInput(videoInput)
        }

        if let audioDevice = AVCaptureDevice.default(for: .audio),
           let audioInput = try? AVCaptureDeviceInput(device: audioDevice),
           session.canAddInput(audioInput) {
            session.addInput(audioInput)
        }

        if session.canAddOutput(movieOutput) {
            session.addOutput(movieOutput)
        }

        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer
    }

    private func startCountdownThenRecording() {
        stopButton?.isEnabled = false
        guard countdown > 0 else {
            statusLabel?.text = "Recording..."
            startRecording()
            return
        }

        var remaining = countdown
        statusLabel?.text = "Starting in \(remaining)"
        countdownTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] timer in
            guard let self = self, !self.finished else {
                timer.invalidate()
                return
            }
            remaining -= 1
            if remaining > 0 {
                self.statusLabel?.text = "Starting in \(remaining)"
            } else {
                timer.invalidate()
                self.countdownTimer = nil
                self.statusLabel?.text = "Recording..."
                self.startRecording()
            }
        }
    }

    private func startElapsedTimer() {
        recordingStartDate = Date()
        elapsedTimer?.invalidate()
        elapsedTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self, !self.finished, let started = self.recordingStartDate else { return }
            let seconds = Int(Date().timeIntervalSince(started))
            self.statusLabel?.text = String(format: "Recording %02d:%02d", seconds / 60, seconds % 60)
        }
    }

    private func stopElapsedTimer() {
        elapsedTimer?.invalidate()
        elapsedTimer = nil
    }

    private func startRecording() {
        guard !movieOutput.isRecording else { return }
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("Camera", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("overlay_\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
        outputURL = url
        movieOutput.startRecording(to: url, recordingDelegate: self)
        stopButton?.isEnabled = true
        startElapsedTimer()
        startScrolling()
        if maxDuration > 0 {
            durationTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(maxDuration), repeats: false) { [weak self] _ in
                self?.stopTapped()
            }
        }
    }

    private func startScrolling() {
        guard chunks.count > 1 else { return }
        scrollTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            guard let self = self, !self.finished else { return }
            if self.chunkIndex < self.chunks.count - 1 {
                self.chunkIndex += 1
                let current = self.chunks[self.chunkIndex]
                let next = self.chunks.indices.contains(self.chunkIndex + 1) ? "\n" + self.chunks[self.chunkIndex + 1] : ""
                self.overlayLabel?.text = current + next
            } else {
                self.scrollTimer?.invalidate()
            }
        }
    }

    @objc private func stopTapped() {
        guard !finished else { return }
        stopButton?.isEnabled = false
        statusLabel?.text = "Saving..."
        countdownTimer?.invalidate()
        scrollTimer?.invalidate()
        durationTimer?.invalidate()
        stopElapsedTimer()
        if movieOutput.isRecording {
            movieOutput.stopRecording()
        } else {
            finishCancel()
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        countdownTimer?.invalidate()
        scrollTimer?.invalidate()
        durationTimer?.invalidate()
        stopElapsedTimer()
        if finished { return }
        finished = true

        if let error = error {
            print("CameraOverlay recording error: \(error)")
            try? FileManager.default.removeItem(at: outputFileURL)
            dispatchCancelAndDismiss()
            return
        }

        var payload: [String: Any?] = [
            "path": outputFileURL.path,
            "mimeType": "video/mp4",
        ]
        if let id = recordingId { payload["id"] = id }
        LaravelBridge.shared.send?(event, payload)
        dismiss(animated: true)
        if session.isRunning { session.stopRunning() }
    }

    private func finishCancel() {
        if finished { return }
        finished = true
        if let url = outputURL { try? FileManager.default.removeItem(at: url) }
        dispatchCancelAndDismiss()
    }

    private func dispatchCancelAndDismiss() {
        var payload: [String: Any?] = ["cancelled": true]
        if let id = recordingId { payload["id"] = id }
        LaravelBridge.shared.send?("Native\\Mobile\\Events\\Camera\\VideoCancelled", payload)
        if session.isRunning { session.stopRunning() }
        dismiss(animated: true)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        countdownTimer?.invalidate()
        scrollTimer?.invalidate()
        durationTimer?.invalidate()
        stopElapsedTimer()
    }

    private static func buildChunks(from script: String) -> [String] {
        let words = script.split { $0.isWhitespace || $0.isNewline }.map(String.init)
        if words.isEmpty { return [] }
        var result: [String] = []
        var i = 0
        while i < words.count {
            let end = min(i + 6, words.count)
            result.append(words[i..<end].joined(separator: " "))
            i = end
        }
        return result
    }
}
