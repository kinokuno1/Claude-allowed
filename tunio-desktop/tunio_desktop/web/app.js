// Tunio for Windows — desktop UI. Talks to the local Python engine (server.py) with a per-launch token.
// Layout: top bar (history + search), Your Library sidebar, main view, now-playing panel, player bar.
import { ICONS } from "./icons.js";

const TOKEN = new URLSearchParams(location.search).get("t") || "";
const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const icon = (name, cls = "i") => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true"><path d="${ICONS[name]}"/></svg>`;
const fmt = (s) => { s = Math.max(0, Math.floor(s || 0)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(x).padStart(2, "0")}` : `${m}:${String(x).padStart(2, "0")}`; };
const fmtOr = (s) => (s ? fmt(s) : "");
const SOURCES = { YOUTUBE: ["YouTube", "YT"], SOUNDCLOUD: ["SoundCloud", "SC"], TIKTOK: ["TikTok", "TT"], SPOTIFY: ["Spotify", "SP"], OTHER: ["Web", "WEB"] };
const LIKED_G = "#5B45B8", DL_G = "#0F6E5C"; // flat colours (no gradients)
const store = { get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode */ } } };

async function api(path, body = {}) {
  const r = await fetch(`/api/${path}`, { method: "POST", headers: { "Content-Type": "application/json", "X-Tunio-Token": TOKEN }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `Error ${r.status}`);
  return j;
}

// ------------------------------------------------------------------ state
const S = {
  lib: { tracks: {}, liked: [], playlists: [], recent: [] }, offline: new Set(), progress: {}, queueInfo: {}, settings: { autoplay: true },
  offlineDir: "", engine: "", loaded: false,
  hist: ["home"], hi: 0,
  libFilter: null,
  search: { q: "", source: "YOUTUBE", loading: false, result: null, error: null, fromLink: false, token: 0 },
};
let libKey = "";

async function poll() {
  try {
    const s = await api("state");
    for (const e of s.errors || []) toast(e);
    const key = JSON.stringify([s.library, s.offline, s.progress, s.queue]);
    S.offline = new Set(s.offline); S.progress = s.progress; S.queueInfo = s.queue; S.settings = s.settings;
    S.offlineDir = s.offlineDir; S.engine = s.engine;
    if (!S.loaded) { S.loaded = true; Volume.init(typeof s.settings.volume === "number" ? s.settings.volume : 0.8); }
    if (key !== libKey) { libKey = key; S.lib = s.library; refresh(); }
  } catch { /* engine starting */ }
}
setInterval(poll, 1200);

const trackById = (id) => S.lib.tracks[id];
const tracksOf = (ids) => ids.map(trackById).filter(Boolean);
const playlist = (id) => S.lib.playlists.find((p) => p.id === id);
const plCover = (p) => p.cover || trackById(p.trackIds[0])?.thumbnail;

// ------------------------------------------------------------------ toasts, menus, dialogs
function toast(msg) {
  const el = document.createElement("div"); el.className = "toast"; el.textContent = msg;
  $("#toasts").append(el); setTimeout(() => el.remove(), 3800);
}
function closeMenu() { $("#menu").classList.add("hidden"); }
document.addEventListener("click", (e) => { if (!e.target.closest("#menu") && !e.target.closest("[data-menu],[data-collmenu]")) closeMenu(); });
window.addEventListener("blur", closeMenu);

function showMenu(anchor, items, onPick) {
  const m = $("#menu");
  m.innerHTML = items.map((it) => it === "-" ? "<hr>" : `<button data-m="${it[0]}">${icon(it[1])}${esc(it[2])}</button>`).join("");
  m.classList.remove("hidden");
  const r = anchor instanceof Element ? anchor.getBoundingClientRect() : { left: anchor.x, right: anchor.x, top: anchor.y, bottom: anchor.y };
  m.style.left = Math.max(8, Math.min(innerWidth - m.offsetWidth - 8, anchor instanceof Element ? r.right - m.offsetWidth : r.left)) + "px";
  m.style.top = Math.max(8, r.bottom + m.offsetHeight > innerHeight - 8 ? r.top - m.offsetHeight : r.bottom) + "px";
  m.onclick = (e) => { const a = e.target.closest("[data-m]")?.dataset.m; if (!a) return; closeMenu(); onPick(a); };
}

function openTrackMenu(anchor, t, playlistId) {
  const liked = S.lib.liked.includes(t.id), off = S.offline.has(t.id);
  showMenu(anchor, [
    ["play", "play_arrow", "Play"], ["next", "skip_next", "Play next"], ["queue", "queue_music", "Add to queue"], "-",
    ["like", liked ? "favorite" : "favorite_border", liked ? "Remove from Liked songs" : "Save to Liked songs"],
    ["addto", "playlist_add", "Add to playlist"],
    off ? ["undl", "delete", "Remove download"] : ["dl", "download", "Download (offline)"],
    ...(playlistId ? ["-", ["rm", "remove_circle_outline", "Remove from this playlist"]] : []),
  ], async (a) => {
    if (a === "play") Player.play([t], 0, false, "search");
    if (a === "next") Player.playNext(t);
    if (a === "queue") Player.addToQueue(t);
    if (a === "like") await toggleLike(t);
    if (a === "addto") addToPlaylistDialog([t]);
    if (a === "dl") { await api("download", { tracks: [t] }); toast(`Downloading “${t.title}”`); poll(); }
    if (a === "undl") { await api("download/remove", { track: t }); poll(); }
    if (a === "rm") { await api("playlist/remove", { id: playlistId, trackId: t.id }); poll(); }
  });
}
async function toggleLike(t) {
  const was = S.lib.liked.includes(t.id);
  justLiked = { id: t.id, at: Date.now() };
  await api("like", { track: t }); toast(was ? "Removed from Liked songs" : "Added to Liked songs"); await poll(); updateBar(); renderNp();
}

function dialog(html, onReady) {
  const m = $("#modal"); m.innerHTML = `<div class="dialog" role="dialog">${html}</div>`; m.classList.remove("hidden");
  const close = () => m.classList.add("hidden");
  m.onclick = (e) => { if (e.target === m || e.target.closest("[data-close]")) close(); };
  onReady?.(m, close);
}
function nameDialog(title, initial, done) {
  dialog(`<h3>${esc(title)}</h3><input class="field" id="nm" value="${esc(initial)}" placeholder="Name">
    <div class="end"><button class="link" data-close>Cancel</button><button class="btn solid" id="ok">Save</button></div>`, (m, close) => {
    const inp = $("#nm", m); inp.focus(); inp.select();
    const ok = () => { close(); done(inp.value.trim()); };
    $("#ok", m).onclick = ok; inp.onkeydown = (e) => { if (e.key === "Enter") ok(); };
  });
}
function addToPlaylistDialog(tracks) {
  const lists = S.lib.playlists.map((p) => `<button data-p="${p.id}">${art(plCover(p))}
    <span class="ellip"><b>${esc(p.name)}</b><br><span class="muted small">${p.trackIds.length} songs</span></span></button>`).join("");
  dialog(`<h3>${tracks.length === 1 ? "Add to playlist" : `Add ${tracks.length} songs to playlist`}</h3>
    <div class="row"><input class="field" id="np-name" placeholder="New playlist name"><button class="btn solid" id="create">${icon("add")}Create</button></div>
    <div class="list" style="margin-top:12px">${lists}</div><div class="end"><button class="link" data-close>Cancel</button></div>`, (m, close) => {
    $("#create", m).onclick = async () => { const name = $("#np-name", m).value.trim() || "My playlist";
      await api("playlist/create", { name, tracks, cover: tracks[0]?.thumbnail }); close(); toast(`Created “${name}”`); poll(); };
    m.querySelectorAll("[data-p]").forEach((b) => b.onclick = async () => {
      await api("playlist/add", { id: b.dataset.p, tracks }); close(); toast(`Added to “${playlist(b.dataset.p)?.name}”`); poll(); });
  });
}
function newPlaylist() {
  nameDialog("Create playlist", `My playlist #${S.lib.playlists.length + 1}`, async (name) => {
    const p = await api("playlist/create", { name: name || "My playlist", tracks: [] }); await poll(); go("pl:" + p.id); });
}

// ------------------------------------------------------------------ building blocks
const art = (url, cls = "art", tid = "") => `<div class="${cls}"${tid ? ` data-tid="${esc(tid)}"` : ""}>${url ? `<img src="${esc(url)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : icon("music_note")}</div>`;
const tart = (t, cls = "art") => art(t.thumbnail, cls, t.id);
const ppShown = new Map();
/** Play/pause icon: two shapes that morph between ▶ and ❚❚. `key` names the button. */
function ppIcon(playing, key) {
  const was = ppShown.has(key) ? ppShown.get(key) : playing;
  return `<svg class="i ppi ${was ? "pause" : ""}" data-pk="${esc(key)}" data-want="${playing ? 1 : 0}" viewBox="0 0 24 24" aria-hidden="true"><path class="pa"/><path class="pb"/></svg>`;
}
/** After a render: morph every play/pause icon to its new state. */
function syncPP() {
  requestAnimationFrame(() => document.querySelectorAll(".ppi").forEach((el) => {
    const want = el.dataset.want === "1";
    el.classList.toggle("pause", want); ppShown.set(el.dataset.pk, want);
  }));
}
function setPP(el, playing, key) {
  if (!el) return;
  const svg = el.querySelector(".ppi");
  if (svg) svg.dataset.want = playing ? 1 : 0; else el.innerHTML = ppIcon(playing, key);
  syncPP();
}
let justLiked = { id: null, at: 0 };
/** Like heart. Right after liking, it fills from the inside in 10 frames and pops. */
function heart(t) {
  const liked = S.lib.liked.includes(t.id), pop = justLiked.id === t.id && Date.now() - justLiked.at < 900;
  const dots = [0, 60, 120, 180, 240, 300].map((a) => `<circle cx="12" cy="12" r="1.4" style="--dx:${(Math.cos(a * Math.PI / 180) * 11).toFixed(1)}px;--dy:${(Math.sin(a * Math.PI / 180) * 11).toFixed(1)}px"/>`).join("");
  return `<svg class="i heart ${liked ? "on" : ""} ${pop ? (liked ? "pop" : "unpop") : ""}" viewBox="0 0 24 24" aria-hidden="true">
    <g class="burst">${dots}</g><path class="hf" d="${ICONS.favorite}"/><path class="ho" d="${ICONS.favorite_border}"/></svg>`;
}

// A cover that fails to load (expired link, removed video…) shows a note instead and is looked up again.
const brokenArt = {}, reportedArt = new Set(); let brokenTimer = 0;
document.addEventListener("error", (e) => {
  const img = e.target;
  if (!(img instanceof HTMLImageElement)) return;
  const box = img.closest("[data-tid]") || img.parentElement;
  if (!box?.matches?.(".art, .np-cover, [data-tid]")) return;
  const tid = box.dataset?.tid, src = img.getAttribute("src");
  img.remove();
  if (box.classList.contains("art") && !box.querySelector("svg")) box.insertAdjacentHTML("beforeend", icon("music_note"));
  if (tid && !reportedArt.has(tid) && trackById(tid)) {
    reportedArt.add(tid); brokenArt[tid] = src;
    clearTimeout(brokenTimer);
    brokenTimer = setTimeout(() => { const b = { ...brokenArt }; for (const k in brokenArt) delete brokenArt[k]; api("artwork/refresh", { broken: b }).catch(() => {}); }, 800);
  }
}, true);
const gradArt = (g, ic, cls = "art") => `<div class="${cls} grad" style="--g:${g}">${icon(ic)}</div>`;
const badge = (src) => `<span class="badge b-${src}">${SOURCES[src]?.[1] || "WEB"}</span>`;
const eqBars = () => `<span class="eq ${audio.paused ? "paused" : ""}"><i></i><i></i><i></i></span>`;
function offlineMark(t) {
  if (S.offline.has(t.id)) return icon("download_done", "i offline-ok");
  const p = S.progress[t.id];
  if (p === undefined) return "";
  return p < 0 ? `<span class="ring busy"></span>` : `<span class="ring" style="--p:${p}"></span>`;
}
const isPlaying = () => !!Player.current() && (!audio.paused || Player.preparing);
const ctxPlaying = (ctx) => Player.context === ctx && isPlaying();

