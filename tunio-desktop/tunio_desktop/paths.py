"""Where Tunio keeps things on Windows (other systems: under the home folder)."""
import os

if os.name == "nt":
    DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "Tunio")
    CACHE_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or DATA_DIR, "Tunio", "cache")
else:
    DATA_DIR = os.path.join(os.path.expanduser("~"), ".tunio")
    CACHE_DIR = os.path.join(DATA_DIR, "cache")

DATA_DIR = os.environ.get("TUNIO_DATA_DIR", DATA_DIR)
CACHE_DIR = os.environ.get("TUNIO_CACHE_DIR", CACHE_DIR)
# Offline songs go to your Music folder, so they're easy to find (and survive reinstalling Tunio).
OFFLINE_DIR = os.environ.get("TUNIO_OFFLINE_DIR", os.path.join(os.path.expanduser("~"), "Music", "Tunio"))
AUDIO_CACHE = os.path.join(CACHE_DIR, "audio")
YTDLP_CACHE = os.path.join(CACHE_DIR, "yt-dlp")
LIBRARY_FILE = os.path.join(DATA_DIR, "library.json")
SETTINGS_FILE = os.path.join(DATA_DIR, "settings.json")

for d in (DATA_DIR, CACHE_DIR, AUDIO_CACHE, YTDLP_CACHE):
    os.makedirs(d, exist_ok=True)
