import sys, os, html
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import layout as L
sols = L.best(300); _, _, seed, J = sols[0]
assert L.verify(J)
P = 30; OX, OY = 140, 70                     # pitch px, board origin
W, H = L.COLS * P, L.ROWS * P
def xy(r, c): return OX + (c - 0.5) * P, OY + (r + 0.5) * P
COL = {'VCC': '#d62728', 'GND': '#222222', 'S0': '#1f77b4', 'S1': '#17becf', 'S2': '#2ca02c', 'S3': '#9467bd',
       'M': '#ff7f0e', 'N': '#8c564b', 'Q': '#e377c2', 'R': '#bcbd22', 'P0': '#7f7f7f', 'P1': '#7f7f7f', 'P2': '#7f7f7f', 'P3': '#7f7f7f'}
NAME = {'M': 'U1-3 (sortie U1A)', 'N': 'U1-4 (sortie U1B)', 'Q': 'U2-4 (sortie U2B)', 'R': 'U2-3 (sortie U2A)'}
o = []
o.append('<rect x="%d" y="%d" width="%d" height="%d" rx="6" fill="#f3e3a6" stroke="#b9a35a"/>' % (OX - 12, OY - 12, W + 24, H + 24))
for r in range(L.ROWS):
    x0, y = xy(r, 1); o.append('<rect x="%g" y="%g" width="%g" height="%g" fill="#d9895b"/>' % (OX + 2, y - P * 0.38, W - 4, P * 0.76))
    o.append('<text x="%g" y="%g" font-size="11" fill="#9a8a50" text-anchor="end" dominant-baseline="middle">%s</text>' % (OX - 16, y, L.RL[r]))
for c in range(1, L.COLS + 1):
    x, _ = xy(0, c); o.append('<text x="%g" y="%g" font-size="10" fill="#9a8a50" text-anchor="middle">%d</text>' % (x, OY - 18, c))
for r in range(L.ROWS):
    for c in range(1, L.COLS + 1):
        x, y = xy(r, c)
        if (r, c) in L.cuts:
            o.append('<rect x="%g" y="%g" width="%g" height="%g" fill="#f3e3a6"/>' % (x - P * 0.42, y - P * 0.4, P * 0.84, P * 0.8))
            o.append('<path d="M%g %gL%g %gM%g %gL%g %g" stroke="#c00" stroke-width="2"/>' % (x - 6, y - 6, x + 6, y + 6, x - 6, y + 6, x + 6, y - 6))
        else:
            o.append('<circle cx="%g" cy="%g" r="3.2" fill="#fff8e6" stroke="#8a5a3a" stroke-width="0.8"/>' % (x, y))
# ICs
for u, (lc, rc) in L.IC.items():
    x1, y1 = xy(L.IC_TOP, lc); x2, y2 = xy(L.IC_TOP + 6, rc)
    o.append('<rect x="%g" y="%g" width="%g" height="%g" rx="3" fill="#3a3a3a" fill-opacity="0.9" stroke="#111"/>' % (x1 - 9, y1 - 13, x2 - x1 + 18, y2 - y1 + 26))
    o.append('<path d="M%g %g a8 8 0 0 0 16 0" fill="#f3e3a6" stroke="#111"/>' % ((x1 + x2) / 2 - 8, y1 - 13))
    o.append('<text x="%g" y="%g" font-size="14" font-weight="bold" fill="#fff" text-anchor="middle" transform="rotate(-90 %g %g)">%s  CD4011</text>' % ((x1 + x2) / 2, (y1 + y2) / 2 + 5, (x1 + x2) / 2, (y1 + y2) / 2, u))
    for n in range(1, 15):
        r, c = L.pin_hole(u, n); x, y = xy(r, c)
        o.append('<rect x="%g" y="%g" width="9" height="9" fill="#d8d8d8" stroke="#555"/>' % (x - 4.5, y - 4.5))
        dx = 10 if c == lc else -10
        o.append('<text x="%g" y="%g" font-size="9" fill="#fff" text-anchor="%s" dominant-baseline="middle">%d</text>' % (x + dx, y, 'start' if dx > 0 else 'end', n))
