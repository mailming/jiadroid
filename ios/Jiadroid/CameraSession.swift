import AVFoundation
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

/// Front camera. The QR code wins. Otherwise a person's shoulders and hips are followed.
final class CameraSession: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    weak var sink: CameraSink?
    var markerWidthMeters: () -> Float = { markerWidthMM / 1000 }
    var personHeightMeters: () -> Float = { personHeightMM / 1000 }

    private let queue = DispatchQueue(label: "dev.jiadroid.camera")
    private var hfov = 70.0 * Double.pi / 180.0
    private var rotation: CGFloat = 0
    private var lastScan = Date.distantPast
    private var running = false
    private var device: AVCaptureDevice?

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
        rotation = rotationAngle(.landscapeRight)
        apply(rotation, to: output.connection(with: .video), mirror: false)
        readFov(device, rotation: rotation)
        session.commitConfiguration()
        session.startRunning()
        running = true
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = Date()
        if now.timeIntervalSince(lastScan) < 0.08 { return }
        lastScan = now
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let barcode = VNDetectBarcodesRequest()
        barcode.symbologies = [.qr]
        let pose = VNDetectHumanBodyPoseRequest()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixel, orientation: .up, options: [:])
        do {
            try handler.perform([barcode, pose])
        } catch {
            return
        }
        let imageWidth = CVPixelBufferGetWidth(pixel)
        let imageHeight = CVPixelBufferGetHeight(pixel)
        guard imageWidth > 0, imageHeight > 0 else { return }
        if let hit = barcode.results?.first(where: { $0.payloadStringValue == markerPayload }) {
            publishMarker(hit, imageWidth, imageHeight)
            return
        }
        publishPerson(pose.results?.first, imageWidth, imageHeight)
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
