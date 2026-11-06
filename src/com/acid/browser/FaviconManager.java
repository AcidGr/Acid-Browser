package com.acid.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.webkit.WebResourceResponse;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Universal Favicon & Web Brand Icon Subsystem for Acid Browser.
 *
 * <p>Serves as the single authoritative source of truth for website favicons across all
 * consumers (Native Bookmarks/History Sheet, Homepage WebView, and Tab Previews):
 * <ul>
 *   <li>High-speed in-memory {@link LruCache} of decoded {@link Bitmap}s for smooth 120Hz list scrolling.</li>
 *   <li>Persistent disk storage under {@code getCacheDir()/favicons/<safe_host>.bin}.</li>
 *   <li>Automatic live favicon capture during WebView browsing ({@code onReceivedIcon}).</li>
 *   <li>Asynchronous multi-candidate network probing with 15-minute negative failure cooldown.</li>
 *   <li>Zero-copy Base64 Data URI generation for WebView preload injection.</li>
 * </ul>
 */
public final class FaviconManager {

    private static final String TAG = "AcidFaviconManager";
    private static final int MEM_CACHE_MAX_KB = 4 * 1024; // 4 MB in-memory bitmap cache (sizeOf returns KB)
    private static final long NEGATIVE_CACHE_TTL_MS = 15 * 60 * 1000L; // 15 mins
    private static final int MAX_FAVICON_BYTES = 512 * 1024; // 512 KB

    public interface FaviconCallback {
        /**
         * Invoked on the Android main UI thread when a favicon has been resolved or failed.
         *
         * @param host normalized hostname
         * @param bitmap decoded Bitmap, or {@code null} if unavailable
         */
        void onFaviconLoaded(String host, Bitmap bitmap);
    }

    private static FaviconManager sInstance;

