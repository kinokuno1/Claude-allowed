import SwiftUI
import UIKit

struct PlayerView: View {
    @ObservedObject private var player = Player.shared
    @ObservedObject private var library = Library.shared
    @ObservedObject private var downloads = Downloads.shared
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var ui: UIState
    @State private var panel: Panel = .cover
    @State private var image: UIImage?

    enum Panel { case cover, lyrics, queue }

    var body: some View {
        ZStack {
            FlowingBackground(image: image, playing: player.isPlaying)
            if let t = player.current {
                VStack(spacing: 0) {
                    topBar(t)
                    Group {
                        switch panel {
                        case .cover: coverPanel(t)
                        case .lyrics: LyricsPanel(track: t)
                        case .queue: QueuePanel()
                        }
                    }
                    .frame(maxHeight: .infinity)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
                    info(t)
                    ProgressBar()
                    controls
                    bottomBar
                }
                .padding(.horizontal, 22).padding(.bottom, 8)
                .animation(.easeInOut(duration: 0.25), value: panel)
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
        .foregroundStyle(Theme.text)
        .task(id: player.current?.thumbnail) { await loadImage() }
        .gesture(DragGesture(minimumDistance: 30).onEnded { v in if v.translation.height > 120 && panel == .cover { dismiss() } })
    }

    private func loadImage() async {
        guard let u = player.current?.thumbnailURL, let data = try? await Net.get(u), let img = UIImage(data: data) else { image = nil; return }
        withAnimation(.easeInOut(duration: 0.6)) { image = img }
    }

    private func topBar(_ t: Track) -> some View {
        HStack {
            Button { dismiss() } label: { Image(systemName: "chevron.down").font(.title3.weight(.semibold)).frame(width: 44, height: 44) }
            Spacer()
            VStack(spacing: 2) {
                Text("PLAYING FROM").font(.caption2.weight(.semibold)).tracking(1.5).foregroundStyle(Theme.muted)
                Text(player.context ?? t.source.label).font(.footnote.weight(.semibold)).lineLimit(1)
            }
            Spacer()
            Menu { TrackMenu(track: t) } label: { Image(systemName: "ellipsis").font(.title3.weight(.semibold)).frame(width: 44, height: 44) }
        }
    }

    private func coverPanel(_ t: Track) -> some View {
        GeometryReader { g in
            let side = min(g.size.width, g.size.height) - 8
            ZStack {
                Cover(url: t.thumbnail, trackID: t.id, size: side, corner: 14)
                    .shadow(color: .black.opacity(0.5), radius: 30, y: 16)
                    .scaleEffect(player.isPlaying ? 1 : 0.9)
                    .animation(.spring(response: 0.45, dampingFraction: 0.75), value: player.isPlaying)
                if player.isLoading {
                    VStack(spacing: 10) {
                        ProgressView().controlSize(.large).tint(.white)
                        Text(downloads.progress[t.id].map { "Fetching audio \(Int($0 * 100))%" } ?? "Finding audio…").font(.footnote)
                    }.padding(18).glassy(cornerRadius: 16)
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }.padding(.vertical, 18)
    }

    private func info(_ t: Track) -> some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text(t.title).font(.title2.bold()).lineLimit(2)
                Text(t.artist.isEmpty ? t.source.label : t.artist).font(.body).foregroundStyle(Theme.muted).lineLimit(1)
            }
            Spacer()
            Button { downloads.isOffline(t) ? downloads.remove(t) : downloads.download([t]) } label: {
                Group {
                    if let p = downloads.progress[t.id] { ProgressView(value: p).progressViewStyle(.circular).tint(Theme.accentLight) }
                    else { Image(systemName: downloads.isOffline(t) ? "arrow.down.circle.fill" : "arrow.down.circle") }
                }.font(.title2).foregroundStyle(downloads.isOffline(t) ? Theme.good : Theme.text).frame(width: 40, height: 40)
            }
            Button { library.toggleLike(t) } label: {
                Image(systemName: library.isLiked(t) ? "heart.fill" : "heart").font(.title2)
                    .foregroundStyle(Theme.text).frame(width: 40, height: 40)
                    .symbolEffect(.bounce, value: library.isLiked(t))
            }
        }.padding(.top, 8)
    }

    private var controls: some View {
        HStack {
            Button { player.toggleShuffle() } label: {
                ToggleGlyph(on: player.shuffle, icon: "shuffle", badge: player.smart ? "sparkle" : nil)
                    .accessibilityLabel(player.smart ? "Smart shuffle" : "Shuffle")
            }
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").font(.system(size: 34)) }
            Spacer()
            Button { player.toggle() } label: {
                ZStack {
                    Circle().fill(Theme.text)
                    if player.isLoading { ProgressView().tint(Theme.c60) }
                    else { Image(systemName: player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 34)).foregroundStyle(Theme.c60).contentTransition(.symbolEffect(.replace)) }
                }.frame(width: 76, height: 76)
            }
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.system(size: 34)) }
            Spacer()
            Button { player.cycleRepeat() } label: {
                ToggleGlyph(on: player.repeatMode != .off, icon: player.repeatMode == .one ? "repeat.1" : "repeat")
            }
        }
        .buttonStyle(.press)
        .padding(.vertical, 10)
    }

    private var bottomBar: some View {
        HStack {
            panelButton(.lyrics, "quote.bubble", "Lyrics")
            Spacer()
            if HomeServer.shared.usable {
                Label("Home server", systemImage: "server.rack").font(.caption).foregroundStyle(Theme.muted)
            }
            Spacer()
            JamButton()
            panelButton(.queue, "list.bullet", "Queue")
        }.padding(.top, 4)
    }

    private func panelButton(_ p: Panel, _ icon: String, _ label: String) -> some View {
        Button { panel = panel == p ? .cover : p } label: {
            ToggleGlyph(on: panel == p, icon: icon, filled: icon == "quote.bubble" ? "quote.bubble.fill" : nil).accessibilityLabel(label)
        }.buttonStyle(.press)
    }
}

