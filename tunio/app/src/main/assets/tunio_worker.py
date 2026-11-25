"""Tunio's long-running yt-dlp worker. Started once, it keeps Python + yt-dlp loaded so each search, link or
song doesn't pay Python's startup again. Protocol: one JSON request per line on stdin; answers are lines
prefixed with \x1eTUNIO on stdout: {"id", "ok", "json"|"error"} or progress {"id", "p"}."""
import json
import os
import sys
import threading
import traceback

MARK = "\x1eTUNIO "
_out = threading.Lock()
_cancelled = set()


def send(obj):
    line = MARK + json.dumps(obj, ensure_ascii=True) + "\n"
    with _out:
        sys.__stdout__.write(line)
        sys.__stdout__.flush()


class Log:
    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        pass

    def error(self, msg):
        sys.stderr.write(str(msg) + "\n")


def main():
    cfg = json.loads(sys.argv[1])
    sys.path.insert(0, cfg["ytdlp"])  # the yt-dlp zipapp
    import yt_dlp
    from yt_dlp.version import __version__

    base = {
        "logger": Log(),
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "cachedir": cfg["cachedir"],
        "socket_timeout": 20,
    }
    if cfg.get("quickjs"):
        base["js_runtimes"] = {"quickjs": {"path": cfg["quickjs"]}}
    if cfg.get("ffmpeg"):
        base["ffmpeg_location"] = cfg["ffmpeg"]

    # Warm up: load the extractors and the YouTube code paths before the first real request.
    with yt_dlp.YoutubeDL(dict(base)) as y:
        y.get_info_extractor("Youtube")
        y.get_info_extractor("YoutubeSearch")
    send({"ready": True, "version": __version__})

    def handle(req):
        rid = req.get("id")
        try:
            op = req["op"]
            opts = dict(base)
            if op == "flat":
                opts["extract_flat"] = "in_playlist"
                if req.get("items"):
                    opts["playlist_items"] = req["items"]
                with yt_dlp.YoutubeDL(opts) as y:
                    info = y.sanitize_info(y.extract_info(req["url"], download=False))
                send({"id": rid, "ok": True, "json": json.dumps(info)})
            elif op == "stream":
                opts.update(format="ba/b", noplaylist=True, playlist_items="1")
                with yt_dlp.YoutubeDL(opts) as y:
                    info = y.extract_info(req["url"], download=False)
                    if isinstance(info, dict) and isinstance(info.get("entries"), list):
                        info = next((e for e in info["entries"] if isinstance(e, dict)), {})
                    info = y.sanitize_info(info)
                send({"id": rid, "ok": True, "json": json.dumps(info)})
            elif op == "download":
                def hook(d):
                    if rid in _cancelled:
                        raise yt_dlp.utils.DownloadCancelled("cancelled")
                    if d.get("status") == "downloading":
                        total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                        if total:
                            send({"id": rid, "p": min(1.0, d.get("downloaded_bytes", 0) / total)})

                opts.update(
                    format="bestaudio/best", outtmpl=req["outtmpl"], noplaylist=True, playlist_items="1",
                    progress_hooks=[hook], updatetime=False, continuedl=False,
                    postprocessors=[{"key": "FFmpegExtractAudio"}] if cfg.get("ffmpeg") else [],
                )
                with yt_dlp.YoutubeDL(opts) as y:
                    y.extract_info(req["url"], download=True)
                send({"id": rid, "ok": True, "json": "{}"})
            elif op == "ping":
                send({"id": rid, "ok": True, "json": json.dumps({"version": __version__})})
            else:
                raise ValueError("unknown op " + str(op))
        except BaseException as e:  # noqa: BLE001 - every failure goes back to the app
            msg = str(e).strip() or type(e).__name__
            send({"id": rid, "ok": False, "error": msg[-600:]})
        finally:
            _cancelled.discard(rid)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        if req.get("op") == "cancel":
            _cancelled.add(req.get("target"))
            continue
        if req.get("op") == "exit":
            break
        threading.Thread(target=handle, args=(req,), daemon=True).start()
    os._exit(0)


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        traceback.print_exc()
        sys.exit(1)
