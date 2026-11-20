// Tunio for Windows — UI. Talks to the local Python engine (server.py) with a per-launch token.
import { ICONS } from "./icons.js";

const TOKEN = new URLSearchParams(location.search).get("t") || "";
const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const icon = (name, cls = "i") => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true"><path d="${ICONS[name]}"/></svg>`;
const fmt = (s) => { s = Math.max(0, Math.floor(s || 0)); if (!s) return ""; const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(x).padStart(2, "0")}` : `${m}:${String(x).padStart(2, "0")}`; };
const SOURCES = { YOUTUBE: ["YouTube", "YT", "#FF5A52"], SOUNDCLOUD: ["SoundCloud", "SC", "#FF8A3D"], TIKTOK: ["TikTok", "TT", "#25F4EE"], SPOTIFY: ["Spotify", "SP", "#1ED760"], OTHER: ["Web", "WEB", "#C4C4CC"] };
const LIKED_G = "linear-gradient(135deg,#7C4DEE,#D4337F)", DL_G = "linear-gradient(135deg,#0284C7,#0A8A62)";

async function api(path, body = {}) {
  const r = await fetch(`/api/${path}`, { method: "POST", headers: { "Content-Type": "application/json", "X-Tunio-Token": TOKEN }, body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `Error ${r.status}`);
  return j;
}

// ------------------------------------------------------------------ state
const S = {
  lib: { tracks: {}, liked: [], playlists: [], recent: [] }, offline: new Set(), progress: {}, queueInfo: {}, settings: { autoplay: true },
  offlineDir: "", engine: "",
  stack: ["home"],
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
    if (key !== libKey) { libKey = key; S.lib = s.library; refresh(); }
  } catch { /* engine starting */ }
}
setInterval(poll, 1200);

const trackById = (id) => S.lib.tracks[id];
const tracksOf = (ids) => ids.map(trackById).filter(Boolean);

// ------------------------------------------------------------------ toasts, menus, dialogs
function toast(msg) {
  const el = document.createElement("div"); el.className = "toast"; el.textContent = msg;
  $("#toasts").append(el); setTimeout(() => el.remove(), 3800);
}
function closeMenu() { $("#menu").classList.add("hidden"); }
document.addEventListener("click", (e) => { if (!e.target.closest("#menu") && !e.target.closest("[data-menu]")) closeMenu(); });

function openTrackMenu(btn, t, playlistId) {
  const m = $("#menu");
  const liked = S.lib.liked.includes(t.id), off = S.offline.has(t.id);
  m.innerHTML = [
    ["next", "skip_next", "Play next"], ["queue", "queue_music", "Add to queue"],
    ["like", liked ? "favorite" : "favorite_border", liked ? "Remove from Liked" : "Like"],
    ["addto", "playlist_add", "Add to playlist"],
    off ? ["undl", "delete", "Remove download"] : ["dl", "download", "Download (offline)"],
  ].map(([a, ic, label]) => `<button data-m="${a}">${icon(ic)}${label}</button>`).join("") +
    (playlistId ? `<hr><button data-m="rm">${icon("remove_circle_outline")}Remove from playlist</button>` : "");
  m.classList.remove("hidden");
  const r = btn.getBoundingClientRect();
  m.style.left = Math.min(innerWidth - m.offsetWidth - 8, r.right - m.offsetWidth) + "px";
  m.style.top = (r.bottom + m.offsetHeight > innerHeight - 8 ? r.top - m.offsetHeight : r.bottom) + "px";
  m.onclick = async (e) => {
    const a = e.target.closest("[data-m]")?.dataset.m; if (!a) return; closeMenu();
    if (a === "next") Player.playNext(t);
    if (a === "queue") Player.addToQueue(t);
    if (a === "like") { await api("like", { track: t }); poll(); }
    if (a === "addto") addToPlaylistDialog([t]);
    if (a === "dl") { await api("download", { tracks: [t] }); toast(`Downloading “${t.title}”`); poll(); }
    if (a === "undl") { await api("download/remove", { track: t }); poll(); }
    if (a === "rm") { await api("playlist/remove", { id: playlistId, trackId: t.id }); poll(); }
  };
}

function dialog(html, onReady) {
  const m = $("#modal"); m.innerHTML = `<div class="dialog">${html}</div>`; m.classList.remove("hidden");
  const close = () => m.classList.add("hidden");
  m.onclick = (e) => { if (e.target === m || e.target.closest("[data-close]")) close(); };
  onReady?.(m, close);
}
function nameDialog(title, initial, done) {
  dialog(`<h3>${esc(title)}</h3><input class="field" id="nm" value="${esc(initial)}" placeholder="Name">
    <div class="end"><button class="link" data-close>Cancel</button><button class="link" id="ok">OK</button></div>`, (m, close) => {
    const inp = $("#nm", m); inp.focus(); inp.select();
    const go = () => { close(); done(inp.value.trim()); };
    $("#ok", m).onclick = go; inp.onkeydown = (e) => { if (e.key === "Enter") go(); };
  });
}
function addToPlaylistDialog(tracks) {
  const lists = S.lib.playlists.map((p) => `<button data-p="${p.id}">${art(p.cover || trackById(p.trackIds[0])?.thumbnail)}
    <span><b>${esc(p.name)}</b><br><span class="muted small">${p.trackIds.length} songs</span></span></button>`).join("");
  dialog(`<h3>${tracks.length === 1 ? "Add to playlist" : `Add ${tracks.length} songs to playlist`}</h3>
    <div class="row"><input class="field" id="np" placeholder="New playlist"><button class="icon-btn glass" id="create" title="Create">${icon("add")}</button></div>
    <div class="list" style="margin-top:10px">${lists}</div><div class="end"><button class="link" data-close>Cancel</button></div>`, (m, close) => {
    $("#create", m).onclick = async () => { const name = $("#np", m).value.trim() || "My playlist";
      await api("playlist/create", { name, tracks, cover: tracks[0]?.thumbnail }); close(); toast(`Created “${name}”`); poll(); };
    m.querySelectorAll("[data-p]").forEach((b) => b.onclick = async () => {
      await api("playlist/add", { id: b.dataset.p, tracks }); close(); toast("Added"); poll(); });
  });
}

// ------------------------------------------------------------------ building blocks
const art = (url, cls = "art") => `<div class="${cls}">${url ? `<img src="${esc(url)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : icon("music_note")}</div>`;
const gradArt = (g, ic, cls = "art") => `<div class="${cls} grad" style="--g:${g}">${icon(ic)}</div>`;
const badge = (src) => `<span class="badge b-${src}">${SOURCES[src]?.[1] || "WEB"}</span>`;
function offlineMark(t) {
  if (S.offline.has(t.id)) return icon("download_done", "i offline-ok");
  const p = S.progress[t.id];
  if (p === undefined) return "";
  return p < 0 ? `<span class="ring busy"></span>` : `<span class="ring" style="--p:${p}"></span>`;
}
function trackRow(t, i, listKey, playlistId) {
  const cur = Player.current()?.id === t.id;
  return `<div class="track${cur ? " current" : ""}" data-play="${listKey}" data-i="${i}">
    ${art(t.thumbnail)}<div class="meta"><div class="t">${esc(t.title)}</div>
    <div class="s">${badge(t.source)}${offlineMark(t)}<span class="txt">${esc([t.artist, fmt(t.durationSec)].filter(Boolean).join(" · "))}</span></div></div>
    <button class="icon-btn plain" data-menu="${listKey}" data-i="${i}" ${playlistId ? `data-pl="${playlistId}"` : ""} title="More">${icon("more_vert")}</button></div>`;
}
const LISTS = {}; // listKey -> tracks currently shown (for click handling)
function trackList(key, tracks, playlistId) { LISTS[key] = tracks; return `<div class="tracks">${tracks.map((t, i) => trackRow(t, i, key, playlistId)).join("")}</div>`; }

document.addEventListener("click", (e) => {
  const menuBtn = e.target.closest("[data-menu]");
  if (menuBtn) { e.stopPropagation(); const t = LISTS[menuBtn.dataset.menu]?.[+menuBtn.dataset.i]; if (t) openTrackMenu(menuBtn, t, menuBtn.dataset.pl); return; }
  const row = e.target.closest("[data-play]");
  if (row) { const list = LISTS[row.dataset.play]; if (!list) return;
    if (row.dataset.play === "search" && !S.search.fromLink) Player.play([list[+row.dataset.i]]); else Player.play(list, +row.dataset.i); return; }
  const nav = e.target.closest("[data-go]");
  if (nav) { go(nav.dataset.go); }
});

// ------------------------------------------------------------------ navigation
const TABS = [["home", "Home", "home"], ["search", "Search", "search"], ["library", "Library", "library_music"]];
const route = () => S.stack[S.stack.length - 1];
function tab(name) { S.stack = [name]; render(true); }
function go(r) { S.stack.push(r); render(true); }
function back() { if (!$("#player").classList.contains("hidden")) return closePlayer(); if (S.stack.length > 1) { S.stack.pop(); render(true); } else if (route() !== "home") tab("home"); }
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") { if (!$("#modal").classList.contains("hidden")) $("#modal").classList.add("hidden"); else back(); }
  if (e.key === " " && !["INPUT", "TEXTAREA"].includes(document.activeElement?.tagName)) { e.preventDefault(); Player.toggle(); }
});
window.addEventListener("mouseup", (e) => { if (e.button === 3) back(); });

