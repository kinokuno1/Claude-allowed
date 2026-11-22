# Tunio for Windows

The Windows version of Tunio. It has the same features as the Android app, with a desktop layout like Spotify's, and runs as one program: **Tunio.exe**.

## Download

Get **Tunio.exe** from the [tunio-latest release](https://github.com/kinokuno1/Claude-allowed/releases/tag/tunio-latest) and double-click it. There's nothing to install.
The app isn't code-signed, so Windows SmartScreen may say *"Windows protected your PC"*. Click **More info → Run anyway**. You only need to do this once.
It needs Windows 10 or 11 with the Microsoft Edge WebView2 runtime, which is already installed on up-to-date Windows.

## What it does

- **Search and links:** search YouTube or SoundCloud, or paste a YouTube, SoundCloud, TikTok or Spotify link (song, playlist or album).
- **Spotify:** Spotify songs play from YouTube, matched by title, artist and length. For private playlists, open the Spotify app, select the songs (Ctrl+A), copy (Ctrl+C) and paste them into Search.
- **Playback:** songs start streaming right away, and the next song is prepared in advance. While you read search results, the top one is prepared too. Recent searches are remembered for 10 minutes. When 3 or fewer songs are left, **autoplay** adds similar songs.
- **Library:** Liked songs, playlists (create, rename, delete, *Save as playlist*) and recently played.
- **Offline:** right-click a song → **Download**, or the ⤓ button on a playlist. Files go to your **Music\Tunio** folder and download in the background.
- **Layout:** a desktop layout like Spotify's. Your Library is on the left (click its title to shrink it to icons), the page is in the middle, and **Now playing** is on the right with the cover, a live audio visualizer and what plays next. The player bar is at the bottom with shuffle, repeat, a seek bar and **volume** (drag it, scroll it, or click the speaker to mute). Back and forward work like in a browser.
- **Songs:** double-click a song to play it, or click the ▶ that appears on hover. Right-click a song for more options.
- **Keyboard:** Space plays and pauses, Ctrl+→ and Ctrl+← skip, Ctrl+↑ and Ctrl+↓ change the volume, Ctrl+K jumps to search, and Alt+← and Alt+→ go back and forward. The keyboard media keys work too.
- **Colours:** the 60-30-10 rule with the app's own palette. 60 % is the near-black main view, 30 % is the dark violet-grey panels and cards, and 10 % is the purple-to-pink accent, used only for play buttons, progress, volume, the playing song and Liked hearts.

## Where things are

| | |
|---|---|
| Offline songs | `%USERPROFILE%\Music\Tunio` |
| Library (likes, playlists) | `%APPDATA%\Tunio\library.json` |
| Playback cache (max 1 GB) | `%LOCALAPPDATA%\Tunio\cache` |
| Log (if something goes wrong) | `%APPDATA%\Tunio\tunio.log` |

## How it's built

- **Engine:** Python running [yt-dlp](https://github.com/yt-dlp/yt-dlp) in-process, already loaded while the window opens. Deno handles YouTube's JavaScript checks. It's downloaded once to `%LOCALAPPDATA%\Tunio\bin` on first launch, so Tunio.exe stays small and starts fast; until then a no-JavaScript YouTube client is used. No ffmpeg: songs are played and saved in their original format (m4a preferred).
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
