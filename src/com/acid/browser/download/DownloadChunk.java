package com.acid.browser.download;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents a single byte range chunk of a multi-threaded download task.
 */
public class DownloadChunk {
    public final int chunkIndex;
    public final long startByte;
    public final long endByte; // inclusive
    public volatile long downloadedBytes;
    public volatile boolean completed;

    public DownloadChunk(int chunkIndex, long startByte, long endByte) {
        this(chunkIndex, startByte, endByte, 0, false);
    }

    public DownloadChunk(int chunkIndex, long startByte, long endByte, long downloadedBytes, boolean completed) {
        this.chunkIndex = chunkIndex;
        this.startByte = startByte;
        this.endByte = endByte;
        this.downloadedBytes = downloadedBytes;
        this.completed = completed;
    }

    public long getTotalBytes() {
        if (endByte < startByte) return 0;
        return (endByte - startByte + 1);
    }

    public long getRemainingBytes() {
        if (completed) return 0;
        long total = getTotalBytes();
        long rem = total - downloadedBytes;
        return Math.max(0, rem);
    }

    public long getCurrentSeekOffset() {
        return startByte + downloadedBytes;
    }

    public JSONObject toJsonObject() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("index", chunkIndex);
        obj.put("start", startByte);
        obj.put("end", endByte);
        obj.put("downloaded", downloadedBytes);
        obj.put("completed", completed);
        return obj;
    }

    public static DownloadChunk fromJsonObject(JSONObject obj) {
        if (obj == null) return null;
        int index = obj.optInt("index", 0);
        long start = obj.optLong("start", 0);
        long end = obj.optLong("end", 0);
        long downloaded = obj.optLong("downloaded", 0);
        boolean completed = obj.optBoolean("completed", false);
        return new DownloadChunk(index, start, end, downloaded, completed);
    }
}
