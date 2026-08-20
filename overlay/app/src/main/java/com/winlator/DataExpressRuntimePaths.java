package com.winlator;

import android.content.Context;

import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;

/** Repairs absolute package paths embedded throughout Winlator's prebuilt rootfs. */
public final class DataExpressRuntimePaths {
    private static final byte[] UPSTREAM_ID = "com.winlator".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] APPLICATION_ID = BuildConfig.APPLICATION_ID.getBytes(StandardCharsets.US_ASCII);

    private DataExpressRuntimePaths() {}

    private static final String MARKER = ".dataexpress-runtime-paths-v1";
    private static final String[] SYSTEM_ROOTS = {
        "bin", "etc", "lib", "lib64", "opt", "sbin", "usr", "var"
    };

    public static void patchRuntime(Context context, RootFS rootFS) {
        if (UPSTREAM_ID.length != APPLICATION_ID.length) {
            throw new IllegalStateException("Application id length is incompatible with Winlator ELF paths");
        }

        File root = rootFS.getRootDir();
        File marker = new File(root, MARKER);
        if (marker.isFile()) {
            DataExpressDiagnostics.record(context, "runtime.paths.ready", "cached", null);
            return;
        }

        try {
            int files = 0;
            int replacements = 0;
            Deque<File> pending = new ArrayDeque<>();
            for (String name : SYSTEM_ROOTS) pending.addLast(new File(root, name));
            while (!pending.isEmpty()) {
                File current = pending.removeFirst();
                if (!current.exists() || Files.isSymbolicLink(current.toPath())) continue;
                if (current.isDirectory()) {
                    File[] children = current.listFiles();
                    if (children != null) {
                        for (File child : children) pending.addLast(child);
                    }
                }
                else if (current.isFile()) {
                    int count = replaceInPlace(current, UPSTREAM_ID, APPLICATION_ID);
                    if (count > 0) files++;
                    replacements += count;
                }
            }
            try (FileOutputStream output = new FileOutputStream(marker)) {
                output.write(BuildConfig.APPLICATION_ID.getBytes(StandardCharsets.US_ASCII));
            }
            DataExpressDiagnostics.record(context, "runtime.paths.ready",
                "files=" + files + "; replacements=" + replacements, null);
        }
        catch (IOException error) {
            DataExpressDiagnostics.record(context, "runtime.paths.failure", null, error);
            throw new IllegalStateException("Cannot adapt Winlator rootfs to " + BuildConfig.APPLICATION_ID, error);
        }
    }

    static int replaceInPlace(File file, byte[] search, byte[] replacement) throws IOException {
        if (!file.isFile()) throw new IOException("Runtime file is absent: " + file);
        if (search.length != replacement.length) throw new IOException("Unsafe unequal path replacement");
        if (file.length() > Integer.MAX_VALUE) throw new IOException("Runtime file is too large: " + file.length());

        byte[] contents = new byte[(int) file.length()];
        int replacements = 0;
        try (RandomAccessFile target = new RandomAccessFile(file, "rw")) {
            target.readFully(contents);
            for (int offset = 0; offset <= contents.length - search.length; offset++) {
                boolean match = true;
                for (int index = 0; index < search.length; index++) {
                    if (contents[offset + index] != search[index]) {
                        match = false;
                        break;
                    }
                }
                if (!match) continue;
                System.arraycopy(replacement, 0, contents, offset, replacement.length);
                replacements++;
                offset += search.length - 1;
            }
            if (replacements > 0) {
                target.seek(0);
                target.write(contents);
            }
        }
        return replacements;
    }
}
