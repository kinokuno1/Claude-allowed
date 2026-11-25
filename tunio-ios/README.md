# Tunio for iPhone and iPad

The iOS version of Tunio, written in Swift (SwiftUI). It has search, links, playlists, offline downloads, synced lyrics, autoplay and your Tunio home server, like the other versions.

## Install it with iloader

Apple only lets App Store apps install directly, so Tunio is *sideloaded*: [iloader](https://github.com/nab138/iloader) signs it with your own Apple ID and puts it on your iPhone. It's free, and you need a computer (Windows, Mac or Linux) and a USB cable.

1. **Download** `Tunio.ipa` from the [tunio-latest release](https://github.com/kinokuno1/Claude-allowed/releases/tag/tunio-latest).
2. **Get iloader** from [its GitHub page](https://github.com/nab138/iloader/releases) or [iloader.app](https://iloader.app) (the only official places) and open it.
3. **Plug in your iPhone**, unlock it and tap **Trust** if it asks.
4. **Sign in** to iloader with your Apple ID. A free one works.
5. Choose to install an **IPA file** and pick `Tunio.ipa`.
6. On the iPhone, turn on **Developer Mode** if it asks: Settings → Privacy & Security → Developer Mode, then restart.
7. If the app won't open ("Untrusted Developer"), go to Settings → General → VPN & Device Management, tap your Apple ID, then **Trust**.

**With a free Apple ID, the app stops opening after 7 days.** Plug in and install it again with iloader to renew it. Your library and downloads stay, because they're kept as long as you don't delete the app. To renew without a computer, install SideStore with iloader; it refreshes your apps over Wi-Fi. A paid Apple Developer account makes it last a year.

## What it does

- **Search** YouTube or SoundCloud, or **paste a link**: a YouTube, SoundCloud or Spotify song, playlist or album. Spotify songs play from YouTube, matched by artist, title and length, and imported Spotify playlists get each song's own cover. TikTok links need the home server.
- **Library:** Liked songs, playlists (create, rename, reorder, delete, *Save as playlist*), Recently played.
- **Offline:** long-press a song → Download, or ⤓ on a playlist. The files are also in the Files app: On My iPhone → Tunio → Offline.
- **Player:** plays in the background, with lock-screen and Control Center controls, shuffle, repeat and a queue you can reorder. The background drifts with the cover's colours. **Autoplay** adds similar songs when 3 or fewer are left.
- **Lyrics:** synced lyrics from [LRCLIB](https://lrclib.net) that glide line by line. Tap a line to jump to it.
- **Home server:** if you have the Tunio home server (Raspberry Pi), the iPhone uses it for searches and audio, so songs start faster and TikTok links work. Connect it by scanning the QR code it shows after installing (it opens a page with a button that sets up the app), or in Settings → Home server: tap *Find it on my Wi-Fi*, or type its address and password. When it can't be reached, Tunio works on its own.
- **Look:** the same 60-30-10 palette as the other versions. iOS 26 uses Apple's Liquid Glass for the tab bar and mini player.

## How it works

- **YouTube:** search, mixes and playlists come from YouTube's own web API (InnerTube). The audio link comes from [YouTubeKit](https://github.com/alexeichhorn/YouTubeKit), which picks an AAC (m4a) stream the iPhone can play.
- **SoundCloud:** its public web API.
- **Spotify:** its public embed pages, with no account needed.
- **Home server:** the same `/api/flat` and `/audio` endpoints the Android app uses.

YouTube changes things now and then. If songs stop playing, download the newest `Tunio.ipa`, since every build uses the newest YouTubeKit. Or set up the home server, which updates itself every night.

## Building

GitHub Actions builds it on macOS (`.github/workflows/build-tunio-ios.yml`):

1. [XcodeGen](https://github.com/yonaskolb/XcodeGen) turns `project.yml` into an Xcode project.
2. `xcodebuild` compiles it for iPhone without signing.
3. The app is zipped into `Payload/Tunio.app` → `Tunio.ipa`.

iloader signs it when installing. On a Mac:

```
brew install xcodegen
cd tunio-ios && xcodegen generate && open Tunio.xcodeproj
```
