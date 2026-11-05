"""Render a Multisim XML schematic (as decoded) to SVG, using the file's own drawing primitives."""
import sys, os, math, html
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from msx import load

def mat(e):
    if e.get('Transformer-M00') is None: return None
    g = lambda k: float(e.get('Transformer-' + k))
    return (g('M00'), g('M01'), g('M10'), g('M11'), g('M20'), g('M21'))
def mul(a, b):  # apply a then b (row-vector convention: p' = p*M)
    return (a[0]*b[0]+a[1]*b[2], a[0]*b[1]+a[1]*b[3], a[2]*b[0]+a[3]*b[2], a[2]*b[1]+a[3]*b[3],
            a[4]*b[0]+a[5]*b[2]+b[4], a[4]*b[1]+a[5]*b[3]+b[5])
I = (1, 0, 0, 1, 0, 0)
def ap(m, x, y): return (x*m[0]+y*m[2]+m[4], x*m[1]+y*m[3]+m[5])

def color(v):
    v = int(float(v or 0)); return '#%02x%02x%02x' % (v & 255, (v >> 8) & 255, (v >> 16) & 255)

def walk(e, m, out, skip_layers=True):
    tag = e.tag
    if tag == 'AnimationLayers' and skip_layers: return
    if tag == 'Item' and len(e):
        for k in e: walk(k, m, out, skip_layers); return
    em = mat(e)
    mm = mul(em, m) if em else m
    pc = color(e.get('Pen-Color') or e.get('PenColor'))
    if e.get('Hidden') == '1': return
    if tag in ('CODLineComp',):
        if e.get('Pen-Width') == '0' and False: pass
        (x1, y1), (x2, y2) = ap(mm, float(e.get('pt0X')), float(e.get('pt0Y'))), ap(mm, float(e.get('pt1X')), float(e.get('pt1Y')))
        out.append('<line x1="%g" y1="%g" x2="%g" y2="%g" stroke="%s" stroke-width="1"/>' % (x1, y1, x2, y2, pc))
    elif tag in ('CODRectComp',):
        pts = [ap(mm, float(e.get(a)), float(e.get(b))) for a, b in (('pt0X', 'pt0Y'), ('pt1X', 'pt0Y'), ('pt1X', 'pt1Y'), ('pt0X', 'pt1Y'))]
        out.append('<polygon points="%s" fill="none" stroke="%s"/>' % (' '.join('%g,%g' % p for p in pts), pc))
    elif tag == 'CODEllipseComp':
        ks = [k for k in e.attrib if k.startswith('pt')]
        if ks:
            p0, p1 = ap(mm, float(e.get('pt0X')), float(e.get('pt0Y'))), ap(mm, float(e.get('pt1X')), float(e.get('pt1Y')))
            fill = color(e.get('FgColor')) if e.get('Brush-Style') == '0' else 'none'
            out.append('<ellipse cx="%g" cy="%g" rx="%g" ry="%g" fill="%s" stroke="%s"/>' % ((p0[0]+p1[0])/2, (p0[1]+p1[1])/2, abs(p1[0]-p0[0])/2, abs(p1[1]-p0[1])/2, fill, pc))
    elif tag == 'CIITArcComp':
        s_ = ap(mm, float(e.get('StartX')), float(e.get('StartY'))); md = ap(mm, float(e.get('MiddleX')), float(e.get('MiddleY'))); en = ap(mm, float(e.get('EndX')), float(e.get('EndY')))
        out.append('<polyline points="%s" fill="none" stroke="%s"/>' % (' '.join('%g,%g' % p for p in arc3(s_, md, en)), pc))
    elif tag == 'CODPolygonComp' or tag == 'CODPolylineComp':
        pts = [ap(mm, float(p.get('X')), float(p.get('Y'))) for p in e.iter('Item') if p.get('X')]
        if pts: out.append('<polygon points="%s" fill="none" stroke="%s"/>' % (' '.join('%g,%g' % p for p in pts), pc))
    elif 'Text' in tag and e.get('Output') and tag not in ('CIITSymTextCompLocation',):
        txt = e.get('Output').replace('&ASC', '').replace('&UNI', '')
        x, y = ap(mm, 0, 0); fh = abs(float(e.get('Font-Height') or -11))
        anchor = {'0': 'start', '1': 'middle', '2': 'end'}.get(e.get('HorizontalAlign') or '0')
        if tag.startswith('CIITPinSymText') : return
        out.append('<text x="%g" y="%g" font-size="%g" font-family="Arial" font-weight="%s" text-anchor="%s" dominant-baseline="hanging">%s</text>' % (x, y, fh*0.8, 'bold' if e.get('Font-Weight') == '700' else 'normal', anchor, html.escape(txt)))
    for k in e:
        if k.tag in ('Objects', 'Item', 'AnimationLayers', 'CODComposite') or k.get('Class'):
            walk(k, mm, out, skip_layers)

def arc3(a, b, c, n=16):
    ax, ay = a; bx, by = b; cx, cy = c
    d = 2*(ax*(by-cy)+bx*(cy-ay)+cx*(ay-by))
    if abs(d) < 1e-9: return [a, c]
    ux = ((ax*ax+ay*ay)*(by-cy)+(bx*bx+by*by)*(cy-ay)+(cx*cx+cy*cy)*(ay-by))/d
    uy = ((ax*ax+ay*ay)*(cx-bx)+(bx*bx+by*by)*(ax-cx)+(cx*cx+cy*cy)*(bx-ax))/d
    r = math.hypot(ax-ux, ay-uy)
    t1, t2, t3 = [math.atan2(p[1]-uy, p[0]-ux) for p in (a, b, c)]
    def between(t, s, e):
        return ((t - s) % (2*math.pi)) <= ((e - s) % (2*math.pi))
    s, e = (t1, t3) if between(t2, t1, t3) else (t3, t1)
    span = (e - s) % (2*math.pi)
    return [(ux + r*math.cos(s+span*i/n), uy + r*math.sin(s+span*i/n)) for i in range(n+1)]

def render(path, out_svg, title=''):
    D = load(path); out = []
    for it in D['objs']:
        c = it.get('Class')
        if c in ('CIITSymbolComp', 'CIITAnimationSymbolComp'): walk(it[0], I, out)
        elif c == 'CIITLinkComp':
            pts = [(float(p.get('X')), float(p.get('Y'))) for p in it[0].find('Points')]
            out.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="1.2"/>' % (' '.join('%g,%g' % p for p in pts), color(it[0].get('Pen-Color'))))
        elif c == 'CODPinComp':
            x, y = float(it[0].get('Transformer-M20') or 0) + float(it[0].get('CenterX') or 0), float(it[0].get('Transformer-M21') or 0) + float(it[0].get('CenterY') or 0)
            out.append('<circle cx="%g" cy="%g" r="3" fill="#c00"/>' % (x, y))
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 720" width="1920" height="1440" style="background:#fff"><rect x="0" y="0" width="960" height="720" fill="#fff" stroke="#999"/>%s<text x="10" y="712" font-size="10" fill="#666">%s</text></svg>' % ('\n'.join(out), html.escape(title))
    open(out_svg, 'w').write(svg)

if __name__ == '__main__':
    render(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else '')
