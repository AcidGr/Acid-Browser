package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Data repository for browser bookmarks, browsing history, and global user preferences.
 */
public class BrowserDataStore {

    public static final String PREF_NAME = "acid_browser_data";
    public static final String PREF_BOOKMARKS = "bookmarks";
    public static final String PREF_HISTORY = "history";
    public static final String PREF_DARK_MODE = "dark_mode";

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

    public List<EntryItem> loadBookmarks() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREF_BOOKMARKS, "[]");
        List<EntryItem> list = new ArrayList<EntryItem>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new EntryItem(o.getString("title"), o.getString("url")));
            }
        } catch (Exception ignored) {}

        if (list.isEmpty()) {
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
        for (EntryItem item : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", item.title);
                o.put("url", item.url);
                arr.put(o);
            } catch (Exception ignored) {}
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

    public List<EntryItem> loadHistory() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREF_HISTORY, "[]");
        List<EntryItem> list = new ArrayList<EntryItem>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new EntryItem(o.getString("title"), o.getString("url")));
            }
        } catch (Exception ignored) {}
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
        try {
            JSONArray arr = new JSONArray(raw);
            JSONArray next = new JSONArray();
            JSONObject cur = new JSONObject();
            cur.put("title", TextUtils.isEmpty(title) ? url : title);
            cur.put("url", url);
            cur.put("time", System.currentTimeMillis());
            next.put(cur);
            for (int i = 0; i < arr.length() && next.length() < 100; i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("url").equals(url)) {
                    next.put(o);
                }
            }
            prefs.edit().putString(PREF_HISTORY, next.toString()).apply();
        } catch (Exception ignored) {}
    }
}
