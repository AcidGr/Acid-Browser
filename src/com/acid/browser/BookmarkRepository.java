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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Repository managing user bookmarks, in-memory caching, SharedPreferences persistence,
 * and two-way sync with workspace `bookmarks.json`.
 */
public class BookmarkRepository {

    private static final String TAG = "AcidBookmarkRepo";
    public static final String PREF_BOOKMARKS = "bookmarks";
    public static final String PREF_BOOKMARKS_PENDING_SYNC = "bookmarks_pending_json_sync";
    public static final String PREF_NAME = BrowserDataStore.PREF_NAME;

    private final Context mContext;
    private final SharedPreferences mPrefsOverride;
    private volatile File mBookmarksJsonFile;
    private final Object mFileLock = new Object();
    private BrowserDataStore.OnBookmarksChangedListener mBookmarksChangedListener;
    private Runnable mBookmarksMutatedListener;
    private BrowserDataStore.CallbackDispatcher mDispatcher;
    private volatile long mLastSelfSaveTime = 0;
    private volatile String mLastSyncedJson = null;
    private final AtomicLong mBookmarksRevision = new AtomicLong(0);

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
        synchronized (mFileLock) {
            if (mBookmarksJsonFile == null) return;
            boolean pending;
            synchronized (this) {
                pending = prefs().getBoolean(PREF_BOOKMARKS_PENDING_SYNC, false);
            }
            if (pending || !mBookmarksJsonFile.exists()) {
                saveBookmarksToJsonFile();
            } else {
                reloadBookmarksFromJsonFile(false);
            }
        }
    }

    public List<BrowserDataStore.EntryItem> loadBookmarks() {
        return loadBookmarksInternal(true);
    }

    private List<BrowserDataStore.EntryItem> loadBookmarksInternal(boolean dispatchSave) {
        List<BrowserDataStore.EntryItem> list = new ArrayList<BrowserDataStore.EntryItem>();
        JSONArray initArrToFlush = null;
        long initRev = 0;
        synchronized (this) {
            SharedPreferences prefs = prefs();
            String raw = prefs.getString(PREF_BOOKMARKS, null);

            // First run initialization: raw is null when no bookmarks entry has ever been saved
            if (raw == null) {
                list.add(new BrowserDataStore.EntryItem("Google", "https://www.google.com"));
                list.add(new BrowserDataStore.EntryItem("GitHub", "https://github.com"));
                list.add(new BrowserDataStore.EntryItem("Bilibili", "https://www.bilibili.com"));
                list.add(new BrowserDataStore.EntryItem("知乎", "https://www.zhihu.com"));
                list.add(new BrowserDataStore.EntryItem("Hacker News", "https://news.ycombinator.com"));
                list.add(new BrowserDataStore.EntryItem("百度", "https://www.baidu.com"));
                initArrToFlush = encodeBookmarksArray(list);
                initRev = commitBookmarksToPrefsLocked(initArrToFlush, dispatchSave);
            } else {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) {
                        BrowserDataStore.EntryItem item = decodeBookmarkEntry(arr.optJSONObject(i), i);
                        if (item != null) list.add(item);
                    }
                } catch (Exception e) {
                    // Corrupt JSON blob: preserve untouched to allow recovery, do not overwrite with defaults
                    Log.w(TAG, "Bookmark store is unreadable; leaving stored data untouched", e);
                }
            }
        }
        if (dispatchSave && initArrToFlush != null && mBookmarksJsonFile != null) {
            dispatchJsonSave(initArrToFlush, initRev);
        }
        return list;
    }

    private static JSONArray encodeBookmarksArray(List<BrowserDataStore.EntryItem> list) {
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
        return arr;
    }

    private long commitBookmarksToPrefsLocked(JSONArray arr, boolean pendingSync) {
        long rev = mBookmarksRevision.incrementAndGet();
        prefs()
            .edit()
            .putString(PREF_BOOKMARKS, arr.toString())
            .putBoolean(PREF_BOOKMARKS_PENDING_SYNC, pendingSync)
            .apply();
        return rev;
    }

    private void dispatchJsonSave(final JSONArray snapshot, final long rev) {
        if (mBookmarksJsonFile == null) return;
        if (AcidAsync.isMainThread()) {
            AcidAsync.runSerialized(new Runnable() {
                @Override
                public void run() {
                    saveBookmarksToJsonFile(snapshot, rev);
                }
            });
        } else {
            saveBookmarksToJsonFile(snapshot, rev);
        }
    }

    public void saveBookmarks(List<BrowserDataStore.EntryItem> list) {
        saveBookmarks(list, true);
    }

    public void saveBookmarks(List<BrowserDataStore.EntryItem> list, boolean syncFile) {
        JSONArray arr = encodeBookmarksArray(list);
        final long rev;
        Runnable mutated;
        synchronized (this) {
            rev = commitBookmarksToPrefsLocked(arr, syncFile);
            mutated = mBookmarksMutatedListener;
        }
        if (syncFile && mBookmarksJsonFile != null) {
            dispatchJsonSave(arr, rev);
        }
        if (mutated != null) {
            dispatchCallback(mutated);
        }
    }

    public boolean saveBookmarksToJsonFile() {
        return saveBookmarksToJsonFile(null, 0);
    }

    public boolean saveBookmarksToJsonFile(JSONArray cachedArr) {
        return saveBookmarksToJsonFile(cachedArr, 0);
    }

    public boolean saveBookmarksToJsonFile(JSONArray cachedArr, long expectedRevision) {
        File file = mBookmarksJsonFile;
        if (file == null) return false;
        JSONArray arr = cachedArr;
        if (arr == null) {
            arr = encodeBookmarksArray(loadBookmarksInternal(false));
        }
        synchronized (mFileLock) {
            file = mBookmarksJsonFile;
            if (file == null) return false;
            if (expectedRevision > 0 && expectedRevision < mBookmarksRevision.get()) {
                return false;
            }
            try {
                mLastSelfSaveTime = System.currentTimeMillis();
                String formatted;
                try {
                    formatted = arr.toString(2);
                } catch (Exception e) {
                    formatted = arr.toString();
                }
                AcidIo.writeFileUtf8(file, formatted);
                mLastSyncedJson = formatted.trim();
                synchronized (this) {
                    if (expectedRevision <= 0 || expectedRevision == mBookmarksRevision.get()) {
                        prefs().edit().putBoolean(PREF_BOOKMARKS_PENDING_SYNC, false).apply();
                    }
                }
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Failed to save bookmarks to " + file + ": " + e.getMessage(), e);
                return false;
            }
        }
    }

    public boolean reloadBookmarksFromObserver() {
        return reloadBookmarksInternal(false, true);
    }

    public boolean reloadBookmarksFromJsonFile() {
        return reloadBookmarksInternal(false, false);
    }

    public boolean reloadBookmarksFromJsonFile(boolean forceOverwritePending) {
        return reloadBookmarksInternal(forceOverwritePending, false);
    }

    private boolean reloadBookmarksInternal(boolean forceOverwritePending, boolean checkObserverBaseline) {
        Runnable mutated = null;
        synchronized (mFileLock) {
            File file = mBookmarksJsonFile;
            if (file == null || !file.exists()) return false;
            if (!forceOverwritePending) {
                synchronized (this) {
                    if (prefs().getBoolean(PREF_BOOKMARKS_PENDING_SYNC, false)) {
                        return false;
                    }
                }
            }
            try {
                String jsonStr = AcidIo.readFileUtf8(file).trim();
                if (jsonStr.isEmpty()) return false;
                if (checkObserverBaseline && jsonStr.equals(mLastSyncedJson)) {
                    return false;
                }

                JSONArray arr = new JSONArray(jsonStr);
                List<BrowserDataStore.EntryItem> list = new ArrayList<BrowserDataStore.EntryItem>();
                for (int i = 0; i < arr.length(); i++) {
                    BrowserDataStore.EntryItem item = decodeBookmarkEntry(arr.optJSONObject(i), i);
                    if (item != null) list.add(item);
                }

                JSONArray cleanArr = encodeBookmarksArray(list);
                synchronized (this) {
                    commitBookmarksToPrefsLocked(cleanArr, false);
                    mutated = mBookmarksMutatedListener;
                }
                mLastSyncedJson = jsonStr;
            } catch (Exception e) {
                Log.w(TAG, "Failed to reload bookmarks from " + file + ": " + e.getMessage(), e);
                return false;
            }
        }
        if (mutated != null) {
            dispatchCallback(mutated);
        }
        dispatchCallback(new Runnable() {
            @Override
            public void run() {
                if (mBookmarksChangedListener != null) {
                    mBookmarksChangedListener.onBookmarksChanged();
                }
            }
        });
        return true;
    }

    public void addBookmark(String title, String url) {
        if (TextUtils.isEmpty(url)) return;
        JSONArray arr;
        long rev;
        Runnable mutated;
        synchronized (this) {
            List<BrowserDataStore.EntryItem> list = loadBookmarks();
            list.add(0, new BrowserDataStore.EntryItem(TextUtils.isEmpty(title) ? url : title, url));
            arr = encodeBookmarksArray(list);
            rev = commitBookmarksToPrefsLocked(arr, true);
            mutated = mBookmarksMutatedListener;
        }
        dispatchJsonSave(arr, rev);
        if (mutated != null) dispatchCallback(mutated);
    }

    public void removeBookmark(int index) {
        JSONArray arr = null;
        long rev = 0;
        Runnable mutated = null;
        synchronized (this) {
            List<BrowserDataStore.EntryItem> list = loadBookmarks();
            if (index >= 0 && index < list.size()) {
                list.remove(index);
                arr = encodeBookmarksArray(list);
                rev = commitBookmarksToPrefsLocked(arr, true);
                mutated = mBookmarksMutatedListener;
            }
        }
        if (arr != null) {
            dispatchJsonSave(arr, rev);
            if (mutated != null) dispatchCallback(mutated);
        }
    }

    public boolean updateBookmark(int index, String title, String url) {
        if (TextUtils.isEmpty(url)) return false;
        JSONArray arr;
        long rev;
        Runnable mutated;
        synchronized (this) {
            List<BrowserDataStore.EntryItem> list = loadBookmarks();
            if (index < 0 || index >= list.size()) return false;
            String trimmedUrl = url.trim();
            list.set(index, new BrowserDataStore.EntryItem(TextUtils.isEmpty(title) ? trimmedUrl : title.trim(), trimmedUrl));
            arr = encodeBookmarksArray(list);
            rev = commitBookmarksToPrefsLocked(arr, true);
            mutated = mBookmarksMutatedListener;
        }
        dispatchJsonSave(arr, rev);
        if (mutated != null) dispatchCallback(mutated);
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
