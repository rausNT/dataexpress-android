#!/bin/bash
set -euo pipefail
work="${DX_ARM32_WORK:-/root/dataexpress-arm32}"
mkdir -p "$work"
root="$work/rootfs"
if [ ! -e "$root/debootstrap/debootstrap" ] && [ ! -e "$root/etc/debian_version" ]; then
  debootstrap --foreign --arch=armhf --variant=minbase --include=libc6-dev,libx11-6,libxext6,libxrender1,libxrandr2,libxcursor1,libxi6,libxinerama1,libxcomposite1,libfreetype6,libfontconfig1,libasound2,libcups2,libgnutls30,libgstreamer1.0-0,libgphoto2-6,libpulse0,libudev1,libunwind8,fonts-dejavu-core bookworm "$root" https://deb.debian.org/debian
fi
if [ -e "$root/debootstrap/debootstrap" ]; then
  chroot "$root" /debootstrap/debootstrap --second-stage
fi
echo 'ARMHF rootfs base ready'
