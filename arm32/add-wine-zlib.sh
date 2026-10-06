#!/bin/bash
set -euo pipefail
work="${DX_ARM32_WORK:-/root/dataexpress-arm32}"
root="$work/rootfs"
artifact=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
chroot "$root" /bin/sh -c 'cd /tmp/wine-data && apt-get download libz-mingw-w64'
for package in "$root"/tmp/wine-data/libz-mingw*.deb; do dpkg-deb -x "$package" "$root"; done
cp "$root/usr/i686-w64-mingw32/lib/zlib1.dll" "$artifact/zlib1.dll"
cp "$artifact/zlib1.dll" "$root/usr/lib/i386-linux-gnu/wine/i386-windows/zlib1.dll"
cp "$artifact/wineserver-tv.sh" "$root/usr/lib/wine/wineserver"
chmod 755 "$root/usr/lib/wine/wineserver"
