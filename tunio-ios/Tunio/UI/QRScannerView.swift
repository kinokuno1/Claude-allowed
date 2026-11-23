import SwiftUI
import AVFoundation
import UIKit

/// Scans the home server's QR code right inside Tunio: corners that breathe while looking, snap onto the code
/// when found, turn green, then a check mark, confetti and a haptic tap, and it connects.
struct QRScannerView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var home = HomeServer.shared

    enum Phase: Equatable { case looking, found, connecting, connected(Int), failed(String) }

    @State private var phase: Phase = .looking
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var area: CGSize = .zero
    @State private var frame: CGRect = .zero
    @State private var green = false
    @State private var flash = 0.0
    @State private var burst = 0.0
    @State private var tick = 0.0
    @State private var hint: String?
    @State private var torch = false
    @State private var bits = ConfettiBit.make(56)

    private let good = Theme.good

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if access == .authorized {
                    CameraPreview(torch: torch) { text, box in handle(text, box) }
                }
                Cutout(rect: frame).fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
                RoundedRectangle(cornerRadius: 14).fill(good.opacity(flash))
                    .frame(width: max(frame.width, 1), height: max(frame.height, 1)).position(x: frame.midX, y: frame.midY)
                if phase == .looking && access == .authorized { ScanLine(rect: frame) }
                corners
                ConfettiBurst(progress: burst, center: CGPoint(x: frame.midX, y: frame.midY), base: min(frame.width, frame.height) / 2,
                              reach: min(area.width, area.height) * 0.62, bits: bits, ring: good)
                    .allowsHitTesting(false)
                Circle().fill(good).frame(width: 76, height: 76)
                    .overlay(Image(systemName: "checkmark").font(.system(size: 36, weight: .heavy)).foregroundStyle(Color(red: 0.04, green: 0.23, blue: 0.17)))
                    .scaleEffect(tick).opacity(min(tick, 1)).position(x: frame.midX, y: frame.midY)
                chrome
            }
            .onAppear {
                area = geo.size
                frame = guide(in: geo.size)
                if access == .notDetermined {
                    AVCaptureDevice.requestAccess(for: .video) { _ in
                        DispatchQueue.main.async { access = AVCaptureDevice.authorizationStatus(for: .video) }
                    }
                }
            }
            .onChange(of: geo.size) { _, s in area = s; if phase == .looking { frame = guide(in: s) } }
        }
        .ignoresSafeArea()
        .foregroundStyle(Theme.text)
        .statusBarHidden(false)
        .onChange(of: hint) { _, h in
            guard h != nil else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { hint = nil }
        }
    }

    @ViewBuilder private var corners: some View {
        let shape = Corners(rect: frame).stroke(green ? good : .white, style: StrokeStyle(lineWidth: green ? 8 : 6, lineCap: .round, lineJoin: .round))
        let anchor = UnitPoint(x: area.width > 0 ? frame.midX / area.width : 0.5, y: area.height > 0 ? frame.midY / area.height : 0.5)
        if phase == .looking {
            shape.phaseAnimator([1.0, 1.035]) { v, s in v.scaleEffect(s, anchor: anchor) } animation: { _ in .easeInOut(duration: 1.3) }
        } else {
            shape
        }
    }

    private var chrome: some View {
        VStack {
            HStack {
                circleButton("xmark", active: false) { dismiss() }
                Spacer()
                if access == .authorized {
                    circleButton(torch ? "flashlight.on.fill" : "flashlight.off.fill", active: torch) { torch.toggle() }
                }
            }
            .padding(.horizontal, 18).padding(.top, 58)
            Spacer()
            VStack(spacing: 6) {
                switch phase {
                case .looking:
                    if access == .denied || access == .restricted {
                        Image(systemName: "camera.fill").font(.system(size: 34)).padding(.bottom, 6)
                        title("Tunio needs the camera")
                        caption("Only to read your home server's QR code. Nothing is recorded.")
                        pill("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }.padding(.top, 12)
                    } else {
                        title("Scan your server's QR code")
                        caption(hint ?? "Fit the code inside the corners. It's the one the installer showed, or the one you printed.")
                            .contentTransition(.opacity)
                            .animation(.easeInOut, value: hint)
                    }
                case .found:
                    title("Got it!")
                case .connecting:
                    HStack(spacing: 12) { ProgressView().tint(.white); title("Connecting to your server…") }
                case .connected(let n):
                    title("Connected!")
                    caption(n > 0 ? "Your server keeps \(n) songs for you." : "Your home server is ready.")
                case .failed(let why):
                    title("Almost")
                    caption(why)
                    HStack(spacing: 10) {
                        pill("Scan again") { reset() }
                        pill("Close", light: false) { dismiss() }
                    }.padding(.top, 12)
                }
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 24).padding(.bottom, 44)
        }
    }

    // MARK: what happens

    private func guide(in s: CGSize) -> CGRect {
        let side = min(s.width, s.height) * 0.68
        return CGRect(x: s.width / 2 - side / 2, y: s.height * 0.42 - side / 2, width: side, height: side)
    }

    private func handle(_ text: String, _ box: CGRect) {
        guard phase == .looking else { return }
        guard let pair = Pairing.parse(text) else {
            if hint == nil { hint = "That's not a Tunio QR code"; UIImpactFeedbackGenerator(style: .light).impactOccurred() }
            return
        }
        phase = .found
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        let g = guide(in: area)
        let target = box.width > 40 && box.height > 40 ? box.insetBy(dx: -14, dy: -14) : g.insetBy(dx: g.width * 0.12, dy: g.height * 0.12)
        withAnimation(.easeOut(duration: 0.2)) { green = true }
        // the corners snap onto the code
        withAnimation(.spring(response: 0.34, dampingFraction: 0.55)) { frame = target }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 260_000_000)
            withAnimation(.easeOut(duration: 0.08)) { flash = 0.55 } completion: {
                withAnimation(.easeOut(duration: 0.6)) { flash = 0 }
            }
            burst = 0
            withAnimation(.easeOut(duration: 1.3)) { burst = 1 }
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
            withAnimation(.spring(response: 0.38, dampingFraction: 0.45)) { tick = 1 }
            try? await Task.sleep(nanoseconds: 700_000_000)
            phase = .connecting
            home.configure(address: pair.address, password: pair.password)
            let ok = await home.check(force: true)
            switch home.status {
            case .online(let n) where ok:
                phase = .connected(n)
            case .wrongPassword:
                phase = .failed("The server says the password is wrong. Print a fresh QR code from the server.")
            default:
                let shown = pair.address.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
                phase = .failed("Found the code, but can't reach the server at \(shown). Is your iPhone on the same Wi-Fi?")
            }
            if case .connected = phase {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                try? await Task.sleep(nanoseconds: 1_300_000_000)
                dismiss()
            }
        }
    }

    private func reset() {
        phase = .looking
        tick = 0; burst = 0; flash = 0
        withAnimation(.easeOut(duration: 0.25)) { green = false }
        withAnimation(.spring(response: 0.45, dampingFraction: 0.75)) { frame = guide(in: area) }
    }

    // MARK: pieces

    private func title(_ s: String) -> some View { Text(s).font(.system(size: 22, weight: .bold)) }
    private func caption(_ s: String) -> some View { Text(s).font(.subheadline).foregroundStyle(Theme.muted) }

    private func pill(_ s: String, light: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(s).font(.headline).foregroundStyle(light ? Theme.c60 : Theme.text)
                .padding(.horizontal, 22).frame(height: 48)
                .background(light ? Color.white : Color.white.opacity(0.14), in: Capsule())
        }.buttonStyle(.plain)
    }

    private func circleButton(_ icon: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 18, weight: .semibold))
                .foregroundStyle(active ? Theme.c60 : Theme.text)
                .frame(width: 46, height: 46)
                .background(active ? Color.white : Color.black.opacity(0.45), in: Circle())
        }.buttonStyle(.plain)
    }
}

