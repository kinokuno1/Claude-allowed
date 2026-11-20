"""Tunio home server: runs on a Raspberry Pi (or any always-on computer) at home.

The phone asks it for searches, playlists and songs instead of running yt-dlp itself:
- every song played or downloaded through it is kept on the server (original m4a/webm, no conversion), so the
  next time it starts instantly, like Spotify;
- a song it doesn't have yet is fetched and streamed to the phone *while* it downloads (no waiting);
- every night it can fill itself with chart playlists (Spotify "Top 50 Global" by default) and update yt-dlp.

Everything needs the password, except a tiny "is this a Tunio server?" check used by the phone's Wi-Fi search.
Run:  python -m tunio_desktop.homeserver            (settings in <data dir>/server.json)
      python -m tunio_desktop.homeserver --show     (prints address + password, used by the installer)
"""
from __future__ import annotations

import hashlib
import html
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse

from . import core, engine
from .paths import DATA_DIR

VERSION = 1
CONFIG_FILE = os.path.join(DATA_DIR, "server.json")
STATE_FILE = os.path.join(DATA_DIR, "server-state.json")

DEFAULTS = {
    "port": 8484,
    "password": "",
    "music_dir": "",
    "max_gb": 30,
    # Filled every night (any Spotify / YouTube / SoundCloud playlist link works). Default: Spotify Top 50 Global.
    "prefill": ["https://open.spotify.com/playlist/37i9dQZEVXbMDoHDwVN2tF"],
    "prefill_max": 100,
    "night_hour": 4,
}

CTYPES = {"m4a": "audio/mp4", "mp4": "audio/mp4", "webm": "audio/webm", "opus": "audio/ogg", "ogg": "audio/ogg",
          "mp3": "audio/mpeg", "aac": "audio/aac", "flac": "audio/flac", "wav": "audio/wav"}


def log(*a):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, file=sys.stderr, flush=True)


