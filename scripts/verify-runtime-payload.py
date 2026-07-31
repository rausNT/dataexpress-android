#!/usr/bin/env python3
"""Validate a staged DataExpress runtime before packaging."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

DENIED_NAMES = {
    "padeguс.dll".casefold(),  # Cyrillic-safe defensive spelling
    "padeguс64.dll".casefold(),
    "padeguC.dll".casefold(),
    "padeguc.dll".casefold(),
}
REQUIRED_FILES = {"DataExpress.exe", "LICENSE.txt", "NOTICE.txt"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("payload", type=Path)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()

    root = args.payload.resolve()
    if not root.is_dir():
        raise SystemExit(f"Payload directory does not exist: {root}")

    files = [path for path in root.rglob("*") if path.is_file()]
    names = {path.name for path in files}
    missing = sorted(REQUIRED_FILES - names)
    if missing:
        raise SystemExit(f"Missing required files: {', '.join(missing)}")

    denied = [path for path in files if path.name.casefold() in DENIED_NAMES]
    if denied:
        formatted = "\n".join(str(path.relative_to(root)) for path in denied)
        raise SystemExit(f"Denied proprietary/unchecked files found:\n{formatted}")

    manifest = {
        "schemaVersion": 1,
        "component": "DataExpress",
        "sourceRepository": "https://github.com/dxbit/dataexpress",
        "sourceRevision": args.source_revision,
        "files": [
            {
                "path": path.relative_to(root).as_posix(),
                "size": path.stat().st_size,
                "sha256": sha256(path),
            }
            for path in sorted(files)
        ],
    }
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Verified {len(files)} files; manifest written to {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
