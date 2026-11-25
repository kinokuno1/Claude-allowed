"""Tunio's Windows icon, made from the app icon art (tools/icon.png, same as the Android and iPhone icons)."""
import os
import sys
from PIL import Image

src = Image.open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.png")).convert("RGBA").resize((256, 256), Image.LANCZOS)
src.save(sys.argv[1] if len(sys.argv) > 1 else "tunio.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
