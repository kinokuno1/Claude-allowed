from tunio_desktop.core import Track
from tunio_desktop.lyrics import Lyrics, guesses, lookup, parse_lrc

LRC = "[00:12.50] Paper lanterns drifting on the bay\n[00:17.00]Hold the light\n[00:20.10][01:20.10] Chorus\n[ar:ignored]\n"


def test_parse_lrc():
    assert parse_lrc(LRC) == [[12.5, "Paper lanterns drifting on the bay"], [17.0, "Hold the light"], [20.1, "Chorus"], [80.1, "Chorus"]]


def test_guesses_youtube_titles():
    t = Track("yt_1", "YOUTUBE", "The Velvet Static - Paper Lanterns (Official Video) [4K]", "TheVelvetStaticVEVO", 207)
    assert guesses(t)[0] == ("The Velvet Static", "Paper Lanterns")
    t = Track("yt_2", "YOUTUBE", "Paper Lanterns (Remastered 2001)", "The Velvet Static - Topic", 207)
    assert guesses(t)[0] == ("The Velvet Static", "Paper Lanterns")
    t = Track("yt_3", "YOUTUBE", "Drake - Rich Flex ft. 21 Savage (Lyrics)", "Lyrics Hub", 239)
    assert ("Drake", "Rich Flex") in guesses(t)


def fake_http(db):
    calls = []

    def http(path, params):
        calls.append((path, params))
        return db(path, params)
    http.calls = calls
    return http


def test_exact_hit_wins():
    rec = {"trackName": "Paper Lanterns", "artistName": "The Velvet Static", "duration": 207, "syncedLyrics": LRC, "plainLyrics": "x"}
    http = fake_http(lambda p, q: rec if p == "get" and q["track_name"] == "Paper Lanterns" else None)
    r = lookup(Track("yt_1", "YOUTUBE", "The Velvet Static - Paper Lanterns (Official Video)", "Vevo", 207), http)
    assert r["found"] and r["synced"][0] == [12.5, "Paper lanterns drifting on the bay"]
    assert http.calls[0][1]["duration"] == 207


def test_search_prefers_synced_same_length():
    results = [
        {"trackName": "Paper Lanterns (Live)", "artistName": "The Velvet Static", "duration": 290, "syncedLyrics": LRC},
        {"trackName": "Paper Lanterns", "artistName": "The Velvet Static", "duration": 205, "plainLyrics": "plain only"},
        {"trackName": "Paper Lanterns", "artistName": "The Velvet Static", "duration": 208, "syncedLyrics": LRC},
    ]
    http = fake_http(lambda p, q: None if p == "get" else results)
    r = lookup(Track("yt_1", "YOUTUBE", "Paper Lanterns", "The Velvet Static", 207), http)
    assert r["found"] and r["synced"] and r["plain"] is None


def test_wrong_song_is_not_shown():
    http = fake_http(lambda p, q: None if p == "get" else [{"trackName": "Other", "artistName": "Someone", "duration": 207, "plainLyrics": "nope"}])
    assert lookup(Track("yt_9", "YOUTUBE", "Paper Lanterns", "The Velvet Static", 207), http)["found"] is False


def test_cache(tmp_path):
    rec = {"trackName": "Paper Lanterns", "artistName": "The Velvet Static", "duration": 207, "syncedLyrics": LRC}
    http = fake_http(lambda p, q: rec if p == "get" else [])
    ly = Lyrics(str(tmp_path), http)
    t = Track("yt_1", "YOUTUBE", "The Velvet Static - Paper Lanterns", "", 207)
    assert ly.get(t)["found"] and ly.get(t)["found"]
    assert len(http.calls) == 1
