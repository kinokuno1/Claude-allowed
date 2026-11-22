"""Song artwork for songs that have none, a wrong one or a broken one.

Spotify playlists only list each song's name, so imported songs used to show the playlist's cover.
Each song's own cover comes from free sources without an account, best first:
  - Spotify songs: Spotify's public oEmbed for the song link (its album cover)
  - YouTube videos: the video thumbnail (always exists, never expires)
  - anything else: the iTunes Search API, then Deezer's public search, by artist and title
Answers are cached on disk; "not found" is retried after a day."""
from __future__ import annotations

import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from .core import Track
from .lyrics import guesses

UA = "Tunio/1.0 (personal music player)"
MISS_TTL = 24 * 3600
_YT_ID = re.compile(r"(?:v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})")
_SP_TRACK = re.compile(r"open\.spotify\.com/track/([A-Za-z0-9]+)")


def _get_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def youtube_id(t: Track) -> str | None:
    if t.source == "YOUTUBE" and t.id.startswith("yt_") and len(t.id) == 14:
        return t.id[3:]
    for u in (t.url, t.matchedUrl):
        m = _YT_ID.search(u or "")
        if m:
            return m.group(1)
    return None


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def _matches(want_artist: str, want_title: str, artist: str, title: str) -> bool:
    wt, t = _norm(want_title), _norm(title)
    if not wt or not t or not (wt in t or t in wt):
        return False
    wa, a = set(_norm(want_artist).split()), set(_norm(artist).split())
    return not wa or bool(wa & a)


def _spotify(t: Track, http) -> str | None:
    m = _SP_TRACK.search(t.url or "")
    if not m:
        return None
    page = f"https://open.spotify.com/track/{m.group(1)}"
    o = http("https://open.spotify.com/oembed?url=" + urllib.parse.quote(page, safe=""))
    return (o or {}).get("thumbnail_url")


def _itunes(artist: str, title: str, http) -> str | None:
    q = urllib.parse.urlencode({"term": f"{artist} {title}".strip(), "media": "music", "entity": "song", "limit": 8})
    for r in (http(f"https://itunes.apple.com/search?{q}") or {}).get("results") or []:
        art = r.get("artworkUrl100")
        if art and _matches(artist, title, r.get("artistName", ""), r.get("trackName", "")):
            return art.replace("100x100bb", "600x600bb")
    return None


def _deezer(artist: str, title: str, http) -> str | None:
    q = f'artist:"{artist}" track:"{title}"' if artist else title
    data = (http("https://api.deezer.com/search?" + urllib.parse.urlencode({"q": q, "limit": 5})) or {}).get("data") or []
    for r in data:
        cover = (r.get("album") or {}).get("cover_xl")
        if cover and _matches(artist, title, (r.get("artist") or {}).get("name", ""), r.get("title", "")):
            return cover
    return None


def lookup(t: Track, broken: str | None = None, http=_get_json) -> str | None:
    """The best cover for a song. `broken` is an image URL that failed to load, never returned again."""
    found: list[str] = []

    def ok(u):
        if u and u != broken:
            found.append(u)
            return True
        return False

    def safe(fn, *a):
        try:
            return fn(*a)
        except Exception:  # noqa: BLE001 - one source failing shouldn't stop the others
            return None

    if t.source == "SPOTIFY" and ok(safe(_spotify, t, http)):
        return found[0]
    vid = youtube_id(t)
    if t.source == "YOUTUBE" and vid and ok(f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"):
        return found[0]
    for artist, title in guesses(t)[:3]:
        if ok(safe(_itunes, artist, title, http)) or ok(safe(_deezer, artist, title, http)):
            return found[0]
    if vid and ok(f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"):  # a matched Spotify song: its YouTube video
        return found[0]
    return None


class Artwork:
    """Disk-cached lookups (thread-safe)."""

    def __init__(self, cache_file: str, http=_get_json):
        self.file, self.http = cache_file, http
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(6, thread_name_prefix="artwork")
        try:
            with open(cache_file, encoding="utf-8") as f:
                self.cache: dict = json.load(f)
        except (OSError, ValueError):
            self.cache = {}

    def _save(self):
        tmp = self.file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.cache, f)
        os.replace(tmp, self.file)

    def get(self, t: Track, broken: str | None = None) -> str | None:
        with self.lock:
            hit = self.cache.get(t.id)
        if hit and not broken:
            if hit.get("url"):
                return hit["url"]
            if time.time() - hit.get("at", 0) < MISS_TTL:
                return None
        if hit and broken and hit.get("url") and hit["url"] != broken:
            return hit["url"]
        url = lookup(t, broken, self.http)
        with self.lock:
            self.cache[t.id] = {"url": url, "at": time.time()}
            self._save()
        return url

    def many(self, tracks: list[Track]) -> dict[str, str]:
        """Covers for several songs at once (in parallel)."""
        urls = list(self.pool.map(lambda t: self.get(t), tracks))
        return {t.id: u for t, u in zip(tracks, urls) if u}
