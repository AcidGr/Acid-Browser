package com.acid.browser.download;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Worker thread logic for downloading a single byte-range chunk directly
 * into pre-allocated RandomAccessFile offsets without memory buffers or post-merges.
 */
public class DownloadWorker implements Runnable {

    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECTS = 5;

    public interface WorkerListener {
        void onChunkProgress(DownloadTask task, DownloadChunk chunk, int bytesRead);
        void onChunkCompleted(DownloadTask task, DownloadChunk chunk);
        void onChunkFailed(DownloadTask task, DownloadChunk chunk, Exception error);
    }

    private final DownloadTask mTask;
    private final DownloadChunk mChunk;
    private final WorkerListener mListener;

    public DownloadWorker(DownloadTask task, DownloadChunk chunk, WorkerListener listener) {
        this.mTask = task;
        this.mChunk = chunk;
        this.mListener = listener;
    }

    @Override
    public void run() {
        if (mTask.isPaused || mTask.isCanceled || mChunk.completed) {
            return;
        }

        long seekOffset = mChunk.getCurrentSeekOffset();
        long endByte = mChunk.endByte;

        // Check if chunk is already fully downloaded
        if (endByte >= 0 && seekOffset > endByte) {
            mChunk.completed = true;
            if (mListener != null) mListener.onChunkCompleted(mTask, mChunk);
            return;
        }

        HttpURLConnection conn = null;
        InputStream is = null;
        RandomAccessFile raf = null;

        try {
            String currentUrl = mTask.finalUrl != null ? mTask.finalUrl : mTask.url;
            int redirects = 0;

            while (redirects < MAX_REDIRECTS) {
                if (mTask.isPaused || mTask.isCanceled) return;

                URL u = new URL(currentUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);

                // Preserve original headers across all redirects
                for (Map.Entry<String, String> entry : mTask.requestHeaders.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }

                // Apply Range header if Range is supported or specified
                if (mTask.supportsRange && endByte >= 0) {
                    conn.setRequestProperty("Range", "bytes=" + seekOffset + "-" + endByte);
                } else if (seekOffset > 0) {
                    conn.setRequestProperty("Range", "bytes=" + seekOffset + "-");
                }

                conn.connect();
                int code = conn.getResponseCode();

                // Handle HTTP Redirects
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.trim().isEmpty()) {
                        throw new IOException("Redirect without Location header (HTTP " + code + ")");
                    }
                    URL nextUrl = new URL(new URL(currentUrl), loc);
                    currentUrl = nextUrl.toString();
                    mTask.finalUrl = currentUrl;
                    redirects++;
                    continue;
                }

                if (code != 200 && code != 206) {
                    throw new IOException("Server returned HTTP " + code);
                }

                break;
            }

            if (redirects >= MAX_REDIRECTS) {
                throw new IOException("Too many redirects");
            }

            File dest = mTask.destinationFile;
            File parentDir = dest.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            raf = new RandomAccessFile(dest, "rw");
            raf.seek(seekOffset);

            is = conn.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;

            while ((n = is.read(buffer)) != -1) {
                if (mTask.isPaused || mTask.isCanceled) {
                    return;
                }

                // If chunk has fixed boundary, guard against overshooting
                if (endByte >= 0) {
                    long remaining = endByte - (seekOffset + mChunk.downloadedBytes) + 1;
                    if (remaining <= 0) break;
                    if (n > remaining) {
                        n = (int) remaining;
                    }
                }

                raf.write(buffer, 0, n);
                mChunk.downloadedBytes += n;
                mTask.addDownloadedBytes(n);

                if (mListener != null) {
                    mListener.onChunkProgress(mTask, mChunk, n);
                }

                if (endByte >= 0 && (seekOffset + mChunk.downloadedBytes) > endByte) {
                    break;
                }
            }

            if (endByte < 0 || (seekOffset + mChunk.downloadedBytes) > endByte) {
                mChunk.completed = true;
            }

            if (mListener != null) {
                mListener.onChunkCompleted(mTask, mChunk);
            }

        } catch (Exception e) {
            if (!mTask.isPaused && !mTask.isCanceled) {
                if (mListener != null) {
                    mListener.onChunkFailed(mTask, mChunk, e);
                }
            }
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (raf != null) {
                try { raf.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
