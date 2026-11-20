"""Same cases as the Android app's unit tests (ParsingTest, SpotifyJsonTest, AutoplayTest, StreamInfoTest)."""
from tunio_desktop.core import (
    Track, extract_url, format_duration, looks_like_url, make_id, parse_result, parse_spotify_embed,
    pick_match, pick_similar, source_of, spotify_link, spotify_track_ids, stream_info, youtube_id,
)


def test_youtube_flat_search():
    r = parse_result({"_type": "playlist", "entries": [
        {"_type": "url", "ie_key": "Youtube", "id": "jfKfPfyJRdk", "url": "https://www.youtube.com/watch?v=jfKfPfyJRdk",
         "title": "lofi hip hop radio", "duration": None, "channel": "Lofi Girl",
         "thumbnails": [{"url": "https://i.ytimg.com/a.jpg"}, {"url": "https://i.ytimg.com/b.jpg"}]},
        {"_type": "url", "ie_key": "Youtube", "id": "abc123", "url": "https://www.youtube.com/watch?v=abc123",
         "title": "Song", "duration": 215.0, "channel": "Artist - Topic"},
    ]})
    a, b = r.tracks
    assert a.id == "yt_jfKfPfyJRdk" and a.source == "YOUTUBE" and a.artist == "Lofi Girl" and a.durationSec == 0
    assert a.thumbnail == "https://i.ytimg.com/b.jpg"
    assert b.artist == "Artist" and b.durationSec == 215
    assert b.thumbnail == "https://i.ytimg.com/vi/abc123/hqdefault.jpg"


def test_soundcloud_entry():
    t = parse_result({"entries": [{"ie_key": "Soundcloud", "id": "123456", "url": "https://api.soundcloud.com/tracks/123456",
                                   "webpage_url": "https://soundcloud.com/artist/song", "title": "Song", "uploader": "Artist",
                                   "duration": 180.5}]}, "SOUNDCLOUD").tracks[0]
    assert t.id == "sc_123456" and t.url == "https://soundcloud.com/artist/song" and t.durationSec == 180


def test_tiktok_track_name_unless_original_sound():
    t = parse_result({"id": "7300", "extractor_key": "TikTok", "webpage_url": "https://www.tiktok.com/@u/video/7300",
                      "title": "my dance", "track": "Real Song", "artist": "Real Artist"}).tracks[0]
    assert t.source == "TIKTOK" and t.title == "Real Song" and t.artist == "Real Artist"
    t2 = parse_result({"id": "7301", "extractor_key": "TikTok", "webpage_url": "https://www.tiktok.com/@u/video/7301",
                       "title": "funny clip", "track": "original sound - u"}).tracks[0]
    assert t2.title == "funny clip"


def test_nested_entries_and_json_text():
    r = parse_result('[log]\n{"_type":"playlist","entries":[{"_type":"playlist","entries":[{"id":"a1","ie_key":"Youtube","title":"A"}]},'
                     '{"id":"b2","ie_key":"Youtube","title":"B"}]}')
    assert [t.title for t in r.tracks] == ["A", "B"]


def test_links():
    assert extract_url("Look! https://vm.tiktok.com/ZMabc/") == "https://vm.tiktok.com/ZMabc/"
    assert extract_url("daft punk") is None
    assert looks_like_url("youtu.be/abc") and not looks_like_url("daft punk around the world")
    assert source_of("https://on.soundcloud.com/x") == "SOUNDCLOUD"
    assert make_id("OTHER", "https://example.com/a b?c=d").replace("_", "").replace("-", "").isalnum()
    assert format_duration(185) == "3:05" and format_duration(3661) == "1:01:01" and format_duration(0) == ""


def test_spotify_links_and_paste():
    assert spotify_link("https://open.spotify.com/intl-fr/track/4uLU6hMCjMI75M1A2tKUQC") == ("track", "4uLU6hMCjMI75M1A2tKUQC")
    assert spotify_link("spotify:album:1ATL5GLyefJaxhQzSPVrLX") == ("album", "1ATL5GLyefJaxhQzSPVrLX")
    glued = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQChttps://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"
    assert spotify_track_ids(glued) == ["4uLU6hMCjMI75M1A2tKUQC", "0VjIjW4GlUZAMYd2vXMi3b"]


def test_spotify_embed_playlist():
    html = ('<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{'
            '"type":"playlist","name":"Chill Mix","coverArt":{"sources":[{"url":"https://i.scdn.co/image/cover"}]},'
            '"trackList":[{"uri":"spotify:track:AAA","title":"Song One","subtitle":"Artist A,\\u00a0Artist B","duration":200000}]'
            '}}}}}}</script>')
    r = parse_spotify_embed(html, "playlist", "PL")
    t = r.tracks[0]
    assert r.title == "Chill Mix" and r.cover == "https://i.scdn.co/image/cover"
    assert t.id == "sp_AAA" and t.artist == "Artist A, Artist B" and t.durationSec == 200 and t.download_target is None


def test_matching_and_similar():
    target = Track("sp_1", "SPOTIFY", "Get Lucky", "Daft Punk", 369)
    live = Track("yt_a", "YOUTUBE", "Daft Punk - Get Lucky (Live)", "Daft Punk", 372)
    video = Track("yt_b", "YOUTUBE", "Daft Punk - Get Lucky (Official Video)", "Daft Punk", 248)
    audio = Track("yt_c", "YOUTUBE", "Get Lucky", "Daft Punk", 368)
    assert pick_match([live, video, audio], target).id == "yt_c"

    def yt(i, title, d=200):
        return Track(f"yt_{i}", "YOUTUBE", title, "A", d, url=f"https://www.youtube.com/watch?v={i}")
    seed, queued = yt("seed0000000", "Artist - Song"), yt("queued00000", "Other Song")
    mix = [seed, yt("dupe0000000", "Artist - Song (Official Video)"), queued, yt("long0000000", "1h mix", 3600),
           yt("good1000000", "Nice Track"), yt("good2000000", "Another Track"), yt("good3000000", "Third")]
    assert [t.id for t in pick_similar(mix, [seed, queued], 2)] == ["yt_good1000000", "yt_good2000000"]
    assert youtube_id(seed) == "seed0000000"
    assert youtube_id(Track("sp_2", "SPOTIFY", "x", matchedUrl="https://www.youtube.com/watch?v=abcdefghijk&t=3")) == "abcdefghijk"


def test_stream_info():
    assert stream_info({"protocol": "https", "url": "https://a/b", "http_headers": {"User-Agent": "UA"}}) == \
        {"url": "https://a/b", "headers": {"User-Agent": "UA"}}
    assert stream_info({"protocol": "m3u8_native", "url": "https://x.m3u8"}) is None
    assert stream_info({"protocol": "https", "url": "https://t", "cookies": "tt=1"}) is None
