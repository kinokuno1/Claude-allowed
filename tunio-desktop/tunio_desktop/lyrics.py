"""Song lyrics from LRCLIB (https://lrclib.net): a free, community-made lyrics database with no API key.
Most songs have *synced* lyrics (each line has a time), which the UI highlights as the song plays.

YouTube titles are messy ("Artist - Song (Official Video) [4K]", channels like "ArtistVEVO" or
"Artist - Topic"), so the title is cleaned up first and several lookups are tried, best match first."""
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

API = "https://lrclib.net/api"
UA = "Tunio/1.0 (personal music player)"
MISS_TTL = 24 * 3600  # "no lyrics" answers are retried after a day; found lyrics are kept

# Bits of YouTube titles that are never part of the song name.
_JUNK = re.compile(
    r"\s*[\(\[【]([^\)\]】]*?\b(official|video|audio|lyrics?|lyric video|visuali[sz]er|music video|mv|hd|hq|4k|"
    r"remaster(ed)?|live|explicit|clean|color coded|prod\.?|full album|topic)\b[^\)\]】]*)[\)\]】]",
    re.I)
_FEAT = re.compile(r"\s*[\(\[]?\s*\b(feat\.?|ft\.?|featuring|with)\s+[^\)\]\-]*[\)\]]?", re.I)
_CHANNEL = re.compile(r"(\s*-\s*topic|vevo|\s*official( channel)?|\s*music)$", re.I)
_TAIL = re.compile(r"\s*(\||//|#).*$")


def _clean(s: str) -> str:
    s = _JUNK.sub("", s or "")
    s = _TAIL.sub("", s)
    s = re.sub(r"[\"“”]|\s+", lambda m: " " if m.group().isspace() else "", s)
    return s.strip(" -–—_.")


def guesses(t: Track) -> list[tuple[str, str]]:
    """(artist, song) pairs to look up, most likely first."""
    title, artist = _clean(t.title), _CHANNEL.sub("", (t.artist or "").strip()).strip()
    out: list[tuple[str, str]] = []
    m = re.match(r"^(.+?)\s+[-–—]\s+(.+)$", title)
    if m:  # "Artist - Song" is the usual YouTube format, and wins over the channel name
        a, s = m.group(1).strip(), m.group(2).strip()
        out += [(a, s), (a, _FEAT.sub("", s).strip()), (_FEAT.sub("", a).strip(), _FEAT.sub("", s).strip())]
    if artist:
        out += [(artist, title), (artist, _FEAT.sub("", title).strip())]
    seen, res = set(), []
    for a, s in out:
        k = (a.lower(), s.lower())
        if a and s and k not in seen:
            seen.add(k)
            res.append((a, s))
    return res


def parse_lrc(lrc: str) -> list[list]:
    """'[01:02.34] text' lines -> [[62.34, 'text'], ...], sorted by time (a line can carry several stamps)."""
    out = []
    for line in (lrc or "").splitlines():
        stamps = re.findall(r"\[(\d+):(\d+(?:[.:]\d+)?)\]", line)
        text = re.sub(r"\[\d+:\d+(?:[.:]\d+)?\]", "", line).strip()
        for m, s in stamps:
            out.append([round(int(m) * 60 + float(s.replace(":", ".")), 2), text])
    out.sort(key=lambda x: x[0])
    return out


def _http(path: str, params: dict):
    url = f"{API}/{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def _score(rec: dict, t: Track, artist: str, song: str) -> float:
    """Higher is better; None-like (-1) when the length is clearly a different recording."""
    d = rec.get("duration") or 0
    if t.durationSec and d and abs(d - t.durationSec) > 12:
        return -1
    sc = 0.0
    if rec.get("syncedLyrics"):
        sc += 3
    elif rec.get("plainLyrics"):
        sc += 1
    if _norm(song) and _norm(song) in _norm(rec.get("trackName")):
        sc += 2
    if _norm(artist) and (_norm(artist) in _norm(rec.get("artistName")) or _norm(rec.get("artistName")) in _norm(artist)):
        sc += 2
    if t.durationSec and d:
        sc += max(0.0, 1 - abs(d - t.durationSec) / 12)
    return sc


def _result(rec: dict | None, artist="", song="") -> dict:
    if not rec:
        return {"found": False, "synced": None, "plain": None, "instrumental": False, "source": "LRCLIB"}
    synced = parse_lrc(rec.get("syncedLyrics") or "") or None
    return {"found": True, "synced": synced, "plain": rec.get("plainLyrics") or None, "instrumental": bool(rec.get("instrumental")),
            "track": rec.get("trackName") or song, "artist": rec.get("artistName") or artist, "source": "LRCLIB"}


def lookup(t: Track, http=_http) -> dict:
    """Best lyrics for a track: exact match first, then searches; prefers synced lyrics of the same length."""
    pairs = guesses(t)
    best, best_sc = None, -1.0
    for a, s in pairs[:2]:  # exact lookups (fast, cached by LRCLIB)
        params = {"artist_name": a, "track_name": s}
        if t.durationSec:
            params["duration"] = t.durationSec
        rec = http("get", params)
        if rec and (rec.get("syncedLyrics") or rec.get("instrumental")):
            return _result(rec, a, s)
        if rec and _score(rec, t, a, s) > best_sc:
            best, best_sc = rec, _score(rec, t, a, s)
    queries = [{"track_name": s, "artist_name": a} for a, s in pairs[:3]] + [{"q": _clean(f"{t.artist} {t.title}")}]
    for q in queries:
        for rec in (http("search", q) or [])[:15]:
            a, s = q.get("artist_name", t.artist), q.get("track_name", t.title)
            sc = _score(rec, t, a, s)
            if sc > best_sc:
                best, best_sc = rec, sc
        if best and best.get("syncedLyrics") and best_sc >= 6:
            break
    return _result(best if best_sc >= 3 else None)


class Lyrics:
    """Lookups with a disk cache (so a song's lyrics load instantly the next time, also offline)."""

    def __init__(self, folder: str, http=_http):
        self.folder, self.http = folder, http
        os.makedirs(folder, exist_ok=True)
        self._locks: dict[str, threading.Lock] = {}
        self._guard = threading.Lock()

    def _file(self, tid: str) -> str:
        return os.path.join(self.folder, re.sub(r"[^A-Za-z0-9_-]", "_", tid) + ".json")

    def get(self, t: Track) -> dict:
        with self._guard:
            lock = self._locks.setdefault(t.id, threading.Lock())
        with lock:  # one lookup per song, even if the UI asks twice
            path = self._file(t.id)
            try:
                with open(path, encoding="utf-8") as f:
                    hit = json.load(f)
                if hit.get("found") or time.time() - hit.get("at", 0) < MISS_TTL:
                    return hit
            except (OSError, ValueError):
                pass
            res = lookup(t, self.http)
            res["at"] = time.time()
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(res, f)
            os.replace(tmp, path)
            return res