/// The seek bar. Drag to scrub; it grows a little while you hold it.
struct ProgressBar: View {
    @ObservedObject private var clock = Clock.shared
    @ObservedObject private var player = Player.shared
    @State private var dragging: Double?

    var body: some View {
        let d = max(clock.duration, 1)
        let shown = dragging ?? min(clock.time / d, 1)
        VStack(spacing: 6) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.2))
                    Capsule().fill(Theme.text).frame(width: g.size.width * shown)
                }
                .frame(height: dragging != nil ? 8 : 4)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in dragging = min(max(v.location.x / g.size.width, 0), 1) }
                    .onEnded { v in
                        player.seek(to: min(max(v.location.x / g.size.width, 0), 1) * d)
                        dragging = nil
                    })
                .animation(.easeOut(duration: 0.15), value: dragging != nil)
            }.frame(height: 22)
            HStack {
                Text(formatDuration(Int(shown * d)).isEmpty ? "0:00" : formatDuration(Int(shown * d)))
                Spacer()
                Text(formatDuration(Int(clock.duration)))
            }.font(.caption.monospacedDigit()).foregroundStyle(Theme.muted)
        }
    }
}

/// Synced lyrics: the list glides to the line being sung, which turns white; tap a line to jump there.
/// "Word" mode lights each word up as it's sung (timing looked up only when Word is picked).
struct LyricsPanel: View {
    let track: Track
    @ObservedObject private var clock = Clock.shared
    @ObservedObject private var player = Player.shared
    @AppStorage("lyrics.mode") private var mode = "line"
    @State private var result: LyricsResult?
    @State private var loading = true
    @State private var words: WordLyrics?
    @State private var loadingWords = false

    var body: some View {
        VStack(spacing: 6) {
            if !loading {
                HStack {
                    if mode == "word", let w = words, let src = w.source {
                        Text(w.estimated ? "Word timing estimated" : "Word timing: \(src)").font(.caption2).foregroundStyle(Theme.muted)
                            .transition(.opacity)
                    }
                    Spacer()
                    LyricsModeToggle(mode: $mode)
                }
            }
            Group {
                if loading {
                    ProgressView("Looking for lyrics…").frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if mode == "word" && (loadingWords || words == nil) {
                    WordsLoading()
                } else if mode == "word", let w = words, !w.lines.isEmpty {
                    wordView(w.lines)
                } else if let r = result, let lines = r.synced {
                    VStack(spacing: 4) {
                        if mode == "word" { Text("No word-by-word timing for this song.").font(.caption).foregroundStyle(Theme.muted) }
                        synced(lines)
                    }
                } else if let r = result, let plain = r.plain {
                    ScrollView { Text(plain).font(.title3.bold()).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 20) }
                } else if result?.instrumental == true {
                    message("♪ Instrumental ♪")
                } else {
                    message("No lyrics found for this song.\nLRCLIB is made by its community, so new or rare songs may be missing.")
                }
            }
            .animation(.easeInOut(duration: 0.3), value: mode)
        }
        .task(id: track.id) {
            loading = true; words = nil
            result = await Lyrics.get(track)
            loading = false
        }
        .task(id: "\(track.id)|\(mode)|\(loading)") {
            guard mode == "word", !loading, words == nil else { return }
            loadingWords = true
            words = await WordSync.get(track, lines: result)
            loadingWords = false
        }
    }

