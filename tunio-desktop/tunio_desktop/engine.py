"""yt-dlp runs inside the app (no separate process per call, so it is fast). Also Spotify's public pages."""
from __future__ import annotations

import os
import shutil
import sys
import threading
import urllib.parse
import urllib.request

from . import core
from .core import Track, TrackList
from .paths import CACHE_DIR, YTDLP_CACHE

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"


def _bundled(name: str) -> str | None:
    """Executables shipped inside Tunio.exe (PyInstaller) or next to it."""
    exe = name + (".exe" if os.name == "nt" else "")
    for base in (getattr(sys, "_MEIPASS", None), os.path.dirname(sys.executable)):
        if base and os.path.isfile(os.path.join(base, exe)):
            return os.path.join(base, exe)
    return shutil.which(name)


def ffmpeg_path() -> str | None:
    p = _bundled("ffmpeg")
    if p:
        return p
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


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

def search(source: str, query: str, count: int = 25) -> list[Track]:
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


def download(target: str, folder: str, key: str, on_progress=None) -> str:
    """Downloads the best audio as <folder>/<key>.<ext> and returns the file path."""
    os.makedirs(folder, exist_ok=True)

    def hook(d):
        if on_progress and d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            if total:
                on_progress(min(1.0, d.get("downloaded_bytes", 0) / total))

    opts = dict(
        format="bestaudio/best",
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


