package com.acid.browser.download;

import com.acid.browser.AcidIo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private final Object mIoLock = new Object();
    private final Object mCheckpointIoLock = new Object();
    private volatile Future<?> mPendingCheckpointFuture = null;

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

    public List<HttpURLConnection> drainActiveConnections() {
        synchronized (mActiveConnections) {
            if (mActiveConnections.isEmpty()) {
                return Collections.emptyList();
            }
            List<HttpURLConnection> drained = new ArrayList<HttpURLConnection>(mActiveConnections);
            mActiveConnections.clear();
            return drained;
        }
    }

    public static void disconnectConnections(List<HttpURLConnection> connections) {
        if (connections == null || connections.isEmpty()) return;
        for (HttpURLConnection c : connections) {
            if (c == null) continue;
            try {
                c.disconnect();
            } catch (Throwable ignored) {}
        }
    }

    public void abortActiveConnections() {
        disconnectConnections(drainActiveConnections());
    }

    void setPendingCheckpointFuture(Future<?> future) {
        mPendingCheckpointFuture = future;
    }

    void awaitPendingCheckpointWrite() {
        Future<?> f = mPendingCheckpointFuture;
        if (f != null && !f.isDone()) {
            try {
                f.get(2000L, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
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
        this.destinationFile = resolveInside(destinationDir, this.fileName);
        this.checkpointFile = resolveInside(destinationDir, this.fileName + ".part.cfg");
        this.requestHeaders = headers != null ? new HashMap<String, String>(headers) : new HashMap<String, String>();
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Last line of defence against a name that escapes {@code dir}.
     *
     * <p>Callers are expected to pass an already-sanitised leaf name (see
     * {@code DownloadCoordinator.sanitizeDownloadFileName}), but this constructor is reached from
     * several entry points, so it re-verifies instead of trusting them. A name whose canonical
     * path falls outside {@code dir} is reduced to its leaf component.
     */
    private static File resolveInside(File dir, String name) {
        if (name != null && !name.trim().isEmpty()) {
            File candidate = new File(dir, name);
            try {
                String basePath = dir.getCanonicalPath();
                String candidatePath = candidate.getCanonicalPath();
                if (!candidatePath.equals(basePath) && candidatePath.startsWith(basePath + File.separator)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // Canonicalisation failed (unusual filesystem): fall through to the leaf fallback.
            }
        }
        String leaf = name != null ? name : "";
        int slash = Math.max(leaf.lastIndexOf('/'), leaf.lastIndexOf('\\'));
        if (slash >= 0) leaf = leaf.substring(slash + 1);
        leaf = leaf.replaceAll("[\\\\/:*?\"<>|;\\r\\n\\t\\x00]", "_").trim();
        if (leaf.isEmpty() || leaf.replace(".", "").isEmpty()) leaf = "download";
        return new File(dir, leaf);
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
        synchronized (mIoLock) {
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
                    AcidIo.closeQuietly(raf);
                }
            }
        }
    }

    /**
     * Opens {@link #destinationFile} under {@code mIoLock} only while the task is still active
     * for {@code expectedGen}, preventing cancelled tasks from recreating deleted files.
     */
    public RandomAccessFile openDestinationForWrite(int expectedGen) throws IOException {
        synchronized (mIoLock) {
            if (isPaused || isCanceled || getGeneration() != expectedGen || destinationFile == null) {
                return null;
            }
            File parentDir = destinationFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            return new RandomAccessFile(destinationFile, "rw");
        }
    }

    /**
     * Atomically writes a chunk buffer slice under {@code mIoLock} and checks the generation token
     * so stale or paused workers never mutate the destination file or chunk offsets concurrently.
     */
    public boolean writeChunkBytes(RandomAccessFile raf,
                                   DownloadChunk chunk,
                                   byte[] buf,
                                   int len,
                                   int expectedGen) throws IOException {
        if (raf == null || chunk == null || buf == null || len <= 0) return false;
        synchronized (mIoLock) {
            if (isPaused || isCanceled || getGeneration() != expectedGen) {
                return false;
            }
            raf.seek(chunk.getCurrentSeekOffset());
            raf.write(buf, 0, len);
            chunk.downloadedBytes += len;
        }
        addDownloadedBytesForGen(len, expectedGen);
        return true;
    }

    public synchronized void addDownloadedBytesForGen(long bytes, int expectedGen) {
        if (isPaused || isCanceled || getGeneration() != expectedGen) {
            return;
        }
        addDownloadedBytes(bytes);
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
     * Builds a consistent JSON snapshot of the task and all chunks under synchronized locks
     * without performing disk I/O inside the lock.
     */
    public synchronized String buildCheckpointJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("id", id);
            root.put("url", url);
            root.put("finalUrl", finalUrl);
            root.put("fileName", fileName);
            root.put("mimeType", mimeType);
            root.put("contentLength", contentLength);
            root.put("supportsRange", supportsRange);
            if (eTag != null && !eTag.isEmpty()) root.put("etag", eTag);
            if (lastModified != null && !lastModified.isEmpty()) root.put("lastModified", lastModified);

            JSONArray chunkArr = new JSONArray();
            long totalFromChunks = 0L;
            synchronized (mIoLock) {
                for (DownloadChunk chunk : chunks) {
                    chunkArr.put(chunk.toJsonObject());
                    totalFromChunks += chunk.downloadedBytes;
                }
            }
            root.put("downloadedBytes", Math.max(downloadedBytes, totalFromChunks));
            root.put("chunks", chunkArr);
            return root.toString(2);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean writeCheckpointJson(String json) {
        if (json == null || checkpointFile == null || isCanceled) return false;
        synchronized (mCheckpointIoLock) {
            if (isCanceled || status == STATUS_SUCCESSFUL) return false;
            return AcidIo.writeFileUtf8Safe(checkpointFile, json);
        }
    }

    /**
     * Writes an active-progress checkpoint only if the task has not been paused, canceled,
     * completed, failed, or superseded by a newer generation.
     */
    public boolean writeActiveCheckpointJson(String json, int expectedGen) {
        if (json == null || checkpointFile == null) return false;
        synchronized (mCheckpointIoLock) {
            if (isCanceled || isPaused || mGeneration.get() != expectedGen
                || status == STATUS_SUCCESSFUL || status == STATUS_FAILED) {
                return false;
            }
            return AcidIo.writeFileUtf8Safe(checkpointFile, json);
        }
    }

    /**
     * Persists a terminal/state-transition checkpoint snapshot (such as PAUSED or FAILED)
     * only if the generation and state still match, preventing late writes from resurrecting
     * deleted checkpoints after cancel or restart.
     */
    public boolean writeSnapshotCheckpointForState(String json, int expectedGen, int expectedStatus) {
        if (json == null || checkpointFile == null) return false;
        synchronized (mCheckpointIoLock) {
            if (mGeneration.get() != expectedGen || status != expectedStatus) {
                return false;
            }
            if (expectedStatus != STATUS_FAILED && isCanceled) {
                return false;
            }
            return AcidIo.writeFileUtf8Safe(checkpointFile, json);
        }
    }

    /**
     * Writes checkpoint state to .part.cfg file for instant resume across restarts.
     */
    public boolean saveCheckpoint() {
        return writeCheckpointJson(buildCheckpointJson());
    }

    /**
     * Restores checkpoint state from .part.cfg file.
     */
    public synchronized boolean loadCheckpoint() {
        synchronized (mCheckpointIoLock) {
            if (!checkpointFile.exists() || checkpointFile.length() == 0) {
                return false;
            }
            try {
                String json = AcidIo.readFileUtf8(checkpointFile);
                JSONObject root = new JSONObject(json);

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
    }

    public void cleanCheckpointFile() {
        synchronized (mCheckpointIoLock) {
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
}
