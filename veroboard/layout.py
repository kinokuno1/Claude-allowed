"""Stripboard (veroboard) layout 25 rows x 38 cols, horizontal strips, for the 2x CD4011 circuit."""
import itertools, sys
ROWS, COLS = 25, 38
R = {c: i for i, c in enumerate('ABCDEFGHIJKLMNOPQRSTUVWXY')}
RL = 'ABCDEFGHIJKLMNOPQRSTUVWXY'
# ---- placement ----
IC = {'U1': (9, 12), 'U2': (23, 26)}          # left pin column, right pin column ; pin1 at row H
IC_TOP = R['H']
def pin_hole(u, n):
    l, r = IC[u]
    return (IC_TOP + n - 1, l) if n <= 7 else (IC_TOP + 14 - n, r)
IC_ROWS = range(R['H'], R['N'] + 1)
CUT_COLS = [10, 17, 24, 31]                    # cuts in every IC row
cuts = {(r, c) for r in IC_ROWS for c in CUT_COLS}
HCOL = 37
header = {'+': 'D', 'P0': 'E', 'P1': 'F', 'P2': 'G', 'P3': 'H', 'GF': 'J', 'S0': 'K', 'S1': 'L', 'S2': 'M', 'S3': 'N', '-': 'O'}
terminals_lug = {'Vcc': (R['D'], 1), 'Gnd': (R['O'], 1)}
# ---- netlist (from the schematic) ----
NETS = {
 'VCC': [('U1', 14), ('U2', 14), ('H', '+'), ('T', 'Vcc')],
 'GND': [('U1', 7), ('U2', 7), ('H', '-'), ('T', 'Gnd')],
 'S0': [('U1', 1), ('U1', 5), ('U2', 9), ('H', 'S0')],
 'S1': [('U1', 6), ('H', 'S1')],
 'S2': [('U1', 2), ('U2', 5), ('H', 'S2')],
 'S3': [('U2', 6), ('H', 'S3')],
 'M': [('U1', 3), ('U1', 13), ('U2', 1), ('U2', 12)],
 'N': [('U1', 4), ('U1', 12), ('U2', 2), ('U2', 13)],
 'Q': [('U2', 4), ('U1', 8), ('U1', 9)],
 'R': [('U2', 3), ('U2', 8)],
 'P0': [('U2', 10), ('H', 'P0')], 'P1': [('U1', 10), ('H', 'P1')],
 'P2': [('U2', 11), ('H', 'P2')], 'P3': [('U1', 11), ('H', 'P3')],
}
def term_hole(t):
    if t[0] == 'H': return (R[header[t[1]]], HCOL)
    if t[0] == 'T': return terminals_lug[t[1]]
    return pin_hole(*t)
occupied = {term_hole(t) for ts in NETS.values() for t in ts}
def segment(hole):
    r, c = hole
    cs = sorted([x for (rr, x) in cuts if rr == r])
    lo = max([x for x in cs if x < c], default=0) + 1
    hi = min([x for x in cs if x > c], default=COLS + 1) - 1
    return (r, lo, hi)
FIXED_ROW = {'VCC': R['D'], 'GND': R['O'], 'P0': R['E'], 'P1': R['F'], 'P2': R['G']}
BUS_ROWS = [R[x] for x in 'ABC'] + [R[x] for x in 'PQRSTUVWXY']
def forbidden_col(c, r1, r2):
    lo, hi = min(r1, r2), max(r1, r2)
    for (l, rr) in IC.values():
        if l <= c <= rr and not (hi < IC_ROWS[0] or lo > IC_ROWS[-1]): return True
    if c >= HCOL - 1 and not (hi < R['D'] or lo > R['O']): return True   # keep header area clear
    if c <= 2 and lo <= R['O'] and hi >= R['D']: return True           # keep lug area clear
    return False

