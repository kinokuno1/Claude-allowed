"""Local web server between the window (HTML UI) and the engine. Listens on 127.0.0.1 only and requires a
random token, so websites open in your browser can't talk to it."""
from __future__ import annotations

import json
import mimetypes
import os
import re
import secrets
import shutil
import threading
import traceback
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import engine
from .core import Track
from .library import Library
from .lyrics import Lyrics

# In Tunio.exe the UI files are unpacked next to this module (PyInstaller --add-data ...;tunio_desktop/web).
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")


def _tracks(body, key="tracks") -> list[Track]:
    return [Track.from_dict(t) for t in body.get(key) or []]


class Api:
    def __init__(self, lib: Library, lyrics: Lyrics | None = None):
        self.lib = lib
        if lyrics is None:
            from .paths import CACHE_DIR
            lyrics = Lyrics(os.path.join(CACHE_DIR, "lyrics"))
        self.lyrics = lyrics

    def handle(self, path: str, body: dict):
        lib = self.lib
        if path == "state":
            return lib.snapshot()
        if path == "search":
            return {"title": None, "tracks": [t.to_dict() for t in engine.search(body.get("source", "YOUTUBE"), body["q"])]}
        if path == "resolve":
            return engine.resolve(body["text"]).to_dict()
        if path == "prepare":
            return self.prepare(Track.from_dict(body["track"]), prefetch=bool(body.get("prefetch")))
        if path == "lyrics":
            return self.lyrics.get(Track.from_dict(body["track"]))
        if path == "similar":
            sim = engine.similar(Track.from_dict(body["track"]), _tracks(body, "exclude"), int(body.get("max", 10)))
            return {"tracks": [t.to_dict() for t in sim]}
        if path == "like":
            lib.toggle_like(Track.from_dict(body["track"]))
        elif path == "recent":
            lib.add_recent(Track.from_dict(body["track"]))
        elif path == "playlist/create":
            return lib.create_playlist(body.get("name", ""), _tracks(body), body.get("sourceUrl"), body.get("cover"))
        elif path == "playlist/add":
            lib.add_to_playlist(body["id"], _tracks(body))
        elif path == "playlist/remove":
            lib.remove_from_playlist(body["id"], body["trackId"])
        elif path == "playlist/rename":
            lib.rename_playlist(body["id"], body["name"])
        elif path == "playlist/delete":
            lib.delete_playlist(body["id"])
        elif path == "download":
            lib.download_all(_tracks(body))
        elif path == "download/remove":
            lib.remove_download(Track.from_dict(body["track"]))
        elif path == "settings":
            lib.save_settings(**{k: v for k, v in body.items() if k in ("autoplay", "volume")})
        elif path == "cache":
            return {"bytes": lib.cache_size()}
        elif path == "cache/clear":
            lib.clear_cache()
        elif path == "open-folder":
            from .paths import OFFLINE_DIR
            if os.name == "nt":
                os.startfile(OFFLINE_DIR)  # noqa: S606 - opens Explorer on the user's own folder
        else:
            raise KeyError(path)
        return {"ok": True}

    def prepare(self, t: Track, prefetch: bool = False) -> dict:
        """Where the player should load the song from: a local file, a live stream, or (fallback) a fresh download.
        prefetch=True warms up the next song in the queue (not added to "Recently played")."""
        if not prefetch:
            self.lib.add_recent(t)
        if self.lib.local_file(t):
            return {"src": f"/audio/{t.id}", "kind": "file"}
        info = self.lib.stream(t)
        if info:
            return {"src": f"/stream/{t.id}", "kind": "stream"}
        if prefetch:  # warming up only: don't download whole songs speculatively
            return {"src": None, "kind": "none"}
        self.lib.ensure(t)
        return {"src": f"/audio/{t.id}", "kind": "file"}


