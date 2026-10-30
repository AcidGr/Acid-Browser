package com.acid.browser;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM in-memory {@link SharedPreferences} implementation for fast unit & regression tests.
 */
public final class InMemoryPrefs implements SharedPreferences {

    private final Map<String, Object> mData = new LinkedHashMap<String, Object>();

    @Override
    public synchronized Map<String, ?> getAll() {
        return new HashMap<String, Object>(mData);
    }

    @Override
    public synchronized String getString(String key, String defValue) {
        Object v = mData.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = mData.get(key);
        return v instanceof Set ? new HashSet<String>((Set<String>) v) : defValues;
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        Object v = mData.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defValue;
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        Object v = mData.get(key);
        return v instanceof Number ? ((Number) v).longValue() : defValue;
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        Object v = mData.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : defValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        Object v = mData.get(key);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : defValue;
    }

    @Override
    public synchronized boolean contains(String key) {
        return mData.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new MemEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    private final class MemEditor implements Editor {
        private final Map<String, Object> mStaged = new LinkedHashMap<String, Object>();
        private final Set<String> mRemoved = new HashSet<String>();
        private boolean mClear = false;

        @Override
        public Editor putString(String key, String value) {
            if (value == null) mRemoved.add(key);
            else mStaged.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            if (values == null) mRemoved.add(key);
            else mStaged.put(key, new HashSet<String>(values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            mStaged.put(key, Integer.valueOf(value));
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            mStaged.put(key, Long.valueOf(value));
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            mStaged.put(key, Float.valueOf(value));
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            mStaged.put(key, Boolean.valueOf(value));
            return this;
        }

        @Override
        public Editor remove(String key) {
            mRemoved.add(key);
            mStaged.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            mClear = true;
            mStaged.clear();
            mRemoved.clear();
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            synchronized (InMemoryPrefs.this) {
                if (mClear) {
                    mData.clear();
                }
                for (String k : mRemoved) {
                    mData.remove(k);
                }
                mData.putAll(mStaged);
            }
        }
    }
}
