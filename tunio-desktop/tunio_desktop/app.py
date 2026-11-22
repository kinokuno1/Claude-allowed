"""Entry point: starts the local server and opens Tunio in its own window (Microsoft Edge WebView2)."""
from __future__ import annotations

import os
import sys

from . import __version__, server


def _log_to_file():
    """Tunio.exe has no console: send output to %APPDATA%\\Tunio\\tunio.log (handy when something goes wrong)."""
    from .paths import DATA_DIR
    if sys.stdout is None or sys.stderr is None:
        log = open(os.path.join(DATA_DIR, "tunio.log"), "a", encoding="utf-8", buffering=1)
        sys.stdout = sys.stdout or log
        sys.stderr = sys.stderr or log


def main():
    _log_to_file()
    httpd, url = server.start()
    if "--browser" in sys.argv or os.environ.get("TUNIO_BROWSER"):
        import time
        import webbrowser
        webbrowser.open(url)
        print(f"Tunio running at {url} (Ctrl+C to quit)")
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            return
    import webview

    webview.create_window(
        f"Tunio {__version__}", url, width=1320, height=840, min_size=(820, 600), background_color="#0E0E12",
    )
    webview.start(private_mode=False, storage_path=os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "Tunio", "webview"))
    httpd.shutdown()


if __name__ == "__main__":
    main()
