import json
import tempfile

from tunio_desktop.core import Track
from tunio_desktop.wordsync import WordSync, estimate, parse_richsync, parse_yrc

T = Track("yt_x", "YOUTUBE", "The Velvet Static - Paper Lanterns (Official Video)", "TheVelvetStaticVEVO", 200)


def test_richsync_glues_spaces_and_ends_words():
    body = json.dumps([{"ts": 10.0, "te": 12.0, "l": [{"c": "Paper", "o": 0}, {"c": " ", "o": 0.4}, {"c": "lanterns", "o": 0.5}], "x": "Paper lanterns"}])
    lines = parse_richsync(body)
    assert lines == [{"t": 10.0, "e": 12.0, "words": [[10.0, 10.5, "Paper "], [10.5, 12.0, "lanterns"]]}]


def test_yrc_parsing_skips_metadata():
    yrc = '{"t":0,"c":[{"tx":"credits"}]}\n[12340,1500](12340,500,0)Hold (12840,1000,0)on'
    assert parse_yrc(yrc) == [{"t": 12.34, "e": 13.84, "words": [[12.34, 12.84, "Hold "], [12.84, 13.84, "on"]]}]


def test_estimate_shares_line_time_by_word_length():
    lines = estimate([[5.0, "Hold the light"], [8.0, ""], [9.0, "Again"]], 12)
    first = lines[0]["words"]
    assert [w[2] for w in first] == ["Hold ", "the ", "light"]
    assert first[0][0] == 5.0 and abs(first[-1][1] - lines[0]["e"]) < 0.01
    assert first[1][1] - first[1][0] < first[2][1] - first[2][0]  # longer words get more time
    assert len(lines) == 2  # empty lines are skipped


def test_sources_in_order_and_cache():
    calls = []

    def http(url, headers=None):
        calls.append(url)
        if "token.get" in url:
            return {"message": {"body": {"user_token": "tok"}}}
        if "matcher.track.get" in url:
            return {"message": {"header": {"status_code": 200}, "body": {"track": {
                "track_id": 7, "has_richsync": 1, "artist_name": "The Velvet Static", "track_name": "Paper Lanterns", "track_length": 201}}}}
        if "richsync.get" in url:
            return {"message": {"body": {"richsync": {"richsync_body": json.dumps([{"ts": 1, "te": 2, "l": [{"c": "Hi", "o": 0}]}])}}}}
        return {}

    with tempfile.TemporaryDirectory() as d:
        ws = WordSync(d, http)
        r = ws.get(T, {"synced": [[1.0, "Hi"]]})
        assert r["source"] == "Musixmatch" and not r["estimated"]
        n = len(calls)
        assert WordSync(d, http).get(T, {})["source"] == "Musixmatch" and len(calls) == n  # from disk


def test_falls_back_to_estimate():
    with tempfile.TemporaryDirectory() as d:
        r = WordSync(d, lambda url, headers=None: {}).get(T, {"synced": [[1.0, "Paper lanterns"]]})
        assert r["estimated"] and r["lines"][0]["words"][0][2] == "Paper "
        none = WordSync(d + "/x", lambda url, headers=None: {}).get(Track("yt_y", "YOUTUBE", "Nothing"), {"synced": None})
        assert none["lines"] == [] and none["error"]