# headers (right edge) and lugs
for grp in (['+', 'P0', 'P1', 'P2', 'P3'], ['GF', 'S0', 'S1', 'S2', 'S3', '-']):
    rs = [L.R[L.header[g]] for g in grp]; x, y1 = xy(min(rs), L.HCOL); _, y2 = xy(max(rs), L.HCOL)
    o.append('<rect x="%g" y="%g" width="%g" height="%g" fill="#222" rx="2"/>' % (x - 11, y1 - 13, 22, y2 - y1 + 26))
    for g in grp:
        _, y = xy(L.R[L.header[g]], L.HCOL)
        o.append('<rect x="%g" y="%g" width="8" height="8" fill="#e6c34a"/>' % (x - 4, y - 4))
        o.append('<text x="%g" y="%g" font-size="15" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>' % (OX + W + 22, y, '#d00' if g == '+' else '#000', g))
for name, (r, c) in L.terminals_lug.items():
    x, y = xy(r, c)
    o.append('<circle cx="%g" cy="%g" r="11" fill="#c9c9c9" stroke="#555" stroke-width="2"/><circle cx="%g" cy="%g" r="4" fill="#777"/>' % (x, y, x, y))
    o.append('<text x="%g" y="%g" font-size="13" font-weight="bold" fill="%s" text-anchor="end" dominant-baseline="middle">%s</text>' % (OX - 30, y, '#d00' if name == 'Vcc' else '#000', 'cosse ' + name))
# jumpers
for net, c, r1, r2 in sorted(J, key=lambda j: -abs(j[2] - j[3])):
    x, ya = xy(r1, c); _, yb = xy(r2, c)
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="#000" stroke-opacity="0.35" stroke-width="6.5" stroke-linecap="round"/>' % (x, ya, x, yb))
    o.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="4.5" stroke-linecap="round"/>' % (x, ya, x, yb, COL[net]))
    for yy in (ya, yb): o.append('<circle cx="%g" cy="%g" r="4" fill="#bbb" stroke="#444"/>' % (x, yy))
# legend
LX = OX + W + 70; ly = OY
o.append('<text x="%d" y="%d" font-size="17" font-weight="bold">Veroboard 25 x 38 - 2 x CD4011</text>' % (LX, ly)); ly += 28
o.append('<text x="%d" y="%d" font-size="13">Pistes horizontales, lignes A..Y, colonnes 1..38</text>' % (LX, ly)); ly += 22
o.append('<text x="%d" y="%d" font-size="13"><tspan fill="#c00" font-weight="bold">X</tspan> = coupures : lignes H a N, colonnes 10, 17, 24, 31</text>' % (LX, ly)); ly += 22
o.append('<text x="%d" y="%d" font-size="13">U1 : broche 1 en H9 ; U2 : broche 1 en H23 (encoche en haut)</text>' % (LX, ly)); ly += 22
o.append('<text x="%d" y="%d" font-size="13">Ligne D = +Vcc, ligne O = Gnd</text>' % (LX, ly)); ly += 30
o.append('<text x="%d" y="%d" font-size="15" font-weight="bold">Straps (%d fils) :</text>' % (LX, ly, len(J))); ly += 22
order = ['VCC', 'GND', 'S0', 'S1', 'S2', 'S3', 'M', 'N', 'Q', 'R', 'P0', 'P1', 'P2', 'P3']
for net in order:
    js = [j for j in J if j[0] == net]
    s = ', '.join('%s%d-%s%d' % (L.RL[min(a, b)], c, L.RL[max(a, b)], c) for _, c, a, b in sorted(js, key=lambda j: j[1]))
    o.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="5" stroke-linecap="round"/>' % (LX, ly - 4, LX + 22, ly - 4, COL[net]))
    o.append('<text x="%d" y="%d" font-size="12.5"><tspan font-weight="bold">%s</tspan> : %s</text>' % (LX + 32, ly, NAME.get(net, net), s)); ly += 21
TW = LX + 560; TH = max(OY + H + 60, ly + 20)
svg = '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" font-family="Arial"><rect width="100%%" height="100%%" fill="#fff"/>%s</svg>' % (TW, TH, TW, TH, '\n'.join(o))
open(sys.argv[1], 'w').write(svg); print(TW, TH, 'seed', seed, 'jumpers', len(J))
