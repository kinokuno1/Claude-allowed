"""Library (likes, playlists, history), offline downloads and the playback cache. Same data model as Android."""
from __future__ import annotations

import json
import os
import shutil
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from . import engine
from .core import Track
from .paths import AUDIO_CACHE, LIBRARY_FILE, OFFLINE_DIR, SETTINGS_FILE

CACHE_LIMIT = 1024 * 1024 * 1024  # 1 GB of songs you've played


class Library:
    def __init__(self):
        self.lock = threading.RLock()
        self.data = {"tracks": {}, "liked": [], "playlists": [], "recent": []}
        self.settings = {"autoplay": True}
        for path, target in ((LIBRARY_FILE, self.data), (SETTINGS_FILE, self.settings)):
            try:
                with open(path, encoding="utf-8") as f:
                    target.update(json.load(f))
            except (OSError, ValueError):
                pass
        os.makedirs(OFFLINE_DIR, exist_ok=True)
        self.offline = {f.rsplit(".", 1)[0] for f in os.listdir(OFFLINE_DIR) if ".part" not in f and "." in f}
        self.progress: dict[str, float] = {}  # track id -> 0..1, or -1 while looking it up
        self.queue = {"total": 0, "done": 0, "failed": 0, "current": None}
        self.errors: list[str] = []
        self._queued: set[str] = set()
        self._inflight: dict[str, threading.Event] = {}
        self._streams: dict[str, tuple[dict, float]] = {}
        self._downloads = ThreadPoolExecutor(2, thread_name_prefix="download")

    # ------------------------------------------------------------ persistence

    def _save(self):
        tmp = LIBRARY_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f)
        os.replace(tmp, LIBRARY_FILE)

    def save_settings(self, **kw):
        with self.lock:
            self.settings.update(kw)
            with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
                json.dump(self.settings, f)

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "library": self.data,
                "offline": sorted(self.offline),
                "progress": dict(self.progress),
                "queue": dict(self.queue),
                "settings": dict(self.settings),
                "errors": self._take_errors(),
                "offlineDir": OFFLINE_DIR,
                "engine": engine.version(),
            }

    def _take_errors(self):
        e, self.errors = self.errors, []
        return e

    # ------------------------------------------------------------ edits

    def _remember(self, *tracks: Track):
        for t in tracks:
            old = self.data["tracks"].get(t.id)
            d = t.to_dict()
            if old and old.get("matchedUrl") and not d.get("matchedUrl"):
                d["matchedUrl"] = old["matchedUrl"]
            if old and old.get("artChecked"):  # keep a cover that was looked up for this song
                d["artChecked"] = True
                d["thumbnail"] = old.get("thumbnail") or d.get("thumbnail")
            self.data["tracks"][t.id] = d

    def needs_artwork(self) -> list[Track]:
        """Songs without a cover, and Spotify songs not checked yet (they were given the playlist's cover)."""
        with self.lock:
            return [Track.from_dict(d) for d in self.data["tracks"].values()
                    if not d.get("artChecked") and (not d.get("thumbnail") or d.get("source") == "SPOTIFY")]

    def set_artwork(self, art: dict[str, str | None]):
        """Stores looked-up covers (None = nothing better found; the song keeps what it had)."""
        with self.lock:
            changed = False
            for tid, url in art.items():
                d = self.data["tracks"].get(tid)
                if d is None:
                    continue
                if url:
                    d["thumbnail"] = url
                d["artChecked"] = True
                changed = True
            if changed:
                self._save()

    def track(self, tid: str) -> Track | None:
        d = self.data["tracks"].get(tid)
        return Track.from_dict(d) if d else None

    def toggle_like(self, t: Track):
        with self.lock:
            if t.id in self.data["liked"]:
                self.data["liked"].remove(t.id)
            else:
                self._remember(t)
                self.data["liked"].insert(0, t.id)
            self._save()

    def add_recent(self, t: Track):
        with self.lock:
            self._remember(t)
            r = [i for i in self.data["recent"] if i != t.id]
            self.data["recent"] = ([t.id] + r)[:50]
            self._save()

    def create_playlist(self, name: str, tracks: list[Track], source_url=None, cover=None) -> dict:
        with self.lock:
            self._remember(*tracks)
            ids = list(dict.fromkeys(t.id for t in tracks))
            p = {"id": str(uuid.uuid4()), "name": name.strip() or "My playlist", "trackIds": ids, "sourceUrl": source_url, "cover": cover}
            self.data["playlists"].append(p)
            self._save()
            return p

    def _playlist(self, pid):
        return next((p for p in self.data["playlists"] if p["id"] == pid), None)

    def add_to_playlist(self, pid: str, tracks: list[Track]):
        with self.lock:
            p = self._playlist(pid)
            if p:
                self._remember(*tracks)
                p["trackIds"] = list(dict.fromkeys(p["trackIds"] + [t.id for t in tracks]))
                self._save()

    def remove_from_playlist(self, pid: str, tid: str):
        with self.lock:
            p = self._playlist(pid)
            if p and tid in p["trackIds"]:
                p["trackIds"].remove(tid)
                self._save()

    def rename_playlist(self, pid: str, name: str):
        with self.lock:
            p = self._playlist(pid)
            if p:
                p["name"] = name.strip() or p["name"]
                self._save()

    def delete_playlist(self, pid: str):
        with self.lock:
            self.data["playlists"] = [p for p in self.data["playlists"] if p["id"] != pid]
            self._save()

    # ------------------------------------------------------------ audio

    def local_file(self, t: Track) -> str | None:
        f = engine.find_audio(OFFLINE_DIR, t.id) or engine.find_audio(AUDIO_CACHE, t.id)
        if f and f.startswith(AUDIO_CACHE):
            try:
                os.utime(f)
            except OSError:
                pass
        return f

    def target(self, t: Track) -> str:
        known = self.track(t.id)
        target = (known.download_target if known else None) or t.download_target
        if target:
            return target
        if t.source != "SPOTIFY":
            raise RuntimeError(f"Nothing to play for {t.title}")
        match = engine.match_on_youtube(t).url
        with self.lock:
            d = self.data["tracks"].get(t.id) or t.to_dict()
            d["matchedUrl"] = match
            self.data["tracks"][t.id] = d
            self._save()
        return match

    def stream(self, t: Track) -> dict | None:
        """A URL the player can stream right away, or None if it must be downloaded first."""
        if t.source in ("TIKTOK", "OTHER"):
            return None
        cached = self._streams.get(t.id)
        if cached and time.time() - cached[1] < 3 * 3600:
            return cached[0]
        self.progress[t.id] = -1
        try:
            info = engine.stream(self.target(t))
        finally:
            self.progress.pop(t.id, None)
        if info:
            self._streams[t.id] = (info, time.time())
        return info

    def stream_info(self, tid: str) -> dict | None:
        c = self._streams.get(tid)
        return c[0] if c else None

    def ensure(self, t: Track) -> str:
        """A local audio file for the song, downloading it to the cache if needed (one download per song)."""
        f = self.local_file(t)
        if f:
            return f
        with self.lock:
            ev = self._inflight.get(t.id)
            owner = ev is None
            if owner:
                ev = self._inflight[t.id] = threading.Event()
        if not owner:
            ev.wait()
            f = self.local_file(t)
            if not f:
                raise RuntimeError(f"Couldn't fetch {t.title}")
            return f
        try:
            self.progress[t.id] = -1
            path = engine.download(self.target(t), AUDIO_CACHE, t.id, lambda p: self.progress.__setitem__(t.id, p))
            self._trim_cache()
            return path
        finally:
            self.progress.pop(t.id, None)
            with self.lock:
                self._inflight.pop(t.id, None)
            ev.set()

    def _trim_cache(self):
        files = [os.path.join(AUDIO_CACHE, f) for f in os.listdir(AUDIO_CACHE)]
        files = sorted((f for f in files if os.path.isfile(f)), key=os.path.getmtime)
        total = sum(os.path.getsize(f) for f in files)
        for f in files:
            if total <= CACHE_LIMIT:
                break
            total -= os.path.getsize(f)
            engine._rm(f)

    def cache_size(self) -> int:
        return sum(os.path.getsize(os.path.join(AUDIO_CACHE, f)) for f in os.listdir(AUDIO_CACHE))

    def clear_cache(self):
        for f in os.listdir(AUDIO_CACHE):
            engine._rm(os.path.join(AUDIO_CACHE, f))

    # ------------------------------------------------------------ offline downloads

    def download_all(self, tracks: list[Track]):
        """Offline downloads, 2 at a time in the background."""
        with self.lock:
            fresh = [t for t in tracks if t.id not in self.offline and t.id not in self._queued]
            if not fresh:
                return
            for t in fresh:
                self._queued.add(t.id)
            self._remember(*fresh)
            self._save()
            if self.queue["total"] - self.queue["done"] - self.queue["failed"] <= 0:
                self.queue = {"total": 0, "done": 0, "failed": 0, "current": None}
            self.queue["total"] += len(fresh)
        for t in fresh:
            self._downloads.submit(self._download_one, t)

    def _download_one(self, t: Track):
        self.queue["current"] = t.title
        ok = False
        try:
            f = self.ensure(t)
            dest = os.path.join(OFFLINE_DIR, os.path.basename(f))
            if os.path.dirname(f) != OFFLINE_DIR:
                shutil.copyfile(f, dest)
                engine._rm(f)
            with self.lock:
                self.offline.add(t.id)
            ok = True
        except Exception as e:  # noqa: BLE001 - reported to the UI
            msg = str(e).strip().splitlines()[-1] if str(e).strip() else type(e).__name__
            self.errors.append(f"Download failed: {t.title}: {msg.removeprefix('ERROR: ')[:160]}")
        finally:
            with self.lock:
                self._queued.discard(t.id)
                self.queue["done" if ok else "failed"] += 1

    def remove_download(self, t: Track):
        for f in os.listdir(OFFLINE_DIR):
            if f.startswith(t.id + "."):
                engine._rm(os.path.join(OFFLINE_DIR, f))
        with self.lock:
            self.offline.discard(t.id)
