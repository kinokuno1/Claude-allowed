import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// Playback position, kept apart from Player so only the views that show it redraw 4 times a second.
@MainActor
final class Clock: ObservableObject {
    static let shared = Clock()
    @Published var time: Double = 0
    @Published var duration: Double = 0
}

/// The queue and AVPlayer. Plays in the background, with lock-screen and Control Center controls.
@MainActor
final class Player: ObservableObject {
    static let shared = Player()

    enum Repeat { case off, all, one }

    @Published private(set) var queue: [Track] = []
    @Published private(set) var index = -1
    @Published private(set) var shuffle = false
    @Published private(set) var repeatMode: Repeat = .off
    @Published private(set) var isPlaying = false
    @Published private(set) var isLoading = false
    @Published private(set) var context: String?
    @Published var message: String?

    var current: Track? { queue.indices.contains(index) ? queue[index] : nil }
    var upNext: [Track] { index + 1 < queue.count ? Array(queue[(index + 1)...]) : [] }

    private let player = AVPlayer()
    /// Exact position right now (word-by-word lyrics read it every frame).
    var currentTime: Double { let t = player.currentTime().seconds; return t.isFinite ? t : 0 }
    private var original: [Track]?
    private var loadTask: Task<Void, Never>?
    private var observers: [NSKeyValueObservation] = []
    private var endObserver: NSObjectProtocol?
    private var failures = 0
    private var autoplayBusy = false
    private var fromHomeServer = false
    private var artworkFor: String?

