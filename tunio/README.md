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
| **Offline** | Tap ⋮ → *Download* on a song, or *Download N* on a playlist or Liked songs. Downloads keep going when you leave the app or lock the phone, with a progress notification. They are listed in Library → Downloads and play with no connection. |
| **Autoplay** | When 3 or fewer songs are left and you're on Wi-Fi, Tunio adds similar songs from YouTube's "Mix" for the current song, like Spotify's autoplay. On Wi-Fi it also loads the next songs in the background. You can turn this off in Settings. |
| **Library** | Liked songs, playlists (create/rename/delete), recently played. |
| **Player** | Full-screen player, queue (play next / add to queue), shuffle, repeat, seek. Background playback with lock-screen and notification controls. |

**Speed:**
- yt-dlp runs as one long-lived background worker. Python and yt-dlp load once, while the app starts, instead of for every search or song.
- The daily yt-dlp update happens in the background and never blocks you.
- Playback starts after 0.5 s of audio.
- Recent searches are remembered for 10 minutes.
- The top search results, and the first song of a pasted link, are prepared while you read them.

Songs start streaming as soon as yt-dlp has found the audio URL. They're saved while they play (up to 500 MB), so replays start instantly and need no network. The next song in the queue is prepared in the background while the current one plays. yt-dlp's cache is kept, so YouTube's player code is only downloaded and processed once (when YouTube updates it), not for every song.

## Spotify login (one-time, for private playlists)

**Needs Spotify Premium.** Since February 2026 Spotify only lets developer apps work if the account that owns the app has Premium. That app is the one you create below. Spotify also only shares the songs of playlists you created or collaborate on. For playlists you just follow, Tunio uses the public page.
**Without Premium**, there are two options. You can make the playlist public in Spotify and share its link to Tunio (you can make it private again afterwards; Tunio keeps its own copy once saved). Or, in the Spotify **desktop** app, open the playlist, press Ctrl+A then Ctrl+C, and paste into Tunio's Search.

1. Go to <https://developer.spotify.com/dashboard>, log in with your Spotify account and **Create app**. Any name and description will do.
2. **Redirect URI:** `http://127.0.0.1:43819/callback`. Type it exactly, with `127.0.0.1`, not `localhost`. Spotify no longer accepts custom addresses like `tunio://…` for new apps. Under *APIs used*, tick **Web API**, then save.
3. Open the app's settings, copy the **Client ID** and paste it in Tunio → Settings → *Spotify account*.
4. Tap **Log in with Spotify** and accept. The browser then shows "Logged in to Spotify": tap *Back to Tunio*. While you log in, Tunio shows a small "Waiting for Spotify login…" notification. That keeps Android from freezing Tunio while the browser is in front. Tunio listens on that local address on your phone only, then stops.
   If the browser still ends on *This site can't be reached* (127.0.0.1…), share that page to Tunio (Share → Tunio), or copy its address into the box under *Log in with Spotify*. The login code is in that address, so Tunio finishes from there.

Tunio only asks to *read* playlists and saved songs. Nothing is changed on your Spotify account. If Spotify says your account isn't allowed, add your account's e-mail under *User Management* in the dashboard app.
Spotify doesn't let apps read the playlists it generates itself (Daily Mix, Discover Weekly, …). Copy-paste the songs from the desktop app instead.

## Look

"Liquid glass" design using [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass): the floating tab bar, mini player, buttons and cards are glass that **bends (refracts) and blurs** what's behind them through a lens at their edges, with a little chromatic dispersion. Scroll a list under the tab bar to see it. Refraction needs Android 13+ and blur needs Android 12+. Older phones get a frosted look instead.
Menus (Home, Search, Library, Settings) sit on a plain violet→pink colour gradient. Inside a playlist, and in the player, the background is the cover art, heavily blurred. In the tab bar the glass pill braces, then stretches toward the tab you tap with a jiggly overshoot. Icons are outlined when idle and filled when selected, with a small flash and wiggle.
Colours are the same violet/pink as before. Text and icon colours were checked against their worst-case backgrounds, including glass over the brightest light and the player over a pure-white cover, so nothing dark sits on black. The lowest text contrast is 5.6:1, above the WCAG 4.5:1 minimum.

## How it works

- **Engine:** [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), the library used by the [Seal](https://github.com/JunkFood02/Seal) downloader. It packages yt-dlp, Python 3.12, QuickJS and ffmpeg for Android. yt-dlp updates itself from GitHub once a day, and you can update it manually in Settings.
- **Player:** AndroidX Media3 / ExoPlayer with a MediaSession service.
- **UI:** Jetpack Compose with Kyant0's Backdrop library for the liquid glass.
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
