import SwiftUI

struct SettingsView: View {
    @AppStorage("autoplay") private var autoplay = true
    @ObservedObject private var home = HomeServer.shared
    @ObservedObject private var downloads = Downloads.shared
    @State private var address = ""
    @State private var password = ""
    @State private var confirmClear = false
    @State private var scanning = false

    var body: some View {
        Form {
            Section {
                Toggle("Autoplay", isOn: $autoplay).tint(Color(red: 0.2, green: 0.78, blue: 0.35))
            } footer: { Text("When 3 or fewer songs are left, similar songs are added, and the next song is loaded in advance.") }

            Section {
                HStack {
                    Text("Status")
                    Spacer()
                    statusLabel
                }
                TextField("Address, e.g. 192.168.1.20", text: $address)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                SecureField("Password", text: $password)
                Button("Save and test") { home.configure(address: address, password: password) }
                    .disabled(address.isEmpty || password.isEmpty)
                Button { scanning = true } label: { Label("Scan its QR code", systemImage: "qrcode.viewfinder") }
                Button {
                    home.discover()
                } label: { Label("Find it on my Wi-Fi", systemImage: "wifi") }
                ForEach(home.found, id: \.self) { a in
                    Button { address = a } label: { Label(a, systemImage: "server.rack") }
                }
                if home.isSetUp {
                    Toggle("Use the home server", isOn: $home.enabled).tint(Color(red: 0.2, green: 0.78, blue: 0.35))
                    Button("Forget this server", role: .destructive) { home.forget(); address = ""; password = "" }
                }
            } header: { Text("Home server") } footer: {
                Text("A Tunio home server (e.g. a Raspberry Pi) finds and keeps songs for your phone, so they start faster and TikTok links work. Scan the QR code it shows after installing, or enter its address and password here. When it can't be reached, Tunio works on its own.")
            }

            Section {
                HStack { Text("Downloaded songs"); Spacer(); Text("\(downloads.offline.count)").foregroundStyle(Theme.muted) }
                HStack {
                    Text("Space used"); Spacer()
                    Text(ByteCountFormatter.string(fromByteCount: downloads.totalBytes, countStyle: .file)).foregroundStyle(Theme.muted)
                }
                Button("Delete all downloads", role: .destructive) { confirmClear = true }.disabled(downloads.offline.isEmpty)
            } header: { Text("Offline") } footer: { Text("Downloads are also in the Files app: On My iPhone → Tunio → Offline.") }

            Section("About") {
                Text("Tunio for iPhone. Music from YouTube, SoundCloud and Spotify links (Spotify songs play from YouTube). Lyrics from LRCLIB.")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.c60)
        .navigationTitle("Settings")
        .onAppear { address = home.address; password = home.password }
        .fullScreenCover(isPresented: $scanning) { QRScannerView() }
        .onChange(of: home.address) { _, a in address = a }
        .onChange(of: home.password) { _, p in password = p }
        .confirmationDialog("Delete all downloaded songs?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Delete all", role: .destructive) { downloads.removeAll() }
        }
    }

    @ViewBuilder private var statusLabel: some View {
        switch home.status {
        case .notSetUp: Text("Not set up").foregroundStyle(Theme.muted)
        case .checking: HStack(spacing: 6) { ProgressView(); Text("Checking…") }.foregroundStyle(Theme.muted)
        case .online(let n): Label("Online · \(n) songs kept", systemImage: "checkmark.circle.fill").foregroundStyle(Theme.good)
        case .offline: Label("Can't reach it", systemImage: "wifi.slash").foregroundStyle(Theme.muted)
        case .wrongPassword: Label("Wrong password", systemImage: "lock.trianglebadge.exclamationmark").foregroundStyle(Color(red: 1, green: 0.79, blue: 0.79))
        }
    }
}