// ------------------------------------------------------------------ screens
function refresh() { render(false); }
function render(navigated) {
  const sc = $("#screen"), r = route(), top = sc.scrollTop;
  setBackground(r);
  renderTabs();
  if (r === "search" && !navigated && $("#q")) { renderSearchResults(); }
  else {
    sc.innerHTML = r === "home" ? homeHtml() : r === "search" ? searchHtml() : r === "library" ? libraryHtml()
      : r === "settings" ? settingsHtml() : collectionHtml(r);
    bindScreen(r);
    if (r === "search") renderSearchResults();
  }
  sc.scrollTop = navigated ? 0 : top;
  renderMini();
  if (!$("#player").classList.contains("hidden")) renderPlayer(false);
}

function coverFor(r) {
  if (r === "liked") return trackById(S.lib.liked[0])?.thumbnail;
  if (r.startsWith("pl:")) { const p = S.lib.playlists.find((x) => x.id === r.slice(3)); return p && (p.cover || trackById(p.trackIds[0])?.thumbnail); }
  return null;
}
function setBackground(r) {
  const bg = $("#bg"), c = (r === "liked" || r.startsWith("pl:")) ? coverFor(r) : null;
  const key = c || "";
  if (bg.dataset.key === key) return;
  bg.dataset.key = key;
  bg.className = c ? "cover" : "";
  bg.innerHTML = c ? `<img src="${esc(c)}" alt="" referrerpolicy="no-referrer">` : "";
}

