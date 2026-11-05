"""Simplified circuit (Karnaugh): X = A+B+C, Y = D(B+C), Z = A+B+C+D."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen import Builder, Proto
from msx import load, comps, partname
S, OUT = sys.argv[1], sys.argv[2]
BASE = S + '/xml/3_bit_square_circuit.ms14.xml'
Pb, E, F = load(BASE), load(S + '/xml/Boolean_Expresion_Simplification.ms14.xml'), load(S + "/xml/4_bit_2's_complement.ms14.xml")
first = lambda D, part: [c[0].get('LocalName')[4:] for c in comps(D) if partname(c) == part][0]
P = dict(OR2=Proto(Pb, 'U5'), AND2=Proto(Pb, first(Pb, 'AND2')), CONST=Proto(Pb, 'U11'), PROBE=Proto(Pb, 'X1'),
         OR4=Proto(E, first(E, 'OR4')), OR3=Proto(F, first(F, 'OR3')))
b = Builder(BASE)
BUS = dict(A=108, B=153, C=198, D=243); ROW = dict(A=72, B=126, C=180, D=234); CP = 63
K = {k: b.add(P['CONST'], 'U%d' % (i + 1), 'OUT', CP, ROW[k], key=k) for i, k in enumerate('ABCD')}
GX = b.add(P['OR3'], 'U5', 'A', 450, 288)
GBC = b.add(P['OR2'], 'U6', 'A', 450, 405)
GY = b.add(P['AND2'], 'U7', 'A', 576, 414)
GZ = b.add(P['OR4'], 'U8', 'A', 450, 531)
PX = b.add(P['PROBE'], 'X1', '1', 567, 270)
PY = b.add(P['PROBE'], 'X2', '1', 693, 396)
PZ = b.add(P['PROBE'], 'X3', '1', 567, 522)
for name, x, y in [('A1', 108, 288), ('B1', 153, 297), ('B2', 153, 405), ('C1', 198, 306), ('C2', 198, 423), ('D1', 243, 450)]:
    b.junction(name, x, y)
p = lambda inst, pin: ('P', inst, pin)
j = lambda n: ('J', n)
k = lambda key: (BUS[key], ROW[key])
b.net('A', [(p(K['A'], 'OUT'), j('A1'), [k('A')]), (j('A1'), p(GX, 'A'), []), (j('A1'), p(GZ, 'A'), [(108, 531)])])
b.net('B', [(p(K['B'], 'OUT'), j('B1'), [k('B')]), (j('B1'), p(GX, 'B'), []), (j('B1'), j('B2'), []),
            (j('B2'), p(GBC, 'A'), []), (j('B2'), p(GZ, 'B'), [(153, 540)])])
b.net('C', [(p(K['C'], 'OUT'), j('C1'), [k('C')]), (j('C1'), p(GX, 'C'), []), (j('C1'), j('C2'), []),
            (j('C2'), p(GBC, 'B'), []), (j('C2'), p(GZ, 'C'), [(198, 558)])])
b.net('D', [(p(K['D'], 'OUT'), j('D1'), [k('D')]), (j('D1'), p(GY, 'B'), [(558, 450), (558, 432)]),
            (j('D1'), p(GZ, 'D'), [(243, 567)])])
b.net('BC', [(p(GBC, 'Y'), p(GY, 'A'), [])])
b.net('X', [(p(GX, 'Y'), p(PX, '1'), [(567, 297)])])
b.net('Y', [(p(GY, 'Y'), p(PY, '1'), [(693, 423)])])
b.net('Z', [(p(GZ, 'Y'), p(PZ, '1'), [(567, 549)])])
b.build(OUT)
print('built', OUT)
