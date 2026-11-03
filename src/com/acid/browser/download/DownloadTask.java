package com.acid.browser.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encapsulates the full state and checkpointing lifecycle of a single download task.
 */
public class DownloadTask {

    public static final int STATUS_SAVED = 0;
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_PAUSED = 4;
    public static final int STATUS_SUCCESSFUL = 8;
    public static final int STATUS_FAILED = 16;

    public final long id;
    public final String url;
    public volatile String finalUrl;
    public volatile String fileName;
    public volatile String mimeType;
    public final File destinationFile;
    public final File checkpointFile;
    public final Map<String, String> requestHeaders;

    public volatile long contentLength = -1;
    public volatile long downloadedBytes = 0;
    public volatile boolean supportsRange = false;
    public volatile int status = STATUS_PENDING;
    public volatile String errorMessage = null;
    public volatile String eTag = null;
    public volatile String lastModified = null;

    public final long createdAt;
    public volatile long completedAt = 0;

    public final List<DownloadChunk> chunks = new ArrayList<DownloadChunk>();

    // Cancellation & pause control flags
    public volatile boolean isPaused = false;
    public volatile boolean isCanceled = false;

    // Run generation token: incremented on start, pause, resume, cancel, or failure.
    // Guarantees obsolete or lingering worker threads immediately brake and cannot mutate state.
    private final AtomicInteger mGeneration = new AtomicInteger(0);
    private final List<HttpURLConnection> mActiveConnections = new ArrayList<HttpURLConnection>();

    public int getGeneration() {
        return mGeneration.get();
    }

    public int nextGeneration() {
        return mGeneration.incrementAndGet();
    }

    public void registerConnection(HttpURLConnection conn) {
        if (conn == null) return;
        synchronized (mActiveConnections) {
            mActiveConnections.add(conn);
        }
    }

    public void unregisterConnection(HttpURLConnection conn) {
        if (conn == null) return;
        synchronized (mActiveConnections) {
            mActiveConnections.remove(conn);
        }
    }

