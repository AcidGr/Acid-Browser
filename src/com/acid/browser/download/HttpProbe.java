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
                        try {
                            totalLength = Long.parseLong(cr.substring(slash + 1).trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } else if (code == 200) {
                // Server ignored Range header and returned full stream
                supportsRange = false;
                totalLength = conn.getContentLengthLong();
            }

            if (totalLength <= 0) {
                totalLength = conn.getContentLengthLong();
            }

            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            if ("bytes".equalsIgnoreCase(acceptRanges)) {
                supportsRange = true;
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
     * Parses filename from Content-Disposition header (supports filename* and filename).
     */
    public static String parseFileNameFromDisposition(String disposition) {
        if (disposition == null || disposition.isEmpty()) return null;

        // Try RFC 5987 / 6266 filename*=UTF-8''...
        int fnStar = disposition.indexOf("filename*=");
        if (fnStar >= 0) {
            String val = disposition.substring(fnStar + 10).trim();
            int semi = val.indexOf(';');
            if (semi >= 0) val = val.substring(0, semi).trim();
            if (val.toLowerCase().startsWith("utf-8''")) {
                try {
                    return URLDecoder.decode(val.substring(7), "UTF-8");
                } catch (Exception ignored) {}
            }
        }

        // Try standard filename="..."
        int fn = disposition.indexOf("filename=");
        if (fn >= 0) {
            String val = disposition.substring(fn + 9).trim();
            int semi = val.indexOf(';');
            if (semi >= 0) val = val.substring(0, semi).trim();
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
            if (!val.isEmpty()) return val;
        }

        return null;
    }
}
