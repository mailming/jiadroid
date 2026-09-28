import AVFoundation
import SwiftUI
import UIKit

struct ContentView: View {
    @StateObject private var session = FollowSession()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showDebug = false

    var body: some View {
        ZStack {
            Color.paper.ignoresSafeArea()
            debugLayout
            if !showDebug {
                EyesScreen(sim: session.sim, status: session.status) {
                    showDebug = true
                }
            }
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

    private var debugLayout: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Follow Me")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Color.ink)
                Text("Point the front camera at a person or the mini person.")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                CameraSection(sim: session.sim, link: session.link, capture: session.camera)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            VStack(alignment: .leading, spacing: 8) {
                StatusSection(status: session.status)
                DuckSection(sim: session.sim)
                LinkSection(link: session.link, toggle: session.toggleLink)
                Button("Eyes") { showDebug = false }
                    .buttonStyle(.bordered)
                    .tint(Color.ink)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .padding(16)
        .opacity(showDebug ? 1 : 0)
        .allowsHitTesting(showDebug)
    }
}

private struct EyesScreen: View {
    @ObservedObject var sim: SimModel
    @ObservedObject var status: StatusModel
    let openDebug: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            EyesFace(lookX: sim.lookX, lookY: sim.lookY)
            Button("Debug", action: openDebug)
                .buttonStyle(.bordered)
                .tint(Color.ink)
                .padding(12)
        }
        .background(Color(red: 1, green: 0.957, blue: 0.824))
        .ignoresSafeArea()
        .accessibilityLabel("Animated robot eyes looking toward the person. \(status.command)")
    }
}

private struct EyesFace: View {
    var lookX: Float
    var lookY: Float

    var body: some View {
        Canvas { context, size in
            let eyeWidth = min(size.width * 0.34, size.height * 0.62)
            let eyeHeight = min(size.height * 0.58, eyeWidth * 0.92)
            let gap = size.width * 0.055
            let centerY = size.height * 0.5
            let leftX = size.width * 0.5 - gap / 2 - eyeWidth / 2
            let rightX = size.width * 0.5 + gap / 2 + eyeWidth / 2
            eye(context, leftX, centerY, eyeWidth, eyeHeight)
            eye(context, rightX, centerY, eyeWidth, eyeHeight)
        }
        .ignoresSafeArea()
    }

    private func eye(_ context: GraphicsContext, _ cx: CGFloat, _ cy: CGFloat, _ eyeWidth: CGFloat, _ eyeHeight: CGFloat) {
        let rect = CGRect(x: cx - eyeWidth / 2, y: cy - eyeHeight / 2, width: eyeWidth, height: eyeHeight)
        context.fill(Path(ellipseIn: rect), with: .color(.white))
        context.stroke(Path(ellipseIn: rect), with: .color(Color(red: 0.21, green: 0.18, blue: 0.15)), lineWidth: 8)
        let irisRadius = min(eyeWidth, eyeHeight) * 0.25
        let pupil = CGPoint(
            x: cx + CGFloat(lookX) * eyeWidth * 0.23,
            y: cy + CGFloat(lookY) * eyeHeight * 0.2
        )
        context.fill(Path(ellipseIn: CGRect(x: pupil.x - irisRadius, y: pupil.y - irisRadius, width: irisRadius * 2, height: irisRadius * 2)), with: .color(Color(red: 0.416, green: 0.682, blue: 0.439)))
        let pupilRadius = irisRadius * 0.52
        context.fill(Path(ellipseIn: CGRect(x: pupil.x - pupilRadius, y: pupil.y - pupilRadius, width: pupilRadius * 2, height: pupilRadius * 2)), with: .color(Color(red: 0.137, green: 0.122, blue: 0.114)))
        let shine = irisRadius * 0.15
        context.fill(
            Path(ellipseIn: CGRect(x: pupil.x - irisRadius * 0.2 - shine, y: pupil.y - irisRadius * 0.22 - shine, width: shine * 2, height: shine * 2)),
            with: .color(.white)
        )
    }
}

private struct CameraSection: View {
    @ObservedObject var sim: SimModel
    @ObservedObject var link: LinkModel
    let capture: CameraSession

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
                MarkerOverlay(corners: sim.corners, imageSize: sim.imageSize, label: sim.label)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
            HStack(spacing: 8) {
                TextField("Laptop address, optional", text: $link.host)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.done)
                    .onSubmit(toggle)
                    .fieldStyle()
                Button(link.connecting ? "Connecting…" : (link.connected ? "Disconnect" : "Connect"), action: toggle)
                    .disabled(link.connecting)
                    .buttonStyle(.borderedProminent)
                    .tint(Color.ink)
            }
            HStack(spacing: 8) {
                Text("QR code")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                TextField("120", text: $link.markerMM)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 64)
                    .fieldStyle()
                Text("mm")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                Text("Person height")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                TextField("1700", text: $link.personMM)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 72)
                    .fieldStyle()
                Text("mm")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
            }
            Text(link.linkText)
                .font(.caption)
                .foregroundStyle(Color.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: CameraSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session.session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.onAlign = { [weak view] in
            session.alignPreview(view?.previewLayer.connection)
        }
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.previewLayer.session = session.session
        let orientation = uiView.window?.windowScene?.interfaceOrientation ?? .landscapeRight
        session.align(to: orientation)
        session.alignPreview(uiView.previewLayer.connection)
    }
}

private final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    var onAlign: (() -> Void)?

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
            self?.onAlign?()
        }
    }
}

private struct MarkerOverlay: View {
    let corners: [CGPoint]
    let imageSize: CGSize
    let label: String

    var body: some View {
        GeometryReader { geo in
            let points = corners.map { map($0, geo.size) }
            Path { path in
                guard let first = points.first else { return }
                path.move(to: first)
                points.dropFirst().forEach { path.addLine(to: $0) }
                path.closeSubpath()
            }
            .stroke(Color.person, lineWidth: 3)
            if let top = points.min(by: { $0.y < $1.y }) {
                Text(label)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.person)
                    .position(x: top.x, y: top.y - 14)
            }
        }
        .allowsHitTesting(false)
    }

    private func map(_ point: CGPoint, _ viewSize: CGSize) -> CGPoint {
        guard imageSize.width > 0, imageSize.height > 0 else { return point }
        let scale = max(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        let dx = (viewSize.width - imageSize.width * scale) / 2
        let dy = (viewSize.height - imageSize.height * scale) / 2
        let mirroredX = imageSize.width - point.x
        return CGPoint(x: mirroredX * scale + dx, y: point.y * scale + dy)
    }
}

private extension View {
    func fieldStyle() -> some View {
        padding(10)
            .background(Color.white.opacity(0.72))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