def _read_json(path: str, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def _write_json(path: str, data) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    os.replace(tmp, path)


def make_password() -> str:
    """Easy to type on a phone: 3 groups of 4 letters/digits without look-alikes (0/O, 1/l...)."""
    alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    return "-".join("".join(secrets.choice(alphabet) for _ in range(4)) for _ in range(3))


class Config:
    def __init__(self, path: str = CONFIG_FILE):
        self.path = path
        self.lock = threading.Lock()
        self.data = {**DEFAULTS, **_read_json(path, {})}
        changed = False
        if not self.data.get("password"):
            self.data["password"] = make_password()
            changed = True
        if not self.data.get("music_dir"):
            self.data["music_dir"] = os.path.join(DATA_DIR, "music")
            changed = True
        if changed or not os.path.exists(path):
            self.save()

    def __getitem__(self, k):
        return self.data[k]

    def update(self, **kw):
        with self.lock:
            self.data.update(kw)
            self.save()

    def save(self):
        _write_json(self.path, self.data)


# ---------------------------------------------------------------- song cache

_YT = re.compile(r"(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")


def key_for(url: str) -> str:
    """Same YouTube video = same file, whatever the link looks like. Other sites: a hash of the link."""
    m = _YT.search(url)
    if m and ("youtube" in url or "youtu.be" in url):
        return "yt_" + m.group(1)
    return "u_" + hashlib.sha1(url.strip().encode()).hexdigest()[:20]


class Store:
    """Songs on disk (<key>.<ext>) plus index.json with their names and when they were last used."""

    def __init__(self, folder: str, max_bytes):
        self.dir = folder
        os.makedirs(folder, exist_ok=True)
        self.max_bytes = max_bytes  # callable, so changing the setting applies at once
        self.index_path = os.path.join(folder, "index.json")
        self.lock = threading.RLock()
        self.items: dict[str, dict] = _read_json(self.index_path, {})
        self.dirty = False
        for f in os.listdir(folder):  # leftovers of an interrupted download
            if ".part" in f:
                _rm(os.path.join(folder, f))
        for k in [k for k, v in self.items.items() if not os.path.isfile(os.path.join(folder, v.get("file", "")))]:
            del self.items[k]
        self.save()

    def path(self, key: str) -> str | None:
        with self.lock:
            it = self.items.get(key)
            if not it:
                return None
            p = os.path.join(self.dir, it["file"])
            if not os.path.isfile(p):
                del self.items[key]
                self.dirty = True
                return None
            return p

    def touch(self, key: str):
        with self.lock:
            if key in self.items:
                self.items[key]["used"] = time.time()
                self.dirty = True

    def add(self, key: str, path: str, meta: dict):
        with self.lock:
            now = time.time()
            self.items[key] = {**meta, "file": os.path.basename(path), "size": os.path.getsize(path), "added": now, "used": now}
            self.save()
        self.evict(keep={key})

    def total(self) -> int:
        with self.lock:
            return sum(v.get("size", 0) for v in self.items.values())

    def evict(self, keep=()):
        """Removes the songs not played for the longest time when over the size limit (or the disk gets full)."""
        with self.lock:
            limit = self.max_bytes()
            total = self.total()
            free = shutil.disk_usage(self.dir).free
            reserve = 2 * 1024 ** 3  # always leave 2 GB free on the disk
            for k, v in sorted(self.items.items(), key=lambda kv: kv[1].get("used", 0)):
                if total <= limit and free >= reserve:
                    break
                if k in keep:
                    continue
                _rm(os.path.join(self.dir, v["file"]))
                total -= v.get("size", 0)
                free += v.get("size", 0)
                del self.items[k]
                self.dirty = True
            self.save()

    def save(self):
        with self.lock:
            _write_json(self.index_path, self.items)
            self.dirty = False

    def save_if_dirty(self):
        if self.dirty:
            self.save()

    def recent(self, n=30) -> list[dict]:
        with self.lock:
            return sorted(self.items.values(), key=lambda v: v.get("added", 0), reverse=True)[:n]


def _rm(p):
    try:
        os.remove(p)
    except OSError:
        pass


class Job:
    """One song being fetched. Readers can stream the part already downloaded while the rest arrives."""

    def __init__(self, key: str, url: str, meta: dict):
        self.key, self.url, self.meta = key, url, meta
        self.cond = threading.Condition()
        self.total: int | None = None  # bytes, known once the download started
        self.done = 0
        self.finished = False
        self.error: str | None = None
        self.part: str | None = None
        self.final: str | None = None
        self.ext = "m4a"

    def wait(self, timeout=600) -> bool:
        end = time.time() + timeout
        with self.cond:
            while not self.finished and self.error is None and time.time() < end:
                self.cond.wait(5)
        return self.finished


CHUNK = 8 * 1024 * 1024  # YouTube slows down single huge requests; yt-dlp also asks in ~10 MB pieces


class Fetcher:
    def __init__(self, store: Store, audio_format=None, download=None):
        self.store = store
        self.jobs: dict[str, Job] = {}
        self.lock = threading.Lock()
        self.audio_format = audio_format or engine.audio_format  # replaceable in tests
        self.download = download or engine.download

    def active(self) -> int:
        with self.lock:
            return len(self.jobs)

    def get(self, url: str, meta: dict | None = None) -> Job | str:
        """The cached file path, or the (new or running) job fetching it."""
        key = key_for(url)
        p = self.store.path(key)
        if p:
            self.store.touch(key)
            return p
        with self.lock:
            job = self.jobs.get(key)
            if job is None:
                job = Job(key, url, meta or {})
                self.jobs[key] = job
                threading.Thread(target=self._run, args=(job,), daemon=True, name="fetch-" + key).start()
            return job

    def _run(self, job: Job):
        try:
            info = self.audio_format(job.url)
            meta = {
                "title": info.get("track") or info.get("title") or job.meta.get("title") or "",
                "artist": info.get("artist") or info.get("uploader") or info.get("channel") or job.meta.get("artist") or "",
                "duration": int(info.get("duration") or 0),
                "url": job.url,
                "via": job.meta.get("via", "play"),
            }
            job.ext = (info.get("ext") or "m4a").lower()
            stream = core.stream_info(info)
            if stream:
                path = self._download_http(job, stream["url"], stream["headers"], info.get("filesize"))
            else:  # HLS (some SoundCloud songs), cookies (TikTok)...: let yt-dlp download it, then serve it
                path = self.download(job.url, self.store.dir, job.key + ".dl")
                final = os.path.join(self.store.dir, job.key + "." + path.rsplit(".", 1)[-1])
                os.replace(path, final)
                path = final
                job.ext = path.rsplit(".", 1)[-1]
            self.store.add(job.key, path, meta)
            with job.cond:
                job.final = path
                job.total = job.done = os.path.getsize(path)
                job.finished = True
                job.cond.notify_all()
            log("cached", job.key, meta["artist"], "-", meta["title"])
        except Exception as e:  # noqa: BLE001 - reported to the phone, which then tries itself
            msg = (str(e).strip().splitlines() or [type(e).__name__])[-1].removeprefix("ERROR: ")[:300]
            log("fetch failed", job.key, msg)
            with job.cond:
                job.error = msg or "download failed"
                job.cond.notify_all()
            if job.part:
                _rm(job.part)
        finally:
            with self.lock:
                self.jobs.pop(job.key, None)

    def _download_http(self, job: Job, url: str, headers: dict, size_hint) -> str:
        part = os.path.join(self.store.dir, f"{job.key}.{job.ext}.part")
        final = os.path.join(self.store.dir, f"{job.key}.{job.ext}")
        with open(part, "wb") as f:
            with job.cond:
                job.part = part
                if size_hint:
                    job.total = int(size_hint)
                    job.cond.notify_all()
            pos = 0
            total = job.total
            while total is None or pos < total:
                req = urllib.request.Request(url, headers={**headers, "Range": f"bytes={pos}-{pos + CHUNK - 1}"})
                with urllib.request.urlopen(req, timeout=30) as r:
                    whole = r.status == 200  # server ignored Range: the body is the whole file
                    if whole and pos:
                        raise RuntimeError("the site stopped allowing partial downloads")
                    m = re.match(r"bytes \d+-\d+/(\d+)", r.headers.get("Content-Range") or "")
                    if m:
                        total = int(m.group(1))
                    elif whole and r.headers.get("Content-Length"):
                        total = int(r.headers["Content-Length"])
                    if total is not None and job.total != total:
                        with job.cond:
                            job.total = total
                            job.cond.notify_all()
                    before = pos
                    while True:
                        buf = r.read(64 * 1024)
                        if not buf:
                            break
                        f.write(buf)
                        f.flush()
                        pos += len(buf)
                        with job.cond:
                            job.done = pos
                            job.cond.notify_all()
                if whole:
                    total = pos
                    break
                if pos == before:
                    raise RuntimeError("download stalled")
        if total is not None and pos < total:
            raise RuntimeError("download incomplete")
        os.replace(part, final)
        return final


# ---------------------------------------------------------------- nightly jobs

class Night:
    """Once a night: update yt-dlp (restarting the server on a new version), then fill the cache with charts."""

    def __init__(self, cfg: Config, store: Store, fetcher: Fetcher):
        self.cfg, self.store, self.fetcher = cfg, store, fetcher
        self.state = _read_json(STATE_FILE, {})
        self.running = False
        self.progress = ""

    def _save(self):
        _write_json(STATE_FILE, self.state)

    def loop(self):
        while True:
            time.sleep(300)
            try:
                self.store.save_if_dirty()
                today = time.strftime("%Y-%m-%d")
                if time.localtime().tm_hour != int(self.cfg["night_hour"]):
                    continue
                if self.state.get("updated") != today:
                    self.state["updated"] = today
                    self._save()
                    self.update_ytdlp()
                if self.state.get("filled") != today:
                    self.fill()
            except Exception:  # noqa: BLE001
                traceback.print_exc()

    def update_ytdlp(self):
        if sys.prefix == sys.base_prefix:  # not the installer's private Python: leave it alone
            return
        before = engine.version()
        r = subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-U", "yt-dlp[default]"],
                           capture_output=True, text=True, timeout=600)
        after = subprocess.run([sys.executable, "-c", "from yt_dlp.version import __version__ as v; print(v)"],
                               capture_output=True, text=True, timeout=60).stdout.strip()
        log("yt-dlp", before, "->", after or "?", (r.stderr or "").strip()[-300:])
        if after and after != before:
            while self.fetcher.active():
                time.sleep(5)
            self.store.save()
            log("restarting on the new yt-dlp")
            os._exit(75)  # systemd starts the server again (Restart=always)

    def fill(self):
        """Downloads the songs of the chart playlists that aren't on the server yet (one at a time)."""
        if self.running:
            return
        self.running = True
        added = failed = 0
        try:
            for link in self.cfg["prefill"]:
                try:
                    lst = engine.resolve(link)
                except Exception as e:  # noqa: BLE001
                    log("prefill: can't read", link, e)
                    failed += 1
                    continue
                for i, t in enumerate(lst.tracks[: int(self.cfg["prefill_max"])]):
                    self.progress = f"{lst.title or link}: {i + 1}/{min(len(lst.tracks), int(self.cfg['prefill_max']))}"
                    try:
                        target = t.download_target or engine.match_on_youtube(t).url
                        if not target:
                            continue
                        got = self.fetcher.get(target, {"title": t.title, "artist": t.artist, "via": "charts"})
                        if isinstance(got, Job):
                            if got.wait():
                                added += 1
                            else:
                                failed += 1
                    except Exception as e:  # noqa: BLE001
                        failed += 1
                        log("prefill:", t.search_query, e)
        finally:
            self.running = False
            self.progress = ""
            self.state.update(filled=time.strftime("%Y-%m-%d"), last_fill=time.strftime("%Y-%m-%d %H:%M"),
                              last_fill_result=f"{added} new songs, {failed} failed")
            self._save()
            log("prefill done:", added, "added,", failed, "failed")


