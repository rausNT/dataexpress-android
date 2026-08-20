#!/usr/bin/env python3
"""Apply deterministic build fixes to the pinned Winlator source tree."""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


UPSTREAM_APPLICATION_ID = "com.winlator"
DATAEXPRESS_APPLICATION_ID = "com.dataexpr"

if len(UPSTREAM_APPLICATION_ID) != len(DATAEXPRESS_APPLICATION_ID):
    raise RuntimeError("DataExpress application id must match Winlator id length for ELF path patching")


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

    native_paths = {
        "app/src/main/cpp/winlator/include/winlator.h": (
            "/data/data/com.winlator/cache",
            "/data/data/com.dataexpr/cache",
        ),
        "app/src/main/cpp/vortekrenderer/include/vortek.h": (
            "/data/data/com.winlator/files/rootfs/tmp/.vortek/V0",
            "/data/data/com.dataexpr/files/rootfs/tmp/.vortek/V0",
        ),
        "app/src/main/cpp/gladiorenderer/include/gladio.h": (
            "/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0",
            "/data/data/com.dataexpr/files/rootfs/tmp/.X11-unix/X0",
        ),
    }
    for relative, (old, new) in native_paths.items():
        replace_once(root / relative, old, new, "native application data path patch")


def patch_android_application(root: Path) -> None:
    build_file = root / "app/build.gradle"
    replace_once(
        build_file,
        "applicationId 'com.winlator'",
        f"applicationId '{DATAEXPRESS_APPLICATION_ID}'",
        "Android application id patch",
    )
    replace_once(
        build_file,
        'versionName "11.1"',
        'versionName "0.1.5-preview.1-winlator-11.1"',
        "Android version name patch",
    )
    replace_once(
        build_file,
        "versionCode 28",
        "versionCode 31",
        "Android version code patch",
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

    replace_once(
        root / "app/src/main/java/com/winlator/core/AppUtils.java",
        'public static final String INTERNAL_STORAGE = "/data/data/com.winlator/storage";',
        'public static final String INTERNAL_STORAGE = "/data/data/com.dataexpr/storage";',
        "internal storage application path patch",
    )
    replace_once(
        root / "app/src/main/java/com/winlator/core/FileUtils.java",
        'FileProvider.getUriForFile(activity, "com.winlator.FileProvider", file)',
        'FileProvider.getUriForFile(activity, "com.dataexpr.FileProvider", file)',
        "FileProvider authority patch",
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
    replace_once(
        xserver,
        "    private String screenEffectProfile;",
        "    private String screenEffectProfile;\n"
        "    private boolean dataExpressWindowSeen;\n"
        "    private boolean dataExpressSessionFinishing;\n"
        "    private boolean dataExpressWindowWatchdogOffered;\n"
        "    private int dataExpressMappedWindowCount;",
        "DataExpress window lifecycle fields",
    )
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

    setup_old = """        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });"""
    setup_new = """        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                DataExpressDiagnostics.record(this, "xserver.setup.start", null, null);
                if (!isGenerateWineprefix()) {
                    DataExpressDiagnostics.record(this, "xserver.wine-files.start", null, null);
                    setupWineSystemFiles();
                    DataExpressDiagnostics.record(this, "xserver.graphics.start", graphicsDriver[0]+","+graphicsDriver[1], null);
                    extractGraphicsDriverFiles();
                    DataExpressDiagnostics.record(this, "xserver.audio.start", audioDriver, null);
                    changeWineAudioDriver();
                }
                DataExpressDiagnostics.record(this, "xserver.environment.start", null, null);
                setupXEnvironment();
                DataExpressDiagnostics.record(this, "xserver.environment.ready", null, null);
                scheduleDataExpressWindowWatchdog();
                DataExpressDiagnostics.flush(this);
            }
            catch (Throwable error) {
                DataExpressDiagnostics.record(this, "xserver.setup.failure", null, error);
                DataExpressDiagnostics.flush(this);
                preloaderDialog.closeOnUiThread();
                runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle("DataExpress: ошибка запуска Wine")
                    .setMessage(error.getClass().getSimpleName()+": "+error.getMessage())
                    .setPositiveButton("Закрыть", (dialog, which) -> finish())
                    .show());
            }
        });"""
    replace_once(xserver, setup_old, setup_new, "XServer staged diagnostics patch")

    replace_once(
        xserver,
        """            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {""",
        """            @Override
            public void onMapWindow(Window window) {
                if (isDataExpressMode() && window.isRenderable()
                    && !window.getClassName().isEmpty()) {
                    if (dataExpressMappedWindowCount < 12) {
                        DataExpressDiagnostics.record(XServerDisplayActivity.this, "x11.window.map",
                            "class=" + window.getClassName()
                                + "; index=" + dataExpressMappedWindowCount,
                            null);
                    }
                    dataExpressMappedWindowCount++;
                    if (isDataExpressWindow(window)) {
                        dataExpressWindowSeen = true;
                        DataExpressDiagnostics.record(XServerDisplayActivity.this, "dataexpress.window.ready",
                            "mappedWindows=" + dataExpressMappedWindowCount, null);
                    }
                }
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {""",
        "DataExpress window map tracking",
    )
    replace_once(
        xserver,
        """            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }""",
        """            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
                if (isDataExpressMode() && dataExpressWindowSeen && isDataExpressWindow(window)) {
                    runOnUiThread(() -> xServerView.postDelayed(() -> {
                        if (!hasVisibleDataExpressWindow(xServer.windowManager.rootWindow)) {
                            finishDataExpressSession(0,
                                "Окно DataExpress закрыто. Последний сеанс завершён нормально.");
                        }
                    }, 1000));
                }
            }""",
        "DataExpress last-window exit tracking",
    )

    replace_once(
        xserver,
        """        boolean enableLogs = preferences.getBoolean(\"enable_wine_debug\", false) || preferences.getInt(\"box64_logs\", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));""",
        """        boolean enableLogs = preferences.getBoolean(\"enable_wine_debug\", false) || preferences.getInt(\"box64_logs\", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        if (getIntent().getBooleanExtra(\"dataexpress_mode\", false)) {
            DataExpressProcessTrace.reset();
            ProcessHelper.addDebugCallback(DataExpressProcessTrace::onLine);
        }""",
        "DataExpress Wine output capture patch",
    )

    replace_once(
        xserver,
        '''        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");''',
        '''        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");
        if (getIntent().getBooleanExtra("dataexpress_mode", false)) {
            envVars.put("WINEDEBUG", "+seh,+module,+process");
            DataExpressDiagnostics.record(this, "wine.debug.enabled",
                "channels=seh,module,process", null);
        }''',
        "DataExpress selective Wine diagnostics",
    )

    replace_once(
        xserver,
        """            String guestExecutable = \"wine explorer /desktop=\"+desktopName+\",\"+xServer.screenInfo+\" \"+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);""",
        """            String guestExecutable = \"wine explorer /desktop=\"+desktopName+\",\"+xServer.screenInfo+\" \"+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);
            if (getIntent().getBooleanExtra(\"dataexpress_mode\", false)) {
                DataExpressProcessTrace.command(this, guestExecutable);
            }""",
        "DataExpress Wine command trace patch",
    )

    replace_once(
        xserver,
        """            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));""",
        """            envVars.putAll(container.getEnvVars());
            if (getIntent().getBooleanExtra("dataexpress_mode", false)) {
                envVars.put("FIREBIRD_LOCK", "C:\\\\DataExpress\\\\fb5\\\\lock");
                boolean compatibilityMode = getIntent().getBooleanExtra(
                    "dataexpress_compatibility_mode", false);
                envVars.put("WINEESYNC", compatibilityMode ? "0" : "1");
                DataExpressDiagnostics.record(this, "wine.compatibility",
                    "esync=" + (compatibilityMode ? "0" : "1")
                        + "; screen=" + xServer.screenInfo,
                    null);
            }
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));""",
        "DataExpress private Firebird lock directory",
    )

    replace_once(
        xserver,
        """        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);""",
        """        guestProgramLauncherComponent.setEnvVars(envVars);
        if (getIntent().getBooleanExtra(\"dataexpress_mode\", false)) {
            guestProgramLauncherComponent.setTerminationCallback((status) -> {
                finishDataExpressSession(status,
                    status == 0
                        ? \"Последний сеанс DataExpress завершён нормально.\"
                        : status == 137
                            ? \"X-сессия DataExpress завершена (код 137 / SIGKILL). Код часто появляется после принудительного закрытия чёрного экрана и сам по себе не доказывает нехватку памяти. Подробности сохранены в диагностике.\"
                            : \"DataExpress завершился с ошибкой (код \" + status + \"). Подробности сохранены в диагностике.\");
            });
        }
        else guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);""",
        "DataExpress Wine termination report patch",
    )

    replace_once(
        xserver,
        "    private void setupUI() {",
        """    private boolean isDataExpressMode() {
        return getIntent().getBooleanExtra(\"dataexpress_mode\", false);
    }

    private boolean isDataExpressWindow(Window window) {
        return window != null && \"dataexpress.exe\".equalsIgnoreCase(window.getClassName());
    }

    private boolean hasVisibleDataExpressWindow(Window window) {
        if (isDataExpressWindow(window) && window.attributes.isViewable()) return true;
        for (Window child : window.getChildren()) {
            if (hasVisibleDataExpressWindow(child)) return true;
        }
        return false;
    }

    private void scheduleDataExpressWindowWatchdog() {
        if (!isDataExpressMode()) return;
        runOnUiThread(() -> xServerView.postDelayed(() -> {
            if (dataExpressSessionFinishing || dataExpressWindowSeen
                || dataExpressWindowWatchdogOffered || isFinishing()) return;
            dataExpressWindowWatchdogOffered = true;
            boolean compatibilityMode = getIntent().getBooleanExtra(
                \"dataexpress_compatibility_mode\", false);
            DataExpressDiagnostics.record(this, \"dataexpress.window.timeout\",
                \"afterMs=20000; compatibility=\" + compatibilityMode
                    + \"; mappedWindows=\" + dataExpressMappedWindowCount,
                null);
            DataExpressProcessTrace.snapshot(this, \"window-timeout\");
            DataExpressDiagnostics.flush(this);
            android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(\"Окно DataExpress не появилось\")
                .setMessage(compatibilityMode
                    ? \"Совместимый режим также не показал окно. Можно продолжить ожидание или вернуться на стартовый экран и отправить журнал.\"
                    : \"Wine запущен, но окно DataExpress не появилось за 20 секунд. Повторить запуск без esync и с разрешением 1280×800?\")
                .setNegativeButton(\"Продолжить ожидание\", null);
            if (compatibilityMode) {
                dialog.setPositiveButton(\"На стартовый экран\", (value, which) ->
                    finishDataExpressSession(124,
                        \"Окно DataExpress не появилось даже в совместимом режиме. Отправьте диагностический журнал.\"));
            }
            else {
                dialog.setPositiveButton(\"Повторить совместимо\", (value, which) ->
                    retryDataExpressCompatibility());
            }
            dialog.show();
        }, 20000));
    }

    private synchronized void retryDataExpressCompatibility() {
        if (dataExpressSessionFinishing) return;
        dataExpressSessionFinishing = true;
        getIntent().putExtra(\"dataexpress_compatibility_mode\", true);
        if (container != null && !\"1280x800\".equals(container.getScreenSize())) {
            container.setScreenSize(\"1280x800\");
            container.saveData();
        }
        DataExpressDiagnostics.record(this, \"dataexpress.compatibility.retry\",
            \"esync=0; screen=1280x800\", null);
        DataExpressDiagnostics.flush(this);
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        runOnUiThread(this::recreate);
    }

    private synchronized void finishDataExpressSession(int status, String message) {
        if (dataExpressSessionFinishing) return;
        dataExpressSessionFinishing = true;
        DataExpressProcessTrace.finish(this, status);
        DataExpressBootstrap.stopWineProcesses(this);
        getIntent().putExtra(DataExpressBootstrap.LAST_RESULT_EXTRA, message);
        runOnUiThread(this::exit);
    }

    private void setupUI() {""",
        "DataExpress session completion helpers",
    )

    launcher = root / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
    replace_once(
        launcher,
        "import com.winlator.box64.Box64Preset;",
        "import com.winlator.DataExpressRuntimePaths;\nimport com.winlator.box64.Box64Preset;",
        "runtime path patcher import",
    )
    replace_once(
        launcher,
        "            extractBox64File();\n            copyDefaultBox64RCFile();",
        "            extractBox64File();\n            DataExpressRuntimePaths.patchRuntime(environment.getContext(), environment.getRootFS());\n            copyDefaultBox64RCFile();",
        "Box64 interpreter path patch",
    )
    replace_once(
        launcher,
        '        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;',
        '''        Context context = environment.getContext();
        File packagedBox64 = new File(context.getApplicationInfo().nativeLibraryDir, "libbox64.so");
        File extractedBox64 = new File(rootDir, "/usr/local/bin/box64");
        String box64Executable = packagedBox64.canExecute()
            ? packagedBox64.getAbsolutePath()
            : extractedBox64.getAbsolutePath();
        String command = box64Executable+" "+guestExecutable;''',
        "execute packaged Box64 from Android native library directory",
    )

    process_helper = root / "app/src/main/java/com/winlator/core/ProcessHelper.java"
    replace_once(
        process_helper,
        """        catch (Exception e) {}
        return pid;""",
        """        catch (Exception e) {
            String message = "ProcessHelper.exec failed: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
            synchronized (debugCallbacks) {
                for (Callback<String> callback : debugCallbacks) callback.call(message);
            }
            if (terminationCallback != null) terminationCallback.call(-1);
        }
        return pid;""",
        "report guest process launch failures",
    )

    manifest = root / "app/src/main/AndroidManifest.xml"
    manifest_text = manifest.read_text(encoding="utf-8")
    manifest_text = manifest_text.replace('android:appCategory="game"', 'android:appCategory="productivity"', 1)
    manifest_text = manifest_text.replace('android:isGame="true"', 'android:isGame="false"', 1)
    manifest_text = manifest_text.replace(
        'android:authorities="com.winlator.FileProvider"',
        'android:authorities="${applicationId}.FileProvider"',
        1,
    )
    main_activity = """        <activity android:name=\"com.winlator.MainActivity\"
            android:theme=\"@style/AppThemeDark\"
            android:exported=\"true\"
            android:screenOrientation=\"sensor\"
            android:configChanges=\"keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation\">
            <intent-filter>
                <action android:name=\"android.intent.action.MAIN\"/>
                <category android:name=\"android.intent.category.LAUNCHER\"/>
            </intent-filter>
        </activity>"""
    home_and_main = """        <activity android:name=\"com.winlator.DataExpressHomeActivity\"
            android:theme=\"@style/AppThemeDark\"
            android:exported=\"true\"
            android:screenOrientation=\"sensor\"
            android:launchMode=\"singleTop\"
            android:configChanges=\"keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation\">
            <intent-filter>
                <action android:name=\"android.intent.action.MAIN\"/>
                <category android:name=\"android.intent.category.LAUNCHER\"/>
            </intent-filter>
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
                android:resource=\"@xml/dataexpress_shortcuts\"/>
        </activity>

        <activity android:name=\"com.winlator.MainActivity\"
            android:theme=\"@style/AppThemeDark\"
            android:exported=\"false\"
            android:screenOrientation=\"sensor\"
            android:configChanges=\"keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation\">
        </activity>"""
    if main_activity in manifest_text:
        manifest_text = manifest_text.replace(main_activity, home_and_main, 1)
    elif home_and_main not in manifest_text:
        raise RuntimeError(f"Main activity declaration not found in {manifest}")
    manifest.write_text(manifest_text, encoding="utf-8")


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
            # libc++_shared is required at runtime by liboboe/libfluidsynth but
            # is not linked directly by the midihandler target. Keep the
            # jniLibs copy so AGP packages it, and copy a second copy only for
            # CMake's imported target lookup.
            if name == "libc++_shared.so":
                shutil.copy2(str(source), str(destination))
            else:
                shutil.move(str(source), str(destination))
        elif not destination.exists():
            raise RuntimeError(f"Imported JNI library is missing: {source}")

    duplicates = [
        name for name in imported
        if name != "libc++_shared.so" and (source_dir / name).exists()
    ]
    if duplicates:
        raise RuntimeError(f"JNI libraries still duplicated: {', '.join(duplicates)}")
    return imported


def package_box64_as_native_executable(root: Path) -> Path:
    """Place Box64 in Android's executable native-library directory."""

    archives = sorted((root / "app/src/main/assets/box64").glob("box64-*.tzst"))
    if len(archives) != 1:
        raise RuntimeError(f"Expected exactly one Box64 archive, found {len(archives)}")

    destination = root / "app/src/main/jniLibs/arm64-v8a/libbox64.so"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        subprocess.run(
            ["tar", "-xf", str(archives[0]), "-C", temporary],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        executable = Path(temporary) / "usr/local/bin/box64"
        if not executable.is_file():
            raise RuntimeError(f"Box64 executable not found in {archives[0]}")
        payload = executable.read_bytes()
        old_package = UPSTREAM_APPLICATION_ID.encode("ascii")
        new_package = DATAEXPRESS_APPLICATION_ID.encode("ascii")
        if old_package not in payload:
            raise RuntimeError(f"Upstream package path not found in {archives[0]}")
        destination.write_bytes(payload.replace(old_package, new_package))
    return destination


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
    packaged_box64 = package_box64_as_native_executable(root)
    print(f"Patched Winlator source: {root}")
    print(f"Relocated {len(moved)} CMake-imported JNI libraries")
    print(f"Packaged Box64 native executable: {packaged_box64}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
