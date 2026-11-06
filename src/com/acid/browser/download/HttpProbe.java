package com.acid.browser.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Map;

/**
 * Handles HTTP pre-flight probing, redirect chains, range-support verification,
 * and header extraction for download targets.
 */
public final class HttpProbe {

    public static final int CONNECT_TIMEOUT_MS = 10000;
    public static final int READ_TIMEOUT_MS = 15000;
    public static final int MAX_REDIRECTS = 5;

    public static class ProbeResult {
        public final String finalUrl;
        public final long contentLength;
        public final boolean supportsRange;
        public final String mimeType;
        public final String suggestedFileName;
        public final int responseCode;
        public final String eTag;
        public final String lastModified;

        public ProbeResult(String finalUrl,
                           long contentLength,
                           boolean supportsRange,
                           String mimeType,
                           String suggestedFileName,
                           int responseCode) {
            this(finalUrl, contentLength, supportsRange, mimeType, suggestedFileName, responseCode, null, null);
        }

        public ProbeResult(String finalUrl,
                           long contentLength,
                           boolean supportsRange,
                           String mimeType,
                           String suggestedFileName,
                           int responseCode,
                           String eTag,
                           String lastModified) {
            this.finalUrl = finalUrl;
            this.contentLength = contentLength;
            this.supportsRange = supportsRange;
            this.mimeType = mimeType;
            this.suggestedFileName = suggestedFileName;
            this.responseCode = responseCode;
            this.eTag = eTag;
            this.lastModified = lastModified;
        }
    }

    private HttpProbe() {}

    /**
     * Probes the given URL with a Range-test probe to discover length and Range support.
     */
    public static ProbeResult probe(String initialUrl, Map<String, String> headers) throws IOException {
        String currentUrl = initialUrl;
        int redirectCount = 0;
        Map<String, String> activeHeaders = headers != null
            ? new java.util.HashMap<String, String>(headers)
            : null;

        while (redirectCount < MAX_REDIRECTS) {
            URL u = new URL(currentUrl);
            String proto = u.getProtocol() != null ? u.getProtocol().toLowerCase(Locale.ROOT) : "";
            if (!"http".equals(proto) && !"https".equals(proto)) {
                throw new IOException("Unsupported download protocol: " + proto);
            }
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false); // Manually handle to enforce credential & protocol policy

            if (activeHeaders != null) {
                for (Map.Entry<String, String> entry : activeHeaders.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            // Probe with Range: bytes=0-0 to test both total length and HTTP 206 support in one shot
            conn.setRequestProperty("Range", "bytes=0-0");
            conn.connect();

            int code = conn.getResponseCode();

            // Redirect handling (301, 302, 303, 307, 308)
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) {
                    throw new IOException("Redirect without Location header (HTTP " + code + ")");
                }
                // Handle relative redirect URL
                URL nextUrl = new URL(u, loc.trim());
                String nextProto = nextUrl.getProtocol() != null ? nextUrl.getProtocol().toLowerCase(Locale.ROOT) : "";
                if (!"http".equals(nextProto) && !"https".equals(nextProto)) {
                    throw new IOException("Disallowed redirect protocol: " + nextProto);
                }
                if (activeHeaders != null && !shouldRetainCredentialsOnRedirect(u, nextUrl)) {
                    stripSensitiveHeaders(activeHeaders);
                }
                currentUrl = nextUrl.toString();
                redirectCount++;
                continue;
            }

            long totalLength = -1;
            boolean supportsRange = false;

            if (code == 206) {
                supportsRange = true;
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0 && slash < cr.length() - 1) {
                        String totalPart = cr.substring(slash + 1).trim();
                        if (!"*".equals(totalPart)) {
                            try {
                                totalLength = Long.parseLong(totalPart);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            } else if (code == 200) {
                // Server ignored Range header and returned full stream (ground truth overrides any Accept-Ranges header)
                supportsRange = false;
                totalLength = conn.getContentLengthLong();
            }

            String mimeType = (code >= 200 && code < 300) ? conn.getContentType() : null;
            String disposition = (code >= 200 && code < 300) ? conn.getHeaderField("Content-Disposition") : null;
            String fileName = parseFileNameFromDisposition(disposition);
            String eTag = (code >= 200 && code < 300) ? conn.getHeaderField("ETag") : null;
            String lastModified = (code >= 200 && code < 300) ? conn.getHeaderField("Last-Modified") : null;

            // Clean up stream safely
            try {
                InputStream is = conn.getInputStream();
                if (is != null) is.close();
            } catch (Exception ignored) {}
            conn.disconnect();

            return new ProbeResult(currentUrl, totalLength, supportsRange, mimeType, fileName, code, eTag, lastModified);
        }

        throw new IOException("Too many redirects (exceeded " + MAX_REDIRECTS + ")");
    }

    /**
     * Parses filename from Content-Disposition header (supports filename* and filename,
     * case-insensitive, with automatic URL percent-decoding and sanitization).
     */
    public static String parseFileNameFromDisposition(String disposition) {
        if (disposition == null || disposition.isEmpty()) return null;

        // 1. Try RFC 5987 / 6266 filename*=UTF-8''... (highest priority)
        int fnStar = indexOfIgnoreCase(disposition, "filename*=", 0);
        if (fnStar >= 0) {
            String val = disposition.substring(fnStar + 10).trim();
            int semi = val.indexOf(';');
            if (semi >= 0) val = val.substring(0, semi).trim();
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1).trim();
            }
            if (startsWithIgnoreCase(val, "utf-8''")) {
                val = val.substring(7);
            } else {
                int quoteQuote = val.indexOf("''");
                if (quoteQuote >= 0 && quoteQuote < 16) {
                    val = val.substring(quoteQuote + 2);
                }
            }
            String decoded = decodeAndSanitizeFileName(val);
            if (decoded != null && !decoded.isEmpty()) {
                return decoded;
            }
        }