/** Collections: what a route (or card) plays. */
function collTracks(r) {
  if (r === "liked") return tracksOf(S.lib.liked);
  if (r === "recent") return tracksOf(S.lib.recent);
  if (r === "downloads") return Object.values(S.lib.tracks).filter((t) => S.offline.has(t.id)).sort((a, b) => a.title.localeCompare(b.title));
  if (r.startsWith("pl:")) return tracksOf(playlist(r.slice(3))?.trackIds || []);
  return [];
}
function collName(r) {
  if (r === "liked") return "Liked songs"; if (r === "downloads") return "Downloads"; if (r === "recent") return "Recently played";
  if (r === "search") return "Search"; if (r?.startsWith("pl:")) return playlist(r.slice(3))?.name || "Playlist"; return "Tunio";
}
function collArt(r, cls = "art") {
  if (r === "liked") return gradArt(LIKED_G, "favorite", cls);
  if (r === "downloads") return gradArt(DL_G, "download_done", cls);
  const p = playlist(r.slice(3)); return art(p && plCover(p), cls);
}
function playContext(r) {
  if (Player.context === r && Player.current()) return Player.toggle();
  const tracks = collTracks(r);
  if (!tracks.length) return toast("Nothing to play here yet");
  Player.play(tracks, Player.shuffle ? Math.floor(Math.random() * tracks.length) : 0, Player.shuffle, r);
}
const fab = (ctx, cls = "") => `<button class="fab ${cls} ${ctxPlaying(ctx) ? "live" : ""}" data-playctx="${ctx}" title="${ctxPlaying(ctx) ? "Pause" : "Play"}">${ppIcon(ctxPlaying(ctx) && !audio.paused, "ctx:" + ctx)}</button>`;

// Track tables: # · Title · Source · ♥ · duration · ⋯ (Spotify-style; double-click or the ▶ on hover plays).
const LISTS = {}; // listKey -> tracks currently shown
function trackTable(key, tracks, { playlistId, compact = false, head = true, offset = 0 } = {}) {
  LISTS[key] = tracks;
  const cur = Player.current()?.id;
  const rows = tracks.map((t, i) => {
    const isCur = cur === t.id, liked = S.lib.liked.includes(t.id);
    return `<div class="row-track${isCur ? " current" : ""}" data-play="${key}" data-i="${i}" tabindex="0">
      <div class="num">${isCur ? eqBars() : `<span class="n">${offset + i + 1}</span>`}<span class="p" data-rowplay title="Play">${ppIcon(isCur && !audio.paused, "row:" + key + ":" + t.id)}</span></div>
      <div class="ttl">${tart(t)}<div class="ellip"><div class="t ellip">${esc(t.title)}</div>
        <div class="s">${badge(t.source)}${offlineMark(t)}<span class="ellip">${esc(t.artist || "")}</span></div></div></div>
      <div class="col-album ellip">${SOURCES[t.source]?.[0] || "Web"}</div>
      <button class="icon-btn hov ${liked ? "liked" : ""}" data-like="${key}" data-i="${i}" title="${liked ? "Remove from Liked songs" : "Save to Liked songs"}">${heart(t)}</button>
      <div class="dur r">${fmtOr(t.durationSec)}</div>
      <button class="icon-btn hov" data-menu="${key}" data-i="${i}" ${playlistId ? `data-pl="${playlistId}"` : ""} title="More options">${icon("more_vert")}</button></div>`;
  }).join("");
  const header = head ? `<div class="tt-head"><div class="r">#</div><div>Title</div><div class="col-album">Source</div><div></div><div class="r">${icon("schedule")}</div><div></div></div>` : "";
  return `<div class="tt ${compact ? "compact" : ""}">${header}${rows}</div>`;
}

function playFromList(key, i) {
  const list = LISTS[key]; const t = list?.[i]; if (!t) return;
  if (Player.current()?.id === t.id && Player.context === ctxForList(key)) return Player.toggle();
  if (key.startsWith("search") && !S.search.fromLink) Player.play([t], 0, false, "search"); // one song + autoplay fills the queue
  else Player.play(list, i, false, ctxForList(key));
}
function ctxForList(key) { return key === "coll" ? route() : key.startsWith("search") ? "search" : key; }

document.addEventListener("click", (e) => {
  const menuBtn = e.target.closest("[data-menu]");
  if (menuBtn) { e.stopPropagation(); const t = LISTS[menuBtn.dataset.menu]?.[+menuBtn.dataset.i]; if (t) openTrackMenu(menuBtn, t, menuBtn.dataset.pl); return; }
  const likeBtn = e.target.closest("[data-like]");
  if (likeBtn) { const t = LISTS[likeBtn.dataset.like]?.[+likeBtn.dataset.i]; if (t) toggleLike(t); return; }
  const pc = e.target.closest("[data-playctx]");
  if (pc) { e.stopPropagation(); playContext(pc.dataset.playctx); return; }
  const card = e.target.closest("[data-playcard]");
  if (card) { playFromList(card.dataset.playcard, +card.dataset.i); return; }
  const row = e.target.closest("[data-play]");
  if (row) {
    document.querySelectorAll(".row-track.sel").forEach((r) => r.classList.remove("sel")); row.classList.add("sel");
    if (e.detail > 1) return; // the 2nd click of a double-click: already playing
    return playFromList(row.dataset.play, +row.dataset.i);
  }
  const nav = e.target.closest("[data-go]");
  if (nav) go(nav.dataset.go);
});
document.addEventListener("contextmenu", (e) => {
  const row = e.target.closest("[data-play]"); if (!row) return;
  const t = LISTS[row.dataset.play]?.[+row.dataset.i]; if (!t) return;
  e.preventDefault(); openTrackMenu({ x: e.clientX, y: e.clientY }, t, row.querySelector("[data-pl]")?.dataset.pl);
});

// ------------------------------------------------------------------ navigation (back / forward like a browser)
const route = () => S.hist[S.hi];
function go(r) {
  if (r === route()) return render(true);
  S.hist = S.hist.slice(0, S.hi + 1); S.hist.push(r); S.hi++; render(true);
}
function back() { if (S.hi > 0) { S.hi--; render(true); } }
function forward() { if (S.hi < S.hist.length - 1) { S.hi++; render(true); } }
document.addEventListener("keydown", (e) => {
  const typing = ["INPUT", "TEXTAREA"].includes(document.activeElement?.tagName);
  if (e.key === "Escape") { if (!$("#modal").classList.contains("hidden")) $("#modal").classList.add("hidden"); else if (!$("#menu").classList.contains("hidden")) closeMenu(); else if (typing) document.activeElement.blur(); else if ($("#app").classList.contains("focus")) { $("#app").classList.remove("focus"); render(true); } return; }
  if (e.altKey && e.key === "ArrowLeft") { e.preventDefault(); return back(); }
  if (e.altKey && e.key === "ArrowRight") { e.preventDefault(); return forward(); }
  if (e.ctrlKey && (e.key === "k" || e.key === "l")) { e.preventDefault(); $("#q").focus(); $("#q").select(); return; }
  if (e.ctrlKey && e.key === "ArrowUp") { e.preventDefault(); return Volume.set(Volume.value + 0.1); }
  if (e.ctrlKey && e.key === "ArrowDown") { e.preventDefault(); return Volume.set(Volume.value - 0.1); }
  if (typing) return;
  if (e.ctrlKey && e.key === "ArrowRight") { e.preventDefault(); return Player.next(); }
  if (e.ctrlKey && e.key === "ArrowLeft") { e.preventDefault(); return Player.prev(); }
  if (e.key === " ") { e.preventDefault(); return Player.toggle(); }
  if (e.key === "Enter") { const row = document.activeElement?.closest?.("[data-play]"); if (row) playFromList(row.dataset.play, +row.dataset.i); }
});
window.addEventListener("mouseup", (e) => { if (e.button === 3) back(); if (e.button === 4) forward(); });

// ------------------------------------------------------------------ top bar
function setupTop() {
  $("#hback").innerHTML = icon("chevron_left"); $("#hfwd").innerHTML = icon("chevron_right");
  $("#hhome").innerHTML = icon("home"); $("#hsettings").innerHTML = icon("settings"); $("#qclear").innerHTML = icon("clear");
  $("#hback").onclick = back; $("#hfwd").onclick = forward; $("#hhome").onclick = () => go("home"); $("#hsettings").onclick = () => go("settings");
  const q = $("#q");
  const sync = () => { S.search.q = q.value; $("#qclear").classList.toggle("hidden", !q.value); $("#sicon").innerHTML = icon(isLink(q.value) ? "link" : "search"); };
  q.oninput = sync;
  q.onfocus = () => { if (route() !== "search") go("search"); };
  q.onkeydown = (e) => { if (e.key === "Enter") { if (route() !== "search") go("search"); runSearch(); } };
  q.onpaste = () => setTimeout(() => { sync(); if (isLink(q.value)) { if (route() !== "search") go("search"); runSearch(); } });
  $("#qclear").onclick = (e) => { e.preventDefault(); q.value = ""; sync(); S.search.result = null; S.search.error = null; q.focus(); if (route() === "search") renderSearchResults(); };
  sync();
}
function updateTop() {
  $("#hback").disabled = S.hi === 0; $("#hfwd").disabled = S.hi >= S.hist.length - 1;
  $("#hhome").classList.toggle("on", route() === "home");
  $("#hhome").innerHTML = icon(route() === "home" ? "home" : "home_outlined");
}

// ------------------------------------------------------------------ Your Library (sidebar)
const layout = { sideMin: store.get("tunio.sideMin", false), npOpen: store.get("tunio.npOpen", true) };
function applyLayout() {
  const app = $("#app");
  app.classList.toggle("side-min", layout.sideMin);
  app.classList.toggle("np-off", !layout.npOpen);
  if (!layout.npOpen) app.classList.remove("np-pinned");
}
function npVisible() { return getComputedStyle($("#np")).display !== "none"; }
function toggleNp() {
  const app = $("#app");
  if (innerWidth <= 1100) { const show = !npVisible(); layout.npOpen = layout.npOpen || show; app.classList.toggle("np-pinned", show); }
  else { layout.npOpen = !layout.npOpen; app.classList.remove("np-pinned"); }
  store.set("tunio.npOpen", layout.npOpen); applyLayout(); renderNp(); updateBar();
}

function renderSide() {
  const r = route(), f = S.libFilter;
  const full = (go, artHtml, title, sub) => {
    const playing = ctxPlaying(go);
    return `<button class="lib-item ${r === go ? "on" : ""} ${Player.context === go && Player.current() ? "playing" : ""}" data-go="${go}" title="${esc(title)}">
      ${artHtml}<div class="ellip" style="flex:1"><div class="t ellip">${esc(title)}</div><div class="sub ellip">${sub}</div></div>
      ${playing ? icon("volume_up", "i speaker") : ""}</button>`;
  };
  const items = [];
  const fullOffline = (p) => p.trackIds.length && p.trackIds.every((id) => S.offline.has(id));
  if (f !== "offline") items.push(["liked", collArt("liked"), "Liked songs", `Playlist · ${S.lib.liked.length} songs`]);
  if (f !== "playlists") items.push(["downloads", collArt("downloads"), "Downloads", `${S.offline.size} songs on this PC`]);
  for (const p of S.lib.playlists) {
    if (f === "offline" && !fullOffline(p)) continue;
    items.push(["pl:" + p.id, art(plCover(p)), p.name, `${fullOffline(p) ? icon("download_done", "i offline-ok") : ""}Playlist · ${p.trackIds.length} songs`]);
  }
  $("#side").innerHTML = `
    <div class="panel-head"><button class="title-btn" id="libtoggle" title="${layout.sideMin ? "Expand Your Library" : "Collapse Your Library"}">${icon(layout.sideMin ? "library_music_outlined" : "library_music")}<span class="full">Your Library</span></button>
      <span class="spacer full"></span><button class="round small full" id="libnew" title="Create playlist">${icon("add")}</button></div>
    <div class="lib-chips full">${[["playlists", "Playlists"], ["offline", "Downloaded"]].map(([k, l]) => `<button class="chip ${f === k ? "on" : ""}" data-filter="${k}">${l}</button>`).join("")}
      ${f ? `<button class="round small" data-filter="" title="Clear filter">${icon("close")}</button>` : ""}</div>
    <div class="panel-scroll full">${items.map((it) => full(...it)).join("") || `<div class="panel muted small">No downloaded playlists yet.</div>`}</div>
    <div class="panel-scroll mini-only">
      <button class="round" id="libnew2" title="Create playlist" style="margin:2px 0 8px">${icon("add")}</button>
      ${items.map(([go, a, title]) => `<button class="lib-item ${r === go ? "on" : ""}" data-go="${go}" title="${esc(title)}">${a}</button>`).join("")}</div>`;
  $("#libtoggle").onclick = () => { layout.sideMin = !layout.sideMin; store.set("tunio.sideMin", layout.sideMin); applyLayout(); renderSide(); };
  $("#libnew").onclick = newPlaylist; $("#libnew2").onclick = newPlaylist;
  $("#side").querySelectorAll("[data-filter]").forEach((b) => b.onclick = () => { S.libFilter = b.dataset.filter === S.libFilter || !b.dataset.filter ? null : b.dataset.filter; renderSide(); });
}