# ---------------------------------------------------------------- HTTP

class Server:
    def __init__(self, cfg: Config, fetcher: Fetcher | None = None):
        self.cfg = cfg
        self.store = Store(cfg["music_dir"], lambda: int(float(self.cfg["max_gb"]) * 1024 ** 3))
        self.fetcher = fetcher or Fetcher(self.store)
        self.fetcher.store = self.store
        self.night = Night(cfg, self.store, self.fetcher)
        self.flat_cache: dict[str, tuple[str, float]] = {}

    def authorized(self, handler) -> bool:
        pw = self.cfg["password"]
        auth = handler.headers.get("Authorization", "")
        if auth.startswith("Bearer ") and secrets.compare_digest(auth[7:].strip(), pw):
            return True
        cookie = re.search(r"(?:^|;\s*)tunio=([0-9a-f]{64})", handler.headers.get("Cookie", ""))
        return bool(cookie) and secrets.compare_digest(cookie.group(1), self.cookie_value())

    def cookie_value(self) -> str:
        return hashlib.sha256(("tunio-cookie:" + self.cfg["password"]).encode()).hexdigest()

    def flat(self, url: str, items: str | None) -> str:
        """Search results / playlists, remembered for 10 minutes."""
        key = f"{url}|{items or ''}"
        hit = self.flat_cache.get(key)
        if hit and time.time() - hit[1] < 600:
            return hit[0]
        out = json.dumps(engine.flat(url, items))
        if len(self.flat_cache) > 300:
            self.flat_cache.clear()
        self.flat_cache[key] = (out, time.time())
        return out

    def status(self) -> dict:
        du = shutil.disk_usage(self.store.dir)
        return {
            "tunio": "home-server", "version": VERSION, "ytdlp": engine.version(),
            "songs": len(self.store.items), "bytes": self.store.total(),
            "maxBytes": self.store.max_bytes(), "diskFree": du.free,
            "fetching": self.fetcher.active(),
        }

    def serve_forever(self, host="0.0.0.0"):
        threading.Thread(target=engine.warm_up, daemon=True).start()
        threading.Thread(target=self.night.loop, daemon=True).start()
        httpd = ThreadingHTTPServer((host, int(self.cfg["port"])), make_handler(self))
        httpd.daemon_threads = True
        log(f"Tunio home server on port {self.cfg['port']}, songs in {self.store.dir}")
        httpd.serve_forever()


