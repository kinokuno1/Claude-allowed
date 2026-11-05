"""Original circuit from the exercise.  X = A+B+C, Y = D(B+C), Z = A+B+C+D.
The three buffers are drawn as OR2 gates with both inputs tied together (a buffer)."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen import Builder, Proto
from msx import load, comps, partname
S, OUT = sys.argv[1], sys.argv[2]
BASE = S + '/xml/3_bit_square_circuit.ms14.xml'
Pb, E = load(BASE), load(S + '/xml/Boolean_Expresion_Simplification.ms14.xml')
first = lambda D, part: [c[0].get('LocalName')[4:] for c in comps(D) if partname(c) == part][0]
P = dict(OR2=Proto(Pb, 'U5'), AND3=Proto(Pb, first(Pb, 'AND3')), CONST=Proto(Pb, 'U11'),
         PROBE=Proto(Pb, 'X1'), OR4=Proto(E, first(E, 'OR4')))
b = Builder(BASE)
BUS = dict(A=108, B=153, C=198, D=243); ROW = dict(A=72, B=126, C=180, D=234); CP = 63
K = {k: b.add(P['CONST'], 'U%d' % (i + 1), 'OUT', CP, ROW[k], key=k) for i, k in enumerate('ABCD')}
BUF1 = b.add(P['OR2'], 'U5', 'A', 306, 270)
OR1  = b.add(P['OR2'], 'U6', 'A', 414, 279)
BUF2 = b.add(P['OR2'], 'U7', 'A', 540, 279)
ORX  = b.add(P['OR2'], 'U8', 'A', 630, 270)
ORAD = b.add(P['OR2'], 'U9', 'A', 306, 423)
BUF3 = b.add(P['OR2'], 'U10', 'A', 306, 567)
ORCD = b.add(P['OR2'], 'U11', 'A', 414, 576)
OR4  = b.add(P['OR4'], 'U12', 'A', 558, 558)
AND1 = b.add(P['AND3'], 'U13', 'A', 666, 441)
ANDY = b.add(P['AND3'], 'U14', 'A', 792, 432)
ORZ  = b.add(P['OR2'], 'U15', 'A', 792, 558)
PX = b.add(P['PROBE'], 'X1', '1', 747, 252)
PY = b.add(P['PROBE'], 'X2', '1', 891, 414)
PZ = b.add(P['PROBE'], 'X3', '1', 891, 540)
for name, x, y in [('A1', 108, 243), ('B1', 153, 315), ('C1', 198, 270), ('D1', 243, 387), ('D2', 243, 441),
                   ('j1', 288, 270), ('j2', 504, 288), ('j3', 522, 288), ('j4', 288, 567), ('j5', 522, 585),
                   ('j6', 648, 441), ('j7', 639, 576), ('j8', 756, 450), ('j9', 774, 432)]:
    b.junction(name, x, y)
p = lambda inst, pin: ('P', inst, pin)
j = lambda n: ('J', n)
k = lambda key: (BUS[key], ROW[key])
b.net('A', [(p(K['A'], 'OUT'), j('A1'), [k('A')]),
            (j('A1'), p(ORX, 'A'), [(621, 243), (621, 270)]),
            (j('A1'), p(ORAD, 'A'), [(108, 423)])])
b.net('B', [(p(K['B'], 'OUT'), j('B1'), [k('B')]),
            (j('B1'), p(OR1, 'B'), [(396, 315), (396, 297)]),
            (j('B1'), p(OR4, 'D'), [(153, 630), (549, 630), (549, 594)])])
b.net('C', [(p(K['C'], 'OUT'), j('C1'), [k('C')]),
            (j('C1'), j('j1'), []),
            (j('j1'), p(BUF1, 'A'), []),
            (j('j1'), p(BUF1, 'B'), [(288, 288)]),
            (j('C1'), j('j4'), [(198, 567)]),
            (j('j4'), p(BUF3, 'A'), []),
            (j('j4'), p(BUF3, 'B'), [(288, 585)])])
b.net('D', [(p(K['D'], 'OUT'), j('D1'), [k('D')]),
            (j('D1'), j('j9'), [(774, 387)]),
            (j('j9'), p(ANDY, 'A'), []),
            (j('j9'), p(ANDY, 'B'), [(774, 441)]),
            (j('D1'), j('D2'), []),
            (j('D2'), p(ORAD, 'B'), []),
            (j('D2'), p(ORCD, 'B'), [(243, 612), (396, 612), (396, 594)])])
b.net('C_buf1', [(p(BUF1, 'Y'), p(OR1, 'A'), [])])
b.net('N1', [(p(OR1, 'Y'), j('j2'), []),
             (j('j2'), j('j3'), []),
             (j('j3'), p(BUF2, 'A'), [(522, 279)]),
             (j('j3'), p(BUF2, 'B'), [(522, 297)]),
             (j('j2'), j('j6'), [(504, 396), (648, 396)]),
             (j('j6'), p(AND1, 'A'), []),
             (j('j6'), p(AND1, 'B'), [(648, 450)])])
b.net('N1_buf2', [(p(BUF2, 'Y'), p(ORX, 'B'), [])])
b.net('X', [(p(ORX, 'Y'), p(PX, '1'), [(747, 279)])])
b.net('N2', [(p(ORAD, 'Y'), p(OR4, 'A'), [(540, 432), (540, 558)])])
b.net('C_buf3', [(p(BUF3, 'Y'), p(ORCD, 'A'), [])])
b.net('N3', [(p(ORCD, 'Y'), j('j5'), []),
             (j('j5'), p(OR4, 'C'), []),
             (j('j5'), p(OR4, 'B'), [(522, 567)])])
b.net('N4', [(p(OR4, 'Y'), j('j7'), []),
             (j('j7'), p(AND1, 'C'), [(639, 459)]),
             (j('j7'), p(ORZ, 'B'), [])])
b.net('N5', [(p(AND1, 'Y'), j('j8'), []),
             (j('j8'), p(ANDY, 'C'), []),
             (j('j8'), p(ORZ, 'A'), [(756, 558)])])
b.net('Y', [(p(ANDY, 'Y'), p(PY, '1'), [(891, 441)])])
b.net('Z', [(p(ORZ, 'Y'), p(PZ, '1'), [(891, 567)])])
b.build(OUT)
print('built', OUT)
