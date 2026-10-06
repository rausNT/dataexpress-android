#!/bin/sh
set -eu
: "${WINEPREFIX:=/home/xuser/.wine}"
ready="$WINEPREFIX/.dataexpress-tv-ready"
if [ ! -f "$ready" ]; then
    echo 'TV_PHASE Wine initialization started'
    boot_status=0
    /usr/local/bin/box86 /usr/lib/wine/wine /usr/lib/i386-linux-gnu/wine/i386-windows/wineboot.exe --init || boot_status=$?
    # The initial prefix may not yet contain Debian's separate native zlib DLL.
    # Recover only its import-failure status after validating prefix creation.
    if [ "$boot_status" -ne 0 ] && [ "$boot_status" -ne 53 ]; then exit "$boot_status"; fi
    test -f "$WINEPREFIX/system.reg"
    test -f "$WINEPREFIX/drive_c/windows/system32/kernel32.dll"
    test -d "$WINEPREFIX/drive_c/windows/system32"
    cp /usr/i686-w64-mingw32/lib/zlib1.dll "$WINEPREFIX/drive_c/windows/system32/zlib1.dll"
    touch "$ready"
    echo 'TV_PHASE Wine initialization completed'
fi
# Debian packages this native Windows dependency separately from Wine builtins.
cp /usr/i686-w64-mingw32/lib/zlib1.dll "$WINEPREFIX/drive_c/windows/system32/zlib1.dll"
echo 'TV_PHASE DataExpress launch started'
launch_status=0
/usr/local/bin/box86 "$@" || launch_status=$?
# Explorer hands DataExpress to a child and then exits. Keep the PRoot parent
# alive until the Wine server exits, so --kill-on-exit cannot kill that child.
/usr/local/bin/box86 /usr/lib/wine/wineserver32 -w
exit "$launch_status"
