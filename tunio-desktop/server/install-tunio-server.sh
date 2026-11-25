#!/usr/bin/env bash
# Installs (or updates) the Tunio home server on a Raspberry Pi (Raspberry Pi OS 64-bit) or any Debian/Ubuntu.
#   curl -fsSL https://github.com/kinokuno1/Claude-allowed/releases/download/tunio-latest/install-tunio-server.sh | sudo bash
# Songs on a USB drive instead of the memory card:
#   curl -fsSL .../install-tunio-server.sh | sudo MUSIC_DIR=/mnt/usb/tunio bash
# Run it again any time to update; your songs, password and settings are kept.
set -euo pipefail

RELEASE="${TUNIO_RELEASE:-https://github.com/kinokuno1/Claude-allowed/releases/download/tunio-latest}"
APP=/opt/tunio-server
DATA=/var/lib/tunio
PORT=8484

say() { printf '\n\033[1;35m==>\033[0m %s\n' "$*"; }

if [ "$(id -u)" -ne 0 ]; then
  echo "Please run with sudo:  curl -fsSL $RELEASE/install-tunio-server.sh | sudo bash" >&2
  exit 1
fi

say "Installing what Tunio needs (Python, ffmpeg)..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq python3 python3-venv python3-pip ffmpeg curl unzip ca-certificates avahi-daemon qrencode >/dev/null

if ! id tunio >/dev/null 2>&1; then
  useradd --system --home-dir "$DATA" --create-home --shell /usr/sbin/nologin tunio
fi
mkdir -p "$DATA" "$APP/bin"

say "Downloading the Tunio server..."
SRC=""
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
  SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
if [ -n "$SRC" ] && [ -d "$SRC/../tunio_desktop" ]; then
  # Run from a copy of the repository (tunio-desktop/server/): use those files.
  rm -rf "$APP/app.new" && mkdir -p "$APP/app.new"
  cp -r "$SRC/../tunio_desktop" "$APP/app.new/"
else
  TMP="$(mktemp -d)"
  curl -fsSL "$RELEASE/tunio-server.tar.gz" -o "$TMP/tunio-server.tar.gz"
  rm -rf "$APP/app.new" && mkdir -p "$APP/app.new"
  tar -xzf "$TMP/tunio-server.tar.gz" -C "$APP/app.new"
  rm -rf "$TMP"
fi
rm -rf "$APP/app" && mv "$APP/app.new" "$APP/app"

say "Installing yt-dlp (the part that gets the music)..."
if [ ! -x "$APP/venv/bin/python" ]; then
  python3 -m venv "$APP/venv"
fi
"$APP/venv/bin/pip" install -q --upgrade pip
"$APP/venv/bin/pip" install -q --upgrade "yt-dlp[default]"

say "Installing Deno (YouTube needs it to unlock songs)..."
case "$(uname -m)" in
  aarch64|arm64) DENO_ZIP=deno-aarch64-unknown-linux-gnu.zip ;;
  x86_64|amd64)  DENO_ZIP=deno-x86_64-unknown-linux-gnu.zip ;;
  *) DENO_ZIP="" ;;
esac
if [ -n "$DENO_ZIP" ]; then
  TMP="$(mktemp -d)"
  if curl -fsSL "https://github.com/denoland/deno/releases/latest/download/$DENO_ZIP" -o "$TMP/deno.zip"; then
    unzip -o -q "$TMP/deno.zip" -d "$TMP" && install -m 755 "$TMP/deno" "$APP/bin/deno"
  else
    echo "Couldn't download Deno; most songs still work without it." >&2
  fi
  rm -rf "$TMP"
else
  echo "This is a 32-bit system: Deno isn't available. Use Raspberry Pi OS (64-bit) for the best results." >&2
fi

chown -R tunio:tunio "$APP/venv" "$DATA"

if [ -n "${MUSIC_DIR:-}" ]; then
  mkdir -p "$MUSIC_DIR"
  chown -R tunio:tunio "$MUSIC_DIR"
fi

RUN_ENV=(env TUNIO_DATA_DIR="$DATA" TUNIO_CACHE_DIR="$DATA/cache" HOME="$DATA" PYTHONPATH="$APP/app")
if [ -n "${MUSIC_DIR:-}" ]; then
  runuser -u tunio -- "${RUN_ENV[@]}" "$APP/venv/bin/python" -m tunio_desktop.homeserver --set "music_dir=$MUSIC_DIR"
fi

say "Starting the server (and making it start by itself when the Pi turns on)..."
cat > /etc/systemd/system/tunio-server.service <<EOF
[Unit]
Description=Tunio home server
After=network-online.target
Wants=network-online.target

[Service]
User=tunio
Environment=TUNIO_DATA_DIR=$DATA
Environment=TUNIO_CACHE_DIR=$DATA/cache
Environment=HOME=$DATA
Environment=PYTHONPATH=$APP/app
Environment=PYTHONUNBUFFERED=1
Environment=PATH=$APP/bin:$APP/venv/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$APP/venv/bin/python -m tunio_desktop.homeserver
Restart=always
RestartSec=3
Nice=5

[Install]
WantedBy=multi-user.target
EOF

# Lets the phone find the server on your Wi-Fi ("Find on my Wi-Fi" in Tunio's settings).
mkdir -p /etc/avahi/services
cat > /etc/avahi/services/tunio.service <<EOF
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name>Tunio on %h</name>
  <service><type>_tunio._tcp</type><port>$PORT</port></service>
</service-group>
EOF
systemctl reload avahi-daemon 2>/dev/null || systemctl restart avahi-daemon 2>/dev/null || true

systemctl daemon-reload
systemctl enable tunio-server >/dev/null 2>&1
systemctl restart tunio-server

INFO="$(runuser -u tunio -- "${RUN_ENV[@]}" "$APP/venv/bin/python" -m tunio_desktop.homeserver --show)"
field() { printf '%s' "$INFO" | python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$IP" ] || IP="$(field ip)"
PW="$(field password)"

for _ in $(seq 1 20); do
  curl -fs "http://127.0.0.1:$PORT/api/hello" >/dev/null 2>&1 && break
  sleep 1
done

say "Done! Tunio server is running."
cat <<EOF

  Address:   http://$IP:$PORT
  Password:  $PW
  Songs in:  $(field music_dir)

  In the Tunio app on your phone: Settings > Home server > "Find on my Wi-Fi",
  type the password, then tap "Save".

  Or scan this with your phone's camera (on the same Wi-Fi) and tap "Connect Tunio":
EOF
qrencode -t ANSIUTF8 -m 2 "http://$IP:$PORT/pair#$PW" 2>/dev/null || echo "  http://$IP:$PORT/pair#$PW"
cat <<EOF

  Status page (songs, nightly charts, space): http://$IP:$PORT

  If the address changes after restarting your router, use "Find on my Wi-Fi" again,
  or give the Pi a fixed address in your router's settings.
EOF
