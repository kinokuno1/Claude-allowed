import SwiftUI
import UIKit

/// The Jam sheet: comes up from the bottom at half height (drag the bar up for all of it, down to close it).
/// Most people only join, so it opens on the Jams nearby; starting one is behind the small + at the top.
struct JamView: View {
    @ObservedObject private var jam = Jam.shared
    @State private var creating = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(jam.state?.name ?? (creating ? "New Jam" : "Jam")).font(.system(size: 24, weight: .heavy)).lineLimit(1)
                Spacer()
                if jam.state == nil { PlusButton(open: creating) { creating.toggle() } }
            }
            .padding(.leading, 20).padding(.trailing, 8).padding(.top, 20).padding(.bottom, 6)
            Group {
                if let s = jam.state { InJamView(state: s) }
                else if creating { CreateJamView { creating = false } }
                else { NearbyView() }
            }
            .transition(.opacity)
            .animation(.easeInOut(duration: 0.2), value: creating)
            .animation(.easeInOut(duration: 0.2), value: jam.state?.id)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Theme.text)
        .background(Theme.c30)
        .preferredColorScheme(.dark)
        .tint(Theme.text)
        .overlay { PasswordPrompt() }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(Theme.c30)
        .presentationCornerRadius(24)
    }
}

/// The small + that opens "New Jam": it squeezes, spins and grows back as an ×, a bit like Face ID's icon.
private struct PlusButton: View {
    let open: Bool
    let action: () -> Void
    @State private var squeeze: CGFloat = 1

    var body: some View {
        Button {
            action()
            withAnimation(.easeIn(duration: 0.1)) { squeeze = 0.45 }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.45)) { squeeze = 1 }
            }
        } label: {
            ZStack {
                Capsule().frame(width: 22 * squeeze, height: 2.4 + (1 - squeeze) * 3)
                Capsule().frame(width: 2.4 + (1 - squeeze) * 3, height: 22 * squeeze)
            }
            .foregroundStyle(open ? Theme.text : Theme.muted)
            .rotationEffect(.degrees(open ? 135 : 0))
            .animation(.spring(response: 0.45, dampingFraction: 0.55), value: open)
            .frame(width: 44, height: 44)
        }
        .buttonStyle(.press)
        .accessibilityLabel(open ? "Close" : "Start a Jam")
    }
}

// MARK: Jams nearby (what the sheet opens on)

private struct NearbyView: View {
    @ObservedObject private var jam = Jam.shared
    @State private var scanning = true
    @State private var found: [JamFound] = []
    @State private var joining: String?

    var body: some View {
        Group {
            if !found.isEmpty {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(found) { f in
                            Button { tap(f) } label: {
                                HStack(spacing: 14) {
                                    RoundedRectangle(cornerRadius: 12).fill(Theme.c30hi).frame(width: 48, height: 48)
                                        .overlay(Image(systemName: "person.2.fill"))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(f.info.name).font(.headline).lineLimit(1)
                                        let sub = [f.info.host ?? "", (f.info.members ?? 0) > 0 ? "\(f.info.members ?? 0) listening" : ""].filter { !$0.isEmpty }
                                        Text(sub.joined(separator: " · ")).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                                    }
                                    Spacer()
                                    if joining == f.info.id { ProgressView() }
                                    else if f.info.locked == true { Image(systemName: "lock.fill").font(.footnote).foregroundStyle(Theme.muted) }
                                }
                                .padding(.horizontal, 20).padding(.vertical, 10)
                            }
                            .buttonStyle(.press)
                        }
                    }
                }
            } else if scanning {
                ProgressView().frame(maxWidth: .infinity, minHeight: 240)
            } else {
                VStack(spacing: 8) {
                    Tumbleweed().frame(width: 220, height: 72)
                    Text("Looks like nothing's here.").font(.subheadline).foregroundStyle(Theme.muted)
                }
                .frame(maxWidth: .infinity, minHeight: 240)
            }
        }
        .task {
            while !Task.isCancelled { // keeps looking while the sheet is open
                scanning = true
                found = await jam.nearby()
                scanning = false
                try? await Task.sleep(nanoseconds: 8_000_000_000)
            }
        }
    }

    private func tap(_ f: JamFound) {
        if f.info.locked == true { jam.pending = f; return }
        joining = f.info.id
        Task {
            do { try await jam.join(f, password: "") } catch { Player.shared.message = error.localizedDescription }
            joining = nil
        }
    }
}

