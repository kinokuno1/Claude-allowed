import SwiftUI
import UIKit

/// Listening together: start a Jam, join one nearby or from a link, and the shared queue once you're in.
struct JamView: View {
    @ObservedObject private var jam = Jam.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Jam").font(.system(size: 26, weight: .heavy)).padding(.horizontal, 20).padding(.top, 22).padding(.bottom, 4)
            Group {
                if let s = jam.state { InJamView(state: s) } else { StartOrJoinView() }
            }
            .scrollContentBackground(.hidden)
        }
        .foregroundStyle(Theme.text)
        .background(Theme.c30)
        .preferredColorScheme(.dark)
        .tint(Theme.accentLight)
        .overlay { JoinPrompt() }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(Theme.c30)
        .presentationCornerRadius(24)
    }
}

// MARK: not in a Jam

private struct StartOrJoinView: View {
    @ObservedObject private var jam = Jam.shared
    @ObservedObject private var home = HomeServer.shared
    @State private var you = Jam.shared.myName
    @State private var name = ""
    @State private var withPassword = false
    @State private var password = ""
    @State private var starting = false
    @State private var problem: String?
    @State private var scanning = true
    @State private var found: [JamFound] = []

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Listen together").font(.system(size: 30, weight: .heavy))
                    Text("Everyone adds songs to one queue. The music plays on the host's phone and its speaker, and anyone can play along on their own iPhone, in sync.")
                        .font(.subheadline).foregroundStyle(Theme.muted)
                }
                .listRowBackground(Color.clear)
            }

            Section {
                if found.isEmpty {
                    HStack(spacing: 12) {
                        if scanning { Radar(); Text("Looking for Jams nearby…").foregroundStyle(Theme.muted) }
                        else { Text("No Jam found nearby. Turn on Bluetooth, or ask the host for the link.").foregroundStyle(Theme.muted) }
                    }
                }
                ForEach(found) { f in
                    Button { jam.pending = f } label: {
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 12).fill(Theme.c30hi).frame(width: 44, height: 44)
                                .overlay(Image(systemName: "person.2.wave.2.fill").foregroundStyle(Theme.accentLight))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(f.info.name).font(.headline).lineLimit(1)
                                Text("\(f.info.host ?? "") · \(f.info.members ?? 0) listening" + (f.info.now?.title.map { " · ♪ \($0)" } ?? ""))
                                    .font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                            }
                            Spacer()
                            Image(systemName: f.info.locked == true ? "lock.fill" : "lock.open").foregroundStyle(Theme.muted)
                        }
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    if let l = Jam.parseLink(UIPasteboard.general.string ?? "") {
                        problem = nil
                        Task { await jam.openLink(server: l.server, id: l.id) }
                    } else {
                        problem = "Copy the Jam link first, then tap here."
                    }
                } label: { Label("Paste a Jam link", systemImage: "link") }
            } header: {
                HStack {
                    Text("Jams nearby")
                    Spacer()
                    if scanning { ProgressView().controlSize(.small) }
                    else { Button { scan() } label: { Image(systemName: "arrow.clockwise") } }
                }
            }
            .listRowBackground(Theme.c30hi)

            Section {
                TextField("Your name", text: $you)
                TextField("Jam name (e.g. \(you.isEmpty ? "My" : you)'s Jam)", text: $name)
                Picker("Who can join", selection: $withPassword) {
                    Text("Open to all").tag(false)
                    Text("Password").tag(true)
                }
                .pickerStyle(.segmented)
                if withPassword { SecureField("Password for guests", text: $password) }
            } header: { Text("Start a Jam") } footer: {
                if home.address.isEmpty {
                    Text("A Jam runs on your Tunio home server: set it up in Settings first. You can still join other people's Jams.")
                } else {
                    Text("Runs on your home server. What's playing now becomes the start of the queue. Guests on your Wi-Fi see it here; send the link to anyone else.")
                }
            }
            .listRowBackground(Theme.c30hi)
            .animation(.easeInOut, value: withPassword)

            Section {
                if let problem { Label(problem, systemImage: "exclamationmark.triangle.fill").foregroundStyle(Color(red: 1, green: 0.79, blue: 0.79)) }
                Button { start() } label: {
                    HStack(spacing: 10) {
                        if starting { ProgressView().tint(Theme.c60) }
                        Text(starting ? "Starting…" : "Start the Jam").font(.headline)
                    }
                    .foregroundStyle(Theme.c60).frame(maxWidth: .infinity).frame(height: 50)
                    .background(Color.white.opacity(canStart ? 1 : 0.4), in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!canStart)
                .listRowBackground(Color.clear)
            }
        }
        .task { scan() }
    }

    private var canStart: Bool { !home.address.isEmpty && !starting && (!withPassword || !password.isEmpty) }

    private func scan() {
        scanning = true
        Task {
            found = await jam.nearby()
            scanning = false
        }
    }

    private func start() {
        starting = true; problem = nil
        jam.myName = you
        let title = name.trimmingCharacters(in: .whitespaces).isEmpty ? "\(you.isEmpty ? "My" : you)'s Jam" : name
        Task {
            do { try await jam.create(name: title, password: withPassword ? password : "") }
            catch { problem = error.localizedDescription }
            starting = false
        }
    }
}

