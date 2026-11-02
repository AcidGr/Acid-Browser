package com.acid.browser.download;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import com.acid.browser.AcidFeedback;
import com.acid.browser.AcidFileProvider;
import com.acid.browser.AcidIo;
import com.acid.browser.AcidSettings;
import com.acid.browser.BrowserDataStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates browser downloads across the built-in multi-threaded {@link AcidDownloader}
 * and Android's system {@link DownloadManager}, handling status synchronization, persistence,
 * MIME/filename deduction, and file/folder Intent launching.
 */
public class DownloadCoordinator {

    private static final String TAG = "AcidDownloadCoord";

    /** Result callback for {@link #syncDownloadStatusAsync(DownloadSyncCallback)}. */
    public interface DownloadSyncCallback {
        void onDownloadSyncDone(boolean anyActive);
    }

    private final Context mContext;
    private final AcidDownloader mDownloader;
    private final AcidDownloader.DownloaderListener mDownloaderListener;
    private final List<DownloadItem> mDownloads = new ArrayList<DownloadItem>();
    private final Handler mMainHandler;
    private BrowserDataStore mDataStore;
    /** Guards against stacking overlapping DownloadManager passes. */
    private volatile boolean mSyncInFlight = false;

    public DownloadCoordinator(Context context, BrowserDataStore dataStore) {
        Context appCtx = (context != null) ? context.getApplicationContext() : null;
        mContext = (appCtx != null) ? appCtx : context;
        mDataStore = dataStore;
        Handler h = null;
        try {
            if (Looper.getMainLooper() != null) {
                h = new Handler(Looper.getMainLooper());
            }
        } catch (Throwable ignored) {}
        mMainHandler = h;

        mDownloader = AcidDownloader.getInstance();
        mDownloaderListener = new AcidDownloader.DownloaderListener() {
            @Override
            public void onTaskStarted(DownloadTask task) {
                updateDownloadItemFromTask(task);
            }

            @Override
            public void onTaskProgress(DownloadTask task) {
                updateDownloadItemFromTask(task);
            }

            @Override
            public void onTaskPaused(DownloadTask task) {
                updateDownloadItemFromTask(task);
            }

            @Override
            public void onTaskCompleted(DownloadTask task) {
                updateDownloadItemFromTask(task);
                onDownloadCompleted(task.id);
            }

            @Override
            public void onTaskFailed(DownloadTask task, String error) {
                updateDownloadItemFromTask(task);
            }
        };
        mDownloader.addListener(mDownloaderListener);

        reloadSavedDownloads();
    }

    public void destroy() {
        mDownloader.removeListener(mDownloaderListener);
    }

    public void setDataStore(BrowserDataStore dataStore) {
        mDataStore = dataStore;
        reloadSavedDownloads();
    }

    public BrowserDataStore getDataStore() {
        return mDataStore;
    }

    public int getDownloaderEngine() {
        return (mDataStore != null) ? mDataStore.getDownloaderEngine() : AcidSettings.DOWNLOADER_BUILTIN;
    }

    private void reloadSavedDownloads() {
        if (mDataStore != null) {
            List<DownloadItem> saved = mDataStore.loadDownloads();
            synchronized (mDownloads) {
                mDownloads.clear();
                mDownloads.addAll(saved);
            }
        }
    }

