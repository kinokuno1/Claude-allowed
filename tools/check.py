import sys, os, re, itertools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from msx import load, comps, partname, ports_of
from collections import Counter

def check(path, verbose=True):
    D = load(path); d = D['diag']; errs = []
    defined = Counter()
    for e in d.iter():
        if e.tag == 'Item' and e.get('ID') and len(e): defined[e.get('ID')] += 1
        if e.tag == 'Item' and e.get('CiID') and e.get('Class'): defined[e.get('CiID')] += 1
    dups = [k for k, v in defined.items() if v > 1]
    if dups: errs.append('duplicate ids %s' % dups[:5])
    stubs = set(e.get('ID') for e in d.iter() if e.tag == 'Item' and e.get('ID') and not len(e))
    allids = set(defined)
    # pin connectors & junction pins are defined inside symbols / objects; collect all IDs anywhere with content
    refattrs = ('Connect1', 'Connect2', 'Node', 'NodeText', 'CiComponent', 'PortID', 'SymCompID', 'Component', 'Model', 'Circuit', 'Scope')
    for e in d.iter():
        for a in refattrs:
            v = e.get(a)
            if v and re.fullmatch(r'\d{5,}', v) and v not in allids: errs.append('%s.%s=%s dangling' % (e.tag, a, v))
    for s_ in stubs:
        if s_ not in allids: errs.append('stub %s dangling' % s_)
    for lst in d.iter():
        if lst.tag in ('Ports', 'Nodes', 'Models', 'Components'):
            for it in lst:
                if it.get('CiID') and it.get('CiID') not in allids: errs.append('%s list item %s dangling' % (lst.tag, it.get('CiID')))
    # symmetric connect lists
    byid = {}
    for e in d.iter():
        if e.tag == 'Item' and e.get('ID') and len(e): byid[e.get('ID')] = e
    for i, e in byid.items():
        cl = e[0].find('ConnectList')
        if cl is None: continue
        for it in cl:
            o = byid.get(it.get('ID'))
            if o is None: errs.append('connect target missing'); continue
            ocl = o[0].find('ConnectList')
            if ocl is None or i not in [x.get('ID') for x in ocl]: errs.append('asymmetric connect %s->%s' % (i, it.get('ID')))
    # wires geometry
    nwires = 0
    for it in D['objs']:
        if it.get('Class') != 'CIITLinkComp': continue
        nwires += 1; L = it[0]
        pts = [(float(p.get('X')), float(p.get('Y'))) for p in L.find('Points')]
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            if x1 != x2 and y1 != y2: errs.append('diagonal wire %s' % it.get('ID'))
        for end, pt in ((L.get('Connect1'), pts[0]), (L.get('Connect2'), pts[-1])):
            ep = byid[end][0]
            if (float(ep.get('CenterX')), float(ep.get('CenterY'))) != pt: errs.append('wire end mismatch %s' % it.get('ID'))
    # electrical netlist
    nets = {}
    for it in D['el']:
        if it.get('Class') == 'CiNode':
            for p in it[0].find('Ports'): nets.setdefault(it.get('CiID'), []).append(p.get('CiID'))
    for it in D['el']:
        if it.get('Class') == 'CiPort':
            for n in it[0].find('Nodes'):
                if it.get('CiID') not in nets.get(n.get('CiID'), []): errs.append('port/node asymmetry')
    if verbose: print(os.path.basename(path), 'ids', len(allids), 'wires', nwires, 'nets', len(nets), 'errors', len(errs), errs[:8])
    return D, errs

def simulate(D):
    """evaluate the electrical netlist for all 16 input combos; returns rows"""
    pn = {}
    for c in comps(D):
        name = c[0].get('LocalName')[4:]
        for p in ports_of(D, c):
            nodes = [n.get('CiID') for n in p[0].find('Nodes')]
            pn[(name, p[0].get('LocalName')[4:])] = nodes[0] if nodes else None
    parts = {c[0].get('LocalName')[4:]: partname(c) for c in comps(D)}
    keys = {}
    for c in comps(D):
        if parts[c[0].get('LocalName')[4:]] == 'INTERACTIVE_DIGITAL_CONSTANT':
            sym = D['byid'][c[0].get('SymCompID')]
            keys[c[0].get('LocalName')[4:]] = [e.get('Output')[4:] for e in sym.iter('CIITTextCompAnimationLayerName')][0]
    rows = []
    for bits in itertools.product([0, 1], repeat=4):
        inp = dict(zip('ABCD', bits)); val = {}
        for u, k in keys.items(): val[pn[(u, 'OUT')]] = inp[k]
        for _ in range(20):
            for u, part in parts.items():
                m = re.fullmatch(r'(AND|OR)(\d)', part)
                if not m: continue
                ins = [val.get(pn[(u, pin)]) for pin in 'ABCD'[:int(m.group(2))]]
                if None in ins: continue
                val[pn[(u, 'Y')]] = int(all(ins)) if m.group(1) == 'AND' else int(any(ins))
        outs = tuple(val.get(pn[(x, '1')]) for x in sorted(u for u, p in parts.items() if p.startswith('PROBE')))
        rows.append((bits, outs))
    return rows

