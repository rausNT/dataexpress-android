package com.winlator.core;

import com.winlator.xserver.Window;
import com.winlator.xserver.Decoration;

/** Excludes Wine popup menus, owned pickers, splash screens and dialogs. */
public final class DataExpressWindowPolicy {
    private DataExpressWindowPolicy() {}

    public static boolean shouldMaximize(Window window) {
        return window != null
            && "dataexpress.exe".equalsIgnoreCase(window.getClassName())
            && window.attributes.isViewable()
            && !window.attributes.isOverrideRedirect()
            && window.getType() == Window.Type.NORMAL
            && window.getTransientFor() == 0
            && window.isApplicationWindow()
            && window.hasDecoration(Decoration.TITLE)
            && window.hasDecoration(Decoration.MAXIMIZE)
            && window.getHandle() != 0;
    }
}
