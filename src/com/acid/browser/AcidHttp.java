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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Shared HTTP client utility with unified User-Agent, timeout policy, response size cap,
 * cross-protocol redirect loop (HTTP -> HTTPS), credential-stripping policy, and atomic file replacement.
 *
 * <p>Replaces duplicated {@code HttpURLConnection} redirect/streaming boilerplate across OTA update checks,
 * remote userscript downloads, AdBlock subscriptions, download probes/workers, favicons, and skill imports.
 */
public final class AcidHttp {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15000;
    public static final int DEFAULT_MAX_BYTES = 20 * 1024 * 1024;
    public static final int MAX_REDIRECTS = 5;

    public interface UrlValidator {
        void validate(URL url) throws IOException;
    }

    public interface RedirectCredentialPolicy {
        boolean shouldRetainCredentials(URL fromUrl, URL toUrl);
    }

    public interface RedirectListener {
        void onConnectionOpened(HttpURLConnection conn, URL url, Map<String, String> activeHeaders) throws IOException;
        void onConnectionClosing(HttpURLConnection conn);
    }

    public static final RedirectCredentialPolicy SAME_ORIGIN_CREDENTIALS = new RedirectCredentialPolicy() {
        @Override
        public boolean shouldRetainCredentials(URL fromUrl, URL toUrl) {
            return isSameOrigin(fromUrl, toUrl);
        }
    };

    public static final RedirectCredentialPolicy DOWNLOAD_CREDENTIALS = new RedirectCredentialPolicy() {
        @Override
        public boolean shouldRetainCredentials(URL fromUrl, URL toUrl) {
            return shouldRetainDownloadCredentials(fromUrl, toUrl);
        }
    };

    private AcidHttp() {}

    public static String userAgent() {
        return "AcidBrowser/" + BuildInfo.VERSION_NAME;
    }

    public static boolean isRedirectStatus(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
            || code == HttpURLConnection.HTTP_MOVED_TEMP
            || code == HttpURLConnection.HTTP_SEE_OTHER
            || code == 307 || code == 308;
    }

    public static boolean isSameOrigin(URL a, URL b) {
        if (a == null || b == null) return false;
        String pa = a.getProtocol() != null ? a.getProtocol().toLowerCase(Locale.ROOT) : "";
        String pb = b.getProtocol() != null ? b.getProtocol().toLowerCase(Locale.ROOT) : "";
        String ha = a.getHost() != null ? a.getHost().toLowerCase(Locale.ROOT) : "";
        String hb = b.getHost() != null ? b.getHost().toLowerCase(Locale.ROOT) : "";
        int porta = a.getPort() != -1 ? a.getPort() : a.getDefaultPort();
        int portb = b.getPort() != -1 ? b.getPort() : b.getDefaultPort();
        return pa.equals(pb) && ha.equals(hb) && porta == portb;
    }