        // 2. Try standard filename="..." or filename=...;
        int searchFrom = 0;
        while (searchFrom < disposition.length()) {
            int fn = indexOfIgnoreCase(disposition, "filename=", searchFrom);
            if (fn < 0) break;
            // Ensure we didn't match the tail of "filename*="
            if (fn > 0 && disposition.charAt(fn - 1) == '*') {
                searchFrom = fn + 9;
                continue;
            }
            String val = disposition.substring(fn + 9).trim();
            if (val.startsWith("\"")) {
                int endQuote = val.indexOf('"', 1);
                if (endQuote >= 1) {
                    val = val.substring(1, endQuote);
                } else {
                    val = val.substring(1);
                }
            } else if (val.startsWith("'")) {
                int endQuote = val.indexOf('\'', 1);
                if (endQuote >= 1) {
                    val = val.substring(1, endQuote);
                } else {
                    val = val.substring(1);
                }
            } else {
                int semi = val.indexOf(';');
                if (semi >= 0) val = val.substring(0, semi).trim();
            }
            String decoded = decodeAndSanitizeFileName(val);
            if (decoded != null && !decoded.isEmpty()) {
                return decoded;
            }
            searchFrom = fn + 9;
        }

        return null;
    }

    /**
     * Case-insensitive {@code indexOf} that never changes the string's length.
     *
     * <p>Replaces the previous {@code disposition.toLowerCase().indexOf(...)} pattern, which
     * returned offsets into a string that can be <em>longer</em> than the original:
     * {@code "İ".toLowerCase()} yields two chars, so the offset was then applied to the original
     * and could run past its end ({@code StringIndexOutOfBoundsException}) or slice mid-token and
     * silently corrupt the filename. Only U+0130 expands under case folding, but one hostile
     * Content-Disposition header was enough to crash the browser on the UI thread.
     *
     * <p>Comparison is ASCII-only case folding, which is all HTTP header tokens need.
     */
    static int indexOfIgnoreCase(String haystack, String needle, int fromIndex) {
        if (haystack == null || needle == null || needle.isEmpty()) return -1;
        int max = haystack.length() - needle.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (regionMatchesIgnoreCase(haystack, i, needle)) return i;
        }
        return -1;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s != null && prefix != null && s.length() >= prefix.length()
            && regionMatchesIgnoreCase(s, 0, prefix);
    }

    private static boolean regionMatchesIgnoreCase(String haystack, int offset, String needle) {
        for (int i = 0; i < needle.length(); i++) {
            char a = haystack.charAt(offset + i);
            char b = needle.charAt(i);
            if (a == b) continue;
            // ASCII-only folding: toLowerCase()/toUpperCase() on a char cannot change length.
            if (toLowerAscii(a) != toLowerAscii(b)) return false;
        }
        return true;
    }

    private static char toLowerAscii(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + ('a' - 'A')) : c;
    }

    /**
     * Percent-decodes UTF-8 sequences (preserving literal '+') and strips illegal path characters.
     */
    public static String decodeAndSanitizeFileName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Strip trailing "; filename*=..." if an upstream caller passed an unsplit value
        int semiIdx = s.indexOf(';');
        if (semiIdx > 0) {
            String tail = s.substring(semiIdx + 1).trim().toLowerCase();
            if (tail.startsWith("filename") || tail.startsWith("size=")) {
                s = s.substring(0, semiIdx).trim();
            }
        }

        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).trim();
        }

        // Decode percent-encoded UTF-8 in a single pass (prevents double-decoding %252e%252e%252f into ../)
        if (s.indexOf('%') >= 0) {
            try {
                s = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
            } catch (Exception ignored) {}
        }

        // Strip any directory components
        int lastSlash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            s = lastSlash < s.length() - 1 ? s.substring(lastSlash + 1) : "";
        }

        // Replace filesystem-reserved characters
        s = s.replaceAll("[\\\\/:*?\"<>|;\\r\\n\\t\\x00]", "_").trim();
        // Reject empty or dot-only directory entries (".", "..", "...")
        if (s.isEmpty() || s.replace(".", "").isEmpty()) {
            return null;
        }
        return s;
    }

    /**
     * Decides whether sensitive request headers ({@code Cookie}, {@code Authorization}) may be
     * carried across an HTTP redirect from {@code fromUrl} to {@code toUrl}.
     *
     * <p>Forbids {@code https -> http} cleartext downgrades and arbitrary cross-domain leaks, while
     * preserving credentials across same-site subdomains and paired cloud-drive CDN domains
     * (e.g. {@code d.pcs.baidu.com -> *.baidupcs.com}, {@code *.115.com -> *.115cdn.com}).
     */
    public static boolean shouldRetainCredentialsOnRedirect(URL fromUrl, URL toUrl) {
        if (fromUrl == null || toUrl == null) return false;
        String fromProto = fromUrl.getProtocol() != null ? fromUrl.getProtocol().toLowerCase(Locale.ROOT) : "";
        String toProto = toUrl.getProtocol() != null ? toUrl.getProtocol().toLowerCase(Locale.ROOT) : "";
        if ("https".equals(fromProto) && !"https".equals(toProto)) {
            return false; // Never leak HTTPS cookies/tokens onto cleartext HTTP
        }
        String fromHost = normalizeHost(fromUrl.getHost());
        String toHost = normalizeHost(toUrl.getHost());
        if (fromHost.isEmpty() || toHost.isEmpty()) return false;
        if (fromHost.equals(toHost)) {
            int fromPort = fromUrl.getPort() != -1 ? fromUrl.getPort() : fromUrl.getDefaultPort();
            int toPort = toUrl.getPort() != -1 ? toUrl.getPort() : toUrl.getDefaultPort();
            return fromPort == toPort;
        }
        // Same cloud-drive ecosystem CDN groups
        if (isBaiduDriveHost(fromHost) && isBaiduDriveHost(toHost)) return true;
        if (is115DriveHost(fromHost) && is115DriveHost(toHost)) return true;
        if (isSubdomainOf(fromHost, "quark.cn") && isSubdomainOf(toHost, "quark.cn")) return true;
        if (isSubdomainOf(fromHost, "uc.cn") && isSubdomainOf(toHost, "uc.cn")) return true;
        // Same registrable parent domain (e.g. files.example.com -> cdn.example.com)
        if (toHost.endsWith("." + fromHost) || fromHost.endsWith("." + toHost)) return true;
        String parentFrom = parentDomainIfSafe(fromHost);
        String parentTo = parentDomainIfSafe(toHost);
        return parentFrom != null && parentFrom.equals(parentTo);
    }

    public static void stripSensitiveHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return;
        java.util.Iterator<String> it = headers.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k == null) continue;
            String lower = k.trim().toLowerCase(Locale.ROOT);
            if ("cookie".equals(lower) || "authorization".equals(lower) || "proxy-authorization".equals(lower)) {
                it.remove();
            }
        }
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
        if (!parent.contains(".")) return null; // Never treat single-label TLD like "com" as shared parent
        if ("com.cn".equals(parent) || "net.cn".equals(parent) || "org.cn".equals(parent)
            || "co.uk".equals(parent) || "co.jp".equals(parent) || "com.hk".equals(parent)
            || "github.io".equals(parent) || "gitlab.io".equals(parent)) {
            return null;
        }
        return parent;
    }
}
