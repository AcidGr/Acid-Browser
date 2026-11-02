package com.acid.browser.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
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

        public ProbeResult(String finalUrl,
                           long contentLength,
                           boolean supportsRange,
                           String mimeType,
                           String suggestedFileName,
                           int responseCode) {
            this.finalUrl = finalUrl;
            this.contentLength = contentLength;
            this.supportsRange = supportsRange;
            this.mimeType = mimeType;
            this.suggestedFileName = suggestedFileName;
            this.responseCode = responseCode;
        }
    }

    private HttpProbe() {}

    /**
     * Probes the given URL with a Range-test probe to discover length and Range support.
     */
    public static ProbeResult probe(String initialUrl, Map<String, String> headers) throws IOException {
        String currentUrl = initialUrl;
        int redirectCount = 0;

        while (redirectCount < MAX_REDIRECTS) {
            URL u = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false); // Manually handle to preserve headers

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
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
                URL nextUrl = new URL(new URL(currentUrl), loc);
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
                // Server ignored Range header and returned full stream
                supportsRange = false;
                totalLength = conn.getContentLengthLong();
                String acceptRanges = conn.getHeaderField("Accept-Ranges");
                if ("bytes".equalsIgnoreCase(acceptRanges)) {
                    supportsRange = true;
                }
            }

            String mimeType = conn.getContentType();
            String disposition = conn.getHeaderField("Content-Disposition");
            String fileName = parseFileNameFromDisposition(disposition);

            // Clean up stream safely
            try {
                InputStream is = conn.getInputStream();
                if (is != null) is.close();
            } catch (Exception ignored) {}
            conn.disconnect();

            return new ProbeResult(currentUrl, totalLength, supportsRange, mimeType, fileName, code);
        }

        throw new IOException("Too many redirects (exceeded " + MAX_REDIRECTS + ")");
    }

    /**
     * Parses filename from Content-Disposition header (supports filename* and filename,
     * case-insensitive, with automatic URL percent-decoding and sanitization).
     */
    public static String parseFileNameFromDisposition(String disposition) {
        if (disposition == null || disposition.isEmpty()) return null;

        String lower = disposition.toLowerCase();

        // 1. Try RFC 5987 / 6266 filename*=UTF-8''... (highest priority)
        int fnStar = lower.indexOf("filename*=");
        if (fnStar >= 0) {
            String val = disposition.substring(fnStar + 10).trim();
            int semi = val.indexOf(';');
            if (semi >= 0) val = val.substring(0, semi).trim();
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1).trim();
            }
            String valLower = val.toLowerCase();
            if (valLower.startsWith("utf-8''")) {
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
            int fn = lower.indexOf("filename=", searchFrom);
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

        // Decode percent-encoded UTF-8 up to 2 passes (handles single and double encoding)
        for (int pass = 0; pass < 2; pass++) {
            if (s.indexOf('%') < 0) break;
            try {
                String decoded = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
                if (decoded.equals(s)) break;
                s = decoded;
            } catch (Exception ignored) {
                break;
            }
        }

        // Strip any directory components
        int lastSlash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < s.length() - 1) {
            s = s.substring(lastSlash + 1);
        }

        // Replace filesystem-reserved characters
        s = s.replaceAll("[\\\\/:*?\"<>|;\\r\\n\\t\\x00]", "_").trim();
        return s.isEmpty() ? null : s;
    }
}
