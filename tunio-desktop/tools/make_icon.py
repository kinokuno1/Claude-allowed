"""Draws Tunio's icon (same as the Android launcher icon: play button + sound waves on a violet->pink gradient)."""
import sys
from PIL import Image, ImageDraw

S = 256
img = Image.new("RGBA", (S, S))
px = img.load()
a, b = (0x8B, 0x5C, 0xF6), (0xEC, 0x48, 0x99)
for y in range(S):
    for x in range(S):
        t = (x + y) / (2 * S)
        px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)
mask = Image.new("L", (S, S))
ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), 56, fill=255)
img.putalpha(mask)
d = ImageDraw.Draw(img)
k = S / 108
d.polygon([(46 * k, 38 * k), (46 * k, 70 * k), (70 * k, 54 * k)], fill="white")
d.arc((18 * k, 38 * k, 50 * k, 70 * k), 90, 270, fill="white", width=int(4 * k))
d.arc((2 * k, 30 * k, 50 * k, 78 * k), 90, 270, fill=(255, 255, 255, 180), width=int(4 * k))
img.save(sys.argv[1] if len(sys.argv) > 1 else "tunio.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
