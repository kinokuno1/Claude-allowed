# Tunio

A personal Spotify-style music player for Android that plays music from **YouTube, SoundCloud, TikTok and Spotify links** and can **download songs for offline listening**.

## Download

Every push builds the app on GitHub Actions and publishes it to the **`tunio-latest`** release of this repository (Releases → *Tunio (latest build)*):

- **Tunio-arm64.apk**: for almost every phone since 2017. Start with this one.
- **Tunio-universal.apk**: works on every device but is bigger. Use it if the arm64 file won't install.

Open the APK on your phone and allow "install unknown apps" if Android asks. The first launch takes ~20 s while it unpacks the music engine and updates yt-dlp.

## What it does

| | |
|---|---|
| **Search** | YouTube and SoundCloud, with source chips in the Search tab. |
| **Links** | Paste a link in Search, or use **Share → Tunio** from the YouTube, SoundCloud, TikTok or Spotify app. Works for songs, playlists, albums, channels and TikTok profiles. Lists can be saved as playlists. |
| **Spotify** | Spotify audio is DRM-locked. Tunio reads the song info (title, artist, length) from Spotify's public embed page and plays the best-matching YouTube upload. The match is chosen by duration and title, and "live", "cover" and "remix" versions are avoided. |
| **Private Spotify playlists** | Library → **Spotify**: log in once and import any of your playlists (private ones too) or your Liked Songs. This needs a free Spotify developer app (steps below). No-login alternative: in the Spotify **desktop** app, select songs (Ctrl+A), copy them (Ctrl+C) and paste into Tunio's Search. |
| **Offline** | Tap ⋮ → *Download* on a song, or *Download N* on a playlist or Liked songs. Downloads are listed in Library → Downloads and play with no connection. |
| **Library** | Liked songs, playlists (create/rename/delete), recently played. |
| **Player** | Full-screen player, queue (play next / add to queue), shuffle, repeat, seek. Background playback with lock-screen and notification controls. |

Every song that has played once is cached (up to 600 MB), so replays start instantly. The next song in the queue is fetched in the background while the current one plays.

## Spotify login (one-time, for private playlists)

1. Go to <https://developer.spotify.com/dashboard>, log in with your Spotify account and **Create app**. Any name and description will do.
2. **Redirect URI:** `tunio://spotify-callback`. Under *APIs used*, tick **Web API**, then save.
3. Open the app's settings, copy the **Client ID** and paste it in Tunio → Settings → *Spotify account*.
4. Tap **Log in with Spotify** and accept. Tunio then opens your playlist list.

Tunio only asks to *read* playlists and saved songs. Nothing is changed on your Spotify account. If Spotify says your account isn't allowed, add your account's e-mail under *User Management* in the dashboard app.
Spotify doesn't let apps read the playlists it generates itself (Daily Mix, Discover Weekly, …). Copy-paste the songs from the desktop app instead.

## How it works

- **Engine:** [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), the library used by the [Seal](https://github.com/JunkFood02/Seal) downloader. It packages yt-dlp, Python 3.12, QuickJS and ffmpeg for Android. yt-dlp updates itself from GitHub once a day, and you can update it manually in Settings.
- **Player:** AndroidX Media3 / ExoPlayer with a MediaSession service.
- **UI:** Jetpack Compose.
- **Storage:** downloads are kept in the app's own folder (`Android/data/ch.tunio.app/files/Music/offline`). The library (likes and playlists) is saved in `library.json` in the app's private storage. Uninstalling the app deletes both.

```
app/src/main/java/ch/tunio/app/
  engine/Engine.kt       yt-dlp calls: search, read link, download audio, self-update
  engine/Spotify.kt      Spotify link → song info (public embed page, no account needed)
  engine/SpotifyAccount.kt  optional Spotify login (PKCE) for private playlists + Liked Songs
  engine/Matching.kt     picks the YouTube upload matching a Spotify song
  engine/AudioStore.kt   cache + offline downloads
  player/                queue + ExoPlayer + background service
  data/                  models + library (likes, playlists, history)
  ui/                    Compose screens
```

## Build

```
./gradlew test assembleRelease
```

The APKs end up in `app/build/outputs/apk/release/`.

## Notes

- TikTok has no public search, so TikTok sounds and videos are added by link or share.
- Spotify playlists are read from the public embed page, which may only list the first ~100 songs of very long playlists.
- If a site changes and songs stop loading, use Settings → *Update yt-dlp now*. There is also an *Extra yt-dlp options* field for workarounds.
- This app is for personal use. Downloading from these services may go against their terms of service.
