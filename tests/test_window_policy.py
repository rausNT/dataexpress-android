"""Run the Java window policy against main, menu and picker window metadata."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


POLICY = Path(__file__).parents[1] / "overlay/app/src/main/java/com/winlator/core/DataExpressWindowPolicy.java"


class WindowPolicyTest(unittest.TestCase):
    def test_only_main_application_window_is_maximized(self):
        self.assertIsNotNone(shutil.which("javac"), "JDK is required for window policy tests")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = {
                "com/winlator/core/DataExpressWindowPolicy.java": POLICY.read_text(encoding="utf-8"),
                "com/winlator/xserver/Decoration.java": "package com.winlator.xserver; public enum Decoration {TITLE, MAXIMIZE}",
                "com/winlator/xserver/Window.java": """
package com.winlator.xserver;
public class Window {
    public enum Type { NORMAL, DIALOG }
    public static class Attributes {
        public boolean viewable = true, popup = false;
        public boolean isViewable() { return viewable; }
        public boolean isOverrideRedirect() { return popup; }
    }
    public final Attributes attributes = new Attributes();
    public String className = "dataexpress.exe";
    public Type type = Type.NORMAL;
    public int owner = 0;
    public boolean application = true, title = true, maximize = true;
    public long handle = 12;
    public String getClassName() { return className; }
    public Type getType() { return type; }
    public int getTransientFor() { return owner; }
    public boolean isApplicationWindow() { return application; }
    public boolean hasDecoration(Decoration d) { return d == Decoration.TITLE ? title : maximize; }
    public long getHandle() { return handle; }
}
""",
                "PolicyCheck.java": """
import com.winlator.core.DataExpressWindowPolicy;
import com.winlator.xserver.Window;
public class PolicyCheck {
    private static void reject(Window w, String name) {
        if (DataExpressWindowPolicy.shouldMaximize(w)) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        Window w = new Window();
        if (!DataExpressWindowPolicy.shouldMaximize(w)) throw new AssertionError("main window");
        w.className = "DataExpress.EXE";
        if (!DataExpressWindowPolicy.shouldMaximize(w)) throw new AssertionError("case insensitive class");
        w = new Window(); w.attributes.popup = true; reject(w, "popup menu");
        w = new Window(); w.owner = 12; reject(w, "owned object list");
        w = new Window(); w.type = Window.Type.DIALOG; reject(w, "dialog");
        w = new Window(); w.title = false; reject(w, "borderless menu");
        w = new Window(); w.maximize = false; reject(w, "fixed size picker");
        w = new Window(); w.application = false; reject(w, "child window");
        w = new Window(); w.attributes.viewable = false; reject(w, "unmapped before delayed callback");
        w = new Window(); w.handle = 0; reject(w, "no Win32 handle");
        w = new Window(); w.className = "explorer.exe"; reject(w, "Wine desktop");
        reject(null, "null");
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
            subprocess.run(["java", "-cp", str(root), "PolicyCheck"],
                           check=True, capture_output=True, text=True)