    public static void stripSensitiveHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return;
        Iterator<String> it = headers.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k == null) continue;
            String lower = k.trim().toLowerCase(Locale.ROOT);
            if ("cookie".equals(lower) || "authorization".equals(lower) || "proxy-authorization".equals(lower)) {
                it.remove();
            }
        }
    }

    /**
     * Decides whether sensitive request headers ({@code Cookie}, {@code Authorization}) may be
     * carried across a download redirect from {@code fromUrl} to {@code toUrl}.
     *
     * <p>Forbids {@code https -> http} cleartext downgrades and arbitrary cross-domain leaks, while
     * preserving credentials across same-site subdomains and paired cloud-drive CDN domains.
     */
    public static boolean shouldRetainDownloadCredentials(URL fromUrl, URL toUrl) {
        if (fromUrl == null || toUrl == null) return false;
        String fromProto = fromUrl.getProtocol() != null ? fromUrl.getProtocol().toLowerCase(Locale.ROOT) : "";
        String toProto = toUrl.getProtocol() != null ? toUrl.getProtocol().toLowerCase(Locale.ROOT) : "";
        if ("https".equals(fromProto) && !"https".equals(toProto)) {
            return false;
        }
        String fromHost = normalizeHost(fromUrl.getHost());
        String toHost = normalizeHost(toUrl.getHost());
        if (fromHost.isEmpty() || toHost.isEmpty()) return false;
        if (fromHost.equals(toHost)) {
            int fromPort = fromUrl.getPort() != -1 ? fromUrl.getPort() : fromUrl.getDefaultPort();
            int toPort = toUrl.getPort() != -1 ? toUrl.getPort() : toUrl.getDefaultPort();
            return fromPort == toPort;
        }
        if (isBaiduDriveHost(fromHost) && isBaiduDriveHost(toHost)) return true;
        if (is115DriveHost(fromHost) && is115DriveHost(toHost)) return true;
        if (isSubdomainOf(fromHost, "quark.cn") && isSubdomainOf(toHost, "quark.cn")) return true;
        if (isSubdomainOf(fromHost, "uc.cn") && isSubdomainOf(toHost, "uc.cn")) return true;
        if (toHost.endsWith("." + fromHost) || fromHost.endsWith("." + toHost)) return true;
        String parentFrom = parentDomainIfSafe(fromHost);
        String parentTo = parentDomainIfSafe(toHost);
        return parentFrom != null && parentFrom.equals(parentTo);
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1).trim();
        }
        return h;
    }

    private static boolean isSubdomainOf(String host, String root) {
        return host.equals(root) || host.endsWith("." + root);
    }

    private static boolean isBaiduDriveHost(String host) {
        return isSubdomainOf(host, "baidu.com") || isSubdomainOf(host, "baidupcs.com");
    }

    private static boolean is115DriveHost(String host) {
        return isSubdomainOf(host, "115.com") || isSubdomainOf(host, "115cdn.com") || isSubdomainOf(host, "115cdn.net");
    }

    private static String parentDomainIfSafe(String host) {
        int dot = host.indexOf('.');
        if (dot <= 0 || dot >= host.length() - 1) return null;
        String parent = host.substring(dot + 1);
        if (!parent.contains(".")) return null;
        if ("com.cn".equals(parent) || "net.cn".equals(parent) || "org.cn".equals(parent)
            || "co.uk".equals(parent) || "co.jp".equals(parent) || "com.hk".equals(parent)
            || "github.io".equals(parent) || "gitlab.io".equals(parent)) {
            return null;
        }
        return parent;
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
        byte[] bytes = fetchBytes(urlStr, connectTimeoutMs, readTimeoutMs, maxBytes, true);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] fetchBytes(
        String urlStr,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxBytes,
        boolean truncateOnMax
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
                        if (!truncateOnMax) {
                            throw new IOException("Response exceeds size limit (" + maxBytes + " bytes)");
                        }
                        int allow = maxBytes - bos.size();
                        if (allow > 0) bos.write(buf, 0, allow);
                        break;
                    }
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } finally {
                AcidIo.closeQuietly(in);
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
                    AcidIo.closeQuietly(out);
                }
            } finally {
                AcidIo.closeQuietly(in);
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
        return openConnectionWithRedirects(
            urlStr, "GET", null, connectTimeoutMs, readTimeoutMs, MAX_REDIRECTS,
            null, SAME_ORIGIN_CREDENTIALS, null
        );
    }

    public static HttpURLConnection openConnectionWithRedirects(
        String urlStr,
        String method,
        Map<String, String> headers,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRedirects,
        UrlValidator urlValidator,
        RedirectCredentialPolicy credentialPolicy,
        RedirectListener listener
    ) throws IOException {
        String currentUrl = urlStr != null ? urlStr.trim() : "";
        int limit = maxRedirects > 0 ? maxRedirects : MAX_REDIRECTS;
        RedirectCredentialPolicy policy = credentialPolicy != null ? credentialPolicy : SAME_ORIGIN_CREDENTIALS;
        Map<String, String> activeHeaders = headers != null ? new HashMap<String, String>(headers) : null;

        for (int redirects = 0; redirects < limit; redirects++) {
            URL u = parseAllowedHttpUrl(currentUrl);
            if (urlValidator != null) {
                urlValidator.validate(u);
            }
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            boolean closingHandled = false;
            try {
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setInstanceFollowRedirects(false); // Manual loop preserves cross-protocol HTTP->HTTPS & credential stripping
                conn.setRequestMethod(method != null && !method.isEmpty() ? method : "GET");
                conn.setRequestProperty("User-Agent", userAgent());

                if (activeHeaders != null) {
                    for (Map.Entry<String, String> entry : activeHeaders.entrySet()) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
                if (listener != null) {
                    listener.onConnectionOpened(conn, u, activeHeaders);
                }

                int code = conn.getResponseCode();
                if (isRedirectStatus(code)) {
                    String loc = conn.getHeaderField("Location");
                    closingHandled = true;
                    if (listener != null) listener.onConnectionClosing(conn);
                    conn.disconnect();
                    if (loc == null || loc.trim().isEmpty()) {
                        throw new IOException("Redirect without Location header (HTTP " + code + ")");
                    }
                    URL nextUrl = new URL(u, loc.trim());
                    validateHttpProtocol(nextUrl);
                    if (urlValidator != null) {
                        urlValidator.validate(nextUrl);
                    }
                    if (activeHeaders != null && !policy.shouldRetainCredentials(u, nextUrl)) {
                        stripSensitiveHeaders(activeHeaders);
                    }
                    currentUrl = nextUrl.toString();
                    continue;
                }
                return conn;
            } catch (IOException e) {
                if (!closingHandled) {
                    if (listener != null) listener.onConnectionClosing(conn);
                    conn.disconnect();
                }
                throw e;
            } catch (RuntimeException e) {
                if (!closingHandled) {
                    if (listener != null) listener.onConnectionClosing(conn);
                    conn.disconnect();
                }
                throw e;
            }
        }
        throw new IOException("Too many redirects (exceeded " + limit + ")");
    }

    private static URL parseAllowedHttpUrl(String urlStr) throws IOException {
        URL url;
        try {
            url = URI.create(urlStr.trim()).toURL();
        } catch (Exception e) {
            url = new URL(urlStr.trim());
        }
        validateHttpProtocol(url);
        return url;
    }

    private static void validateHttpProtocol(URL url) throws IOException {
        String proto = url.getProtocol() != null ? url.getProtocol().toLowerCase(Locale.ROOT) : "";
        if (!"http".equals(proto) && !"https".equals(proto)) {
            throw new IOException("Disallowed protocol in AcidHttp: " + proto);
        }
    }
}
