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

    private final ExecutorService mDispatcherPool = Executors.newCachedThreadPool();
    private final ExecutorService mWorkerPool = Executors.newFixedThreadPool(16);
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
     * Enqueues a new download task.
     */
    public DownloadTask enqueue(String url,
                                String fileName,
                                String mimeType,
                                File destinationDir,
                                Map<String, String> headers) {
        long id = mNextId.incrementAndGet();
        final DownloadTask task = new DownloadTask(id, url, fileName, mimeType, destinationDir, headers);
        mTasks.put(id, task);

        startTaskAsync(task);
        return task;
    }

    /**
     * Starts or resumes a task in the background dispatcher pool.
     */
    public void startTaskAsync(final DownloadTask task) {
        task.isPaused = false;
        task.isCanceled = false;
        task.status = DownloadTask.STATUS_PENDING;

        mDispatcherPool.execute(new Runnable() {
            @Override
            public void run() {
                executeTask(task);
            }
        });
    }

    public boolean pause(long taskId) {
        DownloadTask task = mTasks.get(taskId);
        if (task == null || task.status != DownloadTask.STATUS_RUNNING) {
            return false;
        }
        task.isPaused = true;
        task.status = DownloadTask.STATUS_PAUSED;
        task.saveCheckpoint();
        dispatchPaused(task);
        return true;
    }

    public boolean resume(long taskId) {
        DownloadTask task = mTasks.get(taskId);
        if (task == null || task.status != DownloadTask.STATUS_PAUSED) {
            return false;
        }
        startTaskAsync(task);
        return true;
    }

    public boolean cancel(long taskId, boolean deleteFile) {
        DownloadTask task = mTasks.get(taskId);
        if (task == null) return false;

        task.isCanceled = true;
        task.isPaused = true;
        task.cleanCheckpointFile();
        mTasks.remove(taskId);

        if (deleteFile && task.destinationFile.exists()) {
            try {
                task.destinationFile.delete();
            } catch (Exception ignored) {}
        }
        return true;
    }

    private void executeTask(final DownloadTask task) {
        task.status = DownloadTask.STATUS_RUNNING;
        dispatchStarted(task);

        try {
            boolean resumed = task.loadCheckpoint();
            if (!resumed) {
                // Pre-flight probe to discover range support & total size
                HttpProbe.ProbeResult probe = HttpProbe.probe(task.url, task.requestHeaders);
                task.finalUrl = probe.finalUrl;
                task.contentLength = probe.contentLength;
                task.supportsRange = probe.supportsRange;

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

            final List<DownloadChunk> activeChunks = task.getChunksSnapshot();
            final AtomicInteger remainingChunks = new AtomicInteger(activeChunks.size());

            DownloadWorker.WorkerListener workerListener = new DownloadWorker.WorkerListener() {
                @Override
                public void onChunkProgress(DownloadTask t, DownloadChunk c, int bytesRead) {
                    dispatchProgress(t);
                }

                @Override
                public void onChunkCompleted(DownloadTask t, DownloadChunk c) {
                    t.saveCheckpoint();
                    int rem = remainingChunks.decrementAndGet();
                    if (rem <= 0 && !t.isPaused && !t.isCanceled) {
                        t.status = DownloadTask.STATUS_SUCCESSFUL;
                        t.completedAt = System.currentTimeMillis();
                        t.cleanCheckpointFile();
                        dispatchCompleted(t);
                    }
                }

                @Override
                public void onChunkFailed(DownloadTask t, DownloadChunk c, Exception error) {
                    if (t.isPaused || t.isCanceled) return;
                    t.status = DownloadTask.STATUS_FAILED;
                    t.errorMessage = (error != null) ? error.getMessage() : "Download error";
                    t.saveCheckpoint();
                    dispatchFailed(t, t.errorMessage);
                }
            };

            for (DownloadChunk chunk : activeChunks) {
                if (task.isPaused || task.isCanceled) break;
                if (!chunk.completed) {
                    mWorkerPool.execute(new DownloadWorker(task, chunk, workerListener));
                } else {
                    remainingChunks.decrementAndGet();
                }
            }

            if (remainingChunks.get() <= 0 && !task.isPaused && !task.isCanceled) {
                task.status = DownloadTask.STATUS_SUCCESSFUL;
                task.completedAt = System.currentTimeMillis();
                task.cleanCheckpointFile();
                dispatchCompleted(task);
            }

        } catch (Exception e) {
            if (!task.isPaused && !task.isCanceled) {
                task.status = DownloadTask.STATUS_FAILED;
                task.errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                task.saveCheckpoint();
                dispatchFailed(task, task.errorMessage);
            }
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
