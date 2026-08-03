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
    cmake = root / "app/src/main/cpp/CMakeLists.txt"
    cmake_text = cmake.read_text(encoding="utf-8")
    page_size_flags = (
        '# DataExpress: Android 15+ devices may use 16 KiB memory pages.\n'
        'add_link_options("-Wl,-z,max-page-size=16384")\n'
    )
    anchor = "cmake_minimum_required(VERSION 3.22.1)\n"
    if page_size_flags not in cmake_text:
        if anchor not in cmake_text:
            raise RuntimeError(f"16 KiB linker patch: CMake anchor not found in {cmake}")
        cmake.write_text(
            cmake_text.replace(anchor, anchor + "\n" + page_size_flags, 1),
            encoding="utf-8",
        )

    replace_once(
        root / "app/src/main/cpp/gladiorenderer/src/arb_program.c",
        GENERIC_ATTRIB_OLD,
        GENERIC_ATTRIB_NEW,
        "generic attribute compatibility patch",
    )


def patch_android_application(root: Path) -> None:
    build_file = root / "app/build.gradle"
    replace_once(
        build_file,
        "applicationId 'com.winlator'",
        "applicationId 'ru.mydataexpress.android'",
        "Android application id patch",
    )
    replace_once(
        build_file,
        'versionName "11.1"',
        'versionName "0.1.1-winlator-11.1"',
        "Android version name patch",
    )
    replace_once(
        build_file,
        "implementation 'com.github.luben:zstd-jni:1.5.2-3@aar'",
        "implementation 'com.github.luben:zstd-jni:1.5.7-12@aar'",
        "16 KiB-compatible zstd-jni patch",
    )

    strings_files = sorted((root / "app/src/main/res").glob("values*/strings.xml"))
    patched_names = 0
    for strings_file in strings_files:
        strings_text = strings_file.read_text(encoding="utf-8")
        updated, count = re.subn(
            r'(<string\s+name="app_name"[^>]*>).*?(</string>)',
            r'\1DataExpress Android\2',
            strings_text,
            count=1,
        )
        if count:
            strings_file.write_text(updated, encoding="utf-8")
            patched_names += 1
    if patched_names == 0:
        raise RuntimeError("application name patch: no app_name resources found")

    main_activity = root / "app/src/main/java/com/winlator/MainActivity.java"
    main_text = main_activity.read_text(encoding="utf-8")
    old_install = "RootFSInstaller.installIfNeeded(this);"
    if main_text.count(old_install) != 2:
        raise RuntimeError(f"Expected two RootFS installation calls in {main_activity}")
    main_activity.write_text(
        main_text.replace(old_install, "DataExpressBootstrap.initialize(this);"),
        encoding="utf-8",
    )
    permissions_old = """    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;

        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }"""
    permissions_new = """    private boolean requestAppPermissions() {
        // DataExpress uses the Storage Access Framework and only receives access
        // to a database explicitly selected by the user.
        return false;
    }"""
    replace_once(main_activity, permissions_old, permissions_new, "scoped storage permission patch")
    replace_once(
        main_activity,
        "if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {",
        "if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {",
        "document picker result guard",
    )

    rootfs_installer = root / "app/src/main/java/com/winlator/xenvironment/RootFSInstaller.java"
    replace_once(
        rootfs_installer,
        "import com.winlator.MainActivity;",
        "import com.winlator.MainActivity;\nimport com.winlator.DataExpressBootstrap;",
        "RootFS diagnostics import",
    )
    replace_once(
        rootfs_installer,
        "Executors.newSingleThreadExecutor().execute(() -> {\n            clearRootDir(rootDir);",
        "Executors.newSingleThreadExecutor().execute(() -> {\n            try {\n                clearRootDir(rootDir);",
        "RootFS extraction guard start",
    )
    replace_once(
        rootfs_installer,
        "            dialog.closeOnUiThread();\n        });\n    }\n\n    public static void installIfNeeded",
        "            dialog.closeOnUiThread();\n            }\n            catch (Throwable error) {\n                dialog.closeOnUiThread();\n                DataExpressBootstrap.reportBackgroundFailure(activity, \"Не удалось распаковать среду Winlator\", error);\n            }\n        });\n    }\n\n    public static void installIfNeeded",
        "RootFS extraction guard end",
    )

    xserver = root / "app/src/main/java/com/winlator/XServerDisplayActivity.java"
    exit_old = """    private void exit() {
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        Intent intent = getIntent();
        if (intent.hasExtra(\"exec_path\")) {"""
    exit_new = """    private void exit() {
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        Intent intent = getIntent();
        if (intent.getBooleanExtra(\"dataexpress_mode\", false)) {
            DataExpressBootstrap.finishAndSync(this);
            return;
        }
        if (intent.hasExtra(\"exec_path\")) {"""
    replace_once(xserver, exit_old, exit_new, "DataExpress exit synchronization patch")

    args_old = """            if (intent.hasExtra(\"exec_path\")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra(\"exec_path\"), container);

                if (execPath.endsWith(\".lnk\")) {
                    cmdArgs = \"\\\"\"+execPath+\"\\\"\";
                    execPath = null;
                }
            }"""
    args_new = """            if (intent.hasExtra(\"exec_path\")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra(\"exec_path\"), container);
                String explicitArgs = intent.getStringExtra(\"exec_args\");
                if (explicitArgs != null && !explicitArgs.isEmpty()) execArgs = \" \"+explicitArgs;

                if (execPath.endsWith(\".lnk\")) {
                    cmdArgs = \"\\\"\"+execPath+\"\\\"\"+execArgs;
                    execPath = null;
                }
            }"""
    replace_once(xserver, args_old, args_new, "explicit executable arguments patch")

    manifest = root / "app/src/main/AndroidManifest.xml"
    manifest_text = manifest.read_text(encoding="utf-8")
    manifest_text = manifest_text.replace('android:appCategory="game"', 'android:appCategory="productivity"', 1)
    manifest_text = manifest_text.replace('android:isGame="true"', 'android:isGame="false"', 1)
    manifest_text = manifest_text.replace(
        'android:authorities="com.winlator.FileProvider"',
        'android:authorities="${applicationId}.FileProvider"',
        1,
    )
    launcher_filter = """            <intent-filter>
                <action android:name=\"android.intent.action.MAIN\"/>
                <category android:name=\"android.intent.category.LAUNCHER\"/>
            </intent-filter>"""
    database_filters = launcher_filter + """

            <intent-filter>
                <action android:name=\"android.intent.action.VIEW\"/>
                <category android:name=\"android.intent.category.DEFAULT\"/>
                <category android:name=\"android.intent.category.BROWSABLE\"/>
                <data android:scheme=\"content\" android:mimeType=\"application/octet-stream\"/>
                <data android:scheme=\"content\" android:mimeType=\"application/x-firebird\"/>
            </intent-filter>
            <intent-filter>
                <action android:name=\"android.intent.action.VIEW\"/>
                <category android:name=\"android.intent.category.DEFAULT\"/>
                <category android:name=\"android.intent.category.BROWSABLE\"/>
                <data android:scheme=\"file\" android:pathPattern=\".*\\\\.DXDB\"/>
                <data android:scheme=\"file\" android:pathPattern=\".*\\\\.dxdb\"/>
                <data android:scheme=\"file\" android:pathPattern=\".*\\\\.FDB\"/>
                <data android:scheme=\"file\" android:pathPattern=\".*\\\\.fdb\"/>
            </intent-filter>

            <meta-data
                android:name=\"android.app.shortcuts\"
                android:resource=\"@xml/dataexpress_shortcuts\"/>"""
    if launcher_filter not in manifest_text:
        raise RuntimeError(f"Launcher intent filter not found in {manifest}")
    manifest.write_text(manifest_text.replace(launcher_filter, database_filters, 1), encoding="utf-8")


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
    patch_android_application(root)
    moved = relocate_imported_jni_libraries(root)
    print(f"Patched Winlator source: {root}")
    print(f"Relocated {len(moved)} CMake-imported JNI libraries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
