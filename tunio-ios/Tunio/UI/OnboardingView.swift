import SwiftUI

/// First launch: a short welcome, then connecting the Tunio home server (optional).
struct OnboardingView: View {
    @ObservedObject private var home = HomeServer.shared
    @AppStorage("onboarded") private var onboarded = false
    @State private var step: Step = .welcome
    @State private var address = ""
    @State private var password = ""
    @State private var connecting = false
    @State private var problem: String?
    @State private var searching = false
    @State private var scanning = false

    enum Step { case welcome, server, password, done }

    var body: some View {
        ZStack {
            Theme.c60.ignoresSafeArea()
            LinearGradient(colors: [Theme.glow.opacity(0.7), .clear], startPoint: .top, endPoint: .center).ignoresSafeArea()
            Group {
                switch step {
                case .welcome: welcome
                case .server: server
                case .password: passwordStep
                case .done: done
                }
            }
            .padding(.horizontal, 24)
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .move(edge: .leading).combined(with: .opacity)))
        }
        .foregroundStyle(Theme.text)
        .animation(.spring(response: 0.45, dampingFraction: 0.88), value: step)
        .fullScreenCover(isPresented: $scanning) { QRScannerView() }
        // the QR code page (tunio://pair) can finish the setup at any step
        .onChange(of: home.status) { _, s in
            if case .online = s, step != .done, !connecting, home.isSetUp { step = .done }
        }
    }

    // MARK: steps

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()
            RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Theme.c30hi).frame(width: 84, height: 84)
                .overlay(Image(systemName: "music.note").font(.system(size: 40, weight: .bold)))
            Text("Tunio").font(.system(size: 44, weight: .heavy)).padding(.top, 22)
            Text("Your music from YouTube, SoundCloud and Spotify, in one place.").font(.title3).foregroundStyle(Theme.muted).padding(.top, 6)
            VStack(alignment: .leading, spacing: 18) {
                feature("magnifyingglass", "Search or paste a link", "Songs, playlists and albums from YouTube, SoundCloud and Spotify.")
                feature("arrow.down.circle", "Listen offline", "Download songs and whole playlists to your iPhone.")
                feature("quote.bubble", "Synced lyrics", "The words follow the song as it plays.")
            }.padding(.top, 34)
            Spacer()
            primary("Get started") { step = .server }
        }.padding(.bottom, 12)
    }

    private var server: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: 40)
            Image(systemName: "server.rack").font(.system(size: 34, weight: .semibold)).foregroundStyle(Theme.accentLight)
            Text("Got a Tunio home server?").font(.system(size: 30, weight: .heavy)).padding(.top, 14)
            Text("A Raspberry Pi with Tunio on it finds and keeps songs for your iPhone, so they start faster and TikTok links work. It's optional: Tunio also works on its own.")
                .foregroundStyle(Theme.muted).padding(.top, 8)
            VStack(spacing: 10) {
                option("wifi", "Find it on my Wi-Fi", "Your iPhone will ask to find devices on your network: tap Allow.") {
                    searching = true
                    home.discover()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 15) { searching = false }
                }
                if searching || !home.found.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        if home.found.isEmpty {
                            HStack(spacing: 10) { ProgressView(); Text("Looking for your server…").foregroundStyle(Theme.muted) }
                        }
                        ForEach(home.found, id: \.self) { a in
                            Button { address = a; problem = nil; step = .password } label: {
                                HStack {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.good)
                                    VStack(alignment: .leading) { Text("Tunio server").bold(); Text(a.replacingOccurrences(of: "http://", with: "")).font(.footnote).foregroundStyle(Theme.muted) }
                                    Spacer(); Image(systemName: "chevron.right").foregroundStyle(Theme.muted)
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Theme.c30, in: RoundedRectangle(cornerRadius: 14))
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
                option("qrcode.viewfinder", "Scan its QR code", "Opens the camera. Point it at the QR code the installer showed, or the one you printed.") { scanning = true }
                option("keyboard", "Type its address", "For example 192.168.1.20, or its Tailscale address.") { address = ""; problem = nil; step = .password }
            }
            .padding(.top, 26)
            .animation(.easeInOut, value: home.found)
            .animation(.easeInOut, value: searching)
            Spacer()
            Button("I don't have one") { home.enabled = home.isSetUp; step = .done }
                .font(.body.weight(.semibold)).foregroundStyle(Theme.muted).frame(maxWidth: .infinity).padding(.bottom, 8)
        }.padding(.bottom, 12)
    }

    private var passwordStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { step = .server } label: { Label("Back", systemImage: "chevron.left").font(.body.weight(.semibold)) }
                .foregroundStyle(Theme.accentLight).padding(.top, 16)
            Text("Connect your server").font(.system(size: 30, weight: .heavy)).padding(.top, 20)
            Text("The installer showed the password at the end. You can also find it on the server's page in your browser.")
                .foregroundStyle(Theme.muted).padding(.top, 8)
            VStack(spacing: 0) {
                TextField("Address, e.g. 192.168.1.20", text: $address)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL).padding(14)
                Divider().overlay(Color.white.opacity(0.1))
                SecureField("Password (xxxx-xxxx-xxxx)", text: $password).padding(14)
            }
            .background(Theme.c30, in: RoundedRectangle(cornerRadius: 14)).padding(.top, 24)
            if let problem {
                Label(problem, systemImage: "exclamationmark.triangle.fill").font(.subheadline)
                    .foregroundStyle(Color(red: 1, green: 0.79, blue: 0.79)).padding(.top, 12)
            }
            Spacer()
            primary(connecting ? "Connecting…" : "Connect", busy: connecting) { connect() }
                .disabled(address.isEmpty || password.isEmpty || connecting)
                .opacity(address.isEmpty || password.isEmpty ? 0.5 : 1)
        }.padding(.bottom, 12)
    }

    private var done: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: home.usable ? "checkmark.circle.fill" : "music.note.house.fill")
                .font(.system(size: 64)).foregroundStyle(home.usable ? Theme.good : Theme.accentLight)
                .symbolEffect(.bounce, value: step)
            Text("You're all set").font(.system(size: 32, weight: .heavy)).padding(.top, 20)
            Group {
                if case .online(let n) = home.status, home.isSetUp {
                    Text("Your home server is connected and keeps \(n) songs for you. When you're away from home, Tunio works on its own.")
                } else {
                    Text("Tunio works on its own. You can connect a home server any time in Settings.")
                }
            }.multilineTextAlignment(.center).foregroundStyle(Theme.muted).padding(.top, 10)
            Spacer()
            primary("Start listening") { withAnimation(.easeInOut(duration: 0.35)) { onboarded = true } }
        }.padding(.bottom, 12)
    }

    // MARK: pieces

    private func connect() {
        connecting = true; problem = nil
        home.configure(address: address, password: password)
        Task {
            let ok = await home.check(force: true)
            connecting = false
            if ok { step = .done; return }
            switch home.status {
            case .wrongPassword: problem = "That password isn't right."
            default: problem = "Can't reach the server. Check the address, and that your iPhone is on the same Wi-Fi."
            }
        }
    }

    private func feature(_ icon: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon).font(.title3).foregroundStyle(Theme.accentLight).frame(width: 30)
            VStack(alignment: .leading, spacing: 2) { Text(title).font(.headline); Text(text).font(.subheadline).foregroundStyle(Theme.muted) }
        }
    }

    private func option(_ icon: String, _ title: String, _ sub: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: icon).font(.title3).frame(width: 30)
                VStack(alignment: .leading, spacing: 2) { Text(title).font(.headline); Text(sub).font(.subheadline).foregroundStyle(Theme.muted) }
                Spacer(minLength: 0)
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Theme.c30, in: RoundedRectangle(cornerRadius: 16))
        }.buttonStyle(.plain)
    }

    private func primary(_ title: String, busy: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) { if busy { ProgressView().tint(Theme.c60) }; Text(title).font(.headline) }
                .foregroundStyle(Theme.c60).frame(maxWidth: .infinity).frame(height: 54).background(Color.white, in: Capsule())
        }.buttonStyle(.plain)
    }
}
