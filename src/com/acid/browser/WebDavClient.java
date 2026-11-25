package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Lightweight, zero-dependency WebDAV HTTP/1.1 client for Acid Browser Sync &amp; Backup.
 *
 * <p>Uses raw TCP / TLS sockets for WebDAV extension verbs ({@code PROPFIND}, {@code OPTIONS},
 * {@code MKCOL}) because Android's {@link HttpURLConnection} rejects non-standard HTTP verbs,
 * and delegates {@code PUT}/{@code GET} transfers through {@link AcidHttp} redirect, SSRF, and
 * cross-origin credential-stripping policies.
 *
 * <p>Credentials and WebDAV configuration are isolated in private {@link SharedPreferences}
 * ({@link #PREF_NAME}) and are never serialized into workspace backups or logs.
 */
public final class WebDavClient {

    public static final String PREF_NAME = "acid_webdav_sync";

    private static final String KEY_SERVER_URL = "webdav_url";
    private static final String KEY_REMOTE_FOLDER = "webdav_folder";
    private static final String KEY_USERNAME = "webdav_username";
    private static final String KEY_PASSWORD = "webdav_password";

    private static final String KEY_SYNC_BOOKMARKS = "sync_bookmarks";
    private static final String KEY_SYNC_SETTINGS = "sync_settings";
    private static final String KEY_SYNC_USERSCRIPTS = "sync_userscripts";
    private static final String KEY_SYNC_ADBLOCK = "sync_adblock";
    private static final String KEY_SYNC_SKILLS_HOMEPAGE = "sync_skills_homepage";

    private static final String KEY_LAST_SYNC_TIME = "sync_last_time";
    private static final String KEY_LAST_SYNC_SUMMARY = "sync_last_summary";

    public static final String DEFAULT_REMOTE_FOLDER = "AcidBrowser";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private static final String PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        + "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/></D:prop></D:propfind>";

    private static final char[] BASE64_TABLE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private WebDavClient() {}

    public static final class WebDavConfig {
        public final String serverUrl;
        public final String remoteFolder;
        public final String username;
        public final String password;

        public WebDavConfig(String serverUrl, String remoteFolder, String username, String password) {
            this.serverUrl = normalizeServerUrl(serverUrl);
            this.remoteFolder = normalizeRemoteFolder(remoteFolder);
            this.username = sanitizeCredential(username);
            this.password = sanitizeCredential(password);
        }

        public boolean isConfigured() {
            return !serverUrl.isEmpty();
        }

        public List<String> getFolderSegments() {
            List<String> segs = new ArrayList<String>();
            if (remoteFolder.isEmpty()) return segs;
            String[] parts = remoteFolder.split("/");
            for (String p : parts) {
                if (!p.isEmpty()) segs.add(p);
            }
            return segs;
        }

        public String resolveTargetDirectoryUrl() {
            if (serverUrl.isEmpty()) return "";
            List<String> segs = getFolderSegments();
            if (segs.isEmpty()) return serverUrl;
            StringBuilder sb = new StringBuilder(serverUrl);
            for (String seg : segs) {
                sb.append('/').append(encodePathSegment(seg));
            }
            return sb.toString();
        }

        public String resolveRemoteFileUrl(String fileName) {
            String base = resolveTargetDirectoryUrl();
            if (base.isEmpty() || fileName == null || fileName.isEmpty()) return "";
            return base + "/" + encodePathSegment(fileName);
        }

        public String getMaskedDisplayUrl() {
            if (serverUrl.isEmpty()) return "";
            return remoteFolder.isEmpty() ? serverUrl : (serverUrl + "/" + remoteFolder);
        }
    }

    public static final class SyncSelection {
        public boolean bookmarks = true;
        public boolean settings = true;
        public boolean userscripts = true;
        public boolean adblock = true;
        public boolean skillsAndHomepage = true;

        public SyncSelection() {}

        public SyncSelection(
            boolean bookmarks,
            boolean settings,
            boolean userscripts,
            boolean adblock,
            boolean skillsAndHomepage
        ) {
            this.bookmarks = bookmarks;
            this.settings = settings;
            this.userscripts = userscripts;
            this.adblock = adblock;
            this.skillsAndHomepage = skillsAndHomepage;
        }

        public boolean hasAny() {
            return bookmarks || settings || userscripts || adblock || skillsAndHomepage;
        }

        public boolean hasArchiveModules() {
            return settings || userscripts || adblock || skillsAndHomepage;
        }
    }

    public static final class SyncStatus {
        public final long timestampMs;
        public final String summary;

        public SyncStatus(long timestampMs, String summary) {
            this.timestampMs = timestampMs;
            this.summary = summary != null ? summary : "";
        }
    }

    public static final class WebDavResponse {
        public final int statusCode;
        public final byte[] body;
        public final Map<String, String> headers;

        public WebDavResponse(int statusCode, byte[] body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body != null ? body : new byte[0];
            this.headers = headers != null ? headers : new HashMap<String, String>();
        }
    }

    public static final class WebDavException extends IOException {
        public final int statusCode;

        public WebDavException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public WebDavException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }
    }

    // =========================================================================
    // Preference Storage (Isolated from settings.json / backups)
    // =========================================================================

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static WebDavConfig loadConfig(Context context) {
        if (context == null) {
            return new WebDavConfig("", DEFAULT_REMOTE_FOLDER, "", "");
        }
        SharedPreferences sp = prefs(context);
        return new WebDavConfig(
            sp.getString(KEY_SERVER_URL, ""),
            sp.getString(KEY_REMOTE_FOLDER, DEFAULT_REMOTE_FOLDER),
            sp.getString(KEY_USERNAME, ""),
            sp.getString(KEY_PASSWORD, "")
        );
    }

    public static void saveConfig(Context context, WebDavConfig config) {
        if (context == null || config == null) return;
        prefs(context).edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_REMOTE_FOLDER, config.remoteFolder)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply();
    }

    public static void clearConfig(Context context) {
        if (context == null) return;
        prefs(context).edit()
            .remove(KEY_SERVER_URL)
            .putString(KEY_REMOTE_FOLDER, DEFAULT_REMOTE_FOLDER)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_LAST_SYNC_TIME)
            .remove(KEY_LAST_SYNC_SUMMARY)
            .apply();
    }

    public static SyncSelection loadSyncSelection(Context context) {
        if (context == null) return new SyncSelection();
        SharedPreferences sp = prefs(context);
        return new SyncSelection(
            sp.getBoolean(KEY_SYNC_BOOKMARKS, true),
            sp.getBoolean(KEY_SYNC_SETTINGS, true),
            sp.getBoolean(KEY_SYNC_USERSCRIPTS, true),
            sp.getBoolean(KEY_SYNC_ADBLOCK, true),
            sp.getBoolean(KEY_SYNC_SKILLS_HOMEPAGE, true)
        );
    }

    public static void saveSyncSelection(Context context, SyncSelection sel) {
        if (context == null || sel == null) return;
        prefs(context).edit()
            .putBoolean(KEY_SYNC_BOOKMARKS, sel.bookmarks)
            .putBoolean(KEY_SYNC_SETTINGS, sel.settings)
            .putBoolean(KEY_SYNC_USERSCRIPTS, sel.userscripts)
            .putBoolean(KEY_SYNC_ADBLOCK, sel.adblock)
            .putBoolean(KEY_SYNC_SKILLS_HOMEPAGE, sel.skillsAndHomepage)
            .apply();
    }

    public static SyncStatus loadLastSyncStatus(Context context) {
        if (context == null) return new SyncStatus(0L, "");
        SharedPreferences sp = prefs(context);
        return new SyncStatus(
            sp.getLong(KEY_LAST_SYNC_TIME, 0L),
            sp.getString(KEY_LAST_SYNC_SUMMARY, "")
        );
    }

    public static void saveLastSyncStatus(Context context, String summary) {
        if (context == null) return;
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_SYNC_SUMMARY, summary != null ? summary : "")
            .apply();
    }

    // =========================================================================
    // Normalization & Validation
    // =========================================================================

    public static String normalizeServerUrl(String raw) {
        if (raw == null) return "";
        String cleaned = stripControlChars(raw).trim();
        if (cleaned.isEmpty()) return "";
        try {
            URL u = new URL(cleaned);
            String proto = u.getProtocol() != null ? u.getProtocol().toLowerCase(Locale.ROOT) : "";
            if (!"http".equals(proto) && !"https".equals(proto)) {
                return "";
            }
            if (u.getHost() == null || u.getHost().trim().isEmpty()) {
                return "";
            }
            String path = u.getPath() != null ? u.getPath() : "";
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            StringBuilder out = new StringBuilder();
            out.append(proto).append("://");
            String host = u.getHost();
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
                out.append('[').append(host).append(']');
            } else {
                out.append(host);
            }
            int port = u.getPort();
            if (port != -1 && port != u.getDefaultPort()) {
                out.append(':').append(port);
            }
            out.append(path);
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String normalizeRemoteFolder(String raw) {
        if (raw == null) return "";
        String cleaned = stripControlChars(raw).replace('\\', '/').trim();
        if (cleaned.isEmpty()) return "";
        String[] parts = cleaned.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(seg);
        }
        String res = sb.toString();
        if (res.length() > 128) {
            res = res.substring(0, 128);
            while (res.endsWith("/")) {
                res = res.substring(0, res.length() - 1);
            }
        }
        return res;
    }

    private static String sanitizeCredential(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\r' && c != '\n' && c != '\0') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String stripControlChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String encodePathSegment(String seg) {
        try {
            return URLEncoder.encode(seg, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return seg;
        }
    }

    public static void validateConfigOrThrow(WebDavConfig cfg) throws WebDavException {
        if (cfg == null || !cfg.isConfigured()) {
            throw new WebDavException(0, "请输入有效的 http:// 或 https:// URL");
        }
        try {
            AcidHttp.UrlValidator validator = AcidHttp.createDownloadValidator(cfg.serverUrl);
            validator.validate(URI.create(cfg.serverUrl).toURL());
        } catch (SecurityException se) {
            throw new WebDavException(0, "WebDAV 地址被安全策略拦截：" + se.getMessage(), se);
        } catch (Exception e) {
            throw new WebDavException(0, "WebDAV 服务器地址格式无效", e);
        }
    }

    // =========================================================================
    // Pure-Java Base64 & Error Messages
    // =========================================================================

    static String encodeBase64Utf8(String input) {
        byte[] data = (input != null ? input : "").getBytes(StandardCharsets.UTF_8);
        if (data.length == 0) return "";
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            boolean has1 = i < data.length;
            int b1 = has1 ? (data[i++] & 0xFF) : 0;
            boolean has2 = i < data.length;
            int b2 = has2 ? (data[i++] & 0xFF) : 0;

            out.append(BASE64_TABLE[b0 >> 2]);
            out.append(BASE64_TABLE[((b0 & 0x03) << 4) | (b1 >> 4)]);
            out.append(has1 ? BASE64_TABLE[((b1 & 0x0F) << 2) | (b2 >> 6)] : '=');
            out.append(has2 ? BASE64_TABLE[b2 & 0x3F] : '=');
        }
        return out.toString();
    }

    private static Map<String, String> buildAuthHeaders(WebDavConfig cfg) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (cfg != null && (!cfg.username.isEmpty() || !cfg.password.isEmpty())) {
            String token = encodeBase64Utf8(cfg.username + ":" + cfg.password);
            headers.put("Authorization", "Basic " + token);
        }
        return headers;
    }

    public static String formatHttpError(int code, String actionLabel) {
        String action = (actionLabel != null && !actionLabel.isEmpty()) ? actionLabel : "WebDAV 请求";
        if (code == 401 || code == 403) {
            return "认证失败（HTTP " + code + "），请检查用户名或应用专用密码";
        }
        if (code == 404) {
            return "云端路径或备份文件不存在（HTTP 404）";
        }
        if (code == 405 || code == 409) {
            return "云端目录尚未创建或服务权限不足（HTTP " + code + "）";
        }
        if (code == 413 || code == 507) {
            return "云端存储空间不足或单文件超出限制（HTTP " + code + "）";
        }
        return action + "失败（HTTP " + code + "）";
    }

    // =========================================================================
    // High-Level WebDAV Operations
    // =========================================================================

    /**
     * Verifies connectivity and authentication against the configured WebDAV server.
     *
     * @return a human-readable connection status message on success
     */
    public static String testConnection(WebDavConfig cfg) throws IOException {
        validateConfigOrThrow(cfg);
        Map<String, String> rootHeaders = buildAuthHeaders(cfg);
        rootHeaders.put("Depth", "0");
        rootHeaders.put("Content-Type", "application/xml; charset=utf-8");

        WebDavResponse rootResp = executeWebDavMethod(
            cfg.serverUrl, "PROPFIND", rootHeaders,
            PROPFIND_BODY.getBytes(StandardCharsets.UTF_8),
            AcidHttp.createDownloadValidator(cfg.serverUrl)
        );
        if (rootResp.statusCode == 405) {
            Map<String, String> optHeaders = buildAuthHeaders(cfg);
            rootResp = executeWebDavMethod(
                cfg.serverUrl, "OPTIONS", optHeaders, null,
                AcidHttp.createDownloadValidator(cfg.serverUrl)
            );
        }
        if (rootResp.statusCode == 401 || rootResp.statusCode == 403) {
            throw new WebDavException(rootResp.statusCode, formatHttpError(rootResp.statusCode, "连接验证"));
        }
        if (rootResp.statusCode < 200 || rootResp.statusCode >= 300) {
            throw new WebDavException(rootResp.statusCode, formatHttpError(rootResp.statusCode, "连接测试"));
        }

        if (!cfg.remoteFolder.isEmpty()) {
            String targetDirUrl = cfg.resolveTargetDirectoryUrl();
            Map<String, String> subHeaders = buildAuthHeaders(cfg);
            subHeaders.put("Depth", "0");
            subHeaders.put("Content-Type", "application/xml; charset=utf-8");
            WebDavResponse subResp = executeWebDavMethod(
                targetDirUrl, "PROPFIND", subHeaders,
                PROPFIND_BODY.getBytes(StandardCharsets.UTF_8),
                AcidHttp.createDownloadValidator(cfg.serverUrl)
            );
            if (subResp.statusCode == 401 || subResp.statusCode == 403) {
                throw new WebDavException(subResp.statusCode, formatHttpError(subResp.statusCode, "访问同步目录"));
            }
            if (subResp.statusCode == 404) {
                return "连接成功（云端同步目录将在首次上传时自动创建）";
            }
            if (subResp.statusCode >= 200 && subResp.statusCode < 300) {
                return "连接成功，云端同步目录已就绪";
            }
        }
        return "连接成功，WebDAV 服务可用";
    }

    /**
     * Ensures the configured {@code remoteFolder} chain exists on the WebDAV server via {@code MKCOL}.
     */
    public static void ensureRemoteDirectory(WebDavConfig cfg) throws IOException {
        validateConfigOrThrow(cfg);
        AcidHttp.UrlValidator validator = AcidHttp.createDownloadValidator(cfg.serverUrl);
        List<String> segs = cfg.getFolderSegments();
        if (segs.isEmpty()) return;

        StringBuilder cur = new StringBuilder(cfg.serverUrl);
        for (int i = 0; i < segs.size(); i++) {
            cur.append('/').append(encodePathSegment(segs.get(i)));
            String dirUrl = cur.toString();

            Map<String, String> probeHeaders = buildAuthHeaders(cfg);
            probeHeaders.put("Depth", "0");
            probeHeaders.put("Content-Type", "application/xml; charset=utf-8");
            WebDavResponse probe = executeWebDavMethod(
                dirUrl, "PROPFIND", probeHeaders,
                PROPFIND_BODY.getBytes(StandardCharsets.UTF_8),
                validator
            );
            if (probe.statusCode >= 200 && probe.statusCode < 300) {
                continue;
            }
            if (probe.statusCode == 401 || probe.statusCode == 403) {
                throw new WebDavException(probe.statusCode, formatHttpError(probe.statusCode, "检查同步目录"));
            }
            // RFC 4918: 404 (not found) or 405 (method disabled for dir probe) -> attempt MKCOL
            if (probe.statusCode == 404 || probe.statusCode == 405 || probe.statusCode == 400) {
                WebDavResponse mkResp = executeWebDavMethod(
                    dirUrl, "MKCOL", buildAuthHeaders(cfg), null, validator
                );
                if (mkResp.statusCode == 200
                    || mkResp.statusCode == 201
                    || mkResp.statusCode == 204
                    || mkResp.statusCode == 405) {
                    continue;
                }
                throw new WebDavException(mkResp.statusCode, formatHttpError(mkResp.statusCode, "创建云端目录"));
            }
            throw new WebDavException(probe.statusCode, formatHttpError(probe.statusCode, "检查同步目录"));
        }
    }

    /**
     * Uploads a file payload to the remote target directory via HTTP {@code PUT}.
     */
    public static void putBytes(
        WebDavConfig cfg,
        String remoteFileName,
        String contentType,
        final byte[] data
    ) throws IOException {
        validateConfigOrThrow(cfg);
        String targetUrl = cfg.resolveRemoteFileUrl(remoteFileName);
        if (targetUrl.isEmpty()) {
            throw new WebDavException(0, "无效的远端文件名");
        }
        final byte[] payload = data != null ? data : new byte[0];
        Map<String, String> headers = buildAuthHeaders(cfg);
        if (contentType != null && !contentType.isEmpty()) {
            headers.put("Content-Type", contentType);
        }

        HttpURLConnection conn = AcidHttp.openConnectionWithRedirects(
            targetUrl,
            "PUT",
            headers,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
            AcidHttp.MAX_REDIRECTS,
            AcidHttp.createDownloadValidator(cfg.serverUrl),
            AcidHttp.SAME_ORIGIN_CREDENTIALS,
            new AcidHttp.RedirectListener() {
                @Override
                public void onConnectionOpened(HttpURLConnection c, URL url, Map<String, String> activeHeaders)
                        throws IOException {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(payload.length);
                    OutputStream os = c.getOutputStream();
                    try {
                        os.write(payload);
                        os.flush();
                    } finally {
                        AcidIo.closeQuietly(os);
                    }
                }

                @Override
                public void onConnectionClosing(HttpURLConnection c) {}
            }
        );
        try {
            int code = conn.getResponseCode();
            if (code == 200 || code == 201 || code == 204) {
                return;
            }
            throw new WebDavException(code, formatHttpError(code, "上传 " + remoteFileName));
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Downloads a remote file from the configured target directory via HTTP {@code GET}.
     *
     * @throws WebDavException with {@link WebDavException#statusCode} {@code == 404} if absent
     */
    public static byte[] getBytes(WebDavConfig cfg, String remoteFileName, int maxBytes) throws IOException {
        validateConfigOrThrow(cfg);
        String targetUrl = cfg.resolveRemoteFileUrl(remoteFileName);
        if (targetUrl.isEmpty()) {
            throw new WebDavException(0, "无效的远端文件名");
        }
        Map<String, String> headers = buildAuthHeaders(cfg);
        HttpURLConnection conn = AcidHttp.openConnectionWithRedirects(
            targetUrl,
            "GET",
            headers,
            CONNECT_TIMEOUT_MS,
            READ_TIMEOUT_MS,
            AcidHttp.MAX_REDIRECTS,
            AcidHttp.createDownloadValidator(cfg.serverUrl),
            AcidHttp.SAME_ORIGIN_CREDENTIALS,
            null
        );
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new WebDavException(code, formatHttpError(code, "下载 " + remoteFileName));
            }
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (maxBytes > 0 && bos.size() + n > maxBytes) {
                        throw new WebDavException(0, "云端备份文件体积超出安全上限");
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

    // =========================================================================
    // Raw HTTP/1.1 Extension Transport (PROPFIND / OPTIONS / MKCOL)
    // =========================================================================

    static WebDavResponse executeWebDavMethod(
        String initialUrl,
        String method,
        Map<String, String> headers,
        byte[] body,
        AcidHttp.UrlValidator validator
    ) throws IOException {
        String currentUrl = initialUrl;
        Map<String, String> activeHeaders = headers != null
            ? new LinkedHashMap<String, String>(headers)
            : new LinkedHashMap<String, String>();

        for (int redirects = 0; redirects <= AcidHttp.MAX_REDIRECTS; redirects++) {
            URL u = new URL(currentUrl);
            String proto = u.getProtocol() != null ? u.getProtocol().toLowerCase(Locale.ROOT) : "";
            if (!"http".equals(proto) && !"https".equals(proto)) {
                throw new IOException("Disallowed WebDAV protocol: " + proto);
            }
            if (validator != null) {
                validator.validate(u);
            }
            WebDavResponse resp = sendSingleRawHttp(u, method, activeHeaders, body);
            if (AcidHttp.isRedirectStatus(resp.statusCode)) {
                String loc = resp.headers.get("location");
                if (loc == null || loc.trim().isEmpty()) {
                    throw new WebDavException(resp.statusCode, "云端重定向缺少 Location 头");
                }
                URL nextUrl = new URL(u, loc.trim());
                if (validator != null) {
                    validator.validate(nextUrl);
                }
                if (!AcidHttp.SAME_ORIGIN_CREDENTIALS.shouldRetainCredentials(u, nextUrl)) {
                    AcidHttp.stripSensitiveHeaders(activeHeaders);
                }
                currentUrl = nextUrl.toString();
                continue;
            }
            return resp;
        }
        throw new WebDavException(0, "WebDAV 重定向次数过多");
    }

    private static WebDavResponse sendSingleRawHttp(
        URL url,
        String method,
        Map<String, String> headers,
        byte[] body
    ) throws IOException {
        boolean isHttps = "https".equalsIgnoreCase(url.getProtocol());
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();

        Socket socket = null;
        try {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            plain.setSoTimeout(READ_TIMEOUT_MS);
            if (isHttps) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
                ssl.setSoTimeout(READ_TIMEOUT_MS);
                ssl.startHandshake();
                HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
                if (!hv.verify(host, ssl.getSession())) {
                    AcidIo.closeQuietly(ssl);
                    throw new IOException("TLS 主机名校验失败：" + host);
                }
                socket = ssl;
            } else {
                socket = plain;
            }

            String rawPath = url.getFile();
            if (rawPath == null || rawPath.isEmpty()) {
                rawPath = "/";
            }
            if (rawPath.indexOf('\r') >= 0 || rawPath.indexOf('\n') >= 0) {
                throw new IOException("Invalid request target");
            }

            StringBuilder req = new StringBuilder(256);
            req.append(method).append(' ').append(rawPath).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(formatHostHeader(host, port, url.getDefaultPort())).append("\r\n");
            req.append("User-Agent: ").append(AcidHttp.userAgent()).append("\r\n");
            req.append("Connection: close\r\n");

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String k = entry.getKey();
                    String v = entry.getValue();
                    if (k == null || v == null) continue;
                    if (k.indexOf('\r') >= 0 || k.indexOf('\n') >= 0
                        || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
                        continue;
                    }
                    String lower = k.toLowerCase(Locale.ROOT);
                    if ("host".equals(lower) || "connection".equals(lower) || "content-length".equals(lower)) {
                        continue;
                    }
                    req.append(k).append(": ").append(v).append("\r\n");
                }
            }
            if (body != null && body.length > 0) {
                req.append("Content-Length: ").append(body.length).append("\r\n");
            }
            req.append("\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null && body.length > 0) {
                os.write(body);
            }
            os.flush();

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            return readHttpResponse(in);
        } finally {
            AcidIo.closeQuietly(socket);
        }
    }

    private static String formatHostHeader(String host, int port, int defaultPort) {
        String h = host != null ? host : "";
        if (h.indexOf(':') >= 0 && !h.startsWith("[")) {
            h = "[" + h + "]";
        }
        return (port != -1 && port != defaultPort) ? (h + ":" + port) : h;
    }

    private static WebDavResponse readHttpResponse(BufferedInputStream in) throws IOException {
        int statusCode;
        Map<String, String> respHeaders;

        while (true) {
            String headerBlock = readHeaderBlock(in);
            if (headerBlock.isEmpty()) {
                throw new EOFException("Empty HTTP response from WebDAV server");
            }
            String[] lines = headerBlock.split("\r\n");
            if (lines.length == 0) {
                throw new IOException("Malformed HTTP status line");
            }
            statusCode = parseStatusCode(lines[0]);
            respHeaders = new LinkedHashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String val = line.substring(colon + 1).trim();
                    respHeaders.put(name, val);
                }
            }
            if (statusCode >= 100 && statusCode < 200) {
                // Skip interim 100-Continue headers and read final response
                continue;
            }
            break;
        }

        byte[] respBody = readResponseBody(in, statusCode, respHeaders);
        return new WebDavResponse(statusCode, respBody, respHeaders);
    }

    private static String readHeaderBlock(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0; // Matches \r\n\r\n
        while (buf.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            buf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') {
                return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }
        if (buf.size() >= MAX_HEADER_BYTES) {
            throw new IOException("WebDAV response headers exceed limit");
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static int parseStatusCode(String statusLine) throws IOException {
        if (statusLine == null) throw new IOException("Null HTTP status line");
        String trimmed = statusLine.trim();
        int sp1 = trimmed.indexOf(' ');
        if (sp1 < 0) throw new IOException("Invalid HTTP status line: " + trimmed);
        int start = sp1 + 1;
        while (start < trimmed.length() && trimmed.charAt(start) == ' ') start++;
        int end = start;
        while (end < trimmed.length() && trimmed.charAt(end) >= '0' && trimmed.charAt(end) <= '9') {
            end++;
        }
        if (end == start) throw new IOException("Missing HTTP status code: " + trimmed);
        try {
            return Integer.parseInt(trimmed.substring(start, end));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid HTTP status code: " + trimmed, e);
        }
    }

    private static byte[] readResponseBody(
        BufferedInputStream in,
        int statusCode,
        Map<String, String> headers
    ) throws IOException {
        if (statusCode == 204 || statusCode == 304) {
            return new byte[0];
        }
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunkedBody(in, MAX_METADATA_BYTES);
        }
        String clStr = headers.get("content-length");
        if (clStr != null) {
            long contentLength = -1L;
            try {
                contentLength = Long.parseLong(clStr.trim());
            } catch (NumberFormatException ignored) {}
            if (contentLength == 0L) {
                return new byte[0];
            }
            if (contentLength > 0L) {
                int toRead = (int) Math.min(contentLength, (long) MAX_METADATA_BYTES);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(toRead);
                byte[] buf = new byte[4096];
                int remaining = toRead;
                while (remaining > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, remaining));
                    if (n == -1) break;
                    bos.write(buf, 0, n);
                    remaining -= n;
                }
                return bos.toByteArray();
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            int allow = Math.min(n, MAX_METADATA_BYTES - bos.size());
            if (allow > 0) bos.write(buf, 0, allow);
            if (bos.size() >= MAX_METADATA_BYTES) break;
        }
        return bos.toByteArray();
    }

    private static byte[] readChunkedBody(BufferedInputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLineCrlf(in);
            if (sizeLine == null) break;
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            if (hex.isEmpty()) continue;
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunked size: " + sizeLine, e);
            }
            if (chunkSize == 0) {
                // Consume optional trailer headers until blank line
                while (true) {
                    String trailer = readLineCrlf(in);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                break;
            }
            byte[] buf = new byte[4096];
            int remaining = chunkSize;
            while (remaining > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n == -1) throw new EOFException("Unexpected EOF inside HTTP chunk");
                int allow = Math.min(n, maxBytes - out.size());
                if (allow > 0) out.write(buf, 0, allow);
                remaining -= n;
            }
            readLineCrlf(in); // trailing CRLF after chunk data
        }
        return out.toByteArray();
    }

    private static String readLineCrlf(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int b;
        boolean seenAny = false;
        while ((b = in.read()) != -1) {
            seenAny = true;
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("Chunk header line too long");
        }
        if (!seenAny && b == -1) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