function greeting() { const h = new Date().getHours(); return h >= 5 && h < 12 ? "Good morning" : h < 18 && h >= 12 ? "Good afternoon" : "Good evening"; }
function homeHtml() {
  const recent = tracksOf(S.lib.recent).slice(0, 20); LISTS.recent = recent;
  const tiles = [
    `<button class="tile glass" data-go="liked">${gradArt(LIKED_G, "favorite")}<div><div class="t">Liked songs</div><div class="muted small">${S.lib.liked.length} songs</div></div></button>`,
    `<button class="tile glass" data-go="downloads">${gradArt(DL_G, "download_done")}<div><div class="t">Downloads</div><div class="muted small">${S.offline.size} offline</div></div></button>`,
    ...S.lib.playlists.slice(0, 6).map((p) => `<button class="tile glass" data-go="pl:${p.id}">${art(p.cover || trackById(p.trackIds[0])?.thumbnail)}<div style="min-width:0"><div class="t">${esc(p.name)}</div><div class="muted small">${p.trackIds.length} songs</div></div></button>`),
  ].join("");
  const empty = !recent.length && !S.lib.liked.length && !S.lib.playlists.length;
  return `<div class="row"><h1 class="title">${greeting()}</h1><span class="spacer"></span><button class="icon-btn glass lensed" data-go="settings" title="Settings">${icon("settings")}</button></div>
    <div class="tiles">${tiles}</div>
    ${recent.length ? `<h2 class="section">Recently played</h2><div class="carousel">${recent.map((t, i) => `<button class="card" data-play="recent" data-i="${i}">${art(t.thumbnail)}<div class="t">${esc(t.title)}</div><div class="a">${esc(t.artist)}</div></button>`).join("")}</div>` : ""}
    ${empty ? `<div class="panel glass" style="margin-top:18px"><h2 style="margin:0 0 8px">Welcome to Tunio</h2>
      <p class="muted">• Search YouTube or SoundCloud in the Search tab.<br>• Paste a TikTok, Spotify, YouTube or SoundCloud link (song or playlist).<br>
      • Click ⋮ on any song → Download to keep it offline (saved in your Music\\Tunio folder).</p>
      <button class="btn accent" data-tab="search">${icon("search")}Start searching</button></div>` : ""}`;
}

