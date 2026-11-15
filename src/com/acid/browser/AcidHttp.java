package com.acid.browser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    public static UrlValidator createDownloadValidator(final String initialUrlStr) {
        final boolean initialIsPrivateOrLocal = isPrivateOrLocalEndpoint(initialUrlStr);
        URL parsedInitial = null;
        try {
            parsedInitial = initialUrlStr != null ? new URL(initialUrlStr.trim()) : null;
        } catch (Exception ignored) {}
        final String initialHost = parsedInitial != null ? normalizeHost(parsedInitial.getHost()) : "";

        return new UrlValidator() {
            @Override
            public void validate(URL url) throws IOException {
                try {
                    String targetHost = url != null ? normalizeHost(url.getHost()) : "";
                    boolean blockLocal = !initialIsPrivateOrLocal || !initialHost.equals(targetHost);
                    validateHttpTargetForSsrf(url, blockLocal, "AcidDownload");
                } catch (SecurityException se) {
                    throw new IOException(se.getMessage(), se);
                }
            }
        };
    }

    private static boolean isPrivateOrLocalEndpoint(String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) return false;
        try {
            URL u = new URL(urlStr.trim());
            String host = normalizeHost(u.getHost());
            if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
                host = host.substring(1, host.length() - 1);
            }
            if (host.isEmpty()) return false;
            if (HttpControlServer.isLoopbackOrLocalLiteralHost(host) || host.endsWith(".local")) {
                return true;
            }
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || isIpv4CgnatAddress(addr) || isBlockedIpv6UlaAddress(addr, host)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isIpv4CgnatAddress(InetAddress addr) {
        if (addr == null) return false;
        byte[] raw = addr.getAddress();
        if (raw == null || raw.length != 4) return false;
        return (raw[0] & 0xFF) == 100 && (raw[1] & 0xC0) == 64;
    }

    private static boolean isAlibabaCloudMetadataAddress(InetAddress addr) {
        if (addr == null) return false;
        byte[] raw = addr.getAddress();
        if (raw == null || raw.length != 4) return false;
        return (raw[0] & 0xFF) == 100
            && (raw[1] & 0xFF) == 100
            && (raw[2] & 0xFF) == 100
            && (raw[3] & 0xFF) == 200;
    }

    private static boolean isIpv6UlaAddress(InetAddress addr) {
        if (addr == null) return false;
        byte[] raw = addr.getAddress();
        return raw != null && raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
    }

    /**
     * Checks whether an IPv6 address lies in Clash/Mihomo's default Fake-IPv6 prefix
     * {@code fdfe:dcba:9876::/48} so TUN proxy DNS mappings do not trigger false SSRF blocks.
     */
    private static boolean isClashFakeIpv6Address(InetAddress addr) {
        if (addr == null) return false;
        byte[] raw = addr.getAddress();
        return raw != null && raw.length == 16
            && (raw[0] & 0xFF) == 0xFD && (raw[1] & 0xFF) == 0xFE
            && (raw[2] & 0xFF) == 0xDC && (raw[3] & 0xFF) == 0xBA
            && (raw[4] & 0xFF) == 0x98 && (raw[5] & 0xFF) == 0x76;
    }

    private static boolean isIpLiteralHost(String normalizedHost) {
        if (normalizedHost == null || normalizedHost.isEmpty()) return false;
        if (normalizedHost.indexOf(':') >= 0) return true;
        for (int i = 0; i < normalizedHost.length(); i++) {
            char c = normalizedHost.charAt(i);
            if ((c < '0' || c > '9') && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlockedIpv6UlaAddress(InetAddress addr, String normalizedHost) {
        if (!isIpv6UlaAddress(addr)) return false;
        return isIpLiteralHost(normalizedHost) || !isClashFakeIpv6Address(addr);
    }

    private static boolean isAwsIpv6MetadataAddress(InetAddress addr) {
        if (addr == null) return false;
        byte[] raw = addr.getAddress();
        if (raw == null || raw.length != 16) return false;
        return (raw[0] & 0xFF) == 0xFD && raw[1] == 0
            && raw[2] == 0x0E && (raw[3] & 0xFF) == 0xC2
            && raw[4] == 0 && raw[5] == 0 && raw[6] == 0 && raw[7] == 0
            && raw[8] == 0 && raw[9] == 0 && raw[10] == 0 && raw[11] == 0
            && raw[12] == 0 && raw[13] == 0
            && (raw[14] & 0xFF) == 0x02 && (raw[15] & 0xFF) == 0x54;
    }

    public static final UrlValidator REMOTE_FETCH_VALIDATOR = new UrlValidator() {
        @Override
        public void validate(URL url) throws IOException {
            try {
                validateHttpTargetForSsrf(url, true, "AcidHttp");
            } catch (SecurityException se) {
                throw new IOException(se.getMessage(), se);
            }
        }
    };

    public static final Set<String> PUBLIC_SUFFIXES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "com", "cn", "net", "org", "edu", "gov", "io", "cc", "me", "info", "top", "xyz", "biz", "app", "dev", "ai",
        "co", "tv", "fm", "ly", "gg", "sh", "to", "uk", "jp", "de", "fr", "kr", "tw", "hk", "sg", "au", "ca", "in", "br", "ru",
        "com.cn", "net.cn", "org.cn", "edu.cn", "gov.cn", "ac.cn",
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "blogspot.jp",
        "com.hk", "org.hk", "edu.hk",
        "com.tw", "org.tw", "net.tw",
        "com.sg", "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.kr", "or.kr", "co.in", "net.in", "org.in", "com.br", "org.br",
        "com.mx", "com.tr", "co.za", "co.nz", "co.id", "co.il", "co.th", "com.my", "com.ph", "com.vn",
        "github.io", "gitlab.io", "bitbucket.io", "vercel.app", "pages.dev", "netlify.app",
        "herokuapp.com", "appspot.com", "blogspot.com", "blogspot.co.uk", "wordpress.com", "workers.dev",
        "r2.dev", "fly.dev", "ondigitalocean.app", "azurewebsites.net", "cloudfront.net",
        "s3.amazonaws.com", "s3.us-east-1.amazonaws.com", "googleapis.com", "firebaseapp.com", "web.app",
        "eu.org", "us.org", "uk.com", "onrender.com", "railway.app", "deno.dev", "glitch.me"
    )));

    public static boolean isPublicSuffixOrTld(String domain) {
        if (domain == null || domain.trim().isEmpty()) return true;
        String d = domain.trim().toLowerCase(Locale.ROOT);
        while (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1).trim();
        }
        if (d.isEmpty() || !d.contains(".")) return true; // Single label like "com", "cn", "localhost"
        return PUBLIC_SUFFIXES.contains(d);
    }

    /**
     * Validates an HTTP(S) target URL against SSRF to the local Acid MCP gateway,
     * cloud link-local metadata endpoints, and (when {@code blockAllLoopbackAndLocal} is true)
     * any loopback / local-interface address.
     */
    public static void validateHttpTargetForSsrf(URL url, boolean blockAllLoopbackAndLocal, String callerTag)
            throws SecurityException {
        if (url == null) {
            throw new SecurityException("Null request URL");
        }
        String proto = url.getProtocol() != null ? url.getProtocol().toLowerCase(Locale.ROOT) : "";
        if (!"http".equals(proto) && !"https".equals(proto)) {
            throw new SecurityException("Unsupported protocol in " + callerTag + ": " + proto);
        }
        String rawHost = url.getHost() != null ? url.getHost().toLowerCase(Locale.ROOT).trim() : "";
        if (rawHost.startsWith("[") && rawHost.endsWith("]") && rawHost.length() > 2) {
            rawHost = rawHost.substring(1, rawHost.length() - 1);
        }
        while (rawHost.endsWith(".")) {
            rawHost = rawHost.substring(0, rawHost.length() - 1);
        }
        if (rawHost.isEmpty()) {
            throw new SecurityException("Empty host in " + callerTag);
        }
        int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
        int activeMcpPort = HttpControlServer.getActiveMcpPort();
        boolean isMcpPort = (port == HttpControlServer.DEFAULT_PORT) || (activeMcpPort > 0 && port == activeMcpPort);

        if ("metadata.google.internal".equals(rawHost) || "169.254.169.254".equals(rawHost)) {
            throw new SecurityException("Blocked SSRF access to cloud metadata endpoint: " + rawHost);
        }
        if ((blockAllLoopbackAndLocal || isMcpPort) && HttpControlServer.isLoopbackOrLocalLiteralHost(rawHost)) {
            throw new SecurityException("Blocked SSRF access to local/loopback target " + rawHost + ":" + port);
        }
        if (rawHost.endsWith(".local") && blockAllLoopbackAndLocal) {
            throw new SecurityException("Blocked SSRF access to mDNS/local host " + rawHost);
        }

        try {
            InetAddress[] addrs = InetAddress.getAllByName(rawHost);
            String lanIp = HttpControlServer.getLocalIpAddress();
            for (InetAddress addr : addrs) {
                if (addr.isLinkLocalAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()
                    || isAlibabaCloudMetadataAddress(addr) || isAwsIpv6MetadataAddress(addr)) {
                    throw new SecurityException("Blocked SSRF access to link-local/any/multicast/metadata target " + rawHost);
                }
                if (addr.isLoopbackAddress() && (blockAllLoopbackAndLocal || isMcpPort)) {
                    throw new SecurityException("Blocked SSRF access to loopback address " + rawHost + ":" + port);
                }
                String hostIp = addr.getHostAddress();
                if (lanIp != null && lanIp.equals(hostIp) && (blockAllLoopbackAndLocal || isMcpPort)) {
                    throw new SecurityException("Blocked SSRF access to local device address " + rawHost + ":" + port);
                }
                if ((blockAllLoopbackAndLocal || isMcpPort)
                    && (addr.isSiteLocalAddress() || isIpv4CgnatAddress(addr) || isBlockedIpv6UlaAddress(addr, rawHost))) {
                    throw new SecurityException("Blocked SSRF access to private/internal network address " + rawHost + ":" + port);
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (UnknownHostException ignored) {
            // Defer DNS resolution failure cleanly (matches offline unit tests such as https://example.com/api).
        }
    }

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
        if ((toHost.endsWith("." + fromHost) && !isPublicSuffixOrTld(fromHost))
            || (fromHost.endsWith("." + toHost) && !isPublicSuffixOrTld(toHost))) {
            return true;
        }
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
        if (isPublicSuffixOrTld(parent)) {
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
                    while ((n = in.read(buf)) != -1) {
                        if (n == 0) continue;
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
            REMOTE_FETCH_VALIDATOR, SAME_ORIGIN_CREDENTIALS, null
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

        for (int redirects = 0; redirects <= limit; redirects++) {
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