def make_handler(srv: Server):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "TunioHome"

        def log_message(self, *a):
            pass

        def _send(self, code, body: bytes | str, ctype="application/json", extra=None):
            if isinstance(body, str):
                body = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, obj, code=200):
            self._send(code, json.dumps(obj))

        def _body(self) -> bytes:
            n = int(self.headers.get("Content-Length") or 0)
            return self.rfile.read(n) if n else b""

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            if u.path == "/api/hello":  # no secrets: lets the phone recognise a Tunio server
                return self._json({"tunio": "home-server", "version": VERSION})
            if u.path == "/pair":
                return self._send(200, PAIR_PAGE, "text/html; charset=utf-8")
            if u.path in ("/", "/index.html"):
                if not srv.authorized(self):
                    return self._send(200, page_login(), "text/html; charset=utf-8")
                return self._send(200, page_status(srv, self.headers.get("Host", "")), "text/html; charset=utf-8")
            if not srv.authorized(self):
                return self._json({"error": "wrong password"}, 401)
            if u.path == "/api/ping":
                return self._json(srv.status())
            if u.path == "/audio":
                url = q.get("url", "")
                if not url.startswith(("http://", "https://")):
                    return self._json({"error": "bad url"}, 400)
                return self._audio(url, {"title": q.get("title", ""), "artist": q.get("artist", "")})
            self._json({"error": "not found"}, 404)

        def do_POST(self):
            u = urlparse(self.path)
            if u.path == "/login":
                form = {k: v[0] for k, v in parse_qs(self._body().decode()).items()}
                if secrets.compare_digest(form.get("password", "").strip(), srv.cfg["password"]):
                    cookie = f"tunio={srv.cookie_value()}; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000"
                    return self._send(303, b"", "text/plain", {"Location": "/", "Set-Cookie": cookie})
                return self._send(200, page_login("Wrong password"), "text/html; charset=utf-8")
            if not srv.authorized(self):
                return self._json({"error": "wrong password"}, 401)
            try:
                if u.path == "/api/flat":
                    body = json.loads(self._body() or b"{}")
                    return self._send(200, srv.flat(body["url"], body.get("items")))
                if u.path == "/api/warm":
                    body = json.loads(self._body() or b"{}")
                    for url in (body.get("urls") or [])[:6]:
                        if isinstance(url, str) and url.startswith(("http://", "https://")) and srv.fetcher.active() < 6:
                            srv.fetcher.get(url, {"via": "play"})
                    return self._json({"ok": True})
                if u.path == "/settings":
                    form = {k: v[0] for k, v in parse_qs(self._body().decode()).items()}
                    links = [s.strip() for s in form.get("prefill", "").splitlines() if s.strip().startswith("http")]
                    gb = max(1.0, float(form.get("max_gb") or srv.cfg["max_gb"]))
                    srv.cfg.update(prefill=links, max_gb=gb)
                    threading.Thread(target=srv.store.evict, daemon=True).start()
                    return self._send(303, b"", "text/plain", {"Location": "/"})
                if u.path == "/fill":
                    threading.Thread(target=srv.night.fill, daemon=True).start()
                    return self._send(303, b"", "text/plain", {"Location": "/"})
            except Exception as e:  # noqa: BLE001 - goes back to the phone, which falls back to itself
                msg = (str(e).strip().splitlines() or [type(e).__name__])[-1].removeprefix("ERROR: ")
                return self._json({"error": msg[:300]}, 502)
            self._json({"error": "not found"}, 404)

        # ---- audio

        def _range(self, size: int) -> tuple[int, int, bool]:
            m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
            if not m or not (m.group(1) or m.group(2)):
                return 0, size - 1, False
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start, end = max(0, size - int(m.group(2))), size - 1
            return start, min(end, size - 1), True

        def _head(self, size: int, ext: str) -> tuple[int, int] | None:
            start, end, partial = self._range(size)
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", CTYPES.get(ext, "application/octet-stream"))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("X-Tunio-Ext", ext)
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            return start, end

        def _file(self, path: str):
            span = self._head(os.path.getsize(path), path.rsplit(".", 1)[-1].lower())
            if not span or self.command == "HEAD":
                return
            start, end = span
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(256 * 1024, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)

        def _audio(self, url: str, meta: dict):
            try:
                got = srv.fetcher.get(url, meta)
                if isinstance(got, str):
                    return self._file(got)
                job = got
                with job.cond:
                    end_wait = time.time() + 90
                    while job.total is None and not job.finished and job.error is None and time.time() < end_wait:
                        job.cond.wait(5)
                if job.error:
                    return self._json({"error": job.error}, 502)
                if job.finished and job.final:
                    return self._file(job.final)
                if job.total is None:
                    return self._json({"error": "the site took too long to answer"}, 504)
                self._growing(job)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

        def _growing(self, job: Job):
            """Streams a song that is still downloading: sends each part as soon as it has arrived."""
            span = self._head(job.total, job.ext)
            if not span or self.command == "HEAD":
                return
            start, end = span
            try:
                f = open(job.part, "rb")
            except (OSError, TypeError):
                f = open(job.final or "", "rb")
            with f:
                pos = start
                while pos <= end:
                    with job.cond:
                        deadline = time.time() + 60
                        while job.done <= pos and not job.finished and job.error is None and time.time() < deadline:
                            job.cond.wait(5)
                        avail = job.done
                        if avail <= pos:  # failed or stalled: drop the connection, the phone retries/falls back
                            self.close_connection = True
                            return
                    f.seek(pos)
                    data = f.read(min(256 * 1024, end + 1 - pos, avail - pos))
                    if not data:
                        self.close_connection = True
                        return
                    self.wfile.write(data)
                    pos += len(data)

    return Handler


