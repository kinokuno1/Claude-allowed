"""Jams: listen together, like Spotify Jam. Hosted by the Tunio home server so every phone can reach it
(at home on the Wi-Fi, and from anywhere through the server's public address).

- The host (a phone set up with this server) starts a Jam: a name, open to all or with a password.
- Guests find it on the same Wi-Fi (the phone finds the server, then lists its Jams) or open a shared link.
- Everyone can add songs, reorder, remove, skip, pause. The server keeps the one shared queue and state.
- Music plays on the host's phone (its speaker / Bluetooth device); a guest can also play along on their
  own phone, in sync: the state carries the position at a server time, and phones correct their drift.
- The next songs are fetched ahead by the server, so they start instantly when it's their turn.

Phones follow the state with long polling: GET /api/jam/<id>?since=<version> answers as soon as anything changes.
"""
from __future__ import annotations

import hashlib
import secrets
import threading
import time

IDLE_END = 30 * 60      # a Jam ends when nobody has been seen for this long
HOST_GONE_END = 10 * 60  # ... or when the host has been away this long
MAX_QUEUE = 500
TRACK_FIELDS = ("id", "source", "title", "artist", "durationSec", "thumbnail", "url", "matchedUrl")


def _hash(pw: str, salt: str) -> str:
    return hashlib.sha256((salt + ":" + pw).encode()).hexdigest()


def target(t: dict) -> str | None:
    """What the server fetches for a queued song (Spotify songs play from YouTube, like on the phones)."""
    if t.get("source") == "SPOTIFY":
        if t.get("matchedUrl"):
            return t["matchedUrl"]
        q = f"{t.get('artist', '')} - {t.get('title', '')}".strip(" -")
        return f"ytsearch1:{q} audio" if q else None
    u = t.get("url") or ""
    return u if u.startswith(("http://", "https://")) else None


class JamError(Exception):
    def __init__(self, msg: str, code: int = 400):
        super().__init__(msg)
        self.code = code


