"""Entry point: starts the local server and opens Tunio in its own window (Microsoft Edge WebView2).

The window has no Windows title bar: Tunio's own top bar is the title bar (like Spotify). The page drags,
resizes, minimizes, maximizes and closes the window through WindowApi below."""
from __future__ import annotations

import os
import sys

from . import __version__, server

TITLE = f"Tunio {__version__}"
MINI_TITLE = "Tunio mini player"


def _log_to_file():
    """Tunio.exe has no console: send output to %APPDATA%\\Tunio\\tunio.log (handy when something goes wrong)."""
    from .paths import DATA_DIR
    if sys.stdout is None or sys.stderr is None:
        log = open(os.path.join(DATA_DIR, "tunio.log"), "a", encoding="utf-8", buffering=1)
        sys.stdout = sys.stdout or log
        sys.stderr = sys.stderr or log


class WindowApi:
    """Called from the page as window.pywebview.api.* (Windows only). Sizes are in physical pixels.

    "Maximize" fills the screen's work area itself (a window without a title bar would otherwise also
    cover the taskbar), and remembers the size to go back to."""

    _title = TITLE

    def __init__(self):
        self._window = None
        self._hwnd = 0
        self._restore: list[int] | None = None
        self._mini: "MiniApi | None" = None
        self._now: dict = {}

    # ---- helpers
    @staticmethod
    def _user32():
        import ctypes
        from ctypes import wintypes
        u = ctypes.windll.user32
        if not getattr(u, "_tunio_types", False):
            u.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT]
            u.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
            u.MonitorFromWindow.argtypes = [wintypes.HWND, wintypes.DWORD]
            u.MonitorFromWindow.restype = wintypes.HMONITOR
            u.GetMonitorInfoW.argtypes = [wintypes.HMONITOR, ctypes.c_void_p]
            u.FindWindowW.restype = wintypes.HWND
            u._tunio_types = True
        return u

    def _h(self) -> int:
        if not self._hwnd:
            import ctypes
            try:
                self._hwnd = int(self._window.native.Handle.ToInt64())
            except Exception:  # noqa: BLE001 - fall back to finding the window by its title
                self._hwnd = self._user32().FindWindowW(None, self._title) or 0
        return self._hwnd

    def _rect(self) -> list[int]:
        import ctypes
        from ctypes import wintypes
        r = wintypes.RECT()
        self._user32().GetWindowRect(self._h(), ctypes.byref(r))
        return [r.left, r.top, r.right - r.left, r.bottom - r.top]

    def _work_area(self) -> list[int]:
        import ctypes
        from ctypes import wintypes

        class MONITORINFO(ctypes.Structure):
            _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]
        mon = self._user32().MonitorFromWindow(self._h(), 2)  # nearest monitor
        mi = MONITORINFO()
        mi.cbSize = ctypes.sizeof(MONITORINFO)
        self._user32().GetMonitorInfoW(mon, ctypes.byref(mi))
        w = mi.rcWork
        return [w.left, w.top, w.right - w.left, w.bottom - w.top]

    def _place(self, x, y, w, h):
        self._user32().SetWindowPos(self._h(), None, int(x), int(y), int(w), int(h), 0x0004 | 0x0010)  # NOZORDER|NOACTIVATE

    # ---- called from the page
    def state(self) -> dict:
        return {"maximized": self._restore is not None, "rect": self._rect()}

    def minimize(self):
        self._window.minimize()
        self._show_mini()

    def toggle_maximize(self) -> dict:
        if self._restore is not None:
            r, self._restore = self._restore, None
            self._place(*r)
        else:
            self._restore = self._rect()
            self._place(*self._work_area())
        return self.state()

    def unmaximize_at(self, screen_x: int, screen_y: int, grab_ratio: float) -> dict:
        """Dragging the top bar of a maximized window: back to its normal size, under the mouse."""
        if self._restore is None:
            return self.state()
        x, y, w, h = self._restore
        self._restore = None
        self._place(int(screen_x - w * grab_ratio), int(screen_y - 20), w, h)
        return self.state()

    def set_rect(self, x, y, w, h):
        self._restore = None
        self._place(x, y, w, h)

    def close(self):
        """With a song loaded, closing keeps the music going in the mini player; otherwise Tunio quits."""
        if self._now.get("id") and self._mini:
            self._window.hide()
            self._show_mini()
        else:
            self.quit()

    def quit(self):
        if self._mini and self._mini._window:
            self._mini._window.destroy()
        self._window.destroy()

    # ---- the page tells what's playing (for the mini player)
    def report(self, state: dict):
        self._now = state or {}

    def _show_mini(self):
        if self._mini and self._now.get("id"):
            self._mini.show()

    def _hide_mini(self):
        if self._mini:
            self._mini.hide()


