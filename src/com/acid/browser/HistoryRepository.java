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
 * Repository managing user browsing history, in-memory caching, SharedPreferences persistence,
 * bounded retention (up to 2000 entries), and two-way sync with workspace `history.json`.
 */
public class HistoryRepository {

    private static final String TAG = "AcidHistoryRepo";
    public static final String PREF_HISTORY = "history";
    public static final String PREF_HISTORY_PENDING_SYNC = "history_pending_json_sync";
    public static final int MAX_HISTORY_RETENTION_ENTRIES = 2000;
    public static final String PREF_NAME = BrowserDataStore.PREF_NAME;

    private final Context mContext;
    private final SharedPreferences mPrefsOverride;
    private volatile File mHistoryJsonFile;
    private final Object mFileLock = new Object();
    private BrowserDataStore.OnHistoryChangedListener mHistoryChangedListener;
    private BrowserDataStore.CallbackDispatcher mDispatcher;
    private volatile long mLastSelfSaveTime = 0;
    private volatile String mLastSyncedJson = null;
    private final AtomicLong mHistoryRevision = new AtomicLong(0);

    public HistoryRepository(Context context) {
        this(context, null, null);
    }

    public HistoryRepository(Context context, SharedPreferences prefsOverride, File historyJsonFile) {
        mContext = context != null ? context.getApplicationContext() : null;
        mPrefsOverride = prefsOverride;
        mHistoryJsonFile = historyJsonFile;
    }

    private SharedPreferences prefs() {
        if (mPrefsOverride != null) return mPrefsOverride;
        if (mContext != null) return mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        throw new IllegalStateException("Neither SharedPreferences override nor Context provided to HistoryRepository");
    }

    public synchronized void setJsonFile(File jsonFile) {
        mHistoryJsonFile = jsonFile;
    }

    public File getJsonFile() {
        return mHistoryJsonFile;
    }

    public synchronized void setOnHistoryChangedListener(BrowserDataStore.OnHistoryChangedListener listener) {
        mHistoryChangedListener = listener;
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
        if (mHistoryJsonFile == null) return;
        boolean pending = prefs().getBoolean(PREF_HISTORY_PENDING_SYNC, false);
        if (pending || !mHistoryJsonFile.exists()) {
            saveHistoryToJsonFile();
        } else {
            reloadHistoryFromJsonFile();
        }
    }