/// Asks for your name (and the password if the Jam has one), then joins.
private struct JoinPrompt: View {
    @ObservedObject private var jam = Jam.shared
    @State private var you = Jam.shared.myName
    @State private var password = ""
    @State private var problem: String?
    @State private var busy = false

    var body: some View {
        Color.clear
            .alert(jam.pending?.info.name ?? "Jam", isPresented: Binding(get: { jam.pending != nil }, set: { if !$0 && !busy { jam.pending = nil } })) {
                TextField("Your name", text: $you)
                if jam.pending?.info.locked == true { SecureField("Jam password", text: $password) }
                Button("Cancel", role: .cancel) { jam.pending = nil; problem = nil }
                Button(busy ? "Joining…" : "Join") { join() }
            } message: {
                if let p = jam.pending {
                    Text((problem.map { $0 + "\n" } ?? "") + "Hosted by \(p.info.host ?? "") · \(p.info.members ?? 0) listening")
                }
            }
            .allowsHitTesting(false)
    }

    private func join() {
        guard let p = jam.pending else { return }
        busy = true
        jam.myName = you
        Task {
            do {
                try await jam.join(p, password: password)
                jam.pending = nil; problem = nil; password = ""
            } catch {
                problem = error.localizedDescription
                jam.pending = nil
                try? await Task.sleep(nanoseconds: 400_000_000)
                jam.pending = p // ask again, showing what went wrong
            }
            busy = false
        }
    }
}

// MARK: in a Jam

private struct InJamView: View {
    let state: JamState
    @ObservedObject private var jam = Jam.shared
    @State private var copied = false

    private var host: Bool { state.you?.host == true }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(state.name).font(.system(size: 30, weight: .heavy))
                    Text((host ? "You're the host" : "Hosted by \(state.host ?? "")") + " · \(state.people?.count ?? 0) listening"
                         + (state.locked == true ? " · password" : " · open to all")).font(.subheadline).foregroundStyle(Theme.muted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(state.people ?? []) { p in
                                VStack(spacing: 4) {
                                    Circle().fill(p.host == true ? Theme.accentLight : Theme.c30hi).frame(width: 44, height: 44)
                                        .overlay(Text(p.name.prefix(1).uppercased()).font(.headline).foregroundStyle(p.host == true ? Theme.c60 : Theme.text))
                                    Text(p.id == state.you?.id ? "You" : p.name).font(.caption2).foregroundStyle(Theme.muted).lineLimit(1).frame(width: 56)
                                }
                            }
                        }.padding(.top, 10)
                    }
                }
                .listRowBackground(Color.clear)
            }

            Section {
                HStack(spacing: 12) {
                    Cover(url: state.current?.thumbnail, size: 64, corner: 8)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(state.current?.title ?? "Nothing playing").font(.headline).lineLimit(2)
                        Text(state.current.map { "\($0.artist ?? "") · added by \($0.addedBy ?? "")" } ?? "Add a song and it starts playing")
                            .font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                    }
                }
                HStack(spacing: 28) {
                    Spacer()
                    Button { jam.previous() } label: { Image(systemName: "backward.fill").font(.title2) }
                    Button { jam.setPlaying(!state.playing) } label: {
                        Image(systemName: state.playing ? "pause.fill" : "play.fill").font(.title).foregroundStyle(Theme.c60)
                            .contentTransition(.symbolEffect(.replace))
                            .frame(width: 60, height: 60).background(Color.white, in: Circle())
                    }
                    Button { jam.next() } label: { Image(systemName: "forward.fill").font(.title2) }
                    Spacer()
                }
                .buttonStyle(.plain)
                .padding(.vertical, 6)
            }
            .listRowBackground(Theme.c30hi)

            // like a "This phone" device card
            Section("Where it plays") {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(host ? "This phone" : "\(state.host ?? "The host")'s phone").font(.system(size: 24, weight: .heavy))
                        Text(state.current.map { "\($0.title) – \($0.artist ?? "")" } ?? "Nothing playing")
                            .font(.subheadline).foregroundStyle(Theme.accentLight).lineLimit(1)
                        Text(host ? "And the speaker or Bluetooth device it's connected to" : "Its speaker or Bluetooth device")
                            .font(.footnote).foregroundStyle(Theme.muted)
                    }
                    Spacer()
                    Image(systemName: "iphone").font(.system(size: 34)).foregroundStyle(Theme.accentLight)
                }
                .padding(.vertical, 6)
                if !host {
                    Toggle(isOn: $jam.playAlong) {
                        VStack(alignment: .leading) {
                            Text("Play on this iPhone too")
                            Text("In sync, for your own headphones or speaker.").font(.footnote).foregroundStyle(Theme.muted)
                        }
                    }
                    .tint(Color(red: 0.2, green: 0.78, blue: 0.35))
                }
            }
            .listRowBackground(Theme.c30hi)

            Section {
                Text(jam.link).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                HStack(spacing: 10) {
                    Button {
                        UIPasteboard.general.string = jam.link
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                    } label: {
                        Label(copied ? "Copied" : "Copy link", systemImage: copied ? "checkmark" : "doc.on.doc")
                            .frame(maxWidth: .infinity).frame(height: 44).background(Theme.c30hi, in: Capsule())
                    }
                    ShareLink(item: jam.link, message: Text("Join my Jam “\(state.name)” on Tunio" + (state.locked == true ? " (ask me for the password)" : ""))) {
                        Label("Share", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity).frame(height: 44).background(Theme.c30hi, in: Capsule())
                    }
                }
                .buttonStyle(.plain)
                .font(.subheadline.weight(.semibold))
            } header: { Text("Invite") } footer: {
                Text("People nearby find the Jam with Tunio's Jam button (Bluetooth, or the same Wi-Fi). The link works from anywhere your home server can be reached.")
            }
            .listRowBackground(Theme.c30hi)

            Section {
                if state.upNext.isEmpty {
                    Text("Nothing queued. Tap any song in Search or your Library to add it to the Jam.").foregroundStyle(Theme.muted)
                }
                ForEach(state.upNext) { t in
                    HStack(spacing: 12) {
                        Cover(url: t.thumbnail, size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).lineLimit(1)
                            Text("\(t.artist ?? "") · \(t.addedBy ?? "")").font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { jam.jump(t.qid) }
                }
                .onDelete { idx in idx.map { state.upNext[$0].qid }.forEach(jam.remove) }
                .onMove { from, to in
                    guard let f = from.first else { return }
                    jam.move(state.upNext[f].qid, to: state.index + 1 + (to > f ? to - 1 : to))
                }
            } header: { Text("Up next") }
            .listRowBackground(Theme.c30hi)
            .environment(\.editMode, .constant(.active))

            Section {
                Button(role: .destructive) { jam.leave() } label: {
                    Text(host ? "End the Jam for everyone" : "Leave the Jam").frame(maxWidth: .infinity)
                }
            }
            .listRowBackground(Theme.c30hi)
        }
    }
}

