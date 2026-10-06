import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SERVICE = Path(__file__).parents[1] / "overlay/app/src/main/java/com/winlator/DataExpressStartupServices.java"


class StartupServicesTest(unittest.TestCase):
    def test_rpcss_is_available_on_demand_without_enabling_other_services(self):
        self.assertIsNotNone(shutil.which("javac"))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = {
                "com/winlator/DataExpressStartupServices.java": SERVICE.read_text(encoding="utf-8"),
                "android/content/Context.java": "package android.content; public class Context {}",
                "com/winlator/container/Container.java": """package com.winlator.container;
import java.io.File;
public class Container { public File getRootDir() { return new File("test-prefix"); } }
""",
                "com/winlator/DataExpressDiagnostics.java": """package com.winlator;
import android.content.Context;
public class DataExpressDiagnostics { public static void record(Context c, String e, String d, Throwable t) {} }
""",
                "com/winlator/core/WineRegistryEditor.java": """package com.winlator.core;
import java.io.File;
public class WineRegistryEditor implements AutoCloseable {
    public static Integer value;
    public static String alias, editedKey;
    public static int writes;
    public WineRegistryEditor(File f) {}
    public void setCreateKeyIfNotExist(boolean create) { if (create) throw new AssertionError("create missing service"); }
    public String getSymlinkValue(String k, String n) { return alias; }
    public Integer getDwordValue(String k, String n) { return value; }
    public void setDwordValue(String k, String n, int v) {
        if (!k.endsWith("\\\\Services\\\\RpcSs") || !n.equals("Start")) throw new AssertionError("wrong service");
        editedKey = k; value = v; writes++;
    }
    public void close() {}
}
""",
                "ServiceCheck.java": """import com.winlator.DataExpressStartupServices;
import com.winlator.container.Container;
import com.winlator.core.WineRegistryEditor;
public class ServiceCheck {
    private static void check(Integer before, Integer after, String alias, int writes) {
        WineRegistryEditor.value = before; WineRegistryEditor.alias = alias; WineRegistryEditor.writes = 0;
        DataExpressStartupServices.configure(new android.content.Context(), new Container());
        if (!java.util.Objects.equals(WineRegistryEditor.value, after) || WineRegistryEditor.writes != writes)
            throw new AssertionError("start=" + before);
    }
    public static void main(String[] args) {
        check(4, 3, null, 1);
        if (!WineRegistryEditor.editedKey.equals("System\\\\CurrentControlSet\\\\Services\\\\RpcSs")) throw new AssertionError("default key");
        check(4, 3, "System\\\\ControlSet001", 1);
        if (!WineRegistryEditor.editedKey.equals("System\\\\ControlSet001\\\\Services\\\\RpcSs")) throw new AssertionError("alias");
        check(3, 3, null, 0); check(2, 2, null, 0); check(null, null, null, 0);
    }
}
""",
            }
            for relative, contents in sources.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding="utf-8")
            subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(root),
                            *[str(root / relative) for relative in sources]],
                           check=True, capture_output=True, text=True)
            subprocess.run(["java", "-cp", str(root), "ServiceCheck"],
                           check=True, capture_output=True, text=True)
