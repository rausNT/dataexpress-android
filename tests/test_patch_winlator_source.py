import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "patch-winlator-source.py"
REPO_ROOT = SCRIPT.parent.parent
SPEC = importlib.util.spec_from_file_location("patch_winlator_source", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PatchWinlatorSourceTest(unittest.TestCase):
    def test_legacy_xml_patch_preserves_dataexpress_source_markers(self):
        patch = (
            REPO_ROOT / "patches/dataexpress/legacy-xml-reader.patch"
        ).read_text(encoding="utf-8")
        self.assertIn("procedure TSAXBaseReader.ParseStream", patch)
        self.assertIn("'[__DATAEXPRESS_PARENT__'", patch)
        self.assertIn("'__DATAEXPRESS_PARENT__', '!'", patch)
        self.assertIn("'__DATAEXPRESS_OPTIONAL_PARENT__', '?!'", patch)

        build_script = (
            REPO_ROOT / "scripts/build-dataexpress-windows.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn("fpcmkcfg.exe", build_script)
        self.assertIn("-Recurse -File", build_script)

        verifier = (
            REPO_ROOT / "scripts/verify-runtime-payload.py"
        ).read_text(encoding="utf-8")
        self.assertIn("FIREBIRD_WINE_MODULES", verifier)
        self.assertIn("9c44d86174da80dfaaf86955e96e1c7beb2288ed17f4bba15e49bc4ae6e1d261", verifier)

    def test_external_database_uses_ascii_working_name_and_logs_engine(self):
        bootstrap = (
            REPO_ROOT / "overlay/app/src/main/java/com/winlator/DataExpressBootstrap.java"
        ).read_text(encoding="utf-8")
        self.assertIn('return lower.endsWith(".fdb") ? "database.FDB" : "database.DXDB";', bootstrap)
        self.assertIn('database = new File(demoDir, "DEMO_DB.DXDB");', bootstrap)
        self.assertNotIn("packagedName.renameTo(database)", bootstrap)
        self.assertIn('"database.runtime.select"', bootstrap)
        self.assertIn('"Firebird 2.5" : "Firebird 5"', bootstrap)
        self.assertIn("applyFirebirdWineCompatibility(activity, applicationDir);", bootstrap)
        self.assertIn('"firebird.compat.source-build"', bootstrap)
        self.assertIn('"sourceBuild=firebird-5.0.3-wine-compat', bootstrap)
        self.assertIn('"fb5/plugins/engine13.dll", "8262656"', bootstrap)
        self.assertIn("module[2].equals(sha256File(file))", bootstrap)
        self.assertIn('"; binaryPatches=0;', bootstrap)
        self.assertNotIn("RandomAccessFile", bootstrap)
        self.assertNotIn("queryDosDeviceBranchOffset", bootstrap)

        trace = (
            REPO_ROOT / "overlay/app/src/main/java/com/winlator/DataExpressProcessTrace.java"
        ).read_text(encoding="utf-8")
        self.assertIn('lower.contains("80000100")', trace)
        self.assertIn('lower.contains("c0000096")', trace)
        self.assertIn('lower.contains("dispatch_exception code=")', trace)
        self.assertIn('lower.contains("unimplemented function")', trace)
        self.assertIn('lower.contains("out of memory")', trace)
        self.assertIn('lower.contains("signal 9")', trace)
        self.assertIn('Relevant Wine diagnostics:', trace)

        diagnostics = (
            REPO_ROOT / "overlay/app/src/main/java/com/winlator/DataExpressDiagnostics.java"
        ).read_text(encoding="utf-8")
        self.assertIn("copyToClipboard(Activity activity)", diagnostics)
        self.assertIn("exportToUri(Context context, Uri destination)", diagnostics)
        self.assertIn("Intent.createChooser", diagnostics)
        self.assertIn("shareSnapshot", diagnostics)
        self.assertIn("systemAvailableMemoryMb", diagnostics)
        home = (
            REPO_ROOT / "overlay/app/src/main/java/com/winlator/DataExpressHomeActivity.java"
        ).read_text(encoding="utf-8")
        self.assertIn("SAVE_DIAGNOSTICS_REQUEST", home)
        self.assertIn("offerDiagnosticsAfterFailure", home)
        self.assertIn('lower.contains("код 137")', home)
        self.assertIn("DataExpressDiagnostics.share(this)", home)

    def test_dataexpress_profile_keeps_windows_services_enabled(self):
        profile = json.loads(
            (REPO_ROOT / "overlay/app/src/main/assets/dataexpress/profile.json").read_text(encoding="utf-8")
        )
        self.assertEqual(profile["startupSelection"], 1)
        self.assertEqual(profile["screenSize"], "1280x720")
        self.assertEqual(profile["graphicsDriver"], "vortek,gladio")
        self.assertEqual(profile["dxwrapper"], "dxvk")
        self.assertEqual(profile["box64Preset"], "INTERMEDIATE")
        bootstrap = (
            REPO_ROOT / "overlay/app/src/main/java/com/winlator/DataExpressBootstrap.java"
        ).read_text(encoding="utf-8")
        self.assertIn("Container.STARTUP_SELECTION_ESSENTIAL", bootstrap)

    def test_patches_native_targets_for_16k_pages(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cmake = root / "app/src/main/cpp/CMakeLists.txt"
            gladiorenderer = root / "app/src/main/cpp/gladiorenderer/src/arb_program.c"
            winlator_header = root / "app/src/main/cpp/winlator/include/winlator.h"
            vortek_header = root / "app/src/main/cpp/vortekrenderer/include/vortek.h"
            gladio_header = root / "app/src/main/cpp/gladiorenderer/include/gladio.h"
            cmake.parent.mkdir(parents=True)
            gladiorenderer.parent.mkdir(parents=True)
            cmake.write_text(
                "cmake_minimum_required(VERSION 3.22.1)\n\nadd_subdirectory(winlator)\n",
                encoding="utf-8",
            )
            gladiorenderer.write_text(MODULE.GENERIC_ATTRIB_OLD, encoding="utf-8")
            winlator_header.parent.mkdir(parents=True)
            vortek_header.parent.mkdir(parents=True)
            gladio_header.parent.mkdir(parents=True, exist_ok=True)
            winlator_header.write_text('/data/data/com.winlator/cache', encoding="utf-8")
            vortek_header.write_text('/data/data/com.winlator/files/rootfs/tmp/.vortek/V0', encoding="utf-8")
            gladio_header.write_text('/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0', encoding="utf-8")

            MODULE.patch_native_sources(root)
            MODULE.patch_native_sources(root)

            patched = cmake.read_text(encoding="utf-8")
            self.assertEqual(patched.count("max-page-size=16384"), 1)
            self.assertIn("add_link_options", patched)
            self.assertIn("/data/data/com.dataexpr/cache", winlator_header.read_text(encoding="utf-8"))

    def test_relocates_every_imported_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cmake = root / "app/src/main/cpp/midihandler/CMakeLists.txt"
            jni = root / "app/src/main/jniLibs/arm64-v8a"
            cmake.parent.mkdir(parents=True)
            jni.mkdir(parents=True)
            cmake.write_text(
                "set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/arm64-v8a)\n"
                "set_target_properties(foo PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/libfoo.so)\n"
                "set_target_properties(bar PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/libbar.so)\n",
                encoding="utf-8",
            )
            (jni / "libfoo.so").write_bytes(b"foo")
            (jni / "libbar.so").write_bytes(b"bar")
            (jni / "libunrelated.so").write_bytes(b"keep")

            moved = MODULE.relocate_imported_jni_libraries(root)

            self.assertEqual(moved, ["libbar.so", "libfoo.so"])
            prebuilt = root / "app/src/main/cpp/midihandler/prebuilt/arm64-v8a"
            self.assertEqual((prebuilt / "libfoo.so").read_bytes(), b"foo")
            self.assertEqual((prebuilt / "libbar.so").read_bytes(), b"bar")
            self.assertTrue((jni / "libunrelated.so").exists())
            self.assertFalse((jni / "libfoo.so").exists())
            self.assertIn("${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/arm64-v8a", cmake.read_text(encoding="utf-8"))

    def test_keeps_libcpp_in_jnilibs_for_apk_packaging(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cmake = root / "app/src/main/cpp/midihandler/CMakeLists.txt"
            jni = root / "app/src/main/jniLibs/arm64-v8a"
            cmake.parent.mkdir(parents=True)
            jni.mkdir(parents=True)
            cmake.write_text(
                "set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/arm64-v8a)\n"
                "set_target_properties(cxx PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/libc++_shared.so)\n",
                encoding="utf-8",
            )
            (jni / "libc++_shared.so").write_bytes(b"cxx-runtime")

            MODULE.relocate_imported_jni_libraries(root)

            prebuilt = root / "app/src/main/cpp/midihandler/prebuilt/arm64-v8a/libc++_shared.so"
            self.assertEqual(prebuilt.read_bytes(), b"cxx-runtime")
            self.assertEqual((jni / "libc++_shared.so").read_bytes(), b"cxx-runtime")

    def test_patches_dataexpress_entrypoints_and_branding(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = {
                "app/build.gradle": (
                    "applicationId 'com.winlator'\nversionCode 28\nversionName \"11.1\"\n"
                    "implementation 'com.github.luben:zstd-jni:1.5.2-3@aar'\n"
                ),
                "app/src/main/res/values/strings.xml": '<string name="app_name">Winlator</string>\n',
                "app/src/main/res/values-ru/strings.xml": '<string name="app_name">Winlator</string>\n',
                "app/src/main/java/com/winlator/MainActivity.java": (
                    "if (!requestAppPermissions()) RootFSInstaller.installIfNeeded(this);\n"
                    "RootFSInstaller.installIfNeeded(this);\n"
                    "    private boolean requestAppPermissions() {\n"
                    "        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&\n"
                    "            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;\n\n"
                    "        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};\n"
                    "        ActivityCompat.requestPermissions(this, permissions, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);\n"
                    "        return true;\n"
                    "    }\n"
                    "if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {\n"
                ),
                "app/src/main/java/com/winlator/XServerDisplayActivity.java": """    private String screenEffectProfile;

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    mapped();
                }
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }
        });

    private void exit() {
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            restart();
        }
    }

            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);

                if (execPath.endsWith(".lnk")) {
                    cmdArgs = "\\\""+execPath+"\\\"";
                    execPath = null;
                }
            }

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });

        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));

            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+" "+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
        environment.addComponent(guestProgramLauncherComponent);

    private void setupUI() {
    }
""",
                "app/src/main/java/com/winlator/core/AppUtils.java": (
                    'public static final String INTERNAL_STORAGE = "/data/data/com.winlator/storage";\n'
                ),
                "app/src/main/java/com/winlator/core/FileUtils.java": (
                    'FileProvider.getUriForFile(activity, "com.winlator.FileProvider", file);\n'
                ),
                "app/src/main/java/com/winlator/core/ProcessHelper.java": (
                    "        catch (Exception e) {}\n"
                    "        return pid;\n"
                ),
                "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java": (
                    "import com.winlator.box64.Box64Preset;\n"
                    "            extractBox64File();\n"
                    "            copyDefaultBox64RCFile();\n"
                    '        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;\n'
                ),
                "app/src/main/java/com/winlator/xenvironment/RootFSInstaller.java": """import com.winlator.MainActivity;
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            dialog.closeOnUiThread();
        });
    }

    public static void installIfNeeded
""",
                "app/src/main/AndroidManifest.xml": """<application android:appCategory="game" android:isGame="true">
        <activity android:name="com.winlator.MainActivity"
            android:theme="@style/AppThemeDark"
            android:exported="true"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <provider android:authorities="com.winlator.FileProvider"/>
</application>
""",
            }
            for relative, contents in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding="utf-8")

            MODULE.patch_android_application(root)

            self.assertIn("com.dataexpr", (root / "app/build.gradle").read_text(encoding="utf-8"))
            self.assertIn("versionCode 31", (root / "app/build.gradle").read_text(encoding="utf-8"))
            self.assertIn("0.1.5-preview.1-winlator-11.1", (root / "app/build.gradle").read_text(encoding="utf-8"))
            self.assertIn("zstd-jni:1.5.7-12", (root / "app/build.gradle").read_text(encoding="utf-8"))
            self.assertIn("DataExpress Android", (root / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8"))
            self.assertIn("DataExpress Android", (root / "app/src/main/res/values-ru/strings.xml").read_text(encoding="utf-8"))
            main = (root / "app/src/main/java/com/winlator/MainActivity.java").read_text(encoding="utf-8")
            self.assertEqual(main.count("DataExpressBootstrap.initialize(this);"), 2)
            self.assertNotIn("ActivityCompat.requestPermissions", main)
            self.assertIn("resultCode == Activity.RESULT_OK && data != null", main)
            rootfs = (root / "app/src/main/java/com/winlator/xenvironment/RootFSInstaller.java").read_text(encoding="utf-8")
            self.assertIn("catch (Throwable error)", rootfs)
            xserver = (root / "app/src/main/java/com/winlator/XServerDisplayActivity.java").read_text(encoding="utf-8")
            self.assertIn("DataExpressBootstrap.finishAndSync(this);", xserver)
            self.assertIn('getStringExtra("exec_args")', xserver)
            self.assertIn('"xserver.setup.failure"', xserver)
            self.assertIn("DataExpressProcessTrace::onLine", xserver)
            self.assertIn("DataExpressProcessTrace.finish(this, status)", xserver)
            self.assertIn('envVars.put("WINEDEBUG", "+seh,+module,+process")', xserver)
            self.assertIn('DataExpressProcessTrace.snapshot(this, "window-timeout")', xserver)
            self.assertIn("DataExpressBootstrap.stopWineProcesses(this)", xserver)
            self.assertIn('envVars.put("FIREBIRD_LOCK", "C:\\\\DataExpress\\\\fb5\\\\lock")', xserver)
            self.assertIn("hasVisibleDataExpressWindow", xserver)
            self.assertIn("dataExpressSessionFinishing", xserver)
            self.assertIn("код 137 / SIGKILL", xserver)
            self.assertIn("scheduleDataExpressWindowWatchdog", xserver)
            self.assertIn('"dataexpress.window.timeout"', xserver)
            self.assertIn("retryDataExpressCompatibility", xserver)
            self.assertIn('envVars.put("WINEESYNC", compatibilityMode ? "0" : "1")', xserver)
            self.assertIn('"x11.window.map"', xserver)
            launcher = (root / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java").read_text(encoding="utf-8")
            self.assertIn("DataExpressRuntimePaths.patchRuntime", launcher)
            self.assertIn('nativeLibraryDir, "libbox64.so"', launcher)
            self.assertIn("packagedBox64.canExecute()", launcher)
            app_utils = (root / "app/src/main/java/com/winlator/core/AppUtils.java").read_text(encoding="utf-8")
            self.assertIn("/data/data/com.dataexpr/storage", app_utils)
            process_helper = (root / "app/src/main/java/com/winlator/core/ProcessHelper.java").read_text(encoding="utf-8")
            self.assertIn("ProcessHelper.exec failed", process_helper)
            manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
            self.assertIn("android.intent.action.VIEW", manifest)
            self.assertIn("@xml/dataexpress_shortcuts", manifest)
            self.assertIn("com.winlator.DataExpressHomeActivity", manifest)
            self.assertIn('android:exported="false"', manifest)
            self.assertIn("${applicationId}.FileProvider", manifest)

    def test_packages_box64_in_native_library_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "app/src/main/assets/box64/box64-0.4.0.tzst"
            archive.parent.mkdir(parents=True)
            payload = b"/data/data/com.winlator/files/rootfs/lib/ld-linux-aarch64.so.1"
            with tarfile.open(archive, "w") as bundle:
                info = tarfile.TarInfo("usr/local/bin/box64")
                info.size = len(payload)
                info.mode = 0o755
                bundle.addfile(info, io.BytesIO(payload))

            packaged = MODULE.package_box64_as_native_executable(root)

            self.assertEqual(packaged.name, "libbox64.so")
            self.assertNotIn(b"com.winlator", packaged.read_bytes())
            self.assertIn(b"com.dataexpr", packaged.read_bytes())

    def test_diagnostics_report_real_heap_headroom(self):
        diagnostics = (
            REPO_ROOT / "overlay/app/src/main/java/com/winlator/DataExpressDiagnostics.java"
        ).read_text(encoding="utf-8")
        self.assertIn('"appAllocatedMemoryMb"', diagnostics)
        self.assertIn('"appUsedMemoryMb"', diagnostics)
        self.assertIn('"appHeadroomMemoryMb"', diagnostics)


if __name__ == "__main__":
    unittest.main()
