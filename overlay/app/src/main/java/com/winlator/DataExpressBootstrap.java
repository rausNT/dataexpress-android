package com.winlator;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.Toast;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.ProcessHelper;
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
    public static final String ACTION_OPEN_DATABASE = "ru.mydataexpress.android.action.OPEN_DATABASE";
    public static final String ACTION_LAUNCH_DEMO = "ru.mydataexpress.android.action.LAUNCH_DEMO";
    public static final String LAST_RESULT_EXTRA = "dataexpress_last_result";
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
        DataExpressDiagnostics.initialize(activity, () -> beginInitialization(activity));
    }

    private static void beginInitialization(MainActivity activity) {
        DataExpressDiagnostics.record(activity, "rootfs.prepare", null, null);
        RootFSInstaller.installIfNeeded(activity);
        waitForRootFs(activity, System.currentTimeMillis());
    }

    private static void waitForRootFs(MainActivity activity, long startedAt) {
        RootFS rootFS = RootFS.find(activity);
        if (rootFS.isValid() && rootFS.getVersion() >= RootFSInstaller.LATEST_VERSION) {
            DataExpressDiagnostics.record(activity, "rootfs.ready", "version=" + rootFS.getVersion(), null);
            prepare(activity);
            return;
        }
        if (System.currentTimeMillis() - startedAt >= INSTALL_TIMEOUT_MS) {
            DataExpressDiagnostics.record(activity, "rootfs.timeout", null, null);
            Toast.makeText(activity, "Не удалось подготовить среду DataExpress.", Toast.LENGTH_LONG).show();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> waitForRootFs(activity, startedAt), 500);
    }

    private static void prepare(MainActivity activity) {
        String action = activity.getIntent().getAction();
        if (ACTION_OPEN_DATABASE.equals(action)) {
            requestExternalDatabase(activity);
            return;
        }
        Uri sourceUri = Intent.ACTION_VIEW.equals(action) ? activity.getIntent().getData() : null;
        prepare(activity, sourceUri);
    }

    private static void requestExternalDatabase(MainActivity activity) {
        activity.setOpenFileCallback(uri -> {
            if (uri != null) prepare(activity, uri);
        });
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(picker, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    private static void prepare(MainActivity activity, Uri sourceUri) {
        if (sourceUri != null && !isDatabaseName(getDisplayName(activity, sourceUri))) {
            Toast.makeText(activity, "Выберите файл .DXDB или .FDB.", Toast.LENGTH_LONG).show();
            return;
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
        if (container.getStartupSelection() != Container.STARTUP_SELECTION_ESSENTIAL) {
            container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL);
            container.saveData();
            DataExpressDiagnostics.record(activity, "container.services",
                "startupSelection=essential", null);
        }
        DataExpressDiagnostics.record(activity, "database.stage.start",
            sourceUri == null ? "embedded-demo" : "external:" + shortHash(sourceUri.toString()), null);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File applicationDir = new File(container.getRootDir(), ".wine/drive_c/DataExpress");
                installPayloadIfNeeded(activity, applicationDir);

                File database;
                if (sourceUri == null) {
                    File demoDir = new File(applicationDir, "databases/demo");
                    // The bundled database is ODS 13 and therefore must keep the
                    // .DXDB extension that selects the bundled Firebird 5 engine.
                    database = new File(demoDir, "DEMO_DB.DXDB");
                    if (!database.isFile()) {
                        unzipAsset(activity, ASSET_DEMO, demoDir);
                    }
                }
                else {
                    String sourceName = getDisplayName(activity, sourceUri);
                    String name = safeStagedFilename(sourceName);
                    File databaseDir = new File(applicationDir, "databases/external/" + shortHash(sourceUri.toString()));
                    database = new File(databaseDir, name);
                    copyFromUri(activity, sourceUri, database);
                    DataExpressDiagnostics.record(activity, "database.runtime.select",
                        "sourceNameHash=" + shortHash(sourceName)
                            + "; format=" + databaseFormat(name)
                            + "; engine=" + databaseEngine(name)
                            + "; stagedName=" + name,
                        null);
                }

                if (!database.isFile()) throw new IOException("Database was not staged: " + database);
                if (database.getName().toLowerCase(Locale.ROOT).endsWith(".dxdb")) {
                    applyFirebirdWineCompatibility(activity, applicationDir);
                }
                configureDisplay(activity, container, applicationDir);
                DataExpressDiagnostics.record(activity, "database.stage.ready",
                    "size=" + database.length(), null);
                showLaunchReport(activity, container, applicationDir, database, sourceUri);
            }
            catch (Exception error) {
                showFailure(activity, "Не удалось подготовить базу", error);
            }
        });
    }

    private static void showLaunchReport(MainActivity activity, Container container,
                                         File applicationDir, File database, Uri sourceUri) {
        File executable = new File(applicationDir, "DataExpress.exe");
        File firebirdEngine = new File(applicationDir, "fb5/plugins/engine13.dll");
        String source = sourceUri == null ? "Встроенная учебная база" : "Выбранный файл Android / USB";
        String displayName = sourceUri == null ? database.getName() : getDisplayName(activity, sourceUri);
        String engine = databaseEngine(database.getName());
        String report =
            "База: " + displayName + "\n" +
            "Источник: " + source + "\n" +
            "Формат: " + databaseFormat(database.getName()) + " · движок: " + engine + "\n" +
            "Размер: " + formatSize(database.length()) + "\n\n" +
            statusLine(executable.isFile(), "DataExpress Win32 подготовлен") + "\n" +
            statusLine(firebirdEngine.isFile(), "Firebird Embedded подготовлен") + "\n" +
            statusLine(database.isFile(), "Рабочая копия базы создана") + "\n" +
            "◷ EPAS: запуск штатным движком DataExpress внутри Wine\n" +
            "◷ DLL/COM и действия: результат будет известен после выполнения\n" +
            (sourceUri == null
                ? "ℹ Изменения останутся внутри приложения."
                : "ℹ При штатном выходе изменения будут записаны обратно в выбранный файл.");

        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
            .setTitle("Проверка запуска DataExpress")
            .setMessage(report)
            .setNegativeButton("Отмена", (dialog, which) -> activity.finish())
            .setPositiveButton("Запустить", (dialog, which) -> {
                try {
                    launch(activity, container, applicationDir, database, sourceUri);
                }
                catch (IOException error) {
                    showFailure(activity, "Не удалось запустить базу", error);
                }
            })
            .setCancelable(false)
            .show());
    }

    private static String statusLine(boolean ready, String label) {
        return (ready ? "✓ " : "✗ ") + label;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f КиБ", kib);
        return String.format(Locale.ROOT, "%.1f МиБ", kib / 1024.0);
    }

    private static void configureDisplay(MainActivity activity, Container container,
                                         File applicationDir) throws IOException {
        int physicalWidth;
        int physicalHeight;
        int workspaceWidth;
        int workspaceHeight;
        int insetLeft = 0;
        int insetTop = 0;
        int insetRight = 0;
        int insetBottom = 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            physicalWidth = bounds.width();
            physicalHeight = bounds.height();
            insetLeft = insets.left;
            insetTop = insets.top;
            insetRight = insets.right;
            insetBottom = insets.bottom;
            workspaceWidth = Math.max(1, physicalWidth - insetLeft - insetRight);
            workspaceHeight = Math.max(1, physicalHeight - insetTop - insetBottom);
        }
        else {
            DisplayMetrics realMetrics = new DisplayMetrics();
            Point workspace = new Point();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(realMetrics);
            activity.getWindowManager().getDefaultDisplay().getSize(workspace);
            physicalWidth = realMetrics.widthPixels;
            physicalHeight = realMetrics.heightPixels;
            workspaceWidth = Math.max(1, workspace.x);
            workspaceHeight = Math.max(1, workspace.y);
        }

        int displayWidth = Math.max(workspaceWidth, workspaceHeight);
        int displayHeight = Math.min(workspaceWidth, workspaceHeight);

        // Keep roughly 720 logical pixels vertically, but derive the width from
        // the real Android aspect ratio. This avoids letterboxing on tablets
        // while keeping the Wine framebuffer small enough for older phones.
        String screenSize = selectWineScreenSize(displayWidth, displayHeight);
        String[] screenParts = screenSize.split("x", 2);
        int wineWidth = Integer.parseInt(screenParts[0]);
        int wineHeight = Integer.parseInt(screenParts[1]);
        container.setScreenSize(screenSize);
        container.setGraphicsDriver("vortek,gladio");
        container.setDXWrapper("dxvk");
        container.setWinComponents(
            "direct3d=1,directsound=1,directmusic=1,directshow=0,directplay=0," +
            "xaudio=1,vcrun2005=0,vcrun2010=1,wmdecoder=1");
        container.setBox64Preset("INTERMEDIATE");
        container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL);
        container.saveData();

        File config = new File(applicationDir, "dataexpress.cfg");
        if (config.isFile()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (InputStream input = new BufferedInputStream(new FileInputStream(config))) {
                copy(input, buffer);
            }
            String text = buffer.toString("UTF-8");
            String updated = text
                // Wine does not consistently honour Lazarus wsMaximized. Set
                // the saved normal geometry to the entire virtual desktop so
                // DataExpress fills the Android screen on every launch.
                .replaceAll("(?m)^FormWidth=\\d+", "FormWidth=" + wineWidth)
                .replaceAll("(?m)^FormHeight=\\d+", "FormHeight=" + wineHeight)
                .replaceAll("(?m)^FormLeft=-?\\d+", "FormLeft=0")
                .replaceAll("(?m)^FormTop=-?\\d+", "FormTop=0")
                .replaceAll("(?m)^FormState=\\d+", "FormState=0")
                .replaceAll("(?m)^LogErrors=\\d+", "LogErrors=1");
            if (!updated.equals(text)) {
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(config))) {
                    output.write(updated.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        DataExpressDiagnostics.record(activity, "display.configure",
            "physical=" + physicalWidth + "x" + physicalHeight
                + ", workspace=" + workspaceWidth + "x" + workspaceHeight
                + ", insets=" + insetLeft + "/" + insetTop + "/"
                + insetRight + "/" + insetBottom + ", wine=" + screenSize
                + ", graphics=vortek+gladio, dxwrapper=dxvk, box64=INTERMEDIATE", null);
    }

    private static int roundToMultiple(int value, int multiple) {
        return Math.max(multiple, ((value + multiple / 2) / multiple) * multiple);
    }

    private static String selectWineScreenSize(int displayWidth, int displayHeight) {
        int landscapeWidth = Math.max(displayWidth, displayHeight);
        int landscapeHeight = Math.max(1, Math.min(displayWidth, displayHeight));
        int targetHeight = 720;
        int targetWidth = roundToMultiple(
            (int)Math.round(targetHeight * (landscapeWidth / (double)landscapeHeight)), 8);
        targetWidth = Math.max(1024, Math.min(1600, targetWidth));
        return targetWidth + "x" + targetHeight;
    }

    private static void installPayloadIfNeeded(Context context, File applicationDir) throws Exception {
        String manifest = readAssetText(context, ASSET_MANIFEST);
        String revision = shortHash(manifest);
        File marker = new File(applicationDir, ".payload-" + revision);
        File executable = new File(applicationDir, "DataExpress.exe");
        if (marker.isFile() && executable.isFile()) return;

        unzipAsset(context, ASSET_PAYLOAD, applicationDir);
        int removedLegacyFiles = removeLegacyBackslashPayloadFiles(applicationDir);
        if (!executable.isFile()) throw new IOException("DataExpress.exe is absent from the payload");
        if (!applicationDir.isDirectory() && !applicationDir.mkdirs()) {
            throw new IOException("Cannot create " + applicationDir);
        }
        try (OutputStream output = new FileOutputStream(marker)) {
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
        }
        DataExpressDiagnostics.record(context, "payload.install",
            "revision=" + revision + "; removedLegacyBackslashFiles=" + removedLegacyFiles, null);
    }

    private static int removeLegacyBackslashPayloadFiles(File applicationDir) {
        File[] files = applicationDir.listFiles();
        if (files == null) return 0;
        int removed = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().contains("\\") && file.delete()) removed++;
        }
        return removed;
    }

    /**
     * Validate the Firebird 5.0.3 x86 source build prepared specifically for Wine/Winlator.
     * The compatibility changes live in Firebird's source code, so runtime binary patching is
     * intentionally forbidden. This prevents PE relocations from rewriting patched instructions
     * and causing EPrivilege on real devices.
     */
    private static void applyFirebirdWineCompatibility(Context context, File applicationDir)
        throws IOException {
        final String[][] modules = new String[][] {
            {"fb5/fbclient.dll", "1827840", "4869f96ee2faae94b883c05a81ebe9b573b5465788d0109815ec900c53d605f2"},
            {"fb5/intl/fbintl.dll", "1067008", "8c92a8c742759c5b787e8ca16b840a7383e7b674f9261deca9bcc98fb886375b"},
            {"fb5/plugins/chacha.dll", "392704", "fbc16fc26155b6b3faa970285d6be69defea4ab024a8ebc5cb2ad4ae2a8de2e6"},
            {"fb5/plugins/engine13.dll", "8262656", "9c44d86174da80dfaaf86955e96e1c7beb2288ed17f4bba15e49bc4ae6e1d261"}
        };
        StringBuilder invalid = new StringBuilder();
        for (String[] module : modules) {
            File file = new File(applicationDir, module[0]);
            long expectedLength = Long.parseLong(module[1]);
            boolean valid = file.isFile()
                && file.length() == expectedLength
                && module[2].equals(sha256File(file));
            if (!valid) {
                if (invalid.length() > 0) invalid.append(',');
                invalid.append(module[0]);
            }
        }

        File lockDirectory = new File(applicationDir, "fb5/lock");
        if (!lockDirectory.isDirectory() && !lockDirectory.mkdirs()) {
            throw new IOException("Cannot create private Firebird lock directory " + lockDirectory);
        }

        String details = "sourceBuild=firebird-5.0.3-wine-compat; modules=" + modules.length
            + "; binaryPatches=0; invalid=" + invalid.length();
        DataExpressDiagnostics.record(context, "firebird.compat.source-build", details, null);
        if (invalid.length() > 0) {
            throw new IOException("Firebird/Wine source build validation failed: " + invalid);
        }
    }

    private static String sha256File(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return result.toString();
        }
        catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void launch(MainActivity activity, Container container, File applicationDir,
                               File database, Uri sourceUri) throws IOException {
        final String dosDatabase = toDataExpressDosPath(applicationDir, database);
        DataExpressDiagnostics.record(activity, "wine.launch", "database=" + shortHash(dosDatabase), null);
        DataExpressDiagnostics.flush(activity);
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
        String lastResult = intent.getStringExtra(LAST_RESULT_EXTRA);
        if (source == null || databasePath == null) {
            returnHome(activity, lastResult);
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
                String message = lastResult;
                if (result != null) {
                    Toast.makeText(activity,
                        "Не удалось записать изменения на флешку; рабочая копия сохранена внутри приложения.",
                        Toast.LENGTH_LONG).show();
                    message = (message == null ? "" : message + "\n\n")
                        + "Не удалось записать изменения во внешний файл.";
                }
                returnHome(activity, message);
            });
        });
    }

    private static void returnHome(XServerDisplayActivity activity, String result) {
        Intent home = new Intent(activity, DataExpressHomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (result != null && !result.isEmpty()) home.putExtra(LAST_RESULT_EXTRA, result);
        activity.startActivity(home);
        activity.finish();
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

    /**
     * Old Win32 DataExpress converts database paths through the active Windows
     * code page. Keep the original name in Android's document provider, but use
     * a stable ASCII-only working name inside Wine. The URI-specific parent
     * directory already prevents collisions between imported databases.
     */
    private static String safeStagedFilename(String sourceName) {
        String lower = sourceName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".fdb") ? "database.FDB" : "database.DXDB";
    }

    private static String databaseFormat(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".fdb") ? "FDB / ODS 11.x" : "DXDB / ODS 13.x";
    }

    private static String databaseEngine(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".fdb") ? "Firebird 2.5" : "Firebird 5";
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

    public static void reportBackgroundFailure(MainActivity activity, String prefix, Throwable error) {
        showFailure(activity, prefix, error);
    }

    public static int stopWineProcesses(Context context) {
        int stopped = 0;
        for (ProcessHelper.PStat process : ProcessHelper.getChildProcesses()) {
            if (!process.guestProcess || process.pid <= 0) continue;
            try {
                android.os.Process.killProcess(process.pid);
                stopped++;
            }
            catch (RuntimeException error) {
                DataExpressDiagnostics.record(context, "wine.process.stop.failure",
                    "pid=" + process.pid + "; name=" + process.name, error);
            }
        }
        DataExpressDiagnostics.record(context, "wine.processes.stopped", "count=" + stopped, null);
        return stopped;
    }

    private static void showFailure(MainActivity activity, String prefix, Throwable error) {
        DataExpressDiagnostics.record(activity, "bootstrap.failure", prefix, error);
        DataExpressDiagnostics.flush(activity);
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
            .setTitle("DataExpress: запуск не выполнен")
            .setMessage(prefix + ":\n" + error.getMessage())
            .setPositiveButton("Закрыть", null)
            .show());
    }
}
