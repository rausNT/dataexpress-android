package com.winlator;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** DataExpress-specific first-run, database import and launch orchestration. */
public final class DataExpressBootstrap {
    private static final String CONTAINER_NAME = "DataExpress";
    private static final String ASSET_PROFILE = "dataexpress/profile.json";
    private static final String ASSET_PAYLOAD = "dataexpress/payload.zip";
    private static final String ASSET_MANIFEST = "dataexpress/manifest.json";
    private static final String ASSET_DEMO = "dataexpress/demo-database.zip";
    private static final String DATABASE_EXTRA = "dataexpress_database_path";
    private static final String SOURCE_URI_EXTRA = "dataexpress_source_uri";
    private static final long INSTALL_TIMEOUT_MS = 10 * 60 * 1000L;

    private DataExpressBootstrap() {}

    public static void initialize(MainActivity activity) {
        RootFSInstaller.installIfNeeded(activity);
        waitForRootFs(activity, System.currentTimeMillis());
    }

    private static void waitForRootFs(MainActivity activity, long startedAt) {
        RootFS rootFS = RootFS.find(activity);
        if (rootFS.isValid() && rootFS.getVersion() >= RootFSInstaller.LATEST_VERSION) {
            prepare(activity);
            return;
        }
        if (System.currentTimeMillis() - startedAt >= INSTALL_TIMEOUT_MS) {
            Toast.makeText(activity, "Не удалось подготовить среду DataExpress.", Toast.LENGTH_LONG).show();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> waitForRootFs(activity, startedAt), 500);
    }

    private static void prepare(MainActivity activity) {
        Uri sourceUri = Intent.ACTION_VIEW.equals(activity.getIntent().getAction())
            ? activity.getIntent().getData() : null;
        if (sourceUri != null && !isDatabaseName(getDisplayName(activity, sourceUri))) {
            Toast.makeText(activity, "Выберите файл .DXDB или .FDB.", Toast.LENGTH_LONG).show();
            sourceUri = null;
        }
        if (sourceUri != null) takePersistablePermission(activity, sourceUri);

        ContainerManager manager = new ContainerManager(activity);
        Container existing = null;
        for (Container candidate : manager.getContainers()) {
            if (CONTAINER_NAME.equals(candidate.getName())) {
                existing = candidate;
                break;
            }
        }

        final Uri requestedDatabase = sourceUri;
        if (existing != null) {
            stageAndLaunch(activity, existing, requestedDatabase);
            return;
        }

        try {
            JSONObject profile = new JSONObject(readAssetText(activity, ASSET_PROFILE));
            manager.createContainerAsync(profile, container -> {
                if (container == null) {
                    Toast.makeText(activity, "Не удалось создать контейнер DataExpress.", Toast.LENGTH_LONG).show();
                }
                else stageAndLaunch(activity, container, requestedDatabase);
            });
        }
        catch (Exception error) {
            showFailure(activity, "Ошибка профиля DataExpress", error);
        }
    }

    private static void stageAndLaunch(MainActivity activity, Container container, Uri sourceUri) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File applicationDir = new File(container.getRootDir(), ".wine/drive_c/DataExpress");
                installPayloadIfNeeded(activity, applicationDir);

                File database;
                if (sourceUri == null) {
                    File demoDir = new File(applicationDir, "databases/demo");
                    database = new File(demoDir, "DEMO_DB.DXDB");
                    if (!database.isFile()) unzipAsset(activity, ASSET_DEMO, demoDir);
                }
                else {
                    String name = sanitizeFilename(getDisplayName(activity, sourceUri));
                    File databaseDir = new File(applicationDir, "databases/external/" + shortHash(sourceUri.toString()));
                    database = new File(databaseDir, name);
                    copyFromUri(activity, sourceUri, database);
                }

