package com.winlator;

import android.content.Context;
import com.winlator.container.Container;
import com.winlator.core.WineRegistryEditor;
import java.io.File;

/** Essential startup disables RpcSs, but DataExpress and Wine Explorer use COM. */
public final class DataExpressStartupServices {
    private DataExpressStartupServices() {}

    public static void configure(Context context, Container container) {
        File registry = new File(container.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor editor = new WineRegistryEditor(registry)) {
            editor.setCreateKeyIfNotExist(false);
            String controlSet = editor.getSymlinkValue("System\\CurrentControlSet", "SymbolicLinkValue");
            if (controlSet == null) controlSet = "System\\CurrentControlSet";
            String key = controlSet + "\\Services\\RpcSs";
            Integer previous = editor.getDwordValue(key, "Start");
            // Demand start preserves the lightweight profile without blocking COM.
            Integer configured = previous;
            if (previous != null && previous == 4) {
                editor.setDwordValue(key, "Start", 3);
                configured = 3;
            }
            DataExpressDiagnostics.record(context, "wine.rpcss.configure",
                "previous=" + previous + "; start=" + configured, null);
        }
    }
}