# ---------------------------------------------------------------- pages (status page in a browser)

STYLE = """<meta name="viewport" content="width=device-width,initial-scale=1"><style>
:root{color-scheme:dark}body{margin:0;font:16px/1.5 system-ui,sans-serif;color:#F7F7FA;
background:linear-gradient(135deg,#1E1C28,#0E0E12 60%,#2a1530);min-height:100vh}
main{max-width:640px;margin:0 auto;padding:24px 16px}h1{margin:0 0 4px}.muted{color:#C8C8D2;font-size:14px}
.card{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.14);border-radius:20px;padding:16px 18px;margin:14px 0}
input,textarea{width:100%;box-sizing:border-box;background:rgba(0,0,0,.35);color:#F7F7FA;border:1px solid rgba(255,255,255,.2);
border-radius:12px;padding:10px;font:inherit}textarea{min-height:90px;font-size:13px}
button,.btn{display:inline-block;margin-top:10px;background:linear-gradient(135deg,#7C4DEE,#D4337F);color:#fff;border:0;
border-radius:999px;padding:10px 18px;font:600 15px system-ui;text-decoration:none;cursor:pointer}
.ghost{background:rgba(255,255,255,.12)}ul{padding-left:18px;margin:6px 0}li{font-size:14px}.big{font-size:28px;font-weight:700}
.row{display:flex;gap:24px;flex-wrap:wrap}</style>"""


