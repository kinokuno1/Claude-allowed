"""Stripboard drawing with x/y coordinates for every part (x = column 1..38, y = row 1..25)."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import layout as L
_, _, seed, J = L.best(300)[0]
assert L.verify(J)
P = 34; OX, OY = 200, 130
PITCH = 2.54
BW, BH = 38 * PITCH, 25 * PITCH
mmL = lambda c: (c - 0.5) * PITCH
mmR = lambda c: BW - mmL(c)
mmT = lambda n: (n - 0.5) * PITCH
mmB = lambda n: BH - mmT(n)
f = lambda v: ('%.1f' % v).replace('.', ',')
W, H = L.COLS * P, L.ROWS * P
def xy(r, c): return OX + (c - 0.5) * P, OY + (r + 0.5) * P   # r: 0-based row index
COL = {'VCC': '#d62728', 'GND': '#222222', 'S0': '#1f77b4', 'S1': '#17becf', 'S2': '#2ca02c', 'S3': '#9467bd',
       'M': '#ff7f0e', 'N': '#8c564b', 'Q': '#e377c2', 'R': '#bcbd22', 'P0': '#7f7f7f', 'P1': '#7f7f7f', 'P2': '#7f7f7f', 'P3': '#7f7f7f'}
NAME = {'VCC': '+Vcc', 'GND': 'Gnd', 'M': 'U1-3 out', 'N': 'U1-4 out', 'Q': 'U2-4 out', 'R': 'U2-3 out'}
o = []
T = lambda x, y, s, size=12, **k: o.append('<text x="%g" y="%g" font-size="%g" %s>%s</text>' % (x, y, size, ' '.join('%s="%s"' % (a.replace('_', '-'), b) for a, b in k.items()), s))
o.append('<rect x="%d" y="%d" width="%d" height="%d" fill="#f3e3a6" stroke="#000" stroke-width="2.5"/>' % (OX, OY, W, H))
# mm rulers
sc = P / PITCH
for mm in range(0, 97):
    xx = OX + mm * sc; L5 = 12 if mm % 10 == 0 else (8 if mm % 5 == 0 else 4)
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#000" stroke-width="0.8"/>' % (xx, OY - 46, xx, OY - 46 - L5))
    if mm % 10 == 0: T(xx, OY - 62, mm, 12, text_anchor='middle')
o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#000"/>' % (OX, OY - 46, OX + BW * sc, OY - 46))
T(OX + W + 6, OY - 58, 'mm', 12)
for mm in range(0, 64):
    yy = OY + mm * sc; L5 = 12 if mm % 10 == 0 else (8 if mm % 5 == 0 else 4)
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#000" stroke-width="0.8"/>' % (OX - 50, yy, OX - 50 - L5, yy))
    if mm % 10 == 0: T(OX - 66, yy, mm, 12, text_anchor='end', dominant_baseline='middle')
o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#000"/>' % (OX - 50, OY, OX - 50, OY + BH * sc))
T(OX - 66, OY + H + 18, 'mm', 12, text_anchor='end')
for r in range(L.ROWS):
    _, y = xy(r, 1)
    o.append('<rect x="%g" y="%g" width="%g" height="%g" fill="#d9895b"/>' % (OX + 2, y - P * 0.36, W - 4, P * 0.72))
    T(OX - 8, y, r + 1, 10, fill='#555', text_anchor='end', dominant_baseline='middle', font_weight='bold')
for c in range(1, L.COLS + 1):
    x, _ = xy(0, c); T(x, OY - 8, c, 10, fill='#555', text_anchor='middle', font_weight='bold')
T(OX + W / 2, OY - 92, 'Plaque 96,5 x 63,5 mm (38 x 25 trous, pas 2,54 mm) - cotes au centre des trous', 16, text_anchor='middle', font_weight='bold')

for r in range(L.ROWS):
    for c in range(1, L.COLS + 1):
        x, y = xy(r, c)
        if (r, c) in L.cuts:
            o.append('<rect x="%g" y="%g" width="%g" height="%g" fill="#f3e3a6"/>' % (x - P * 0.42, y - P * 0.38, P * 0.84, P * 0.76))
            o.append('<path d="M%g %gL%g %gM%g %gL%g %g" stroke="#c00" stroke-width="2.5"/>' % (x - 7, y - 7, x + 7, y + 7, x - 7, y + 7, x + 7, y - 7))
        else:
            o.append('<circle cx="%g" cy="%g" r="3.4" fill="#fff8e6" stroke="#8a5a3a" stroke-width="0.8"/>' % (x, y))
# ICs
for i, (u, (lc, rc)) in enumerate(L.IC.items(), start=1):
    x1, y1 = xy(L.IC_TOP, lc); x2, y2 = xy(L.IC_TOP + 6, rc)
    o.append('<rect x="%g" y="%g" width="%g" height="%g" rx="3" fill="#3a3a3a" fill-opacity="0.88" stroke="#111"/>' % (x1 - 10, y1 - 14, x2 - x1 + 20, y2 - y1 + 28))
    o.append('<path d="M%g %g a9 9 0 0 0 18 0" fill="#f3e3a6" stroke="#111"/>' % ((x1 + x2) / 2 - 9, y1 - 14))
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
    T(cx, cy + 5, 'IC%d  CD4011' % i, 14, fill='#fff', text_anchor='middle', font_weight='bold', transform='rotate(-90 %g %g)' % (cx, cy))
    for n in range(1, 15):
        r, c = L.pin_hole(u, n); x, y = xy(r, c)
        o.append('<rect x="%g" y="%g" width="10" height="10" fill="#d8d8d8" stroke="#555"/>' % (x - 5, y - 5))
        dx = 11 if c == lc else -11
        T(x + dx, y, n, 10, fill='#fff', text_anchor='start' if dx > 0 else 'end', dominant_baseline='middle')
    # coordinate callout
    px1, py1 = xy(L.IC_TOP, lc)
    dc = '#0050c8'; hy = py1 - P * (0.62 if i == 1 else 1.12)
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="1.6" stroke-dasharray="5 3" marker-start="url(#a)" marker-end="url(#a)"/>' % (OX + 1, hy, px1, hy, dc))
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="1.2"/>' % (px1, hy, px1, py1, dc))
    lx = xy(0, 2.4)[0] if i == 1 else xy(0, 20)[0]
    T(lx, hy - 5, 'IC%d : %s mm' % (i, f(mmL(lc))), 12.5, text_anchor='middle', fill=dc, font_weight='bold', paint_order='stroke', stroke='#fff', stroke_width='4')
    vx = px1 - 14
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="1.6" stroke-dasharray="5 3" marker-start="url(#a)" marker-end="url(#a)"/>' % (vx, OY + 1, vx, py1, dc))
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="1.2"/>' % (vx - 4, py1, px1, py1, dc))
    T(vx - 5, OY + (py1 - OY) * 0.3, '%s mm' % f(mmT(L.IC_TOP + 1)), 12.5, text_anchor='end', fill=dc, font_weight='bold', paint_order='stroke', stroke='#fff', stroke_width='4')
# headers
for grp in (['+', 'P0', 'P1', 'P2', 'P3'], ['GF', 'S0', 'S1', 'S2', 'S3', '-']):
    rs = [L.R[L.header[g]] for g in grp]; x, ya = xy(min(rs), L.HCOL); _, yb = xy(max(rs), L.HCOL)
    o.append('<rect x="%g" y="%g" width="24" height="%g" fill="#222" rx="2"/>' % (x - 12, ya - 14, yb - ya + 28))
    for g in grp:
        r = L.R[L.header[g]]; _, y = xy(r, L.HCOL)
        o.append('<rect x="%g" y="%g" width="9" height="9" fill="#e6c34a"/>' % (x - 4.5, y - 4.5))
        T(OX + W + 14, y, '%s  (%s mm du haut)' % ('&#8722;' if g == '-' else g, f(mmT(r + 1))), 13, dominant_baseline='middle', font_weight='bold', fill='#d00' if g == '+' else '#000')
# lugs
for name, (r, c) in L.terminals_lug.items():
    x, y = xy(r, c)
    o.append('<circle cx="%g" cy="%g" r="12" fill="#c9c9c9" stroke="#555" stroke-width="2"/><circle cx="%g" cy="%g" r="4" fill="#777"/>' % (x, y, x, y))
    T(x + 16, y + 1, name, 13, dominant_baseline='middle', font_weight='bold', fill='#d00' if name == 'Vcc' else '#000', paint_order='stroke', stroke='#fff', stroke_width='4')
# jumpers, numbered left->right, top->bottom
JJ = sorted(((c, min(a, b), max(a, b), net) for net, c, a, b in J))
for i, (c, a, b, net) in enumerate(JJ, start=1):
    x, ya = xy(a, c); _, yb = xy(b, c)
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#000" stroke-opacity="0.35" stroke-width="7" stroke-linecap="round"/>' % (x, ya, x, yb))
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="5" stroke-linecap="round"/>' % (x, ya, x, yb, COL[net]))
    for yy in (ya, yb): o.append('<circle cx="%g" cy="%g" r="4.5" fill="#bbb" stroke="#444"/>' % (x, yy))
    my = (ya + yb) / 2 if b - a > 1 else ya + P / 2
    o.append('<circle cx="%g" cy="%g" r="11" fill="#fff" stroke="%s" stroke-width="2.5"/>' % (x, my, COL[net]))
    T(x, my + 0.5, i, 11, text_anchor='middle', dominant_baseline='middle', font_weight='bold')
# table
LX = OX + W + 190; ly = OY - 30
T(LX, ly, 'Straps : distance de chaque bord (mm)', 17, font_weight='bold'); ly += 30
o.append('<text x="%d" y="%d" font-size="13" font-weight="bold"><tspan x="%d">N&#176;</tspan><tspan x="%d">gauche</tspan><tspan x="%d">droite</tspan><tspan x="%d">haut*</tspan><tspan x="%d">bas*</tspan><tspan x="%d">signal</tspan></text>' % (LX, ly, LX, LX + 40, LX + 105, LX + 170, LX + 230, LX + 320))
ly += 8; o.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#999"/>' % (LX, ly, LX + 420, ly)); ly += 20
for i, (c, a, b, net) in enumerate(JJ, start=1):
    if i % 2 == 0: o.append('<rect x="%d" y="%d" width="420" height="21" fill="#f2f2f2"/>' % (LX - 4, ly - 15))
    o.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="5"/>' % (LX + 290, ly - 4, LX + 312, ly - 4, COL[net]))
    o.append('<text x="%d" y="%d" font-size="13.5"><tspan x="%d" font-weight="bold">%d</tspan><tspan x="%d">%s</tspan><tspan x="%d">%s</tspan><tspan x="%d">%s</tspan><tspan x="%d">%s</tspan><tspan x="%d">%s</tspan></text>' % (LX, ly, LX, i, LX + 40, f(mmL(c)), LX + 105, f(mmR(c)), LX + 170, f(mmT(a + 1)), LX + 230, f(mmB(b + 1)), LX + 320, NAME.get(net, net)))
    ly += 21
ly += 4
T(LX, ly, '* haut = bout du haut depuis le bord haut ; bas = bout du bas depuis le bord bas', 11.5, fill='#444'); ly += 26
T(LX, ly, 'Composants (mm, centre des trous) :', 15, font_weight='bold'); ly += 22
for i, (u, (lc, rc)) in enumerate(L.IC.items(), start=1):
    T(LX + 10, ly, 'IC%d broche 1 : gauche %s, droite %s, haut %s, bas %s' % (i, f(mmL(lc)), f(mmR(lc)), f(mmT(L.IC_TOP + 1)), f(mmB(L.IC_TOP + 1))), 13.5); ly += 20
    T(LX + 24, ly, '(encoche en haut ; broches espacees de 2,54 et 7,62 mm entre rangees)', 12, fill='#444'); ly += 20
T(LX + 10, ly, 'Barrettes : droite %s, gauche %s' % (f(mmR(37)), f(mmL(37))), 13.5); ly += 20
T(LX + 24, ly, '5 pins (+..P3) : haut %s a %s ; 6 pins (GF..-) : haut %s a %s' % (f(mmT(4)), f(mmT(8)), f(mmT(10)), f(mmT(15))), 13); ly += 20
T(LX + 10, ly, 'Cosse Vcc : gauche %s, haut %s, bas %s' % (f(mmL(1)), f(mmT(4)), f(mmB(4))), 13.5); ly += 20
T(LX + 10, ly, 'Cosse Gnd : gauche %s, haut %s, bas %s' % (f(mmL(1)), f(mmT(15)), f(mmB(15))), 13.5); ly += 28
T(LX, ly, 'Coupures (X rouge) :', 15, font_weight='bold'); ly += 22
from collections import defaultdict
cc = defaultdict(list)
for r, c in sorted(L.cuts): cc[c].append(r + 1)
for c in sorted(cc):
    T(LX + 10, ly, '%s mm de gauche : de %s a %s mm du haut' % (f(mmL(c)), f(mmT(min(cc[c]))), f(mmT(max(cc[c])))), 13.5); ly += 20
TW = LX + 470; TH = max(OY + H + 90, ly + 20)
svg = '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" font-family="Arial"><defs><marker id="a" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#0050c8"/></marker></defs><rect width="100%%" height="100%%" fill="#fff"/>%s</svg>' % (TW, TH, TW, TH, '\n'.join(o))
open(sys.argv[1], 'w').write(svg); print(TW, TH, len(JJ))