class Jam:
    def __init__(self, name: str, password: str, host_name: str):
        self.id = secrets.token_urlsafe(6).replace("-", "a").replace("_", "b")
        self.name = (name or "").strip()[:60] or f"{host_name or 'Someone'}'s Jam"
        self.salt = secrets.token_hex(8)
        self.pw = _hash(password, self.salt) if password else ""
        self.created = time.time()
        self.members: dict[str, dict] = {}   # token -> {id, name, host, seen}
        self.queue: list[dict] = []
        self.index = -1
        self.playing = False
        self.pos = 0.0
        self.pos_at = time.time()
        self.version = 1
        self.ended = False
        self.cond = threading.Condition()

    # ---- members

    def add_member(self, name: str, host: bool) -> str:
        token = secrets.token_urlsafe(18)
        self.members[token] = {"id": secrets.token_hex(4), "name": (name or "").strip()[:40] or ("Host" if host else "Guest"),
                               "host": host, "seen": time.time()}
        return token

    def member(self, token: str) -> dict:
        m = self.members.get(token or "")
        if not m:
            raise JamError("You're not in this Jam (any more).", 403)
        m["seen"] = time.time()
        return m

    # ---- state

    def position(self, now: float | None = None) -> float:
        now = now or time.time()
        return self.pos + (now - self.pos_at if self.playing else 0.0)

    def _set_pos(self, p: float):
        self.pos, self.pos_at = max(0.0, float(p)), time.time()

    def _changed(self):
        self.version += 1
        self.cond.notify_all()

    def summary(self) -> dict:
        cur = self.queue[self.index] if 0 <= self.index < len(self.queue) else None
        host = next((m["name"] for m in self.members.values() if m["host"]), "")
        return {"id": self.id, "name": self.name, "locked": bool(self.pw), "host": host,
                "members": len(self.members), "now": cur and {"title": cur.get("title"), "artist": cur.get("artist")}}

    def snapshot(self, you: dict | None = None) -> dict:
        now = time.time()
        return {
            **self.summary(), "version": self.version, "ended": self.ended, "queue": self.queue, "index": self.index,
            "playing": self.playing, "position": round(self.position(now), 3), "serverTime": now,
            "people": [{"id": m["id"], "name": m["name"], "host": m["host"]} for m in self.members.values()],
            "you": you and {"id": you["id"], "name": you["name"], "host": you["host"]},
        }

    def _find(self, qid: str) -> int:
        for i, t in enumerate(self.queue):
            if t["qid"] == qid:
                return i
        raise JamError("That song isn't in the queue any more.", 404)

    def _start(self, i: int, play: bool = True):
        self.index = i
        self._set_pos(0)
        self.playing = play and 0 <= i < len(self.queue)

    def act(self, who: dict, body: dict) -> list[dict]:
        """Applies one action. Returns the songs to fetch ahead."""
        a = body.get("action")
        if self.ended:
            raise JamError("This Jam has ended.", 410)
        if a == "add":
            items = []
            for t in (body.get("tracks") or [])[:200]:
                if isinstance(t, dict) and t.get("id") and t.get("title"):
                    item = {k: t.get(k) for k in TRACK_FIELDS if t.get(k) is not None}
                    item.update(qid=secrets.token_hex(5), addedBy=who["name"])
                    items.append(item)
            if not items:
                raise JamError("No songs to add.")
            if len(self.queue) + len(items) > MAX_QUEUE:
                raise JamError("The queue is full.")
            nothing = not (0 <= self.index < len(self.queue))
            at = self.index + 1 if body.get("next") and not nothing else len(self.queue)
            self.queue[at:at] = items
            if body.get("play") or nothing:  # the first songs (or songs added after the end) start playing
                self._start(at)
        elif a == "remove":
            i = self._find(body.get("qid", ""))
            del self.queue[i]
            if i < self.index:
                self.index -= 1
            elif i == self.index:  # the song playing: the next one takes its place
                if i < len(self.queue):
                    self._start(i, self.playing)
                else:
                    self.index, self.playing = len(self.queue), False
        elif a == "move":
            i = self._find(body.get("qid", ""))
            to = max(0, min(int(body.get("to", 0)), len(self.queue) - 1))
            cur = self.queue[self.index]["qid"] if 0 <= self.index < len(self.queue) else None
            self.queue.insert(to, self.queue.pop(i))
            if cur:
                self.index = self._find(cur)
        elif a == "jump":
            self._start(self._find(body.get("qid", "")))
        elif a == "next":
            # the host's player sends {"from": qid} when a song ends: only once, even if it arrives twice
            if body.get("from") and not (0 <= self.index < len(self.queue) and self.queue[self.index]["qid"] == body["from"]):
                return []
            if self.index + 1 < len(self.queue):
                self._start(self.index + 1, True)
            else:  # the end of the queue: nothing playing until someone adds a song
                self.index, self.playing = len(self.queue), False
                self._set_pos(0)
        elif a == "prev":
            if (self.position() > 3 and self.index < len(self.queue)) or self.index <= 0:
                self._set_pos(0)
            else:
                self._start(min(self.index, len(self.queue)) - 1, True)
        elif a in ("play", "pause"):
            p = self.position()
            self.playing = a == "play" and 0 <= self.index < len(self.queue)
            self._set_pos(p)
        elif a == "seek":
            self._set_pos(float(body.get("position", 0)))
        elif a == "report":
            # the host's real position (buffering makes it slip a little): only a real difference wakes everyone
            if not who["host"] or not (0 <= self.index < len(self.queue)) or self.queue[self.index]["qid"] != body.get("qid"):
                return []
            playing = bool(body.get("playing"))
            p = float(body.get("position", 0))
            if playing == self.playing and abs(self.position() - p) < 0.6:
                return []
            self.playing = playing
            self._set_pos(p)
        else:
            raise JamError("Unknown action.")
        self._changed()
        return self.upcoming()

    def upcoming(self, n: int = 3) -> list[dict]:
        start = max(self.index, 0)
        return self.queue[start:start + n]


