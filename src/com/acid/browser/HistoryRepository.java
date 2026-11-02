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
 * Repository managing user browsing history, in-memory caching, SharedPreferences persistence,
 * bounded retention (up to 2000 entries), and two-way sync with workspace `history.json`.
 */
public class HistoryRepository {

    private static final String TAG = "AcidHistoryRepo";
    public static final String PREF_HISTORY = "history";
    public static final int MAX_HISTORY_RETENTION_ENTRIES = 2000;
    public static final String PREF_NAME = BrowserDataStore.PREF_NAME;

    private final Context mContext;
    private final SharedPreferences mPrefsOverride;
    private File mHistoryJsonFile;
    private BrowserDataStore.OnHistoryChangedListener mHistoryChangedListener;
    private BrowserDataStore.CallbackDispatcher mDispatcher;
    private volatile long mLastSelfSaveTime = 0;

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
        if (mHistoryJsonFile.exists()) {
            reloadHistoryFromJsonFile();
        } else {
            saveHistoryToJsonFile();
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

    public synchronized void clearHistory() {
        prefs()
            .edit()
            .putString(PREF_HISTORY, "[]")
            .apply();

        if (mHistoryJsonFile != null) {
            saveHistoryToJsonFile(new JSONArray());
        }

        dispatchCallback(new Runnable() {
            @Override
            public void run() {
                if (mHistoryChangedListener != null) {
                    mHistoryChangedListener.onHistoryChanged();
                }
            }
        });
    }

    public synchronized boolean saveHistoryToJsonFile() {
        return saveHistoryToJsonFile(null);
    }

    public synchronized boolean saveHistoryToJsonFile(JSONArray cachedArr) {
        if (mHistoryJsonFile == null) return false;
        try {
            JSONArray arr = cachedArr;
            if (arr == null) {
                List<BrowserDataStore.EntryItem> list = loadHistory();
                arr = new JSONArray();
                for (BrowserDataStore.EntryItem item : list) {
                    if (item == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("title", item.title);
                    o.put("url", item.url);
                    o.put("time", item.time);
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
            AcidIo.writeFileUtf8(mHistoryJsonFile, formatted);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to save history to " + mHistoryJsonFile, e);
            return false;
        }
    }

    public synchronized boolean reloadHistoryFromJsonFile() {
        if (mHistoryJsonFile == null || !mHistoryJsonFile.exists()) return false;
        try {
            String jsonStr = AcidIo.readFileUtf8(mHistoryJsonFile).trim();
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
            prefs().edit().putString(PREF_HISTORY, next.toString()).apply();

            dispatchCallback(new Runnable() {
                @Override
                public void run() {
                    if (mHistoryChangedListener != null) {
                        mHistoryChangedListener.onHistoryChanged();
                    }
                }
            });
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to reload history from " + mHistoryJsonFile + ": " + e.getMessage(), e);
            return false;
        }
    }

    public synchronized void recordHistory(String url, String title, boolean incognitoMode) {
        if (incognitoMode) return;
        if (url == null || url.isEmpty() || url.equals(BrowserController.HOME_URL)
            || url.contains("/acid_workspace/homepage/index.html")
            || "acid://home".equalsIgnoreCase(url) || "about:home".equalsIgnoreCase(url)) {
            return;
        }
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

        prefs.edit().putString(PREF_HISTORY, next.toString()).apply();

        if (mHistoryJsonFile != null) {
            saveHistoryToJsonFile(next);
        }

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
