package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Data repository for browser bookmarks, browsing history, and global user preferences.
 *
 * Parsing policy: one corrupt record must never invalidate the whole collection. Every
 * element is decoded inside its own try/catch and skipped (with a log line) when it is
 * unreadable, so a single bad entry cannot silently wipe or freeze user data.
 */
public class BrowserDataStore {

    private static final String TAG = "AcidDataStore";

    public static final String PREF_NAME = "acid_browser_data";
    public static final String PREF_BOOKMARKS = "bookmarks";
    public static final String PREF_HISTORY = "history";
    public static final String PREF_DARK_MODE = "dark_mode";
    public static final String PREF_DOWNLOADS = "downloads";

    public static class EntryItem {
        public final String title;
        public final String url;

        public EntryItem(String title, String url) {
            this.title = title != null ? title : "";
            this.url = url != null ? url : "";
        }
    }

    private final Context mContext;

    public BrowserDataStore(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isDarkMode() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_DARK_MODE, false);
    }

    public void setDarkMode(boolean dark) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(PREF_DARK_MODE, dark);
        ed.apply();
    }

    /**
     * Decodes one bookmark/history element. Returns null (and logs) when the element is
     * unusable, so the caller can skip it without aborting the whole batch.
     */
    private static EntryItem decodeEntry(JSONObject o, int index, String kind) {
        if (o == null) {
            Log.w(TAG, kind + "[" + index + "] is not a JSON object; skipped");
            return null;
        }
        String url = o.optString("url", "");
        if (TextUtils.isEmpty(url)) {
            Log.w(TAG, kind + "[" + index + "] has no usable url; skipped");
            return null;
        }
        return new EntryItem(o.optString("title", url), url);
    }

    public List<EntryItem> loadBookmarks() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREF_BOOKMARKS, null);
        List<EntryItem> list = new ArrayList<EntryItem>();
        // "readable" means the stored blob was absent (first run) or decoded successfully.
        // It is false only for a corrupt blob, which must never be overwritten.
        boolean readable = (raw == null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                readable = true;
                for (int i = 0; i < arr.length(); i++) {
                    EntryItem item = decodeEntry(arr.optJSONObject(i), i, "bookmark");
                    if (item != null) list.add(item);
                }
            } catch (Exception e) {
                // Do NOT fall through to the seeding branch: overwriting an unreadable blob
                // would permanently destroy bookmarks that a later build could still recover.
                Log.w(TAG, "Bookmark store is unreadable; leaving stored data untouched", e);
                return list;
            }
        }

        if (list.isEmpty() && readable) {
            list.add(new EntryItem("Acid 靶场首页", BrowserController.HOME_URL));
            list.add(new EntryItem("Hacker News", "https://news.ycombinator.com"));
            list.add(new EntryItem("GitHub", "https://github.com"));
            list.add(new EntryItem("百度搜索", "https://www.baidu.com"));
            saveBookmarks(list);
        }
        return list;
    }

    public void saveBookmarks(List<EntryItem> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (EntryItem item : list) {
                if (item == null) continue;
                JSONObject o = new JSONObject();
                try {
                    o.put("title", item.title);
                    o.put("url", item.url);
                } catch (Exception e) {
                    // org.json declares a checked exception on put(); it is not thrown in
                    // practice, but log instead of swallowing so a real failure is visible.
                    Log.w(TAG, "Failed to encode bookmark: " + item.url, e);
                    continue;
                }
                arr.put(o);
            }
        }
        mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_BOOKMARKS, arr.toString())
            .apply();
    }

    public void addBookmark(String title, String url) {
        if (TextUtils.isEmpty(url)) return;
        List<EntryItem> list = loadBookmarks();
        list.add(0, new EntryItem(TextUtils.isEmpty(title) ? url : title, url));
        saveBookmarks(list);
    }

    public void removeBookmark(int index) {
        List<EntryItem> list = loadBookmarks();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            saveBookmarks(list);
        }
    }

    /**
     * Rewrites the bookmark at {@code index} with a new title/url pair.
     * EntryItem's fields are final, so the element is replaced rather than mutated.
     *
     * @return true when the index was valid and the list was persisted
     */
    public boolean updateBookmark(int index, String title, String url) {
        if (TextUtils.isEmpty(url)) return false;
        List<EntryItem> list = loadBookmarks();
        if (index < 0 || index >= list.size()) return false;
        String trimmedUrl = url.trim();
        list.set(index, new EntryItem(TextUtils.isEmpty(title) ? trimmedUrl : title.trim(), trimmedUrl));
        saveBookmarks(list);
        return true;
    }

    public List<EntryItem> loadHistory() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREF_HISTORY, null);
        List<EntryItem> list = new ArrayList<EntryItem>();
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                EntryItem item = decodeEntry(arr.optJSONObject(i), i, "history");
                if (item != null) list.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "History store is unreadable; returning what could be recovered", e);
        }
        return list;
    }

    public void clearHistory() {
        mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_HISTORY, "[]")
            .apply();
    }

    public void recordHistory(String url, String title, boolean incognitoMode) {
        if (incognitoMode) return;
        if (url == null || url.isEmpty() || url.equals(BrowserController.HOME_URL)) return;
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREF_HISTORY, "[]");
        JSONArray next = new JSONArray();
        try {
            JSONObject cur = new JSONObject();
            cur.put("title", TextUtils.isEmpty(title) ? url : title);
            cur.put("url", url);
            cur.put("time", System.currentTimeMillis());
            next.put(cur);
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode history entry: " + url, e);
            return;
        }

        // Carry over previous entries, skipping (not aborting on) individually corrupt ones.
        // A single bad record used to abort the whole loop and leave the write below
        // unreachable, permanently freezing history collection.
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && next.length() < 100; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    Log.w(TAG, "history[" + i + "] is not a JSON object; dropped on rewrite");
                    continue;
                }
                if (!url.equals(o.optString("url", ""))) {
                    next.put(o);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Previous history is unreadable; starting a fresh list", e);
        }

        prefs.edit().putString(PREF_HISTORY, next.toString()).apply();
    }

    public List<BrowserController.DownloadItem> loadDownloads() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREF_DOWNLOADS, null);
        List<BrowserController.DownloadItem> list = new ArrayList<BrowserController.DownloadItem>();
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    Log.w(TAG, "download[" + i + "] is not a JSON object; skipped");
                    continue;
                }
                list.add(new BrowserController.DownloadItem(
                    o.optLong("downloadId", 0),
                    o.optString("fileName", "file"),
                    o.optString("url", ""),
                    o.optString("mimeType", ""),
                    o.optLong("contentLength", 0),
                    o.optLong("downloadedBytes", 0),
                    o.optInt("status", 0),
                    o.optLong("timestamp", System.currentTimeMillis())
                ));
            }
        } catch (Exception e) {
            Log.w(TAG, "Download store is unreadable; returning what could be recovered", e);
        }
        return list;
    }

    public void saveDownloads(List<BrowserController.DownloadItem> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (BrowserController.DownloadItem item : list) {
                if (item == null) continue;
                JSONObject o = new JSONObject();
                try {
                    o.put("downloadId", item.downloadId);
                    o.put("fileName", item.fileName);
                    o.put("url", item.url);
                    o.put("mimeType", item.mimeType);
                    o.put("contentLength", item.contentLength);
                    o.put("downloadedBytes", item.downloadedBytes);
                    o.put("status", item.status);
                    o.put("timestamp", item.timestamp);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to encode download record: " + item.fileName, e);
                    continue;
                }
                arr.put(o);
            }
        }
        mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DOWNLOADS, arr.toString())
            .apply();
    }

    public void clearDownloads() {
        mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DOWNLOADS, "[]")
            .apply();
    }
}
