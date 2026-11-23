"""Jams on the home server: create, find, join (password), shared queue, long polling, sync position."""
import json
import os
import tempfile
import threading
import time
import urllib.error
import urllib.request

os.environ.setdefault("TUNIO_DATA_DIR", tempfile.mkdtemp())
os.environ.setdefault("TUNIO_CACHE_DIR", tempfile.mkdtemp())

from tunio_desktop import jam  # noqa: E402
from tunio_desktop import homeserver as hs  # noqa: E402
from tests.test_homeserver import FakeSite, serve  # noqa: E402

T1 = {"id": "yt_aaaaaaaaaaa", "source": "YOUTUBE", "title": "First made-up song", "artist": "Band",
      "url": "https://www.youtube.com/watch?v=aaaaaaaaaaa", "durationSec": 200}
T2 = {"id": "sp_2", "source": "SPOTIFY", "title": "Second", "artist": "Other", "url": "https://open.spotify.com/track/2"}
T3 = {"id": "sc_3", "source": "SOUNDCLOUD", "title": "Third", "artist": "Third", "url": "https://soundcloud.com/x/y"}


def test_queue_actions_and_positions():
    warmed = []
    hub = jam.JamHub(warmed.extend)
    made = hub.create("Party", "", "Ana", [T1, T2], index=0, position=12, playing=True)
    jid, host = made["id"], made["token"]
    assert made["state"]["position"] >= 12 and made["state"]["playing"]
    assert [t["id"] for t in warmed] == ["yt_aaaaaaaaaaa", "sp_2"]

    guest = hub.join(jid, "Bo", "")["token"]
    s = hub.act(jid, guest, {"action": "add", "tracks": [T3]})
    assert [t["id"] for t in s["queue"]] == ["yt_aaaaaaaaaaa", "sp_2", "sc_3"]
    assert s["queue"][2]["addedBy"] == "Bo"
    # move the last song right after the current one
    s = hub.act(jid, guest, {"action": "move", "qid": s["queue"][2]["qid"], "to": 1})
    assert [t["id"] for t in s["queue"]] == ["yt_aaaaaaaaaaa", "sc_3", "sp_2"] and s["index"] == 0
    # the host's player finishes the song: "next" from that song only counts once
    first = s["queue"][0]["qid"]
    s = hub.act(jid, host, {"action": "next", "from": first})
    s = hub.act(jid, host, {"action": "next", "from": first})
    assert s["index"] == 1 and s["position"] < 1
    s = hub.act(jid, guest, {"action": "pause"})
    assert not s["playing"]
    s = hub.act(jid, guest, {"action": "seek", "position": 42})
    assert abs(s["position"] - 42) < 0.01
    # removing the current song plays the next one
    s = hub.act(jid, guest, {"action": "remove", "qid": s["queue"][1]["qid"]})
    assert s["queue"][s["index"]]["id"] == "sp_2"
    # the end of the queue, then a new song starts playing
    s = hub.act(jid, host, {"action": "next"})
    assert s["index"] == len(s["queue"]) and not s["playing"]
    s = hub.act(jid, guest, {"action": "add", "tracks": [T3]})
    assert s["queue"][s["index"]]["id"] == "sc_3" and s["playing"]


def test_password_and_listing():
    hub = jam.JamHub()
    jid = hub.create("Secret", "pass1234", "Ana")["id"]
    assert hub.list()[0]["locked"] is True and hub.list()[0]["name"] == "Secret"
    try:
        hub.join(jid, "Bo", "nope")
        raise AssertionError("joined with a wrong password")
    except jam.JamError as e:
        assert e.code == 401
    assert hub.join(jid, "Bo", "pass1234")["token"]


def test_long_poll_wakes_on_change_and_end():
    hub = jam.JamHub()
    made = hub.create("Live", "", "Ana")
    jid, host = made["id"], made["token"]
    guest = hub.join(jid, "Bo", "")["token"]
    v = hub.state(jid, guest, 0, 0)["version"]
    threading.Timer(0.3, lambda: hub.act(jid, host, {"action": "add", "tracks": [T1]})).start()
    t0 = time.time()
    s = hub.state(jid, guest, v, 10)
    assert time.time() - t0 < 5 and s["queue"][0]["id"] == T1["id"]
    try:
        hub.act(jid, guest, {"action": "end"})
        raise AssertionError("a guest ended the Jam")
    except jam.JamError:
        pass
    hub.act(jid, host, {"action": "end"})
    assert hub.list() == []


def test_spotify_songs_play_from_youtube():
    assert jam.target(T2).startswith("ytsearch1:Other - Second")
    assert jam.target({**T2, "matchedUrl": "https://youtu.be/bbbbbbbbbbb"}) == "https://youtu.be/bbbbbbbbbbb"
    assert jam.target(T1) == T1["url"]


def _req(url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(r, timeout=30) as resp:
        return resp.status, resp.read()


def test_http_flow_with_guest_audio():
    site, site_url = serve(FakeSite)
    d = tempfile.mkdtemp()
    cfg = hs.Config(os.path.join(d, "server.json"))
    cfg.update(music_dir=os.path.join(d, "music"), password="abcd-efgh-jkmn")
    srv = hs.Server(cfg)
    srv.fetcher.audio_format = lambda url: {"url": site_url + "/song", "protocol": "http", "ext": "m4a", "http_headers": {},
                                            "title": "Song", "artist": "Band", "duration": 200}
    httpd, base = serve(hs.make_handler(srv))
    # only the server's own phones start Jams
    try:
        _req(base + "/api/jams", {"name": "x"})
        raise AssertionError("started a Jam without the server password")
    except urllib.error.HTTPError as e:
        assert e.code == 401
    _, b = _req(base + "/api/jams", {"name": "Kitchen", "you": "Ana", "tracks": [T1]}, {"Authorization": "Bearer abcd-efgh-jkmn"})
    made = json.loads(b)
    jid = made["id"]
    _, b = _req(base + "/api/jams")
    assert json.loads(b)["jams"][0]["name"] == "Kitchen"
    status, page = _req(base + "/jam/" + jid)
    assert status == 200 and b"Kitchen" in page and b"tunio://jam" in page
    _, b = _req(base + f"/api/jam/{jid}/join", {"you": "Bo"})
    guest = json.loads(b)["token"]
    _, b = _req(base + f"/api/jam/{jid}?since=0&wait=0", headers={"X-Jam-Token": guest})
    state = json.loads(b)
    assert state["you"]["name"] == "Bo" and state["queue"][0]["title"] == T1["title"]
    # a guest playing along gets the song from the server (only songs in the Jam)
    qid = state["queue"][0]["qid"]
    status, audio = _req(base + f"/api/jam/{jid}/audio?qid={qid}&token={guest}")
    assert status == 200 and len(audio) > 1_000_000
    try:
        _req(base + f"/api/jam/{jid}/audio?qid=nope&token={guest}")
        raise AssertionError("served a song that isn't in the Jam")
    except urllib.error.HTTPError as e:
        assert e.code == 404
    httpd.shutdown()
    site.shutdown()