if __name__ == '__main__':
    for f in sys.argv[1:]:
        D, errs = check(f)
        if 'out' in f:
            rows = simulate(D)
            ok = all(o == (int(a or b or c), int(d and (b or c)), int(a or b or c or d)) for (a, b, c, d), o in rows)
            print('  simulated truth table matches X=A+B+C, Y=D(B+C), Z=A+B+C+D:', ok)
            for r in rows: print('  ', r)

def bodies(D):
    """component body boxes from CIITSymbolBorderRect (absolute)"""
    out = {}
    for c in comps(D):
        sym = D['byid'][c[0].get('SymCompID')]; top = sym[0]
        tx, ty = float(top.get('Transformer-M20') or 0), float(top.get('Transformer-M21') or 0)
        r = top.find('Objects/Item/CIITSymbolBorderRect')
        ox, oy = tx + float(r.get('Transformer-M20') or 0), ty + float(r.get('Transformer-M21') or 0)
        xs = [ox + float(r.get('pt0X')), ox + float(r.get('pt1X'))]; ys = [oy + float(r.get('pt0Y')), oy + float(r.get('pt1Y'))]
        out[c[0].get('LocalName')[4:]] = (min(xs), min(ys), max(xs), max(ys))
    return out

def overlaps(D):
    bx = bodies(D); probs = []
    names = list(bx)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            A, B = bx[a], bx[b]
            if A[0] < B[2] and B[0] < A[2] and A[1] < B[3] and B[1] < A[3]: probs.append('bodies %s/%s overlap' % (a, b))
    for it in D['objs']:
        if it.get('Class') != 'CIITLinkComp': continue
        pts = [(float(p.get('X')), float(p.get('Y'))) for p in it[0].find('Points')]
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            for n, (l, t, r, b) in bx.items():
                sx0, sx1, sy0, sy1 = min(x1, x2), max(x1, x2), min(y1, y2), max(y1, y2)
                if sx0 < r and sx1 > l and sy0 < b and sy1 > t: probs.append('wire %s crosses body %s' % ((x1, y1, x2, y2), n))
    # T-joins/overlaps between collinear wire segments of different nets
    segs = []
    for it in D['objs']:
        if it.get('Class') != 'CIITLinkComp': continue
        pts = [(float(p.get('X')), float(p.get('Y'))) for p in it[0].find('Points')]
        segs += [(it[0].get('Node'), a, b) for a, b in zip(pts, pts[1:])]
    for i, (n1, a1, b1) in enumerate(segs):
        for n2, a2, b2 in segs[i + 1:]:
            if n1 == n2: continue
            if a1[0] == b1[0] == a2[0] == b2[0] and max(min(a1[1], b1[1]), min(a2[1], b2[1])) < min(max(a1[1], b1[1]), max(a2[1], b2[1])): probs.append('collinear overlap x=%s' % a1[0])
            if a1[1] == b1[1] == a2[1] == b2[1] and max(min(a1[0], b1[0]), min(a2[0], b2[0])) < min(max(a1[0], b1[0]), max(a2[0], b2[0])): probs.append('collinear overlap y=%s' % a1[1])
            # endpoint of one lying on the other (accidental visual T)
            for p in (a2, b2):
                if (a1[0] == b1[0] == p[0] and min(a1[1], b1[1]) < p[1] < max(a1[1], b1[1])) or (a1[1] == b1[1] == p[1] and min(a1[0], b1[0]) < p[0] < max(a1[0], b1[0])): probs.append('foreign endpoint on wire at %s' % (p,))
            for p in (a1, b1):
                if (a2[0] == b2[0] == p[0] and min(a2[1], b2[1]) < p[1] < max(a2[1], b2[1])) or (a2[1] == b2[1] == p[1] and min(a2[0], b2[0]) < p[0] < max(a2[0], b2[0])): probs.append('foreign endpoint on wire at %s' % (p,))
    allx = [v for b in bx.values() for v in (b[0], b[2])]; ally = [v for b in bx.values() for v in (b[1], b[3])]
    return probs, (min(allx), min(ally), max(allx), max(ally)), bx
