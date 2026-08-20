package com.winlator;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Local diagnostics with explicit opt-in HTTPS delivery. */
public final class DataExpressDiagnostics {
    private static final String PREFERENCES = "dataexpress_diagnostics";
    private static final String ENDPOINT =
        "https://dx.74-208-142-118.sslip.io/api/android-diagnostics";
    private static final Object FILE_LOCK = new Object();
    private static final int MAX_CLIPBOARD_BYTES = 256 * 1024;
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
            .setMessage("Для поиска ошибок совместимости приложение записывает технические журналы: этапы запуска, версии Android и APK, сбои Wine/DataExpress/Firebird, EPAS/DLL и сохранения файла.\n\nСодержимое базы, записи, пароли и документы не отправляются. Имя и путь внешней базы заменяются хешем. Разрешить автоматическую отправку отчётов по HTTPS при доступности сервера диагностики?")
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
            ActivityManager manager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                item.put("systemAvailableMemoryMb", memory.availMem / (1024 * 1024));
                item.put("systemMemoryThresholdMb", memory.threshold / (1024 * 1024));
                item.put("systemLowMemory", memory.lowMemory);
            }
            Runtime runtime = Runtime.getRuntime();
            item.put("appMaxMemoryMb", runtime.maxMemory() / (1024 * 1024));
            item.put("appAllocatedMemoryMb", runtime.totalMemory() / (1024 * 1024));
            item.put("appFreeMemoryMb", runtime.freeMemory() / (1024 * 1024));
            item.put("appUsedMemoryMb",
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            item.put("appHeadroomMemoryMb",
                (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024));
            if (error != null) {
                item.put("errorType", error.getClass().getName());
                item.put("error", String.valueOf(error.getMessage()));
                item.put("stack", Log.getStackTraceString(error));
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
        catch (Exception exception) {
            Log.e("DataExpressDiagnostics", "Cannot append diagnostic event", exception);
        }
    }

    public static void flush(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (!preferences.getBoolean("upload_enabled", false)) return;
        Executors.newSingleThreadExecutor().execute(() -> upload(context.getApplicationContext()));
    }

    public static boolean hasEvents(Context context) {
        synchronized (FILE_LOCK) {
            File file = logFile(context);
            return file.isFile() && file.length() > 0;
        }
    }

    public static String suggestedFilename() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        return "dataexpress-diagnostics-" + timestamp + ".jsonl";
    }

    public static void copyToClipboard(Activity activity) {
        try {
            String text = clipboardText(activity);
            if (text.isEmpty()) {
                Toast.makeText(activity, "Журнал диагностики пока пуст.", Toast.LENGTH_LONG).show();
                return;
            }
            ClipboardManager clipboard =
                (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard service is unavailable");
            clipboard.setPrimaryClip(ClipData.newPlainText("DataExpress diagnostics", text));
            Toast.makeText(activity, "Журнал скопирован в буфер обмена.", Toast.LENGTH_LONG).show();
        }
        catch (Exception error) {
            Toast.makeText(activity, "Не удалось скопировать журнал: " + error.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    public static void exportToUri(Context context, Uri destination) throws Exception {
        synchronized (FILE_LOCK) {
            File source = logFile(context);
            if (!source.isFile() || source.length() == 0) {
                throw new IllegalStateException("Журнал диагностики пока пуст");
            }
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("Не удалось открыть выбранный файл");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
        }
    }

    public static void share(Activity activity) {
        try {
            File source = shareSnapshot(activity);
            Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".FileProvider", source);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Журнал запуска DataExpress Android");
            send.putExtra(Intent.EXTRA_TEXT,
                "Технический журнал DataExpress Android. Базы данных, документы и пароли не приложены.");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri("DataExpress diagnostics", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "Отправить журнал DataExpress"));
        }
        catch (IllegalStateException empty) {
            Toast.makeText(activity, empty.getMessage(), Toast.LENGTH_LONG).show();
        }
        catch (Exception error) {
            Toast.makeText(activity, "Не удалось открыть меню отправки: " + error.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private static File shareSnapshot(Context context) throws Exception {
        synchronized (FILE_LOCK) {
            File source = logFile(context);
            if (!source.isFile() || source.length() == 0) {
                throw new IllegalStateException("Журнал диагностики пока пуст.");
            }
            File directory = new File(source.getParentFile(), "share");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Не удалось подготовить файл журнала");
            }
            File[] oldSnapshots = directory.listFiles();
            if (oldSnapshots != null) {
                for (File old : oldSnapshots) if (old.isFile()) old.delete();
            }
            File snapshot = new File(directory, suggestedFilename());
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(snapshot)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
            return snapshot;
        }
    }

    private static String clipboardText(Context context) throws Exception {
        synchronized (FILE_LOCK) {
            File file = logFile(context);
            if (!file.isFile() || file.length() == 0) return "";
            byte[] payload = Files.readAllBytes(file.toPath());
            if (payload.length <= MAX_CLIPBOARD_BYTES) {
                return new String(payload, StandardCharsets.UTF_8);
            }
            int start = payload.length - MAX_CLIPBOARD_BYTES;
            while (start < payload.length && payload[start] != '\n') start++;
            if (start < payload.length) start++;
            return "[Начало журнала пропущено: в буфер помещены последние 256 КиБ]\n"
                + new String(payload, start, payload.length - start, StandardCharsets.UTF_8);
        }
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
            int responseCode = connection.getResponseCode();
            String acknowledgement = connection.getHeaderField("X-DataExpress-Diagnostics");
            if (responseCode == HttpURLConnection.HTTP_ACCEPTED && "accepted".equals(acknowledgement)) {
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