// MARK: shapes

private extension CGRect {
    var animatable: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, CGFloat>> {
        get { AnimatablePair(AnimatablePair(minX, minY), AnimatablePair(width, height)) }
        set { self = CGRect(x: newValue.first.first, y: newValue.first.second, width: newValue.second.first, height: newValue.second.second) }
    }
}

/// The whole screen minus a rounded square (fill with eoFill).
private struct Cutout: Shape {
    var rect: CGRect
    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, CGFloat>> {
        get { rect.animatable } set { rect.animatable = newValue }
    }
    func path(in r: CGRect) -> Path {
        var p = Path(r)
        p.addRoundedRect(in: rect, cornerSize: CGSize(width: 14, height: 14))
        return p
    }
}

/// Only the four corners of the square.
private struct Corners: Shape {
    var rect: CGRect
    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, CGFloat>> {
        get { rect.animatable } set { rect.animatable = newValue }
    }
    func path(in _: CGRect) -> Path {
        let r = rect, k: CGFloat = 14, len = min(r.width, r.height) * 0.2
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY + len)); p.addLine(to: CGPoint(x: r.minX, y: r.minY + k))
        p.addQuadCurve(to: CGPoint(x: r.minX + k, y: r.minY), control: CGPoint(x: r.minX, y: r.minY)); p.addLine(to: CGPoint(x: r.minX + len, y: r.minY))
        p.move(to: CGPoint(x: r.maxX - len, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX - k, y: r.minY))
        p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY + k), control: CGPoint(x: r.maxX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.minY + len))
        p.move(to: CGPoint(x: r.maxX, y: r.maxY - len)); p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - k))
        p.addQuadCurve(to: CGPoint(x: r.maxX - k, y: r.maxY), control: CGPoint(x: r.maxX, y: r.maxY)); p.addLine(to: CGPoint(x: r.maxX - len, y: r.maxY))
        p.move(to: CGPoint(x: r.minX + len, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX + k, y: r.maxY))
        p.addQuadCurve(to: CGPoint(x: r.minX, y: r.maxY - k), control: CGPoint(x: r.minX, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX, y: r.maxY - len))
        return p
    }
}

