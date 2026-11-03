package com.acid.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared UTF-8 file/stream/asset I/O and system clipboard utility.
 *
 * <p>Eliminates duplicated byte-buffer loops and {@link ClipboardManager} boilerplate across
 * stores, managers, sandbox tools, and UI sheets.
 */
public final class AcidIo {

    private static final String TAG = "AcidIo";

    private AcidIo() {}

    public static String readStreamUtf8(InputStream is) throws IOException {
        if (is == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {}
        }
    }

    public static String readFileUtf8(File file) throws IOException {
        if (file == null) throw new IOException("File is null");
        return readStreamUtf8(new FileInputStream(file));
    }

    public static String readFileUtf8OrEmpty(File file, long maxBytes) {
        if (file == null || !file.exists() || !file.isFile()) return "";
        long len = file.length();
        if (maxBytes > 0 && len > maxBytes) {
            Log.w(TAG, "File exceeds size cap (" + len + " > " + maxBytes + "): " + file.getAbsolutePath());
            return "";
        }
        try {
            return readFileUtf8(file);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read file: " + file.getAbsolutePath(), e);
            return "";
        }
    }

    public static void writeFileUtf8(File file, String content) throws IOException {
        byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
        writeBytes(file, bytes);
    }

    public static boolean writeFileUtf8Safe(File file, String content) {
        try {
            writeFileUtf8(file, content);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write file: " + (file != null ? file.getAbsolutePath() : "null"), e);
            return false;
        }
    }

    public static void writeBytes(File file, byte[] bytes) throws IOException {
        if (file == null) throw new IOException("Target file is null");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create parent directory: " + parent.getAbsolutePath());
        }
        File destDir = parent != null ? parent : new File(".");
        File tmpFile = new File(destDir, file.getName() + ".tmp_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000));
        FileOutputStream fos = new FileOutputStream(tmpFile);
        boolean success = false;
        try {
            if (bytes != null && bytes.length > 0) {
                fos.write(bytes);
            }
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {}
            success = true;
        } finally {
            try {
                fos.close();
            } catch (IOException ignored) {}
            if (!success) {
                try { tmpFile.delete(); } catch (Throwable ignored) {}
            }
        }

        // Atomic POSIX rename replaces destination file safely without truncating original
        if (!tmpFile.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                // If destination cannot be deleted, attempt copy fallback
            }
            if (!tmpFile.renameTo(file)) {
                copyFile(tmpFile, file);
                try { tmpFile.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    public static boolean writeBytesSafe(File file, byte[] bytes) {
        try {
            writeBytes(file, bytes);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write bytes: " + (file != null ? file.getAbsolutePath() : "null"), e);
            return false;
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        if (src == null || dst == null) throw new IOException("Source or destination file is null");
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create parent directory: " + parent.getAbsolutePath());
        }
        FileInputStream fis = new FileInputStream(src);
        try {
            FileOutputStream fos = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
                fos.flush();
            } finally {
                try { fos.close(); } catch (IOException ignored) {}
            }
        } finally {
            try { fis.close(); } catch (IOException ignored) {}
        }
    }

    public static String readAssetUtf8OrEmpty(Context context, String assetPath) {
        if (context == null || assetPath == null) return "";
        try {
            return readStreamUtf8(context.getAssets().open(assetPath));
        } catch (Exception e) {
            Log.w(TAG, "Failed to read asset: " + assetPath, e);
            return "";
        }
    }

    public static boolean copyToClipboard(Context context, String label, String text, String successMessage) {
        if (context == null || text == null || text.isEmpty()) return false;
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label != null ? label : "text", text));
                if (successMessage != null && !successMessage.isEmpty()) {
                    AcidFeedback.ok(context, successMessage);
                }
                return true;
            }
        } catch (Exception e) {
            AcidFeedback.error(context, "复制失败: " + e.getMessage());
        }
        return false;
    }
}
