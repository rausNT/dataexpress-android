#!/bin/bash
set -euo pipefail
work="${DX_ARM32_WORK:-/root/dataexpress-arm32}"
root="$work/rootfs"
artifact=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test -x "$root/usr/local/bin/box86"
mkdir -p "$root/opt/dataexpress" "$root/tmp" "$root/home" "$root/dev" "$root/proc" "$root/sys"
cp -a "$artifact/demo-runtime/." "$root/opt/dataexpress/"
printf '127.0.0.1 localhost\n' > "$root/etc/hosts"
chmod 1777 "$root/tmp"
# Strip only DWARF information; keep executable sections and resources intact.
find "$root/usr/lib/i386-linux-gnu/wine/i386-windows" -type f \( -name '*.dll' -o -name '*.exe' \) -exec strip --strip-debug {} +
python3 "$artifact/restore-pe-flags.py"
arm-linux-gnueabihf-strip --strip-debug "$root/usr/local/bin/box86"
du -sh "$root/usr/lib/i386-linux-gnu/wine/i386-windows"
tar -C "$root" --format=gnu --one-file-system \
  --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' --exclude='./run/*' \
  --exclude='./tmp/*' --exclude='./home/*' --exclude='./root/*' \
  --exclude='./var/cache/*' --exclude='./var/lib/apt/lists/*' --exclude='./var/log/*' \
  --exclude='./usr/include/*' --exclude='./usr/share/doc/*' --exclude='./usr/share/man/*' \
  --exclude='./usr/share/locale/*' --exclude='*.a' --exclude='./etc/resolv.conf' \
  -cf - . | zstd -T4 -3 > "$artifact/winlator6/app/src/main/assets/imagefs.tzst"
ls -lh "$artifact/winlator6/app/src/main/assets/imagefs.tzst"
