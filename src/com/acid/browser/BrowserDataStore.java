package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.FileObserver;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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
    public static final String PREF_ADBLOCK_ENABLED = "adblock_enabled";
    public static final String PREF_ADBLOCK_BLOCKED_COUNT = "adblock_blocked_count";
    public static final String PREF_ADBLOCK_SUBSCRIPTION_URL = "adblock_sub_url";
    public static final String PREF_SEARCH_ENGINE = "search_engine";
    public static final String PREF_SEARCH_ENGINE_CUSTOM = "search_engine_custom";
    public static final String PREF_HOME_TYPE = "home_type";
    public static final String PREF_HOME_CUSTOM_URL = "home_custom_url";
    public static final String PREF_CLEAR_ON_EXIT = "clear_on_exit";
    public static final String PREF_WEB_DEBUGGING = "web_debugging";
    public static final String PREF_DO_NOT_TRACK = "do_not_track";
    public static final String PREF_BLOCK_IMAGES = "block_images";

    public static final int SEARCH_GOOGLE = 0;
    public static final int SEARCH_BAIDU = 1;
    public static final int SEARCH_BING = 2;
    public static final int SEARCH_DUCKDUCKGO = 3;
    public static final int SEARCH_CUSTOM = 4;

    public static final String[] SEARCH_ENGINE_NAMES = new String[] {
        "Google", "百度 (Baidu)", "必应 (Bing)", "DuckDuckGo", "自定义"
    };

    public static final String[] SEARCH_ENGINE_URLS = new String[] {
        "https://www.google.com/search?q=%s",
        "https://www.baidu.com/s?wd=%s",
        "https://www.bing.com/search?q=%s",
        "https://duckduckgo.com/?q=%s",
        ""
    };

    public static final int HOME_ACID = 0;
    public static final int HOME_BLANK = 1;
    public static final int HOME_CUSTOM = 2;

    public static final String[] HOME_TYPE_NAMES = new String[] {
        "Acid 默认首页", "空白页 (about:blank)", "自定义网址"
    };

    public static class EntryItem {
        public final String title;
        public final String url;

        public EntryItem(String title, String url) {
            this.title = title != null ? title : "";
            this.url = url != null ? url : "";
        }
    }

    public interface OnSettingsChangedListener {
        void onSettingsChanged();
    }

    private final Context mContext;
    private File mSettingsJsonFile = null;
    private OnSettingsChangedListener mSettingsChangedListener = null;
    private FileObserver mFileObserver = null;
    private volatile long mLastSelfSaveTime = 0;

    public BrowserDataStore(Context context) {
        mContext = context.getApplicationContext();
    }

    public void setWorkspaceDir(File workspaceDir) {
        if (workspaceDir != null) {
            mSettingsJsonFile = new File(workspaceDir, "settings.json");
            syncSettingsJsonFile();
            startWatchingSettingsFile();
        }
    }

    public void startWatchingSettingsFile() {
        if (mSettingsJsonFile == null) return;
        if (mFileObserver != null) {
            try { mFileObserver.stopWatching(); } catch (Exception e) {}
            mFileObserver = null;
        }
        try {
            File parent = mSettingsJsonFile.getParentFile();
            if (parent != null && parent.exists()) {
                mFileObserver = new FileObserver(parent.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path != null && path.endsWith("settings.json")) {
                            if (System.currentTimeMillis() - mLastSelfSaveTime < 800) {
                                return; // Ignore own writes
                            }
                            mLastSelfSaveTime = System.currentTimeMillis();
                            reloadFromJsonFile();
                        }
                    }
                };
                mFileObserver.startWatching();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start FileObserver on settings directory", e);
        }
    }

    public void setOnSettingsChangedListener(OnSettingsChangedListener l) {
        mSettingsChangedListener = l;
    }

    public File getSettingsJsonFile() {
        return mSettingsJsonFile;
    }

    public void syncSettingsJsonFile() {
        if (mSettingsJsonFile == null) return;
        if (mSettingsJsonFile.exists()) {
            reloadFromJsonFile();
        } else {
            saveToJsonFile();
        }
    }

    public static String searchEngineToString(int idx) {
        switch (idx) {
            case SEARCH_GOOGLE: return "google";
            case SEARCH_BAIDU: return "baidu";
            case SEARCH_BING: return "bing";
            case SEARCH_DUCKDUCKGO: return "duckduckgo";
            case SEARCH_CUSTOM: return "custom";
            default: return "google";
        }
    }

    public static int searchEngineFromString(String str) {
        if (str == null) return SEARCH_GOOGLE;
        str = str.trim().toLowerCase();
        if ("baidu".equals(str)) return SEARCH_BAIDU;
        if ("bing".equals(str)) return SEARCH_BING;
        if ("duckduckgo".equals(str) || "ddg".equals(str)) return SEARCH_DUCKDUCKGO;
        if ("custom".equals(str)) return SEARCH_CUSTOM;
        return SEARCH_GOOGLE;
    }

    public static String homeTypeToString(int type) {
        switch (type) {
            case HOME_ACID: return "acid";
            case HOME_BLANK: return "blank";
            case HOME_CUSTOM: return "custom";
            default: return "acid";
        }
    }

    public static int homeTypeFromString(String str) {
        if (str == null) return HOME_ACID;
        str = str.trim().toLowerCase();
        if ("blank".equals(str) || "about:blank".equals(str)) return HOME_BLANK;
        if ("custom".equals(str)) return HOME_CUSTOM;
        return HOME_ACID;
    }

    public JSONObject exportToJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("search_engine", searchEngineToString(getSearchEngineIndex()));
            o.put("search_engine_custom_url", getCustomSearchEngineUrl());
            o.put("home_type", homeTypeToString(getHomeType()));
            o.put("home_custom_url", getCustomHomeUrl());
            o.put("block_images", isBlockImagesEnabled());
            o.put("do_not_track", isDoNotTrackEnabled());
            o.put("web_debugging", isWebDebuggingEnabled());
            o.put("clear_on_exit", isClearOnExit());
            o.put("dark_mode", isDarkMode());
            o.put("adblock_enabled", isAdBlockEnabled());
        } catch (Exception e) {
            Log.w(TAG, "Failed to export settings to JSON", e);
        }
        return o;
    }

    public void saveToJsonFile() {
        if (mSettingsJsonFile == null) return;
        try {
            mLastSelfSaveTime = System.currentTimeMillis();
            JSONObject o = exportToJson();
            String jsonStr = o.toString(2);
            FileOutputStream fos = new FileOutputStream(mSettingsJsonFile);
            fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save settings.json", e);
        }
    }

    public boolean reloadFromJsonFile() {
        if (mSettingsJsonFile == null || !mSettingsJsonFile.exists()) return false;
        try {
            FileInputStream fis = new FileInputStream(mSettingsJsonFile);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            fis.close();
            String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(content);

            SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
            if (o.has("search_engine")) {
                Object se = o.get("search_engine");
                if (se instanceof Number) {
                    ed.putInt(PREF_SEARCH_ENGINE, ((Number) se).intValue());
                } else {
                    ed.putInt(PREF_SEARCH_ENGINE, searchEngineFromString(String.valueOf(se)));
                }
            }
            if (o.has("search_engine_custom_url")) {
                ed.putString(PREF_SEARCH_ENGINE_CUSTOM, o.getString("search_engine_custom_url"));
            }
            if (o.has("home_type")) {
                Object ht = o.get("home_type");
                if (ht instanceof Number) {
                    ed.putInt(PREF_HOME_TYPE, ((Number) ht).intValue());
                } else {
                    ed.putInt(PREF_HOME_TYPE, homeTypeFromString(String.valueOf(ht)));
                }
            }
            if (o.has("home_custom_url")) {
                ed.putString(PREF_HOME_CUSTOM_URL, o.getString("home_custom_url"));
            }
            if (o.has("block_images")) {
                ed.putBoolean(PREF_BLOCK_IMAGES, o.getBoolean("block_images"));
            }
            if (o.has("do_not_track")) {
                ed.putBoolean(PREF_DO_NOT_TRACK, o.getBoolean("do_not_track"));
            }
            if (o.has("web_debugging")) {
                ed.putBoolean(PREF_WEB_DEBUGGING, o.getBoolean("web_debugging"));
            }
            if (o.has("clear_on_exit")) {
                ed.putBoolean(PREF_CLEAR_ON_EXIT, o.getBoolean("clear_on_exit"));
            }
            if (o.has("dark_mode")) {
                ed.putBoolean(PREF_DARK_MODE, o.getBoolean("dark_mode"));
            }
            if (o.has("adblock_enabled")) {
                ed.putBoolean(PREF_ADBLOCK_ENABLED, o.getBoolean("adblock_enabled"));
            }
            ed.apply();

            if (mSettingsChangedListener != null) {
                mSettingsChangedListener.onSettingsChanged();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to reload settings from " + mSettingsJsonFile, e);
            return false;
        }
    }

    public boolean isDarkMode() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_DARK_MODE, false);
    }

    public void setDarkMode(boolean dark) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(PREF_DARK_MODE, dark);
        ed.apply();
        saveToJsonFile();
    }

    public boolean isAdBlockEnabled() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_ADBLOCK_ENABLED, true);
    }

    public void setAdBlockEnabled(boolean enabled) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(PREF_ADBLOCK_ENABLED, enabled);
        ed.apply();
        saveToJsonFile();
    }

    public int getAdBlockBlockedCount() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_ADBLOCK_BLOCKED_COUNT, 0);
    }

    public void setAdBlockBlockedCount(int count) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putInt(PREF_ADBLOCK_BLOCKED_COUNT, count);
        ed.apply();
    }

    public String getAdBlockSubscriptionUrl() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_ADBLOCK_SUBSCRIPTION_URL, "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-easylist.txt");
    }

    public void setAdBlockSubscriptionUrl(String url) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putString(PREF_ADBLOCK_SUBSCRIPTION_URL, url != null ? url.trim() : "");
        ed.apply();
    }

    public int getSearchEngineIndex() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_SEARCH_ENGINE, SEARCH_GOOGLE);
    }

    public void setSearchEngineIndex(int index) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putInt(PREF_SEARCH_ENGINE, index);
        ed.apply();
        saveToJsonFile();
    }

    public String getCustomSearchEngineUrl() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_SEARCH_ENGINE_CUSTOM, "");
    }

    public void setCustomSearchEngineUrl(String url) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putString(PREF_SEARCH_ENGINE_CUSTOM, url != null ? url.trim() : "");
        ed.apply();
        saveToJsonFile();
    }

    public String buildSearchUrl(String query) {
        if (query == null) query = "";
        int idx = getSearchEngineIndex();
        String template = null;
        if (idx >= 0 && idx < SEARCH_ENGINE_URLS.length - 1) {
            template = SEARCH_ENGINE_URLS[idx];
        } else if (idx == SEARCH_CUSTOM) {
            String custom = getCustomSearchEngineUrl();
            if (!TextUtils.isEmpty(custom)) {
                template = custom;
            }
        }
        if (TextUtils.isEmpty(template)) {
            template = SEARCH_ENGINE_URLS[SEARCH_GOOGLE];
        }

        try {
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            if (template.contains("%s")) {
                return template.replace("%s", encoded);
            } else {
                return template + encoded;
            }
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + query;
        }
    }

    public int getHomeType() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_HOME_TYPE, HOME_ACID);
    }

    public void setHomeType(int type) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putInt(PREF_HOME_TYPE, type);
        ed.apply();
        saveToJsonFile();
    }

    public String getCustomHomeUrl() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_HOME_CUSTOM_URL, "");
    }

    public void setCustomHomeUrl(String url) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putString(PREF_HOME_CUSTOM_URL, url != null ? url.trim() : "");
        ed.apply();
        saveToJsonFile();
    }

    public String getEffectiveHomeUrl() {
        int type = getHomeType();
        if (type == HOME_BLANK) {
            return "about:blank";
        }
        if (type == HOME_CUSTOM) {
            String custom = getCustomHomeUrl();
            if (!TextUtils.isEmpty(custom)) {
                custom = custom.trim();
                if (!custom.startsWith("http://") && !custom.startsWith("https://") && !custom.startsWith("file://") && !custom.startsWith("about:")) {
                    custom = "https://" + custom;
                }
                return custom;
            }
        }
        return BrowserController.HOME_URL;
    }

    public boolean isClearOnExit() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_CLEAR_ON_EXIT, false);
    }

    public void setClearOnExit(boolean val) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(PREF_CLEAR_ON_EXIT, val);
        ed.apply();
        saveToJsonFile();
    }

    public boolean isWebDebuggingEnabled() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_WEB_DEBUGGING, false);
    }

    public void setWebDebuggingEnabled(boolean enabled) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(PREF_WEB_DEBUGGING, enabled);
        ed.apply();
        saveToJsonFile();
    }

    public boolean isDoNotTrackEnabled() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_DO_NOT_TRACK, false);
    }

    public void setDoNotTrackEnabled(boolean enabled) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(PREF_DO_NOT_TRACK, enabled);
        ed.apply();
        saveToJsonFile();
    }

    public boolean isBlockImagesEnabled() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_BLOCK_IMAGES, false);
    }

    public void setBlockImagesEnabled(boolean enabled) {
        SharedPreferences.Editor ed = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        ed.putBoolean(PREF_BLOCK_IMAGES, enabled);
        ed.apply();
        saveToJsonFile();
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