/// The bar above the tabs while in a Jam whose music plays on the host's phone.
struct JamBar: View {
    @ObservedObject private var jam = Jam.shared

    var body: some View {
        if let s = jam.state, !jam.playsHere {
            Button { jam.showSheet = true } label: {
                HStack(spacing: 10) {
                    Cover(url: s.current?.thumbnail, size: 44, corner: 8)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(s.current?.title ?? "Nothing playing yet").font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text("Jam · \(s.name) · on \(s.host ?? "the host")'s phone").font(.caption).foregroundStyle(Theme.accentLight).lineLimit(1)
                    }
                    Spacer()
                    Button { jam.setPlaying(!s.playing) } label: {
                        Image(systemName: s.playing ? "pause.fill" : "play.fill").font(.title3).frame(width: 40, height: 40)
                            .contentTransition(.symbolEffect(.replace))
                    }
                }
                .padding(8)
                .background(Theme.c30hi, in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.plain)
        }
    }
}

/// The player's Jam button: lit with a count while in a Jam.
struct JamButton: View {
    @ObservedObject private var jam = Jam.shared
    @State private var open = false

    var body: some View {
        Button { open = true } label: {
            Image(systemName: "person.2.wave.2.fill").font(.title3)
                .foregroundStyle(jam.active ? Theme.accentLight : Theme.text)
                .overlay(alignment: .topTrailing) {
                    if let n = jam.state?.people?.count {
                        Text("\(n)").font(.system(size: 10, weight: .bold)).foregroundStyle(Theme.c60)
                            .frame(width: 16, height: 16).background(Theme.accentLight, in: Circle()).offset(x: 10, y: -8)
                    }
                }
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $open) { JamView() }
    }
}

/// Rings spreading out while looking for Jams nearby.
private struct Radar: View {
    var body: some View {
        TimelineView(.animation) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.6) / 1.6
            ZStack {
                ForEach(0..<2) { k in
                    let q = (t + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
                    Circle().fill(Theme.accentLight.opacity(0.5)).scaleEffect(0.3 + q).opacity(1 - q)
                }
                Image(systemName: "person.2.fill").font(.system(size: 10))
            }
            .frame(width: 28, height: 28)
        }
    }
}
