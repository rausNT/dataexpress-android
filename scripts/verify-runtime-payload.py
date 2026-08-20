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
FIREBIRD_WINE_MODULES = {
    "fb5/fbclient.dll": (1827840, "4869f96ee2faae94b883c05a81ebe9b573b5465788d0109815ec900c53d605f2"),
    "fb5/intl/fbintl.dll": (1067008, "8c92a8c742759c5b787e8ca16b840a7383e7b674f9261deca9bcc98fb886375b"),
    "fb5/plugins/chacha.dll": (392704, "fbc16fc26155b6b3faa970285d6be69defea4ab024a8ebc5cb2ad4ae2a8de2e6"),
    "fb5/plugins/engine13.dll": (8262656, "9c44d86174da80dfaaf86955e96e1c7beb2288ed17f4bba15e49bc4ae6e1d261"),
}


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

    invalid_firebird = []
    for relative, (expected_size, expected_hash) in FIREBIRD_WINE_MODULES.items():
        module = root / relative
        if (
            not module.is_file()
            or module.stat().st_size != expected_size
            or sha256(module) != expected_hash
        ):
            invalid_firebird.append(relative)
    if invalid_firebird:
        raise SystemExit(
            "Payload does not contain the verified Firebird 5.0.3 Wine build: "
            + ", ".join(invalid_firebird)
        )

    manifest = {
        "schemaVersion": 2,
        "component": "DataExpress",
        "archivePathStyle": "posix",
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
