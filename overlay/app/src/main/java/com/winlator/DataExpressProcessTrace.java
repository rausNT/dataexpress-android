package com.winlator;

import android.content.Context;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded Box64/Wine output capture used only by the DataExpress launcher. */
public final class DataExpressProcessTrace {
    private static final int MAX_LINES = 120;
    private static final int MAX_LINE_LENGTH = 800;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static String command = "";

    private DataExpressProcessTrace() {}

    public static synchronized void reset() {
        LINES.clear();
        command = "";
    }

    public static synchronized void command(Context context, String value) {
        command = value == null ? "" : value;
        DataExpressDiagnostics.record(context, "wine.command", command, null);
    }

    public static synchronized void onLine(String value) {
        if (value == null) return;
        String line = value.length() > MAX_LINE_LENGTH
            ? value.substring(0, MAX_LINE_LENGTH) + "…"
            : value;
        while (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(line);
    }

    public static synchronized String finish(Context context, int status) {
        StringBuilder output = new StringBuilder();
        for (String line : LINES) {
            if (output.length() > 0) output.append('\n');
            output.append(line);
        }
        String detail = "status=" + status + "; command=" + command + "; output=" + output;
        DataExpressDiagnostics.record(context, "wine.terminated", detail, null);
        DataExpressDiagnostics.flush(context);
        if (output.length() == 0) output.append("Box64/Wine не передал диагностический вывод.");
        return "Код завершения: " + status + "\n\n" + output;
    }
}