    public static String smartGuessFileName(String url, String contentDisposition, String mimeType) {
        String effectiveMime = mimeType;
        if (effectiveMime != null) {
            String lower = effectiveMime.trim().toLowerCase();
            if (lower.equals("application/octet-stream") ||
                lower.equals("binary/octet-stream") ||
                lower.equals("application/force-download") ||
                lower.equals("*/*") ||
                lower.isEmpty()) {
                effectiveMime = null;
            }
        }

        // 1. Priority: RFC 5987 / 6266 Content-Disposition header (handles filename*=UTF-8'' and percent-encoded UTF-8)
        String fromDisposition = HttpProbe.parseFileNameFromDisposition(contentDisposition);
        if (fromDisposition != null && !fromDisposition.isEmpty()) {
            return ensureExtensionIfMissing(fromDisposition, url, effectiveMime);
        }

        // 2. Check common filename query parameters in URL (e.g. UC/Quark &filename=, Baidu PCS &fin=, OSS &response-content-disposition=)
        String fromUrlQuery = extractFileNameFromUrlQuery(url);
        if (fromUrlQuery != null && !fromUrlQuery.isEmpty()) {
            return ensureExtensionIfMissing(fromUrlQuery, url, effectiveMime);
        }

        // 3. Fallback to Android URLUtil + percent-decoding & sanitization
        String guessed = null;
        try {
            guessed = URLUtil.guessFileName(url, contentDisposition, effectiveMime);
        } catch (Throwable ignored) {}

        guessed = HttpProbe.decodeAndSanitizeFileName(guessed);
        if (guessed == null || guessed.isEmpty()) {
            guessed = extractFileNameFromUrlPath(url);
        }
        if (guessed == null || guessed.isEmpty()) {
            guessed = "download";
        }

        // Extra guard: If URL path or contentDisposition has an explicit known extension,
        // but URLUtil forced a .bin extension, restore the original extension.
        if (guessed.toLowerCase().endsWith(".bin")) {
            String candidate = extractFileNameFromUrlPath(url);
            if (candidate != null) {
                int dot = candidate.lastIndexOf('.');
                if (dot >= 0 && dot < candidate.length() - 1) {
                    String ext = candidate.substring(dot).toLowerCase();
                    if (!ext.equals(".bin")) {
                        if (guessed.toLowerCase().endsWith(ext + ".bin")) {
                            guessed = guessed.substring(0, guessed.length() - 4);
                        } else {
                            guessed = guessed.substring(0, guessed.length() - 4) + ext;
                        }
                    }
                }
            }
        }

        return ensureExtensionIfMissing(guessed, url, effectiveMime);
    }