// ------------------------------------------------------------------ main view
function refresh() { render(false); }
function render(navigated) {
  const sc = $("#screen"), r = route(), top = sc.scrollTop;
  closeMenu();
  sc.classList.toggle("ly-mode", r === "lyrics");
  if (r !== "lyrics") $("#app").classList.remove("focus");
  if (r === "lyrics") {
    const html = lyricsHtml(), key = /data-key="([^"]*)"/.exec(html)?.[1];
    if (navigated || !key || $(".ly-wrap", sc)?.dataset.key !== key.replace(/&amp;/g, "&")) { sc.innerHTML = html; bindLyrics(); }
  } else if (r === "search" && !navigated && $("#results")) renderSearchResults();
  else {
    if (r.startsWith("pl:") && !playlist(r.slice(3)) && S.loaded) { S.hist.splice(S.hi, 1); S.hi = Math.max(0, S.hi - 1); return render(true); }
    sc.innerHTML = `<div class="page">${r === "home" ? homeHtml() : r === "search" ? searchHtml() : r === "settings" ? settingsHtml() : collectionHtml(r)}</div>`;
    bindScreen(r);
    if (r === "search") renderSearchResults();
  }
  if (r !== "lyrics") sc.scrollTop = navigated ? 0 : top;
  updateTop(); renderSide(); updateBar(); renderNp(); syncPP();
}

function greeting() { const h = new Date().getHours(); return h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 18 ? "Good afternoon" : "Good evening"; }
function homeHtml() {
  const recent = tracksOf(S.lib.recent).slice(0, 14); LISTS.recent = recent;
  const qtile = (go, artHtml, title) => `<div class="qtile" data-go="${go}" role="button" tabindex="0">${artHtml}<div class="t ellip">${esc(title)}</div>
    ${ctxPlaying(go) ? eqBars() : ""}${fab(go)}</div>`;
  const tiles = [qtile("liked", collArt("liked"), "Liked songs"), qtile("downloads", collArt("downloads"), "Downloads"),
    ...S.lib.playlists.slice(0, 6).map((p) => qtile("pl:" + p.id, art(plCover(p)), p.name))].join("");
  const empty = !recent.length && !S.lib.liked.length && !S.lib.playlists.length;
  const shelf = (title, items, more) => items ? `<div class="sec-head"><h2>${title}</h2><span class="spacer"></span>${more || ""}</div><div class="shelf">${items}</div>` : "";
  const recentCards = recent.map((t, i) => `<div class="card" data-playcard="recent" data-i="${i}" role="button" tabindex="0" title="${esc(t.title)}">
      <div class="cover">${tart(t)}<button class="fab ${Player.current()?.id === t.id && isPlaying() ? "live" : ""}" data-playcard="recent" data-i="${i}" title="Play">${ppIcon(Player.current()?.id === t.id && !audio.paused, "card:" + t.id)}</button></div>
      <div class="t ellip">${esc(t.title)}</div><div class="a">${esc(t.artist || SOURCES[t.source]?.[0])}</div></div>`).join("");
  const plCards = S.lib.playlists.map((p) => `<div class="card" data-go="pl:${p.id}" role="button" tabindex="0">
      <div class="cover">${art(plCover(p))}${fab("pl:" + p.id)}</div>
      <div class="t ellip">${esc(p.name)}</div><div class="a">Playlist · ${p.trackIds.length} songs</div></div>`).join("");
  return `<div class="header-glow"></div>
    <h1 class="title">${greeting()}</h1>
    <div class="quick">${tiles}</div>
    ${shelf("Recently played", recentCards)}
    ${shelf("Your playlists", plCards, `<button class="link" id="mkpl">Create playlist</button>`)}
    ${empty ? `<div class="panel" style="margin-top:24px"><h3>Welcome to Tunio</h3>
      <p class="muted">Search YouTube or SoundCloud with the bar at the top, or paste a TikTok, Spotify, YouTube or SoundCloud link (song, playlist or album).<br>
      Right-click a song → <b>Download</b> to keep it offline in your Music\\Tunio folder.</p>
      <button class="btn solid" id="startsearch">${icon("search")}Start searching</button></div>` : ""}`;
}

function searchHtml() {
  const s = S.search;
  return `<div class="src-chips">${["YOUTUBE", "SOUNDCLOUD"].map((k) => `<button class="chip ${s.source === k ? "on" : ""}" data-src="${k}">${SOURCES[k][0]}</button>`).join("")}</div>
    <div id="results"></div>`;
}
function renderSearchResults() {
  const s = S.search, el = $("#results"); if (!el) return;
  let h = "";
  if (!s.result && !s.loading && !s.error) h = `<div class="panel"><h3>Search and links</h3><p class="muted">Type a song or artist and press <kbd>Enter</kbd>.
    Paste a TikTok, Spotify, YouTube or SoundCloud link (song, playlist or album) and it opens right away. Spotify songs play from YouTube.</p>
    <p class="muted small">Private Spotify playlists: in the Spotify app select all songs (<kbd>Ctrl</kbd>+<kbd>A</kbd>), copy (<kbd>Ctrl</kbd>+<kbd>C</kbd>) and paste them in the search bar.</p></div>`;
  if (s.loading) h += `<div class="loading"><span class="spinner"></span>${s.fromLink ? "Reading link…" : "Searching…"}</div>`;
  if (s.error) h += `<div class="err">${esc(s.error)}</div>`;
  const r = s.result;
  if (r?.tracks?.length) {
    if (s.fromLink && r.tracks.length > 1) {
      LISTS.search = r.tracks;
      const total = r.tracks.reduce((a, t) => a + (t.durationSec || 0), 0);
      h += `<div class="coll-head" style="padding-top:8px">${art(r.cover || r.tracks[0].thumbnail)}<div style="min-width:0"><div class="kind">Imported list</div>
          <h1>${esc(r.title || "Imported list")}</h1><div class="stats">${r.tracks.length} songs<span class="muted">${total ? `, about ${Math.round(total / 60)} min` : ""}</span>${s.artLeft ? `<span class="muted"> · getting song covers (${s.artLeft} left)…</span>` : ""}</div></div></div>
        <div class="coll-actions"><button class="fab xl" id="playall" title="Play">${icon("play_arrow")}</button>
          <button class="icon-btn" id="shufall" title="Shuffle play">${icon("shuffle")}</button>
          <button class="btn" id="saveall">${icon("playlist_add")}Save as playlist</button></div>
        ${trackTable("search", r.tracks)}`;
    } else if (s.fromLink) {
      h += trackTable("search", r.tracks);
    } else {
      const top = r.tracks[0];
      h += `<div class="top-result"><div><div class="sec-head" style="margin-top:0"><h2>Top result</h2></div>
          <div class="top-card" data-playcard="search" data-i="0" role="button" tabindex="0">${tart(top)}
            <div class="t ellip">${esc(top.title)}</div><div class="muted">${badge(top.source)} &nbsp;${esc(top.artist || "")}</div>
            <button class="fab" data-playcard="search" data-i="0" title="Play">${icon("play_arrow")}</button></div></div>
        <div style="min-width:0"><div class="sec-head" style="margin-top:0"><h2>Songs</h2></div>${trackTable("search", r.tracks.slice(0, 4), { compact: true, head: false })}</div></div>`;
      if (r.tracks.length > 4) h += `<div class="sec-head"><h2>More results</h2></div>${trackTable("search-more", r.tracks.slice(4), { offset: 4 })}`;
      LISTS.search = r.tracks;
      LISTS["search-more"] = r.tracks.slice(4);
    }
  }
  el.innerHTML = h;
  $("#playall")?.addEventListener("click", () => Player.play(r.tracks, 0, false, "search"));
  $("#shufall")?.addEventListener("click", () => Player.play(r.tracks, 0, true, "search"));
  $("#saveall")?.addEventListener("click", async () => { const p = await api("playlist/create", { name: r.title || "Imported", tracks: r.tracks, sourceUrl: r.sourceUrl, cover: r.cover });
    api("artwork/refresh").catch(() => {}); // covers still being looked up are finished in the background
    toast(`Saved as playlist “${p.name}”`); await poll(); go("pl:" + p.id); });
}
const isLink = (q) => /https?:\/\/\S+|spotify:(track|album|playlist):|open\.spotify\.com\/track\//.test(q) || /^(www\.|m\.|vm\.)?[a-z0-9-]+\.(com|be|link|fi)\/\S+/i.test(q.trim());
/** Imported Spotify lists only name their songs, so each song's own cover is looked up, 12 at a time,
 *  and shown as it arrives. */
async function fillArtwork(token, tracks) {
  const s = S.search, need = tracks.filter((t) => t.source === "SPOTIFY");
  if (!need.length) return;
  s.artLeft = need.length; if (route() === "search") renderSearchResults();
  for (let i = 0; i < need.length; i += 12) {
    if (token !== s.token) return;
    const chunk = need.slice(i, i + 12);
    try {
      const { art } = await api("artwork", { tracks: chunk });
      for (const t of chunk) if (art[t.id]) t.thumbnail = art[t.id];
    } catch { /* covers stay as they were */ }
    if (token !== s.token) return;
    s.artLeft = Math.max(0, need.length - i - chunk.length);
    if (route() === "search") renderSearchResults();
  }
}
async function runSearch() {
  const s = S.search, q = s.q.trim(); if (!q) return;
  const my = ++s.token; s.loading = true; s.error = null; s.result = null; s.artLeft = 0; s.fromLink = isLink(q); renderSearchResults();
  try {
    const r = s.fromLink ? await api("resolve", { text: q }) : await api("search", { source: s.source, q });
    if (my !== s.token) return;
    s.result = r; if (!r.tracks.length) s.error = "Nothing found.";
    // Get the top result ready to play while you're still reading the list.
    if (r.tracks[0]) api("prepare", { track: r.tracks[0], prefetch: true }).catch(() => {});
    if (s.fromLink) fillArtwork(my, r.tracks);
  } catch (e) { if (my === s.token) s.error = e.message; }
  if (my === s.token) { s.loading = false; if (route() === "search") renderSearchResults(); }
}

function collectionHtml(r) {
  const pl = r.startsWith("pl:") ? playlist(r.slice(3)) : null;
  if (r.startsWith("pl:") && !pl) return `<div class="loading"><span class="spinner"></span></div>`;
  const tracks = collTracks(r);
  const title = collName(r);
  const total = tracks.reduce((a, t) => a + (t.durationSec || 0), 0);
  const missing = tracks.filter((t) => !S.offline.has(t.id)).length, busy = tracks.filter((t) => t.id in S.progress).length;
  let dl = "";
  if (r !== "downloads" && tracks.length) dl = !missing ? `<span class="icon-btn good" title="Available offline">${icon("download_done")}</span>`
    : busy ? `<span class="row muted small" style="display:flex;gap:8px;align-items:center"><span class="spinner"></span>Downloading ${busy}…</span>`
    : `<button class="icon-btn" id="dlall" title="Download ${missing} songs">${icon("download")}</button>`;
  if (r === "downloads") dl = `<button class="btn" id="openfolder">${icon("folder_open")}Open folder</button>`;
  const empty = r === "liked" ? "Songs you like appear here. Click ♡ on any song." : r === "downloads" ? "Nothing downloaded yet. Right-click a song → Download, or use ⤓ on a playlist." : "This playlist is empty. Search for songs and right-click → Add to playlist.";
  const cover = r.startsWith("pl:") ? plCover(pl) : null;
  const glow = cover ? `<div class="header-glow cover"><img src="${esc(cover)}" alt="" referrerpolicy="no-referrer"></div>`
    : `<div class="header-glow" style="--tint:${r === "liked" ? "#5B45B8" : "#0F6E5C"};height:420px"></div>`;
  return `${glow}
    <div class="coll-head">${collArt(r)}<div style="min-width:0"><div class="kind">${r === "downloads" ? "Offline" : "Playlist"}</div>
      <h1>${esc(title)}</h1><div class="stats"><b>Tunio</b> · ${tracks.length} songs<span class="muted">${total ? `, about ${total >= 3600 ? `${Math.floor(total / 3600)} hr ` : ""}${Math.round(total % 3600 / 60)} min` : ""}</span></div></div></div>
    <div class="coll-actions">
      <button class="fab xl" data-playctx="${r}" title="Play" ${tracks.length ? "" : "disabled"}>${ppIcon(ctxPlaying(r) && !audio.paused, "ctx:" + r)}</button>
      <button class="icon-btn ${Player.shuffle ? "on" : ""}" id="shuffle" title="Shuffle play">${icon("shuffle")}</button>
      ${dl}
      ${pl ? `<button class="icon-btn" data-collmenu id="collmore" title="More options">${icon("more_vert")}</button>` : ""}</div>
    ${tracks.length ? trackTable("coll", tracks, { playlistId: pl?.id }) : `<div class="panel muted">${empty}</div>`}`;
}

