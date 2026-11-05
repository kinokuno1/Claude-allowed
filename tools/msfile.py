"""Multisim .ms14 container: magic + u64 total + [u32 dlen, u32 clen, PKWare DCL implode]*"""
import struct
MAGIC = b"MSMCompressedElectronicsWorkbenchXML"

LITLEN = [11,124,8,7,28,7,188,13,76,4,10,8,12,10,12,10,8,23,8,9,7,6,7,8,7,6,55,8,23,24,12,11,7,9,11,12,6,7,22,5,
          7,24,6,11,9,6,7,22,7,11,38,7,9,8,25,11,8,11,9,12,8,12,5,38,5,38,5,11,7,5,6,21,6,10,53,8,7,24,10,27,
          44,253,253,253,252,252,252,13,12,45,12,45,12,61,12,45,44,173]
LENLEN = [2,35,36,53,38,23]
DISTLEN = [2,20,53,230,247,151,248]
BASE = [3,2,4,5,6,7,8,9,10,12,16,24,40,72,136,264]
EXTRA = [0,0,0,0,0,0,0,0,1,2,3,4,5,6,7,8]

def expand(rep):
    lens = []
    for b in rep:
        lens += [b & 15] * ((b >> 4) + 1)
    return lens

def canon(lens):
    """blast-style canonical codes: returns {sym: (code, length)} where code is MSB-first (pre-inversion)."""
    maxl = max(lens); count = [0]*(maxl+1)
    for l in lens: count[l] += 1
    count[0] = 0
    nxt = [0]*(maxl+2); code = 0
    for l in range(1, maxl+1):
        nxt[l] = code; code = (code + count[l]) << 1
    out = {}
    for s, l in enumerate(lens):
        if l: out[s] = (nxt[l], l); nxt[l] += 1
    return out

def decoder(lens):
    return {(c, l): s for s, (c, l) in canon(lens).items()}

LIT, LEN, DIST = expand(LITLEN), expand(LENLEN), expand(DISTLEN)
LITD, LEND, DISTD = decoder(LIT), decoder(LEN), decoder(DIST)
LITE, LENE, DISTE = canon(LIT), canon(LEN), canon(DIST)

class BitIn:
    def __init__(s, d): s.d, s.p, s.buf, s.n = d, 0, 0, 0
    def bits(s, k):
        while s.n < k:
            s.buf |= s.d[s.p] << s.n; s.p += 1; s.n += 8
        v = s.buf & ((1 << k) - 1); s.buf >>= k; s.n -= k; return v
    def huff(s, table):
        code = 0
        for l in range(1, 17):
            code = (code << 1) | (s.bits(1) ^ 1)   # codes stored inverted
            if (code, l) in table: return table[(code, l)]
        raise ValueError("bad code")

def explode(data):
    b = BitIn(data); lit = b.bits(8); dic = b.bits(8); out = bytearray()
    while True:
        if b.bits(1):
            sym = b.huff(LEND); ln = BASE[sym] + b.bits(EXTRA[sym])
            if ln == 519: break
            sh = 2 if ln == 2 else dic
            dist = (b.huff(DISTD) << sh) + b.bits(sh) + 1
            for _ in range(ln): out.append(out[-dist])
        else:
            out.append(b.huff(LITD) if lit else b.bits(8))
    return bytes(out)

class BitOut:
    def __init__(s): s.out, s.buf, s.n = bytearray(), 0, 0
    def bits(s, v, k):
        s.buf |= v << s.n; s.n += k
        while s.n >= 8: s.out.append(s.buf & 255); s.buf >>= 8; s.n -= 8
    def huff(s, code, l):
        for i in range(l - 1, -1, -1): s.bits(((code >> i) & 1) ^ 1, 1)
    def done(s):
        if s.n: s.out.append(s.buf & 255)
        return bytes(s.out)

def implode(data, dic=6):
    """ASCII-literal mode, large dictionary; greedy LZ77 with hash chains."""
    o = BitOut(); o.bits(1, 8); o.bits(dic, 8)
    window = 64 << dic; heads = {}; i = 0; n = len(data)
    def emit_len(ln):
        for sym in range(15, -1, -1):
            if BASE[sym] <= ln and sym != 1 or (sym == 1 and ln == 2):
                if sym == 0 and ln != 3: continue
                if ln - BASE[sym] < (1 << EXTRA[sym]) or EXTRA[sym] == 0 and ln == BASE[sym]:
                    o.bits(1, 1); o.huff(*LENE[sym]); o.bits(ln - BASE[sym], EXTRA[sym]); return
        raise ValueError(ln)
    while i < n:
        best_l = best_d = 0
        if i + 3 <= n:
            key = data[i:i+3]
            for j in reversed(heads.get(key, [])[-32:]):
                d = i - j
                if d > window: continue
                l = 0
                while l < 518 and i + l < n and data[j + l] == data[i + l]: l += 1
                if l > best_l: best_l, best_d = l, d
        if best_l >= 3:
            emit_len(best_l); dd = best_d - 1
            o.huff(*DISTE[dd >> dic]); o.bits(dd & ((1 << dic) - 1), dic)
            for k in range(i, i + best_l):
                if k + 3 <= n: heads.setdefault(data[k:k+3], []).append(k)
            i += best_l
        else:
            o.bits(0, 1); o.huff(*LITE[data[i]])
            if i + 3 <= n: heads.setdefault(data[i:i+3], []).append(i)
            i += 1
    emit_len(519)
    return o.done()

def read_ms(path):
    d = open(path, "rb").read(); assert d.startswith(MAGIC)
    p = len(MAGIC); total, = struct.unpack_from("<Q", d, p); p += 8; out = b""
    while p < len(d):
        dl, cl = struct.unpack_from("<II", d, p); p += 8
        blk = explode(d[p:p+cl]); assert len(blk) == dl, (len(blk), dl); out += blk; p += cl
    assert len(out) == total
    return out

def write_ms(path, xml, block=900000):
    parts = [MAGIC, struct.pack("<Q", len(xml))]
    for i in range(0, len(xml), block):
        s = xml[i:i+block]; c = implode(s); parts += [struct.pack("<II", len(s), len(c)), c]
    open(path, "wb").write(b"".join(parts))
