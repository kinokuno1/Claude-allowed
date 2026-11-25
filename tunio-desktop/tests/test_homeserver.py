"""Home server: password, cache, and streaming a song while it is still downloading (no real sites involved)."""
import json
import os
import re
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.environ.setdefault("TUNIO_DATA_DIR", tempfile.mkdtemp())
os.environ.setdefault("TUNIO_CACHE_DIR", tempfile.mkdtemp())

from tunio_desktop import homeserver as hs  # noqa: E402

SONG = bytes((i * 7) % 256 for i in range(3 * 1024 * 1024 + 123))


class FakeSite(BaseHTTPRequestHandler):
    """Like googlevideo: honours Range, sends slowly so the server is still downloading while the phone reads."""
    protocol_version = "HTTP/1.1"
    hits = 0

    def log_message(self, *a):
        pass

    def do_GET(self):
        FakeSite.hits += 1
        m = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        start = int(m.group(1)) if m else 0
        end = min(int(m.group(2)) if m and m.group(2) else len(SONG) - 1, len(SONG) - 1)
        self.send_response(206 if m else 200)
        self.send_header("Content-Length", str(end - start + 1))
        if m:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(SONG)}")
        self.end_headers()
        for i in range(start, end + 1, 256 * 1024):
            self.wfile.write(SONG[i:min(end + 1, i + 256 * 1024)])
            time.sleep(0.02)


def serve(handler):
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, f"http://127.0.0.1:{httpd.server_address[1]}"


def make_server(site_url):
    d = tempfile.mkdtemp()
    cfg = hs.Config(os.path.join(d, "server.json"))
    cfg.update(music_dir=os.path.join(d, "music"), password="abcd-efgh-jkmn")
    calls = []

    def fake_format(url):
        calls.append(url)
        return {"url": site_url + "/song", "protocol": "http", "ext": "m4a", "http_headers": {}, "title": "Song",
                "artist": "Band", "duration": 200}

    srv = hs.Server(cfg)
    srv.fetcher.audio_format = fake_format
    httpd, base = serve(hs.make_handler(srv))
    return srv, base, calls


def get(url, password="abcd-efgh-jkmn", headers=None):
    h = {"Authorization": "Bearer " + password} if password else {}
    h.update(headers or {})
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=30)


def test_key_for_youtube_variants():
    assert hs.key_for("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == "yt_dQw4w9WgXcQ"
    assert hs.key_for("https://youtu.be/dQw4w9WgXcQ?si=x") == "yt_dQw4w9WgXcQ"
    assert hs.key_for("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD") == "yt_dQw4w9WgXcQ"
    assert hs.key_for("https://soundcloud.com/a/b").startswith("u_")


def test_password_required():
    _, site = serve(FakeSite)
    _, base, _ = make_server(site)
    assert json.load(get(base + "/api/hello", password=None))["tunio"] == "home-server"
    for pw in (None, "wrong"):
        try:
            get(base + "/api/ping", password=pw)
            raise AssertionError("should need the password")
        except urllib.error.HTTPError as e:
            assert e.code == 401
    assert json.load(get(base + "/api/ping"))["songs"] == 0


def test_streams_while_downloading_then_serves_from_cache():
    _, site = serve(FakeSite)
    srv, base, calls = make_server(site)
    yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    audio = base + "/audio?url=" + urllib.request.quote(yt, safe="")

    t0 = time.time()
    r = get(audio)
    first = r.read(1024)
    assert time.time() - t0 < 1.5, "first bytes should arrive before the download finishes"
    assert r.headers["X-Tunio-Ext"] == "m4a"
    assert int(r.headers["Content-Length"]) == len(SONG)
    body = first + r.read()
    assert body == SONG

    # A seek (Range) while/after downloading.
    r = get(audio, headers={"Range": "bytes=1000000-1000099"})
    assert r.status == 206 and r.read() == SONG[1000000:1000100]

    for _ in range(50):
        if srv.store.path("yt_dQw4w9WgXcQ"):
            break
        time.sleep(0.1)
    assert srv.store.path("yt_dQw4w9WgXcQ")
    hits = FakeSite.hits
    r = get(audio, headers={"Range": "bytes=-10"})
    assert r.status == 206 and r.read() == SONG[-10:]
    assert FakeSite.hits == hits, "cached songs must not touch the site again"
    assert len(calls) == 1
    assert srv.store.items["yt_dQw4w9WgXcQ"]["title"] == "Song"


def test_eviction_keeps_recent():
    d = tempfile.mkdtemp()
    limit = [1000]
    store = hs.Store(d, lambda: limit[0])
    for i, k in enumerate(["a", "b", "c"]):
        p = os.path.join(d, k + ".m4a")
        with open(p, "wb") as f:
            f.write(b"x" * 10)
        store.add(k, p, {})
        store.items[k]["used"] = i  # a = oldest
    store.touch("a")
    limit[0] = 25
    store.evict()
    assert set(store.items) == {"a", "c"}
    assert not os.path.exists(os.path.join(d, "b.m4a"))


def test_failed_fetch_reports_error():
    _, site = serve(FakeSite)
    srv, base, _ = make_server(site)

    def broken(url):
        raise RuntimeError("ERROR: Video unavailable")

    srv.fetcher.audio_format = broken
    try:
        get(base + "/audio?url=" + urllib.request.quote("https://youtu.be/aaaaaaaaaaa", safe=""))
        raise AssertionError("should fail")
    except urllib.error.HTTPError as e:
        assert e.code == 502
        assert "Video unavailable" in json.load(e)["error"]