/// A soft line sweeping down inside the square while looking.
private struct ScanLine: View {
    let rect: CGRect
    var body: some View {
        TimelineView(.animation) { tl in
            Canvas { ctx, _ in
                let t = tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2.2) / 2.2
                let y = rect.minY + 18 + (rect.height - 36) * t
                var line = Path()
                line.move(to: CGPoint(x: rect.minX + 26, y: y)); line.addLine(to: CGPoint(x: rect.maxX - 26, y: y))
                ctx.stroke(line, with: .color(.white.opacity(0.55 * sin(t * .pi))), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        }
        .allowsHitTesting(false)
    }
}

struct ConfettiBit {
    let angle: Double, speed: Double, size: Double, spin: Double, drift: Double, round: Bool, color: Color
    static func make(_ n: Int) -> [ConfettiBit] {
        let colors: [Color] = [.white, Theme.accentLight, Theme.pinkLight, Theme.good,
                               Color(red: 1, green: 0.84, blue: 0.42), Color(red: 0.49, green: 0.77, blue: 1)]
        return (0..<n).map { i in
            ConfettiBit(angle: .random(in: 0..<(2 * .pi)), speed: .random(in: 0.55...1.3), size: .random(in: 5...12),
                        spin: .random(in: -540...540), drift: .random(in: -0.2...0.2), round: i % 3 == 0, color: colors[i % colors.count])
        }
    }
}

/// Rings and confetti bursting out of the code (progress 0 -> 1).
private struct ConfettiBurst: View, Animatable {
    var progress: Double
    let center: CGPoint, base: CGFloat, reach: CGFloat
    let bits: [ConfettiBit]
    let ring: Color
    var animatableData: Double { get { progress } set { progress = newValue } }

