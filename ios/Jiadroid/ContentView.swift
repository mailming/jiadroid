import AVFoundation
import SwiftUI
import UniformTypeIdentifiers
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
                EyesScreen(sim: session.sim, status: session.status, voice: session.voice) {
                    setDebug(true)
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

    private func setDebug(_ show: Bool) {
        showDebug = show
        // Keep Debug mode laid out behind the eyes. The preview needs its
        // surface even when the user only sees the normal eyes screen.
        session.reopenMic()
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
                TalkSection(voice: session.voice, say: session.sayTyped)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            VStack(alignment: .leading, spacing: 8) {
                StatusSection(status: session.status)
                DuckSection(sim: session.sim)
                LinkSection(
                    link: session.link,
                    toggle: session.toggleLink,
                    toggleUsb: session.toggleUsb,
                    saveWifi: session.saveWifi,
                    flashFirmware: session.flashFirmware
                )
                BrainSection(
                    voice: session.voice,
                    use: { session.useBrain(save: true) },
                    saveName: session.saveRobotName
                )
                Button("Eyes") { setDebug(false) }
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
    @ObservedObject var voice: VoiceModel
    let openDebug: () -> Void

    var body: some View {
        ZStack {
            EyesFace(lookX: sim.lookX, lookY: sim.lookY, emotion: voice.emotion)
                .contentShape(Rectangle())
                .onTapGesture(perform: openDebug)
            VStack {
                HStack {
                    Spacer()
                    Button("Debug", action: openDebug)
                        .buttonStyle(.bordered)
                        .tint(Color.ink)
                        .padding(12)
                        .frame(minWidth: 140, minHeight: 56)
                }
                Spacer()
                Text(voice.line)
                    .font(.body)
                    .foregroundStyle(Color.ink)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 28)
            }
        }
        .background(Color(red: 1, green: 0.957, blue: 0.824))
        .ignoresSafeArea()
        .accessibilityLabel("Animated robot eyes looking toward the person. \(status.command). \(voice.line)")
    }
}

private struct EyesFace: View {
    var lookX: Float
    var lookY: Float
    var emotion: Emotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            let blink = blinkAmount(at: timeline.date)
            let mood = EyeMood(emotion)
            let wobble: Float = emotion == .thinking
                ? 0.012 * Float(sin(timeline.date.timeIntervalSinceReferenceDate * 5.5))
                : 0
            Canvas { context, size in
                let face = mood.face
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(face))
                let eyeWidth = min(size.width * 0.34, size.height * 0.62)
                let eyeHeight = min(size.height * 0.58, eyeWidth * 0.92) * mood.heightScale
                let gap = size.width * 0.055
                let centerY = size.height * 0.5
                let leftX = size.width * 0.5 - gap / 2 - eyeWidth / 2
                let rightX = size.width * 0.5 + gap / 2 + eyeWidth / 2
                eye(context, leftX, centerY, eyeWidth, eyeHeight, blink: blink, mood: mood, wobble: wobble, face: face)
                eye(context, rightX, centerY, eyeWidth, eyeHeight, blink: blink, mood: mood, wobble: wobble, face: face)
                if mood.cheekAlpha > 0 {
                    let r = eyeWidth * 0.18
                    let cheek = Color(red: 1, green: 0.47, blue: 0.51).opacity(mood.cheekAlpha)
                    context.fill(Path(ellipseIn: CGRect(x: leftX - r, y: centerY + eyeHeight * 0.55 - r, width: r * 2, height: r * 2)), with: .color(cheek))
                    context.fill(Path(ellipseIn: CGRect(x: rightX - r, y: centerY + eyeHeight * 0.55 - r, width: r * 2, height: r * 2)), with: .color(cheek))
                }
            }
        }
        .ignoresSafeArea()
        .background(EyeMood(emotion).face)
    }

    private func blinkAmount(at date: Date) -> CGFloat {
        // ~3s cycle: quick close then open, then a pause.
        let t = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3.2)
        if t < 0.09 { return CGFloat(t / 0.09) }
        if t < 0.18 { return CGFloat(1 - (t - 0.09) / 0.09) }
        return 0
    }

    private func eye(
        _ context: GraphicsContext,
        _ cx: CGFloat,
        _ cy: CGFloat,
        _ eyeWidth: CGFloat,
        _ eyeHeight: CGFloat,
        blink: CGFloat,
        mood: EyeMood,
        wobble: Float,
        face: Color
    ) {
        let open = max(0.06, (1 - blink) * mood.lidOpen)
        let visibleHeight = eyeHeight * open
        let rect = CGRect(x: cx - eyeWidth / 2, y: cy - visibleHeight / 2, width: eyeWidth, height: visibleHeight)
        context.fill(Path(ellipseIn: rect), with: .color(.white))
        context.stroke(Path(ellipseIn: rect), with: .color(Color(red: 0.21, green: 0.18, blue: 0.15)), lineWidth: 8)

        let irisRadius = min(eyeWidth, eyeHeight) * 0.25 * mood.irisScale
        let pupil = CGPoint(
            x: cx + CGFloat(lookX + wobble) * eyeWidth * 0.23,
            y: cy + CGFloat(lookY) * eyeHeight * 0.2
        )
        context.fill(
            Path(ellipseIn: CGRect(x: pupil.x - irisRadius, y: pupil.y - irisRadius, width: irisRadius * 2, height: irisRadius * 2)),
            with: .color(mood.iris)
        )
        let pupilRadius = irisRadius * 0.52 * mood.pupilScale
        context.fill(
            Path(ellipseIn: CGRect(x: pupil.x - pupilRadius, y: pupil.y - pupilRadius, width: pupilRadius * 2, height: pupilRadius * 2)),
            with: .color(Color(red: 0.137, green: 0.122, blue: 0.114))
        )
        let shine = irisRadius * 0.15
        context.fill(
            Path(ellipseIn: CGRect(x: pupil.x - irisRadius * 0.2 - shine, y: pupil.y - irisRadius * 0.22 - shine, width: shine * 2, height: shine * 2)),
            with: .color(.white)
        )

        let lidCover = eyeHeight * (1 - open)
        if lidCover > 1 {
            context.fill(
                Path(CGRect(x: cx - eyeWidth / 2 - 2, y: cy - eyeHeight / 2 - 2, width: eyeWidth + 4, height: lidCover)),
                with: .color(face)
            )
        }

        let browY = cy - eyeHeight * 0.62 + mood.browLift * eyeHeight
        var brow = Path()
        brow.move(to: CGPoint(x: cx - eyeWidth * 0.42, y: browY + mood.browTilt * eyeHeight * 0.08))
        brow.addQuadCurve(
            to: CGPoint(x: cx + eyeWidth * 0.42, y: browY - mood.browTilt * eyeHeight * 0.08),
            control: CGPoint(x: cx, y: browY + mood.browTilt * eyeHeight * 0.12)
        )
        context.stroke(brow, with: .color(Color(red: 0.21, green: 0.18, blue: 0.15)), style: StrokeStyle(lineWidth: 7, lineCap: .round))
    }
}

