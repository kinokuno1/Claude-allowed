"""yt-dlp runs inside the app (no separate process per call, so it is fast). Also Spotify's public pages."""
from __future__ import annotations

import os
import shutil
import sys
import threading
import time
import urllib.parse
import urllib.request

from . import core
from .core import Track, TrackList
from .paths import CACHE_DIR, YTDLP_CACHE

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"


TOOLS_DIR = os.path.join(CACHE_DIR, "..", "bin")


def _bundled(name: str) -> str | None:
    """A helper program: inside Tunio.exe, next to it, downloaded by Tunio (Deno), or on PATH."""
    exe = name + (".exe" if os.name == "nt" else "")
    for base in (getattr(sys, "_MEIPASS", None), os.path.dirname(sys.executable), TOOLS_DIR):
        if base and os.path.isfile(os.path.join(base, exe)):
            return os.path.normpath(os.path.join(base, exe))
    return shutil.which(name)


DENO_URL = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip"


def ensure_deno():
    """YouTube's JavaScript checks run fastest with Deno. It isn't packed into Tunio.exe (that would make every
    launch unpack ~100 MB); it is downloaded once, in the background, on first launch. Until then yt-dlp uses
    YouTube's no-JavaScript client, so songs still play."""
    if os.name != "nt" or _bundled("deno"):
        return
    import io
    import zipfile
    try:
        os.makedirs(TOOLS_DIR, exist_ok=True)
        req = urllib.request.Request(DENO_URL, headers={"User-Agent": "Tunio"})
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            tmp = os.path.join(TOOLS_DIR, "deno.exe.tmp")
            with z.open("deno.exe") as src, open(tmp, "wb") as dst:
                shutil.copyfileobj(src, dst)
        os.replace(tmp, os.path.join(TOOLS_DIR, "deno.exe"))
    except Exception as e:  # noqa: BLE001 - optional speed-up
        print("Deno download failed:", e, file=sys.stderr)


def warm_up():
    """Loads yt-dlp and its YouTube code while the window opens, so the first search doesn't wait."""
    try:
        with _ydl() as y:
            y.get_info_extractor("Youtube")
            y.get_info_extractor("YoutubeSearch")
    except Exception:  # noqa: BLE001
        pass


def ffmpeg_path() -> str | None:
    """Optional: Tunio plays and saves the original audio (m4a/webm), so it doesn't need ffmpeg."""
    return _bundled("ffmpeg")


class _Log:
    """yt-dlp messages go to Tunio's log file, never to a console (Tunio.exe has none)."""
    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        print("yt-dlp warning:", msg, file=sys.stderr)

    def error(self, msg):
        print("yt-dlp error:", msg, file=sys.stderr)


def _opts(**extra) -> dict:
    opts = {
        "logger": _Log(),
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "cachedir": YTDLP_CACHE,  # keeps YouTube's pre-processed player code between songs
        "socket_timeout": 20,
    }
    deno = _bundled("deno")
    if deno:
        opts["js_runtimes"] = {"deno": {"path": deno}}
    ff = ffmpeg_path()
    if ff:
        opts["ffmpeg_location"] = ff
    opts.update(extra)
    return opts


def _ydl(**extra):
    import yt_dlp
    return yt_dlp.YoutubeDL(_opts(**extra))


def version() -> str:
    try:
        from yt_dlp.version import __version__
        return __version__
    except Exception:
        return "unknown"


# ---------------------------------------------------------------- search & links

_search_cache: dict[str, tuple[list[Track], float]] = {}


def search(source: str, query: str, count: int = 25) -> list[Track]:
    """Recent searches are remembered for 10 minutes, so going back to results is instant."""
    key = f"{source}|{count}|{query.strip().lower()}"
    hit = _search_cache.get(key)
    if hit and time.time() - hit[1] < 600:
        return hit[0]
    result = _search(source, query, count)
    if len(_search_cache) > 50:
        _search_cache.clear()
    _search_cache[key] = (result, time.time())
    return result


def _search(source: str, query: str, count: int) -> list[Track]:
    prefix = "scsearch" if source == "SOUNDCLOUD" else "ytsearch"
    with _ydl(extract_flat="in_playlist") as y:
        info = y.extract_info(f"{prefix}{count}:{query}", download=False)
    return core.parse_result(info, "SOUNDCLOUD" if source == "SOUNDCLOUD" else "YOUTUBE").tracks


def resolve(text: str) -> TrackList:
    ids = core.spotify_track_ids(text)
    if len(ids) >= 2 or (ids and not core.extract_url(text)):
        return _spotify_tracks(ids)
    url = core.extract_url(text) or ("https://" + text.strip() if core.looks_like_url(text) else None)
    if not url:
        raise ValueError("Not a link")
    if core.is_spotify(url):
        return _spotify(url)
    with _ydl(extract_flat="in_playlist") as y:
        info = y.extract_info(url, download=False)
    r = core.parse_result(info, core.source_of(url))
    r.sourceUrl = r.sourceUrl or url
    return r


def similar(track: Track, exclude: list[Track], max_count: int = 10) -> list[Track]:
    """Autoplay: YouTube's Mix for the song (like Spotify's autoplay)."""
    vid = core.youtube_id(track)
    if vid is None:
        found = search("YOUTUBE", track.search_query, 1)
        vid = core.youtube_id(found[0]) if found else None
    if vid is None:
        return []
    with _ydl(extract_flat="in_playlist", playlist_items="1:40") as y:
        info = y.extract_info(f"https://www.youtube.com/watch?v={vid}&list=RD{vid}", download=False)
    mix = core.parse_result(info, "YOUTUBE").tracks
    return core.pick_similar(mix, exclude + [track], max_count)


