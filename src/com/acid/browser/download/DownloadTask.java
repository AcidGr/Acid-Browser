package com.acid.browser.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    public final long createdAt;
    public volatile long completedAt = 0;

    public final List<DownloadChunk> chunks = new ArrayList<DownloadChunk>();

    // Cancellation & pause control flags
    public volatile boolean isPaused = false;
    public volatile boolean isCanceled = false;

    // Rolling speed tracking
    private long mLastSpeedSampleTime = 0;
    private long mLastSpeedSampleBytes = 0;
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

    public synchronized List<DownloadChunk> getChunksSnapshot() {
        return new ArrayList<DownloadChunk>(chunks);
    }

    public synchronized void addDownloadedBytes(long bytes) {
        if (bytes > 0) {
            downloadedBytes += bytes;
            updateSpeedSample();
        }
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

            JSONArray chunkArr = new JSONArray();
            for (DownloadChunk chunk : chunks) {
                chunkArr.put(chunk.toJsonObject());
            }
            root.put("chunks", chunkArr);

            FileOutputStream fos = new FileOutputStream(checkpointFile);
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
            return true;
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
            this.finalUrl = root.optString("finalUrl", this.url);
            this.contentLength = root.optLong("contentLength", -1);
            this.supportsRange = root.optBoolean("supportsRange", false);

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
            if (checkpointFile != null && checkpointFile.exists()) {
                checkpointFile.delete();
            }
        } catch (Exception ignored) {}
    }
}
