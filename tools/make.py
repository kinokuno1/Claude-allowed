import sys, os, subprocess
H = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, H)
import msfile
from gen import finalize
from check import check, simulate, overlaps
from render import render
S, DEST = sys.argv[1], sys.argv[2]
jobs = [('circuit.py', 'circuit_original.ms14'), ('simplified.py', 'circuit_simplifie.ms14')]
for script, name in jobs:
    raw = os.path.join(S, name + '.raw.xml')
    subprocess.run([sys.executable, '-I', os.path.join(H, script), S, raw], check=True)
    D, errs = check(raw); assert not errs, errs
    rows = simulate(D)
    assert all(o == (int(a or b or c), int(d and (b or c)), int(a or b or c or d)) for (a, b, c, d), o in rows), rows
    probs, ext, _ = overlaps(D); assert not probs, probs
    svg = os.path.join(S, name + '.svg'); png = os.path.join(S, name + '.png')
    render(raw, svg)
    subprocess.run(['node', os.path.join(S, 'png.js'), svg, png, '960', '720', '1'], check=True)
    xml = finalize(open(raw, encoding='ascii', newline='').read(), name, open(png, 'rb').read())
    final = os.path.join(S, name + '.xml'); open(final, 'w', encoding='ascii', newline='').write(xml)
    D, errs = check(final); assert not errs
    out = os.path.join(DEST, name)
    msfile.write_ms(out, xml.encode('ascii'))
    back = msfile.read_ms(out)
    assert back == xml.encode('ascii')
    print('OK', out, os.path.getsize(out), 'bytes; decoded back identical; truth table verified')
