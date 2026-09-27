import AVFoundation
import SwiftUI
import UIKit

struct ContentView: View {
    @StateObject private var session = FollowSession()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Color.paper.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Follow Me")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(Color.ink)
                    Text("Point the back camera at the mini person.")
                        .font(.subheadline)
                        .foregroundStyle(Color.muted)
                }

                camera
                StatusSection(status: session.status)
                DuckSection(sim: session.sim)
                LinkSection(link: session.link, toggle: session.toggleLink)
            }
            .padding(16)
        }
        .onAppear { session.start() }
        .onDisappear { session.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                session.resume()
            } else {
                session.pause()
            }
        }
    }

    private var camera: some View {
        CameraSection(sim: session.sim, link: session.link, capture: session.camera.session)
    }
}

private struct CameraSection: View {
    @ObservedObject var sim: SimModel
    @ObservedObject var link: LinkModel
    let capture: AVCaptureSession

    var body: some View {
        ZStack {
            CameraPreview(session: capture)
            if let message = link.cameraMessage {
                Color.ink.opacity(0.88)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(Color.paper)
                    .multilineTextAlignment(.center)
                    .padding(16)
            } else {
                MarkerOverlay(corners: sim.corners, imageSize: sim.imageSize)
            }
        }
        .frame(height: 220)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct StatusSection: View {
    @ObservedObject var status: StatusModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(status.situation)
                .font(.subheadline)
                .foregroundStyle(Color.muted)
            Text(status.command)
                .font(.title.weight(.bold))
                .foregroundStyle(status.moving ? Color.go : Color.stop)
            Text(status.detail)
                .font(.caption.monospacedDigit())
                .foregroundStyle(Color.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct DuckSection: View {
    @ObservedObject var sim: SimModel

    var body: some View {
        DuckSimView(pose: sim.pose, scene: sim.scene, gait: sim.gait, hfov: sim.hfov, trail: sim.trail)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.paper)
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct LinkSection: View {
    @ObservedObject var link: LinkModel
    let toggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("Laptop address, optional", text: $link.host)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .submitLabel(.done)
                .onSubmit(toggle)
                .fieldStyle()
            HStack(spacing: 8) {
                Text("Printed code width")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                TextField("60", text: $link.markerMM)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 64)
                    .fieldStyle()
                Text("mm")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                Spacer()
                Button(link.connecting ? "Connecting…" : (link.connected ? "Disconnect" : "Connect"), action: toggle)
                    .disabled(link.connecting)
                    .buttonStyle(.borderedProminent)
                    .tint(Color.ink)
            }
            Text(link.linkText)
                .font(.caption)
                .foregroundStyle(Color.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.previewLayer.session = session
    }
}

private final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window != nil else { return }
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(alignPreview),
            name: .AVCaptureSessionDidStartRunning,
            object: previewLayer.session
        )
        alignPreview()
    }

    @objc private func alignPreview() {
        DispatchQueue.main.async { [weak self] in
            guard let connection = self?.previewLayer.connection, connection.isVideoRotationAngleSupported(90) else { return }
            connection.videoRotationAngle = 90
        }
    }
}

private struct MarkerOverlay: View {
    let corners: [CGPoint]
    let imageSize: CGSize

    var body: some View {
        GeometryReader { geo in
            Path { path in
                let points = corners.map { map($0, geo.size) }
                guard let first = points.first else { return }
                path.move(to: first)
                points.dropFirst().forEach { path.addLine(to: $0) }
                path.closeSubpath()
            }
            .stroke(Color.person, lineWidth: 3)
        }
        .allowsHitTesting(false)
    }

    private func map(_ point: CGPoint, _ viewSize: CGSize) -> CGPoint {
        guard imageSize.width > 0, imageSize.height > 0 else { return point }
        let scale = max(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        let dx = (viewSize.width - imageSize.width * scale) / 2
        let dy = (viewSize.height - imageSize.height * scale) / 2
        return CGPoint(x: point.x * scale + dx, y: point.y * scale + dy)
    }
}

private extension View {
    func fieldStyle() -> some View {
        padding(10)
            .background(Color.white.opacity(0.72))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