    public void abortActiveConnections() {
        List<HttpURLConnection> toAbort;
        synchronized (mActiveConnections) {
            toAbort = new ArrayList<HttpURLConnection>(mActiveConnections);
            mActiveConnections.clear();
        }
        for (HttpURLConnection c : toAbort) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {}
        }
    }

    // Rolling speed tracking & progress dispatch throttling
    public static final long PROGRESS_THROTTLE_MS = 200L;
    public static final long CHECKPOINT_THROTTLE_MS = 1500L;
    private long mLastSpeedSampleTime = 0;
    private long mLastSpeedSampleBytes = 0;
    private long mLastProgressDispatchTime = 0;
    private long mLastCheckpointSaveTime = 0;
    public volatile long currentSpeedBytesPerSec = 0;

    public DownloadTask(long id,
                        String url,
                        String fileName,
                        String mimeType,
                        File destinationDir,
                        Map<String, String> headers) {
        this.id = id;
        this.url = url != null ? url : "";
        this.finalUrl = this.url;
        this.fileName = (fileName != null && !fileName.trim().isEmpty()) ? fileName.trim() : "file";
        this.mimeType = mimeType != null ? mimeType : "";
        this.destinationFile = new File(destinationDir, this.fileName);
        this.checkpointFile = new File(destinationDir, this.fileName + ".part.cfg");
        this.requestHeaders = headers != null ? new HashMap<String, String>(headers) : new HashMap<String, String>();
        this.createdAt = System.currentTimeMillis();
    }

    public synchronized void setChunks(List<DownloadChunk> newChunks) {
        chunks.clear();
        if (newChunks != null) {
            chunks.addAll(newChunks);
        }
    }

    /**
     * Resets progress counters, chunks, and truncates any stale partial destination file
     * before starting or restarting a download from byte 0.
     */
    public synchronized void resetForFreshStart() {
        this.downloadedBytes = 0;
        this.currentSpeedBytesPerSec = 0;
        this.mLastSpeedSampleTime = 0;
        this.mLastSpeedSampleBytes = 0;
        this.mLastCheckpointSaveTime = 0;
        this.chunks.clear();
        cleanCheckpointFile();
        if (this.destinationFile != null && this.destinationFile.exists()) {
            RandomAccessFile raf = null;
            try {
                raf = new RandomAccessFile(this.destinationFile, "rw");
                raf.setLength(0);
            } catch (Exception e) {
                try {
                    this.destinationFile.delete();
                } catch (Exception ignored) {}
            } finally {
                if (raf != null) {
                    try { raf.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    public synchronized List<DownloadChunk> getChunksSnapshot() {
        return new ArrayList<DownloadChunk>(chunks);
    }

    public synchronized void addDownloadedBytes(long bytes) {
        if (bytes > 0) {
            downloadedBytes += bytes;
            updateSpeedSample();
        }
    }

    /**
     * Returns true at most once every {@link #PROGRESS_THROTTLE_MS} milliseconds so
     * multi-threaded workers do not flood the main thread or SharedPreferences on every 8KB read.
     */
    public synchronized boolean shouldDispatchProgress(long nowMs) {
        if (nowMs - mLastProgressDispatchTime >= PROGRESS_THROTTLE_MS) {
            mLastProgressDispatchTime = nowMs;
            return true;
        }
        return false;
    }

    /**
     * Returns true at most once every {@link #CHECKPOINT_THROTTLE_MS} milliseconds
     * to throttle saving chunk progress to .part.cfg while download is actively in-flight.
     */
    public synchronized boolean shouldSaveCheckpoint(long nowMs) {
        if (supportsRange && (mLastCheckpointSaveTime == 0 || (nowMs - mLastCheckpointSaveTime >= CHECKPOINT_THROTTLE_MS))) {
            mLastCheckpointSaveTime = nowMs;
            return true;
        }
        return false;
    }

    private void updateSpeedSample() {
        long now = System.currentTimeMillis();
        if (mLastSpeedSampleTime == 0) {
            mLastSpeedSampleTime = now;
            mLastSpeedSampleBytes = downloadedBytes;
            return;
        }
        long timeDelta = now - mLastSpeedSampleTime;
        if (timeDelta >= 800) { // update sample every 800ms
            long bytesDelta = downloadedBytes - mLastSpeedSampleBytes;
            if (bytesDelta >= 0) {
                currentSpeedBytesPerSec = (bytesDelta * 1000L) / timeDelta;
            }
            mLastSpeedSampleTime = now;
            mLastSpeedSampleBytes = downloadedBytes;
        }
    }

    public int getProgressPercent() {
        if (status == STATUS_SUCCESSFUL) return 100;
        if (contentLength > 0 && downloadedBytes > 0) {
            int p = (int) ((downloadedBytes * 100L) / contentLength);
            return Math.max(0, Math.min(100, p));
        }
        return status == STATUS_RUNNING ? 5 : 0;
    }

    public String getFormattedSpeed() {
        if (status != STATUS_RUNNING || currentSpeedBytesPerSec <= 0) {
            return "";
        }
        if (currentSpeedBytesPerSec < 1024) {
            return currentSpeedBytesPerSec + " B/s";
        } else if (currentSpeedBytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB/s", currentSpeedBytesPerSec / 1024.0);
        } else {
            return String.format(Locale.US, "%.2f MB/s", currentSpeedBytesPerSec / (1024.0 * 1024.0));
        }
    }

    public String getEstimatedRemainingTime() {
        if (status != STATUS_RUNNING || currentSpeedBytesPerSec <= 0 || contentLength <= 0) {
            return "";
        }
        long remainingBytes = contentLength - downloadedBytes;
        if (remainingBytes <= 0) return "";
        long seconds = remainingBytes / currentSpeedBytesPerSec;
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            long mins = seconds / 60;
            long remSecs = seconds % 60;
            return mins + "分" + (remSecs > 0 ? remSecs + "秒" : "");
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return hours + "小时" + (mins > 0 ? mins + "分" : "");
        }
    }

    /**
     * Writes checkpoint state to .part.cfg file for instant resume across restarts.
     */
    public synchronized boolean saveCheckpoint() {
        try {
            JSONObject root = new JSONObject();
            root.put("id", id);
            root.put("url", url);
            root.put("finalUrl", finalUrl);
            root.put("fileName", fileName);
            root.put("mimeType", mimeType);
            root.put("contentLength", contentLength);
            root.put("downloadedBytes", downloadedBytes);
            root.put("supportsRange", supportsRange);
            if (eTag != null && !eTag.isEmpty()) root.put("etag", eTag);
            if (lastModified != null && !lastModified.isEmpty()) root.put("lastModified", lastModified);

            JSONArray chunkArr = new JSONArray();
            for (DownloadChunk chunk : chunks) {
                chunkArr.put(chunk.toJsonObject());
            }
            root.put("chunks", chunkArr);

            File parent = checkpointFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File tmpFile = new File(checkpointFile.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmpFile);
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Exception ignored) {}
            fos.close();

            if (tmpFile.renameTo(checkpointFile)) {
                return true;
            } else {
                if (checkpointFile.exists()) {
                    checkpointFile.delete();
                }
                if (tmpFile.renameTo(checkpointFile)) {
                    return true;
                }
                try {
                    FileOutputStream directFos = new FileOutputStream(checkpointFile);
                    directFos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                    directFos.flush();
                    directFos.close();
                    tmpFile.delete();
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Restores checkpoint state from .part.cfg file.
     */
    public synchronized boolean loadCheckpoint() {
        if (!checkpointFile.exists() || checkpointFile.length() == 0) {
            return false;
        }
        try {
            FileInputStream fis = new FileInputStream(checkpointFile);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();

            JSONObject root = new JSONObject(sb.toString());

            // Strict URL validation: Verify this checkpoint belongs to the exact same URL
            String savedUrl = root.optString("url", "");
            if (savedUrl.isEmpty() || !savedUrl.equals(this.url)) {
                return false;
            }

            this.finalUrl = root.optString("finalUrl", this.url);
            this.contentLength = root.optLong("contentLength", -1);
            this.supportsRange = root.optBoolean("supportsRange", false);
            this.eTag = root.has("etag") ? root.optString("etag", null) : null;
            this.lastModified = root.has("lastModified") ? root.optString("lastModified", null) : null;

            JSONArray chunkArr = root.optJSONArray("chunks");
            if (chunkArr != null && chunkArr.length() > 0) {
                chunks.clear();
                long totalDownloaded = 0;
                for (int i = 0; i < chunkArr.length(); i++) {
                    DownloadChunk chunk = DownloadChunk.fromJsonObject(chunkArr.optJSONObject(i));
                    if (chunk != null) {
                        chunks.add(chunk);
                        totalDownloaded += chunk.downloadedBytes;
                    }
                }
                this.downloadedBytes = totalDownloaded;
                return true;
            }
        } catch (Exception e) {
            // Ignore corrupted checkpoint
        }
        return false;
    }

    public void cleanCheckpointFile() {
        try {
            if (checkpointFile != null) {
                if (checkpointFile.exists()) {
                    checkpointFile.delete();
                }
                File tmp = new File(checkpointFile.getAbsolutePath() + ".tmp");
                if (tmp.exists()) {
                    tmp.delete();
                }
            }
        } catch (Exception ignored) {}
    }
}
