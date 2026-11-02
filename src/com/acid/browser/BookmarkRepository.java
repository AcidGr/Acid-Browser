package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository managing user bookmarks, in-memory caching, SharedPreferences persistence,
 * and two-way sync with workspace `bookmarks.json`.
 */
public class BookmarkRepository {

    private static final String TAG = "AcidBookmarkRepo";
    public static final String PREF_BOOKMARKS = "bookmarks";
    private static final String PREF_NAME = "acid_browser_prefs";

    private final Context mContext;
    private final SharedPreferences mPrefsOverride;
    private File mBookmarksJsonFile;
    private BrowserDataStore.OnBookmarksChangedListener mBookmarksChangedListener;
    private Runnable mBookmarksMutatedListener;
    private BrowserDataStore.CallbackDispatcher mDispatcher;
    private volatile long mLastSelfSaveTime = 0;

    public BookmarkRepository(Context context) {
        this(context, null, null);
    }

    public BookmarkRepository(Context context, SharedPreferences prefsOverride, File bookmarksJsonFile) {
        mContext = context != null ? context.getApplicationContext() : null;
        mPrefsOverride = prefsOverride;
        mBookmarksJsonFile = bookmarksJsonFile;
    }

    private SharedPreferences prefs() {
        if (mPrefsOverride != null) return mPrefsOverride;
        if (mContext != null) return mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        throw new IllegalStateException("Neither SharedPreferences override nor Context provided to BookmarkRepository");
    }

    public synchronized void setJsonFile(File jsonFile) {
        mBookmarksJsonFile = jsonFile;
    }

    public File getJsonFile() {
        return mBookmarksJsonFile;
    }

    public synchronized void setOnBookmarksChangedListener(BrowserDataStore.OnBookmarksChangedListener listener) {
        mBookmarksChangedListener = listener;
    }

    public synchronized void setOnBookmarksMutatedListener(Runnable listener) {
        mBookmarksMutatedListener = listener;
    }

    public synchronized void setCallbackDispatcher(BrowserDataStore.CallbackDispatcher dispatcher) {
        mDispatcher = dispatcher;
    }

    private void dispatchCallback(Runnable action) {
        if (action == null) return;
        if (mDispatcher != null) {
            mDispatcher.dispatch(action);
        } else {
            action.run();
        }
    }

    public long getLastSelfSaveTime() {
        return mLastSelfSaveTime;
    }

    public void setLastSelfSaveTime(long time) {
        mLastSelfSaveTime = time;
    }

    public void syncJsonFile() {
        if (mBookmarksJsonFile == null) return;
        if (mBookmarksJsonFile.exists()) {
            reloadBookmarksFromJsonFile();
        } else {
            saveBookmarksToJsonFile();
        }
    }

    public synchronized List<BrowserDataStore.EntryItem> loadBookmarks() {
        SharedPreferences prefs = prefs();
        String raw = prefs.getString(PREF_BOOKMARKS, null);
        List<BrowserDataStore.EntryItem> list = new ArrayList<BrowserDataStore.EntryItem>();
        boolean readable = (raw == null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                readable = true;
                for (int i = 0; i < arr.length(); i++) {
                    BrowserDataStore.EntryItem item = decodeBookmarkEntry(arr.optJSONObject(i), i);
                    if (item != null) list.add(item);
                }
            } catch (Exception e) {
                Log.w(TAG, "Bookmark store is unreadable; leaving stored data untouched", e);
                return list;
            }
        }

