package com.acid.browser;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated persistent key-value storage engine for Tampermonkey / GreaseMonkey userscripts.
 *
 * <p>Each userscript maintains its own sandboxed key-value store identified by {@code scriptId}
 * (typically script's sanitized fileName). Values support primitive types, strings, and JSON objects.
 *
 * <p>Data is persisted inside {@code <workspace>/userscript_storage.json} using safe atomic writes.
 */
public class UserscriptStorage {

    private static final String TAG = "AcidUserscriptStorage";
    private static final String STORAGE_FILE = "userscript_storage.json";

    private final File mStorageFile;
    private final ConcurrentHashMap<String, JSONObject> mStores = new ConcurrentHashMap<String, JSONObject>();
    private final Object mLock = new Object();
    private boolean mLoaded = false;

    public UserscriptStorage(File workspaceDir) {
        mStorageFile = new File(workspaceDir, STORAGE_FILE);
        load();
    }

    public void load() {
        synchronized (mLock) {
            mStores.clear();
            if (!mStorageFile.exists()) {
                mLoaded = true;
                return;
            }
            try {
                String content = AcidIo.readFileUtf8OrEmpty(mStorageFile, 10 * 1024 * 1024);
                if (!content.isEmpty()) {
                    JSONObject root = new JSONObject(content);
                    Iterator<?> keys = root.keys();
                    while (keys.hasNext()) {
                        String k = (String) keys.next();
                        JSONObject store = root.optJSONObject(k);
                        if (store != null) {
                            mStores.put(k, store);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load userscript storage file", e);
            }
            mLoaded = true;
        }
    }

    public void save() {
        synchronized (mLock) {
            try {
                JSONObject root = new JSONObject();
                for (String k : mStores.keySet()) {
                    JSONObject s = mStores.get(k);
                    if (s != null) {
                        root.put(k, s);
                    }
                }
                AcidIo.writeFileUtf8Safe(mStorageFile, root.toString(2));
            } catch (Exception e) {
                Log.w(TAG, "Failed to persist userscript storage", e);
            }
        }
    }

    public void setValue(String scriptId, String key, Object value) {
        if (scriptId == null || key == null) return;
        synchronized (mLock) {
            JSONObject store = mStores.get(scriptId);
            if (store == null) {
                store = new JSONObject();
                mStores.put(scriptId, store);
            }
            try {
                store.put(key, value == null ? JSONObject.NULL : value);
                save();
            } catch (Exception e) {
                Log.w(TAG, "Failed to set value for script " + scriptId + ", key " + key, e);
            }
        }
    }

    public Object getValue(String scriptId, String key, Object defaultValue) {
        if (scriptId == null || key == null) return defaultValue;
        synchronized (mLock) {
            JSONObject store = mStores.get(scriptId);
            if (store == null || !store.has(key)) {
                return defaultValue;
            }
            Object val = store.opt(key);
            if (val == JSONObject.NULL) {
                return null;
            }
            return val;
        }
    }

    public boolean deleteValue(String scriptId, String key) {
        if (scriptId == null || key == null) return false;
        synchronized (mLock) {
            JSONObject store = mStores.get(scriptId);
            if (store != null && store.has(key)) {
                store.remove(key);
                save();
                return true;
            }
            return false;
        }
    }

    public List<String> listValues(String scriptId) {
        List<String> list = new ArrayList<String>();
        if (scriptId == null) return list;
        synchronized (mLock) {
            JSONObject store = mStores.get(scriptId);
            if (store != null) {
                Iterator<?> keys = store.keys();
                while (keys.hasNext()) {
                    list.add((String) keys.next());
                }
            }
        }
        return list;
    }

    public void clear(String scriptId) {
        if (scriptId == null) return;
        synchronized (mLock) {
            mStores.remove(scriptId);
            save();
        }
    }
}
