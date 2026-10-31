package com.acid.browser.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes optimal chunk partitioning for multi-threaded downloads.
 */
public final class ChunkDivider {

    public static final int DEFAULT_MAX_CHUNKS = 8;
    public static final long MIN_CHUNK_SIZE = 1024 * 1024; // 1 MB per chunk minimum

    private ChunkDivider() {}

    /**
     * Determines optimal chunk count based on total content length and range support.
     */
    public static int determineChunkCount(long contentLength, boolean supportsRange, int maxChunks) {
        if (!supportsRange || contentLength <= 0) {
            return 1;
        }
        int cap = maxChunks > 0 ? maxChunks : DEFAULT_MAX_CHUNKS;
        if (contentLength < 2L * 1024 * 1024) { // < 2 MB
            return 1;
        } else if (contentLength < 16L * 1024 * 1024) { // 2 - 16 MB
            return Math.min(cap, 3);
        } else if (contentLength < 64L * 1024 * 1024) { // 16 - 64 MB
            return Math.min(cap, 5);
        } else {
            return cap;
        }
    }

    /**
     * Divides total length into disjoint [start, end] ranges.
     */
    public static List<DownloadChunk> divide(long contentLength, int chunkCount) {
        if (contentLength <= 0 || chunkCount <= 1) {
            long end = contentLength > 0 ? (contentLength - 1) : -1;
            return Collections.singletonList(new DownloadChunk(0, 0, end));
        }

        List<DownloadChunk> list = new ArrayList<DownloadChunk>(chunkCount);
        long baseSize = contentLength / chunkCount;
        long start = 0;

        for (int i = 0; i < chunkCount; i++) {
            long end;
            if (i == chunkCount - 1) {
                end = contentLength - 1;
            } else {
                end = start + baseSize - 1;
            }
            list.add(new DownloadChunk(i, start, end));
            start = end + 1;
        }
        return list;
    }
}