function searchHtml() {
  const s = S.search;
  return `<h1 class="title">Search</h1>
    <div class="search glass lensed">${icon(isLink(s.q) ? "link" : "search")}<input id="q" placeholder="Songs, artists, or paste a link" value="${esc(s.q)}" autocomplete="off">
      <button class="icon-btn plain ${s.q ? "" : "hidden"}" id="clear" title="Clear">${icon("clear")}</button></div>
    <div class="chips">${["YOUTUBE", "SOUNDCLOUD"].map((k) => `<button class="chip glass ${s.source === k ? "on" : ""}" data-src="${k}" style="${s.source === k ? `background:${SOURCES[k][2]}` : ""}">${SOURCES[k][0]}</button>`).join("")}</div>
    <div id="results"></div>`;
}
function renderSearchResults() {
  const s = S.search, el = $("#results"); if (!el) return;
  let h = "";
  if (!s.result && !s.loading && !s.error) h = `<div class="panel glass muted small">TikTok &amp; Spotify: paste a link (song, playlist or album). Spotify songs play from YouTube.<br>
    Private Spotify playlists: make them public, or in the Spotify app select all songs (Ctrl+A), copy (Ctrl+C) and paste here.</div>`;
  if (s.loading) h += `<div class="loading"><span class="spinner"></span>${s.fromLink ? "Reading link…" : "Searching…"}</div>`;
  if (s.error) h += `<div class="err">${esc(s.error)}</div>`;
  const r = s.result;
  if (r?.tracks?.length) {
    if (s.fromLink && r.tracks.length > 1) h += `<div class="panel glass"><div class="row">${art(r.cover || r.tracks[0].thumbnail)}<div style="flex:1;min-width:0">
      <div style="font-weight:700;font-size:19px">${esc(r.title || "Imported list")}</div><div class="muted small">${r.tracks.length} songs</div></div></div>
      <div class="actions"><button class="btn accent" id="playall">${icon("play_arrow")}Play</button><button class="btn glass" id="saveall">${icon("playlist_add")}Save as playlist</button></div></div>`;
    h += trackList("search", r.tracks);
  }
  el.innerHTML = h;
  $("#playall")?.addEventListener("click", () => Player.play(r.tracks, 0));
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
  if (my === s.token) { s.loading = false; renderSearchResults(); }
}

function libraryHtml() {
  const row = (go, artHtml, t, sub) => `<button class="tile" style="width:100%" data-go="${go}">${artHtml}<div style="min-width:0"><div class="t" style="font-size:16px">${esc(t)}</div><div class="muted small">${esc(sub)}</div></div></button>`;
  return `<div class="row"><h1 class="title">Your Library</h1><span class="spacer"></span><button class="icon-btn glass lensed" id="newpl" title="New playlist">${icon("add")}</button></div>
    <div class="panel glass" style="padding:6px">${row("liked", gradArt(LIKED_G, "favorite"), "Liked songs", `${S.lib.liked.length} songs`)}${row("downloads", gradArt(DL_G, "download_done"), "Downloads", `${S.offline.size} songs available offline`)}</div>
    ${S.lib.playlists.length ? `<h2 class="section">Playlists</h2>` : ""}
    ${S.lib.playlists.map((p) => row("pl:" + p.id, art(p.cover || trackById(p.trackIds[0])?.thumbnail), p.name, `Playlist · ${p.trackIds.length} songs`)).join("")}`;
}

