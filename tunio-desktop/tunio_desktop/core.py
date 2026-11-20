"""Pure logic shared with the Android app (ported from Parsing.kt, Matching.kt, Spotify.kt, SpotifyJson.kt).
No network or disk access here, so it is unit tested."""
from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field

SOURCES = {
    "YOUTUBE": ("YouTube", "YT"),
    "SOUNDCLOUD": ("SoundCloud", "SC"),
    "TIKTOK": ("TikTok", "TT"),
    "SPOTIFY": ("Spotify", "SP"),
    "OTHER": ("Web", "WEB"),
}
_PREFIX = {"YOUTUBE": "yt", "SOUNDCLOUD": "sc", "TIKTOK": "tt", "SPOTIFY": "sp", "OTHER": "x"}


@dataclass
class Track:
    id: str
    source: str
    title: str
    artist: str = ""
    durationSec: int = 0
    thumbnail: str | None = None
    url: str | None = None
    matchedUrl: str | None = None

    @property
    def download_target(self) -> str | None:
        return self.matchedUrl if self.source == "SPOTIFY" else self.url

    @property
    def search_query(self) -> str:
        return self.title if not self.artist else f"{self.artist} - {self.title}"

    def to_dict(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_dict(d: dict) -> "Track":
        return Track(
            id=d["id"], source=d.get("source", "OTHER"), title=d.get("title", ""), artist=d.get("artist", "") or "",
            durationSec=int(d.get("durationSec") or 0), thumbnail=d.get("thumbnail"), url=d.get("url"),
            matchedUrl=d.get("matchedUrl"),
        )


@dataclass
class TrackList:
    title: str | None
    tracks: list[Track]
    cover: str | None = None
    sourceUrl: str | None = None

    def to_dict(self) -> dict:
        return {"title": self.title, "tracks": [t.to_dict() for t in self.tracks], "cover": self.cover, "sourceUrl": self.sourceUrl}


# ---------------------------------------------------------------- helpers

def _s(o: dict, key: str) -> str | None:
    v = o.get(key)
    if v is None:
        return None
    v = str(v)
    return v if v.strip() and v != "null" else None


def _n(o: dict, key: str) -> float | None:
    v = o.get(key)
    try:
        return float(v) if v is not None else None
    except (TypeError, ValueError):
        return None


def source_of(url: str | None, extractor: str | None = None) -> str:
    e = (extractor or "").lower()
    u = (url or "").lower()
    if e.startswith("youtube") or "youtube.com" in u or "youtu.be" in u:
        return "YOUTUBE"
    if e.startswith("soundcloud") or "soundcloud.com" in u:
        return "SOUNDCLOUD"
    if e.startswith("tiktok") or "tiktok.com" in u:
        return "TIKTOK"
    if "spotify" in e or "spotify.com" in u or u.startswith("spotify:"):
        return "SPOTIFY"
    return "OTHER"


def make_id(source: str, raw_id: str) -> str:
    clean = re.sub(r"[^A-Za-z0-9_-]", "_", raw_id)
    if len(clean) > 60:
        clean = format(_java_hash(raw_id) & 0xFFFFFFFF, "x") + clean[-20:]
    return f"{_PREFIX[source]}_{clean}"


def _java_hash(s: str) -> int:
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h


def looks_like_url(text: str) -> bool:
    t = text.strip()
    return t.startswith(("http://", "https://", "spotify:")) or bool(
        re.match(r"^(www\.|m\.|vm\.|open\.)?[a-z0-9-]+\.(com|be|link|fi)/\S+", t, re.I)
    )


def extract_url(text: str) -> str | None:
    m = re.search(r"(https?://\S+|spotify:(track|album|playlist):[A-Za-z0-9]+)", text)
    return m.group(0).rstrip(".,)\"") if m else None


def clean_artist(name: str) -> str:
    name = name.removesuffix(" - Topic").removesuffix("VEVO")
    return name.strip()


def track_from(o: dict, default_source: str = "OTHER") -> Track | None:
    extractor = _s(o, "ie_key") or _s(o, "extractor_key") or _s(o, "extractor")
    url = _s(o, "webpage_url") or _s(o, "original_url") or _s(o, "url")
    detected = source_of(url, extractor)
    source = default_source if detected == "OTHER" else detected
    raw_id = _s(o, "id") or url
    if raw_id is None:
        return None
    if source == "YOUTUBE" and (url is None or not url.startswith("http")):
        url = f"https://www.youtube.com/watch?v={raw_id}"
    if url is None:
        return None
    track_name = _s(o, "track")
    if track_name and track_name.lower().startswith("original sound"):
        track_name = None
    title = track_name or _s(o, "title") or _s(o, "fulltitle")
    if title is None:
        return None
    artists = o.get("artists")
    artist = (
        _s(o, "artist")
        or (str(artists[0]) if isinstance(artists, list) and artists else None)
        or _s(o, "creator") or _s(o, "uploader") or _s(o, "channel") or ""
    )
    thumb = _s(o, "thumbnail")
    thumbs = o.get("thumbnails")
    if thumb is None and isinstance(thumbs, list):
        urls = [t["url"] for t in thumbs if isinstance(t, dict) and t.get("url")]
        thumb = urls[-1] if urls else None
    if thumb is None and source == "YOUTUBE":
        thumb = f"https://i.ytimg.com/vi/{raw_id}/hqdefault.jpg"
    return Track(
        id=make_id(source, raw_id), source=source, title=title.strip(), artist=clean_artist(artist),
        durationSec=int(_n(o, "duration") or 0), thumbnail=thumb, url=url,
    )


def _flatten(entries) -> list[dict]:
    out: list[dict] = []
    for e in entries or []:
        if not isinstance(e, dict):
            continue
        if isinstance(e.get("entries"), list):
            out.extend(_flatten(e["entries"]))
        else:
            out.append(e)
    return out


def parse_result(info: dict | str, default_source: str = "OTHER") -> TrackList:
    """Parses a yt-dlp info dict (or its JSON): a single item or a playlist with entries."""
    if isinstance(info, str):
        start = info.find("{")
        if start < 0:
            return TrackList(None, [])
        info = json.loads(info[start:])
    if not isinstance(info, dict):
        return TrackList(None, [])
    if isinstance(info.get("entries"), list):
        tracks, seen = [], set()
        for e in _flatten(info["entries"]):
            t = track_from(e, default_source)
            if t and t.id not in seen:
                seen.add(t.id)
                tracks.append(t)
        thumbs = [t.get("url") for t in info.get("thumbnails") or [] if isinstance(t, dict) and t.get("url")]
        return TrackList(
            _s(info, "title"), tracks, thumbs[-1] if thumbs else (tracks[0].thumbnail if tracks else None),
            _s(info, "webpage_url") or _s(info, "original_url"),
        )
    t = track_from(info, default_source)
    return TrackList(None, [t] if t else [])


def stream_info(info: dict) -> dict | None:
    """The audio URL + headers if the chosen format can be streamed over plain HTTP(S)."""
    protocol = info.get("protocol") or "https"
    if protocol not in ("http", "https"):
        return None
    url = info.get("url")
    if not url or info.get("cookies"):
        return None
    headers = {k: str(v) for k, v in (info.get("http_headers") or {}).items()}
    return {"url": url, "headers": headers}


def youtube_id(track: Track) -> str | None:
    if track.source == "YOUTUBE" and track.id.startswith("yt_") and len(track.id) == 14:
        return track.id[3:]
    url = track.matchedUrl or track.url
    if not url:
        return None
    m = re.search(r"(?:[?&]v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})", url)
    return m.group(1) if m else None


def _norm_title(t: str) -> str:
    t = re.sub(r"\(.*?\)|\[.*?]", "", t.lower())
    return re.sub(r"[^\w]+", " ", t).strip()


def pick_similar(candidates: list[Track], exclude: list[Track], max_count: int = 10) -> list[Track]:
    ids = {t.id for t in exclude}
    titles = {_norm_title(t.title) for t in exclude}
    out: list[Track] = []
    for c in candidates:
        if len(out) >= max_count:
            break
        if c.id in ids or c.durationSec > 600:
            continue
        nt = _norm_title(c.title)
        if not nt or nt in titles:
            continue
        out.append(c)
        ids.add(c.id)
        titles.add(nt)
    return out


# ---------------------------------------------------------------- Spotify -> YouTube matching

_BAD_WORDS = ["live", "cover", "karaoke", "instrumental", "remix", "sped up", "slowed", "8d", "reaction", "nightcore", "tutorial"]


def match_score(candidate: Track, target: Track) -> float:
    s = 0.0
    if target.durationSec > 0 and candidate.durationSec > 0:
        diff = abs(candidate.durationSec - target.durationSec)
        s += 0.0 if diff <= 3 else 5.0 if diff <= 10 else 20.0 if diff <= 30 else 50.0 + diff / 10.0
    ct, tt = candidate.title.lower(), target.title.lower()
    for w in _BAD_WORDS:
        if w in ct and w not in tt:
            s += 25.0
    words = [w for w in re.split(r"[^\w]+", tt) if len(w) > 1]
    if words:
        s += 20.0 * sum(1 for w in words if w not in ct) / len(words)
    artist = target.artist.lower().split(",")[0].strip()
    if artist and artist not in ct and artist not in candidate.artist.lower():
        s += 10.0
    if "audio" in ct:
        s -= 2.0
    return s


def pick_match(candidates: list[Track], target: Track) -> Track | None:
    return min(candidates, key=lambda c: match_score(c, target)) if candidates else None


# ---------------------------------------------------------------- Spotify links (public metadata only)

_SP_LINK = re.compile(r"open\.spotify\.com/(?:intl-[a-z-]+/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]+)")
_SP_URI = re.compile(r"spotify:(track|album|playlist):([A-Za-z0-9]+)")
_SP_TRACK = re.compile(r"(?:open\.spotify\.com/(?:intl-[a-z-]+/)?track/|spotify:track:)([A-Za-z0-9]{22})")


def is_spotify(url: str) -> bool:
    u = url.lower()
    return "spotify.com" in u or "spotify.link" in u or "spoti.fi" in u or u.startswith("spotify:")


def spotify_link(url: str) -> tuple[str, str] | None:
    m = _SP_LINK.search(url) or _SP_URI.search(url)
    return (m.group(1), m.group(2)) if m else None


def spotify_track_ids(text: str) -> list[str]:
    """All Spotify song ids in pasted text (e.g. songs copied from the Spotify desktop app), in order."""
    out: list[str] = []
    for m in _SP_TRACK.finditer(text):
        if m.group(1) not in out:
            out.append(m.group(1))
    return out


def _walk(e, visit):
    visit(e)
    if isinstance(e, dict):
        for v in e.values():
            _walk(v, visit)
    elif isinstance(e, list):
        for v in e:
            _walk(v, visit)


def _find_obj(root, pred):
    found = []
    _walk(root, lambda e: found.append(e) if not found and isinstance(e, dict) and pred(e) else None)
    return found[0] if found else None


def _find_image(root):
    found = []

    def visit(e):
        if not found and isinstance(e, str) and e.startswith("https://") and (
            "i.scdn.co/image" in e or "image-cdn" in e or "mosaic.scdn.co" in e
        ):
            found.append(e)

    _walk(root, visit)
    return found[0] if found else None


def _clean_artists(s: str) -> str:
    return re.sub(r"\s*,\s*", ", ", s.replace(" ", " ")).strip()


def _duration_ms(o: dict) -> int:
    d = o.get("duration")
    if isinstance(d, dict):
        d = d.get("totalMilliseconds")
    if d is None:
        d = o.get("duration_ms")
    try:
        return int(float(d))
    except (TypeError, ValueError):
        return 0


def parse_spotify_embed(html: str, kind: str, sid: str) -> TrackList | None:
    m = re.search(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    if not m:
        return None
    root = json.loads(m.group(1))
    entity = None
    try:
        entity = root["props"]["pageProps"]["state"]["data"]["entity"]
    except (KeyError, TypeError):
        pass
    entity = entity or _find_obj(root, lambda o: isinstance(o.get("trackList"), list)) or _find_obj(
        root, lambda o: o.get("type") == kind and o.get("name")
    )
    if not isinstance(entity, dict):
        return None
    cover = _find_image(entity)
    track_list = entity.get("trackList")
    if isinstance(track_list, list) and (kind != "track" or track_list):
        tracks = []
        for t in track_list:
            if not isinstance(t, dict):
                continue
            title = _s(t, "title") or _s(t, "name")
            if not title:
                continue
            um = _SP_URI.search(_s(t, "uri") or "")
            tid = um.group(2) if um else (_s(t, "id") or format(_java_hash(title + str(t.get("subtitle"))), "x"))
            artists = _s(t, "subtitle") or ", ".join(
                a.get("name", "") for a in t.get("artists") or [] if isinstance(a, dict)
            )
            tracks.append(Track(
                id=make_id("SPOTIFY", tid), source="SPOTIFY", title=title, artist=_clean_artists(artists),
                durationSec=_duration_ms(t) // 1000, thumbnail=_find_image(t) or cover,
                url=f"https://open.spotify.com/track/{tid}",
            ))
        if tracks or kind != "track":
            return TrackList(_s(entity, "name") or _s(entity, "title"), tracks, cover)
    title = _s(entity, "name") or _s(entity, "title")
    if not title:
        return None
    artists = ", ".join(a.get("name", "") for a in entity.get("artists") or [] if isinstance(a, dict)) or (_s(entity, "subtitle") or "")
    t = Track(
        id=make_id("SPOTIFY", sid), source="SPOTIFY", title=title, artist=_clean_artists(artists),
        durationSec=_duration_ms(entity) // 1000, thumbnail=cover, url=f"https://open.spotify.com/track/{sid}",
    )
    return TrackList(None, [t], cover)


def format_duration(sec: int) -> str:
    if sec <= 0:
        return ""
    h, m, s = sec // 3600, (sec % 3600) // 60, sec % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"