function settingsHtml() {
  return `<h1 class="title">Settings</h1>
    <div class="panel"><div class="set-row"><div style="flex:1"><h3>Autoplay</h3><span class="muted small">When 3 or fewer songs are left, add similar songs and load the next song in advance.</span></div>
      <button class="switch ${S.settings.autoplay ? "on" : ""}" id="autoplay" title="Autoplay" role="switch" aria-checked="${!!S.settings.autoplay}"></button></div></div>
    <div class="panel"><h3>Offline songs</h3><p class="muted small">Saved in <b style="color:var(--text)">${esc(S.offlineDir)}</b>. They stay there even if you delete Tunio.</p>
      <button class="btn" id="openfolder">${icon("folder_open")}Open folder</button></div>
    <div class="panel"><h3>Playback cache</h3><p class="muted small" id="cachesz">Songs you've played, so replays are instant. The oldest are removed automatically above 1 GB.</p>
      <button class="btn" id="clearcache">${icon("delete")}Clear cache</button></div>
    <div class="panel"><h3>Keyboard</h3><p class="muted small" style="line-height:2.1">
      <kbd>Space</kbd> play / pause · <kbd>Ctrl</kbd>+<kbd>→</kbd> next · <kbd>Ctrl</kbd>+<kbd>←</kbd> previous ·
      <kbd>Ctrl</kbd>+<kbd>↑</kbd>/<kbd>↓</kbd> volume · <kbd>Ctrl</kbd>+<kbd>K</kbd> search · <kbd>Alt</kbd>+<kbd>←</kbd>/<kbd>→</kbd> back / forward ·
      double-click a song to play it, right-click for more. Media keys work too.</p></div>
    <div class="panel"><h3>About</h3><p class="muted small">Tunio for Windows. Audio from YouTube, SoundCloud and TikTok through yt-dlp ${esc(S.engine)}.
      Spotify links are read for song info only and matched to YouTube.</p></div>`;
}

function bindScreen(r) {
  $("#startsearch")?.addEventListener("click", () => $("#q").focus());
  $("#mkpl")?.addEventListener("click", newPlaylist);
  $("#openfolder")?.addEventListener("click", () => api("open-folder"));
  $("#screen").querySelectorAll(".qtile, .card[data-go]").forEach((el) => el.addEventListener("keydown", (e) => { if (e.key === "Enter") go(el.dataset.go); }));
  if (r === "search") {
    document.querySelectorAll("[data-src]").forEach((b) => b.onclick = () => {
      S.search.source = b.dataset.src; render(true); if (S.search.q.trim() && !isLink(S.search.q)) runSearch(); });
    if (!S.search.q) setTimeout(() => $("#q").focus());
  }
  if (r === "settings") {
    $("#autoplay").onclick = async () => { await api("settings", { autoplay: !S.settings.autoplay }); S.settings.autoplay = !S.settings.autoplay; refresh(); };
    $("#clearcache").onclick = async () => { await api("cache/clear"); toast("Cache cleared"); };
    api("cache").then((c) => { const el = $("#cachesz"); if (el) el.textContent = `${(c.bytes / 1048576).toFixed(1)} MB of songs you've played, so replays are instant. The oldest are removed automatically above 1 GB.`; });
  }
  if (r === "liked" || r === "downloads" || r.startsWith("pl:")) {
    const tracks = collTracks(r), pid = r.startsWith("pl:") ? r.slice(3) : null;
    $("#shuffle")?.addEventListener("click", () => { if (tracks.length) Player.play(tracks, Math.floor(Math.random() * tracks.length), true, r); });
    $("#dlall")?.addEventListener("click", async () => { await api("download", { tracks }); toast(`Downloading ${tracks.filter((t) => !S.offline.has(t.id)).length} songs in the background`); poll(); });
    $("#collmore")?.addEventListener("click", (e) => showMenu(e.currentTarget, [["rename", "edit", "Rename"], ["dl", "download", "Download all"], "-", ["del", "delete", "Delete playlist"]], (a) => {
      if (a === "rename") nameDialog("Rename playlist", playlist(pid)?.name || "", async (n) => { if (n) { await api("playlist/rename", { id: pid, name: n }); poll(); } });
      if (a === "dl") $("#dlall") ? $("#dlall").click() : toast("Already downloaded");
      if (a === "del") dialog(`<h3>Delete “${esc(playlist(pid)?.name)}”?</h3><p class="muted">Downloaded songs stay in Downloads.</p>
        <div class="end"><button class="link" data-close>Cancel</button><button class="btn solid" id="yes">Delete</button></div>`, (m, close) => {
        $("#yes", m).onclick = async () => { close(); await api("playlist/delete", { id: pid }); await poll(); S.hist = ["home"]; S.hi = 0; render(true); }; });
    }));
  }
}

// ------------------------------------------------------------------ sliders (progress and volume)
function slider(el, { onInput, onCommit, step = 0.05 }) {
  el.innerHTML = `<div class="track"><div class="fill"></div></div><div class="knob"></div>`;
  el.tabIndex = 0; el.setAttribute("role", "slider");
  const at = (e) => { const r = el.getBoundingClientRect(); return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)); };
  const api_ = { dragging: false, set(v) { el.style.setProperty("--v", v); el.setAttribute("aria-valuenow", Math.round(v * 100)); } };
  el.addEventListener("pointerdown", (e) => { if (e.button !== 0) return; el.setPointerCapture(e.pointerId); api_.dragging = true; el.classList.add("drag"); const v = at(e); api_.set(v); onInput?.(v); });
  el.addEventListener("pointermove", (e) => { if (!api_.dragging) return; const v = at(e); api_.set(v); onInput?.(v); });
  const end = (e) => { if (!api_.dragging) return; api_.dragging = false; el.classList.remove("drag"); onCommit?.(at(e)); };
  el.addEventListener("pointerup", end); el.addEventListener("pointercancel", end);
  el.addEventListener("keydown", (e) => {
    const cur = +getComputedStyle(el).getPropertyValue("--v") || 0;
    if (e.key === "ArrowRight" || e.key === "ArrowUp") { e.preventDefault(); e.stopPropagation(); const v = Math.min(1, cur + step); api_.set(v); onCommit?.(v); }
    if (e.key === "ArrowLeft" || e.key === "ArrowDown") { e.preventDefault(); e.stopPropagation(); const v = Math.max(0, cur - step); api_.set(v); onCommit?.(v); }
  });
  return api_;
}

// ------------------------------------------------------------------ volume
const audio = $("#audio");
const Volume = (() => {
  let value = 0.8, muted = false, saveT = 0, ui = null;
  const curve = (v) => v * v; // feels even across the slider
  function apply() {
    const g = muted ? 0 : curve(value);
    if (typeof Beat !== "undefined" && Beat.gain) { Beat.gain.gain.value = g; audio.volume = 1; } else audio.volume = g;
    ui?.set(muted ? 0 : value);
    const b = $("#vbtn"); if (b) { b.innerHTML = icon(muted || value === 0 ? "volume_off" : value < 0.5 ? "volume_down" : "volume_up"); b.title = muted ? "Unmute" : "Mute"; }
  }
  function set(v, save = true) {
    value = Math.min(1, Math.max(0, v)); muted = false; apply();
    if (save) { clearTimeout(saveT); saveT = setTimeout(() => api("settings", { volume: Math.round(value * 100) / 100 }).catch(() => {}), 400); }
  }
  return { apply, set, init(v) { value = v; apply(); }, toggleMute() { if (!muted && value === 0) return set(0.5); muted = !muted; apply(); },
    attach(s) { ui = s; apply(); }, get value() { return value; } };
})();

// ------------------------------------------------------------------ lyrics (LRCLIB, via the local engine)
/** Lyrics for the playing song: a card in the Now playing panel and a full view in the main area.
 *  Synced lyrics light up line by line with the song; click a line to jump there. */
const LY = { mode: store.get("tunio.lyMode", "line"), d3: store.get("tunio.ly3d", false), card: store.get("tunio.lyCard", false) };
const Lyrics = (() => {
  const cache = new Map(); // track id -> { status: "loading" | "ok" | "error", data }
  const wordCache = new Map(); // track id -> word timings, only fetched when Word or 3D mode is on
  let raf = 0, shownIdx = -2, userScrollAt = 0, lastT = 0;
  const onLoaded = (t) => { if (Player.current()?.id === t.id) { renderNp(true); if (route() === "lyrics") render(false); } };
  function get(t) {
    if (!t) return null;
    let e = cache.get(t.id);
    if (!e || (e.status === "error" && Date.now() - e.at > 30000)) {
      e = { status: "loading" }; cache.set(t.id, e);
      api("lyrics", { track: t }).then((d) => { e.status = "ok"; e.data = d; }).catch((err) => { e.status = "error"; e.error = err.message; e.at = Date.now(); })
        .finally(() => onLoaded(t));
    }
    return e;
  }
  /** Word timings (Musixmatch, NetEase, or estimated from the lines). */
  function words(t) {
    if (!t) return null;
    let e = wordCache.get(t.id);
    if (!e || (e.status === "error" && Date.now() - e.at > 30000)) {
      e = { status: "loading" }; wordCache.set(t.id, e);
      api("lyrics/words", { track: t }).then((d) => { e.status = d.lines?.length ? "ok" : "none"; e.data = d; })
        .catch((err) => { e.status = "error"; e.error = err.message; e.at = Date.now(); })
        .finally(() => onLoaded(t));
    }
    return e;
  }
  const wantWords = () => LY.mode === "word" || LY.d3;
  const prefetch = (t) => { if (t) { get(t); if (wantWords()) words(t); } };
  /** Index of the line being sung (last line whose time has come), -1 before the first one. */
  function indexAt(lines, time, at = (l) => l[0]) {
    let lo = 0, hi = lines.length - 1, ans = -1;
    while (lo <= hi) { const m = (lo + hi) >> 1; if (at(lines[m]) <= time + 0.25) { ans = m; lo = m + 1; } else hi = m - 1; }
    return ans;
  }
  const synced = () => { const e = cache.get(Player.current()?.id); return e?.status === "ok" ? e.data.synced : null; };
  const wordLines = () => { const e = wordCache.get(Player.current()?.id); return e?.status === "ok" ? e.data.lines : null; };
  function tick(now) {
    raf = 0;
    const time = audio.currentTime || 0, dt = Math.min(0.1, (now - (lastT || now)) / 1000); lastT = now;
    const lines = synced();
    if (lines) { const idx = indexAt(lines, time); if (idx !== shownIdx) { shownIdx = idx; paint(idx); } }
    const wl = wordLines();
    if (wl && route() === "lyrics") {
      if (LY.d3 && $("#world")) Stage.frame(wl, time, dt);
      else if (LY.mode === "word" && $("#lylines.words")) paintWords(wl, time);
    }
    if (!audio.paused || (LY.d3 && Stage.moving())) raf = requestAnimationFrame(tick);
  }
  function wake(force = false) { if (force) { shownIdx = -2; wordIdx = -2; } if (!raf) { lastT = 0; raf = requestAnimationFrame(tick); } }
  function paint(idx) {
    // full view (line mode): lines change colour softly while the list glides to the line being sung
    const box = $("#lylines:not(.words)");
    if (box) {
      box.querySelectorAll(".ly-line").forEach((el, i) => { el.classList.toggle("on", i === idx); el.classList.toggle("past", i < idx); });
      follow(box.children[Math.max(0, idx)]);
    }
    // card in the Now playing panel: the whole text slides up so the line being sung is on top
    const card = $("#lycardlines");
    if (card) {
      [...card.children].forEach((el, i) => { el.classList.toggle("on", i === idx); el.classList.toggle("past", i < idx); });
      const el = card.children[Math.max(0, idx)];
      const y = el && idx > 0 ? el.offsetTop - card.children[0].offsetTop : 0;
      if (el) card.parentElement.style.setProperty("--clear", `${el.offsetHeight + 4}px`); // the sung line always stays sharp
      if (card.dataset.ready !== "1") { // first paint after (re)building the card: jump, don't slide
        card.style.transition = "none"; card.style.transform = `translateY(${-y}px)`; void card.offsetHeight; card.style.transition = ""; card.dataset.ready = "1";
      } else card.style.transform = `translateY(${-y}px)`;
    }
  }
  function follow(el) {
    const sc = $("#lyscroll");
    if (el && sc && Date.now() - userScrollAt > 3000) glide(sc, el.offsetTop - sc.clientHeight * 0.36 + el.offsetHeight / 2);
  }
  /** Word mode: the text stays put; white fills each word as it's sung, and a line slides under the word being sung. */
  let wordIdx = -2;
  function paintWords(wl, time) {
    const box = $("#lylines.words"); if (!box) return;
    const idx = indexAt(wl, time, (l) => l.t);
    if (idx !== wordIdx) {
      const old = box.children[wordIdx];
      if (old) old.querySelectorAll(".w").forEach((w) => { w.style.setProperty("--p", 1); w.classList.remove("now"); });
      [...box.children].forEach((el, i) => { el.classList.toggle("on", i === idx); el.classList.toggle("past", i < idx); });
      wordIdx = idx;
      follow(box.children[Math.max(0, idx)]);
    }
    const line = wl[idx], el = box.children[idx];
    if (!line || !el) return;
    const spans = el.querySelectorAll(".w");
    let at = -1;
    line.words.forEach(([s0, s1], k) => {
      const p = Math.max(0, Math.min(1, (time - s0) / Math.max(0.05, s1 - s0)));
      const sp = spans[k]; if (!sp) return;
      if (Math.abs((+sp.dataset.p || 0) - p) > 0.01) { sp.dataset.p = p; sp.style.setProperty("--p", p.toFixed(3)); }
      if (time >= s0 - 0.05) at = k;
    });
    underline(box, spans[at]);
  }
  /** The line under the word being sung. It glides along to the next word on the same row of text; when the
   *  next word is on another row (or another lyric line) it doesn't travel across the text: it appears there. */
  let ulFor = null, ulRow = null;
  function wordBox(sp) {
    // the word itself (not the space after it), on the row where it ends if it wraps
    const node = sp.firstChild, len = node?.textContent.trimEnd().length || 0;
    let rects = [];
    if (node && node.nodeType === 3 && len) { const r = document.createRange(); r.setStart(node, 0); r.setEnd(node, len); rects = [...r.getClientRects()]; }
    if (!rects.length) rects = [...sp.getClientRects()];
    return rects[rects.length - 1] || sp.getBoundingClientRect();
  }
  function underline(box, sp) {
    let ul = box.querySelector(".uline");
    if (!ul) { ul = document.createElement("i"); ul.className = "uline"; box.append(ul); ulFor = null; ulRow = null; }
    if (!sp) { ul.classList.remove("on"); ulFor = null; ulRow = null; return; }
    if (sp === ulFor) return;
    ulFor = sp;
    const b = box.getBoundingClientRect(), r = wordBox(sp);
    const x = r.left - b.left, y = r.bottom - b.top + 1, row = `${sp.closest(".ly-line")?.dataset.t}|${Math.round(y)}`;
    const jump = !ul.classList.contains("on") || row !== ulRow;
    ulRow = row;
    if (jump) { ul.style.transition = "none"; ul.classList.remove("on"); }
    ul.style.transform = `translate(${x}px, ${y}px)`;
    ul.style.width = `${Math.max(8, r.width)}px`;
    if (jump) { void ul.offsetWidth; ul.style.transition = ""; }
    ul.classList.add("on");
  }
  /** Smooth, slow scroll (the browser's own "smooth" is short and abrupt). */
  let glideRaf = 0;
  function glide(sc, target) {
    cancelAnimationFrame(glideRaf);
    target = Math.max(0, Math.min(target, sc.scrollHeight - sc.clientHeight));
    const from = sc.scrollTop, dist = target - from, t0 = performance.now(), dur = Math.min(900, 450 + Math.abs(dist));
    if (Math.abs(dist) < 1) return;
    const ease = (x) => 1 - Math.pow(1 - x, 4);
    const step = (now) => { const k = Math.min(1, (now - t0) / dur); sc.scrollTop = from + dist * ease(k); if (k < 1) glideRaf = requestAnimationFrame(step); };
    glideRaf = requestAnimationFrame(step);
  }
  audio.addEventListener("play", () => wake(true));
  audio.addEventListener("seeked", () => { wake(true); Stage.reset(); });
  document.addEventListener("wheel", (e) => { if (e.target.closest?.("#lyscroll")) { userScrollAt = Date.now(); cancelAnimationFrame(glideRaf); } }, { passive: true });
  // the wavy background behind the big lyrics rests while the music is paused
  ["play", "pause"].forEach((ev) => audio.addEventListener(ev, () => $(".ly-wrap")?.classList.toggle("paused", audio.paused)));
  return { get, words, wantWords, prefetch, wake, indexAt, paint: () => { shownIdx = -2; wordIdx = -2; wake(true); } };
})();

