#!/bin/bash
set -euo pipefail
work="${DX_ARM32_WORK:-/root/dataexpress-arm32}"
root="$work/rootfs"
test -f "$root/etc/debian_version"
printf 'deb https://deb.debian.org/debian bookworm main\n' > "$root/etc/apt/sources.list"
cp /etc/resolv.conf "$root/etc/resolv.conf"
chroot "$root" dpkg --add-architecture i386
chroot "$root" apt-get update
mkdir -p "$root/tmp/wine-packages"
chroot "$root" /bin/sh -c 'cd /tmp/wine-packages && apt-get download wine32:i386 libwine:i386'
# Take the x86 Wine engine and its PE DLLs; native ARM libraries come from Debian armhf.
for package in "$root"/tmp/wine-packages/*i386.deb; do dpkg-deb -x "$package" "$root"; done
# Common library data, fonts, registry defaults and NLS tables are required too.
mkdir -p "$root/tmp/wine-data"
chroot "$root" /bin/sh -c 'cd /tmp/wine-data && apt-get download wine fonts-wine'
for package in "$root"/tmp/wine-data/*.deb; do dpkg-deb -x "$package" "$root"; done
file "$root/usr/lib/wine/wine" "$root/usr/lib/wine/wineserver"
mkdir -p "$work/wine-original"
for package in "$root"/tmp/wine-packages/libwine_*i386.deb; do dpkg-deb -x "$package" "$work/wine-original"; done
echo "Debian Wine32 extracted"