/// A small tumbleweed rolling by with little hops, over a faint ground line.
private struct Tumbleweed: View {
    var body: some View {
        TimelineView(.animation) { tl in
            Canvas { ctx, size in
                let p = tl.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 5.2) / 5.2
                let r: CGFloat = 14
                let ground = size.height - 6
                var line = Path(); line.move(to: CGPoint(x: 0, y: ground)); line.addLine(to: CGPoint(x: size.width, y: ground))
                ctx.stroke(line, with: .color(Theme.muted.opacity(0.18)), lineWidth: 1)
                let x = -r * 2 + (size.width + r * 4) * CGFloat(p)
                let hop = CGFloat(abs(sin(p * .pi * 5))) * 12
                let y = ground - r - hop
                let fade = p < 0.12 ? p / 0.12 : p > 0.88 ? (1 - p) / 0.12 : 1
                let col = Theme.muted.opacity(0.55 * fade)
                var c = ctx
                c.translateBy(x: x, y: y)
                c.rotate(by: .radians(Double(x / r)))
                for k in 0..<7 { // tangled loops
                    let a = Double(k) * 0.9, rr = r * (0.5 + 0.07 * CGFloat(k))
                    let rect = CGRect(x: -rr + CGFloat(cos(a)) * r * 0.25, y: -rr * 0.7 + CGFloat(sin(a)) * r * 0.25, width: rr * 2, height: rr * 1.4)
                    c.stroke(Path(ellipseIn: rect), with: .color(col), lineWidth: 1.4)
                }
                c.stroke(Path(ellipseIn: CGRect(x: -r, y: -r, width: r * 2, height: r * 2)), with: .color(col), lineWidth: 1.6)
            }
        }
    }
}

/// Asks for the password of a Jam that has one (open Jams are joined right away).
private struct PasswordPrompt: View {
    @ObservedObject private var jam = Jam.shared
    @State private var password = ""
    @State private var problem: String?
    @State private var busy = false

    var body: some View {
        Color.clear
            .alert(jam.pending?.info.name ?? "Jam", isPresented: Binding(get: { jam.pending != nil }, set: { if !$0 && !busy { jam.pending = nil } })) {
                SecureField("Password", text: $password)
                Button("Cancel", role: .cancel) { jam.pending = nil; problem = nil; password = "" }
                Button(busy ? "Joining…" : "Join") { join() }
            } message: {
                Text(problem ?? "This Jam has a password.")
            }
            .allowsHitTesting(false)
    }

    private func join() {
        guard let p = jam.pending else { return }
        busy = true
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

// MARK: starting a Jam (behind the +)

private struct CreateJamView: View {
    let started: () -> Void
    @ObservedObject private var jam = Jam.shared
    @ObservedObject private var home = HomeServer.shared
    @State private var name = ""
    @State private var locked = false
    @State private var password = ""
    @State private var starting = false
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(spacing: 0) {
                TextField("\(jam.myName)'s Jam", text: $name).padding(.horizontal, 16).frame(height: 52)
                Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 16)
                Toggle("Password", isOn: $locked.animation()).tint(Color(red: 0.2, green: 0.78, blue: 0.35)).padding(.horizontal, 16).frame(height: 52)
                if locked {
                    Divider().overlay(Color.white.opacity(0.08)).padding(.leading, 16)
                    SecureField("Password for guests", text: $password).padding(.horizontal, 16).frame(height: 52)
                }
            }
            .background(Theme.c30hi, in: RoundedRectangle(cornerRadius: 16))
            if let problem { Text(problem).font(.subheadline).foregroundStyle(Color(red: 1, green: 0.79, blue: 0.79)) }
            if home.address.isEmpty { Text("Needs your Tunio home server (Settings).").font(.footnote).foregroundStyle(Theme.muted) }
            Button { start() } label: {
                HStack(spacing: 10) {
                    if starting { ProgressView().tint(Theme.c60) }
                    Text(starting ? "Starting…" : "Start").font(.headline)
                }
                .foregroundStyle(Theme.c60).frame(maxWidth: .infinity).frame(height: 52)
                .background(Color.white.opacity(canStart ? 1 : 0.4), in: Capsule())
            }
            .buttonStyle(.press)
            .disabled(!canStart)
        }
        .padding(.horizontal, 20).padding(.bottom, 24)
    }

    private var canStart: Bool { !home.address.isEmpty && !starting && (!locked || !password.isEmpty) }

    private func start() {
        starting = true; problem = nil
        let title = name.trimmingCharacters(in: .whitespaces).isEmpty ? "\(jam.myName)'s Jam" : name
        Task {
            do { try await jam.create(name: title, password: locked ? password : ""); started() }
            catch { problem = error.localizedDescription }
            starting = false
        }
    }
}

// MARK: in a Jam

