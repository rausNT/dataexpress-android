#!/usr/bin/env python3
"""Apply deterministic build fixes to the pinned Winlator source tree."""

from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path


GENERIC_ATTRIB_OLD = (
    "                                if (IntArray_indexOf(&asmSource->genericAttribs, index) == -1) "
    "IntArray_add(&asmSource->genericAttribs, index);"
)
GENERIC_ATTRIB_NEW = """                                bool hasGenericAttrib = false;
                                for (int attribIndex = 0; attribIndex < asmSource->genericAttribs.size; attribIndex++) {
                                    if (asmSource->genericAttribs.values[attribIndex] == index) {
                                        hasGenericAttrib = true;
                                        break;
                                    }
                                }
                                if (!hasGenericAttrib) IntArray_add(&asmSource->genericAttribs, index);"""


def replace_once(path: Path, old: str, new: str, description: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        if new in text:
            return
        raise RuntimeError(f"{description}: expected source text not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_native_sources(root: Path) -> None:
    replace_once(
        root / "app/src/main/cpp/gladiorenderer/src/arb_program.c",
        GENERIC_ATTRIB_OLD,
        GENERIC_ATTRIB_NEW,
        "generic attribute compatibility patch",
    )


def relocate_imported_jni_libraries(root: Path) -> list[str]:
    """Move CMake-imported libraries outside jniLibs to prevent AGP duplicates.

    Android Gradle Plugin packages both files from jniLibs and IMPORTED CMake
    targets. Keeping the imported files in jniLibs therefore makes
    mergeDebugNativeLibs see every linked library twice.
    """

    cmake_file = root / "app/src/main/cpp/midihandler/CMakeLists.txt"
    cmake = cmake_file.read_text(encoding="utf-8")
    old_dir = "set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/arm64-v8a)"
    new_dir = "set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/arm64-v8a)"
    if old_dir in cmake:
        cmake = cmake.replace(old_dir, new_dir, 1)
        cmake_file.write_text(cmake, encoding="utf-8")
    elif new_dir not in cmake:
        raise RuntimeError(f"JNI library directory declaration not found in {cmake_file}")

    imported = sorted(set(re.findall(r"\$\{JNILIBS_DIR\}/([^\s)]+\.so)", cmake)))
    if not imported:
        raise RuntimeError(f"No imported JNI libraries discovered in {cmake_file}")

    source_dir = root / "app/src/main/jniLibs/arm64-v8a"
    destination_dir = root / "app/src/main/cpp/midihandler/prebuilt/arm64-v8a"
    destination_dir.mkdir(parents=True, exist_ok=True)

    for name in imported:
        source = source_dir / name
        destination = destination_dir / name
        if source.exists():
            shutil.move(str(source), str(destination))
        elif not destination.exists():
            raise RuntimeError(f"Imported JNI library is missing: {source}")

    duplicates = [name for name in imported if (source_dir / name).exists()]
    if duplicates:
        raise RuntimeError(f"JNI libraries still duplicated: {', '.join(duplicates)}")
    return imported


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="checked-out winlator-app source tree")
    args = parser.parse_args()

    root = args.source.resolve()
    if not (root / "app/build.gradle").is_file():
        raise SystemExit(f"Not a Winlator source tree: {root}")

    patch_native_sources(root)
    moved = relocate_imported_jni_libraries(root)
    print(f"Patched Winlator source: {root}")
    print(f"Relocated {len(moved)} CMake-imported JNI libraries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
