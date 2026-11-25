# Tunio home server (Raspberry Pi)

Your Pi does the searching and downloading for the Tunio app and **keeps every song**. Songs it already has start
almost instantly, new ones are streamed to your phone while the Pi downloads them, and your phone's battery lasts
longer. Every night it also downloads the current charts (Spotify Top 50 Global by default) and updates itself.

If the Pi can't be reached, the app simply does everything on the phone like before.

Works best on a **Raspberry Pi 5 or 4** running **Raspberry Pi OS (64-bit)**. Uses about 3–5 W.

---

## 1. Prepare the Pi (skip if your Pi already runs Raspberry Pi OS 64-bit with SSH on)

On your Windows PC:

1. Install **Raspberry Pi Imager** from raspberrypi.com/software and put the Pi's memory card in the PC.
2. In Imager choose: *Raspberry Pi 5* → **Raspberry Pi OS Lite (64-bit)** (under "Raspberry Pi OS (other)") → your card.
3. When it asks to customise the settings, say **Edit settings**:
   - Hostname: `tunio`
   - Username and password: anything you'll remember
   - Wi-Fi: your Wi-Fi name and password (or plug the Pi into your router with a cable later; that's even better)
   - Services tab: turn on **SSH** (use password authentication)
4. Write the card, put it in the Pi, power the Pi on and wait 2 minutes.

## 2. Install Tunio on the Pi (one command)

Connect to the Pi. On the Windows PC: open **Terminal** (or PowerShell) and type (use your username):

```
ssh yourname@tunio.local
```

Type `yes` the first time, then your Pi password. (From a phone you can use an SSH app such as Termius.)

Then copy and paste this and press Enter:

```
curl -fsSL https://github.com/kinokuno1/Claude-allowed/releases/download/tunio-latest/install-tunio-server.sh | sudo bash
```

It takes 2–5 minutes. At the end it shows the **address**, the **password** and a QR code.

## 3. Connect your phone

On the same Wi-Fi as the Pi, choose one way:

- **Scan the QR code** with your phone's camera, open the page and tap **Connect Tunio**, or
- in Tunio: **Settings → Home server (Raspberry Pi) → Find on my Wi-Fi**, type the password, tap **Save**.

It should say *Connected: … songs ready*. That's it: just use Tunio as usual.

## 4. Optional: use it away from home (Tailscale, free)

Tailscale gives your phone a private, encrypted path to your Pi from anywhere, without opening anything on your
router.

1. On the Pi (in the SSH window):
   ```
   curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
   ```
   Open the link it prints and sign in (Google, Microsoft…).
2. On your phone: install **Tailscale** from the Play Store, sign in with the same account, turn it on.
3. In Tunio's settings, change the address to `tunio` (the Pi's name in Tailscale) and tap **Save**.

## 5. Optional: keep the songs on a USB drive

Memory cards are slower and wear out; a USB SSD or stick is better if you keep lots of songs. Plug it in, then in
the SSH window:

```
lsblk -f                                   # find the drive, e.g. sda1
sudo mkdir -p /mnt/usb
echo "UUID=$(sudo blkid -s UUID -o value /dev/sda1) /mnt/usb auto defaults,nofail 0 2" | sudo tee -a /etc/fstab
sudo mount -a
curl -fsSL https://github.com/kinokuno1/Claude-allowed/releases/download/tunio-latest/install-tunio-server.sh | sudo MUSIC_DIR=/mnt/usb/tunio bash
```

## Status page and settings

Open the address (e.g. `http://192.168.1.20:8484`) in any browser on your Wi-Fi and log in with the password. You
can see how many songs are ready and the space used, change the **nightly chart playlists** (any Spotify, YouTube or
SoundCloud playlist links) and the **maximum space** (when full, the songs not played for the longest time are
removed first), or fill it with the charts right away.

## Good to know

- **Update Tunio server**: run the install command again. Songs, password and settings are kept. yt-dlp (the part
  that talks to YouTube) already updates itself every night.
- **Forgot the password**: `sudo cat /var/lib/tunio/server.json`
- **Is it running?** `systemctl status tunio-server` · **What is it doing?** `journalctl -u tunio-server -f`
- **The address changed** (after restarting the router): use *Find on my Wi-Fi* again, or give the Pi a fixed
  address in your router's settings.
- **Keep it private.** The server is for you: it's protected by the password and only reachable on your Wi-Fi
  (and your own Tailscale). Don't forward its port on your router or share the music with others.
- **Uninstall**:
  ```
  sudo systemctl disable --now tunio-server
  sudo rm -rf /opt/tunio-server /var/lib/tunio /etc/systemd/system/tunio-server.service /etc/avahi/services/tunio.service
  sudo userdel tunio
  ```
