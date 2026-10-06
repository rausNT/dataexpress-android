#!/bin/bash
set -euo pipefail
work="${DX_ARM32_WORK:-/root/dataexpress-arm32}"
root="$work/rootfs"
test -f "$root/usr/include/stdlib.h"
if [ ! -d "$work/box86-src" ]; then git clone --depth 1 --branch v0.3.8 https://github.com/ptitSeb/box86.git "$work/box86-src"; fi
test "$(git -C "$work/box86-src" rev-parse HEAD)" = ec278bd7843252d7bfc6e5e91d7074f9c0310de7
python3 - "$root" <<'PY'
import os, sys
from pathlib import Path
root=Path(sys.argv[1])
for base in [root/'usr/lib',root/'usr/include']:
    for parent, dirs, files in os.walk(base):
        for name in files:
            path=Path(parent)/name
            if path.is_symlink():
                target=os.readlink(path)
                if target.startswith('/'):
                    path.unlink()
                    path.symlink_to(os.path.relpath(root/target.lstrip('/'),path.parent))
PY
cmake -S "$work/box86-src" -B "$work/box86-build" -G Ninja \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=arm \
  -DCMAKE_SYSROOT="$root" -DCMAKE_C_COMPILER=arm-linux-gnueabihf-gcc \
  -DCMAKE_C_FLAGS='-std=gnu11 -march=armv7-a -mfpu=neon -mfloat-abi=hard -Wno-error=incompatible-pointer-types' \
  -DCMAKE_ASM_FLAGS='-march=armv7-a -mfpu=neon -mfloat-abi=hard' \
  -DCMAKE_C_STANDARD_LIBRARIES='-lm -ldl -lrt -lpthread -lresolv' \
  -DCMAKE_BUILD_TYPE=Release -DARM_DYNAREC=ON -DNOLOADADDR=ON -DBAD_SIGNAL=ON -DHAVE_TRACE=OFF
cmake --build "$work/box86-build" -j 4
install -m755 "$work/box86-build/box86" "$root/usr/local/bin/box86"
file "$root/usr/local/bin/box86"
arm-linux-gnueabihf-readelf --version-info "$root/usr/local/bin/box86" | tail -10
artifact=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source="$artifact/winlator6/android_sysvshm/android_sysvshm.c"
arm-linux-gnueabihf-gcc --sysroot="$root" -std=gnu99 -shared -fPIC -include string.h "$source" -o "$root/usr/lib/arm-linux-gnueabihf/libandroid-sysvshm.so" -pthread