def match_on_youtube(track: Track) -> Track:
    candidates = search("YOUTUBE", track.search_query + " audio", 6)
    best = core.pick_match(candidates, track)
    if best is None:
        raise RuntimeError(f"No YouTube match for {track.search_query}")
    return best


# ---------------------------------------------------------------- audio

def stream(target: str) -> dict | None:
    """Only *where* the best audio is (no download), so playback starts at once."""
    with _ydl(format="ba/b", noplaylist=True, playlist_items="1") as y:
        info = y.extract_info(target, download=False)
    if isinstance(info, dict) and isinstance(info.get("entries"), list):
        info = next((e for e in info["entries"] if isinstance(e, dict)), None) or {}
    return core.stream_info(info)


AUDIO_FORMAT = "bestaudio[ext=m4a]/bestaudio/best"


def audio_format(target: str) -> dict:
    """Everything yt-dlp knows about the audio Tunio keeps (m4a first): url, protocol, headers, ext, size, title..."""
    with _ydl(format=AUDIO_FORMAT, noplaylist=True, playlist_items="1") as y:
        info = y.extract_info(target, download=False)
        if isinstance(info, dict) and isinstance(info.get("entries"), list):
            info = next((e for e in info["entries"] if isinstance(e, dict)), None) or {}
        return y.sanitize_info(info)


def flat(url: str, items: str | None = None) -> dict:
    """yt-dlp's quick listing (search results, playlists, mixes), exactly as the phone's worker returns it."""
    opts = {"extract_flat": "in_playlist"}
    if items:
        opts["playlist_items"] = items
    with _ydl(**opts) as y:
        return y.sanitize_info(y.extract_info(url, download=False))


def download(target: str, folder: str, key: str, on_progress=None) -> str:
    """Downloads the best audio as <folder>/<key>.<ext> and returns the file path."""
    os.makedirs(folder, exist_ok=True)

    def hook(d):
        if on_progress and d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            if total:
                on_progress(min(1.0, d.get("downloaded_bytes", 0) / total))

    opts = dict(
        # m4a first: plays everywhere (Windows Media Player, phones) and needs no conversion.
        format=AUDIO_FORMAT,
        outtmpl=os.path.join(folder, key + ".%(ext)s"),
        noplaylist=True,
        playlist_items="1",
        progress_hooks=[hook],
        postprocessors=[{"key": "FFmpegExtractAudio"}] if ffmpeg_path() else [],
        updatetime=False,
        continuedl=False,
    )
    try:
        with _ydl(**opts) as y:
            y.extract_info(target, download=True)
    except Exception:
        for f in os.listdir(folder):
            if f.startswith(key + "."):
                _rm(os.path.join(folder, f))
        raise
    f = find_audio(folder, key)
    if not f:
        raise RuntimeError("yt-dlp finished but no audio file was written")
    return f


_PARTIAL = {"part", "ytdl", "temp", "tmp", "webp", "jpg", "png", "json"}


def find_audio(folder: str, key: str) -> str | None:
    if not os.path.isdir(folder):
        return None
    best = None
    for f in os.listdir(folder):
        if not f.startswith(key + ".") or ".part" in f:
            continue
        if f.rsplit(".", 1)[-1].lower() in _PARTIAL:
            continue
        p = os.path.join(folder, f)
        if best is None or os.path.getsize(p) > os.path.getsize(best):
            best = p
    return best


def _rm(p):
    try:
        os.remove(p)
    except OSError:
        pass


# ---------------------------------------------------------------- Spotify (public pages only)

def _get(url: str) -> tuple[str, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.geturl(), r.read().decode("utf-8", "replace")


def _spotify_public(kind: str, sid: str) -> TrackList:
    page = f"https://open.spotify.com/{kind}/{sid}"
    try:
        r = core.parse_spotify_embed(_get(f"https://open.spotify.com/embed/{kind}/{sid}")[1], kind, sid)
        if r and r.tracks:
            r.sourceUrl = page
            return r
    except Exception:
        pass
    if kind == "track":
        import json
        o = json.loads(_get("https://open.spotify.com/oembed?url=" + urllib.parse.quote(page, safe=""))[1])
        t = Track(core.make_id("SPOTIFY", sid), "SPOTIFY", o.get("title") or "Spotify song", "", 0, o.get("thumbnail_url"), page)
        return TrackList(None, [t], sourceUrl=page)
    raise RuntimeError(
        "Couldn't read this Spotify playlist. If it's private: make it public in Spotify, or in the Spotify app "
        "select its songs (Ctrl+A), copy (Ctrl+C) and paste them here."
    )


def _spotify(url: str) -> TrackList:
    link = core.spotify_link(url)
    if link is None:  # short links (spotify.link / spoti.fi)
        final, body = _get(url)
        link = core.spotify_link(final) or core.spotify_link(body)
    if link is None:
        raise ValueError("Not a Spotify song, album or playlist link")
    return _spotify_public(*link)


def _spotify_tracks(ids: list[str]) -> TrackList:
    from concurrent.futures import ThreadPoolExecutor

    def one(i):
        try:
            return _spotify_public("track", i).tracks[0]
        except Exception:
            return None

    with ThreadPoolExecutor(6) as pool:
        tracks = [t for t in pool.map(one, ids) if t]
    if not tracks:
        raise RuntimeError(f"Couldn't read any of the {len(ids)} Spotify songs")
    return TrackList("Pasted Spotify songs", tracks, tracks[0].thumbnail)