function collectionHtml(r) {
  const pl = r.startsWith("pl:") ? S.lib.playlists.find((p) => p.id === r.slice(3)) : null;
  if (r.startsWith("pl:") && !pl) { setTimeout(back); return ""; }
  const tracks = r === "liked" ? tracksOf(S.lib.liked) : r === "downloads"
    ? Object.values(S.lib.tracks).filter((t) => S.offline.has(t.id)).sort((a, b) => a.title.localeCompare(b.title)) : tracksOf(pl.trackIds);
  const title = r === "liked" ? "Liked songs" : r === "downloads" ? "Downloads" : pl.name;
  const heroArt = r === "liked" ? gradArt(LIKED_G, "favorite") : r === "downloads" ? gradArt(DL_G, "download_done") : art(pl.cover || tracks[0]?.thumbnail);
  const missing = tracks.filter((t) => !S.offline.has(t.id)).length, busy = tracks.filter((t) => t.id in S.progress).length;
  let dl = "";
  if (r !== "downloads" && tracks.length) dl = !missing ? `<span class="row good small" style="gap:6px">${icon("download_done")}Available offline</span>`
    : busy ? `<span class="row muted small" style="gap:8px"><span class="spinner"></span>Downloading ${busy}…</span>`
    : `<button class="btn glass lensed" id="dlall">${icon("download")}Download ${missing}</button>`;
  if (r === "downloads") dl = `<button class="btn glass lensed" id="openfolder">${icon("folder_open")}Open folder</button>`;
  const empty = r === "liked" ? "Songs you like appear here. Click ⋮ → Like on any song." : r === "downloads" ? "Nothing downloaded yet. Click ⋮ → Download on a song, or Download on a playlist." : "This playlist is empty. Add songs from Search with ⋮ → Add to playlist.";
  LISTS._coll = tracks;
  return `<div class="row"><button class="icon-btn glass lensed" id="back" title="Back">${icon("arrow_back")}</button><span class="spacer"></span>
      ${pl ? `<button class="icon-btn glass lensed" id="rename" title="Rename">${icon("edit")}</button><button class="icon-btn glass lensed" id="del" title="Delete">${icon("delete")}</button>` : ""}</div>
    <div class="hero">${heroArt}<h1>${esc(title)}</h1><div class="muted small">${tracks.length} songs</div></div>
    <div class="actions">${dl}<span class="spacer"></span>
      <button class="icon-btn glass lensed" id="shuffle" title="Shuffle" style="width:48px;height:48px;color:var(--accent-light)">${icon("shuffle")}</button>
      <button class="play-circle" id="playc" title="Play" ${tracks.length ? "" : "disabled"}>${icon("play_arrow")}</button></div>
    ${tracks.length ? trackList("coll", tracks, pl?.id) : `<div class="panel glass muted">${empty}</div>`}`;
}

function settingsHtml() {
  return `<div class="row"><button class="icon-btn glass lensed" id="back" title="Back">${icon("arrow_back")}</button><h1 class="title" style="margin:0">Settings</h1></div>
    <div class="panel glass"><b>Autoplay</b><div class="row" style="margin-top:6px"><span class="muted small" style="flex:1">When 3 or fewer songs are left, add similar songs (like Spotify's autoplay) and load the next song in advance.</span>
      <button class="switch ${S.settings.autoplay ? "on" : ""}" id="autoplay" title="Autoplay"></button></div></div>
    <div class="panel glass"><b>Offline songs</b><p class="muted small">Saved in <b style="color:var(--text)">${esc(S.offlineDir)}</b>. They stay there even if you delete Tunio.</p>
      <button class="btn glass" id="openfolder">${icon("folder_open")}Open folder</button></div>
    <div class="panel glass"><b>Playback cache</b><p class="muted small" id="cachesz">Songs you've played, so replays are instant. The oldest are removed automatically above 1 GB.</p>
      <button class="btn glass" id="clearcache">${icon("delete")}Clear cache</button></div>
    <div class="panel glass"><b>About</b><p class="muted small">Tunio for Windows. Audio from YouTube, SoundCloud and TikTok through yt-dlp ${esc(S.engine)}.
      Spotify links are read for song info only and matched to YouTube. Keyboard: Space = play/pause, Esc = back. Media keys work too.</p></div>`;
}

