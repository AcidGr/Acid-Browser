package com.acid.browser.download;

import android.app.DownloadManager;

import java.util.Locale;

/**
 * Persisted download record and file category classifier shared between
 * {@link DownloadCoordinator}, {@code BrowserDataStore}, and download UI sheets.
 */
public class DownloadItem {

    public long downloadId;
    public String fileName;
    public final String url;
    public String mimeType;
    public long contentLength;
    public long downloadedBytes;
    public int status;
    public final long timestamp;

    public volatile long speedBytesPerSec = 0;
    public volatile String formattedSpeed = "";
    public volatile String remainingTime = "";

    /** Saved in download manager for on-demand download (collapsed from confirm sheet). */
    public static final int STATUS_SAVED = 0;

    public static final int CAT_ALL = 0;
    public static final int CAT_APK = 1;
    public static final int CAT_ARCHIVE = 2;
    public static final int CAT_DOC = 3;
    public static final int CAT_IMAGE = 4;
    /**
     * Legacy bucket that used to hold both audio and video. Kept so old serialized
     * constants resolve cleanly, though {@link #getCategory()} now splits video/audio.
     */
    @Deprecated
    public static final int CAT_MEDIA = 5;
    public static final int CAT_VIDEO = 6;
    public static final int CAT_AUDIO = 7;
    public static final int CAT_OTHER = 8;

    private static final String[] VIDEO_EXT = {
        ".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".m4v", ".3gp", ".wmv", ".mpg", ".mpeg", ".ts"
    };
    private static final String[] AUDIO_EXT = {
        ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".amr", ".mid", ".midi"
    };
    private static final String[] ARCHIVE_EXT = {
        ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz", ".zst", ".tgz", ".tbz", ".lz", ".lzma"
    };
    private static final String[] DOC_EXT = {
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv",
        ".json", ".xml", ".md", ".log", ".rtf", ".epub", ".html", ".htm"
    };
    private static final String[] IMAGE_EXT = {
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".svg", ".heic", ".heif", ".avif", ".ico"
    };

    public DownloadItem(
        long downloadId,
        String fileName,
        String url,
        String mimeType,
        long contentLength,
        long downloadedBytes,
        int status,
        long timestamp
    ) {
        this.downloadId = downloadId;
        this.fileName = (fileName != null && !fileName.isEmpty()) ? fileName : "file";
        this.url = url != null ? url : "";
        this.mimeType = mimeType != null ? mimeType : "";
        this.contentLength = contentLength;
        this.downloadedBytes = downloadedBytes;
        this.status = status;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public DownloadItem(String url, String mimeType, long contentLength) {
        this(0, "download", url, mimeType, contentLength, 0, 0, System.currentTimeMillis());
    }

    public String getFormattedSpeed() {
        return formattedSpeed != null ? formattedSpeed : "";
    }

    public String getRemainingTime() {
        return remainingTime != null ? remainingTime : "";
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "未知大小";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static boolean matches(String name, String[] exts) {
        for (String e : exts) {
            if (name.endsWith(e)) return true;
        }
        return false;
    }

    /**
     * Classifies the download by extension first, then MIME type, and finally
     * falls back to {@link #CAT_OTHER}.
     */
    public int getCategory() {
        String name = fileName != null ? fileName.toLowerCase(Locale.US) : "";
        String mime = mimeType != null ? mimeType.toLowerCase(Locale.US) : "";

        if (name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")
            || name.endsWith(".apkm") || mime.contains("package-archive")) {
            return CAT_APK;
        }
        if (matches(name, ARCHIVE_EXT) || mime.contains("zip") || mime.contains("compressed")
            || mime.contains("x-tar") || mime.contains("x-7z") || mime.contains("x-rar")) {
            return CAT_ARCHIVE;
        }
        if (matches(name, DOC_EXT) || mime.contains("pdf") || mime.contains("document")
            || mime.contains("sheet") || mime.contains("presentation") || mime.startsWith("text/")) {
            return CAT_DOC;
        }
        if (matches(name, IMAGE_EXT) || mime.startsWith("image/")) {
            return CAT_IMAGE;
        }
        if (matches(name, VIDEO_EXT) || mime.startsWith("video/")) {
            return CAT_VIDEO;
        }
        if (matches(name, AUDIO_EXT) || mime.startsWith("audio/")) {
            return CAT_AUDIO;
        }
        return CAT_OTHER;
    }

    /** Short human label used in the list subtitle and filter pills. */
    public String getCategoryLabel() {
        switch (getCategory()) {
            case CAT_APK: return "安装包";
            case CAT_ARCHIVE: return "压缩包";
            case CAT_DOC: return "文档";
            case CAT_IMAGE: return "图片";
            case CAT_VIDEO: return "视频";
            case CAT_AUDIO: return "音频";
            default: return "其它";
        }
    }

    public int getProgressPercent() {
        if (status == DownloadManager.STATUS_SUCCESSFUL) return 100;
        if (contentLength > 0 && downloadedBytes > 0) {
            int p = (int) ((downloadedBytes * 100L) / contentLength);
            return Math.max(0, Math.min(100, p));
        }
        return status == DownloadManager.STATUS_RUNNING ? 5 : 0;
    }
}