MAXI = 0
def solve(seed=0, cap=20000):
    import random
    rnd = random.Random(seed)
    jumpers = []; used = set(occupied) | set(cuts); colspans = {}; nodes = [0]
    def can(c, r1, r2):
        if (r1, c) in used or (r2, c) in used or forbidden_col(c, r1, r2): return False
        lo, hi = min(r1, r2), max(r1, r2)
        return all(hi < a or lo > b for a, b in colspans.get(c, []))
    def place(c, r1, r2, net):
        used.add((r1, c)); used.add((r2, c)); colspans.setdefault(c, []).append((min(r1, r2), max(r1, r2))); jumpers.append((net, c, r1, r2))
    def unplace():
        net, c, r1, r2 = jumpers.pop(); used.discard((r1, c)); used.discard((r2, c)); colspans[c].remove((min(r1, r2), max(r1, r2)))
    order = ['VCC', 'P0', 'P2', 'P1', 'GND', 'R', 'M', 'N', 'S0', 'Q', 'S2', 'S1', 'S3', 'P3']
    busy_rows = set(FIXED_ROW.values())
    def segs_for(net):
        return sorted({segment(term_hole(t)) for t in NETS[net]})
    def rec(i):
        global MAXI
        MAXI = max(MAXI, i)
        if i == len(order): return True
        net = order[i]; segs = segs_for(net)
        if net in FIXED_ROW: rows = [FIXED_ROW[net]]
        else:
            rows = [b for b in BUS_ROWS if b not in busy_rows]
            rnd.shuffle(rows); rows.sort(key=lambda b: sum(abs(b - s[0]) for s in segs) + rnd.random() * 12)
        # nets whose segments are all in one column range on adjacent rows: chain them directly
        for b in rows[:5]:
            todo = [s for s in segs if s[0] != b]
            rnd.shuffle(todo); todo.sort(key=lambda s: -abs(s[0] - b))
            busy_rows.add(b)
            if connect(todo, b, 0, net, i): return True
            if net not in FIXED_ROW: busy_rows.discard(b)
        return False
    def connect(todo, b, k, net, i, done=None):
        nodes[0] += 1
        if nodes[0] > cap: return False
        if done is None: done = [(b, 1, COLS)]
        if k == len(todo): return rec(i + 1)
        r, lo, hi = todo[k]
        opts = []
        for (dr, dlo, dhi) in done:            # join to bus row or to an already-joined segment of this net
            for c in range(max(lo, dlo), min(hi, dhi) + 1):
                if can(c, r, dr): opts.append((abs(r - dr) + rnd.random() * 3, c, dr))
        opts.sort()
        for _, c, dr in opts[:5]:
            place(c, r, dr, net)
            if connect(todo, b, k + 1, net, i, done + [(r, lo, hi)]): return True
            unplace()
        return False
    ok = rec(0)
    return ok, jumpers

def best(n=400):
    sols = []
    for s in range(n):
        ok, J = solve(s)
        if ok:
            length = sum(abs(r1 - r2) for _, _, r1, r2 in J)
            sols.append((len(J), length, s, list(J)))
    sols.sort()
    return sols

def verify(jumpers):
    parent = {}
    def f(x):
        parent.setdefault(x, x)
        while parent[x] != x: parent[x] = parent[parent[x]]; x = parent[x]
        return x
    def u(a, b): parent[f(a)] = f(b)
    for r in range(ROWS):
        for c in range(1, COLS):
            if (r, c) not in cuts and (r, c + 1) not in cuts: u((r, c), (r, c + 1))
    for net, c, r1, r2 in jumpers: u((r1, c), (r2, c))
    groups = {}
    for net, ts in NETS.items():
        roots = {f(term_hole(t)) for t in ts}
        assert len(roots) == 1, ('open', net)
        groups[net] = roots.pop()
    assert len(set(groups.values())) == len(groups), 'short between nets'
    # every jumper end belongs to its own net
    for net, c, r1, r2 in jumpers: assert f((r1, c)) == groups[net]
    return True

if __name__ == '__main__':
    sols = best(int(sys.argv[1]) if len(sys.argv) > 1 else 300); print('solutions', len(sols))
    n, L, s, J = sols[0]; print('best seed', s, 'jumpers', n, 'total length', L)
    print('verify', verify(J))
    for j in J: print(j[0], '%s%d-%s%d' % (RL[j[2]], j[1], RL[j[3]], j[1]))
