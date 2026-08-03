package com.winlator;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Local diagnostics with explicit opt-in HTTPS delivery. */
public final class DataExpressDiagnostics {
    private static final String PREFERENCES = "dataexpress_diagnostics";
    private static final String ENDPOINT =
        "https://dx.74-208-142-118.sslip.io/api/android-diagnostics";
    private static final Object FILE_LOCK = new Object();
    private static volatile boolean handlerInstalled;

    private DataExpressDiagnostics() {}

    public static void initialize(MainActivity activity, Runnable continuation) {
        installCrashHandler(activity.getApplicationContext());
        record(activity, "app.start", null, null);
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (preferences.getBoolean("consent_seen", false)) {
            flush(activity);
            continuation.run();
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle("Диагностика DataExpress")
            .setMessage("Для поиска ошибок совместимости приложение записывает технические журналы: этапы запуска, версии Android и APK, сбои Wine/DataExpress/Firebird, EPAS/DLL и сохранения файла.\n\nСодержимое базы, записи, пароли и документы не отправляются. Имя и путь внешней базы заменяются хешем. Разрешить автоматическую отправку отчётов по HTTPS?")
            .setNegativeButton("Только локально", (dialog, which) -> {
                preferences.edit().putBoolean("consent_seen", true)
                    .putBoolean("upload_enabled", false).apply();
                record(activity, "diagnostics.consent", "local-only", null);
                continuation.run();
            })
            .setPositiveButton("Разрешить отправку", (dialog, which) -> {
                preferences.edit().putBoolean("consent_seen", true)
                    .putBoolean("upload_enabled", true).apply();
                record(activity, "diagnostics.consent", "https-upload", null);
                flush(activity);
                continuation.run();
            })
            .setCancelable(false)
            .show();
    }

    public static void record(Context context, String event, String detail, Throwable error) {
        try {
            JSONObject item = new JSONObject();
            item.put("timestampMs", System.currentTimeMillis());
            item.put("installationId", installationId(context));
            item.put("event", event);
            item.put("detail", detail == null ? JSONObject.NULL : detail);
            item.put("android", Build.VERSION.RELEASE);
            item.put("sdk", Build.VERSION.SDK_INT);
            item.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            item.put("abis", String.join(",", Build.SUPPORTED_ABIS));
            if (error != null) {
                item.put("errorType", error.getClass().getName());
                item.put("error", String.valueOf(error.getMessage()));
                item.put("stack", android.util.Log.getStackTraceString(error));
            }
            byte[] line = (item.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (FILE_LOCK) {
                File file = logFile(context);
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file, true))) {
                    output.write(line);
                }
            }
        }
        catch (Exception ignored) {}
    }

    public static void flush(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (!preferences.getBoolean("upload_enabled", false)) return;
        Executors.newSingleThreadExecutor().execute(() -> upload(context.getApplicationContext()));
    }

    private static void upload(Context context) {
        byte[] payload;
        synchronized (FILE_LOCK) {
            try {
                File file = logFile(context);
                if (!file.isFile() || file.length() == 0) return;
                payload = Files.readAllBytes(file.toPath());
            }
            catch (Exception ignored) {
                return;
            }
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-ndjson; charset=utf-8");
            connection.setRequestProperty("X-DataExpress-Install", installationId(context));
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            if (connection.getResponseCode() / 100 == 2) {
                synchronized (FILE_LOCK) {
                    Files.write(logFile(context).toPath(), new byte[0]);
                }
            }
        }
        catch (Exception ignored) {}
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void installCrashHandler(Context context) {
        if (handlerInstalled) return;
        synchronized (DataExpressDiagnostics.class) {
            if (handlerInstalled) return;
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                record(context, "app.uncaught", thread.getName(), error);
                if (previous != null) previous.uncaughtException(thread, error);
            });
            handlerInstalled = true;
        }
    }

    private static String installationId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String id = preferences.getString("installation_id", null);
        if (id != null) return id;
        id = UUID.randomUUID().toString();
        preferences.edit().putString("installation_id", id).apply();
        return id;
    }

    private static File logFile(Context context) {
        return new File(context.getFilesDir(), "diagnostics/events.jsonl");
    }
}
