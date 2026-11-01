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

        String guessed = URLUtil.guessFileName(url, contentDisposition, effectiveMime);

        // Extra guard: If URL path or contentDisposition has an explicit known extension,
        // but URLUtil forced a .bin extension, restore the original extension.
        if (guessed != null && guessed.toLowerCase().endsWith(".bin")) {
            String candidate = null;
            if (contentDisposition != null) {
                int fnIndex = contentDisposition.toLowerCase().indexOf("filename=");
                if (fnIndex >= 0) {
                    String part = contentDisposition.substring(fnIndex + 9).trim();
                    if (part.startsWith("\"") && part.length() > 1) {
                        int endQuote = part.indexOf('\"', 1);
                        if (endQuote > 1) candidate = part.substring(1, endQuote);
                    } else {
                        int semi = part.indexOf(';');
                        candidate = semi > 0 ? part.substring(0, semi).trim() : part;
                    }
                }
            }
            if (candidate == null && url != null) {
                try {
                    String path = Uri.parse(url).getPath();
                    if (path != null) {
                        int slash = path.lastIndexOf('/');
                        if (slash >= 0 && slash < path.length() - 1) {
                            candidate = path.substring(slash + 1);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to derive a file name from the URL", e);
                }
            }

            if (candidate != null) {
                int dot = candidate.lastIndexOf('.');
                if (dot >= 0 && dot < candidate.length() - 1) {
                    String ext = candidate.substring(dot).toLowerCase();
                    if (!ext.equals(".bin")) {
                        if (guessed.toLowerCase().endsWith(ext + ".bin")) {
                            guessed = guessed.substring(0, guessed.length() - 4);
                        } else if (guessed.toLowerCase().endsWith(".bin")) {
                            guessed = guessed.substring(0, guessed.length() - 4) + ext;
                        }
                    }
                }
            }
        }

        return guessed;
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

            Map<String, String> headers = new HashMap<String, String>();
            String cookie = null;
            try {
                cookie = CookieManager.getInstance().getCookie(url);
            } catch (Exception ignored) {}
            if (cookie != null && !cookie.isEmpty()) {
                headers.put("Cookie", cookie);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                headers.put("User-Agent", userAgent);
            }

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
                if (it.downloadId <= 0) {
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
