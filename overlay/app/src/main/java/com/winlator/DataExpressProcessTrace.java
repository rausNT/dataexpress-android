package com.winlator;

import android.content.Context;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded Box64/Wine output capture used only by the DataExpress launcher. */
public final class DataExpressProcessTrace {
    private static final int MAX_LINES = 120;
    private static final int MAX_SIGNAL_LINES = 40;
    private static final int MAX_LINE_LENGTH = 800;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final Deque<String> SIGNAL_LINES = new ArrayDeque<>();
    private static String command = "";

    private DataExpressProcessTrace() {}

    public static synchronized void reset() {
        LINES.clear();
        SIGNAL_LINES.clear();
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
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("80000100")
            || lower.contains("c0000096")
            || lower.contains("privileged instruction")
            || lower.contains("unhandled exception")
            || lower.contains("exception code=")
            || lower.contains("dispatch_exception code=")
            || lower.contains("unimplemented function")
            || lower.contains("wine: call from")
            || lower.contains("err:")
            || lower.contains("failed to load")
            || lower.contains("out of memory")
            || lower.contains("oom")
            || lower.contains("killed")
            || lower.contains("signal 9")
            || lower.contains("err:module")) {
            while (SIGNAL_LINES.size() >= MAX_SIGNAL_LINES) SIGNAL_LINES.removeFirst();
            SIGNAL_LINES.addLast(line);
        }
    }

    public static synchronized void snapshot(Context context, String reason) {
        String output = renderOutput();
        String detail = "reason=" + reason + "; command=" + command + "; output="
            + (output.isEmpty() ? "<no Wine output>" : output);
        DataExpressDiagnostics.record(context, "wine.snapshot", detail, null);
        DataExpressDiagnostics.flush(context);
    }

    public static synchronized String finish(Context context, int status) {
        String output = renderOutput();
        String detail = "status=" + status + "; command=" + command + "; output=" + output;
        DataExpressDiagnostics.record(context, "wine.terminated", detail, null);
        DataExpressDiagnostics.flush(context);
        if (output.length() == 0) output = "Box64/Wine не передал диагностический вывод.";
        return "Код завершения: " + status + "\n\n" + output;
    }

    private static String renderOutput() {
        StringBuilder output = new StringBuilder();
        if (!SIGNAL_LINES.isEmpty()) {
            output.append("Relevant Wine diagnostics:");
            for (String line : SIGNAL_LINES) output.append('\n').append(line);
            output.append("\n\nLast Wine output:");
        }
        for (String line : LINES) {
            if (output.length() > 0) output.append('\n');
            output.append(line);
        }
        return output.toString();
    }
}