                if (!database.isFile()) throw new IOException("Database was not staged: " + database);
                launch(activity, container, applicationDir, database, sourceUri);
            }
            catch (Exception error) {
                showFailure(activity, "Не удалось подготовить базу", error);
            }
        });
    }

    private static void installPayloadIfNeeded(Context context, File applicationDir) throws Exception {
        String manifest = readAssetText(context, ASSET_MANIFEST);
        String revision = shortHash(manifest);
        File marker = new File(applicationDir, ".payload-" + revision);
        File executable = new File(applicationDir, "DataExpress.exe");
        if (marker.isFile() && executable.isFile()) return;

        unzipAsset(context, ASSET_PAYLOAD, applicationDir);
        if (!executable.isFile()) throw new IOException("DataExpress.exe is absent from the payload");
        if (!applicationDir.isDirectory() && !applicationDir.mkdirs()) {
            throw new IOException("Cannot create " + applicationDir);
        }
        try (OutputStream output = new FileOutputStream(marker)) {
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void launch(MainActivity activity, Container container, File applicationDir,
                               File database, Uri sourceUri) throws IOException {
        final String dosDatabase = toDataExpressDosPath(applicationDir, database);
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("exec_path", new File(applicationDir, "DataExpress.exe").getPath());
            intent.putExtra("exec_args", "\"" + dosDatabase + "\" t:\"C:\\DataExpress\\templates\" o:\"C:\\DataExpress\\output\"");
            intent.putExtra("dataexpress_mode", true);
            intent.putExtra(DATABASE_EXTRA, database.getPath());
            if (sourceUri != null) intent.putExtra(SOURCE_URI_EXTRA, sourceUri.toString());
            activity.startActivity(intent);
            activity.finish();
        });
    }

    public static void finishAndSync(XServerDisplayActivity activity) {
        Intent intent = activity.getIntent();
        String source = intent.getStringExtra(SOURCE_URI_EXTRA);
        String databasePath = intent.getStringExtra(DATABASE_EXTRA);
        if (source == null || databasePath == null) {
            activity.finishAndRemoveTask();
            return;
        }

        Toast.makeText(activity, "Сохраняем базу на внешний накопитель…", Toast.LENGTH_LONG).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            Exception failure = null;
            try {
                copyToUri(activity, new File(databasePath), Uri.parse(source));
            }
            catch (Exception error) {
                failure = error;
            }
            Exception result = failure;
            activity.runOnUiThread(() -> {
                if (result != null) {
                    Toast.makeText(activity,
                        "Не удалось записать изменения на флешку; рабочая копия сохранена внутри приложения.",
                        Toast.LENGTH_LONG).show();
                }
                activity.finishAndRemoveTask();
            });
        });
    }

    private static String toDataExpressDosPath(File applicationDir, File database) throws IOException {
        String base = applicationDir.getCanonicalPath();
        String child = database.getCanonicalPath();
        if (!child.startsWith(base + File.separator)) throw new IOException("Database escaped application directory");
        String relative = child.substring(base.length() + 1).replace('/', '\\');
        return "C:\\DataExpress\\" + relative;
    }

    private static void unzipAsset(Context context, String asset, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create " + destination);
        }
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(context.getAssets().open(asset)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File output = new File(destination, entry.getName());
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new IOException("Unsafe ZIP entry: " + entry.getName());
                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create " + output);
                }
                else {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                    try (OutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) target.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void copyFromUri(Context context, Uri source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        try (InputStream input = context.getContentResolver().openInputStream(source);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            if (input == null) throw new IOException("Cannot open " + source);
            copy(input, output);
        }
    }

    private static void copyToUri(Context context, File source, Uri destination) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = context.getContentResolver().openOutputStream(destination, "rwt")) {
            if (output == null) throw new IOException("Cannot write " + destination);
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static String readAssetText(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            copy(input, output);
            return output.toString("UTF-8");
        }
    }

    private static String getDisplayName(Context context, Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) return cursor.getString(index);
                }
            }
            catch (Exception ignored) {}
        }
        String segment = uri.getLastPathSegment();
        return segment != null ? segment : "database.dxdb";
    }

    private static boolean isDatabaseName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dxdb") || lower.endsWith(".fdb");
    }

    private static String sanitizeFilename(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return cleaned.isEmpty() ? "database.dxdb" : cleaned;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) result.append(String.format(Locale.ROOT, "%02x", digest[index]));
            return result.toString();
        }
        catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void takePersistablePermission(Context context, Uri uri) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        }
        catch (Exception ignored) {}
    }

    private static void showFailure(MainActivity activity, String prefix, Exception error) {
        activity.runOnUiThread(() -> Toast.makeText(activity,
            prefix + ": " + error.getMessage(), Toast.LENGTH_LONG).show());
    }
}