    public synchronized List<BrowserDataStore.EntryItem> loadHistory() {
        SharedPreferences prefs = prefs();
        String raw = prefs.getString(PREF_HISTORY, null);
        List<BrowserDataStore.EntryItem> list = new ArrayList<BrowserDataStore.EntryItem>();
        if (raw == null) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                BrowserDataStore.EntryItem item = decodeHistoryEntry(arr.optJSONObject(i), i);
                if (item != null) list.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "History store is unreadable; returning what could be recovered", e);
        }
        return list;
    }

    private void dispatchJsonSave(final JSONArray snapshot, final long rev) {
        if (mHistoryJsonFile == null) return;
        if (AcidAsync.isMainThread()) {
            AcidAsync.runSerialized(new Runnable() {
                @Override
                public void run() {
                    saveHistoryToJsonFile(snapshot, rev);
                }
            });
        } else {
            saveHistoryToJsonFile(snapshot, rev);
        }
    }

    public void clearHistory() {
        final long rev;
        synchronized (this) {
            rev = mHistoryRevision.incrementAndGet();
            prefs()
                .edit()
                .putString(PREF_HISTORY, "[]")
                .putBoolean(PREF_HISTORY_PENDING_SYNC, true)
                .apply();
        }
        dispatchJsonSave(new JSONArray(), rev);

        dispatchCallback(new Runnable() {
            @Override
            public void run() {
                if (mHistoryChangedListener != null) {
                    mHistoryChangedListener.onHistoryChanged();
                }
            }
        });
    }

    public boolean saveHistoryToJsonFile() {
        return saveHistoryToJsonFile(null, 0);
    }

    public boolean saveHistoryToJsonFile(JSONArray cachedArr) {
        return saveHistoryToJsonFile(cachedArr, 0);
    }

    public boolean saveHistoryToJsonFile(JSONArray cachedArr, long expectedRevision) {
        File file = mHistoryJsonFile;
        if (file == null) return false;
        JSONArray arr = cachedArr;
        if (arr == null) {
            List<BrowserDataStore.EntryItem> list = loadHistory();
            arr = new JSONArray();
            for (BrowserDataStore.EntryItem item : list) {
                if (item == null) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("title", item.title);
                    o.put("url", item.url);
                    o.put("time", item.time);
                    arr.put(o);
                } catch (Exception ignored) {}
            }
        }
        synchronized (mFileLock) {
            file = mHistoryJsonFile;
            if (file == null) return false;
            if (expectedRevision > 0 && expectedRevision < mHistoryRevision.get()) {
                // A newer history write or clearHistory() superseded this async snapshot
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
                if (expectedRevision <= 0 || expectedRevision == mHistoryRevision.get()) {
                    prefs().edit().putBoolean(PREF_HISTORY_PENDING_SYNC, false).apply();
                }
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Failed to save history to " + file, e);
                return false;
            }
        }
    }

    public boolean reloadHistoryFromObserver() {
        File file = mHistoryJsonFile;
        if (file == null || !file.exists()) return false;
        try {
            String trimmed = AcidIo.readFileUtf8(file).trim();
            if (trimmed.isEmpty() || trimmed.equals(mLastSyncedJson)) {
                return false;
            }
        } catch (Exception ignored) {}
        return reloadHistoryFromJsonFile();
    }

    public boolean reloadHistoryFromJsonFile() {
        synchronized (mFileLock) {
            File file = mHistoryJsonFile;
            if (file == null || !file.exists()) return false;
            try {
                String jsonStr = AcidIo.readFileUtf8(file).trim();
                if (jsonStr.isEmpty()) return false;

                JSONArray arr = new JSONArray(jsonStr);
                List<BrowserDataStore.EntryItem> list = new ArrayList<BrowserDataStore.EntryItem>();
                for (int i = 0; i < arr.length(); i++) {
                    BrowserDataStore.EntryItem item = decodeHistoryEntry(arr.optJSONObject(i), i);
                    if (item != null) list.add(item);
                }

                JSONArray next = new JSONArray();
                for (BrowserDataStore.EntryItem item : list) {
                    JSONObject o = new JSONObject();
                    o.put("title", item.title);
                    o.put("url", item.url);
                    o.put("time", item.time);
                    next.put(o);
                }
                synchronized (this) {
                    mHistoryRevision.incrementAndGet();
                    prefs()
                        .edit()
                        .putString(PREF_HISTORY, next.toString())
                        .putBoolean(PREF_HISTORY_PENDING_SYNC, false)
                        .apply();
                }
                mLastSyncedJson = jsonStr;
            } catch (Exception e) {
                Log.w(TAG, "Failed to reload history from " + file + ": " + e.getMessage(), e);
                return false;
            }
        }

        dispatchCallback(new Runnable() {
            @Override
            public void run() {
                if (mHistoryChangedListener != null) {
                    mHistoryChangedListener.onHistoryChanged();
                }
            }
        });
        return true;
    }

    public void recordHistory(String url, String title, boolean incognitoMode) {
        if (incognitoMode) return;
        if (url == null || url.isEmpty() || url.equals(BrowserController.HOME_URL)
            || url.contains("/acid_workspace/homepage/index.html")
            || "acid://home".equalsIgnoreCase(url) || "about:home".equalsIgnoreCase(url)) {
            return;
        }
        final JSONArray snapshot;
        final long rev;
        synchronized (this) {
            SharedPreferences prefs = prefs();
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

            // Bounded retention policy up to MAX_HISTORY_RETENTION_ENTRIES, skipping corrupt items and deduplicating
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length() && next.length() < MAX_HISTORY_RETENTION_ENTRIES; i++) {
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

            rev = mHistoryRevision.incrementAndGet();
            prefs
                .edit()
                .putString(PREF_HISTORY, next.toString())
                .putBoolean(PREF_HISTORY_PENDING_SYNC, true)
                .apply();
            snapshot = next;
        }

        dispatchJsonSave(snapshot, rev);

        dispatchCallback(new Runnable() {
            @Override
            public void run() {
                if (mHistoryChangedListener != null) {
                    mHistoryChangedListener.onHistoryChanged();
                }
            }
        });
    }

    private static BrowserDataStore.EntryItem decodeHistoryEntry(JSONObject o, int index) {
        if (o == null) {
            Log.w(TAG, "history[" + index + "] is not a JSON object; skipped");
            return null;
        }
        String url = o.optString("url", "");
        if (TextUtils.isEmpty(url)) {
            Log.w(TAG, "history[" + index + "] has no usable url; skipped");
            return null;
        }
        long time = o.optLong("time", 0);
        return new BrowserDataStore.EntryItem(o.optString("title", url), url, time);
    }
}
