import AVFoundation
import Vision

protocol CameraSink: AnyObject {
    func cameraDidMeasure(_ scene: Scene, corners: [CGPoint], imageSize: CGSize, hfov: Double)
    func cameraFailed(_ message: String)
}

/// Back camera. Finds the printed QR whose payload is `jiadroid:person`.
final class CameraSession: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    weak var sink: CameraSink?
    var markerWidthMeters: () -> Float = { markerWidthMM / 1000 }

    private let queue = DispatchQueue(label: "dev.jiadroid.camera")
    private var hfov = 70.0 * Double.pi / 180.0
    private var lastScan = Date.distantPast
    private var running = false

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            queue.async { self.configureAndRun() }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted {
                    self.queue.async { self.configureAndRun() }
                } else {
                    DispatchQueue.main.async { self.sink?.cameraFailed("Camera permission is required to see the marker.") }
                }
            }
        default:
            DispatchQueue.main.async { self.sink?.cameraFailed("Camera permission is required to see the marker.") }
        }
    }

    func stop() {
        queue.async {
            if self.session.isRunning { self.session.stopRunning() }
            self.running = false
        }
    }

    private func configureAndRun() {
        if running {
            if !session.isRunning { session.startRunning() }
            return
        }
        session.beginConfiguration()
        session.sessionPreset = session.canSetSessionPreset(.hd1280x720) ? .hd1280x720 : .high
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.sink?.cameraFailed("This phone has no back camera.") }
            return
        }
        session.addInput(input)
        readFov(device)
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            DispatchQueue.main.async { self.sink?.cameraFailed("This phone has no back camera.") }
            return
        }
        session.addOutput(output)
        rotate(output.connection(with: .video))
        session.commitConfiguration()
        session.startRunning()
        running = true
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = Date()
        if now.timeIntervalSince(lastScan) < 0.08 { return }
        lastScan = now
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(cvPixelBuffer: pixel, orientation: .up, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return
        }
        guard let codes = request.results, let hit = codes.first(where: { $0.payloadStringValue == markerPayload }) else { return }
        let imageWidth = CVPixelBufferGetWidth(pixel)
        let imageHeight = CVPixelBufferGetHeight(pixel)
        guard imageWidth > 0, imageHeight > 0 else { return }
        let corners = [
            pixelPoint(hit.topLeft, imageWidth, imageHeight),
            pixelPoint(hit.topRight, imageWidth, imageHeight),
            pixelPoint(hit.bottomRight, imageWidth, imageHeight),
            pixelPoint(hit.bottomLeft, imageWidth, imageHeight),
        ]
        let centerX = Float(corners.map(\.x).reduce(0, +) / 4)
        let top = hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y)
        let bottom = hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y)
        let measured = measure(
            centerX: centerX,
            widthPx: Float(top + bottom) / 2,
            imageWidth: imageWidth,
            hfovRad: hfov,
            markerWidthM: markerWidthMeters()
        )
        guard measured.visible else { return }
        let size = CGSize(width: imageWidth, height: imageHeight)
        let fov = hfov
        DispatchQueue.main.async { [weak self] in
            self?.sink?.cameraDidMeasure(measured, corners: corners, imageSize: size, hfov: fov)
        }
    }

    private func pixelPoint(_ point: CGPoint, _ width: Int, _ height: Int) -> CGPoint {
        CGPoint(x: point.x * CGFloat(width), y: (1 - point.y) * CGFloat(height))
    }

    private func readFov(_ device: AVCaptureDevice) {
        let landscape = Double(device.activeFormat.videoFieldOfView) * Double.pi / 180
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        if dims.width == 0 || dims.height == 0 || landscape < 30 * Double.pi / 180 || landscape > 120 * Double.pi / 180 {
            hfov = 70 * Double.pi / 180
            return
        }
        let aspect = Double(dims.width) / Double(dims.height)
        hfov = 2 * atan(tan(landscape / 2) / aspect)
    }

    private func rotate(_ connection: AVCaptureConnection?) {
        guard let connection else { return }
        if connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
        if connection.isVideoMirroringSupported {
            connection.isVideoMirrored = false
        }
    }
}
