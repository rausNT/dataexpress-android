#!/usr/bin/env python3
"""Create a deterministic Android-compatible DataExpress payload ZIP."""

from __future__ import annotations

import argparse
import os
import zipfile
from pathlib import Path


ZIP_TIMESTAMP = (2020, 1, 1, 0, 0, 0)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("payload", type=Path, help="staged payload directory")
    parser.add_argument("archive", type=Path, help="destination ZIP")
    args = parser.parse_args()

    root = args.payload.resolve()
    archive = args.archive.resolve()
    if not root.is_dir():
        raise SystemExit(f"Payload directory does not exist: {root}")

    files = sorted(path for path in root.rglob("*") if path.is_file())
    if not files:
        raise SystemExit(f"Payload directory is empty: {root}")

    archive.parent.mkdir(parents=True, exist_ok=True)
    temporary = archive.with_suffix(archive.suffix + ".tmp")
    try:
        with zipfile.ZipFile(
            temporary,
            "w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
            allowZip64=True,
        ) as output:
            for source in files:
                relative = source.relative_to(root).as_posix()
                if "\\" in relative or relative.startswith("/") or ".." in Path(relative).parts:
                    raise SystemExit(f"Unsafe payload path: {relative}")
                info = zipfile.ZipInfo(relative, date_time=ZIP_TIMESTAMP)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o100644 & 0xFFFF) << 16
                with source.open("rb") as input_file, output.open(info, "w", force_zip64=True) as target:
                    while chunk := input_file.read(1024 * 1024):
                        target.write(chunk)

        with zipfile.ZipFile(temporary, "r") as check:
            names = check.namelist()
            if len(names) != len(files) or any("\\" in name for name in names):
                raise SystemExit("Archive contains invalid or duplicate paths")
            bad = check.testzip()
            if bad is not None:
                raise SystemExit(f"Corrupt ZIP entry: {bad}")

        os.replace(temporary, archive)
    finally:
        if temporary.exists():
            temporary.unlink()

    print(f"Packaged {len(files)} files into {archive}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