def page_login(error: str = "") -> str:
    err = f'<p style="color:#FECACA">{html.escape(error)}</p>' if error else ""
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>Tunio server</title>{STYLE}</head><body><main>
<h1>Tunio server</h1><p class="muted">Enter the password the installer showed you.</p>{err}
<form method="post" action="/login" class="card"><input name="password" type="password" autofocus
placeholder="xxxx-xxxx-xxxx" autocomplete="current-password"><button>Open</button></form></main></body></html>"""


def _gb(b) -> str:
    return f"{b / 1024 ** 3:.1f} GB"


def page_status(srv: Server, host: str) -> str:
    s = srv.status()
    st = srv.night.state
    origin = "http://" + (host or f"{lan_ip()}:{srv.cfg['port']}")
    pair = f"tunio://pair?u={quote(origin, safe='')}&k={quote(srv.cfg['password'], safe='')}"
    recent = "".join(
        f"<li>{html.escape(v.get('artist') or '')} – {html.escape(v.get('title') or v['file'])}"
        f"{' <span class=muted>(charts)</span>' if v.get('via') == 'charts' else ''}</li>"
        for v in srv.store.recent(25)) or "<li class=muted>Nothing yet: play a song on your phone.</li>"
    filling = f"<p>Filling now: {html.escape(srv.night.progress)}</p>" if srv.night.running else ""
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>Tunio server</title>{STYLE}</head><body><main>
<h1>Tunio server</h1><p class="muted">Address for the app: <b>{html.escape(origin)}</b> · yt-dlp {html.escape(s['ytdlp'])}</p>
<div class="card row"><div><div class="big">{s['songs']}</div><div class="muted">songs ready</div></div>
<div><div class="big">{_gb(s['bytes'])}</div><div class="muted">of {_gb(s['maxBytes'])} max</div></div>
<div><div class="big">{_gb(s['diskFree'])}</div><div class="muted">free on disk</div></div></div>
<div class="card"><b>Connect your phone</b><p class="muted">Open this page on your phone and tap the button
(Tunio must be installed).</p><a class="btn" href="{html.escape(pair)}">Connect Tunio on this phone</a></div>
<form class="card" method="post" action="/settings"><b>Nightly charts</b>
<p class="muted">Every night at {int(srv.cfg['night_hour'])}:00 the server downloads the songs of these playlists
(Spotify, YouTube or SoundCloud links, one per line, up to {int(srv.cfg['prefill_max'])} songs each).
Last run: {html.escape(st.get('last_fill', 'never'))} {html.escape(st.get('last_fill_result', ''))}</p>{filling}
<textarea name="prefill">{html.escape(chr(10).join(srv.cfg['prefill']))}</textarea>
<p class="muted">Maximum space for songs (GB). The songs not played for the longest time are removed first.</p>
<input name="max_gb" type="number" min="1" step="1" value="{float(srv.cfg['max_gb']):g}">
<button>Save</button></form>
<form method="post" action="/fill"><button class="ghost">Fill with the charts now</button></form>
<div class="card"><b>Latest songs</b><ul>{recent}</ul></div></main></body></html>"""


PAIR_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>Connect Tunio</title>""" + STYLE + """</head>
<body><main><h1>Connect Tunio</h1><p class="muted">Tap the button to connect the Tunio app on this phone to your
server.</p><a class="btn" id="go" href="#">Connect Tunio</a>
<script>var k=decodeURIComponent(location.hash.slice(1));document.getElementById('go').href=
'tunio://pair?u='+encodeURIComponent(location.origin)+'&k='+encodeURIComponent(k);</script></main></body></html>"""


def lan_ip() -> str:
    """This computer's address on the home network (no traffic is sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.0.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    cfg = Config()
    if argv and argv[0] == "--show":
        print(json.dumps({"ip": lan_ip(), "port": cfg["port"], "password": cfg["password"], "music_dir": cfg["music_dir"]}))
        return
    if argv and argv[0] == "--set":
        for kv in argv[1:]:
            k, _, v = kv.partition("=")
            cfg.update(**{k: int(v) if v.isdigit() else v})
        return
    Server(cfg).serve_forever()


if __name__ == "__main__":
    main()