class MiniApi(WindowApi):
    """The mini player window (always on top). It shows what the main window reports and sends its buttons there."""
    _title = MINI_TITLE

    def __init__(self, main: WindowApi):
        super().__init__()
        self._main = main
        self._placed = False
        self._shown = False

    def now(self) -> dict:
        return self._main._now

    def cmd(self, name: str, arg=None):
        import json
        if name in ("toggle", "next", "prev", "seek", "like"):
            self._main._window.evaluate_js(f"window.TunioRemote && window.TunioRemote.cmd({json.dumps(name)}, {json.dumps(arg)})")

    def show(self):
        if not self._placed:  # first time: bottom right of the screen
            self._placed = True
            try:
                self._window.show()
                x, y, w, h = self._work_area()
                ww, wh = self._rect()[2:]
                self._place(x + w - ww - 24, y + h - wh - 24, ww, wh)
            except Exception:  # noqa: BLE001
                pass
        self._window.show()
        self._shown = True

    def hide(self):
        if self._shown:
            self._window.hide()
            self._shown = False

    def expand(self):
        """Back to the big window."""
        self.hide()
        self._main._window.show()
        self._main._window.restore()

    def quit(self):
        self._main.quit()


def _round_corners(api: WindowApi):
    """Windows 11: rounded window corners (ignored on Windows 10)."""
    try:
        import ctypes
        pref = ctypes.c_int(2)  # DWMWCP_ROUND
        ctypes.windll.dwmapi.DwmSetWindowAttribute(ctypes.c_void_p(api._h()), 33, ctypes.byref(pref), 4)
    except Exception:  # noqa: BLE001
        pass


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
    # A music player must be able to start sound by itself: WebView2 otherwise only lets audio start within a
    # few seconds of a click, so a song that takes longer to look up stays paused.
    args = os.environ.get("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "")
    if "autoplay-policy" not in args:
        os.environ["WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS"] = (args + " --disable-features=ElasticOverscroll --autoplay-policy=no-user-gesture-required").strip()
    import webview

    api = WindowApi()
    window = webview.create_window(
        TITLE, url, width=1320, height=840, min_size=(820, 600), background_color="#0E0E12",
        frameless=True, easy_drag=False, shadow=True, js_api=api,
    )
    api._window = window
    window.events.shown += lambda: _round_corners(api)
    mini_api = MiniApi(api)
    mini = webview.create_window(
        MINI_TITLE, url.replace("/?", "/mini.html?", 1), width=380, height=112, min_size=(220, 80), background_color="#0E0E12",
        frameless=True, easy_drag=False, shadow=True, on_top=True, hidden=True, js_api=mini_api,
    )
    mini_api._window = mini
    api._mini = mini_api
    mini.events.shown += lambda: _round_corners(mini_api)
    window.events.minimized += lambda: api._show_mini()
    window.events.restored += lambda: api._hide_mini()
    webview.start(private_mode=False, storage_path=os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "Tunio", "webview"))
    httpd.shutdown()


if __name__ == "__main__":
    main()
