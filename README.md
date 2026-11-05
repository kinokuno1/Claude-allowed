# Logic circuit – analysis (inputs A, B, C, D → outputs X, Y, Z)

> Note: the triangle symbols in the drawing are **buffers** (no inversion bubble), so they pass their input through unchanged.

## Netlist (original circuit)

| Gate | Inputs | Output |
|------|--------|--------|
| BUF1 | C | C |
| OR1  | BUF1, B | N1 = B + C |
| BUF2 | N1 | N1 |
| OR_X | A, BUF2 | **X** |
| OR2  | A, D | N2 = A + D |
| BUF3 | C | C |
| OR3  | BUF3, D | N3 = C + D |
| OR4 (4 in) | N2, N3, N3, B | N4 = A + B + C + D |
| AND1 | N1, N1, N4 | N5 = (B + C)·(A + B + C + D) |
| AND2 (Y) | D, D, N5 | **Y** |
| OR_Z | N5, N4 | **Z** |

## Truth table

| A | B | C | D | X | Y | Z |
|---|---|---|---|---|---|---|
| 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 0 | 0 | 0 | 1 | 0 | 0 | 1 |
| 0 | 0 | 1 | 0 | 1 | 0 | 1 |
| 0 | 0 | 1 | 1 | 1 | 1 | 1 |
| 0 | 1 | 0 | 0 | 1 | 0 | 1 |
| 0 | 1 | 0 | 1 | 1 | 1 | 1 |
| 0 | 1 | 1 | 0 | 1 | 0 | 1 |
| 0 | 1 | 1 | 1 | 1 | 1 | 1 |
| 1 | 0 | 0 | 0 | 1 | 0 | 1 |
| 1 | 0 | 0 | 1 | 1 | 0 | 1 |
| 1 | 0 | 1 | 0 | 1 | 0 | 1 |
| 1 | 0 | 1 | 1 | 1 | 1 | 1 |
| 1 | 1 | 0 | 0 | 1 | 0 | 1 |
| 1 | 1 | 0 | 1 | 1 | 1 | 1 |
| 1 | 1 | 1 | 0 | 1 | 0 | 1 |
| 1 | 1 | 1 | 1 | 1 | 1 | 1 |

## Simplified equations (Karnaugh)

- **X = A + B + C**   (only 0 when A = B = C = 0)
- **Y = B·D + C·D = D·(B + C)**
- **Z = A + B + C + D**   (only 0 when all inputs are 0)

Simplified circuit: one 3-input OR (X), one 2-input OR feeding a 2-input AND with D (Y), one 4-input OR (Z).

## Building it in Multisim 14

1. 4 × `SPDT` switches (Basic ▸ Switch), keys A/B/C/D, between VCC (5 V) and GND, for inputs A–D.
2. Gates from **Misc Digital ▸ TIL**: `OR2`, `OR3`, `OR4`, `AND2`, `AND3`, `BUFFER`; or 74LS32 / 74LS08 / 74LS4072 equivalents.
3. 3 × `PROBE` (Indicators) on X, Y, Z.
4. Wire the gates following the netlist above, run the simulation and compare it with the truth table.