    private func message(_ s: String) -> some View {
        Text(s).font(.headline).multilineTextAlignment(.center).foregroundStyle(Theme.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var fade: some View {
        LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.12),
                               .init(color: .black, location: 0.8), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom)
    }

    private func synced(_ lines: [LyricLine]) -> some View {
        let idx = lines.lastIndex { $0.time <= clock.time + 0.25 } ?? -1
        return ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 18) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                        Text(line.text.isEmpty ? "♪" : line.text)
                            .font(.system(size: 26, weight: .heavy))
                            .foregroundStyle(i == idx ? Color.white : Color.white.opacity(i < idx ? 0.55 : 0.35))
                            .blur(radius: i == idx || abs(i - idx) > 6 ? 0 : 0.4)
                            .scaleEffect(i == idx ? 1.03 : 1, anchor: .leading)
                            .animation(.easeInOut(duration: 0.5), value: idx)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(i)
                            .onTapGesture { Player.shared.seek(to: line.time) }
                    }
                }
                .padding(.vertical, 120)
            }
            .mask(fade)
            .onChange(of: idx) { _, i in withAnimation(.spring(response: 0.7, dampingFraction: 0.9)) { proxy.scrollTo(max(i, 0), anchor: UnitPoint(x: 0.5, y: 0.35)) } }
            .onAppear { proxy.scrollTo(max(idx, 0), anchor: UnitPoint(x: 0.5, y: 0.35)) }
        }
    }

    /// Word by word: the text stays put, only a white highlight runs through the words as they're sung.
    private func wordView(_ lines: [WordLine]) -> some View {
        let idx = lines.lastIndex { $0.start <= clock.time + 0.15 } ?? -1
        return ScrollViewReader { proxy in
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 18) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                        Group {
                            if i == idx {
                                TimelineView(.animation(paused: !player.isPlaying)) { _ in sung(line, now: player.currentTime) }
                            } else {
                                sung(line, now: i < idx ? .infinity : -.infinity)
                            }
                        }
                        .font(.system(size: 26, weight: .heavy))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .id(i)
                        .onTapGesture { Player.shared.seek(to: line.start) }
                    }
                }
                .padding(.vertical, 120)
            }
            .mask(fade)
            .onChange(of: idx) { _, i in withAnimation(.spring(response: 0.7, dampingFraction: 0.9)) { proxy.scrollTo(max(i, 0), anchor: UnitPoint(x: 0.5, y: 0.35)) } }
            .onAppear { proxy.scrollTo(max(idx, 0), anchor: UnitPoint(x: 0.5, y: 0.35)) }
        }
    }

    /// One line as a single Text, each word as bright as how much of it has been sung.
    private func sung(_ line: WordLine, now: Double) -> Text {
        line.words.reduce(Text("")) { text, w in
            let f = w.end > w.start ? min(max((now - w.start) / (w.end - w.start), 0), 1) : (now >= w.start ? 1 : 0)
            let base = now == .infinity ? 0.55 : 0.35
            return text + Text(w.text).foregroundColor(Color.white.opacity(now == .infinity ? base : base + (1 - base) * f))
        }
    }
}

/// Line / Word switch: two icons with a pill sliding between them.
struct LyricsModeToggle: View {
    @Binding var mode: String
    @Namespace private var ns

    var body: some View {
        HStack(spacing: 2) {
            item("line", "Line by line") { LineIcon() }
            item("word", "Word by word") { WordIcon() }
        }
        .padding(3)
        .background(Color.white.opacity(0.1), in: Capsule())
    }

