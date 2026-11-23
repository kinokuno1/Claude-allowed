// Tunio mini player. Shows what the main window is playing (through the Python side) and sends its buttons back.
import { ICONS } from "./icons.js";

const $ = (s) => document.querySelector(s);
const fmt = (s) => { s = Math.max(0, Math.floor(s || 0)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
const api = () => window.pywebview?.api;
let st = {}, at = performance.now();

$("#like").innerHTML = `<svg class="heart" viewBox="0 0 24 24"><path class="hf" d="${ICONS.favorite}"/><path class="ho" d="${ICONS.favorite_border}"/></svg>`;

async function poll() {
  const a = api(); if (!a?.now) return;
  try { st = (await a.now()) || {}; at = performance.now(); show(); } catch { /* main window busy */ }
}
setInterval(poll, 350);
window.addEventListener("pywebviewready", poll);

function show() {
  const has = !!st.id;
  $("#mini").classList.toggle("empty", !has);
  $("#title").textContent = has ? st.title : "Nothing playing";
  $("#artist").textContent = has ? (st.loading ? "Loading…" : st.artist) : "";
  for (const img of [$("#cimg"), $("#bgimg")]) {
    if (st.cover && img.getAttribute("src") !== st.cover) img.src = st.cover;
    if (!st.cover) img.removeAttribute("src");
  }
  $("#play .ppi").classList.toggle("pause", !!st.playing);
  $("#like .heart").classList.toggle("on", !!st.liked);
  tick();
}
function tick() {
  const d = st.duration || 0, t = Math.min(d || Infinity, (st.time || 0) + (st.playing ? (performance.now() - at) / 1000 : 0));
  $("#fill").style.width = d ? `${Math.min(100, t / d * 100)}%` : "0";
  $("#tc").textContent = fmt(t); $("#td").textContent = fmt(d);
}
(function loop() { tick(); requestAnimationFrame(loop); })();

const cmd = (name, arg) => { api()?.cmd(name, arg); setTimeout(poll, 120); };
$("#play").onclick = () => { st.playing = !st.playing; show(); cmd("toggle"); };
$("#next").onclick = () => cmd("next");
$("#prev").onclick = () => cmd("prev");
$("#like").onclick = () => { st.liked = !st.liked; show(); cmd("like"); };
$("#bar").onclick = (e) => { const r = e.currentTarget.getBoundingClientRect(); cmd("seek", (e.clientX - r.left) / r.width * (st.duration || 0)); };
$("#expand").onclick = () => api()?.expand();
$("#close").onclick = () => api()?.quit();
$("#cover").ondblclick = () => api()?.expand();

// move the window by dragging any empty part; resize by the edges (same as the main window)
const dpr = () => devicePixelRatio || 1;
function track(el, e, onDelta) {
  el.setPointerCapture(e.pointerId);
  const x0 = e.screenX, y0 = e.screenY; let last = e, busy = false, dirty = false, done = false;
  const pump = async () => {
    if (busy) { dirty = true; return; }
    busy = true;
    try { do { dirty = false; await onDelta((last.screenX - x0) * dpr(), (last.screenY - y0) * dpr()); } while (dirty && !done); }
    finally { busy = false; }
  };
  const move = (ev) => { last = ev; pump(); };
  const up = () => { done = true; el.removeEventListener("pointermove", move); el.removeEventListener("pointerup", up); };
  el.addEventListener("pointermove", move); el.addEventListener("pointerup", up);
}
$("#mini").addEventListener("pointerdown", async (e) => {
  if (e.button !== 0 || e.target.closest("button, .bar") || !api()?.state) return;
  const r = (await api().state()).rect;
  track($("#mini"), e, (dx, dy) => api().set_rect(r[0] + dx, r[1] + dy, r[2], r[3]));
});
$("#edges").addEventListener("pointerdown", async (e) => {
  const dir = e.target.dataset?.e; if (!dir || !api()?.state) return;
  e.preventDefault();
  const r0 = (await api().state()).rect, minW = 220 * dpr(), minH = 80 * dpr();
  track(e.target, e, (dx, dy) => {
    let [x, y, w, h] = r0;
    if (dir.includes("e")) w = Math.max(minW, r0[2] + dx);
    if (dir.includes("s")) h = Math.max(minH, r0[3] + dy);
    if (dir.includes("w")) { w = Math.max(minW, r0[2] - dx); x = r0[0] + r0[2] - w; }
    if (dir.includes("n")) { h = Math.max(minH, r0[3] - dy); y = r0[1] + r0[3] - h; }
    return api().set_rect(Math.round(x), Math.round(y), Math.round(w), Math.round(h));
  });
});