private struct EyeMood {
    let face: Color
    let iris: Color
    let lidOpen: CGFloat
    let irisScale: CGFloat
    let pupilScale: CGFloat
    let heightScale: CGFloat
    let browLift: CGFloat
    let browTilt: CGFloat
    let cheekAlpha: Double

    init(_ emotion: Emotion) {
        switch emotion {
        case .neutral:
            face = Color(red: 1, green: 0.957, blue: 0.824)
            iris = Color(red: 0.416, green: 0.682, blue: 0.439)
            lidOpen = 1; irisScale = 1; pupilScale = 1; heightScale = 1
            browLift = 0; browTilt = 0; cheekAlpha = 0
        case .happy:
            face = Color(red: 1, green: 0.925, blue: 0.769)
            iris = Color(red: 0.353, green: 0.667, blue: 0.471)
            lidOpen = 0.78; irisScale = 1; pupilScale = 0.92; heightScale = 0.92
            browLift = 0.04; browTilt = -0.6; cheekAlpha = 0.22
        case .curious:
            face = Color(red: 1, green: 0.957, blue: 0.824)
            iris = Color(red: 0.392, green: 0.627, blue: 0.784)
            lidOpen = 1; irisScale = 1.12; pupilScale = 1.15; heightScale = 1.05
            browLift = 0.08; browTilt = 0.35; cheekAlpha = 0
        case .listening:
            face = Color(red: 1, green: 0.957, blue: 0.824)
            iris = Color(red: 0.416, green: 0.682, blue: 0.439)
            lidOpen = 1; irisScale = 1.08; pupilScale = 1.2; heightScale = 1
            browLift = 0.02; browTilt = 0; cheekAlpha = 0
        case .thinking:
            face = Color(red: 0.961, green: 0.941, blue: 0.902)
            iris = Color(red: 0.471, green: 0.588, blue: 0.51)
            lidOpen = 0.9; irisScale = 0.95; pupilScale = 0.85; heightScale = 0.95
            browLift = 0.1; browTilt = 0.5; cheekAlpha = 0
        case .confused:
            face = Color(red: 0.98, green: 0.949, blue: 0.863)
            iris = Color(red: 0.549, green: 0.588, blue: 0.392)
            lidOpen = 0.95; irisScale = 1.05; pupilScale = 1.05; heightScale = 1
            browLift = 0.12; browTilt = 0.9; cheekAlpha = 0
        case .sad:
            face = Color(red: 0.922, green: 0.933, blue: 0.961)
            iris = Color(red: 0.353, green: 0.51, blue: 0.588)
            lidOpen = 0.7; irisScale = 0.95; pupilScale = 1.1; heightScale = 0.9
            browLift = -0.06; browTilt = 0.7; cheekAlpha = 0
        case .excited:
            face = Color(red: 1, green: 0.902, blue: 0.745)
            iris = Color(red: 0.314, green: 0.745, blue: 0.431)
            lidOpen = 1; irisScale = 1.18; pupilScale = 1.25; heightScale = 1.08
            browLift = 0.1; browTilt = -0.4; cheekAlpha = 0.27
        }
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

private struct TalkSection: View {
    @ObservedObject var voice: VoiceModel
    let say: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(voice.brainStatus)
                .font(.caption)
                .foregroundStyle(Color.muted)
                .lineLimit(1)
            ScrollViewReader { proxy in
                ScrollView {
                    Text(voice.talkLog)
                        .font(.subheadline)
                        .foregroundStyle(Color.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .id("talk-bottom")
                }
                .frame(height: 120)
                .onChange(of: voice.talkLog) { _, _ in
                    proxy.scrollTo("talk-bottom", anchor: .bottom)
                }
            }
            HStack(spacing: 8) {
                TextField("Type what you would say", text: $voice.say)
                    .submitLabel(.send)
                    .onSubmit(say)
                    .fieldStyle()
                Button("Say", action: say)
                    .buttonStyle(.borderedProminent)
                    .tint(Color.ink)
            }
        }
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
    let toggleUsb: () -> Void
    let saveWifi: () -> Void
    let flashFirmware: (Data) -> Void
    @State private var pickingFirmware = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                TextField("Wi‑Fi host:port, or leave blank for USB", text: $link.host)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.done)
                    .onSubmit(toggle)
                    .fieldStyle()
                Button(link.connecting ? "…" : (link.connected ? "Disconnect" : "Connect"), action: toggle)
                    .disabled(link.connecting || link.wifiBusy)
                    .buttonStyle(.borderedProminent)
                    .tint(Color.ink)
                Button(link.connecting ? "…" : (link.connected ? "Disconnect" : "USB"), action: toggleUsb)
                    .disabled(link.connecting || link.wifiBusy)
                    .buttonStyle(.bordered)
                    .tint(Color.ink)
            }
            HStack(spacing: 8) {
                TextField("Robot Wi‑Fi SSID", text: $link.wifiSsid)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .fieldStyle()
                SecureField("Password", text: $link.wifiPassword)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .fieldStyle()
                Button("Save Wi‑Fi", action: saveWifi)
                    .disabled(!link.canConfigureWifi || link.wifiBusy)
                    .buttonStyle(.bordered)
                    .tint(Color.ink)
                Button("Flash .bin") { pickingFirmware = true }
                    .disabled(!link.canFlashFirmware || link.wifiBusy)
                    .buttonStyle(.bordered)
                    .tint(Color.ink)
                    .fileImporter(isPresented: $pickingFirmware, allowedContentTypes: [.data, .item], allowsMultipleSelection: false) { result in
                        guard case let .success(urls) = result, let url = urls.first else { return }
                        let access = url.startAccessingSecurityScopedResource()
                        defer { if access { url.stopAccessingSecurityScopedResource() } }
                        if let data = try? Data(contentsOf: url) {
                            flashFirmware(data)
                        } else {
                            link.linkText = "Can't read that firmware file."
                        }
                    }
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

private struct BrainSection: View {
    @ObservedObject var voice: VoiceModel
    let use: () -> Void
    let saveName: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text("Name")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                TextField("Lulu", text: $voice.robotName)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .onSubmit(saveName)
                    .frame(maxWidth: 120)
                    .fieldStyle()
                Text("Say the name once; it keeps listening for about 30 seconds")
                    .font(.caption)
                    .foregroundStyle(Color.muted)
            }
            HStack(spacing: 4) {
                TextField("Model URL, e.g. http://192.168.1.10:11434/v1", text: $voice.brainURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .font(.caption)
                    .fieldStyle()
                TextField("Model", text: $voice.brainModel)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.caption)
                    .frame(maxWidth: 90)
                    .fieldStyle()
                SecureField("API key", text: $voice.brainKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.caption)
                    .frame(maxWidth: 90)
                    .fieldStyle()
                Button("Use", action: use)
                    .buttonStyle(.bordered)
                    .tint(Color.ink)
            }
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
