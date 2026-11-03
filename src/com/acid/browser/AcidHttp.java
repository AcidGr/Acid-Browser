package com.acid.browser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Shared HTTP client utility with unified User-Agent, timeout policy, response size cap,
 * cross-protocol redirect loop (HTTP -> HTTPS), and atomic destination file replacement.
 *
 * <p>Replaces duplicated {@code HttpURLConnection} boilerplate across OTA update checks,
 * remote userscript downloads, and AdBlock subscription syncs.
 */
public final class AcidHttp {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15000;
    public static final int DEFAULT_MAX_BYTES = 20 * 1024 * 1024;
    public static final int MAX_REDIRECTS = 5;

    private AcidHttp() {}

    public static String userAgent() {
        return "AcidBrowser/" + BuildInfo.VERSION_NAME;
    }

    public static String fetchText(String urlStr) throws IOException {
        return fetchText(urlStr, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_BYTES);
    }

    public static String fetchText(
        String urlStr,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxBytes
    ) throws IOException {
        HttpURLConnection conn = openGetConnectionWithRedirects(urlStr, connectTimeoutMs, readTimeoutMs);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (maxBytes > 0 && bos.size() + n > maxBytes) {
                        int allow = maxBytes - bos.size();
                        if (allow > 0) bos.write(buf, 0, allow);
                        break;
                    }
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    public static void downloadToFile(
        String urlStr,
        File targetFile,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxBytes
    ) throws IOException {
        if (targetFile == null) throw new IOException("Target file is null");
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create destination directory: " + parent.getAbsolutePath());
        }
        File destDir = parent != null ? parent : new File(".");
        File tmpFile = new File(destDir, targetFile.getName() + ".tmp_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000));

        HttpURLConnection conn = openGetConnectionWithRedirects(urlStr, connectTimeoutMs, readTimeoutMs);
        boolean success = false;
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP 错误: " + code);
            }
            InputStream in = conn.getInputStream();
            try {
                FileOutputStream out = new FileOutputStream(tmpFile);
                try {
                    byte[] buf = new byte[8192];
                    int total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (maxBytes > 0 && total + n > maxBytes) {
                            throw new IOException("文件超过大小上限 (" + (maxBytes / (1024 * 1024)) + " MB)");
                        }
                        out.write(buf, 0, n);
                        total += n;
                    }
                    out.flush();
                    try {
                        out.getFD().sync();
                    } catch (Throwable ignored) {}
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {}
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {}
            }

            // Atomic POSIX rename replaces destination file safely without truncating original on error
            if (!tmpFile.renameTo(targetFile)) {
                if (targetFile.exists() && !targetFile.delete()) {
                    // ignore
                }
                if (!tmpFile.renameTo(targetFile)) {
                    AcidIo.copyFile(tmpFile, targetFile);
                    try { tmpFile.delete(); } catch (Throwable ignored) {}
                }
            }
            success = true;
        } finally {
            conn.disconnect();
            if (!success && tmpFile.exists()) {
                try {
                    tmpFile.delete();
                } catch (Throwable ignored) {}
            }
        }
    }

    public static HttpURLConnection openGetConnectionWithRedirects(
        String urlStr,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws IOException {
        String currentUrl = urlStr != null ? urlStr.trim() : "";
        int redirects = 0;

        while (redirects < MAX_REDIRECTS) {
            HttpURLConnection conn = openRawGetConnection(currentUrl, connectTimeoutMs, readTimeoutMs);
            int code = conn.getResponseCode();

            // Handle HTTP 301, 302, 303, 307, 308 (including cross-protocol HTTP -> HTTPS)
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) {
                    throw new IOException("Redirect without Location header (HTTP " + code + ")");
                }
                URL nextUrl = new URL(new URL(currentUrl), loc.trim());
                currentUrl = nextUrl.toString();
                redirects++;
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects (exceeded " + MAX_REDIRECTS + ")");
    }

    private static HttpURLConnection openRawGetConnection(
        String urlStr,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws IOException {
        URL url;
        try {
            url = URI.create(urlStr.trim()).toURL();
        } catch (Exception e) {
            url = new URL(urlStr.trim());
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(false); // Handle redirects manually to support HTTP -> HTTPS
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", userAgent());
        return conn;
    }
}
