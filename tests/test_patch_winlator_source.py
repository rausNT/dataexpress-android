import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "patch-winlator-source.py"
SPEC = importlib.util.spec_from_file_location("patch_winlator_source", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PatchWinlatorSourceTest(unittest.TestCase):
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

    def test_patches_dataexpress_entrypoints_and_branding(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = {
                "app/build.gradle": "applicationId 'com.winlator'\nversionName \"11.1\"\n",
                "app/src/main/res/values/strings.xml": '<string name="app_name">Winlator</string>\n',
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
                "app/src/main/java/com/winlator/XServerDisplayActivity.java": """    private void exit() {
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
""",
                "app/src/main/AndroidManifest.xml": """<application android:appCategory="game" android:isGame="true">
        <activity>
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

            self.assertIn("ru.mydataexpress.android", (root / "app/build.gradle").read_text(encoding="utf-8"))
            main = (root / "app/src/main/java/com/winlator/MainActivity.java").read_text(encoding="utf-8")
            self.assertEqual(main.count("DataExpressBootstrap.initialize(this);"), 2)
            self.assertNotIn("ActivityCompat.requestPermissions", main)
            self.assertIn("resultCode == Activity.RESULT_OK && data != null", main)
            xserver = (root / "app/src/main/java/com/winlator/XServerDisplayActivity.java").read_text(encoding="utf-8")
            self.assertIn("DataExpressBootstrap.finishAndSync(this);", xserver)
            self.assertIn('getStringExtra("exec_args")', xserver)
            manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
            self.assertIn("android.intent.action.VIEW", manifest)
            self.assertIn("@xml/dataexpress_shortcuts", manifest)
            self.assertIn("${applicationId}.FileProvider", manifest)


if __name__ == "__main__":
    unittest.main()
