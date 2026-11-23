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
    /// Smart shuffle: a song you might like after every 3 to 5 songs (the shuffle button's third state).
    @Published private(set) var smart = false
    /// Recommended songs smart shuffle put in the queue (shown as "Recommended").
    @Published private(set) var recommended: Set<String> = []
    private var smartPool: [Track] = []
    private var smartBusy = false
    private var nextGap = Int.random(in: 3...5)
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
                self.lastReport = .distantPast // a Jam host reports play / pause right away
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
        recommended = []
        smartPool = []
        if shuffled == false { smart = false }
        // in a Jam, a tapped song joins the shared queue
        if Jam.shared.active { return Jam.shared.add([tracks[min(max(start, 0), tracks.count - 1)]]) }
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
        if Jam.shared.active { return Jam.shared.add([t], next: true) }
        guard current != nil else { return play([t]) }
        queue.insert(t, at: index + 1)
        message = "Plays next: \(t.title)"
    }

    func addToQueue(_ t: Track) {
        if Jam.shared.active { return Jam.shared.add([t]) }
        guard current != nil else { return play([t]) }
        queue.append(t)
        message = "Added to queue: \(t.title)"
    }

    func removeFromQueue(at i: Int) {
        if let s = Jam.shared.state { if s.queue.indices.contains(i) { Jam.shared.remove(s.queue[i].qid) }; return }
        guard queue.indices.contains(i), i != index else { return }
        queue.remove(at: i)
        if i < index { index -= 1 }
    }

    func moveUpNext(from: IndexSet, to: Int) {
        if let s = Jam.shared.state, let f = from.first {
            let next = s.upNext
            guard next.indices.contains(f) else { return }
            Jam.shared.move(next[f].qid, to: s.index + 1 + (to > f ? to - 1 : to))
            return
        }
        var next = upNext
        next.move(fromOffsets: from, toOffset: to)
        queue = Array(queue[...index]) + next
    }

    func jump(to i: Int) {
        if let s = Jam.shared.state { if s.queue.indices.contains(i) { Jam.shared.jump(s.queue[i].qid) }; return }
        if queue.indices.contains(i) { load(i) }
    }

    func toggle() {
        if let s = Jam.shared.state { return Jam.shared.setPlaying(!s.playing) }
        guard current != nil, !isLoading else { return }
        if player.currentItem == nil { return load(index) }
        try? AVAudioSession.sharedInstance().setActive(true)
        if player.timeControlStatus == .paused { player.play() } else { player.pause() }
    }

    func next(auto: Bool = false) {
        if let s = Jam.shared.state {
            // a song ended: the host moves the Jam on (once); guests playing along wait for it
            if !auto { Jam.shared.next() } else if Jam.shared.isHost, let q = s.current?.qid { Jam.shared.next(from: q) }
            return
        }
        guard !queue.isEmpty else { return }
        if auto && repeatMode == .one { seek(to: 0); player.play(); return }
        if index + 1 < queue.count { load(index + 1) }
        else if repeatMode == .all { load(0) }
        else if !auto { load(index) }
    }

    func previous() {
        if Jam.shared.active { return Jam.shared.previous() }
        if Clock.shared.time > 3 || index <= 0 { seek(to: 0) } else { load(index - 1) }
    }

    func seek(to seconds: Double) {
        if Jam.shared.active {
            Jam.shared.seek(seconds)
            if !Jam.shared.playsHere { return }
        }
        player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        Clock.shared.time = seconds
        updateNowPlaying()
    }

    /// Shuffle button: off → shuffle → smart shuffle (a song you might like every 3 to 5 songs) → off.
    func toggleShuffle() {
        if !shuffle { setShuffle(true) }
        else if !smart && !Jam.shared.active {
            smart = true
            message = "Smart shuffle: a song you might like every few songs"
            fillSmart()
        } else {
            smart = false
            if index >= 0 { queue = Array(queue[...index]) + queue[(index + 1)...].filter { !recommended.contains($0.id) } }
            original = original?.filter { !recommended.contains($0.id) }
            setShuffle(false)
        }
    }

    /// Smart shuffle: puts a recommended song (similar to the list) after every 3 to 5 songs coming up.
    private func fillSmart() {
        guard smart, !Jam.shared.active, current != nil, !smartBusy else { return }
        var gap = 0
        var i = index
        while i >= 0, !recommended.contains(queue[i].id) { gap += 1; i -= 1 }
        var at: [Int] = []
        var j = index + 1
        while j < queue.count, j <= index + 12 {
            if recommended.contains(queue[j].id) { gap = 0 } else {
                gap += 1
                if gap >= nextGap { at.append(j + 1); gap = 0; nextGap = Int.random(in: 3...5) }
            }
            j += 1
        }
        guard !at.isEmpty else { return }
        smartBusy = true
        Task {
            if smartPool.count < at.count {
                let mine = queue.filter { !recommended.contains($0.id) }
                if let seed = mine.randomElement() ?? current {
                    let exclude = Set(queue.map(\.id) + Library.shared.recent.prefix(30))
                    let more = await Engine.similar(to: seed, excluding: exclude)
                    smartPool += more.filter { m in !queue.contains { $0.id == m.id } && !smartPool.contains { $0.id == m.id } }.shuffled()
                }
            }
            for pos in at.reversed() {
                guard !smartPool.isEmpty else { break }
                let t = smartPool.removeFirst()
                guard pos <= queue.count, !queue.contains(where: { $0.id == t.id }) else { continue }
                recommended.insert(t.id)
                queue.insert(t, at: pos)
            }
            smartBusy = false
        }
    }

    private func setShuffle(_ on: Bool) {
        shuffle = on
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

    private func load(_ i: Int, allowHomeServer: Bool = true, startAt: Double = 0) {
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
                let audio: Engine.Audio
                let matched: Track
                if Downloads.shared.file(for: t) == nil, let u = Jam.shared.audioURL(for: t) {
                    audio = .remote(u, headers: [:]) // playing along: the Jam's server fetched it ahead
                    matched = t
                } else {
                    let r = try await Engine.audio(for: t)
                    audio = r.0
                    matched = r.1
                }
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
                if startAt > 0.5 { _ = await self.player.seek(to: CMTime(seconds: startAt, preferredTimescale: 600)) }
                if let s = Jam.shared.state, !s.playing { self.player.pause() } else { self.player.play() }
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
        if Jam.shared.active { // the host skips it for everyone
            if Jam.shared.isHost, let q = Jam.shared.state?.current?.qid { Jam.shared.next(from: q) }
            return
        }
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
        if smart { fillSmart() }
        let autoplay = UserDefaults.standard.object(forKey: "autoplay") as? Bool ?? true
        if autoplay, !Jam.shared.active, queue.count - index - 1 <= 3, repeatMode == .off, !autoplayBusy {
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
        guard index + 1 < queue.count, !(Jam.shared.active && !Jam.shared.isHost) else { return }
        let next = queue[index + 1]
        if HomeServer.shared.usable, let target = next.playTarget { HomeServer.shared.warm([target]) }
        Task.detached(priority: .utility) { _ = try? await Engine.audio(for: next) }
    }

    // MARK: Jam

    private var lastReport = Date.distantPast
    private var lastCorrection = Date.distantPast
    private var jamTicker: Task<Void, Never>?

    /// Follows the Jam's shared queue: the host's phone, or a guest playing along.
    func followJam(_ old: JamState?, _ new: JamState?) {
        let jam = Jam.shared
        if jamTicker == nil, new != nil { startJamTicker() }
        guard let new, jam.playsHere else {
            // a guest joined (the music plays on the host's phone), or left: this phone goes quiet
            let guestJoined = new != nil && old == nil
            let guestLeft = new == nil && old != nil && old?.you?.host != true
            if guestJoined || guestLeft { player.pause() }
            return
        }
        guard let cur = new.current else { player.pause(); return }
        let wasID = current?.id
        let loaded = player.currentItem != nil || isLoading
        queue = new.queue.map(\.track)
        context = "Jam · \(new.name)"
        let expected = jam.expectedPosition(new)
        if wasID == cur.id && loaded {
            index = new.index
            if !isLoading, abs(currentTime - expected) > (jam.isHost ? 2.5 : 1.2) {
                player.seek(to: CMTime(seconds: expected, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            }
            if !isLoading {
                if new.playing, player.timeControlStatus == .paused { try? AVAudioSession.sharedInstance().setActive(true); player.play() }
                if !new.playing, player.timeControlStatus != .paused { player.pause() }
            }
        } else {
            load(new.index, startAt: expected)
        }
    }

    /// A guest turned "play on this phone too" on or off.
    func jamPlayAlongChanged(_ on: Bool) {
        if on { followJam(nil, Jam.shared.state) } else if Jam.shared.active && !Jam.shared.isHost { player.pause() }
    }

    /// Every second: the host tells the Jam where it really is; guests playing along fix their drift.
    private func startJamTicker() {
        jamTicker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                self?.jamTick()
            }
        }
    }

    private func jamTick() {
        let jam = Jam.shared
        guard let s = jam.state, jam.playsHere, let cur = s.current, current?.id == cur.id, !isLoading else { return }
        if jam.isHost {
            if Date().timeIntervalSince(lastReport) > 10 {
                lastReport = Date()
                jam.report(qid: cur.qid, position: currentTime, playing: player.timeControlStatus != .paused)
            }
        } else if s.playing, player.timeControlStatus == .playing, Date().timeIntervalSince(lastCorrection) > 3 {
            let expected = jam.expectedPosition(s)
            if abs(currentTime - expected) > 0.35 {
                lastCorrection = Date()
                player.seek(to: CMTime(seconds: expected, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            }
        }
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