/** Tempo, for the 3D lyrics: the bass is watched for hits (Web Audio) to learn the song's BPM, so words pop in
 *  softly on slow songs and hard on fast ones. Only switched on with 3D mode (a click), and only if the browser
 *  lets the audio graph run, so the music never goes silent. */
const Beat = (() => {
  let ctx = null, an = null, gain = null, data = null, avg = 0, last = 0, hits = [];
  async function enable() {
    if (ctx) { if (ctx.state !== "running") await ctx.resume().catch(() => {}); return true; }
    try {
      const c = new (window.AudioContext || window.webkitAudioContext)();
      if (c.state !== "running") await c.resume().catch(() => {});
      if (c.state !== "running") { c.close(); return false; }
      const src = c.createMediaElementSource(audio);
      an = c.createAnalyser(); an.fftSize = 1024; an.smoothingTimeConstant = 0.55;
      gain = c.createGain();
      src.connect(an); an.connect(gain); gain.connect(c.destination);
      data = new Uint8Array(an.frequencyBinCount);
      ctx = c; Volume.apply();
      return true;
    } catch (e) { console.warn("tempo off:", e); return false; }
  }
  /** Called every frame: notes the bass hits. */
  function sample(now) {
    if (!an || audio.paused) return;
    an.getByteFrequencyData(data);
    let bass = 0; for (let i = 1; i < 9; i++) bass += data[i]; bass /= 8; // ~40-380 Hz
    avg = avg ? avg * 0.94 + bass * 0.06 : bass;
    if (bass > avg * 1.22 && bass > 100 && now - last > 250) { last = now; hits.push(now); if (hits.length > 40) hits.shift(); }
  }
  /** Beats per minute (the usual gap between hits), or 0 while it's still listening. */
  function bpm() {
    if (hits.length < 8) return 0;
    const gaps = hits.slice(1).map((t, i) => t - hits[i]).filter((g) => g > 250 && g < 1600).sort((a, b) => a - b);
    if (gaps.length < 6) return 0;
    let b = 60000 / gaps[gaps.length >> 1];
    while (b < 75) b *= 2;
    while (b > 175) b /= 2;
    return Math.round(b);
  }
  /** How hard words pop in: "soft" (slow songs), "mid" or "hard" (fast songs). */
  function energy() { const b = bpm(); return !b ? "mid" : b < 96 ? "soft" : b > 132 ? "hard" : "mid"; }
  audio.addEventListener("play", () => { if (ctx && ctx.state !== "running") ctx.resume().catch(() => {}); });
  audio.addEventListener("emptied", () => { hits = []; avg = 0; }); // a new song: listen again
  return { enable, sample, bpm, energy, get gain() { return gain; }, get on() { return !!an; } };
})();

/** The 3D lyrics stage: the lines hang one behind the other, straight ahead. The camera keeps flying forward
 *  (it never turns): it drifts toward the line being sung, then flies through it to the next one, while the
 *  words pop in as they're sung, softer or harder with the song's tempo. */
const Stage = (() => {
  const GAP = 760, VIEW = 560;
  let camZ = null, built = new Map(), lineIdx = -2, velo = 1;
  const place = (i) => -i * GAP;
  function reset() { built.forEach((el) => el.remove()); built.clear(); lineIdx = -2; camZ = null; }
  function line(wl, i) {
    if (built.has(i) || !wl[i]) return built.get(i);
    const el = document.createElement("div");
    el.className = "l3";
    el.style.transform = `translate3d(0px, 0px, ${place(i)}px)`;
    el.innerHTML = wl[i].words.map(([, , w]) => `<span class="w3">${esc(w.trim()) || "♪"}</span>`).join(" ");
    $("#world").append(el); built.set(i, el);
    return el;
  }
  function frame(wl, time, dt) {
    Beat.sample(performance.now());
    const idx = Math.max(0, Lyrics.indexAt(wl, time, (l) => l.t));
    if (idx !== lineIdx) {
      for (const [i, el] of built) if (i < idx - 1 || i > idx + 1) { el.remove(); built.delete(i); }
      built.forEach((el, i) => { el.classList.toggle("gone", i < idx); el.classList.toggle("cur", i === idx); });
      lineIdx = idx;
      $("#ly3d").dataset.energy = Beat.energy();
    }
    const el = line(wl, idx); line(wl, idx + 1);
    if (!el) return;
    // words pop in as they're sung (the next line stays invisible until its words come)
    const spans = el.querySelectorAll(".w3");
    wl[idx].words.forEach(([s0], k) => {
      const sp = spans[k]; if (!sp) return;
      const on = time >= s0 - 0.04;
      if (on && !sp.classList.contains("pop")) sp.classList.add("pop");
      if (!on && sp.classList.contains("pop") && time < s0 - 0.3) sp.classList.remove("pop");
    });
    // always forward: a slow drift along the line, then through it to the next one
    const L = wl[idx], end = L.e || wl[idx + 1]?.t || L.t + 4;
    const prog = Math.max(0, Math.min(1, (time - L.t) / Math.max(1, end - L.t)));
    const target = place(idx) + VIEW - prog * 200;
    if (camZ === null || target > camZ + GAP) camZ = target + 120; // first frame / seeked back: start just behind
    const a = 1 - Math.exp(-dt * 4);
    camZ += Math.min(0, (target - camZ) * a); // never backwards
    velo = Math.abs(target - camZ);
    $("#cam").style.transform = `translate3d(0px, 0px, ${-camZ}px)`;
  }
  return { frame, reset, moving: () => velo > 2 };
})();