    private init() {
        player.automaticallyWaitsToMinimizeStalling = true
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, policy: .longFormAudio)
        player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated {
                Clock.shared.time = t.seconds.isFinite ? t.seconds : 0
                if let d = self?.player.currentItem?.duration.seconds, d.isFinite, d > 0 { Clock.shared.duration = d }
            }
        }
        observers.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let playing = p.timeControlStatus != .paused
            Task { @MainActor in
                guard let self, self.isPlaying != playing else { return }
                self.isPlaying = playing
                self.updateNowPlaying()
            }
        })
        setUpRemoteCommands()
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, AVAudioSession.InterruptionType(rawValue: raw) == .ended,
                  let opts = n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                  AVAudioSession.InterruptionOptions(rawValue: opts).contains(.shouldResume) else { return }
            MainActor.assumeIsolated { self?.player.play() }
        }
    }

    // MARK: queue

    func play(_ tracks: [Track], at start: Int = 0, shuffled: Bool? = nil, context: String? = nil) {
        guard !tracks.isEmpty else { return }
        let sh = shuffled ?? shuffle
        shuffle = sh
        self.context = context
        if sh {
            original = tracks
            let first = tracks[min(start, tracks.count - 1)]
            queue = [first] + tracks.enumerated().filter { $0.offset != start }.map(\.element).shuffled()
            load(0)
        } else {
            original = nil
            queue = tracks
            load(min(start, tracks.count - 1))
        }
    }

    func playNext(_ t: Track) {
        guard current != nil else { return play([t]) }
        queue.insert(t, at: index + 1)
        message = "Plays next: \(t.title)"
    }

    func addToQueue(_ t: Track) {
        guard current != nil else { return play([t]) }
        queue.append(t)
        message = "Added to queue: \(t.title)"
    }

    func removeFromQueue(at i: Int) {
        guard queue.indices.contains(i), i != index else { return }
        queue.remove(at: i)
        if i < index { index -= 1 }
    }

    func moveUpNext(from: IndexSet, to: Int) {
        var next = upNext
        next.move(fromOffsets: from, toOffset: to)
        queue = Array(queue[...index]) + next
    }

    func jump(to i: Int) { if queue.indices.contains(i) { load(i) } }

    func toggle() {
        guard current != nil, !isLoading else { return }
        if player.currentItem == nil { return load(index) }
        try? AVAudioSession.sharedInstance().setActive(true)
        if player.timeControlStatus == .paused { player.play() } else { player.pause() }
    }

    func next(auto: Bool = false) {
        guard !queue.isEmpty else { return }
        if auto && repeatMode == .one { seek(to: 0); player.play(); return }
        if index + 1 < queue.count { load(index + 1) }
        else if repeatMode == .all { load(0) }
        else if !auto { load(index) }
    }

    func previous() {
        if Clock.shared.time > 3 || index <= 0 { seek(to: 0) } else { load(index - 1) }
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        Clock.shared.time = seconds
        updateNowPlaying()
    }

    func toggleShuffle() {
        shuffle.toggle()
        guard let cur = current else { return }
        if shuffle {
            original = queue
            queue = Array(queue[...index]) + queue[(index + 1)...].shuffled()
        } else if let o = original {
            queue = o
            index = queue.firstIndex(of: cur) ?? 0
        }
    }

    func cycleRepeat() { repeatMode = repeatMode == .off ? .all : repeatMode == .all ? .one : .off }

    // MARK: loading

    private func load(_ i: Int, allowHomeServer: Bool = true) {
        index = i
        guard let t = current else { return }
        loadTask?.cancel()
        isLoading = true
        player.pause()
        Clock.shared.time = 0
        Clock.shared.duration = Double(t.durationSec)
        updateNowPlaying()
        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                if !allowHomeServer { HomeServer.shared.markDown() }
                let (audio, matched) = try await Engine.audio(for: t)
                guard !Task.isCancelled, self.current?.id == t.id else { return }
                if matched != t, self.queue.indices.contains(self.index) { self.queue[self.index] = matched }
                let item: AVPlayerItem
                switch audio {
                case .file(let u):
                    item = AVPlayerItem(url: u); self.fromHomeServer = false
                case .remote(let u, let headers):
                    let asset = AVURLAsset(url: u, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
                    item = AVPlayerItem(asset: asset); self.fromHomeServer = !headers.isEmpty
                }
                self.watch(item, track: matched)
                try? AVAudioSession.sharedInstance().setActive(true)
                self.player.replaceCurrentItem(with: item)
                self.player.play()
                self.failures = 0
                Library.shared.addRecent(matched)
                self.isLoading = false
                self.updateNowPlaying()
                self.afterTrackChange()
            } catch {
                guard !Task.isCancelled, self.current?.id == t.id else { return }
                self.isLoading = false
                self.failed(t, error.localizedDescription)
            }
        }
    }

    private func failed(_ t: Track, _ why: String) {
        failures += 1
        message = "Couldn't play “\(t.title)”: \(why)"
        if failures < 3, index + 1 < queue.count { load(index + 1) }
    }

    private func watch(_ item: AVPlayerItem, track: Track) {
        if let o = endObserver { NotificationCenter.default.removeObserver(o) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.next(auto: true) }
        }
        let statusObs = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            let why = item.error?.localizedDescription ?? "playback error"
            Task { @MainActor in
                guard let self, self.current?.id == track.id else { return }
                if self.fromHomeServer { // the server stopped answering: play it from the phone instead
                    self.fromHomeServer = false
                    self.load(self.index, allowHomeServer: false)
                } else {
                    self.failed(track, why)
                }
            }
        }
        observers = Array(observers.prefix(1)) + [statusObs]
    }

    private func afterTrackChange() {
        guard let t = current else { return }
        let autoplay = UserDefaults.standard.object(forKey: "autoplay") as? Bool ?? true
        if autoplay, queue.count - index - 1 <= 3, repeatMode == .off, !autoplayBusy {
            autoplayBusy = true
            Task {
                let exclude = Set(queue.map(\.id) + Library.shared.recent.prefix(30))
                let more = await Engine.similar(to: t, excluding: exclude)
                let fresh = more.filter { m in !queue.contains { $0.id == m.id } }
                if !fresh.isEmpty { queue.append(contentsOf: fresh); message = "Autoplay: added \(fresh.count) similar songs" }
                autoplayBusy = false
                warmNext()
            }
        }
        warmNext()
    }

    /// Gets the next song's audio link ready, so it starts instantly.
    private func warmNext() {
        guard index + 1 < queue.count else { return }
        let next = queue[index + 1]
        if HomeServer.shared.usable, let target = next.playTarget { HomeServer.shared.warm([target]) }
        Task.detached(priority: .utility) { _ = try? await Engine.audio(for: next) }
    }

    // MARK: lock screen

    private func setUpRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.player.play() }; return .success }
        c.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.player.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.toggle() }; return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.next() }; return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.previous() }; return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { self?.seek(to: e.positionTime) }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let t = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
        if info[MPMediaItemPropertyTitle] as? String != t.title || artworkFor != t.id { info[MPMediaItemPropertyArtwork] = nil }
        info[MPMediaItemPropertyTitle] = t.title
        info[MPMediaItemPropertyArtist] = t.artist
        info[MPMediaItemPropertyPlaybackDuration] = Clock.shared.duration
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Clock.shared.time
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if artworkFor != t.id, let u = t.thumbnailURL {
            artworkFor = t.id
            Task {
                guard let data = try? await Net.get(u), let img = UIImage(data: data), self.current?.id == t.id else { return }
                let art = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
                var i = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                i[MPMediaItemPropertyArtwork] = art
                MPNowPlayingInfoCenter.default().nowPlayingInfo = i
            }
        }
    }
}
