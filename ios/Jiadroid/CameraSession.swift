import AVFoundation
import CoreImage
import UIKit
import Vision

struct Sighting {
    var scene: Scene
    var corners: [CGPoint]
    var imageSize: CGSize
    var hfov: Double
    var subject: String
    var gazeY: Float
    var label: String
}

protocol CameraSink: AnyObject {
    func cameraDidMeasure(_ sighting: Sighting)
    func cameraFailed(_ message: String)
}

/// Front camera follows a person or QR marker. Vision “look at this” shots use the back camera.
final class CameraSession: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    weak var sink: CameraSink?
    var markerWidthMeters: () -> Float = { markerWidthMM / 1000 }
    var personHeightMeters: () -> Float = { personHeightMM / 1000 }

    private let queue = DispatchQueue(label: "dev.jiadroid.camera")
    private let ciContext = CIContext(options: nil)
    private var hfov = 70.0 * Double.pi / 180.0
    private var rotation: CGFloat = 0
    private var lastScan = Date.distantPast
    private var running = false
    private var device: AVCaptureDevice?
    private var frontInput: AVCaptureDeviceInput?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var capturingFromBack = false
    private var stillGeneration = 0
    private var pendingStill: (generation: Int, completion: (Data?) -> Void)?

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            queue.async { self.configureAndRun() }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted {
                    self.queue.async { self.configureAndRun() }
                } else {
                    DispatchQueue.main.async { self.sink?.cameraFailed("Camera permission is required to see who to follow.") }
                }
            }
        default:
            DispatchQueue.main.async { self.sink?.cameraFailed("Camera permission is required to see who to follow.") }
        }
    }

    func stop() {
        queue.async {
            self.finishStill(generation: self.stillGeneration, data: nil)
            if self.session.isRunning { self.session.stopRunning() }
            self.running = false
        }
    }

    func align(to interfaceOrientation: UIInterfaceOrientation) {
        let angle = rotationAngle(interfaceOrientation)
        queue.async {
            self.rotation = angle
            self.session.connections.forEach { self.apply(angle, to: $0, mirror: false) }
            if let device = self.device { self.readFov(device, rotation: angle) }
        }
    }

    func alignPreview(_ connection: AVCaptureConnection?) {
        apply(rotation, to: connection, mirror: true)
    }

    /// Captures one JPEG from the **back** camera (falls back to front if needed).
    /// Completion runs on the camera queue.
    func captureStill(completion: @escaping (Data?) -> Void) {
        queue.async {
            self.stillGeneration += 1
            let generation = self.stillGeneration
            self.pendingStill = (generation, completion)
            if self.switchToBackCamera() {
                self.capturingFromBack = true
            } else {
                // No back camera — take the next front frame instead.
                self.capturingFromBack = false
            }
            self.queue.asyncAfter(deadline: .now() + 2.5) { [weak self] in
                self?.finishStill(generation: generation, data: nil)
            }
        }
    }

    private func configureAndRun() {
        if running {
            if !session.isRunning { session.startRunning() }
            return
        }
        session.beginConfiguration()
        session.sessionPreset = session.canSetSessionPreset(.hd1280x720) ? .hd1280x720 : .high
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.sink?.cameraFailed("This phone has no front camera.") }
            return
        }
        self.device = device
        self.frontInput = input
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.sink?.cameraFailed("This phone has no front camera.") }
            return
        }
        session.addOutput(output)
        self.videoOutput = output
        rotation = rotationAngle(.landscapeRight)
        apply(rotation, to: output.connection(with: .video), mirror: false)
        readFov(device, rotation: rotation)
        session.commitConfiguration()
        session.startRunning()
        running = true
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if let pending = pendingStill {
            // Prefer a real JPEG from this frame; timeout clears a failed capture.
            if let jpeg = jpegData(from: pixel) {
                finishStill(generation: pending.generation, data: jpeg)
            }
            return
        }
        // Skip follow ML while the back camera is swapped in.
        if capturingFromBack { return }
        let now = Date()
        if now.timeIntervalSince(lastScan) < 0.08 { return }
        lastScan = now
        let imageWidth = CVPixelBufferGetWidth(pixel)
        let imageHeight = CVPixelBufferGetHeight(pixel)
        guard imageWidth > 0, imageHeight > 0 else { return }
        let handler = VNImageRequestHandler(cvPixelBuffer: pixel, orientation: .up, options: [:])
        // Match Android: QR first, then body pose only when the marker is missing.
        let barcode = VNDetectBarcodesRequest()
        barcode.symbologies = [.qr]
        do {
            try handler.perform([barcode])
        } catch {
            return
        }
        if let hit = barcode.results?.first(where: { $0.payloadStringValue == markerPayload }) {
            publishMarker(hit, imageWidth, imageHeight)
            return
        }
        let pose = VNDetectHumanBodyPoseRequest()
        do {
            try handler.perform([pose])
        } catch {
            return
        }
        publishPerson(pose.results?.first, imageWidth, imageHeight)
    }

    @discardableResult
    private func switchToBackCamera() -> Bool {
        guard let back = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let backInput = try? AVCaptureDeviceInput(device: back) else {
            return false
        }
        session.beginConfiguration()
        for input in session.inputs {
            session.removeInput(input)
        }
        guard session.canAddInput(backInput) else {
            if let front = frontInput, session.canAddInput(front) {
                session.addInput(front)
            }
            session.commitConfiguration()
            return false
        }
        session.addInput(backInput)
        device = back
        apply(rotation, to: videoOutput?.connection(with: .video), mirror: false)
        session.commitConfiguration()
        return true
    }

    private func restoreFrontCamera() {
        guard let front = frontInput else { return }
        session.beginConfiguration()
        for input in session.inputs {
            session.removeInput(input)
        }
        if session.canAddInput(front) {
            session.addInput(front)
            device = front.device
            apply(rotation, to: videoOutput?.connection(with: .video), mirror: false)
            readFov(front.device, rotation: rotation)
        }
        session.commitConfiguration()
        capturingFromBack = false
    }

    private func finishStill(generation: Int, data: Data?) {
        guard let pending = pendingStill, pending.generation == generation else { return }
        pendingStill = nil
        if capturingFromBack {
            restoreFrontCamera()
        }
        pending.completion(data)
    }

    private func jpegData(from pixel: CVPixelBuffer) -> Data? {
        let image = CIImage(cvPixelBuffer: pixel)
        guard let cg = ciContext.createCGImage(image, from: image.extent) else { return nil }
        return UIImage(cgImage: cg).jpegData(compressionQuality: 0.85)
    }

    private func publishMarker(_ hit: VNBarcodeObservation, _ imageWidth: Int, _ imageHeight: Int) {
        let corners = [
            pixelPoint(hit.topLeft, imageWidth, imageHeight),
            pixelPoint(hit.topRight, imageWidth, imageHeight),
            pixelPoint(hit.bottomRight, imageWidth, imageHeight),
            pixelPoint(hit.bottomLeft, imageWidth, imageHeight),
        ]
        let edges = [
            hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y),
            hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y),
            hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y),
            hypot(corners[0].x - corners[3].x, corners[0].y - corners[3].y),
        ]
        let centerX = Float(corners.map(\.x).reduce(0, +) / 4)
        let measured = measure(
            centerX: centerX,
            widthPx: Float(edges.min() ?? 0),
            imageWidth: imageWidth,
            hfovRad: hfov,
            markerWidthM: markerWidthMeters()
        )
        guard measured.visible else { return }
        let gaze = ((Float(corners.map(\.y).reduce(0, +) / 4) / Float(imageHeight)) - 0.5) * 2
        deliver(Sighting(
            scene: measured,
            corners: corners,
            imageSize: CGSize(width: imageWidth, height: imageHeight),
            hfov: hfov,
            subject: "Marker",
            gazeY: min(1, max(-1, gaze)),
            label: "mini person"
        ))
    }

    private func publishPerson(_ observation: VNHumanBodyPoseObservation?, _ imageWidth: Int, _ imageHeight: Int) {
        guard let observation,
              let leftShoulder = trusted(observation, .leftShoulder, imageWidth, imageHeight),
              let rightShoulder = trusted(observation, .rightShoulder, imageWidth, imageHeight) else { return }
        let leftHip = trusted(observation, .leftHip, imageWidth, imageHeight)
        let rightHip = trusted(observation, .rightHip, imageWidth, imageHeight)
        let hips = leftHip.flatMap { left in rightHip.map { (left, $0) } }
        let measured = measurePerson(
            shoulders: (landmark(leftShoulder), landmark(rightShoulder)),
            hips: hips.map { (landmark($0.0), landmark($0.1)) },
            imageWidth: imageWidth,
            hfovRad: hfov,
            personHeightM: personHeightMeters()
        )
        guard measured.visible else { return }
        let shoulderWidth = hypot(leftShoulder.x - rightShoulder.x, leftShoulder.y - rightShoulder.y)
        let lowLeft = leftHip ?? CGPoint(x: leftShoulder.x, y: leftShoulder.y + shoulderWidth)
        let lowRight = rightHip ?? CGPoint(x: rightShoulder.x, y: rightShoulder.y + shoulderWidth)
        let nose = trusted(observation, .nose, imageWidth, imageHeight)
        let gazeSource = nose?.y ?? (leftShoulder.y + rightShoulder.y) / 2
        let gaze = ((Float(gazeSource) / Float(imageHeight)) - 0.5) * 2
        deliver(Sighting(
            scene: measured,
            corners: [rightShoulder, leftShoulder, lowLeft, lowRight],
            imageSize: CGSize(width: imageWidth, height: imageHeight),
            hfov: hfov,
            subject: "Person",
            gazeY: min(1, max(-1, gaze)),
            label: "person"
        ))
    }

    private func trusted(_ observation: VNHumanBodyPoseObservation, _ joint: VNHumanBodyPoseObservation.JointName, _ width: Int, _ height: Int) -> CGPoint? {
        guard let point = try? observation.recognizedPoint(joint), point.confidence >= 0.7 else { return nil }
        return pixelPoint(point.location, width, height)
    }

    private func deliver(_ sighting: Sighting) {
        DispatchQueue.main.async { [weak self] in
            self?.sink?.cameraDidMeasure(sighting)
        }
    }

    private func landmark(_ point: CGPoint) -> LandmarkPoint {
        LandmarkPoint(x: Float(point.x), y: Float(point.y))
    }

    private func pixelPoint(_ point: CGPoint, _ width: Int, _ height: Int) -> CGPoint {
        CGPoint(x: point.x * CGFloat(width), y: (1 - point.y) * CGFloat(height))
    }

    private func readFov(_ device: AVCaptureDevice, rotation: CGFloat) {
        let landscape = Double(device.activeFormat.videoFieldOfView) * Double.pi / 180
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        if dims.width == 0 || dims.height == 0 || landscape < 30 * Double.pi / 180 || landscape > 120 * Double.pi / 180 {
            hfov = 70 * Double.pi / 180
            return
        }
        let portrait = rotation == 90 || rotation == 270
        if !portrait {
            hfov = landscape
            return
        }
        let aspect = Double(dims.width) / Double(dims.height)
        hfov = 2 * atan(tan(landscape / 2) / aspect)
    }

    private func rotationAngle(_ orientation: UIInterfaceOrientation) -> CGFloat {
        switch orientation {
        case .landscapeLeft: return 180
        case .portrait: return 90
        case .portraitUpsideDown: return 270
        default: return 0
        }
    }

    private func apply(_ angle: CGFloat, to connection: AVCaptureConnection?, mirror: Bool) {
        guard let connection else { return }
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = mirror
        }
    }
}
