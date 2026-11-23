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
- **Layout:** a desktop layout like Spotify's, with no Windows title bar: Tunio's own top bar is the title bar (drag it to move the window, double-click it to maximize; minimize, maximize and close are at the top right). Your Library is on the left, the page is in the middle, and **Now playing** is on the right in three parts: the song, its lyrics and what plays next. The player bar is at the bottom with shuffle, repeat, a seek bar and **volume** (drag it, scroll it, or click the speaker to mute). Back and forward work like in a browser.
- **Mini player:** minimize Tunio, or close it while a song is loaded, and a small player stays on top of your other windows with the cover, ♥, previous / play / next and the progress bar. Drag it anywhere and resize it from its edges: it's a strip when it's wide and low, and shows the cover with the controls under it when it's square or tall. ⤢ opens Tunio again; × quits.
- **Resizing:** drag the gaps between the library, the page and Now playing, or between the song, lyrics and queue parts. The mouse turns into a resize arrow over them. Each part switches layout for its size instead of cropping: Now playing goes from a big cover with a short lyrics strip, to a small cover beside the title, to just the song; the library becomes a grid of covers when it's wide; the queue drops its covers when it's narrow. Double-click a gap to reset it. Drag the library very narrow to shrink it to covers only.
- **Covers:** songs imported from Spotify get their own cover (not the playlist's), looked up while the list loads. Songs with a missing or broken cover are looked up again automatically (Spotify, YouTube, iTunes and Deezer, no account needed).
- **Lyrics:** synced lyrics from [LRCLIB](https://lrclib.net), a free, community-made lyrics library (no account needed). In Now playing, the song and its lyrics share one part, with the queue below. The lines slide up smoothly as the song plays, and the current line is in white. Click the lyrics, or the microphone in the player bar, to show them big over a slowly flowing, blurred copy of the cover. Click a line to jump to it.
  - **Big player card:** click the song's title at the top left of the big lyrics, and it grows into a card with the cover, ♥, controls and progress. Click the card's title to shrink it back.
  - **Line / Word** (the two icons, top right): *Word* keeps the text still and fills each word with white as it's sung. Word timing comes from Musixmatch or NetEase (free, community-made). If neither has the song, it's estimated from the line timing. It's only looked up when you turn on Word or 3D.
  - **3D** (the two cubes): a camera flies from word to word through space, each word pops up as it's sung, and the camera kicks on the beat (found by listening to the bass), like a music-video edit.
- **Songs:** click a song to play it. Right-click a song for more options. Liking a song fills its heart from the bottom up and makes it pop. Play and pause buttons morph smoothly between ▶ and ❚❚.
- **Keyboard:** Space plays and pauses, Ctrl+→ and Ctrl+← skip, Ctrl+↑ and Ctrl+↓ change the volume, Ctrl+K jumps to search, and Alt+← and Alt+→ go back and forward. The keyboard media keys work too.
- **Colours:** the 60-30-10 rule with flat colours (no gradients). 60 % is the near-black main view, 30 % is the dark grey panels and cards, and 10 % is a soft violet accent for what's playing or switched on. Play buttons are white.

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