def make_handler(api: Api, token: str):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):  # quiet
            pass

        def _send(self, code, body: bytes, ctype="application/json", extra=None):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _authorized(self, q) -> bool:
            return secrets.compare_digest(self.headers.get("X-Tunio-Token", "") or (q.get("t") or [""])[0], token)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            u = urlparse(self.path)
            q = parse_qs(u.query)
            if u.path.startswith(("/audio/", "/stream/")):
                if not self._authorized(q):
                    return self._send(403, b"{}")
                tid = u.path.split("/", 2)[2]
                if not re.fullmatch(r"[A-Za-z0-9_-]+", tid):
                    return self._send(400, b"{}")
                return self._audio(tid) if u.path.startswith("/audio/") else self._stream(tid)
            # Static UI files.
            rel = "index.html" if u.path in ("/", "") else u.path.lstrip("/")
            full = os.path.normpath(os.path.join(WEB_DIR, rel))
            if not full.startswith(os.path.normpath(WEB_DIR)) or not os.path.isfile(full):
                return self._send(404, b"not found", "text/plain")
            with open(full, "rb") as f:
                data = f.read()
            ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
            if ctype.startswith("text/") or ctype in ("application/javascript",):
                ctype += "; charset=utf-8"
            self._send(200, data, ctype)

        def do_POST(self):
            u = urlparse(self.path)
            if not u.path.startswith("/api/") or not self._authorized({}):
                return self._send(403, b'{"error":"forbidden"}')
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}")
                result = api.handle(u.path[5:], body)
                self._send(200, json.dumps(result).encode())
            except Exception as e:  # noqa: BLE001 - errors go back to the UI
                msg = (str(e).strip().splitlines() or [type(e).__name__])[-1].removeprefix("ERROR: ")
                if not isinstance(e, (KeyError, ValueError, RuntimeError)):
                    traceback.print_exc()
                self._send(500, json.dumps({"error": msg[:300]}).encode())

        # ---- audio

        def _audio(self, tid):
            t = api.lib.track(tid) or Track(tid, "OTHER", tid)
            path = api.lib.local_file(t)
            if not path:
                return self._send(404, b"{}")
            size = os.path.getsize(path)
            start, end = 0, size - 1
            m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
            status = 200
            if m and (m.group(1) or m.group(2)):
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                else:
                    start = max(0, size - int(m.group(2)))
                status = 206
            end = min(end, size - 1)
            ctype = mimetypes.guess_type(path)[0] or {"opus": "audio/ogg", "m4a": "audio/mp4", "webm": "audio/webm"}.get(
                path.rsplit(".", 1)[-1], "application/octet-stream")
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if self.command == "HEAD":
                return
            try:
                with open(path, "rb") as f:
                    f.seek(start)
                    left = end - start + 1
                    while left > 0:
                        chunk = f.read(min(256 * 1024, left))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

        def _stream(self, tid):
            """Proxies the YouTube/SoundCloud audio (with the headers yt-dlp says it needs), keeping seek support."""
            info = api.lib.stream_info(tid)
            if not info:
                return self._send(404, b"{}")
            headers = dict(info["headers"])
            if self.headers.get("Range"):
                headers["Range"] = self.headers["Range"]
            try:
                r = urllib.request.urlopen(urllib.request.Request(info["url"], headers=headers), timeout=20)
            except urllib.error.HTTPError as e:
                r = e
            with r:
                self.send_response(r.status)
                for h in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"):
                    if r.headers.get(h):
                        self.send_header(h, r.headers[h])
                self.end_headers()
                if self.command == "HEAD":
                    return
                try:
                    shutil.copyfileobj(r, self.wfile, 128 * 1024)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
                    pass

    return Handler


def start(lib: Library | None = None, port: int = 0) -> tuple[ThreadingHTTPServer, str]:
    """Starts the server in the background; returns it and the URL to open (with the token)."""
    # Speed-ups that run while the window opens: load yt-dlp now, fetch Deno once if missing.
    threading.Thread(target=engine.warm_up, daemon=True).start()
    threading.Thread(target=engine.ensure_deno, daemon=True).start()
    token = secrets.token_urlsafe(24)
    api = Api(lib or Library())
    httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(api, token))
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, f"http://127.0.0.1:{httpd.server_address[1]}/?t={token}"