function bindScreen(r) {
  $("#screen").querySelectorAll("[data-tab]").forEach((b) => b.onclick = () => tab(b.dataset.tab));
  $("#back")?.addEventListener("click", back);
  if (r === "search") {
    const q = $("#q");
    q.oninput = () => { S.search.q = q.value; $("#clear").classList.toggle("hidden", !q.value); };
    q.onkeydown = (e) => { if (e.key === "Enter") runSearch(); };
    q.onpaste = () => setTimeout(() => { S.search.q = q.value; if (isLink(q.value)) runSearch(); });
    $("#clear").onclick = () => { q.value = S.search.q = ""; q.focus(); $("#clear").classList.add("hidden"); };
    document.querySelectorAll("[data-src]").forEach((b) => b.onclick = () => { S.search.source = b.dataset.src; render(true); if (S.search.q.trim() && !isLink(S.search.q)) runSearch(); });
    if (!S.search.q) q.focus();
  }
  if (r === "library") $("#newpl").onclick = () => nameDialog("New playlist", "", async (name) => { const p = await api("playlist/create", { name, tracks: [] }); await poll(); go("pl:" + p.id); });
  if (r === "settings") {
    $("#autoplay").onclick = async () => { await api("settings", { autoplay: !S.settings.autoplay }); S.settings.autoplay = !S.settings.autoplay; refresh(); };
    $("#clearcache").onclick = async () => { await api("cache/clear"); toast("Cache cleared"); };
    api("cache").then((c) => { const el = $("#cachesz"); if (el) el.textContent = `${(c.bytes / 1048576).toFixed(1)} MB of songs you've played, so replays are instant. The oldest are removed automatically above 1 GB.`; });
  }
  $("#openfolder")?.addEventListener("click", () => api("open-folder"));
  if (r === "liked" || r === "downloads" || r.startsWith("pl:")) {
    const tracks = LISTS._coll || [], pid = r.startsWith("pl:") ? r.slice(3) : null;
    $("#playc")?.addEventListener("click", () => Player.play(tracks, 0, false));
    $("#shuffle")?.addEventListener("click", () => Player.play(tracks, 0, true));
    $("#dlall")?.addEventListener("click", async () => { await api("download", { tracks }); toast(`Downloading ${tracks.filter((t) => !S.offline.has(t.id)).length} songs in the background`); poll(); });
    $("#rename")?.addEventListener("click", () => nameDialog("Rename playlist", S.lib.playlists.find((p) => p.id === pid)?.name || "", async (n) => { await api("playlist/rename", { id: pid, name: n }); poll(); }));
    $("#del")?.addEventListener("click", () => dialog(`<h3>Delete this playlist?</h3><p class="muted">Downloaded songs stay in Downloads.</p>
      <div class="end"><button class="link" data-close>Cancel</button><button class="link danger" id="yes">Delete</button></div>`, (m, close) => {
      $("#yes", m).onclick = async () => { close(); await api("playlist/delete", { id: pid }); S.stack = ["library"]; await poll(); render(true); }; }));
  }
}

// ------------------------------------------------------------------ jelly tab bar
let pillIndex = -1, edges = null, pillRaf = 0;
function renderTabs() {
  const nav = $("#tabs"), active = TABS.findIndex(([r]) => r === S.stack[0]);
  if (!nav.querySelector(".tab")) {
    nav.insertAdjacentHTML("beforeend", TABS.map(([r, label]) => `<button class="tab" data-t="${r}"><span class="ic"><span class="glow"></span><svg class="i" viewBox="0 0 24 24"><path/></svg></span>${label}</button>`).join(""));
    nav.querySelectorAll(".tab").forEach((b) => b.onclick = () => tab(b.dataset.t));
    new ResizeObserver(() => placePill(pillIndex, false)).observe(nav);
  }
  nav.querySelectorAll(".tab").forEach((b, i) => {
    const on = i === active, name = TABS[i][2];
    b.querySelector("path").setAttribute("d", ICONS[on ? name : name + "_outlined"]); // outlined when idle, filled when selected
    if (on && !b.classList.contains("on") && pillIndex !== -1) { b.classList.remove("flash"); void b.offsetWidth; b.classList.add("flash"); }
    b.classList.toggle("on", on);
  });
  if (active !== pillIndex) placePill(active, pillIndex !== -1);
}

/** Jelly pill: braces (squeezes), then its leading edge springs to the new tab while the trailing edge follows
 *  later and softer, so it stretches, overshoots and wobbles before settling (same springs as on Android). */
function placePill(i, animate) {
  if (i < 0) return;
  const nav = $("#tabs"), pill = $("#pill"), w = nav.clientWidth / TABS.length, pad = 6;
  const target = { l: i * w + pad, r: (i + 1) * w - pad };
  const apply = () => { pill.style.left = Math.min(edges.l, edges.r - 24) + "px"; pill.style.width = Math.max(24, edges.r - edges.l) + "px"; };
  const toRight = edges ? target.l > edges.l : true;
  pillIndex = i;
  if (!animate || !edges) { cancelAnimationFrame(pillRaf); edges = { ...target, vl: 0, vr: 0 }; apply(); return; }
  pill.animate([{ transform: "scale(1)" }, { transform: "scale(.86,.9)", offset: .18 }, { transform: "scale(1.04,.97)", offset: .5 }, { transform: "scale(1)" }],
    { duration: 560, easing: "ease-out" });
  const lead = toRight ? "r" : "l";
  const spring = { [lead]: { k: 380, z: 0.5, delay: 90 }, [lead === "r" ? "l" : "r"]: { k: 230, z: 0.38, delay: 160 } };
  const start = performance.now(); let last = start;
  cancelAnimationFrame(pillRaf);
  const step = (now) => {
    const dt = Math.min(0.032, (now - last) / 1000); last = now;
    for (const e of ["l", "r"]) {
      const { k, z, delay } = spring[e];
      if (now - start < delay) continue;
      const v = edges["v" + e], a = -k * (edges[e] - target[e]) - 2 * z * Math.sqrt(k) * v;
      edges["v" + e] = v + a * dt; edges[e] += edges["v" + e] * dt;
    }
    apply();
    const settled = ["l", "r"].every((e) => Math.abs(edges[e] - target[e]) < 0.3 && Math.abs(edges["v" + e]) < 4);
    if (settled) { edges = { ...target, vl: 0, vr: 0 }; apply(); } else pillRaf = requestAnimationFrame(step);
  };
  pillRaf = requestAnimationFrame(step);
}

