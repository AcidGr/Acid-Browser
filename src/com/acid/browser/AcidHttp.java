package com.acid.browser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Shared HTTP client utility with unified User-Agent, timeout policy, and response size cap.
 *
 * <p>Replaces duplicated {@code HttpURLConnection} boilerplate across OTA update checks,
 * remote userscript downloads, and AdBlock subscription syncs.
 */
public final class AcidHttp {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15000;
    public static final int DEFAULT_MAX_BYTES = 20 * 1024 * 1024;

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
        HttpURLConnection conn = openGetConnection(urlStr, connectTimeoutMs, readTimeoutMs);
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
        HttpURLConnection conn = openGetConnection(urlStr, connectTimeoutMs, readTimeoutMs);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP 错误: " + code);
            }
            InputStream in = conn.getInputStream();
            try {
                FileOutputStream out = new FileOutputStream(targetFile);
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
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openGetConnection(
        String urlStr,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr.trim()).toURL().openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", userAgent());
        return conn;
    }
}
