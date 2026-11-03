package com.acid.browser.download;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, multi-threaded native download manager with RFC 7233 range chunking,
 * dynamic speed calculation, and persistent checkpoint resume.
 */
public class AcidDownloader {

    public interface DownloaderListener {
        void onTaskStarted(DownloadTask task);
        void onTaskProgress(DownloadTask task);
        void onTaskPaused(DownloadTask task);
        void onTaskCompleted(DownloadTask task);
        void onTaskFailed(DownloadTask task, String error);
    }

    private static AcidDownloader sInstance;

    public static synchronized AcidDownloader getInstance() {
        if (sInstance == null) {
            sInstance = new AcidDownloader();
        }
        return sInstance;
    }

    private final AtomicLong mNextId = new AtomicLong(System.currentTimeMillis());
    private final ConcurrentHashMap<Long, DownloadTask> mTasks = new ConcurrentHashMap<Long, DownloadTask>();
    private final List<DownloaderListener> mListeners = new CopyOnWriteArrayList<DownloaderListener>();

    private final ExecutorService mDispatcherPool = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "AcidDownloadDispatcher-" + mCount.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });
    private final ExecutorService mWorkerPool = Executors.newFixedThreadPool(16, new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "AcidDownloadWorker-" + mCount.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });
    private Handler mMainHandler;

    public AcidDownloader() {
        try {
            if (Looper.getMainLooper() != null) {
                mMainHandler = new Handler(Looper.getMainLooper());
            }
        } catch (Exception ignored) {
            mMainHandler = null; // Stubs/test runner fallback
        }
    }

    public void addListener(DownloaderListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(DownloaderListener listener) {
        mListeners.remove(listener);
    }

    public DownloadTask getTask(long id) {
        return mTasks.get(id);
    }

    public List<DownloadTask> getAllTasks() {
        return new ArrayList<DownloadTask>(mTasks.values());
    }

    /**
     * Checks if any active (PENDING or RUNNING) task is currently downloading to this file name.
     */
    public boolean isFileNameActive(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        for (DownloadTask task : mTasks.values()) {
            if (task == null) continue;
            synchronized (task) {
                if (task.status == DownloadTask.STATUS_PENDING || task.status == DownloadTask.STATUS_RUNNING) {
                    if (fileName.equalsIgnoreCase(task.fileName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Enqueues a new download task with a specific id (or auto-generated if id <= 0).
     */
    public DownloadTask enqueue(long specificId,
                                String url,
                                String fileName,
                                String mimeType,
                                File destinationDir,
                                Map<String, String> headers) {
        long id = specificId > 0 ? specificId : mNextId.incrementAndGet();
        if (specificId > 0) {
            long cur;
            do {
                cur = mNextId.get();
                if (specificId <= cur) break;
            } while (!mNextId.compareAndSet(cur, specificId));
        }
        final DownloadTask task = new DownloadTask(id, url, fileName, mimeType, destinationDir, headers);
        mTasks.put(id, task);

        startTaskAsync(task);
        return task;
    }

    /**
     * Enqueues a new download task.
     */
    public DownloadTask enqueue(String url,
                                String fileName,
                                String mimeType,
                                File destinationDir,
                                Map<String, String> headers) {
        return enqueue(0, url, fileName, mimeType, destinationDir, headers);
    }

    /**
     * Starts or resumes a task in the background dispatcher pool.
     */
    public void startTaskAsync(final DownloadTask task) {
        if (task == null) return;
        final int runGen;
        synchronized (task) {
            task.isPaused = false;
            task.isCanceled = false;
            task.status = DownloadTask.STATUS_PENDING;
            runGen = task.nextGeneration();
        }

        mDispatcherPool.execute(new Runnable() {
            @Override
            public void run() {
                executeTask(task, runGen);
            }
        });
    }

    public synchronized boolean pause(long taskId) {
        DownloadTask task = mTasks.get(taskId);
        if (task == null) {
            return false;
        }
        synchronized (task) {
            if (task.status != DownloadTask.STATUS_RUNNING && task.status != DownloadTask.STATUS_PENDING) {
                return false;
            }
            task.isPaused = true;
            task.status = DownloadTask.STATUS_PAUSED;
            task.nextGeneration();
            task.abortActiveConnections();
            task.saveCheckpoint();
        }
        dispatchPaused(task);
        return true;
    }

    public synchronized boolean resume(long taskId) {
        DownloadTask task = mTasks.get(taskId);
        if (task == null) {
            return false;
        }
        synchronized (task) {
            if (task.status != DownloadTask.STATUS_PAUSED && task.status != DownloadTask.STATUS_FAILED) {
                return false;
            }
        }
        startTaskAsync(task);
        return true;
    }

    public synchronized boolean cancel(long taskId, boolean deleteFile) {
        DownloadTask task = mTasks.get(taskId);
        if (task == null) return false;

        synchronized (task) {
            task.isCanceled = true;
            task.isPaused = true;
            task.nextGeneration();
            task.abortActiveConnections();
            task.cleanCheckpointFile();
            mTasks.remove(taskId);

            if (deleteFile && task.destinationFile.exists()) {
                try {
                    task.destinationFile.delete();
                } catch (Exception ignored) {}
            }
        }
        return true;
    }

    private void executeTask(final DownloadTask task, final int runGen) {
        synchronized (task) {
            if (task.getGeneration() != runGen || task.isPaused || task.isCanceled) {
                return;
            }
            task.status = DownloadTask.STATUS_RUNNING;
        }
        dispatchStarted(task);

        try {
            boolean resumed = false;
            synchronized (task) {
                if (task.getGeneration() != runGen || task.isPaused || task.isCanceled) {
                    return;
                }
                resumed = task.loadCheckpoint();
                if (resumed && (task.destinationFile == null || !task.destinationFile.exists())) {
                    resumed = false;
                    task.cleanCheckpointFile();
                }
            }

            if (resumed) {
                // Verify remote file consistency before resuming to ensure the server resource hasn't changed
                try {
                    HttpProbe.ProbeResult probe = HttpProbe.probe(task.url, task.requestHeaders);
                    if (probe.contentLength > 0 && task.contentLength > 0 && probe.contentLength != task.contentLength) {
                        // Remote size changed! Checkpoint is obsolete
                        resumed = false;
                        task.cleanCheckpointFile();
                    } else if (probe.eTag != null && task.eTag != null && !probe.eTag.equals(task.eTag)) {
                        // Remote ETag changed! Resource was modified
                        resumed = false;
                        task.cleanCheckpointFile();
                    } else {
                        task.finalUrl = probe.finalUrl;
                        if (probe.eTag != null) task.eTag = probe.eTag;
                        if (probe.lastModified != null) task.lastModified = probe.lastModified;
                    }
                } catch (Exception ignored) {
                    // Probe network hiccup: allow workers to proceed with existing checkpoint
                }
            }

            if (!resumed) {
                // Pre-flight probe to discover range support & total size
                HttpProbe.ProbeResult probe = HttpProbe.probe(task.url, task.requestHeaders);

                synchronized (task) {
                    if (task.getGeneration() != runGen || task.isPaused || task.isCanceled) {
                        return;
                    }
                    task.finalUrl = probe.finalUrl;
                    task.contentLength = probe.contentLength;
                    task.supportsRange = probe.supportsRange;
                    task.eTag = probe.eTag;
                    task.lastModified = probe.lastModified;

                    if (task.mimeType == null || task.mimeType.isEmpty() || "*/*".equals(task.mimeType)) {
                        task.mimeType = probe.mimeType;
                    }

                    // If destination dir doesn't exist, create it
                    File parent = task.destinationFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }

                    // Pre-allocate destination file space if length is known to avoid fragmentation
                    if (task.contentLength > 0) {
                        RandomAccessFile raf = null;
                        try {
                            raf = new RandomAccessFile(task.destinationFile, "rw");
                            raf.setLength(task.contentLength);
                        } catch (Exception ignored) {
                        } finally {
                            if (raf != null) {
                                try { raf.close(); } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Compute chunks
                    int chunkCount = ChunkDivider.determineChunkCount(
                        task.contentLength,
                        task.supportsRange,
                        ChunkDivider.DEFAULT_MAX_CHUNKS
                    );
                    List<DownloadChunk> chunks = ChunkDivider.divide(task.contentLength, chunkCount);
                    task.setChunks(chunks);
                    task.saveCheckpoint();
                }
            }

            final List<DownloadChunk> activeChunks;
            final AtomicInteger remainingChunks;
            synchronized (task) {
                if (task.getGeneration() != runGen || task.isPaused || task.isCanceled) {
                    return;
                }
                activeChunks = task.getChunksSnapshot();
                remainingChunks = new AtomicInteger(activeChunks.size());
            }

            DownloadWorker.WorkerListener workerListener = new DownloadWorker.WorkerListener() {
                @Override
                public void onChunkProgress(DownloadTask t, DownloadChunk c, int bytesRead) {
                    if (t.getGeneration() != runGen || t.isPaused || t.isCanceled) return;
                    if (t.shouldDispatchProgress(System.currentTimeMillis())) {
                        dispatchProgress(t);
                    }
                }

                @Override
                public void onChunkCompleted(DownloadTask t, DownloadChunk c) {
                    synchronized (t) {
                        if (t.getGeneration() != runGen || t.isCanceled || t.isPaused || t.status == DownloadTask.STATUS_FAILED) {
                            return;
                        }
                        t.saveCheckpoint();
                        int rem = remainingChunks.decrementAndGet();
                        if (rem <= 0 && !t.isPaused && !t.isCanceled && t.status != DownloadTask.STATUS_FAILED) {
                            t.status = DownloadTask.STATUS_SUCCESSFUL;
                            t.completedAt = System.currentTimeMillis();
                            t.cleanCheckpointFile();
                            dispatchCompleted(t);
                        }
                    }
                }

                @Override
                public void onChunkFailed(DownloadTask t, DownloadChunk c, Exception error) {
                    synchronized (t) {
                        if (t.getGeneration() != runGen || t.isPaused || t.isCanceled || t.status == DownloadTask.STATUS_FAILED) return;
                        t.isCanceled = true; // Signal all active worker threads to halt immediately
                        t.status = DownloadTask.STATUS_FAILED;
                        t.errorMessage = (error != null) ? error.getMessage() : "Download error";
                        t.nextGeneration();
                        t.abortActiveConnections();
                        if (t.supportsRange) {
                            // Preserves checkpoint so subsequent retry can resume where it left off
                            t.saveCheckpoint();
                        } else {
                            // Non-range downloads cannot resume; purge dirty partial file and checkpoint
                            t.cleanCheckpointFile();
                            if (t.destinationFile != null && t.destinationFile.exists()) {
                                try {
                                    t.destinationFile.delete();
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    dispatchFailed(t, t.errorMessage);
                }
            };

            for (DownloadChunk chunk : activeChunks) {
                synchronized (task) {
                    if (task.getGeneration() != runGen || task.isPaused || task.isCanceled) break;
                }
                if (!chunk.completed) {
                    mWorkerPool.execute(new DownloadWorker(task, chunk, workerListener, runGen));
                } else {
                    remainingChunks.decrementAndGet();
                }
            }

            synchronized (task) {
                if (task.getGeneration() == runGen && remainingChunks.get() <= 0
                    && !task.isPaused && !task.isCanceled && task.status != DownloadTask.STATUS_FAILED) {
                    task.status = DownloadTask.STATUS_SUCCESSFUL;
                    task.completedAt = System.currentTimeMillis();
                    task.cleanCheckpointFile();
                    dispatchCompleted(task);
                }
            }

        } catch (Exception e) {
            synchronized (task) {
                if (task.getGeneration() != runGen || task.isPaused || task.isCanceled || task.status == DownloadTask.STATUS_FAILED) {
                    return;
                }
                task.isCanceled = true;
                task.status = DownloadTask.STATUS_FAILED;
                task.errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                task.nextGeneration();
                task.abortActiveConnections();
                if (task.supportsRange) {
                    task.saveCheckpoint();
                } else {
                    task.cleanCheckpointFile();
                    if (task.destinationFile != null && task.destinationFile.exists()) {
                        try {
                            task.destinationFile.delete();
                        } catch (Exception ignored) {}
                    }
                }
            }
            dispatchFailed(task, task.errorMessage);
        }
    }

    private void runOnMain(Runnable r) {
        if (mMainHandler != null) {
            mMainHandler.post(r);
        } else {
            r.run();
        }
    }

    private void dispatchStarted(final DownloadTask task) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                for (DownloaderListener l : mListeners) {
                    l.onTaskStarted(task);
                }
            }
        });
    }

    private void dispatchProgress(final DownloadTask task) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                for (DownloaderListener l : mListeners) {
                    l.onTaskProgress(task);
                }
            }
        });
    }

    private void dispatchPaused(final DownloadTask task) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                for (DownloaderListener l : mListeners) {
                    l.onTaskPaused(task);
                }
            }
        });
    }

    private void dispatchCompleted(final DownloadTask task) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                for (DownloaderListener l : mListeners) {
                    l.onTaskCompleted(task);
                }
            }
        });
    }

    private void dispatchFailed(final DownloadTask task, final String error) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                for (DownloaderListener l : mListeners) {
                    l.onTaskFailed(task, error);
                }
            }
        });
    }
}