// ------------------------------------------------------------------ player
const audio = $("#audio");
const Player = (() => {
  let queue = [], index = -1, original = null, shuffle = false, repeat = "off", preparing = false, loadToken = 0, autoplayBusy = false, failures = 0;
  const current = () => queue[index] || null;
  async function load(i, autoplayCheck = true) {
    index = i; const t = current(); if (!t) return;
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
  function play(tracks, start = 0, shuffled = shuffle) {
    if (!tracks.length) return; shuffle = shuffled;
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
  function toggle() { if (!current()) return; if (preparing) return; if (!audio.src) return load(index); audio.paused ? audio.play() : audio.pause(); }
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
  ["play", "pause", "playing"].forEach((ev) => audio.addEventListener(ev, updateUi));
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
    get queue() { return queue; }, get index() { return index; }, get shuffle() { return shuffle; }, get repeat() { return repeat; }, get preparing() { return preparing; } };
})();

function updateUi() {
  renderMini();
  if (!$("#player").classList.contains("hidden")) renderPlayer(false);
  document.querySelectorAll(".track").forEach((row) => { const t = LISTS[row.dataset.play]?.[+row.dataset.i]; row.classList.toggle("current", !!t && t.id === Player.current()?.id); });
}

function renderMini() {
  const t = Player.current(), m = $("#mini");
  m.classList.toggle("hidden", !t);
  $("#screen").style.paddingBottom = t ? "190px" : "120px";
  if (!t) return;
  const playing = !audio.paused;
  m.innerHTML = `${art(t.thumbnail)}<div class="meta"><div class="t">${esc(t.title)}</div><div class="a">${Player.preparing ? "Loading…" : esc(t.artist)}</div></div>
    ${Player.preparing ? `<span class="icon-btn"><span class="spinner"></span></span>` : `<button class="icon-btn plain" id="mp">${icon(playing ? "pause" : "play_arrow")}</button>`}
    <button class="icon-btn plain" id="mn">${icon("skip_next")}</button><div class="bar"><i></i></div>`;
  m.onclick = (e) => { if (e.target.closest("#mp")) return Player.toggle(); if (e.target.closest("#mn")) return Player.next(); openPlayer(); };
}

function openPlayer() { $("#player").classList.remove("hidden"); renderPlayer(true); }
function closePlayer() { $("#player").classList.add("hidden"); }
function renderPlayer(full) {
  const t = Player.current(), el = $("#player"); if (!t) return closePlayer();
  const liked = S.lib.liked.includes(t.id), off = S.offline.has(t.id), playing = !audio.paused, p = S.progress[t.id];
  const upNext = Player.queue.slice(Player.index + 1); LISTS.queue = upNext;
  const key = JSON.stringify([t.id, liked, off, playing, Player.preparing, p, Player.shuffle, Player.repeat, upNext.map((x) => x.id)]);
  if (!full && el.dataset.key === key) return; el.dataset.key = key;
  el.innerHTML = `<div class="cover-bg">${t.thumbnail ? `<img src="${esc(t.thumbnail)}" alt="" referrerpolicy="no-referrer">` : ""}</div><div class="inner">
    <div class="top"><button class="icon-btn glass lensed" id="pclose" title="Close">${icon("expand_more")}</button>
      <div class="from"><div class="k">PLAYING FROM</div><div class="v">${SOURCES[t.source]?.[0] || "Web"}</div></div>
      <button class="icon-btn glass lensed" data-menu="now" data-i="0" title="More">${icon("more_vert")}</button></div>
    <div class="cover-art">${t.thumbnail ? `<img src="${esc(t.thumbnail)}" alt="" referrerpolicy="no-referrer">` : ""}
      ${Player.preparing ? `<div class="wait"><span class="spinner" style="width:36px;height:36px;border-width:3px"></span><span>${p !== undefined && p >= 0 ? `Fetching audio ${Math.round(p * 100)}%` : "Finding audio…"}</span></div>` : ""}</div>
    <div class="info"><div class="meta"><div class="t">${esc(t.title)}</div><div class="a">${esc(t.artist)}</div></div>
      <button class="icon-btn glass lensed ${off ? "good" : ""}" id="pdl" title="${off ? "Downloaded" : "Download"}">${icon(off ? "download_done" : "download")}</button>
      <button class="icon-btn glass lensed ${liked ? "liked" : ""}" id="plike" title="Like">${icon(liked ? "favorite" : "favorite_border")}</button></div>
    <div class="slab glass lensed"><input class="seek" id="seek" type="range" min="0" max="1000" value="0">
      <div class="times"><span id="tcur">0:00</span><span id="tdur"></span></div>
      <div class="controls"><button class="icon-btn plain ${Player.shuffle ? "on" : ""}" id="pshuf" title="Shuffle">${icon("shuffle")}</button>
        <button class="icon-btn plain big" id="pprev" title="Previous">${icon("skip_previous")}</button>
        <button class="main" id="pplay" title="Play/pause">${Player.preparing ? `<span class="spinner" style="width:30px;height:30px;border-width:3px;border-top-color:#000"></span>` : icon(playing ? "pause" : "play_arrow")}</button>
        <button class="icon-btn plain big" id="pnext" title="Next">${icon("skip_next")}</button>
        <button class="icon-btn plain ${Player.repeat !== "off" ? "on" : ""}" id="prep" title="Repeat">${icon(Player.repeat === "one" ? "repeat_one" : "repeat")}</button></div></div>
    ${upNext.length ? `<h2 class="section">Up next</h2>${upNext.map((x, i) => `<div class="track" data-q="${i}">${art(x.thumbnail)}<div class="meta"><div class="t">${esc(x.title)}</div>
      <div class="s">${badge(x.source)}${offlineMark(x)}<span class="txt">${esc(x.artist)}</span></div></div><button class="icon-btn plain" data-rmq="${i}" title="Remove">${icon("close")}</button></div>`).join("")}` : ""}
  </div>`;
  LISTS.now = [t];
  $("#pclose").onclick = closePlayer;
  $("#pplay").onclick = Player.toggle; $("#pnext").onclick = () => Player.next(); $("#pprev").onclick = Player.prev;
  $("#pshuf").onclick = Player.toggleShuffle; $("#prep").onclick = Player.cycleRepeat;
  $("#plike").onclick = async () => { await api("like", { track: t }); poll(); };
  $("#pdl").onclick = async () => { if (off) await api("download/remove", { track: t }); else { await api("download", { tracks: [t] }); toast(`Downloading “${t.title}”`); } poll(); };
  el.querySelectorAll("[data-q]").forEach((r) => r.onclick = (e) => { if (e.target.closest("[data-rmq]")) return Player.remove(Player.index + 1 + +e.target.closest("[data-rmq]").dataset.rmq); Player.jump(Player.index + 1 + +r.dataset.q); });
  const seek = $("#seek");
  seek.oninput = () => { seeking = true; $("#tcur").textContent = fmt(seek.value / 1000 * (audio.duration || 0)) || "0:00"; };
  seek.onchange = () => { if (audio.duration) audio.currentTime = seek.value / 1000 * audio.duration; seeking = false; };
  tickProgress();
}
let seeking = false;
function tickProgress() {
  const d = audio.duration || Player.current()?.durationSec || 0, c = audio.currentTime || 0, frac = d ? Math.min(1, c / d) : 0;
  const bar = $("#mini .bar i"); if (bar) bar.style.width = (frac * 100) + "%";
  const seek = $("#seek");
  if (seek && !seeking) { seek.value = Math.round(frac * 1000); $("#tcur").textContent = fmt(c) || "0:00"; $("#tdur").textContent = fmt(d); }
}
audio.addEventListener("timeupdate", tickProgress);

// ------------------------------------------------------------------ start
render(true);
poll();