// ------------------------------------------------------------------ player
const Player = (() => {
  let smart = false, smartPool = [], smartBusy = false, nextGap = 3 + Math.floor(Math.random() * 3);
  let queue = [], index = -1, original = null, shuffle = false, repeat = "off", preparing = false, loadToken = 0, autoplayBusy = false, failures = 0, context = null;
  const current = () => queue[index] || null;
  async function load(i, autoplayCheck = true) {
    index = i; const t = current(); if (!t) return;
    Lyrics.prefetch(t);
    const my = ++loadToken; preparing = true; audio.pause(); updateUi();
    try {
      const { src, kind } = await api("prepare", { track: t });
      if (my !== loadToken) return;
      attempt = { my, kind, retried: false, track: t };
      audio.src = `${src}?t=${encodeURIComponent(TOKEN)}`;
      await start(my);
      failures = 0;
      if (kind === "stream") setTimeout(() => { if (attempt.my === my && !attempt.retried && my === loadToken && !(audio.currentTime > 0.05) && !userPaused) retry("slow"); }, 12000);
    } catch (e) {
      if (my !== loadToken) return;
      toast(`Couldn't play “${t.title}”: ${e.message}`);
      if (++failures < 3 && index + 1 < queue.length) return load(index + 1);
    } finally { if (my === loadToken) { preparing = false; updateUi(); } }
    media(); if (autoplayCheck) afterTrackChange();
  }
  async function afterTrackChange() {
    const left = queue.length - index - 1, t = current();
    if (S.settings.autoplay && left <= 3 && repeat === "off" && !autoplayBusy && t) {
      autoplayBusy = true;
      try {
        const exclude = [...queue, ...tracksOf(S.lib.recent.slice(0, 30))];
        const { tracks } = await api("similar", { track: t, exclude });
        const fresh = tracks.filter((x) => !queue.some((q) => q.id === x.id));
        if (fresh.length) { queue.push(...fresh); toast(`Autoplay: added ${fresh.length} similar songs`); updateUi(); }
      } catch { /* no autoplay this time */ } finally { autoplayBusy = false; }
    }
    if (smart) await fillSmart();
    const next = queue[index + 1]; // warm up the next song so it starts instantly
    if (next) { api("prepare", { track: next, prefetch: true }).catch(() => {}); Lyrics.prefetch(next); }
  }
  function play(tracks, start = 0, shuffled = shuffle, ctx = null) {
    if (!tracks.length) return; shuffle = shuffled; context = ctx;
    if (shuffled) { original = tracks.slice(); const first = tracks[start]; queue = [first, ...tracks.filter((_, i) => i !== start).sort(() => Math.random() - .5)]; load(0); }
    else { original = null; queue = tracks.slice(); load(start); }
  }
  function next(auto = false) {
    if (!queue.length) return;
    if (auto && repeat === "one") { audio.currentTime = 0; audio.play(); return; }
    if (index + 1 < queue.length) load(index + 1);
    else if (repeat === "all") load(0);
    else if (!auto) load(index); // restart last
  }
  let attempt = { my: -1 }, userPaused = false;
  /** Starts playback. If the browser blocks sound that starts on its own, the next click anywhere starts it. */
  async function start(my) {
    try { await audio.play(); }
    catch (e) {
      if (e?.name !== "NotAllowedError" || my !== loadToken) return;
      toast("Click anywhere to start the music");
      const go = () => { document.removeEventListener("pointerdown", go, true); if (my === loadToken && audio.paused) audio.play().catch(() => {}); };
      document.addEventListener("pointerdown", go, true);
    }
  }
  /** The stream didn't start (expired link, refused, too slow): get the whole song from the engine and play that. */
  async function retry(why) {
    const t = current(); if (!t || attempt.retried) return;
    attempt.retried = true;
    const my = loadToken; preparing = true; updateUi();
    if (why === "slow") toast(`“${t.title}” is slow to start: fetching it…`);
    try {
      const { src } = await api("prepare", { track: t, retry: true });
      if (my !== loadToken) return;
      const at = audio.currentTime || 0;
      audio.src = `${src}?t=${encodeURIComponent(TOKEN)}`;
      if (at > 1) audio.currentTime = at;
      await start(my);
    } catch (e) { if (my === loadToken) toast(`Couldn't play “${t.title}”: ${e.message}`); }
    finally { if (my === loadToken) { preparing = false; updateUi(); } }
  }
  audio.addEventListener("pause", () => { userPaused = !preparing && !audio.ended; });
  audio.addEventListener("play", () => { userPaused = false; });
  function prev() { if (audio.currentTime > 3 || index <= 0) audio.currentTime = 0; else load(index - 1); }
  function toggle() { if (!current() || preparing) return; if (!audio.src) return load(index); audio.paused ? audio.play() : audio.pause(); }
  /** Shuffle button: off → shuffle → smart shuffle (a recommended song every 3 to 5 songs) → off. */
  function toggleShuffle() {
    const cur = current();
    if (!shuffle) {
      shuffle = true;
      if (cur) { original = queue.slice(); queue = [...queue.slice(0, index + 1), ...queue.slice(index + 1).sort(() => Math.random() - .5)]; }
    } else if (!smart) {
      smart = true; toast("Smart shuffle: a song you might like every few songs");
      fillSmart();
    } else {
      smart = false; shuffle = false;
      queue = queue.filter((t, i) => i <= index || !t.smart); // the recommendations still to come go away
      if (original && cur) { queue = original; index = Math.max(0, queue.findIndex((t) => t.id === cur.id)); }
    }
    updateUi();
  }
  /** Smart shuffle: puts a recommended song (similar to what's playing) after every 3 to 5 songs coming up. */
  async function fillSmart() {
    if (!smart || !current()) return;
    let gap = 0;
    for (let i = index; i >= 0 && !queue[i].smart; i--) gap++;
    const at = [];
    for (let i = index + 1; i < queue.length && i <= index + 12; i++) {
      if (queue[i].smart) { gap = 0; continue; }
      if (++gap >= nextGap) { at.push(i + 1); gap = 0; nextGap = 3 + Math.floor(Math.random() * 3); }
    }
    if (!at.length) return;
    if (smartPool.length < at.length) await refillSmart();
    for (const pos of at.reverse()) { const r = smartPool.shift(); if (!r) break; queue.splice(pos, 0, { ...r, smart: true }); }
    updateUi();
  }
  async function refillSmart() {
    if (smartBusy) return;
    smartBusy = true;
    try {
      const mine = queue.filter((t) => !t.smart), seed = mine[Math.floor(Math.random() * mine.length)] || current();
      const { tracks } = await api("similar", { track: seed, exclude: [...queue, ...tracksOf(S.lib.recent.slice(0, 30))] });
      const fresh = tracks.filter((x) => !queue.some((q) => q.id === x.id) && !smartPool.some((q) => q.id === x.id));
      smartPool.push(...fresh.sort(() => Math.random() - .5));
    } catch { /* no recommendations this time */ } finally { smartBusy = false; }
  }
  function cycleRepeat() { repeat = repeat === "off" ? "all" : repeat === "all" ? "one" : "off"; updateUi(); }
  function playNext(t) { if (!current()) return play([t]); queue.splice(index + 1, 0, t); toast(`Plays next: ${t.title}`); updateUi(); }
  function addToQueue(t) { if (!current()) return play([t]); queue.push(t); toast(`Added to queue: ${t.title}`); updateUi(); }
  function remove(i) { if (i === index) return; queue.splice(i, 1); if (i < index) index--; updateUi(); }
  function jump(i) { load(i); }
  audio.addEventListener("ended", () => next(true));
  audio.addEventListener("error", () => {
    if (!audio.src || preparing) return;
    if (attempt.my === loadToken && attempt.kind === "stream" && !attempt.retried) return retry("error"); // first, try the whole song
    const t = current(); toast(`Playback error: ${t?.title || ""}`); if (++failures < 3) next(true);
  });
  ["play", "pause"].forEach((ev) => audio.addEventListener(ev, updateUi));
  function media() {
    const t = current(); if (!("mediaSession" in navigator) || !t) return;
    navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: t.artist, artwork: t.thumbnail ? [{ src: t.thumbnail, sizes: "480x360" }] : [] });
    navigator.mediaSession.setActionHandler("play", () => audio.play());
    navigator.mediaSession.setActionHandler("pause", () => audio.pause());
    navigator.mediaSession.setActionHandler("nexttrack", () => next());
    navigator.mediaSession.setActionHandler("previoustrack", () => prev());
    navigator.mediaSession.setActionHandler("seekto", (d) => { audio.currentTime = d.seekTime; });
  }
  return { play, next, prev, toggle, toggleShuffle, cycleRepeat, playNext, addToQueue, remove, jump, current,
    get queue() { return queue; }, get index() { return index; }, get shuffle() { return shuffle; }, get smart() { return smart; }, get repeat() { return repeat; },
    get preparing() { return preparing; }, get context() { return context; } };
})();

let uiKey = "";
function updateUi() {
  updateBar(); renderNp();
  // Play/pause state shows in many places (rows, cards, sidebar): re-render the page only when it changed.
  const key = JSON.stringify([Player.current()?.id, audio.paused, Player.preparing, Player.context, Player.shuffle, Player.smart]);
  if (key !== uiKey) { uiKey = key; render(false); }
}

// ------------------------------------------------------------------ player bar
let progress = null, seeking = false;
function setupBar() {
  $("#bar").innerHTML = `
    <div class="now" id="bnow"></div>
    <div class="mid">
      <div class="ctrls">
        <button class="icon-btn" id="bshuf" title="Enable shuffle">${icon("shuffle")}</button>
        <button class="icon-btn skip" id="bprev" title="Previous">${icon("skip_previous")}</button>
        <button class="pp" id="bplay" title="Play">${ppIcon(false, "bar")}</button>
        <button class="icon-btn skip" id="bnext" title="Next">${icon("skip_next")}</button>
        <button class="icon-btn" id="brep" title="Enable repeat">${icon("repeat")}</button>
      </div>
      <div class="prog"><span id="tcur">0:00</span><div class="slider" id="seek" aria-label="Song progress"></div><span id="tdur">0:00</span></div>
    </div>
    <div class="side">
      <button class="icon-btn" id="bdl" title="Download">${icon("download")}</button>
      <button class="icon-btn" id="bly" title="Lyrics">${icon("mic")}</button>
      <button class="icon-btn" id="bnp" title="Now playing view">${icon("view_sidebar")}</button>
      <button class="icon-btn" id="vbtn" title="Mute">${icon("volume_up")}</button>
      <div class="slider vol" id="vol" aria-label="Volume"></div>
    </div>`;
  $("#bplay").onclick = Player.toggle; $("#bnext").onclick = () => Player.next(); $("#bprev").onclick = Player.prev;
  $("#bshuf").onclick = Player.toggleShuffle; $("#brep").onclick = Player.cycleRepeat;
  $("#bnp").onclick = toggleNp;
  $("#bly").onclick = () => (route() === "lyrics" ? back() : go("lyrics")); $("#vbtn").onclick = () => Volume.toggleMute();
  $("#bdl").onclick = async () => { const t = Player.current(); if (!t) return;
    if (S.offline.has(t.id)) { await api("download/remove", { track: t }); toast("Download removed"); } else { await api("download", { tracks: [t] }); toast(`Downloading “${t.title}”`); } poll(); };
  progress = slider($("#seek"), {
    step: 0.02,
    onInput: (v) => { seeking = true; $("#tcur").textContent = fmt(v * (audio.duration || 0)); },
    onCommit: (v) => { if (audio.duration) audio.currentTime = v * audio.duration; seeking = false; tickProgress(); },
  });
  Volume.attach(slider($("#vol"), { onInput: (v) => Volume.set(v), onCommit: (v) => Volume.set(v) }));
  $("#vol").addEventListener("wheel", (e) => { e.preventDefault(); Volume.set(Volume.value + (e.deltaY < 0 ? 0.05 : -0.05)); }, { passive: false });
}
function updateBar() {
  const t = Player.current(), playing = !audio.paused;
  const now = $("#bnow");
  const key = JSON.stringify([t?.id, S.lib.liked.includes(t?.id), Player.preparing]);
  if (now.dataset.key !== key) {
    now.dataset.key = key;
    now.innerHTML = t ? `${tart(t)}<div class="ellip"><div class="t ellip" id="btitle">${esc(t.title)}</div><div class="a ellip">${Player.preparing ? "Loading…" : esc(t.artist || SOURCES[t.source]?.[0])}</div></div>
      <button class="icon-btn ${S.lib.liked.includes(t.id) ? "liked" : ""}" id="blike" title="${S.lib.liked.includes(t.id) ? "Remove from Liked songs" : "Save to Liked songs"}">${heart(t)}</button>` : "";
    if (t) { $("#blike").onclick = () => toggleLike(t); now.querySelector(".art").onclick = $("#btitle").onclick = () => { if (!npVisible()) toggleNp(); }; }
  }
  const pp = $("#bplay");
  pp.disabled = !t;
  if (Player.preparing) { if (!pp.querySelector(".spinner")) pp.innerHTML = `<span class="spinner" style="border-top-color:var(--c10)"></span>`; }
  else { if (pp.querySelector(".spinner")) pp.innerHTML = ""; setPP(pp, playing, "bar"); }
  pp.title = playing ? "Pause" : "Play";
  $("#bshuf").classList.toggle("on", Player.shuffle); $("#bshuf").classList.toggle("smart", Player.smart);
  $("#bshuf").title = Player.smart ? "Turn shuffle off" : Player.shuffle ? "Smart shuffle (adds songs you might like)" : "Enable shuffle";
  $("#brep").classList.toggle("on", Player.repeat !== "off"); $("#brep").innerHTML = icon(Player.repeat === "one" ? "repeat_one" : "repeat");
  $("#brep").title = Player.repeat === "off" ? "Enable repeat" : Player.repeat === "all" ? "Enable repeat one" : "Disable repeat";
  const off = t && S.offline.has(t.id), p = t ? S.progress[t.id] : undefined;
  $("#bdl").classList.toggle("good", !!off); $("#bdl").disabled = !t;
  $("#bdl").innerHTML = p !== undefined && !off ? `<span class="ring ${p < 0 ? "busy" : ""}" style="--p:${Math.max(0, p)}"></span>` : icon(off ? "download_done" : "download");
  $("#bdl").title = off ? "Downloaded (click to remove)" : "Download";
  $("#bnp").classList.toggle("on", npVisible());
  $("#bly").classList.toggle("on", route() === "lyrics"); $("#bly").disabled = !t;
  if ($("#bcplay")) {
    setPP($("#bcplay"), playing, "bigcard");
    $("#bcshuf").classList.toggle("on", Player.shuffle); $("#bcshuf").classList.toggle("smart", Player.smart); $("#bcrep").classList.toggle("on", Player.repeat !== "off");
    $("#bcrep").innerHTML = icon(Player.repeat === "one" ? "repeat_one" : "repeat");
  }
  $("#seek").classList.toggle("busy", !!Player.preparing);
  tickProgress();
}
function tickProgress() {
  const d = audio.duration || Player.current()?.durationSec || 0, c = audio.currentTime || 0, frac = d && isFinite(d) ? Math.min(1, c / d) : 0;
  if (progress && !seeking) { progress.set(frac); $("#tcur").textContent = fmt(Player.current() ? c : 0); $("#tdur").textContent = fmt(isFinite(d) ? d : 0); }
  const f = $("#bcf"); if (f) { f.style.width = (frac * 100) + "%"; $("#bct").textContent = fmt(c); if (d && isFinite(d)) $("#bcd").textContent = fmt(d); }
}
audio.addEventListener("timeupdate", tickProgress);
audio.addEventListener("durationchange", tickProgress);