    private func item<I: View>(_ m: String, _ label: String, @ViewBuilder icon: () -> I) -> some View {
        Button {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.72)) { mode = m }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            icon()
                .foregroundStyle(mode == m ? Theme.c60 : Theme.text)
                .frame(width: 40, height: 30)
                .background {
                    if mode == m { Capsule().fill(Color.white).matchedGeometryEffect(id: "pill", in: ns) }
                }
        }
        .buttonStyle(.press)
        .accessibilityLabel(label)
    }
}

/// Three bars: whole lines.
private struct LineIcon: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 2.5) {
            RoundedRectangle(cornerRadius: 1.5).frame(width: 16, height: 3)
            RoundedRectangle(cornerRadius: 1.5).frame(width: 11, height: 3).opacity(0.55)
            RoundedRectangle(cornerRadius: 1.5).frame(width: 13.5, height: 3).opacity(0.55)
        }.frame(width: 18, height: 16)
    }
}

/// Three word blocks, the first one underlined.
private struct WordIcon: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 1.5) {
                RoundedRectangle(cornerRadius: 1.5).frame(width: 5, height: 5)
                RoundedRectangle(cornerRadius: 1.5).frame(width: 4.5, height: 5).opacity(0.55)
                RoundedRectangle(cornerRadius: 1.5).frame(width: 4.5, height: 5).opacity(0.55)
            }
            RoundedRectangle(cornerRadius: 0.8).frame(width: 5, height: 1.6)
        }.frame(width: 18, height: 16)
    }
}

/// Three dots bouncing while word timing is looked up.
private struct WordsLoading: View {
    @State private var up = false
    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 8) {
                ForEach(0..<3) { i in
                    Circle().fill(Color.white).frame(width: 10, height: 10)
                        .offset(y: up ? -8 : 4)
                        .opacity(up ? 1 : 0.4)
                        .animation(.easeInOut(duration: 0.45).repeatForever().delay(Double(i) * 0.15), value: up)
                }
            }
            Text("Finding word-by-word timing…").font(.subheadline).foregroundStyle(Theme.muted)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { up = true }
    }
}

struct QueuePanel: View {
    @ObservedObject private var player = Player.shared
    var body: some View {
        List {
            Section {
                if player.upNext.isEmpty {
                    Text(UserDefaults.standard.object(forKey: "autoplay") as? Bool ?? true ? "Autoplay adds similar songs when the queue runs low." : "The queue is empty.")
                        .foregroundStyle(Theme.muted)
                }
                ForEach(Array(player.upNext.enumerated()), id: \.offset) { i, t in
                    HStack(spacing: 12) {
                        Cover(url: t.thumbnail, trackID: t.id, size: 44)
                        VStack(alignment: .leading) {
                            Text(t.title).lineLimit(1)
                            Text((player.recommended.contains(t.id) ? "✦ Recommended · " : "") + t.artist).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { player.jump(to: player.index + 1 + i) }
                }
                .onDelete { idx in idx.sorted(by: >).forEach { player.removeFromQueue(at: player.index + 1 + $0) } }
                .onMove { from, to in player.moveUpNext(from: from, to: to) }
            } header: { Text("Next in queue").font(.headline).foregroundStyle(Theme.text) }
            .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.editMode, .constant(.active))
    }
}

/// The player's background: blurred copies of the cover drifting in slow waves (they rest while paused).
struct FlowingBackground: View {
    var image: UIImage?
    var playing: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !playing)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            GeometryReader { g in
                ZStack {
                    Theme.c60
                    if let image {
                        ForEach(0..<3) { i in
                            let k = Double(i)
                            Image(uiImage: image).resizable().scaledToFill()
                                .frame(width: g.size.width * 1.3, height: g.size.width * 1.3)
                                .clipShape(Circle())
                                .rotationEffect(.degrees(t * (6 + k * 3) * (i == 1 ? -1 : 1)))
                                .offset(x: CGFloat(sin(t / (5 + k) + k * 2)) * g.size.width * 0.3,
                                        y: CGFloat(cos(t / (7 + k) + k)) * g.size.height * 0.25 + CGFloat(k - 1) * g.size.height * 0.25)
                                .opacity(0.75)
                        }
                        .blur(radius: 70)
                    }
                    LinearGradient(colors: [Color.black.opacity(0.25), Color.black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
                }
            }
        }
        .ignoresSafeArea()
    }
}
