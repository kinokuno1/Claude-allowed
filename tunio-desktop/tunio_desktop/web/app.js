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
const LIKED_G = "linear-gradient(135deg,#7C4DEE,#D4337F)", DL_G = "linear-gradient(135deg,#3B3A8F,#0A8A62)";
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
const art = (url, cls = "art") => `<div class="${cls}">${url ? `<img src="${esc(url)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : icon("music_note")}</div>`;
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
const fab = (ctx, cls = "") => `<button class="fab ${cls} ${ctxPlaying(ctx) ? "live" : ""}" data-playctx="${ctx}" title="${ctxPlaying(ctx) ? "Pause" : "Play"}">${icon(ctxPlaying(ctx) && !audio.paused ? "pause" : "play_arrow")}</button>`;

// Track tables: # · Title · Source · ♥ · duration · ⋯ (Spotify-style; double-click or the ▶ on hover plays).
const LISTS = {}; // listKey -> tracks currently shown
function trackTable(key, tracks, { playlistId, compact = false, head = true, offset = 0 } = {}) {
  LISTS[key] = tracks;
  const cur = Player.current()?.id;
  const rows = tracks.map((t, i) => {
    const isCur = cur === t.id, liked = S.lib.liked.includes(t.id);
    return `<div class="row-track${isCur ? " current" : ""}" data-play="${key}" data-i="${i}" tabindex="0">
      <div class="num">${isCur ? eqBars() : `<span class="n">${offset + i + 1}</span>`}<span class="p" data-rowplay title="Play">${icon(isCur && !audio.paused ? "pause" : "play_arrow")}</span></div>
      <div class="ttl">${art(t.thumbnail)}<div class="ellip"><div class="t ellip">${esc(t.title)}</div>
        <div class="s">${badge(t.source)}${offlineMark(t)}<span class="ellip">${esc(t.artist || "")}</span></div></div></div>
      <div class="col-album ellip">${SOURCES[t.source]?.[0] || "Web"}</div>
      <button class="icon-btn hov ${liked ? "liked" : ""}" data-like="${key}" data-i="${i}" title="${liked ? "Remove from Liked songs" : "Save to Liked songs"}">${icon(liked ? "favorite" : "favorite_border")}</button>
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
    if (e.target.closest("[data-rowplay]")) return playFromList(row.dataset.play, +row.dataset.i);
    document.querySelectorAll(".row-track.sel").forEach((r) => r.classList.remove("sel")); row.classList.add("sel"); return;
  }
  const nav = e.target.closest("[data-go]");
  if (nav) go(nav.dataset.go);
});
document.addEventListener("dblclick", (e) => { const row = e.target.closest("[data-play]"); if (row && !e.target.closest("button")) playFromList(row.dataset.play, +row.dataset.i); });
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
  if (e.key === "Escape") { if (!$("#modal").classList.contains("hidden")) $("#modal").classList.add("hidden"); else if (!$("#menu").classList.contains("hidden")) closeMenu(); else if (typing) document.activeElement.blur(); return; }
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
  if (r === "search" && !navigated && $("#results")) renderSearchResults();
  else {
    if (r.startsWith("pl:") && !playlist(r.slice(3)) && S.loaded) { S.hist.splice(S.hi, 1); S.hi = Math.max(0, S.hi - 1); return render(true); }
    sc.innerHTML = `<div class="page">${r === "home" ? homeHtml() : r === "search" ? searchHtml() : r === "settings" ? settingsHtml() : collectionHtml(r)}</div>`;
    bindScreen(r);
    if (r === "search") renderSearchResults();
  }
  sc.scrollTop = navigated ? 0 : top;
  updateTop(); renderSide(); updateBar(); renderNp();
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
      <div class="cover">${art(t.thumbnail)}<button class="fab ${Player.current()?.id === t.id && isPlaying() ? "live" : ""}" data-playcard="recent" data-i="${i}" title="Play">${icon(Player.current()?.id === t.id && !audio.paused ? "pause" : "play_arrow")}</button></div>
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
          <h1>${esc(r.title || "Imported list")}</h1><div class="stats">${r.tracks.length} songs<span class="muted">${total ? `, about ${Math.round(total / 60)} min` : ""}</span></div></div></div>
        <div class="coll-actions"><button class="fab xl" id="playall" title="Play">${icon("play_arrow")}</button>
          <button class="icon-btn" id="shufall" title="Shuffle play">${icon("shuffle")}</button>
          <button class="btn" id="saveall">${icon("playlist_add")}Save as playlist</button></div>
        ${trackTable("search", r.tracks)}`;
    } else if (s.fromLink) {
      h += trackTable("search", r.tracks);
    } else {
      const top = r.tracks[0];
      h += `<div class="top-result"><div><div class="sec-head" style="margin-top:0"><h2>Top result</h2></div>
          <div class="top-card" data-playcard="search" data-i="0" role="button" tabindex="0">${art(top.thumbnail)}
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
    toast(`Saved as playlist “${p.name}”`); await poll(); go("pl:" + p.id); });
}
const isLink = (q) => /https?:\/\/\S+|spotify:(track|album|playlist):|open\.spotify\.com\/track\//.test(q) || /^(www\.|m\.|vm\.)?[a-z0-9-]+\.(com|be|link|fi)\/\S+/i.test(q.trim());
async function runSearch() {
  const s = S.search, q = s.q.trim(); if (!q) return;
  const my = ++s.token; s.loading = true; s.error = null; s.result = null; s.fromLink = isLink(q); renderSearchResults();
  try {
    const r = s.fromLink ? await api("resolve", { text: q }) : await api("search", { source: s.source, q });
    if (my !== s.token) return;
    s.result = r; if (!r.tracks.length) s.error = "Nothing found.";
    // Get the top result ready to play while you're still reading the list.
    if (r.tracks[0]) api("prepare", { track: r.tracks[0], prefetch: true }).catch(() => {});
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
    : `<div class="header-glow" style="--tint:${r === "liked" ? "#7C4DEE" : "#2F6F8F"};height:420px"></div>`;
  return `${glow}
    <div class="coll-head">${collArt(r)}<div style="min-width:0"><div class="kind">${r === "downloads" ? "Offline" : "Playlist"}</div>
      <h1>${esc(title)}</h1><div class="stats"><b>Tunio</b> · ${tracks.length} songs<span class="muted">${total ? `, about ${total >= 3600 ? `${Math.floor(total / 3600)} hr ` : ""}${Math.round(total % 3600 / 60)} min` : ""}</span></div></div></div>
    <div class="coll-actions">
      <button class="fab xl" data-playctx="${r}" title="Play" ${tracks.length ? "" : "disabled"}>${icon(ctxPlaying(r) && !audio.paused ? "pause" : "play_arrow")}</button>
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

// ------------------------------------------------------------------ audio graph: volume + visualizer
const audio = $("#audio");
/** Web Audio: element → analyser (visualizer, independent of volume) → gain (volume) → speakers.
 *  Created on the first play (a click), so the browser lets it run. If it can't be created, the plain
 *  <audio> volume is used and the visualizer stays off — songs always play. */
const Graph = (() => {
  let ctx = null, analyser = null, gain = null, failed = false;
  function ensure() {
    if (ctx) { if (ctx.state !== "running") ctx.resume().catch(() => {}); return; }
    if (failed) return;
    try {
      ctx = new (window.AudioContext || window.webkitAudioContext)();
      const src = ctx.createMediaElementSource(audio);
      analyser = ctx.createAnalyser(); analyser.fftSize = 2048; analyser.smoothingTimeConstant = 0.78;
      gain = ctx.createGain();
      src.connect(analyser); analyser.connect(gain); gain.connect(ctx.destination);
      audio.volume = 1; Volume.apply();
      if (ctx.state !== "running") ctx.resume().catch(() => {});
    } catch (e) { failed = true; ctx = null; console.warn("visualizer off:", e); }
  }
  audio.addEventListener("play", () => { if (ctx && ctx.state !== "running") ctx.resume().catch(() => {}); Viz.wake(); });
  return { ensure, get analyser() { return analyser; }, get gain() { return gain; }, get ctx() { return ctx; } };
})();

const Volume = (() => {
  let value = 0.8, muted = false, saveT = 0, ui = null;
  const curve = (v) => v * v; // feels even across the slider
  function apply() {
    const g = muted ? 0 : curve(value);
    if (Graph.gain) { Graph.gain.gain.setTargetAtTime(g, Graph.ctx.currentTime, 0.015); audio.volume = 1; } else audio.volume = g;
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

/** Live spectrum in the now-playing panel: log-spaced bars in the accent gradient, with falling peak caps. */
const Viz = (() => {
  let raf = 0, data = null, levels = [], peaks = [];
  const BARS = 40;
  function wake() { if (!raf) raf = requestAnimationFrame(draw); }
  function draw() {
    raf = 0;
    const cv = $("#viz"); if (!cv || !cv.offsetParent) return; // panel hidden: sleep until the next play/open
    const dpr = devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
    if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
    const g = cv.getContext("2d"); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, h);
    const an = Graph.analyser, live = an && !audio.paused;
    if (an) { if (!data) data = new Uint8Array(an.frequencyBinCount); an.getByteFrequencyData(data); }
    const sr = Graph.ctx?.sampleRate || 48000, binHz = sr / 2048;
    let moving = false;
    const gap = 3, bw = (w - gap * (BARS - 1)) / BARS;
    const grad = g.createLinearGradient(0, h, 0, 0); grad.addColorStop(0, "#7C4DEE"); grad.addColorStop(1, "#D4337F");
    for (let i = 0; i < BARS; i++) {
      // 40 Hz … 14 kHz, spaced like hearing (logarithmic)
      const f0 = 40 * Math.pow(14000 / 40, i / BARS), f1 = 40 * Math.pow(14000 / 40, (i + 1) / BARS);
      let target = 0;
      if (live) { const a = Math.max(1, Math.floor(f0 / binHz)), b = Math.max(a + 1, Math.ceil(f1 / binHz)); let m = 0; for (let k = a; k < b && k < data.length; k++) m = Math.max(m, data[k]); target = Math.pow(m / 255, 1.6); }
      const lv = levels[i] = (levels[i] || 0) + (target - (levels[i] || 0)) * (target > (levels[i] || 0) ? 0.6 : 0.18);
      peaks[i] = Math.max(lv, (peaks[i] || 0) - 0.012);
      if (lv > 0.003 || peaks[i] > 0.003) moving = true;
      const bh = Math.max(live ? 2 : 0, lv * (h - 26)), x = i * (bw + gap);
      if (bh >= 1) { g.fillStyle = grad; g.beginPath(); g.roundRect(x, h - bh, bw, bh, [2, 2, 0, 0]); g.fill(); }
      if (peaks[i] > 0.04) { g.fillStyle = "rgba(247,247,250,.85)"; g.fillRect(x, h - Math.max(2, peaks[i] * (h - 26)) - 4, bw, 2); }
    }
    if (live || moving) raf = requestAnimationFrame(draw);
  }
  return { wake };
})();

// ------------------------------------------------------------------ player
const Player = (() => {
  let queue = [], index = -1, original = null, shuffle = false, repeat = "off", preparing = false, loadToken = 0, autoplayBusy = false, failures = 0, context = null;
  const current = () => queue[index] || null;
  async function load(i, autoplayCheck = true) {
    index = i; const t = current(); if (!t) return;
    Graph.ensure();
    const my = ++loadToken; preparing = true; audio.pause(); updateUi();
    try {
      const { src } = await api("prepare", { track: t });
      if (my !== loadToken) return;
      audio.src = `${src}?t=${encodeURIComponent(TOKEN)}`;
      await audio.play().catch(() => {});
      failures = 0;
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
    const next = queue[index + 1]; // warm up the next song so it starts instantly
    if (next) api("prepare", { track: next, prefetch: true }).catch(() => {});
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
  function prev() { if (audio.currentTime > 3 || index <= 0) audio.currentTime = 0; else load(index - 1); }
  function toggle() { if (!current() || preparing) return; Graph.ensure(); if (!audio.src) return load(index); audio.paused ? audio.play() : audio.pause(); }
  function toggleShuffle() {
    shuffle = !shuffle; const cur = current(); if (!cur) return updateUi();
    if (shuffle) { original = queue.slice(); queue = [...queue.slice(0, index + 1), ...queue.slice(index + 1).sort(() => Math.random() - .5)]; }
    else if (original) { queue = original; index = Math.max(0, queue.findIndex((t) => t.id === cur.id)); }
    updateUi();
  }
  function cycleRepeat() { repeat = repeat === "off" ? "all" : repeat === "all" ? "one" : "off"; updateUi(); }
  function playNext(t) { if (!current()) return play([t]); queue.splice(index + 1, 0, t); toast(`Plays next: ${t.title}`); updateUi(); }
  function addToQueue(t) { if (!current()) return play([t]); queue.push(t); toast(`Added to queue: ${t.title}`); updateUi(); }
  function remove(i) { if (i === index) return; queue.splice(i, 1); if (i < index) index--; updateUi(); }
  function jump(i) { load(i); }
  audio.addEventListener("ended", () => next(true));
  audio.addEventListener("error", () => { if (!audio.src || preparing) return; const t = current(); toast(`Playback error: ${t?.title || ""}`); if (++failures < 3) next(true); });
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
    get queue() { return queue; }, get index() { return index; }, get shuffle() { return shuffle; }, get repeat() { return repeat; },
    get preparing() { return preparing; }, get context() { return context; } };
})();

let uiKey = "";
function updateUi() {
  updateBar(); renderNp();
  // Play/pause state shows in many places (rows, cards, sidebar): re-render the page only when it changed.
  const key = JSON.stringify([Player.current()?.id, audio.paused, Player.preparing, Player.context, Player.shuffle]);
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
        <button class="pp" id="bplay" title="Play">${icon("play_arrow")}</button>
        <button class="icon-btn skip" id="bnext" title="Next">${icon("skip_next")}</button>
        <button class="icon-btn" id="brep" title="Enable repeat">${icon("repeat")}</button>
      </div>
      <div class="prog"><span id="tcur">0:00</span><div class="slider" id="seek" aria-label="Song progress"></div><span id="tdur">0:00</span></div>
    </div>
    <div class="side">
      <button class="icon-btn" id="bdl" title="Download">${icon("download")}</button>
      <button class="icon-btn" id="bnp" title="Now playing view">${icon("view_sidebar")}</button>
      <button class="icon-btn" id="vbtn" title="Mute">${icon("volume_up")}</button>
      <div class="slider vol" id="vol" aria-label="Volume"></div>
    </div>`;
  $("#bplay").onclick = Player.toggle; $("#bnext").onclick = () => Player.next(); $("#bprev").onclick = Player.prev;
  $("#bshuf").onclick = Player.toggleShuffle; $("#brep").onclick = Player.cycleRepeat;
  $("#bnp").onclick = toggleNp; $("#vbtn").onclick = () => Volume.toggleMute();
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
    now.innerHTML = t ? `${art(t.thumbnail)}<div class="ellip"><div class="t ellip" id="btitle">${esc(t.title)}</div><div class="a ellip">${Player.preparing ? "Loading…" : esc(t.artist || SOURCES[t.source]?.[0])}</div></div>
      <button class="icon-btn ${S.lib.liked.includes(t.id) ? "liked" : ""}" id="blike" title="${S.lib.liked.includes(t.id) ? "Remove from Liked songs" : "Save to Liked songs"}">${icon(S.lib.liked.includes(t.id) ? "favorite" : "favorite_border")}</button>` : "";
    if (t) { $("#blike").onclick = () => toggleLike(t); now.querySelector(".art").onclick = $("#btitle").onclick = () => { if (!npVisible()) toggleNp(); }; }
  }
  const pp = $("#bplay");
  pp.disabled = !t;
  pp.innerHTML = Player.preparing ? `<span class="spinner" style="border-top-color:var(--c10)"></span>` : icon(playing ? "pause" : "play_arrow");
  pp.title = playing ? "Pause" : "Play";
  $("#bshuf").classList.toggle("on", Player.shuffle); $("#bshuf").title = Player.shuffle ? "Disable shuffle" : "Enable shuffle";
  $("#brep").classList.toggle("on", Player.repeat !== "off"); $("#brep").innerHTML = icon(Player.repeat === "one" ? "repeat_one" : "repeat");
  $("#brep").title = Player.repeat === "off" ? "Enable repeat" : Player.repeat === "all" ? "Enable repeat one" : "Disable repeat";
  const off = t && S.offline.has(t.id), p = t ? S.progress[t.id] : undefined;
  $("#bdl").classList.toggle("good", !!off); $("#bdl").disabled = !t;
  $("#bdl").innerHTML = p !== undefined && !off ? `<span class="ring ${p < 0 ? "busy" : ""}" style="--p:${Math.max(0, p)}"></span>` : icon(off ? "download_done" : "download");
  $("#bdl").title = off ? "Downloaded (click to remove)" : "Download";
  $("#bnp").classList.toggle("on", npVisible());
  $("#seek").classList.toggle("busy", !!Player.preparing);
  tickProgress();
}
function tickProgress() {
  const d = audio.duration || Player.current()?.durationSec || 0, c = audio.currentTime || 0, frac = d && isFinite(d) ? Math.min(1, c / d) : 0;
  if (progress && !seeking) { progress.set(frac); $("#tcur").textContent = fmt(Player.current() ? c : 0); $("#tdur").textContent = fmt(isFinite(d) ? d : 0); }
}
audio.addEventListener("timeupdate", tickProgress);
audio.addEventListener("durationchange", tickProgress);

// ------------------------------------------------------------------ now-playing panel
function setupNp() {
  $("#np").innerHTML = `<div class="panel-head"><h2 id="npctx" class="ellip">Now playing</h2><span class="spacer"></span>
      <button class="round small" id="npclose" title="Hide Now playing view">${icon("close")}</button></div>
    <div class="np-body"><div id="npmain"></div>
      <div class="viz-wrap" id="vizwrap"><span class="viz-label">LIVE AUDIO</span><canvas id="viz"></canvas></div>
      <div id="npqueue"></div></div>`;
  $("#npclose").onclick = toggleNp;
}
function renderNp() {
  if (!npVisible()) return;
  const t = Player.current(), main = $("#npmain"), q = $("#npqueue");
  $("#vizwrap").classList.toggle("hidden", !t);
  $("#npctx").textContent = t ? collName(Player.context) : "Now playing";
  if (!t) { main.innerHTML = `<div class="empty">${icon("graphic_eq")}<p>Nothing playing yet.<br>Pick a song and it shows up here, with its live audio and what plays next.</p></div>`; q.innerHTML = ""; main.dataset.key = ""; return; }
  const liked = S.lib.liked.includes(t.id), p = S.progress[t.id];
  const upNext = Player.queue.slice(Player.index + 1, Player.index + 31); LISTS.queue = upNext;
  const key = JSON.stringify([t.id, liked, Player.preparing, p, upNext.map((x) => x.id), S.offline.has(t.id)]);
  if (main.dataset.key !== key) {
    main.dataset.key = key;
    main.innerHTML = `<div class="np-cover">${t.thumbnail ? `<img src="${esc(t.thumbnail)}" alt="" referrerpolicy="no-referrer">` : ""}
        ${Player.preparing ? `<div class="wait"><span class="spinner" style="width:32px;height:32px;border-width:3px"></span><span>${p !== undefined && p >= 0 ? `Fetching audio ${Math.round(p * 100)}%` : "Finding audio…"}</span></div>` : ""}</div>
      <div class="np-info"><div style="flex:1;min-width:0"><div class="t">${esc(t.title)}</div><div class="a ellip">${badge(t.source)} ${offlineMark(t)} ${esc(t.artist || "")}</div></div>
        <button class="icon-btn ${liked ? "liked" : ""}" id="nplike" title="${liked ? "Remove from Liked songs" : "Save to Liked songs"}">${icon(liked ? "favorite" : "favorite_border")}</button>
        <button class="icon-btn" data-menu="now" data-i="0" title="More options">${icon("more_vert")}</button></div>`;
    LISTS.now = [t];
    $("#nplike").onclick = () => toggleLike(t);
    q.innerHTML = `<div class="q-card"><h3>Next in queue<span class="spacer"></span><span class="muted small" style="font-weight:400">${Player.queue.length - Player.index - 1 || ""}</span></h3>
      ${upNext.length ? upNext.map((x, i) => `<div class="q-item" data-q="${i}" role="button" tabindex="0">${art(x.thumbnail)}<div class="ellip" style="flex:1"><div class="t ellip">${esc(x.title)}</div>
        <div class="muted small ellip">${esc(x.artist || "")}</div></div><button class="icon-btn" data-rmq="${i}" title="Remove from queue">${icon("close")}</button></div>`).join("")
        : `<p class="muted small" style="margin:4px 8px 8px">${S.settings.autoplay ? "Autoplay adds similar songs when the queue runs low." : "The queue is empty."}</p>`}</div>`;
    q.querySelectorAll("[data-q]").forEach((r) => r.onclick = (e) => {
      const rm = e.target.closest("[data-rmq]"); if (rm) return Player.remove(Player.index + 1 + +rm.dataset.rmq);
      Player.jump(Player.index + 1 + +r.dataset.q); });
  }
  Viz.wake();
}

// ------------------------------------------------------------------ start
setupTop(); setupBar(); setupNp(); applyLayout();
window.addEventListener("resize", () => { renderNp(); $("#bnp").classList.toggle("on", npVisible()); Viz.wake(); });
render(true);
poll();