// ------------------------------------------------------------------ now-playing panel
function setupNp() {
  $("#np-song").innerHTML = `<div class="panel-head"><h2 id="npctx" class="ellip">Now playing</h2><span class="spacer"></span>
      <button class="round small" id="npclose" title="Hide Now playing view">${icon("close")}</button></div>
    <div class="np-songbody"><div class="np-top" id="npmain"></div><div class="np-ly" id="lyslot"></div></div>`;
  $("#np-queue").innerHTML = `<div class="panel-head"><h2>Next in queue</h2><span class="spacer"></span><span class="muted small" id="qcount"></span></div>
    <div class="q-list" id="npqueue"></div>`;
  $("#npclose").onclick = toggleNp;
  $("#lyslot").addEventListener("click", () => { if (Player.current()) route() === "lyrics" ? back() : go("lyrics"); });
  $("#lyslot").addEventListener("keydown", (e) => { if (e.key === "Enter") $("#lyslot").click(); });
  // keep the sliding lyrics lined up when the section changes size
  new ResizeObserver(() => Lyrics.paint()).observe($("#lyslot"));
}
function lyricsCardHtml(t) {
  const e = t ? Lyrics.get(t) : null;
  let body;
  if (!e) body = `<p class="dim">Lyrics show up here while a song plays.</p>`;
  else if (e.status === "loading") body = `<p class="dim">Looking for lyrics…</p>`;
  else if (e.status === "error") body = `<p class="dim">Couldn't reach the lyrics service. Check your internet connection.</p>`;
  else if (e.data.instrumental) body = `<p class="on">♪ Instrumental</p>`;
  else if (e.data.synced) body = `<div class="ly-view"><div class="ly-track" id="lycardlines">${e.data.synced.map(([, txt]) => `<p>${esc(txt) || "♪"}</p>`).join("")}</div><div class="ly-blur"></div></div>`;
  else if (e.data.plain) body = `<div class="ly-view plain">${e.data.plain.split("\n").filter(Boolean).slice(0, 40).map((l) => `<p>${esc(l)}</p>`).join("")}<div class="ly-blur"></div></div>`;
  else body = `<p class="dim">No lyrics found for this song.</p>`;
  const open = route() === "lyrics";
  return `<div class="ly-card ${open ? "open" : ""}" role="button" tabindex="0" title="${open ? "Close lyrics" : "Show lyrics in the big view"}">
    <h3>Lyrics<span class="spacer"></span>${t ? icon(open ? "close" : "open_in_full") : ""}</h3>${body}</div>`;
}
function renderNp(force = false) {
  if (!npVisible()) return;
  const t = Player.current(), main = $("#npmain"), q = $("#npqueue"), ly = $("#lyslot");
  $("#npctx").textContent = t ? collName(Player.context) : "Now playing";
  if (!t) {
    if (main.dataset.key !== "none") {
      main.dataset.key = "none"; ly.dataset.key = "none";
      main.innerHTML = `<div class="empty">${icon("music_note")}<p>Nothing playing yet.<br>Pick a song and it shows up here.</p></div>`;
      ly.innerHTML = lyricsCardHtml(null);
      q.innerHTML = `<p class="muted q-empty">Songs you play next show up here.</p>`; $("#qcount").textContent = "";
    }
    return;
  }
  const liked = S.lib.liked.includes(t.id), p = S.progress[t.id];
  const upNext = Player.queue.slice(Player.index + 1, Player.index + 51); LISTS.queue = upNext;
  const key = JSON.stringify([t.id, t.thumbnail, liked, Player.preparing, p, upNext.map((x) => x.id + (x.thumbnail || "") + (x.smart ? "*" : "")), S.offline.has(t.id)]);
  if (main.dataset.key !== key) {
    main.dataset.key = key;
    main.innerHTML = `<div class="np-cover" data-tid="${esc(t.id)}">${t.thumbnail ? `<img src="${esc(t.thumbnail)}" alt="" referrerpolicy="no-referrer">` : ""}
        ${Player.preparing ? `<div class="wait"><span class="spinner" style="width:32px;height:32px;border-width:3px"></span><span>${p !== undefined && p >= 0 ? `Fetching audio ${Math.round(p * 100)}%` : "Finding audio…"}</span></div>` : ""}</div>
      <div class="np-info"><div style="flex:1;min-width:0"><div class="t">${esc(t.title)}</div><div class="a ellip">${badge(t.source)} ${offlineMark(t)} ${esc(t.artist || "")}</div></div>
        <button class="icon-btn ${liked ? "liked" : ""}" id="nplike" title="${liked ? "Remove from Liked songs" : "Save to Liked songs"}">${heart(t)}</button>
        <button class="icon-btn" data-menu="now" data-i="0" title="More options">${icon("more_vert")}</button></div>`;
    LISTS.now = [t];
    $("#nplike").onclick = () => toggleLike(t);
    const left = Player.queue.length - Player.index - 1;
    $("#qcount").textContent = left ? `${left} song${left === 1 ? "" : "s"}` : "";
    q.innerHTML = upNext.length ? upNext.map((x, i) => `<div class="q-item" data-q="${i}" role="button" tabindex="0">${tart(x)}<div class="ellip" style="flex:1"><div class="t ellip">${esc(x.title)}</div>
        <div class="muted ellip">${x.smart ? `<span class="rec">Recommended</span> ` : ""}${esc(x.artist || "")}</div></div><button class="icon-btn" data-rmq="${i}" title="Remove from queue">${icon("close")}</button></div>`).join("")
      : `<p class="muted q-empty">${S.settings.autoplay ? "Autoplay adds similar songs when the queue runs low." : "The queue is empty."}</p>`;
    q.querySelectorAll("[data-q]").forEach((r) => r.onclick = (e) => {
      const rm = e.target.closest("[data-rmq]"); if (rm) return Player.remove(Player.index + 1 + +rm.dataset.rmq);
      Player.jump(Player.index + 1 + +r.dataset.q); });
  }
  const lkey = JSON.stringify([t.id, Lyrics.get(t).status, route() === "lyrics"]);
  if (force || ly.dataset.key !== lkey) { ly.dataset.key = lkey; ly.innerHTML = lyricsCardHtml(t); Lyrics.paint(); }
}

// ------------------------------------------------------------------ lyrics view (main area)
function lyricsHtml() {
  const t = Player.current();
  if (!t) return `<div class="page"><h1 class="title">Lyrics</h1><div class="panel muted">Play a song and its lyrics show up here.</div></div>`;
  const e = Lyrics.get(t), d = e.data || {}, cover = t.thumbnail;
  const we = Lyrics.wantWords() && e.status === "ok" && d.synced ? Lyrics.words(t) : null;
  const wordsOk = we?.status === "ok", wordsLoading = we?.status === "loading";
  let lines = "", note = "", cls = d.synced ? "synced" : "";
  if (e.status === "loading") lines = `<p class="ly-msg"><span class="spinner"></span> Looking for lyrics…</p>`;
  else if (e.status === "error") lines = `<p class="ly-msg">Couldn't reach the lyrics service.<br><span class="small">Check your internet connection and try again.</span></p>`;
  else if (d.instrumental) lines = `<p class="ly-msg">♪ This song is instrumental ♪</p>`;
  else if (LY.mode === "word" && wordsOk) {
    cls += " words";
    lines = we.data.lines.map((l) => `<p class="ly-line" data-t="${l.t}">${l.words.map(([, , w]) => `<span class="w">${esc(w)}</span>`).join("")}</p>`).join("");
    note = we.data.estimated ? "Word timing estimated from the line timing." : `Word timing from ${esc(we.data.source)}.`;
  } else if (d.synced) lines = d.synced.map(([time, txt]) => `<p class="ly-line" data-t="${time}">${esc(txt) || "♪"}</p>`).join("");
  else if (d.plain) { lines = d.plain.split("\n").map((l) => `<p class="ly-line plain">${esc(l) || "&nbsp;"}</p>`).join(""); note = "These lyrics aren't synced to the song."; }
  else lines = `<p class="ly-msg">No lyrics found for this song.<br><span class="small">LRCLIB is made by its community, so new or rare songs may be missing.</span></p>`;
  if (Lyrics.wantWords() && we && (we.status === "none" || we.status === "error")) note = we.data?.error || "No word timing for this song: showing it line by line.";
  const d3 = LY.d3 && wordsOk, focus = $("#app").classList.contains("focus");
  const key = [t.id, e.status, LY.mode, LY.d3, LY.card, we?.status || "", S.lib.liked.includes(t.id)].join("|");
  return `<div class="ly-wrap ${audio.paused ? "paused" : ""} ${LY.card ? "with-card" : ""} ${d3 ? "is3d" : ""}" data-key="${esc(key)}">
    <div class="ly-bg">${cover ? ["b1", "b2", "b3", "b4"].map((c) => `<img class="${c}" src="${esc(cover)}" alt="" referrerpolicy="no-referrer">`).join("") : ""}</div>
    ${LY.card ? bigCardHtml(t) : ""}
    ${d3 ? `<div class="ly3d" id="ly3d"><div class="cam" id="cam"><div class="world" id="world"></div></div></div>`
      : `<div class="ly-scroll" id="lyscroll"><div class="ly-lines ${cls}" id="lylines">${lines}</div>
      <p class="ly-credit">${note ? `${note} · ` : ""}${e.status === "ok" && d.found ? `Lyrics from LRCLIB${d.track ? ` (“${esc(d.track)}” by ${esc(d.artist)})` : ""}` : ""}</p></div>`}
    ${wordsLoading ? `<div class="ly-loading"><div class="lyr-dots"><i></i><i></i><i></i><i></i><i></i></div><span>Loading word-by-word lyrics…</span></div>` : ""}
    <div class="ly-top">
      ${LY.card ? "" : `<button class="ly-song" id="lysong" title="Show the big player">${art(cover, "art lyc")}<div class="ellip"><div class="t ellip lyt">${esc(t.title)}</div><div class="a ellip">${esc(t.artist || "")}</div></div></button>`}
      <span class="spacer"></span>
      <div class="seg ${LY.mode === "word" ? "at2" : ""}" role="group" aria-label="Highlight">
        <button class="${LY.mode === "line" ? "on" : ""}" data-lymode="line" title="Line by line" aria-label="Line by line">${LINE_ICON}</button>
        <button class="${LY.mode === "word" ? "on" : ""}" data-lymode="word" title="Word by word" aria-label="Word by word">${WORD_ICON}</button></div>
      <button class="pill3d ${LY.d3 ? "on" : ""}" id="ly3dbtn" title="3D: fly through the lyrics, words pop in with the song's tempo" aria-label="3D">${CUBES_ICON}</button>
      <button class="round" id="lyfocus" title="${focus ? "Show the side panels again" : "Fill the window with lyrics"}">${icon(focus ? "fullscreen_exit" : "fullscreen")}</button>
      <button class="round" id="lyclose" title="Close lyrics">${icon("close")}</button></div>
  </div>`;
}
const LINE_ICON = `<svg class="i" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="3" rx="1.5"/><rect x="3" y="10.5" width="13" height="3" rx="1.5" opacity=".55"/><rect x="3" y="16" width="16" height="3" rx="1.5" opacity=".55"/></svg>`;
const WORD_ICON = `<svg class="i" viewBox="0 0 24 24" aria-hidden="true"><rect x="2.5" y="9" width="6" height="6" rx="1.5"/><rect x="10" y="9" width="5" height="6" rx="1.5" opacity=".55"/><rect x="16.5" y="9" width="5" height="6" rx="1.5" opacity=".55"/><rect x="2.5" y="17" width="6" height="1.6" rx=".8"/></svg>`;
const CUBES_ICON = `<svg class="i cubes" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round"><path d="M15.3 2.9L20.0 5.6L20.0 11.0L15.3 13.7L10.6 11.0L10.6 5.6Z M10.6 5.6L15.3 8.3L20.0 5.6 M15.3 8.3L15.3 13.7"/><path d="M8.7 10.3L13.4 13.0L13.4 18.4L8.7 21.1L4.0 18.4L4.0 13.0Z M4.0 13.0L8.7 15.7L13.4 13.0 M8.7 15.7L8.7 21.1"/></svg>`;
/** The big player card in the lyrics view (the small title at the top left grows into it). */
function bigCardHtml(t) {
  const playing = !audio.paused;
  return `<div class="ly-big" id="lybig">
    <div class="bc-cover lyc">${t.thumbnail ? `<img src="${esc(t.thumbnail)}" alt="" referrerpolicy="no-referrer">` : icon("music_note")}
      <button class="bc-like ${S.lib.liked.includes(t.id) ? "liked" : ""}" id="bclike" title="Like">${heart(t)}</button></div>
    <div class="bc-ctrls">
      <button id="bcshuf" class="${Player.shuffle ? "on" : ""}" title="Shuffle">${icon("shuffle")}</button>
      <button id="bcprev" title="Previous">${icon("skip_previous")}</button>
      <button id="bcplay" title="Play/pause">${ppIcon(playing, "bigcard")}</button>
      <button id="bcnext" title="Next">${icon("skip_next")}</button>
      <button id="bcrep" class="${Player.repeat !== "off" ? "on" : ""}" title="Repeat">${icon(Player.repeat === "one" ? "repeat_one" : "repeat")}</button></div>
    <div class="bc-prog"><span id="bct">0:00</span><div class="bc-bar" id="bcbar"><i id="bcf"></i></div><span id="bcd">${fmtOr(t.durationSec)}</span></div>
    <button class="bc-title" id="bctitle" title="Back to the small title"><div class="t lyt">${esc(t.title)}</div><div class="a">${esc(t.artist || "")}</div></button>
  </div>`;
}
function toggleCard() {
  const go = () => { LY.card = !LY.card; store.set("tunio.lyCard", LY.card); render(false); };
  if (document.startViewTransition && !matchMedia("(prefers-reduced-motion: reduce)").matches) document.startViewTransition(go); else go();
}
function bindLyrics() {
  $("#lyclose")?.addEventListener("click", back);
  $("#lyfocus")?.addEventListener("click", () => { $("#app").classList.toggle("focus"); render(true); });
  $("#lysong")?.addEventListener("click", toggleCard);
  $("#bctitle")?.addEventListener("click", toggleCard);
  $("#lylines")?.addEventListener("click", (e) => {
    const line = e.target.closest(".ly-line[data-t]"); if (!line) return;
    audio.currentTime = Math.max(0, +line.dataset.t); if (audio.paused) audio.play(); Lyrics.paint();
  });
  document.querySelectorAll("[data-lymode]").forEach((b) => b.onclick = () => {
    if (LY.mode === b.dataset.lymode) return;
    LY.mode = b.dataset.lymode; store.set("tunio.lyMode", LY.mode);
    const seg = b.closest(".seg"); seg.classList.toggle("at2", LY.mode === "word");
    seg.querySelectorAll("button").forEach((x) => x.classList.toggle("on", x === b));
    setTimeout(() => { render(false); Lyrics.paint(); }, 260); // let the highlight slide over first
  });
  $("#ly3dbtn")?.addEventListener("click", async () => {
    LY.d3 = !LY.d3; store.set("tunio.ly3d", LY.d3);
    if (LY.d3) await Beat.enable(); // learns the tempo; without it, words pop in the middle way
    Stage.reset(); render(false); Lyrics.paint();
  });
  if ($("#lybig")) {
    $("#bcplay").onclick = Player.toggle; $("#bcnext").onclick = () => Player.next(); $("#bcprev").onclick = Player.prev;
    $("#bcshuf").onclick = Player.toggleShuffle; $("#bcrep").onclick = Player.cycleRepeat;
    $("#bclike").onclick = () => { const t = Player.current(); if (t) toggleLike(t); };
    $("#bcbar").onclick = (e) => { const r = e.currentTarget.getBoundingClientRect(); if (audio.duration) audio.currentTime = (e.clientX - r.left) / r.width * audio.duration; };
  }
  if (LY.d3) { Stage.reset(); if (Beat.on === false) Beat.enable(); }
  tickProgress();
  Lyrics.paint();
}

