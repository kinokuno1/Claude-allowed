"""Look of the home server's web pages (status page, login, phone pairing, Jam links).

Two themes, both flat (no gradients) and 60-30-10:
  - "tunio": the apps' colours: near-black background (60), dark cards (30), white buttons + one soft accent (10).
  - "dots": black, white and red, dot-matrix titles, monospaced labels and a faint dot grid.
The choice is remembered in the browser (localStorage), so every page on the server uses it.
"""

HEAD = """<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Doto:wght@700;900&family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
<script>try{document.documentElement.dataset.theme=localStorage.getItem('tunio.theme')||'tunio'}catch(e){document.documentElement.dataset.theme='tunio'}</script>
<style>
:root{color-scheme:dark;--bg:#0E0E12;--card:#1A1823;--line:rgba(255,255,255,.08);--text:#F7F7FA;--muted:#B5B3C2;--accent:#C9B6FF;
--btn:#fff;--btn-text:#0E0E12;--r:16px;--head:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;--label:system-ui,-apple-system,"Segoe UI",sans-serif}
[data-theme=dots]{--bg:#000;--card:#0D0D0D;--line:#262626;--text:#fff;--muted:#8C8C8C;--accent:#E3211B;--btn:#fff;--btn-text:#000;--r:6px;
--head:"Doto",ui-monospace,"Cascadia Mono",monospace;--label:"Space Mono",ui-monospace,"Cascadia Mono",monospace}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
[data-theme=dots] body{background-image:radial-gradient(#1A1A1A 1.1px,transparent 1.3px);background-size:16px 16px}
main{max-width:680px;margin:0 auto;padding:32px 16px 56px}
header{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
h1{margin:0;font:900 32px/1.1 var(--head);letter-spacing:-.01em}
[data-theme=dots] h1{text-transform:uppercase;letter-spacing:.06em;font-size:30px}
.sub{color:var(--muted);font-size:14px;margin:8px 0 0}
.label{margin:28px 4px 8px;color:var(--muted);font:700 11px/1 var(--label);letter-spacing:.14em;text-transform:uppercase}
.muted{color:var(--muted);font-size:14px}
.card{background:var(--card);border:1px solid var(--line);border-radius:var(--r);padding:16px}
.card > :first-child{margin-top:0}.card > :last-child{margin-bottom:0}
.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
@media (max-width:520px){.stats{grid-template-columns:1fr 1fr}.stats .card:nth-child(3){grid-column:span 2}}
.big{font:900 30px/1.1 var(--head);margin-bottom:4px}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
input,textarea{width:100%;background:var(--bg);color:var(--text);border:1px solid var(--line);border-radius:calc(var(--r) - 4px);
padding:12px;font:inherit;transition:border-color .15s}
textarea{min-height:110px;font:13px/1.5 var(--label)}
input:focus,textarea:focus{outline:none;border-color:var(--text)}
button,.btn{display:inline-block;background:var(--btn);color:var(--btn-text);border:0;border-radius:999px;padding:12px 22px;
font:700 15px/1.2 var(--label);text-decoration:none;cursor:pointer;transition:transform .12s,opacity .12s}
[data-theme=dots] button,[data-theme=dots] .btn{text-transform:uppercase;letter-spacing:.08em;font-size:13px}
button:active,.btn:active{transform:scale(.96)}
.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}
.ghost:hover{border-color:var(--muted)}
code{font:13px var(--label);background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:2px 6px;display:inline-block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:bottom}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--accent);flex:none}
.live .dot{animation:blink 1s steps(2,start) infinite}
@keyframes blink{to{visibility:hidden}}
.err{color:var(--accent);font-size:14px}
ul.list{list-style:none;margin:0;padding:0}
ul.list li{display:flex;gap:10px;align-items:baseline;padding:10px 0;border-top:1px solid var(--line);font-size:14px}
ul.list li:first-child{border-top:0;padding-top:0}ul.list li:last-child{padding-bottom:0}
.tag{color:var(--accent);border:1px solid currentColor;border-radius:999px;padding:0 7px;font:700 10px/1.6 var(--label);
letter-spacing:.1em;text-transform:uppercase;white-space:nowrap}
.themes{margin-left:auto;display:flex;gap:2px;border:1px solid var(--line);border-radius:999px;padding:3px}
.themes button{background:transparent;color:var(--muted);padding:6px 12px;font-size:12px}
.themes button.on{background:var(--text);color:var(--bg)}
</style>"""

THEMES = """<div class="themes" role="group" aria-label="Theme"><button type="button" data-t="tunio">Tunio</button>
<button type="button" data-t="dots">Dots</button></div>
<script>(function(){var b=document.querySelectorAll('.themes button');function on(){var t=document.documentElement.dataset.theme;
b.forEach(function(x){x.classList.toggle('on',x.dataset.t===t)})}b.forEach(function(x){x.onclick=function(){
document.documentElement.dataset.theme=x.dataset.t;try{localStorage.setItem('tunio.theme',x.dataset.t)}catch(e){}on()}});on()})();</script>"""
