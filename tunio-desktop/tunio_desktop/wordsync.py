"""Word-by-word lyrics timing, looked up only when you turn on Word or 3D mode in the lyrics view.

Sources, best first (all free, no account):
  1. Musixmatch "richsync" (word timings), through the desktop app's anonymous token, like Spicetify's lyrics app.
  2. NetEase Cloud Music "yrc" (word timings), which also covers most Western songs.
  3. Estimated: LRCLIB's line timings, with each line's time shared among its words by their length.

Result: {"lines": [{"t": start, "e": end, "words": [[start, end, "word "], ...]}], "source": "...", "estimated": bool}"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from .core import Track
from .lyrics import guesses

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
MXM = "https://apic-desktop.musixmatch.com/ws/1.1/"
MXM_APP = "web-desktop-app-v1.0"
MISS_TTL = 24 * 3600


def _get_json(url: str, headers: dict | None = None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=12) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def _close(t: Track, artist: str, title: str, dur: float | None) -> bool:
    if t.durationSec and dur and abs(dur - t.durationSec) > 8:
        return False
    a, n = _norm(artist), _norm(title)
    return bool(n) and any(_norm(s) and (_norm(s) in n or n in _norm(s)) for _, s in guesses(t)) and \
        (not a or any(_norm(x) and (_norm(x) in a or a in _norm(x)) for x, _ in guesses(t)))


# ---------------------------------------------------------------- 1. Musixmatch richsync

def parse_richsync(body: str) -> list[dict]:
    """[{"ts": 12.3, "te": 15.6, "l": [{"c": "Hello", "o": 0.0}, {"c": " ", "o": 0.4}, ...]}] -> lines of words."""
    out = []
    for line in json.loads(body):
        ts, te = float(line.get("ts", 0)), float(line.get("te", 0))
        parts = [(ts + float(p.get("o", 0)), p.get("c", "")) for p in line.get("l") or []]
        words, cur = [], None
        for start, text in parts:  # glue spaces onto the word before them
            if text.strip() == "" and cur is not None:
                cur[2] += text
                continue
            cur = [round(start, 3), None, text]
            words.append(cur)
        for i, w in enumerate(words):
            w[1] = round(words[i + 1][0] if i + 1 < len(words) else te, 3)
        if words:
            out.append({"t": round(ts, 3), "e": round(te, 3), "words": words})
    return out


class _Musixmatch:
    def __init__(self, cache_dir: str, http=_get_json):
        self.http, self.file = http, os.path.join(cache_dir, "mxm-token.json")
        self.token = None
        try:
            with open(self.file, encoding="utf-8") as f:
                d = json.load(f)
            if time.time() - d.get("at", 0) < 6 * 3600:
                self.token = d.get("token")
        except (OSError, ValueError):
            pass

    def _call(self, method: str, params: dict):
        if not self.token:
            r = self.http(f"{MXM}token.get?app_id={MXM_APP}&user_language=en&t={int(time.time() * 1000)}",
                          {"Cookie": "AWSELBCORS=0; AWSELB=0"})
            self.token = (((r or {}).get("message") or {}).get("body") or {}).get("user_token")
            if not self.token or "UpgradeOnlyUpgradeOnly" in self.token:
                self.token = None
                raise RuntimeError("Musixmatch is busy")
            try:
                with open(self.file, "w", encoding="utf-8") as f:
                    json.dump({"token": self.token, "at": time.time()}, f)
            except OSError:
                pass
        q = urllib.parse.urlencode({**params, "app_id": MXM_APP, "usertoken": self.token, "t": int(time.time() * 1000)})
        r = self.http(f"{MXM}{method}?{q}", {"Cookie": "AWSELBCORS=0; AWSELB=0"})
        msg = (r or {}).get("message") or {}
        if (msg.get("header") or {}).get("status_code") == 401:
            self.token = None  # expired or rate-limited: a new token next time
        return msg

    def words(self, t: Track) -> list[dict] | None:
        for artist, title in guesses(t)[:2]:
            p = {"q_track": title, "q_artist": artist}
            if t.durationSec:
                p["q_duration"] = t.durationSec
            tr = (self._call("matcher.track.get", p).get("body") or {}).get("track") or {}
            if not tr.get("track_id") or not tr.get("has_richsync"):
                continue
            if not _close(t, tr.get("artist_name", ""), tr.get("track_name", ""), tr.get("track_length")):
                continue
            rs = (self._call("track.richsync.get", {"track_id": tr["track_id"]}).get("body") or {}).get("richsync") or {}
            if rs.get("richsync_body"):
                lines = parse_richsync(rs["richsync_body"])
                if lines:
                    return lines
        return None


# ---------------------------------------------------------------- 2. NetEase yrc

_YRC_LINE = re.compile(r"^\[(\d+),(\d+)\](.*)$")
_YRC_WORD = re.compile(r"\((\d+),(\d+),\d+\)([^(]*)")


def parse_yrc(yrc: str) -> list[dict]:
    """'[12340,3450](12340,500,0)Hello (12840,400,0)world' lines (milliseconds) -> lines of words."""
    out = []
    for raw in (yrc or "").splitlines():
        m = _YRC_LINE.match(raw.strip())
        if not m:
            continue  # metadata lines are JSON
        start, dur = int(m.group(1)) / 1000, int(m.group(2)) / 1000
        words = [[round(int(a) / 1000, 3), round((int(a) + int(b)) / 1000, 3), w] for a, b, w in _YRC_WORD.findall(m.group(3)) if w]
        if words:
            out.append({"t": round(start, 3), "e": round(start + dur, 3), "words": words})
    return out


def _netease(t: Track, http=_get_json) -> list[dict] | None:
    hdr = {"Referer": "https://music.163.com/"}
    for artist, title in guesses(t)[:2]:
        q = urllib.parse.urlencode({"s": f"{artist} {title}", "type": 1, "limit": 6})
        songs = ((http(f"https://music.163.com/api/search/get?{q}", hdr) or {}).get("result") or {}).get("songs") or []
        for s in songs:
            artists = " ".join(a.get("name", "") for a in s.get("artists") or [])
            if not _close(t, artists, s.get("name", ""), (s.get("duration") or 0) / 1000):
                continue
            r = http(f"https://music.163.com/api/song/lyric/v1?id={s['id']}&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0", hdr) or {}
            lines = parse_yrc((r.get("yrc") or {}).get("lyric") or "")
            if lines:
                return lines
    return None


# ---------------------------------------------------------------- 3. estimated from line timings

def estimate(synced: list[list], song_end: float = 0) -> list[dict]:
    """Shares each line's time among its words by their length (a word sung faster than ~0.12 s/char is rare)."""
    out = []
    for i, (start, text) in enumerate(synced):
        if not text.strip():
            continue
        nxt = synced[i + 1][0] if i + 1 < len(synced) else (song_end or start + 5)
        words = re.findall(r"\S+\s*", text)
        weight = [len(w.strip()) + 1.5 for w in words]
        span = max(0.4, min(nxt - start, sum(weight) * 0.12 + 0.8, 12))  # don't stretch over long pauses
        t, line = start, []
        for w, k in zip(words, weight):
            d = span * k / sum(weight)
            line.append([round(t, 3), round(t + d, 3), w])
            t += d
        out.append({"t": round(start, 3), "e": round(start + span, 3), "words": line})
    return out