class JamHub:
    def __init__(self, warm=None):
        self.jams: dict[str, Jam] = {}
        self.lock = threading.Lock()
        self.warm = warm or (lambda tracks: None)  # fetches songs ahead (the server's cache)

    def _get(self, jid: str) -> Jam:
        with self.lock:
            self._expire()
            j = self.jams.get(jid or "")
        if not j:
            raise JamError("This Jam doesn't exist (any more).", 404)
        return j

    def _expire(self):
        now = time.time()
        for jid, j in list(self.jams.items()):
            seen = max((m["seen"] for m in j.members.values()), default=j.created)
            host_seen = max((m["seen"] for m in j.members.values() if m["host"]), default=j.created)
            if j.ended or now - seen > IDLE_END or now - host_seen > HOST_GONE_END:
                with j.cond:
                    j.ended = True
                    j._changed()
                del self.jams[jid]

    def create(self, name: str, password: str, host_name: str, tracks: list | None = None, index: int = 0,
               position: float = 0, playing: bool = False) -> dict:
        j = Jam(name, password, host_name)
        token = j.add_member(host_name, True)
        if tracks:
            with j.cond:
                j.act(j.members[token], {"action": "add", "tracks": tracks})
                j.index = max(0, min(int(index), len(j.queue) - 1))
                j.playing = bool(playing)
                j._set_pos(position)
        with self.lock:
            self.jams[j.id] = j
        self.warm(j.upcoming())
        return {"id": j.id, "token": token, "state": j.snapshot(j.members[token])}

    def list(self) -> list[dict]:
        with self.lock:
            self._expire()
            return [j.summary() for j in self.jams.values()]

    def join(self, jid: str, name: str, password: str) -> dict:
        j = self._get(jid)
        with j.cond:
            if j.pw and not secrets.compare_digest(_hash(password or "", j.salt), j.pw):
                raise JamError("Wrong password.", 401)
            token = j.add_member(name, False)
            j._changed()
            return {"id": j.id, "token": token, "state": j.snapshot(j.members[token])}

    def state(self, jid: str, token: str, since: int = 0, wait: float = 25) -> dict:
        j = self._get(jid)
        with j.cond:
            you = j.member(token)
            end = time.time() + max(0.0, min(wait, 30))
            while j.version <= since and not j.ended and time.time() < end:
                j.cond.wait(min(5, end - time.time()))
                you["seen"] = time.time()
            return j.snapshot(you)

    def act(self, jid: str, token: str, body: dict) -> dict:
        j = self._get(jid)
        with j.cond:
            you = j.member(token)
            if body.get("action") == "leave":
                j.members.pop(token, None)
                if you["host"]:
                    j.ended = True
                j._changed()
                return {"ok": True}
            if body.get("action") == "end":
                if not you["host"]:
                    raise JamError("Only the host can end the Jam.", 403)
                j.ended = True
                j._changed()
                return {"ok": True}
            ahead = j.act(you, body)
            snap = j.snapshot(you)
        if ahead:
            self.warm(ahead)
        return snap

    def track_target(self, jid: str, token: str, qid: str) -> tuple[str, dict]:
        """Where a guest playing along fetches a queued song from (only songs that are in the Jam)."""
        j = self._get(jid)
        with j.cond:
            j.member(token)
            t = j.queue[j._find(qid)]
        url = target(t)
        if not url:
            raise JamError("This song can't be played here.", 404)
        return url, {"title": t.get("title", ""), "artist": t.get("artist", "")}


def page(jam_id: str, summary: dict | None) -> str:
    """What a shared Jam link shows in a browser: the Jam, and a button that opens it in Tunio."""
    import html
    import json
    name = html.escape((summary or {}).get("name") or "This Jam has ended")
    host = html.escape((summary or {}).get("host") or "")
    sub = f"Hosted by {host} · {summary['members']} listening" if summary else "Ask the host for a new link."
    lock = " · password needed" if summary and summary.get("locked") else ""
    btn = ('<a class="btn" id="go" href="#">Open in Tunio</a><p class="muted">Tunio must be installed on this phone.</p>'
           if summary else "")
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>{name}</title>
<meta name="viewport" content="width=device-width,initial-scale=1"><style>
:root{{color-scheme:dark}}body{{margin:0;font:16px/1.5 system-ui,sans-serif;color:#F7F7FA;background:#0E0E12;min-height:100vh;
display:flex;align-items:center;justify-content:center}}main{{max-width:420px;padding:28px 20px;text-align:center}}
.disc{{width:96px;height:96px;border-radius:24px;background:#252231;margin:0 auto 18px;display:flex;align-items:center;
justify-content:center;font-size:44px}}h1{{margin:0 0 6px;font-size:28px}}.muted{{color:#B5B3C2;font-size:14px}}
.btn{{display:inline-block;margin-top:22px;background:#fff;color:#0E0E12;border-radius:999px;padding:14px 26px;
font:700 17px system-ui;text-decoration:none}}</style></head><body><main><div class="disc">♫</div>
<h1>{name}</h1><p class="muted">{sub}{lock}</p>{btn}
<script>var id={json.dumps(jam_id)};var a=document.getElementById('go');
var q='jam?u='+encodeURIComponent(location.origin)+'&j='+encodeURIComponent(id);
if(a){{a.href='tunio://'+q;
/* opening the link goes straight into Tunio (the button stays, in case the phone asks first) */
setTimeout(function(){{location.href=/android/i.test(navigator.userAgent)
?'intent://'+q+'#Intent;scheme=tunio;package=ch.tunio.app;end':'tunio://'+q;}},60);}}</script></main></body></html>"""