    var body: some View {
        Canvas { ctx, _ in
            let p = progress
            guard p > 0, p < 1 else { return }
            for k in 0..<2 {
                let q = min(max((p - Double(k) * 0.12) / 0.88, 0), 1)
                guard q > 0 else { continue }
                let r = CGFloat(Double(base) * (0.9 + q * 1.4))
                ctx.stroke(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
                           with: .color(ring.opacity((1 - q) * 0.7)), lineWidth: CGFloat((1 - q) * 10 + 1))
            }
            let travel = 1 - pow(1 - p, 3)
            let cx = Double(center.x), cy = Double(center.y), far = Double(reach)
            for b in bits {
                let d: Double = far * b.speed * travel
                let x: Double = cx + cos(b.angle) * d + b.drift * far * p
                let y: Double = cy + sin(b.angle) * d + p * p * far * 0.55
                let s: Double = b.size * (1 - p * 0.35)
                let a: Double = max(0, 1 - p * p)
                var c = ctx
                c.translateBy(x: CGFloat(x), y: CGFloat(y))
                c.rotate(by: .degrees(b.spin * p))
                let shape = b.round ? Path(ellipseIn: CGRect(x: -s / 2, y: -s / 2, width: s, height: s))
                    : Path(roundedRect: CGRect(x: -s / 2, y: -s * 0.3, width: s, height: s * 0.6), cornerRadius: 1.5)
                c.fill(shape, with: .color(b.color.opacity(a)))
            }
        }
    }
}

// MARK: camera

/// Forwards QR codes found by the camera (called on the main queue).
private final class MetadataForwarder: NSObject, AVCaptureMetadataOutputObjectsDelegate {
    var handler: ((AVMetadataMachineReadableCodeObject) -> Void)?
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        if let code = objects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first(where: { $0.stringValue != nil }) { handler?(code) }
    }
}

final class ScannerUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    private var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "tunio.scanner")
    private let forwarder = MetadataForwarder()
    private var device: AVCaptureDevice?
    var onCode: ((String, CGRect) -> Void)?

    func start() {
        preview.videoGravity = .resizeAspectFill
        preview.session = session
        forwarder.handler = { [weak self] code in
            guard let self, let text = code.stringValue else { return }
            let box = self.preview.transformedMetadataObject(for: code)?.bounds ?? .zero
            self.onCode?(text, box)
        }
        let session = self.session, forwarder = self.forwarder
        let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
        self.device = device
        queue.async {
            session.beginConfiguration()
            if let device, let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
                session.addInput(input)
                let out = AVCaptureMetadataOutput()
                if session.canAddOutput(out) {
                    session.addOutput(out)
                    out.setMetadataObjectsDelegate(forwarder, queue: .main)
                    if out.availableMetadataObjectTypes.contains(.qr) { out.metadataObjectTypes = [.qr] }
                }
            }
            session.commitConfiguration()
            session.startRunning()
        }
    }

    func stop() {
        let session = self.session
        queue.async { session.stopRunning() }
    }

    func setTorch(_ on: Bool) {
        guard let device, device.hasTorch, (device.torchMode == .on) != on else { return }
        try? device.lockForConfiguration()
        device.torchMode = on ? .on : .off
        device.unlockForConfiguration()
    }
}

private struct CameraPreview: UIViewRepresentable {
    var torch: Bool
    var onCode: (String, CGRect) -> Void

    func makeUIView(context: Context) -> ScannerUIView {
        let v = ScannerUIView()
        v.onCode = onCode
        v.start()
        return v
    }

    func updateUIView(_ v: ScannerUIView, context: Context) {
        v.onCode = onCode
        v.setTorch(torch)
    }

    static func dismantleUIView(_ v: ScannerUIView, coordinator: ()) { v.stop() }
}