    private static String extractFileNameFromUrlQuery(String url) {
        if (url == null || url.isEmpty()) return null;
        int qIdx = url.indexOf('?');
        if (qIdx < 0 || qIdx >= url.length() - 1) return null;
        String query = url.substring(qIdx + 1);
        int hashIdx = query.indexOf('#');
        if (hashIdx >= 0) query = query.substring(0, hashIdx);

        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String p = pairs[i];
            int eq = p.indexOf('=');
            if (eq <= 0 || eq >= p.length() - 1) continue;
            String key = p.substring(0, eq).trim().toLowerCase();
            String val = p.substring(eq + 1).trim();
            if ("filename".equals(key) || "fin".equals(key) || "file_name".equals(key) || "attname".equals(key)) {
                String decoded = HttpProbe.decodeAndSanitizeFileName(val);
                if (decoded != null && !decoded.isEmpty()) return decoded;
            } else if ("response-content-disposition".equals(key)) {
                String decodedDisp = HttpProbe.decodeAndSanitizeFileName(val);
                String parsed = HttpProbe.parseFileNameFromDisposition(decodedDisp != null ? decodedDisp : val);
                if (parsed != null && !parsed.isEmpty()) return parsed;
            }
        }
        return null;
    }

    public static long extractContentLengthFromUrlQuery(String url) {
        if (url == null || url.isEmpty()) return -1;
        int qIdx = url.indexOf('?');
        if (qIdx < 0 || qIdx >= url.length() - 1) return -1;
        String query = url.substring(qIdx + 1);
        int hashIdx = query.indexOf('#');
        if (hashIdx >= 0) query = query.substring(0, hashIdx);

        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String p = pairs[i];
            int eq = p.indexOf('=');
            if (eq <= 0 || eq >= p.length() - 1) continue;
            String key = p.substring(0, eq).trim().toLowerCase();
            String val = p.substring(eq + 1).trim();
            if ("size".equals(key) || "sta_dx".equals(key) || "filesize".equals(key)) {
                try {
                    long parsed = Long.parseLong(val);
                    if (parsed > 0) return parsed;
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private static String extractFileNameFromUrlPath(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            String clean = url;
            int q = clean.indexOf('?');
            if (q >= 0) clean = clean.substring(0, q);
            int h = clean.indexOf('#');
            if (h >= 0) clean = clean.substring(0, h);
            int slash = clean.lastIndexOf('/');
            if (slash >= 0 && slash < clean.length() - 1) {
                return HttpProbe.decodeAndSanitizeFileName(clean.substring(slash + 1));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String ensureExtensionIfMissing(String fileName, String url, String effectiveMime) {
        if (fileName == null || fileName.isEmpty()) return "download";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            return fileName;
        }
        // Try extension from URL path
        String pathName = extractFileNameFromUrlPath(url);
        if (pathName != null) {
            int pDot = pathName.lastIndexOf('.');
            if (pDot > 0 && pDot < pathName.length() - 1) {
                String ext = pathName.substring(pDot);
                if (ext.length() <= 8) {
                    return fileName + ext;
                }
            }
        }
        // Try extension from MIME type
        if (effectiveMime != null && !effectiveMime.isEmpty()) {
            try {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(effectiveMime);
                if (ext != null && !ext.isEmpty()) {
                    return fileName + "." + ext;
                }
            } catch (Throwable ignored) {}
        }
        return fileName;
    }

    public static String resolveMimeType(String fileName, String mimeType) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase();
                if ("apk".equals(ext)) {
                    return "application/vnd.android.package-archive";
                }
                String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (guessed != null && !guessed.isEmpty()) {
                    return guessed;
                }
            }
        }
        if (mimeType != null && !mimeType.isEmpty() && !"*/*".equals(mimeType) && !"application/octet-stream".equals(mimeType)) {
            return mimeType;
        }
        return "*/*";
    }

    public List<DownloadItem> getDownloads() {
        synchronized (mDownloads) {
            return new ArrayList<DownloadItem>(mDownloads);
        }
    }

    public AcidDownloader getDownloader() {
        return mDownloader;
    }

    private void updateDownloadItemFromTask(DownloadTask task) {
        if (task == null) return;
        boolean changed = false;
        synchronized (mDownloads) {
            for (DownloadItem it : mDownloads) {
                if (it.downloadId == task.id) {
                    it.downloadedBytes = task.downloadedBytes;
                    if (task.contentLength > 0) it.contentLength = task.contentLength;
                    it.status = task.status;
                    it.speedBytesPerSec = task.currentSpeedBytesPerSec;
                    it.formattedSpeed = task.getFormattedSpeed();
                    it.remainingTime = task.getEstimatedRemainingTime();
                    changed = true;
                    break;
                }
            }
            if (changed && mDataStore != null) {
                mDataStore.saveDownloads(mDownloads);
            }
        }
    }

    public static final String UA_BAIDU_NETDISK =
        "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0";
    public static final String UA_QUARK_CLIENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch";
    public static final String UA_UC_CLIENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    public static final String UA_115_CLIENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 115Browser/27.0.5.7";

    public static Map<String, String> buildCloudDriveAwareHeaders(String url, String userAgent) {
        Map<String, String> headers = new HashMap<String, String>();
        String lowerUrl = url != null ? url.toLowerCase() : "";

        String cookie = null;
        try {
            cookie = CookieManager.getInstance().getCookie(url);
        } catch (Throwable ignored) {}

        // Enrich cross-subdomain cookies & Referer/UA for cloud drive CDNs
        if (lowerUrl.contains(".quark.cn")) {
            String mainCookie = safeGetCookie("https://pan.quark.cn");
            cookie = mergeCookies(cookie, mainCookie);
            headers.put("Referer", "https://pan.quark.cn/");
            if (userAgent == null || !userAgent.contains("quark-cloud-drive")) {
                userAgent = UA_QUARK_CLIENT;
            }
        } else if (lowerUrl.contains(".uc.cn")) {
            String mainCookie = safeGetCookie("https://drive.uc.cn");
            cookie = mergeCookies(cookie, mainCookie);
            headers.put("Referer", "https://drive.uc.cn/");
            if (userAgent == null || !userAgent.contains("uc-cloud-drive")) {
                userAgent = UA_UC_CLIENT;
            }
        } else if (lowerUrl.contains("baidupcs.com") || lowerUrl.contains("pcs.baidu.com") || lowerUrl.contains("d2-ant.baidu.com")) {
            String mainCookie = safeGetCookie("https://pan.baidu.com");
            cookie = mergeCookies(cookie, mainCookie);
            headers.put("Referer", "https://pan.baidu.com/");
            if (userAgent == null || !userAgent.contains("netdisk;")) {
                userAgent = UA_BAIDU_NETDISK;
            }
        } else if (lowerUrl.contains("115.com") || lowerUrl.contains("115cdn.com")) {
            String mainCookie = safeGetCookie("https://115.com");
            cookie = mergeCookies(cookie, mainCookie);
            headers.put("Referer", "https://115.com/");
            if (userAgent == null || !userAgent.contains("115Browser")) {
                userAgent = UA_115_CLIENT;
            }
        }

        if (cookie != null && !cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        if (userAgent != null && !userAgent.isEmpty()) {
            headers.put("User-Agent", userAgent);
        }
        return headers;
    }

    /**
     * Returns true when a navigation URL is a known cloud-drive direct CDN file stream that
     * would return HTTP 403 if loaded directly by WebView with a standard browser User-Agent.
     */
    public static boolean isCloudDriveDirectStreamUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        if (lower.contains(".baidupcs.com/file/") || lower.contains("d.pcs.baidu.com/file/")) {
            return true;
        }
        if (lower.contains("dl-pc-zb.drive.uc.cn/") || (lower.contains(".quark.cn/") && lower.contains("auth_key="))) {
            return true;
        }
        return false;
    }

    private static String safeGetCookie(String domainUrl) {
        try {
            return CookieManager.getInstance().getCookie(domainUrl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String mergeCookies(String primary, String secondary) {
        if (primary == null || primary.isEmpty()) return secondary;
        if (secondary == null || secondary.isEmpty()) return primary;
        if (primary.equals(secondary) || primary.contains(secondary)) return primary;
        return primary + "; " + secondary;
    }

    /**
     * Saves a download item in {@link DownloadItem#STATUS_SAVED} ("待下载") state when the user
     * collapses the confirmation sheet, allowing on-demand download later from DownloadsSheet.
     */
    public DownloadItem saveDownloadForLater(
        String url,
        String fileName,
        String userAgent,
        String contentDisposition,
        String mimeType,
        long contentLength
    ) {
        if (url == null || url.isEmpty()) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;

        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = smartGuessFileName(url, contentDisposition, mimeType);
        }
        String effectiveMime = resolveMimeType(fileName, mimeType);
        long now = System.currentTimeMillis();

        DownloadItem savedItem = null;
        synchronized (mDownloads) {
            // If an unstarted/failed entry with the same URL already exists, update and promote it
            for (int i = 0; i < mDownloads.size(); i++) {
                DownloadItem existing = mDownloads.get(i);
                if (url.equals(existing.url) &&
                    (existing.status == DownloadItem.STATUS_SAVED || existing.status == DownloadTask.STATUS_FAILED)) {
                    mDownloads.remove(i);
                    savedItem = new DownloadItem(
                        existing.downloadId > 0 ? existing.downloadId : now,
                        fileName,
                        url,
                        effectiveMime,
                        contentLength > 0 ? contentLength : existing.contentLength,
                        0,
                        DownloadItem.STATUS_SAVED,
                        now
                    );
                    break;
                }
            }
            if (savedItem == null) {
                savedItem = new DownloadItem(
                    now,
                    fileName,
                    url,
                    effectiveMime,
                    contentLength,
                    0,
                    DownloadItem.STATUS_SAVED,
                    now
                );
            }
            mDownloads.add(0, savedItem);
            if (mDataStore != null) {
                mDataStore.saveDownloads(mDownloads);
            }
        }

        Log.i(TAG, "[DOWNLOAD] Saved for later: " + fileName + " (id=" + savedItem.downloadId + ")");
        AcidFeedback.info(mContext, "已暂存至下载管理: " + fileName + "\n可在「下载管理」中随时点击下载");
        return savedItem;
    }

    private void removeSavedOrFailedDuplicateLocked(String url) {
        if (url == null || url.isEmpty()) return;
        for (int i = mDownloads.size() - 1; i >= 0; i--) {
            DownloadItem existing = mDownloads.get(i);
            if (url.equals(existing.url) &&
                (existing.status == DownloadItem.STATUS_SAVED || existing.status == DownloadTask.STATUS_FAILED)) {
                mDownloads.remove(i);
            }
        }
    }

    public void startDownload(
        String url,
        String fileName,
        String userAgent,
        String contentDisposition,
        String mimeType,
        long contentLength
    ) {
        if (url == null || url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            AcidFeedback.warn(mContext, "当前暂不支持下载此协议资源: " + url);
            return;
        }

        if (getDownloaderEngine() == AcidSettings.DOWNLOADER_SYSTEM) {
            startDownloadWithSystem(url, fileName, userAgent, contentDisposition, mimeType, contentLength);
        } else {
            startDownloadWithBuiltin(url, fileName, userAgent, contentDisposition, mimeType, contentLength);
        }
    }

    public void startDownloadWithBuiltin(
        String url,
        String fileName,
        String userAgent,
        String contentDisposition,
        String mimeType,
        long contentLength
    ) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = smartGuessFileName(url, contentDisposition, mimeType);
            }
            String effectiveMime = resolveMimeType(fileName, mimeType);

            File destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (destDir == null || (!destDir.exists() && !destDir.mkdirs())) {
                File extFiles = mContext.getExternalFilesDir(null);
                destDir = new File(extFiles != null ? extFiles : mContext.getFilesDir(), "Download");
                destDir.mkdirs();
            }

            Map<String, String> headers = buildCloudDriveAwareHeaders(url, userAgent);

            final DownloadTask task = mDownloader.enqueue(url, fileName, effectiveMime, destDir, headers);

            DownloadItem item = new DownloadItem(
                task.id,
                task.fileName,
                url,
                effectiveMime,
                contentLength,
                0,
                DownloadTask.STATUS_RUNNING,
                task.createdAt
            );

            synchronized (mDownloads) {
                removeSavedOrFailedDuplicateLocked(url);
                mDownloads.add(0, item);
                if (mDataStore != null) {
                    mDataStore.saveDownloads(mDownloads);
                }
            }

            Log.i(TAG, "[DOWNLOAD] Enqueued multi-thread task: " + fileName + " (id=" + task.id + ") -> /Download/");
            AcidFeedback.ok(mContext, "已开始内置下载: " + fileName + "\n保存至手机 Download 目录");
        } catch (Exception e) {
            Log.e(TAG, "[DOWNLOAD] Failed to enqueue builtin: " + e.getMessage(), e);
            AcidFeedback.error(mContext, "内置下载发起失败: " + e.getMessage());
        }
    }

    public void startDownloadWithSystem(
        String url,
        String fileName,
        String userAgent,
        String contentDisposition,
        String mimeType,
        long contentLength
    ) {
        try {
            DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                AcidFeedback.warn(mContext, "系统下载器不可用，自动转为内置下载");
                startDownloadWithBuiltin(url, fileName, userAgent, contentDisposition, mimeType, contentLength);
                return;
            }

            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = smartGuessFileName(url, contentDisposition, mimeType);
            }
            String effectiveMime = resolveMimeType(fileName, mimeType);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (effectiveMime != null && !effectiveMime.isEmpty() && !"*/*".equals(effectiveMime)) {
                request.setMimeType(effectiveMime);
            }

            String cookie = null;
            try {
                cookie = CookieManager.getInstance().getCookie(url);
            } catch (Exception ignored) {}
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }

            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle(fileName);
            request.setDescription("Acid Browser 系统下载");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            long downloadId = dm.enqueue(request);

            DownloadItem item = new DownloadItem(
                downloadId,
                fileName,
                url,
                effectiveMime,
                contentLength,
                0,
                DownloadManager.STATUS_RUNNING,
                System.currentTimeMillis()
            );

            synchronized (mDownloads) {
                removeSavedOrFailedDuplicateLocked(url);
                mDownloads.add(0, item);
                if (mDataStore != null) {
                    mDataStore.saveDownloads(mDownloads);
                }
            }

            Log.i(TAG, "[DOWNLOAD] Enqueued system task: " + fileName + " (id=" + downloadId + ") -> /Download/");
            AcidFeedback.ok(mContext, "已通过系统下载器开始下载: " + fileName);
        } catch (Exception e) {
            Log.w(TAG, "[DOWNLOAD] System download enqueue failed: " + e.getMessage() + ", falling back to builtin");
            startDownloadWithBuiltin(url, fileName, userAgent, contentDisposition, mimeType, contentLength);
        }
    }

    public boolean pauseDownload(long downloadId) {
        return mDownloader.pause(downloadId);
    }

    public boolean resumeDownload(long downloadId) {
        DownloadTask task = mDownloader.getTask(downloadId);
        if (task != null) {
            if (task.status == DownloadTask.STATUS_FAILED) {
                // Failed download: clean up stale task and restart fresh from scratch
                String retryUrl = task.url;
                String retryFileName = task.fileName;
                String retryMime = task.mimeType;
                long retryContentLength = task.contentLength;
                mDownloader.cancel(downloadId, true);
                startDownload(retryUrl, retryFileName, null, null, retryMime, retryContentLength);
                return true;
            }
            return mDownloader.resume(downloadId);
        }
        synchronized (mDownloads) {
            for (DownloadItem it : mDownloads) {
                if (it.downloadId == downloadId) {
                    startDownload(it.url, it.fileName, null, null, it.mimeType, it.contentLength);
                    return true;
                }
            }
        }
        return false;
    }

    public void onDownloadCompleted(long downloadId) {
        syncDownloadStatusAsync(null);
        synchronized (mDownloads) {
            for (DownloadItem it : mDownloads) {
                if (it.downloadId == downloadId) {
                    Log.i(TAG, "[DOWNLOAD] Complete: " + it.fileName);
                    break;
                }
            }
        }
    }

    public void syncDownloadStatusAsync(final DownloadSyncCallback callback) {
        if (mSyncInFlight) return;
        mSyncInFlight = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean anyActive = false;
                try {
                    anyActive = syncDownloadStatusOffMainThread();
                } catch (Exception e) {
                    Log.w(TAG, "Background download status sync failed", e);
                } finally {
                    mSyncInFlight = false;
                }

                final boolean result = anyActive;
                if (mMainHandler != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onDownloadSyncDone(result);
                            }
                        }
                    });
                } else if (callback != null) {
                    callback.onDownloadSyncDone(result);
                }
            }
        }, "AcidDownloadSync").start();
    }

    private boolean syncDownloadStatusOffMainThread() {
        DownloadManager dm = null;
        try {
            dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        } catch (Exception ignored) {}

        boolean changed = false;
        boolean anyActive = false;

        synchronized (mDownloads) {
            for (DownloadItem it : mDownloads) {
                if (it.downloadId <= 0 || it.status == DownloadItem.STATUS_SAVED) {
                    continue;
                }

                DownloadTask task = mDownloader.getTask(it.downloadId);
                if (task != null) {
                    if (it.downloadedBytes != task.downloadedBytes) {
                        it.downloadedBytes = task.downloadedBytes;
                        changed = true;
                    }
                    if (task.contentLength > 0 && it.contentLength != task.contentLength) {
                        it.contentLength = task.contentLength;
                        changed = true;
                    }
                    if (it.status != task.status) {
                        it.status = task.status;
                        changed = true;
                    }
                    it.speedBytesPerSec = task.currentSpeedBytesPerSec;
                    it.formattedSpeed = task.getFormattedSpeed();
                    it.remainingTime = task.getEstimatedRemainingTime();
                    if (it.status == DownloadTask.STATUS_RUNNING || it.status == DownloadTask.STATUS_PENDING) {
                        anyActive = true;
                    }
                    continue;
                }

                if (dm == null) continue;
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(it.downloadId);
                Cursor c = null;
                try {
                    c = dm.query(q);
                    if (c != null && c.moveToFirst()) {
                        int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int bytesCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);

                        if (statusCol != -1) {
                            int status = c.getInt(statusCol);
                            if (it.status != status) {
                                it.status = status;
                                changed = true;
                            }
                        }
                        if (bytesCol != -1) {
                            long bytes = c.getLong(bytesCol);
                            if (it.downloadedBytes != bytes) {
                                it.downloadedBytes = bytes;
                                changed = true;
                            }
                        }
                        if (totalCol != -1) {
                            long total = c.getLong(totalCol);
                            if (total > 0 && (it.contentLength <= 0 || it.contentLength != total)) {
                                it.contentLength = total;
                                changed = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read download status for id " + it.downloadId, e);
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to close download-status cursor", e);
                        }
                    }
                }

                if (it.status == DownloadManager.STATUS_RUNNING
                    || it.status == DownloadManager.STATUS_PENDING) {
                    anyActive = true;
                }
            }

            if (changed && mDataStore != null) {
                mDataStore.saveDownloads(mDownloads);
            }
        }

        return anyActive;
    }

    public void openDownloadedFile(DownloadItem it) {
        openDownloadedFile(it, false);
    }

    public void openDownloadedFile(DownloadItem it, boolean forceChooser) {
        if (it == null) return;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(dir, it.fileName);
            if (!file.exists()) {
                File fallbackDir = new File(mContext.getExternalFilesDir(null), "Download");
                File fallbackFile = new File(fallbackDir, it.fileName);
                if (fallbackFile.exists()) {
                    file = fallbackFile;
                } else if (mContext.getFilesDir() != null) {
                    File internalDir = new File(mContext.getFilesDir(), "Download");
                    File internalFile = new File(internalDir, it.fileName);
                    if (internalFile.exists()) {
                        file = internalFile;
                    }
                }
            }

            Uri uri = null;
            if (file.exists()) {
                uri = AcidFileProvider.getUriForFile(file);
            }
            if (uri == null) {
                DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null && it.downloadId > 0) {
                    uri = dm.getUriForDownloadedFile(it.downloadId);
                }
            }

            if (uri == null) {
                AcidFeedback.warn(mContext, "未找到下载文件: " + it.fileName);
                return;
            }

            String mime = it.mimeType;
            String lower = it.fileName != null ? it.fileName.toLowerCase() : "";
            if (lower.endsWith(".apk")) {
                mime = "application/vnd.android.package-archive";
            } else if (mime == null || mime.isEmpty() || "*/*".equals(mime) || "application/octet-stream".equals(mime)) {
                int dot = lower.lastIndexOf('.');
                if (dot >= 0 && dot < lower.length() - 1) {
                    String ext = lower.substring(dot + 1);
                    String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    if (guessed != null) {
                        mime = guessed;
                    }
                }
            }
            if (mime == null || mime.isEmpty()) {
                mime = "*/*";
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            if (forceChooser) {
                Intent chooser = Intent.createChooser(intent, "选择打开方式: " + it.fileName);
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(chooser);
            } else {
                mContext.startActivity(intent);
            }
        } catch (ActivityNotFoundException anfe) {
            AcidFeedback.warn(mContext, "系统未找到可打开此类型文件的应用 (" + it.fileName + ")");
        } catch (Exception e) {
            AcidFeedback.error(mContext, "无法打开文件: " + e.getMessage());
        }
    }

    public void openDownloadFolder() {
        try {
            Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } catch (Exception e1) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload");
                intent.setDataAndType(uri, "vnd.android.document/directory");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mContext.startActivity(intent);
            } catch (Exception e2) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                AcidFeedback.info(mContext, "存储目录: " + dir.getAbsolutePath());
            }
        }
    }

    public void copyUrlToClipboard(String url) {
        AcidIo.copyToClipboard(mContext, "download_url", url, "已复制到剪贴板");
    }

    public boolean deleteDownload(long downloadId, boolean deletePhysicalFile) {
        boolean removed = false;
        DownloadItem target = null;
        mDownloader.cancel(downloadId, deletePhysicalFile);

        synchronized (mDownloads) {
            for (int i = 0; i < mDownloads.size(); i++) {
                DownloadItem it = mDownloads.get(i);
                if (it.downloadId == downloadId) {
                    target = it;
                    mDownloads.remove(i);
                    removed = true;
                    break;
                }
            }
        }

        if (deletePhysicalFile && target != null) {
            try {
                File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), target.fileName);
                if (file.exists()) {
                    file.delete();
                } else {
                    File fallback = new File(new File(mContext.getExternalFilesDir(null), "Download"), target.fileName);
                    if (fallback.exists()) fallback.delete();
                }
                DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null && downloadId > 0) {
                    dm.remove(downloadId);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete downloaded file for id " + downloadId, e);
            }
        }

        if (removed) {
            synchronized (mDownloads) {
                if (mDataStore != null) {
                    mDataStore.saveDownloads(mDownloads);
                }
            }
        }
        return removed;
    }

    public void clearDownloads() {
        synchronized (mDownloads) {
            mDownloads.clear();
        }
        if (mDataStore != null) {
            mDataStore.clearDownloads();
        }
    }
}
