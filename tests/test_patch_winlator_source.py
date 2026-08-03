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


if __name__ == "__main__":
    unittest.main()