    public static synchronized FaviconManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new FaviconManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Context mContext;
    private final File mCacheDir;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);

    // Fast in-memory Bitmap cache (indexed by lowercase host, sized in KB)
    private final LruCache<String, Bitmap> mMemCache = new LruCache<String, Bitmap>(MEM_CACHE_MAX_KB) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            if (bitmap == null) return 1;
            if (Build.VERSION.SDK_INT >= 19) {
                return Math.max(1, bitmap.getAllocationByteCount() / 1024);
            }
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };

    // Cooldown tracker for hosts that returned 404 or timed out
    private final ConcurrentHashMap<String, Long> mNegativeCache = new ConcurrentHashMap<String, Long>();

    // In-flight task queue to deduplicate simultaneous requests for the same host
    private final Map<String, List<FaviconCallback>> mInFlightCallbacks = new HashMap<String, List<FaviconCallback>>();

    private FaviconManager(Context context) {
        mContext = context;
        mCacheDir = new File(context.getCacheDir(), "favicons");
        if (!mCacheDir.exists()) {
            mCacheDir.mkdirs();
        }
    }

    public File getCacheDir() {
        return mCacheDir;
    }

    // =========================================================================
    // Hostname Normalization & File Mapping
    // =========================================================================

    /**
     * Extracts and normalizes the lowercase hostname from any full URL or bare host string.
     */
    public static String extractHost(String urlOrHost) {
        if (urlOrHost == null) return "";
        String trimmed = urlOrHost.trim();
        if (trimmed.isEmpty()) return "";
        try {
            int schemeIdx = trimmed.indexOf("://");
            if (schemeIdx >= 0) {
                trimmed = trimmed.substring(schemeIdx + 3);
            }
            int slash = trimmed.indexOf('/');
            if (slash >= 0) {
                trimmed = trimmed.substring(0, slash);
            }
            int at = trimmed.indexOf('@');
            if (at >= 0) {
                trimmed = trimmed.substring(at + 1);
            }
            int q = trimmed.indexOf('?');
            if (q >= 0) {
                trimmed = trimmed.substring(0, q);
            }
            int h = trimmed.indexOf('#');
            if (h >= 0) {
                trimmed = trimmed.substring(0, h);
            }
            trimmed = trimmed.toLowerCase(Locale.US);
            // If port is specified, strip default standard ports (:80, :443), but PRESERVE non-standard ports (e.g. :8317, :9090)
            int colon = trimmed.indexOf(':');
            if (colon >= 0) {
                String port = trimmed.substring(colon + 1);
                if ("80".equals(port) || "443".equals(port)) {
                    trimmed = trimmed.substring(0, colon);
                }
            }
            return trimmed;
        } catch (Exception ignored) {
            return "";
        }
    }

    private File getCacheFileForHost(String host) {
        if (host == null || host.isEmpty()) return null;
        String safe = host.replaceAll("[^a-z0-9._-]", "_");
        if (safe.isEmpty()) return null;
        return new File(mCacheDir, safe + ".bin");
    }

    // =========================================================================
    // Synchronous Fast Queries (Memory -> Disk, Non-Blocking)
    // =========================================================================

    /**
     * Synchronously checks memory LruCache and local disk cache.
     * Returns immediately (never performs network I/O).
     *
     * @return Decoded {@link Bitmap} or {@code null} if not yet cached locally.
     */
    public Bitmap getFaviconFast(String urlOrHost) {
        final String host = extractHost(urlOrHost);
        if (host.isEmpty()) return null;

        synchronized (mMemCache) {
            Bitmap mem = mMemCache.get(host);
            if (mem != null && !mem.isRecycled()) {
                return mem;
            }
        }

        File file = getCacheFileForHost(host);
        if (file != null && file.exists() && file.length() > 32) {
            byte[] bytes = readFileBytes(file);
            if (bytes != null && isValidFaviconBytes(bytes)) {
                Bitmap bmp = decodeFaviconBitmap(bytes);
                if (bmp != null) {
                    synchronized (mMemCache) {
                        mMemCache.put(host, bmp);
                    }
                    return bmp;
                }
            }
        }
        return null;
    }

    /**
     * Returns the cached Base64 Data URI (e.g. {@code "data:image/png;base64,..."}) from disk,
     * or {@code null} if not cached.
     */
    public String getFaviconDataUriFast(String urlOrHost) {
        final String host = extractHost(urlOrHost);
        if (host.isEmpty()) return null;

        File file = getCacheFileForHost(host);
        if (file != null && file.exists() && file.length() > 32 && file.length() < 65536) {
            byte[] bytes = readFileBytes(file);
            if (bytes != null && isValidFaviconBytes(bytes)) {
                String mime = detectFaviconMime(bytes);
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                return "data:" + mime + ";base64," + b64;
            }
        }
        return null;
    }

    /**
     * Returns raw cached bytes from disk, or {@code null} if not cached.
     */
    public byte[] getFaviconBytesFast(String urlOrHost) {
        final String host = extractHost(urlOrHost);
        if (host.isEmpty()) return null;
        File file = getCacheFileForHost(host);
        if (file != null && file.exists() && file.length() > 32) {
            byte[] bytes = readFileBytes(file);
            if (bytes != null && isValidFaviconBytes(bytes)) {
                return bytes;
            }
        }
        return null;
    }

    // =========================================================================
    // Asynchronous Fetch & Smooth Notification
    // =========================================================================

    /**
     * Asynchronously loads the favicon for a URL or hostname.
     * <ol>
     *   <li>If already in memory or disk, dispatches to {@code callback} immediately.</li>
     *   <li>If missing and not in negative cooldown, probes origin and CDN endpoints in background.</li>
     *   <li>Saves discovered icon to memory and disk, then dispatches to {@code callback}.</li>
     * </ol>
     */
    public void loadFaviconAsync(String urlOrHost, final FaviconCallback callback) {
        final String host = extractHost(urlOrHost);
        if (host.isEmpty()) {
            if (callback != null) callback.onFaviconLoaded("", null);
            return;
        }

        Bitmap fast = getFaviconFast(host);
        if (fast != null) {
            if (callback != null) {
                callback.onFaviconLoaded(host, fast);
            }
            return;
        }

        // Check 15-minute negative cache
        Long lastFail = mNegativeCache.get(host);
        if (lastFail != null && (System.currentTimeMillis() - lastFail.longValue()) < NEGATIVE_CACHE_TTL_MS) {
            if (callback != null) {
                callback.onFaviconLoaded(host, null);
            }
            return;
        }

        // Deduplicate in-flight requests for the same host
        boolean isFirstRequester = false;
        synchronized (mInFlightCallbacks) {
            List<FaviconCallback> list = mInFlightCallbacks.get(host);
            if (list == null) {
                list = new ArrayList<FaviconCallback>();
                mInFlightCallbacks.put(host, list);
                isFirstRequester = true;
            }
            if (callback != null) {
                list.add(callback);
            }
        }

        if (!isFirstRequester) {
            return;
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                byte[] downloaded = probeAndDownloadFaviconBytes(host);
                Bitmap resultBmp = null;

                if (downloaded != null && isValidFaviconBytes(downloaded)) {
                    saveToDisk(host, downloaded);
                    resultBmp = decodeFaviconBitmap(downloaded);
                    if (resultBmp != null) {
                        synchronized (mMemCache) {
                            mMemCache.put(host, resultBmp);
                        }
                        mNegativeCache.remove(host);
                    } else {
                        // Valid icon bytes (e.g. SVG for WebView data URI) that cannot be decoded into Android Bitmap
                        mNegativeCache.put(host, Long.valueOf(System.currentTimeMillis()));
                    }
                } else {
                    mNegativeCache.put(host, Long.valueOf(System.currentTimeMillis()));
                }

                final Bitmap finalBmp = resultBmp;
                final List<FaviconCallback> waiting;
                synchronized (mInFlightCallbacks) {
                    waiting = mInFlightCallbacks.remove(host);
                }

                if (waiting != null && !waiting.isEmpty()) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            for (FaviconCallback cb : waiting) {
                                try {
                                    cb.onFaviconLoaded(host, finalBmp);
                                } catch (Exception e) {
                                    Log.w(TAG, "Callback error for " + host, e);
                                }
                            }
                        }
                    });
                }
            }
        });
    }

    /**
     * Caches a favicon captured natively by WebView during browsing (e.g. {@code onReceivedIcon}).
     */
    public void cacheVisitedFavicon(String pageUrl, final Bitmap icon) {
        if (pageUrl == null || icon == null || icon.isRecycled()) return;
        final String host = extractHost(pageUrl);
        if (host.isEmpty()) return;

        synchronized (mMemCache) {
            mMemCache.put(host, icon);
        }
        mNegativeCache.remove(host);

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    icon.compress(Bitmap.CompressFormat.PNG, 95, bos);
                    byte[] bytes = bos.toByteArray();
                    if (bytes.length > 32) {
                        saveToDisk(host, bytes);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Saves raw favicon bytes to disk under {@code cache/favicons/<host>.bin}.
     */
    public boolean saveToDisk(String host, byte[] bytes) {
        File file = getCacheFileForHost(host);
        if (file == null || bytes == null || bytes.length == 0) return false;
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to save favicon bytes for " + host, e);
            return false;
        }
    }

    // =========================================================================
    // Network Probing & Decoding Internals
    // =========================================================================

    public static boolean isLocalOrIpHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        if ("localhost".equalsIgnoreCase(h) || h.startsWith("127.") || h.startsWith("192.168.")
                || h.startsWith("10.") || h.endsWith(".local") || h.endsWith(".lan")) {
            return true;
        }
        return h.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    }

    private byte[] probeAndDownloadFaviconBytes(String host) {
        List<String> candidates = new ArrayList<String>();
        boolean isLocalOrIp = isLocalOrIpHost(host);

        if (isLocalOrIp) {
            candidates.add("http://" + host + "/favicon.ico");
            candidates.add("http://" + host + "/apple-touch-icon.png");
            candidates.add("https://" + host + "/favicon.ico");
        } else {
            candidates.add("https://" + host + "/favicon.ico");
            candidates.add("https://" + host + "/apple-touch-icon.png");
            candidates.add("http://" + host + "/favicon.ico");
            if (!host.contains(":")) {
                candidates.add("https://icons.duckduckgo.com/ip3/" + host + ".ico");
            }
        }

        for (String cand : candidates) {
            byte[] b = fetchUrlBytes(cand);
            if (b != null && isValidFaviconBytes(b)) {
                return b;
            }
        }
        return null;
    }

    public static byte[] fetchUrlBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            String cur = urlStr;
            for (int r = 0; r < 3; r++) {
                conn = (HttpURLConnection) new URL(cur).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("User-Agent", BrowserController.UA_MOBILE);
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) return null;
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code != 200) {
                    conn.disconnect();
                    return null;
                }
                InputStream is = new BufferedInputStream(conn.getInputStream());
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_FAVICON_BYTES) break;
                    bos.write(buf, 0, n);
                }
                is.close();
                conn.disconnect();
                return bos.toByteArray();
            }
        } catch (Exception ignored) {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored2) {}
        }
        return null;
    }

    public static Bitmap decodeFaviconBitmap(byte[] b) {
        if (b == null || b.length < 32) return null;
        try {
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, boundsOpts);
            if (boundsOpts.outWidth <= 1 || boundsOpts.outHeight <= 1
                || boundsOpts.outWidth > 4096 || boundsOpts.outHeight > 4096) {
                return null; // Guard against invalid icons and PNG decompression bombs
            }
            int sampleSize = 1;
            int maxEdge = Math.max(boundsOpts.outWidth, boundsOpts.outHeight);
            while (maxEdge / sampleSize > 192) {
                sampleSize *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length, opts);
            if (bmp != null && bmp.getWidth() > 1 && bmp.getHeight() > 1) {
                return bmp;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isValidFaviconBytes(byte[] b) {
        if (b == null || b.length < 32) return false;
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        // ICO: 00 00 01 00 or 00 00 02 00
        if (b[0] == 0 && b[1] == 0 && (b[2] == 1 || b[2] == 2) && b[3] == 0) return true;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // GIF: GIF8
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;
        // WebP: RIFF....WEBP
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        // BMP: BM
        if (b[0] == 'B' && b[1] == 'M') return true;
        // SVG
        String head = new String(b, 0, Math.min(b.length, 160), StandardCharsets.UTF_8).toLowerCase(Locale.US);
        return head.contains("<svg") && !head.contains("<html");
    }

    public static String detectFaviconMime(byte[] b) {
        if (b != null && b.length >= 4) {
            if ((b[0] & 0xFF) == 0x89 && b[1] == 'P') return "image/png";
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (b[0] == 'G' && b[1] == 'I') return "image/gif";
            if (b[0] == 'R' && b[1] == 'I') return "image/webp";
            String head = new String(b, 0, Math.min(b.length, 120), StandardCharsets.UTF_8).toLowerCase(Locale.US);
            if (head.contains("<svg")) return "image/svg+xml";
        }
        return "image/x-icon";
    }

    public WebResourceResponse interceptFaviconWebRequest(Uri reqUri) {
        try {
            String rawHost = reqUri.getQueryParameter("host");
            String targetUrl = reqUri.getQueryParameter("url");
            String host = (rawHost != null && !rawHost.isEmpty()) ? extractHost(rawHost) : null;
            if ((host == null || host.isEmpty()) && targetUrl != null) {
                host = extractHost(targetUrl);
            }
            if (host == null || host.trim().isEmpty()) {
                return buildEmpty404Response();
            }
            host = host.trim().toLowerCase(Locale.US);

            byte[] cached = getFaviconBytesFast(host);
            if (cached != null) {
                return buildFaviconResponse(cached);
            }

            Long lastFail = mNegativeCache.get(host);
            if (lastFail != null && (System.currentTimeMillis() - lastFail.longValue()) < NEGATIVE_CACHE_TTL_MS) {
                return buildEmpty404Response();
            }

            byte[] downloaded = probeAndDownloadFaviconBytes(host);
            if (downloaded != null && isValidFaviconBytes(downloaded)) {
                saveToDisk(host, downloaded);
                Bitmap bmp = decodeFaviconBitmap(downloaded);
                if (bmp != null) {
                    synchronized (mMemCache) {
                        mMemCache.put(host, bmp);
                    }
                }
                mNegativeCache.remove(host);
                return buildFaviconResponse(downloaded);
            }

            mNegativeCache.put(host, Long.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) {}
        return buildEmpty404Response();
    }

    public static WebResourceResponse buildFaviconResponse(byte[] bytes) {
        String mime = detectFaviconMime(bytes);
        WebResourceResponse resp = new WebResourceResponse(mime, null, new ByteArrayInputStream(bytes));
        if (Build.VERSION.SDK_INT >= 21) {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cache-Control", "public, max-age=31536000, immutable");
            headers.put("Content-Security-Policy", "default-src 'none'; sandbox");
            headers.put("X-Content-Type-Options", "nosniff");
            resp.setResponseHeaders(headers);
        }
        return resp;
    }

    public static WebResourceResponse buildEmpty404Response() {
        WebResourceResponse resp = new WebResourceResponse(
            "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0])
        );
        if (Build.VERSION.SDK_INT >= 21) {
            resp.setStatusCodeAndReasonPhrase(404, "Not Found");
        }
        return resp;
    }

    private static byte[] readFileBytes(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            fis.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
