import os
import tempfile

from tunio_desktop.artwork import Artwork, lookup, youtube_id
from tunio_desktop.core import Track


def fake(answers):
    calls = []

    def http(url):
        calls.append(url)
        for key, value in answers.items():
            if key in url:
                return value
        return {}
    http.calls = calls
    return http


def test_spotify_song_gets_its_own_cover_from_oembed():
    t = Track("sp_abc", "SPOTIFY", "Paper Lanterns", "The Velvet Static", 200, "https://playlist-cover", "https://open.spotify.com/track/abc")
    http = fake({"open.spotify.com/oembed": {"thumbnail_url": "https://cover-of-the-song"}})
    assert lookup(t, http=http) == "https://cover-of-the-song"
    assert "track%2Fabc" in http.calls[0]


def test_youtube_uses_video_thumbnail_without_network():
    t = Track("yt_dQw4w9WgXcQ", "YOUTUBE", "Some Song (Official Video)", "Some Artist")
    http = fake({})
    assert lookup(t, http=http) == "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
    assert http.calls == []
    assert youtube_id(Track("sp_x", "SPOTIFY", "x", matchedUrl="https://www.youtube.com/watch?v=dQw4w9WgXcQ")) == "dQw4w9WgXcQ"


def test_broken_youtube_thumbnail_falls_back_to_itunes_with_matching_artist():
    t = Track("yt_dQw4w9WgXcQ", "YOUTUBE", "Paper Lanterns", "The Velvet Static")
    http = fake({"itunes.apple.com": {"results": [
        {"artistName": "Someone Else", "trackName": "Paper Lanterns", "artworkUrl100": "https://wrong/100x100bb.jpg"},
        {"artistName": "The Velvet Static", "trackName": "Paper Lanterns", "artworkUrl100": "https://right/100x100bb.jpg"},
    ]}})
    assert lookup(t, broken="https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", http=http) == "https://right/600x600bb.jpg"


def test_deezer_when_itunes_has_nothing():
    t = Track("sc_1", "SOUNDCLOUD", "Paper Lanterns", "The Velvet Static")
    http = fake({"api.deezer.com": {"data": [{"title": "Paper Lanterns", "artist": {"name": "The Velvet Static"}, "album": {"cover_xl": "https://dz"}}]}})
    assert lookup(t, http=http) == "https://dz"


def test_cache_and_misses():
    with tempfile.TemporaryDirectory() as d:
        http = fake({})
        art = Artwork(os.path.join(d, "a.json"), http)
        t = Track("sc_2", "SOUNDCLOUD", "Nothing", "Nobody")
        assert art.get(t) is None
        n = len(http.calls)
        assert art.get(t) is None and len(http.calls) == n  # miss is remembered
        good = fake({"api.deezer.com": {"data": [{"title": "Nothing", "artist": {"name": "Nobody"}, "album": {"cover_xl": "https://c"}}]}})
        art2 = Artwork(os.path.join(d, "b.json"), good)
        assert art2.many([t]) == {"sc_2": "https://c"}
        assert Artwork(os.path.join(d, "b.json"), fake({})).get(t) == "https://c"  # from disk
