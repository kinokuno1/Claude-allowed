# Tunio for Windows

The Windows version of Tunio. It has the same features and look as the Android app, but runs as one program: **Tunio.exe**.

## Download

Get **Tunio.exe** from the [tunio-latest release](https://github.com/kinokuno1/Claude-allowed/releases/tag/tunio-latest) and double-click it. There's nothing to install.
The app isn't code-signed, so Windows SmartScreen may say *"Windows protected your PC"*. Click **More info → Run anyway**. You only need to do this once.
It needs Windows 10 or 11 with the Microsoft Edge WebView2 runtime, which is already installed on up-to-date Windows.

## What it does

- **Search and links:** search YouTube or SoundCloud, or paste a YouTube, SoundCloud, TikTok or Spotify link (song, playlist or album).
- **Spotify:** Spotify songs play from YouTube, matched by title, artist and length. For private playlists, open the Spotify app, select the songs (Ctrl+A), copy (Ctrl+C) and paste them into Search.
- **Playback:** songs start streaming right away, and the next song is prepared in advance. When 3 or fewer songs are left, **autoplay** adds similar songs.
- **Library:** Liked songs, playlists (create, rename, delete, *Save as playlist*) and recently played.
- **Offline:** ⋮ → **Download**, or **Download N** on a playlist. Files go to your **Music\Tunio** folder and download in the background.
- **Keyboard:** Space plays and pauses, Esc goes back, and the keyboard media keys work too.
- **Look:** the same liquid-glass design as the phone app. The floating tab bar, mini player and buttons bend the light behind them. The tab pill braces, stretches and wobbles, and the icons flash when selected.

## Where things are

| | |
|---|---|
| Offline songs | `%USERPROFILE%\Music\Tunio` |
| Library (likes, playlists) | `%APPDATA%\Tunio\library.json` |
| Playback cache (max 1 GB) | `%LOCALAPPDATA%\Tunio\cache` |
| Log (if something goes wrong) | `%APPDATA%\Tunio\tunio.log` |

## How it's built

- **Engine:** Python running [yt-dlp](https://github.com/yt-dlp/yt-dlp) in-process, with Deno for YouTube's JavaScript checks and ffmpeg.
- **Window:** [pywebview](https://pywebview.flowrl.com/) on Microsoft Edge WebView2. The UI is plain HTML, CSS and JS in `tunio_desktop/web`.
- **Local server:** a small server on `127.0.0.1` connects the UI to the engine. It only accepts the window's own random token.
- **Packaging:** [PyInstaller](https://pyinstaller.org/) bundles it all into one `Tunio.exe`. GitHub Actions builds it (`.github/workflows/build-tunio-windows.yml`) and publishes it next to the Android APK.

Run from source (Windows, macOS or Linux, Python 3.11+):

```
pip install -r requirements.txt
python -m tunio_desktop            # opens the Tunio window
python -m tunio_desktop --browser  # or use it in your normal browser
python -m pytest tests
```

Every new build includes the newest yt-dlp. If YouTube changes and songs stop loading, download the latest Tunio.exe.