        if (list.isEmpty() && readable) {
            list.add(new BrowserDataStore.EntryItem("Google", "https://www.google.com"));
            list.add(new BrowserDataStore.EntryItem("GitHub", "https://github.com"));
            list.add(new BrowserDataStore.EntryItem("Bilibili", "https://www.bilibili.com"));
            list.add(new BrowserDataStore.EntryItem("知乎", "https://www.zhihu.com"));
            list.add(new BrowserDataStore.EntryItem("Hacker News", "https://news.ycombinator.com"));
            list.add(new BrowserDataStore.EntryItem("百度", "https://www.baidu.com"));
            saveBookmarks(list, false);
            if (mBookmarksJsonFile != null) {
                saveBookmarksToJsonFile();
            }
        }
        return list;
    }

    public void saveBookmarks(List<BrowserDataStore.EntryItem> list) {
        saveBookmarks(list, true);
    }

    public synchronized void saveBookmarks(List<BrowserDataStore.EntryItem> list, boolean syncFile) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (BrowserDataStore.EntryItem item : list) {
                if (item == null) continue;
                JSONObject o = new JSONObject();
                try {
                    o.put("title", item.title);
                    o.put("url", item.url);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to encode bookmark: " + item.url, e);
                    continue;
                }
                arr.put(o);
            }
        }
        prefs()
            .edit()
            .putString(PREF_BOOKMARKS, arr.toString())
            .apply();

        if (syncFile && mBookmarksJsonFile != null) {
            saveBookmarksToJsonFile(arr);
        }
        if (mBookmarksMutatedListener != null) {
            dispatchCallback(mBookmarksMutatedListener);
        }
    }

    public synchronized boolean saveBookmarksToJsonFile() {
        return saveBookmarksToJsonFile(null);
    }

    public synchronized boolean saveBookmarksToJsonFile(JSONArray cachedArr) {
        if (mBookmarksJsonFile == null) return false;
        try {
            JSONArray arr = cachedArr;
            if (arr == null) {
                List<BrowserDataStore.EntryItem> list = loadBookmarks();
                arr = new JSONArray();
                for (BrowserDataStore.EntryItem item : list) {
                    if (item == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("title", item.title);
                    o.put("url", item.url);
                    arr.put(o);
                }
            }
            mLastSelfSaveTime = System.currentTimeMillis();
            String formatted;
            try {
                formatted = arr.toString(2);
            } catch (Exception e) {
                formatted = arr.toString();
            }
            AcidIo.writeFileUtf8(mBookmarksJsonFile, formatted);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to save bookmarks to " + mBookmarksJsonFile + ": " + e.getMessage(), e);
            return false;
        }
    }

    public synchronized boolean reloadBookmarksFromJsonFile() {
        if (mBookmarksJsonFile == null || !mBookmarksJsonFile.exists()) return false;
        try {
            String jsonStr = AcidIo.readFileUtf8(mBookmarksJsonFile).trim();
            if (jsonStr.isEmpty()) return false;

            JSONArray arr = new JSONArray(jsonStr);
            List<BrowserDataStore.EntryItem> list = new ArrayList<BrowserDataStore.EntryItem>();
            for (int i = 0; i < arr.length(); i++) {
                BrowserDataStore.EntryItem item = decodeBookmarkEntry(arr.optJSONObject(i), i);
                if (item != null) list.add(item);
            }

            saveBookmarks(list, false);

            dispatchCallback(new Runnable() {
                @Override
                public void run() {
                    if (mBookmarksChangedListener != null) {
                        mBookmarksChangedListener.onBookmarksChanged();
                    }
                }
            });
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to reload bookmarks from " + mBookmarksJsonFile + ": " + e.getMessage(), e);
            return false;
        }
    }

    public synchronized void addBookmark(String title, String url) {
        if (TextUtils.isEmpty(url)) return;
        List<BrowserDataStore.EntryItem> list = loadBookmarks();
        list.add(0, new BrowserDataStore.EntryItem(TextUtils.isEmpty(title) ? url : title, url));
        saveBookmarks(list);
    }

    public synchronized void removeBookmark(int index) {
        List<BrowserDataStore.EntryItem> list = loadBookmarks();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            saveBookmarks(list);
        }
    }

    public synchronized boolean updateBookmark(int index, String title, String url) {
        if (TextUtils.isEmpty(url)) return false;
        List<BrowserDataStore.EntryItem> list = loadBookmarks();
        if (index < 0 || index >= list.size()) return false;
        String trimmedUrl = url.trim();
        list.set(index, new BrowserDataStore.EntryItem(TextUtils.isEmpty(title) ? trimmedUrl : title.trim(), trimmedUrl));
        saveBookmarks(list);
        return true;
    }

    private static BrowserDataStore.EntryItem decodeBookmarkEntry(JSONObject o, int index) {
        if (o == null) {
            Log.w(TAG, "bookmark[" + index + "] is not a JSON object; skipped");
            return null;
        }
        String url = o.optString("url", "");
        if (TextUtils.isEmpty(url)) {
            Log.w(TAG, "bookmark[" + index + "] has no usable url; skipped");
            return null;
        }
        long time = o.optLong("time", 0);
        return new BrowserDataStore.EntryItem(o.optString("title", url), url, time);
    }
}