class WordSync:
    """Disk-cached lookups (thread-safe). `line_lyrics` gives LRCLIB's result for the estimate."""

    def __init__(self, folder: str, http=_get_json):
        self.folder, self.http = folder, http
        os.makedirs(folder, exist_ok=True)
        self.mxm = _Musixmatch(folder, http)
        self._lock = threading.Lock()

    def get(self, t: Track, line_lyrics: dict) -> dict:
        path = os.path.join(self.folder, re.sub(r"[^A-Za-z0-9_-]", "_", t.id) + ".words.json")
        with self._lock:
            try:
                with open(path, encoding="utf-8") as f:
                    hit = json.load(f)
                if not hit.get("estimated") or time.time() - hit.get("at", 0) < MISS_TTL:
                    return hit
            except (OSError, ValueError):
                pass
        res = None
        for name, fn in (("Musixmatch", self.mxm.words), ("NetEase", lambda tr: _netease(tr, self.http))):
            try:
                lines = fn(t)
            except Exception:  # noqa: BLE001 - one source failing shouldn't stop the others
                lines = None
            if lines:
                res = {"lines": lines, "source": name, "estimated": False}
                break
        if res is None:
            synced = (line_lyrics or {}).get("synced")
            if not synced:
                return {"lines": [], "source": None, "estimated": True, "error": "This song has no synced lyrics to follow word by word."}
            res = {"lines": estimate(synced, t.durationSec), "source": "LRCLIB (word timing estimated)", "estimated": True}
        res["at"] = time.time()
        with self._lock:
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(res, f)
            os.replace(tmp, path)
        return res
