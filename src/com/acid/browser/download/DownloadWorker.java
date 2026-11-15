package com.acid.browser.download;

import com.acid.browser.AcidHttp;
import com.acid.browser.AcidIo;

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
    private final int mGeneration;

    public DownloadWorker(DownloadTask task, DownloadChunk chunk, WorkerListener listener) {
        this(task, chunk, listener, task != null ? task.getGeneration() : 0);
    }

    public DownloadWorker(DownloadTask task, DownloadChunk chunk, WorkerListener listener, int generation) {
        this.mTask = task;
        this.mChunk = chunk;
        this.mListener = listener;
        this.mGeneration = generation;
    }

    private boolean isAborted() {
        if (mTask == null) return true;
        return mTask.isPaused || mTask.isCanceled || mTask.getGeneration() != mGeneration;
    }

    @Override
    public void run() {
        if (isAborted() || mChunk.completed) {
            return;
        }

        long seekOffset = mChunk.getCurrentSeekOffset();
        long endByte = mChunk.endByte;

        // Check if chunk is already fully downloaded
        if (endByte >= 0 && seekOffset > endByte) {
            mChunk.completed = true;
            if (mListener != null && !isAborted()) mListener.onChunkCompleted(mTask, mChunk);
            return;
        }

        HttpURLConnection conn = null;
        InputStream is = null;
        RandomAccessFile raf = null;

        try {
            String currentUrl = mTask.finalUrl != null ? mTask.finalUrl : mTask.url;
            Map<String, String> activeHeaders = new java.util.HashMap<String, String>(mTask.requestHeaders);
            // If pre-flight probe already redirected off-origin from mTask.url to mTask.finalUrl, enforce policy too
            if (mTask.url != null && !mTask.url.equals(currentUrl)) {
                try {
                    if (!AcidHttp.shouldRetainDownloadCredentials(new URL(mTask.url), new URL(currentUrl))) {
                        AcidHttp.stripSensitiveHeaders(activeHeaders);
                    }
                } catch (Exception ignored) {}
            }

            final long finalSeekOffset = seekOffset;
            final long finalEndByte = endByte;
            String validatorOrigin = (mTask.url != null && !mTask.url.isEmpty()) ? mTask.url : currentUrl;
            conn = AcidHttp.openConnectionWithRedirects(
                currentUrl,
                "GET",
                activeHeaders,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS,
                MAX_REDIRECTS,
                AcidHttp.createDownloadValidator(validatorOrigin),
                AcidHttp.DOWNLOAD_CREDENTIALS,
                new AcidHttp.RedirectListener() {
                    @Override
                    public void onConnectionOpened(HttpURLConnection c, URL url, Map<String, String> headers) throws IOException {
                        if (isAborted()) {
                            throw new IOException("Aborted");
                        }
                        mTask.registerConnection(c);

                        // Apply Range header if Range is supported or specified
                        if (mTask.supportsRange && finalEndByte >= 0) {
                            c.setRequestProperty("Range", "bytes=" + finalSeekOffset + "-" + finalEndByte);
                        } else if (finalSeekOffset > 0) {
                            c.setRequestProperty("Range", "bytes=" + finalSeekOffset + "-");
                        }
                        // Only send If-Range when resuming an interrupted chunk AND validator is a strong ETag
                        // (RFC 7232/7233 explicitly forbids weak validators W/ in If-Range)
                        boolean isResumingChunk = mChunk.downloadedBytes > 0 && finalSeekOffset > mChunk.startByte;
                        if (isResumingChunk && mTask.eTag != null && !mTask.eTag.trim().isEmpty()) {
                            String tag = mTask.eTag.trim();
                            if (!tag.startsWith("W/") && !tag.startsWith("w/")) {
                                c.setRequestProperty("If-Range", tag);
                            }
                        }
                    }

                    @Override
                    public void onConnectionClosing(HttpURLConnection c) {
                        mTask.unregisterConnection(c);
                    }
                }
            );

            int code = conn.getResponseCode();
            // Strict RFC 7233 Range guard: If we requested a sub-range (seekOffset > 0),
            // but the server returned HTTP 200 OK (full file body starting from byte 0),
            // writing it at seekOffset would cause silent data corruption!
            if (code == HttpURLConnection.HTTP_OK && seekOffset > 0) {
                if (!isAborted()) {
                    mTask.supportsRange = false;
                }
                throw new IOException("Server does not support byte-range resume (HTTP 200 OK returned for Range offset " + seekOffset + ")");
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("Server returned HTTP " + code);
            }

            if (isAborted()) return;

            File dest = mTask.destinationFile;
            raf = mTask.openDestinationForWrite(mGeneration);
            if (raf == null || isAborted()) {
                return;
            }
            raf.seek(seekOffset);

            is = conn.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;

            while ((n = is.read(buffer)) != -1) {
                if (isAborted()) {
                    return;
                }

                // If chunk has fixed boundary, guard against overshooting
                if (endByte >= 0) {
                    long remaining = endByte - mChunk.getCurrentSeekOffset() + 1;
                    if (remaining <= 0) break;
                    if (n > remaining) {
                        n = (int) remaining;
                    }
                }

                if (!mTask.writeChunkBytes(raf, mChunk, buffer, n, mGeneration)) {
                    return;
                }

                if (mListener != null && !isAborted()) {
                    mListener.onChunkProgress(mTask, mChunk, n);
                }

                if (endByte >= 0 && mChunk.getCurrentSeekOffset() > endByte) {
                    break;
                }
            }

            if (isAborted()) return;

            if (endByte < 0 || mChunk.getCurrentSeekOffset() > endByte) {
                mChunk.completed = true;
            } else {
                throw new IOException("Premature EOF for chunk " + mChunk.chunkIndex
                    + " at offset " + mChunk.getCurrentSeekOffset() + " (expected endByte " + endByte + ")");
            }

            if (mListener != null && !isAborted()) {
                mListener.onChunkCompleted(mTask, mChunk);
            }

        } catch (Exception e) {
            if (!isAborted()) {
                if (mListener != null) {
                    mListener.onChunkFailed(mTask, mChunk, e);
                }
            }
        } finally {
            AcidIo.closeQuietly(is);
            AcidIo.closeQuietly(raf);
            if (conn != null) {
                if (mTask != null) {
                    mTask.unregisterConnection(conn);
                }
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