// ------------------------------------------------------------------ resizable sections
/** Drags a splitter. onMove gets how far the mouse moved (px) since the drag started. */
function dragSplit(el, kind, { start, move, end, reset }) {
  el.addEventListener("pointerdown", (e) => {
    if (e.button !== 0) return;
    e.preventDefault(); el.setPointerCapture(e.pointerId);
    const x0 = e.clientX, y0 = e.clientY; start?.();
    el.classList.add("drag"); document.documentElement.classList.add("resizing", kind);
    let raf = 0, last = e;
    const onMove = (ev) => { last = ev; if (!raf) raf = requestAnimationFrame(() => { raf = 0; move(last.clientX - x0, last.clientY - y0); }); };
    const onUp = () => {
      el.removeEventListener("pointermove", onMove); el.removeEventListener("pointerup", onUp); el.removeEventListener("pointercancel", onUp);
      el.classList.remove("drag"); document.documentElement.classList.remove("resizing", kind); end?.();
    };
    el.addEventListener("pointermove", onMove); el.addEventListener("pointerup", onUp); el.addEventListener("pointercancel", onUp);
  });
  if (reset) el.addEventListener("dblclick", reset);
}
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const SIZES = { side: store.get("tunio.sideW", 300), np: store.get("tunio.npW", 320), rows: store.get("tunio.npRows2", [0.7, 0.3]) };
const ROW_MIN = [260, 80]; // song + lyrics, queue (px)
function applySizes() {
  const app = $("#app");
  app.style.setProperty("--side-user", SIZES.side + "px");
  app.style.setProperty("--np-user", SIZES.np + "px");
  document.querySelectorAll(".np-sec").forEach((sec, i) => sec.style.setProperty("--grow", SIZES.rows[i]));
}
function setupSplitters() {
  applySizes();
  const app = $("#app"), roomForMain = () => innerWidth - 16 - 16 - 380; // keep at least 380 px for the page
  // Your Library: drag narrower to shrink it to covers only, wider to bring the names back
  let w0 = 0;
  dragSplit($("#split-side"), "col", {
    start: () => { w0 = $("#side").getBoundingClientRect().width; },
    move: (dx) => {
      const w = w0 + dx, npW = npVisible() ? SIZES.np : 0;
      if (w < 150) { if (!layout.sideMin) { layout.sideMin = true; applyLayout(); renderSide(); } return; }
      if (layout.sideMin) { layout.sideMin = false; applyLayout(); renderSide(); }
      SIZES.side = Math.round(clamp(w, 220, Math.min(560, roomForMain() - npW))); applySizes();
    },
    end: () => { store.set("tunio.sideW", SIZES.side); store.set("tunio.sideMin", layout.sideMin); renderNp(true); },
    reset: () => { SIZES.side = 300; layout.sideMin = false; applyLayout(); applySizes(); renderSide(); store.set("tunio.sideW", 300); store.set("tunio.sideMin", false); },
  });
  // Now playing column
  dragSplit($("#split-np"), "col", {
    start: () => { w0 = $("#np").getBoundingClientRect().width; },
    move: (dx) => { const sideW = layout.sideMin ? 72 : SIZES.side; SIZES.np = Math.round(clamp(w0 - dx, 260, Math.min(620, roomForMain() - sideW))); applySizes(); },
    end: () => { store.set("tunio.npW", SIZES.np); renderNp(true); },
    reset: () => { SIZES.np = 320; applySizes(); store.set("tunio.npW", 320); },
  });
  // song / lyrics / queue
  document.querySelectorAll(".split.row").forEach((sp) => {
    const i = +sp.dataset.row; let h = [];
    dragSplit(sp, "row", {
      start: () => { h = [...document.querySelectorAll(".np-sec")].map((x) => x.getBoundingClientRect().height); },
      move: (_, dy) => {
        const pair = h[i] + h[i + 1], a = clamp(h[i] + dy, ROW_MIN[i], pair - ROW_MIN[i + 1]);
        const next = h.slice(); next[i] = a; next[i + 1] = pair - a;
        const total = next.reduce((x, y) => x + y, 0);
        SIZES.rows = next.map((x) => +(x / total).toFixed(4)); applySizes();
      },
      end: () => store.set("tunio.npRows2", SIZES.rows),
      reset: () => { SIZES.rows = [0.7, 0.3]; applySizes(); store.set("tunio.npRows2", SIZES.rows); },
    });
  });
}

// ------------------------------------------------------------------ window (Tunio.exe has no Windows title bar: the top bar is it)
const Win = (() => {
  let api = null, maxed = false;
  const dpr = () => devicePixelRatio || 1;
  const MAX_ICON = '<svg viewBox="0 0 10 10"><path d="M.5.5h9v9h-9z"/></svg>';
  const RESTORE_ICON = '<svg viewBox="0 0 10 10"><path d="M2.5 2.5h7v7h-7zM.5 7.5V.5h7"/></svg>';
  function setMax(m) {
    maxed = m; document.documentElement.classList.toggle("maxed", m);
    $("#wmax").innerHTML = m ? RESTORE_ICON : MAX_ICON; $("#wmax").title = m ? "Restore" : "Maximize";
  }
  async function toggleMax() { const st = await api.toggle_maximize(); setMax(st.maximized); }
  /** Moves or resizes the window while the mouse is held; calls are queued so they never pile up. */
  function track(el, e, onDelta) {
    el.setPointerCapture(e.pointerId);
    const x0 = e.screenX, y0 = e.screenY; let last = e, busy = false, dirty = false, done = false;
    const pump = async () => {
      if (busy) { dirty = true; return; }
      busy = true;
      try { do { dirty = false; await onDelta((last.screenX - x0) * dpr(), (last.screenY - y0) * dpr(), last); } while (dirty && !done); }
      finally { busy = false; }
    };
    const onMove = (ev) => { last = ev; pump(); };
    const onUp = () => { done = true; el.removeEventListener("pointermove", onMove); el.removeEventListener("pointerup", onUp); el.removeEventListener("pointercancel", onUp); };
    el.addEventListener("pointermove", onMove); el.addEventListener("pointerup", onUp); el.addEventListener("pointercancel", onUp);
  }
  async function init() {
    if (api || !window.pywebview?.api?.toggle_maximize) return;
    api = window.pywebview.api;
    document.documentElement.classList.add("framed");
    $("#winctl").classList.remove("hidden"); $("#edges").classList.remove("hidden");
    $("#wmin").onclick = () => api.minimize(); $("#wmax").onclick = toggleMax; $("#wclose").onclick = () => api.close();
    setMax((await api.state()).maximized);
    const top = $("#top");
    const isBar = (t) => !t.closest("button, input, label, a, .searchbox");
    top.addEventListener("dblclick", (e) => { if (isBar(e.target)) toggleMax(); });
    // drag the window by any empty part of the top bar
    top.addEventListener("pointerdown", async (e) => {
      if (e.button !== 0 || !isBar(e.target)) return;
      const st = await api.state(); let rect = st.rect, moved = false;
      setMax(st.maximized);
      track(top, e, async (dx, dy, ev) => {
        if (!moved) {
          if (Math.hypot(dx, dy) < 5 * dpr()) return;
          moved = true;
          if (maxed) { // pull a maximized window down: back to normal size, under the mouse
            const r = await api.unmaximize_at(ev.screenX * dpr(), ev.screenY * dpr(), e.clientX / innerWidth); setMax(false);
            rect = [r.rect[0] - dx, r.rect[1] - dy, r.rect[2], r.rect[3]];
          }
        }
        await api.set_rect(rect[0] + dx, rect[1] + dy, rect[2], rect[3]);
      });
    });
    // resize by the window edges and corners
    $("#edges").addEventListener("pointerdown", async (e) => {
      const dir = e.target.dataset?.e; if (!dir || e.button !== 0) return;
      e.preventDefault();
      const r0 = (await api.state()).rect, minW = 820 * dpr(), minH = 600 * dpr();
      track(e.target, e, async (dx, dy) => {
        let [x, y, w, h] = r0;
        if (dir.includes("e")) w = Math.max(minW, r0[2] + dx);
        if (dir.includes("s")) h = Math.max(minH, r0[3] + dy);
        if (dir.includes("w")) { w = Math.max(minW, r0[2] - dx); x = r0[0] + r0[2] - w; }
        if (dir.includes("n")) { h = Math.max(minH, r0[3] - dy); y = r0[1] + r0[3] - h; }
        await api.set_rect(Math.round(x), Math.round(y), Math.round(w), Math.round(h));
      });
    });
  }
  window.addEventListener("pywebviewready", init);
  return { init, get api() { return api; } };
})();

/** The mini player (a small window that stays on top when Tunio is minimized or closed) shows what's playing here
 *  and sends its buttons back. */
window.TunioRemote = {
  cmd(name, arg) {
    if (name === "toggle") Player.toggle();
    if (name === "next") Player.next();
    if (name === "prev") Player.prev();
    if (name === "seek" && audio.duration) audio.currentTime = Math.max(0, Math.min(audio.duration, +arg));
    if (name === "like") { const t = Player.current(); if (t) toggleLike(t); }
  },
};
let lastReport = "";
setInterval(() => {
  const api = window.pywebview?.api; if (!api?.report) return;
  const t = Player.current();
  const st = t ? { id: t.id, title: t.title, artist: t.artist || "", cover: t.thumbnail || "", playing: !audio.paused, loading: Player.preparing,
    time: audio.currentTime || 0, duration: (isFinite(audio.duration) && audio.duration) || t.durationSec || 0, liked: S.lib.liked.includes(t.id) } : {};
  const key = JSON.stringify({ ...st, time: Math.floor((st.time || 0) * 2) });
  if (key !== lastReport) { lastReport = key; api.report(st); }
}, 400);

// ------------------------------------------------------------------ start
setupTop(); setupBar(); setupNp(); applyLayout(); setupSplitters(); Win.init();
window.addEventListener("resize", () => { renderNp(); $("#bnp").classList.toggle("on", npVisible()); Lyrics.paint(); });
render(true);
poll();