private struct InJamView: View {
    let state: JamState
    @ObservedObject private var jam = Jam.shared

    private var host: Bool { state.you?.host == true }

    var body: some View {
        List {
            // who's here
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(state.people ?? []) { p in
                        VStack(spacing: 4) {
                            Circle().fill(p.host == true ? Color.white : Theme.c30hi).frame(width: 44, height: 44)
                                .overlay(Text(p.name.prefix(1).uppercased()).font(.headline).foregroundStyle(p.host == true ? Theme.c60 : Theme.text))
                            Text(p.id == state.you?.id ? "You" : p.name).font(.caption2).foregroundStyle(Theme.muted).lineLimit(1).frame(width: 52)
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
            .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)

            // where it plays, like a device card, with the controls
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(host ? "This phone" : "\(state.host ?? "The host")'s phone").font(.system(size: 22, weight: .heavy))
                        Text(state.current.map { "\($0.title) – \($0.artist ?? "")" } ?? "Nothing playing")
                            .font(.subheadline).foregroundStyle(Theme.accentLight).lineLimit(1)
                    }
                    Spacer()
                    Image(systemName: "iphone").font(.system(size: 32)).foregroundStyle(Theme.accentLight)
                }
                .padding(.vertical, 4)
                HStack(spacing: 28) {
                    Spacer()
                    Button { jam.previous() } label: { Image(systemName: "backward.fill").font(.title2).frame(width: 44, height: 44) }
                    Button { jam.setPlaying(!state.playing) } label: {
                        Image(systemName: state.playing ? "pause.fill" : "play.fill").font(.title2).foregroundStyle(Theme.c60)
                            .contentTransition(.symbolEffect(.replace))
                            .frame(width: 56, height: 56).background(Color.white, in: Circle())
                    }
                    Button { jam.next() } label: { Image(systemName: "forward.fill").font(.title2).frame(width: 44, height: 44) }
                    Spacer()
                }
                .buttonStyle(.press)
                if !host {
                    Toggle("Play on this iPhone too", isOn: $jam.playAlong).tint(Color(red: 0.2, green: 0.78, blue: 0.35))
                }
            }
            .listRowBackground(Theme.c30hi)

            Section {
                if state.upNext.isEmpty { Text("Tap any song to add it.").foregroundStyle(Theme.muted) }
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
            } header: { Text("Up next").font(.headline).foregroundStyle(Theme.text).textCase(nil) }
            .listRowBackground(Theme.c30hi)
            .environment(\.editMode, .constant(.active))

            HStack(spacing: 12) {
                ShareLink(item: jam.link, message: Text("Join my Jam on Tunio" + (state.locked == true ? " (ask me for the password)" : ""))) {
                    Text("Invite").font(.headline).foregroundStyle(Theme.c60).frame(maxWidth: .infinity).frame(height: 48).background(Color.white, in: Capsule())
                }
                Button { jam.leave() } label: {
                    Text(host ? "End" : "Leave").font(.headline).frame(maxWidth: .infinity).frame(height: 48).background(Theme.c30hi, in: Capsule())
                }
            }
            .buttonStyle(.press)
            .listRowInsets(EdgeInsets(top: 12, leading: 20, bottom: 12, trailing: 20)).listRowBackground(Color.clear).listRowSeparator(.hidden)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
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
                        Text("\(s.name) · \(s.host ?? "the host")'s phone").font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                    }
                    Spacer()
                    Button { jam.setPlaying(!s.playing) } label: {
                        Image(systemName: s.playing ? "pause.fill" : "play.fill").font(.title3).frame(width: 44, height: 44)
                            .contentTransition(.symbolEffect(.replace))
                    }
                    .buttonStyle(.press)
                }
                .padding(8)
                .background(Theme.c30hi, in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.press)
        }
    }
}

/// The player's Jam button: outlined, filled while you're in a Jam (with how many people).
struct JamButton: View {
    @ObservedObject private var jam = Jam.shared
    @State private var open = false

    var body: some View {
        Button { open = true } label: {
            Image(systemName: jam.active ? "person.2.wave.2.fill" : "person.2.wave.2").font(.title3)
                .foregroundStyle(Theme.text)
                .contentTransition(.symbolEffect(.replace))
                .overlay(alignment: .topTrailing) {
                    if let n = jam.state?.people?.count {
                        Text("\(n)").font(.system(size: 10, weight: .bold)).foregroundStyle(Theme.c60)
                            .frame(width: 16, height: 16).background(Color.white, in: Circle()).offset(x: 10, y: -8)
                    }
                }
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.press)
        .sheet(isPresented: $open) { JamView() }
    }
}
