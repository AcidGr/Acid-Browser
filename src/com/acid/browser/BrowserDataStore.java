package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.FileObserver;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    public static final String PREF_MCP_ENABLED = "mcp_server_enabled";
    private static final String LEGACY_PREF_ACTIVITY = "acid_browser_prefs";

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
        public final long time;

        public EntryItem(String title, String url) {
            this(title, url, 0);
        }

        public EntryItem(String title, String url, long time) {
            this.title = title != null ? title : "";
            this.url = url != null ? url : "";
            this.time = time;
        }
    }

    public interface OnSettingsChangedListener {
        void onSettingsChanged();
    }

    public interface OnBookmarksChangedListener {
        void onBookmarksChanged();
    }

    public interface OnHistoryChangedListener {
        void onHistoryChanged();
    }

    public interface CallbackDispatcher {
        void dispatch(Runnable action);
    }

    private final Context mContext;
    private final SharedPreferences mPrimaryPrefsOverride;
    private final SharedPreferences mLegacyPrefsOverride;
    private final Map<AcidSettings.SettingKey<?>, CopyOnWriteArrayList<AcidSettings.KeyObserver<?>>> mKeyObservers =
        new ConcurrentHashMap<AcidSettings.SettingKey<?>, CopyOnWriteArrayList<AcidSettings.KeyObserver<?>>>();
    private final Map<AcidSettings.BoolKey, StateSource.BoolState> mBoolStateCache =
        new ConcurrentHashMap<AcidSettings.BoolKey, StateSource.BoolState>();
    private File mSettingsJsonFile = null;
    private File mBookmarksJsonFile = null;
    private File mHistoryJsonFile = null;
    private File mWorkspaceDir = null;
    private OnSettingsChangedListener mSettingsChangedListener = null;
    private OnBookmarksChangedListener mBookmarksChangedListener = null;
    private OnHistoryChangedListener mHistoryChangedListener = null;
    private CallbackDispatcher mDispatcher = null;
    private FileObserver mFileObserver = null;
    private volatile long mLastSelfSaveTime = 0;
    private volatile long mLastBookmarksSelfSaveTime = 0;
    private volatile long mLastHistorySelfSaveTime = 0;

    public BrowserDataStore(Context context) {
        mContext = context.getApplicationContext();
        mPrimaryPrefsOverride = null;
        mLegacyPrefsOverride = null;
    }

    BrowserDataStore(SharedPreferences primary, SharedPreferences legacy, File settingsJsonFile) {
        this(primary, legacy, settingsJsonFile, null, null);
    }

    BrowserDataStore(SharedPreferences primary, SharedPreferences legacy, File settingsJsonFile, File bookmarksJsonFile) {
        this(primary, legacy, settingsJsonFile, bookmarksJsonFile, null);
    }

    BrowserDataStore(SharedPreferences primary, SharedPreferences legacy, File settingsJsonFile, File bookmarksJsonFile, File historyJsonFile) {
        mContext = null;
        mPrimaryPrefsOverride = primary;
        mLegacyPrefsOverride = legacy;
        mSettingsJsonFile = settingsJsonFile;
        mBookmarksJsonFile = bookmarksJsonFile;
        mHistoryJsonFile = historyJsonFile;
    }

    SharedPreferences prefs() {
        return mPrimaryPrefsOverride != null
            ? mPrimaryPrefsOverride
            : mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    SharedPreferences legacyPrefs() {
        return mLegacyPrefsOverride != null
            ? mLegacyPrefsOverride
            : mContext.getSharedPreferences(LEGACY_PREF_ACTIVITY, Context.MODE_PRIVATE);
    }

    public void setWorkspaceDir(File workspaceDir) {
        if (workspaceDir != null) {
            mWorkspaceDir = workspaceDir;
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs();
            }
            mSettingsJsonFile = new File(workspaceDir, "settings.json");
            mBookmarksJsonFile = new File(workspaceDir, "bookmarks.json");
            mHistoryJsonFile = new File(workspaceDir, "history.json");
            syncSettingsJsonFile();
            syncBookmarksJsonFile();
            syncHistoryJsonFile();
            syncDefaultHomepage();
            startWatchingSettingsFile();
        }
    }

    public File getWorkspaceDir() {
        return mWorkspaceDir;
    }

    public void syncDefaultHomepage() {
        if (mWorkspaceDir == null || mContext == null) return;
        try {
            File homepageDir = new File(mWorkspaceDir, "homepage");
            if (!homepageDir.exists()) {
                homepageDir.mkdirs();
            }
            File targetIndex = new File(homepageDir, "index.html");
            if (!targetIndex.exists() || targetIndex.length() == 0) {
                InputStream is = mContext.getAssets().open("homepage/index.html");
                BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(targetIndex), StandardCharsets.UTF_8);
                osw.write(sb.toString());
                osw.flush();
                osw.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to sync default homepage template to workspace", e);
        }
    }

    public void startWatchingSettingsFile() {
        if (mSettingsJsonFile == null && mBookmarksJsonFile == null && mHistoryJsonFile == null) return;
        if (mFileObserver != null) {
            try { mFileObserver.stopWatching(); } catch (Exception e) {}
            mFileObserver = null;
        }
        try {
            File targetFile = mSettingsJsonFile != null ? mSettingsJsonFile
                : (mBookmarksJsonFile != null ? mBookmarksJsonFile : mHistoryJsonFile);
            File parent = targetFile.getParentFile();
            if (parent != null && parent.exists()) {
                mFileObserver = new FileObserver(parent.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path == null) return;
                        if (path.endsWith("settings.json")) {
                            if (System.currentTimeMillis() - mLastSelfSaveTime < 800) {
                                return; // Ignore own writes
                            }
                            mLastSelfSaveTime = System.currentTimeMillis();
                            reloadFromJsonFile();
                        } else if (path.endsWith("bookmarks.json")) {
                            if (System.currentTimeMillis() - mLastBookmarksSelfSaveTime < 800) {
                                return; // Ignore own writes
                            }
                            mLastBookmarksSelfSaveTime = System.currentTimeMillis();
                            reloadBookmarksFromJsonFile();
                        } else if (path.endsWith("history.json")) {
                            if (System.currentTimeMillis() - mLastHistorySelfSaveTime < 800) {
                                return; // Ignore own writes
                            }
                            mLastHistorySelfSaveTime = System.currentTimeMillis();
                            reloadHistoryFromJsonFile();
                        }
                    }
                };
                mFileObserver.startWatching();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not start FileObserver on workspace directory", e);
        }
    }

    public void setOnSettingsChangedListener(OnSettingsChangedListener l) {
        mSettingsChangedListener = l;
    }

    public void setOnBookmarksChangedListener(OnBookmarksChangedListener l) {
        mBookmarksChangedListener = l;
    }

    public void setOnHistoryChangedListener(OnHistoryChangedListener l) {
        mHistoryChangedListener = l;
    }

    public void setCallbackDispatcher(CallbackDispatcher dispatcher) {
        mDispatcher = dispatcher;
    }

    private void dispatchCallback(Runnable action) {
        if (mDispatcher != null) {
            mDispatcher.dispatch(action);
        } else {
            action.run();
        }
    }

    public File getSettingsJsonFile() {
        return mSettingsJsonFile;
    }

    public File getBookmarksJsonFile() {
        return mBookmarksJsonFile;
    }

    public File getHistoryJsonFile() {
        return mHistoryJsonFile;
    }

    public void syncSettingsJsonFile() {
        if (mSettingsJsonFile == null) return;
        if (mSettingsJsonFile.exists()) {
            reloadFromJsonFile();
        } else {
            saveToJsonFile();
        }
    }

    public void syncBookmarksJsonFile() {
        if (mBookmarksJsonFile == null) return;
        if (mBookmarksJsonFile.exists()) {
            reloadBookmarksFromJsonFile();
        } else {
            saveBookmarksToJsonFile();
        }
    }

    public void syncHistoryJsonFile() {
        if (mHistoryJsonFile == null) return;
        if (mHistoryJsonFile.exists()) {
            reloadHistoryFromJsonFile();
        } else {
            saveHistoryToJsonFile();
        }
    }

    // =========================================================================
    // Generic Declarative Settings Accessors & Reactive State Bindings
    // =========================================================================

    public <T> T get(AcidSettings.SettingKey<T> key) {
        return key.read(prefs(), legacyPrefs());
    }

    public <T> boolean set(final AcidSettings.SettingKey<T> key, T value) {
        final T sanitized = key.sanitize(value);
        T previous = get(key);
        boolean changed = (previous == null) ? (sanitized != null) : !previous.equals(sanitized);

        SharedPreferences.Editor ed = prefs().edit();
        key.write(ed, sanitized);
        ed.apply();

        if (key.exportToJson) {
            saveToJsonFile();
        }
        if (changed) {
            notifyKeyObservers(key, sanitized);
        }
        return changed;
    }

    public <T> void observe(AcidSettings.SettingKey<T> key, AcidSettings.KeyObserver<T> observer) {
        if (key == null || observer == null) return;
        CopyOnWriteArrayList<AcidSettings.KeyObserver<?>> list = mKeyObservers.get(key);
        if (list == null) {
            CopyOnWriteArrayList<AcidSettings.KeyObserver<?>> created =
                new CopyOnWriteArrayList<AcidSettings.KeyObserver<?>>();
            CopyOnWriteArrayList<AcidSettings.KeyObserver<?>> existing = mKeyObservers.putIfAbsent(key, created);
            list = existing != null ? existing : created;
        }
        if (!list.contains(observer)) {
            list.add(observer);
        }
    }

    public <T> void unobserve(AcidSettings.SettingKey<T> key, AcidSettings.KeyObserver<T> observer) {
        if (key == null || observer == null) return;
        CopyOnWriteArrayList<AcidSettings.KeyObserver<?>> list = mKeyObservers.get(key);
        if (list != null) {
            list.remove(observer);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void notifyKeyObservers(final AcidSettings.SettingKey<T> key, final T newVal) {
        final CopyOnWriteArrayList<AcidSettings.KeyObserver<?>> list = mKeyObservers.get(key);
        if (list == null || list.isEmpty()) return;
        dispatchCallback(new Runnable() {
            @Override
            public void run() {
                for (AcidSettings.KeyObserver<?> raw : list) {
                    ((AcidSettings.KeyObserver<T>) raw).onSettingChanged(newVal);
                }
            }
        });
    }

    /**
     * Returns a cached {@link StateSource.BoolState} view over a persisted {@link AcidSettings.BoolKey}
     * so UI switches and menu tiles bind directly to settings without manual listener glue.
     */
    public StateSource.BoolState boolState(final AcidSettings.BoolKey key) {
        StateSource.BoolState cached = mBoolStateCache.get(key);
        if (cached != null) return cached;

        final CopyOnWriteArrayList<StateSource.Observer> stateObservers =
            new CopyOnWriteArrayList<StateSource.Observer>();

        observe(key, new AcidSettings.KeyObserver<Boolean>() {
            @Override
            public void onSettingChanged(Boolean newValue) {
                boolean b = newValue != null && newValue.booleanValue();
                for (StateSource.Observer obs : stateObservers) {
                    obs.onChanged(b);
                }
            }
        });

        StateSource.BoolState created = new StateSource.BoolState() {
            @Override
            public boolean get() {
                return BrowserDataStore.this.get(key).booleanValue();
            }

            @Override
            public void set(boolean value) {
                BrowserDataStore.this.set(key, Boolean.valueOf(value));
            }

            @Override
            public void toggle() {
                set(!get());
            }

            @Override
            public void observe(StateSource.Observer observer) {
                if (observer != null && !stateObservers.contains(observer)) {
                    stateObservers.add(observer);
                }
            }

            @Override
            public void unobserve(StateSource.Observer observer) {
                if (observer != null) {
                    stateObservers.remove(observer);
                }
            }
        };
        StateSource.BoolState race = mBoolStateCache.putIfAbsent(key, created);
        return race != null ? race : created;
    }

    public static String searchEngineToString(int idx) {
        return AcidSettings.SEARCH_ENGINE.indexToCode(idx);
    }

    public static int searchEngineFromString(String str) {
        return AcidSettings.SEARCH_ENGINE.codeToIndex(str);
    }

    public static String homeTypeToString(int type) {
        return AcidSettings.HOME_TYPE.indexToCode(type);
    }

    public static int homeTypeFromString(String str) {
        return AcidSettings.HOME_TYPE.codeToIndex(str);
    }

    @SuppressWarnings("unchecked")
    private <T> void exportSingleKey(JSONObject out, AcidSettings.SettingKey<T> key) throws Exception {
        key.writeToJson(out, get(key));
    }

    public JSONObject exportToJson() {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < AcidSettings.ALL_KEYS.size(); i++) {
                AcidSettings.SettingKey<?> k = AcidSettings.ALL_KEYS.get(i);
                if (k.exportToJson) {
                    exportSingleKey(o, k);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to export settings to JSON", e);
        }
        return o;
    }

    public void saveToJsonFile() {
        if (mSettingsJsonFile == null) return;
        try {
            File parent = mSettingsJsonFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
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

    private static final class ChangedKeyEntry<T> {
        final AcidSettings.SettingKey<T> key;
        final T newValue;

        ChangedKeyEntry(AcidSettings.SettingKey<T> key, T newValue) {
            this.key = key;
            this.newValue = newValue;
        }

        void notifyOn(BrowserDataStore store) {
            store.notifyKeyObservers(key, newValue);
        }
    }

    private <T> ChangedKeyEntry<T> stageKeyFromJson(
        JSONObject json,
        SharedPreferences.Editor ed,
        AcidSettings.SettingKey<T> key
    ) throws Exception {
        if (!key.exportToJson || !json.has(key.jsonKey)) return null;
        T oldVal = get(key);
        T nextVal = key.sanitize(key.parseFromJson(json.get(key.jsonKey)));
        key.write(ed, nextVal);
        boolean changed = (oldVal == null) ? (nextVal != null) : !oldVal.equals(nextVal);
        return changed ? new ChangedKeyEntry<T>(key, nextVal) : null;
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

            SharedPreferences.Editor ed = prefs().edit();
            final List<ChangedKeyEntry<?>> changedEntries = new ArrayList<ChangedKeyEntry<?>>();
            for (int i = 0; i < AcidSettings.ALL_KEYS.size(); i++) {
                ChangedKeyEntry<?> diff = stageKeyFromJson(o, ed, AcidSettings.ALL_KEYS.get(i));
                if (diff != null) {
                    changedEntries.add(diff);
                }
            }
            ed.apply();

            for (int i = 0; i < changedEntries.size(); i++) {
                changedEntries.get(i).notifyOn(this);
            }
            if (mSettingsChangedListener != null) {
                dispatchCallback(new Runnable() {
                    @Override
                    public void run() {
                        if (mSettingsChangedListener != null) {
                            mSettingsChangedListener.onSettingsChanged();
                        }
                    }
                });
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to reload settings from " + mSettingsJsonFile, e);
            return false;
        }
    }

    // Convenience typed delegates (backed by AcidSettings registry)
    public boolean isDarkMode() { return get(AcidSettings.DARK_MODE).booleanValue(); }
    public void setDarkMode(boolean dark) { set(AcidSettings.DARK_MODE, Boolean.valueOf(dark)); }

    public boolean isFullscreenEnabled() { return get(AcidSettings.FULLSCREEN_ENABLED).booleanValue(); }
    public void setFullscreenEnabled(boolean enabled) { set(AcidSettings.FULLSCREEN_ENABLED, Boolean.valueOf(enabled)); }

    public boolean isAdBlockEnabled() { return get(AcidSettings.ADBLOCK_ENABLED).booleanValue(); }
    public void setAdBlockEnabled(boolean enabled) { set(AcidSettings.ADBLOCK_ENABLED, Boolean.valueOf(enabled)); }

    public int getAdBlockBlockedCount() { return get(AcidSettings.ADBLOCK_BLOCKED_COUNT).intValue(); }
    public void setAdBlockBlockedCount(int count) { set(AcidSettings.ADBLOCK_BLOCKED_COUNT, Integer.valueOf(count)); }

    public String getAdBlockSubscriptionUrl() { return get(AcidSettings.ADBLOCK_SUBSCRIPTION_URL); }
    public void setAdBlockSubscriptionUrl(String url) { set(AcidSettings.ADBLOCK_SUBSCRIPTION_URL, url); }

    public int getSearchEngineIndex() { return get(AcidSettings.SEARCH_ENGINE).intValue(); }
    public void setSearchEngineIndex(int index) { set(AcidSettings.SEARCH_ENGINE, Integer.valueOf(index)); }

    public String getCustomSearchEngineUrl() { return get(AcidSettings.SEARCH_ENGINE_CUSTOM_URL); }
    public void setCustomSearchEngineUrl(String url) { set(AcidSettings.SEARCH_ENGINE_CUSTOM_URL, url); }

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

    public int getHomeType() { return get(AcidSettings.HOME_TYPE).intValue(); }
    public void setHomeType(int type) { set(AcidSettings.HOME_TYPE, Integer.valueOf(type)); }

    public int getDownloaderEngine() { return get(AcidSettings.DOWNLOADER_ENGINE).intValue(); }
    public void setDownloaderEngine(int engine) { set(AcidSettings.DOWNLOADER_ENGINE, Integer.valueOf(engine)); }

    public String getCustomHomeUrl() { return get(AcidSettings.HOME_CUSTOM_URL); }
    public void setCustomHomeUrl(String url) { set(AcidSettings.HOME_CUSTOM_URL, url); }

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
        if (mWorkspaceDir != null) {
            File customHome = new File(new File(mWorkspaceDir, "homepage"), "index.html");
            if (customHome.exists() && customHome.isFile() && customHome.length() > 0) {
                return "file://" + customHome.getAbsolutePath();
            }
        }
        return BrowserController.HOME_URL;
    }

    public boolean isClearOnExit() { return get(AcidSettings.CLEAR_ON_EXIT).booleanValue(); }
    public void setClearOnExit(boolean val) { set(AcidSettings.CLEAR_ON_EXIT, Boolean.valueOf(val)); }

    public boolean isWebDebuggingEnabled() { return get(AcidSettings.WEB_DEBUGGING).booleanValue(); }
    public void setWebDebuggingEnabled(boolean enabled) { set(AcidSettings.WEB_DEBUGGING, Boolean.valueOf(enabled)); }

    public boolean isDoNotTrackEnabled() { return get(AcidSettings.DO_NOT_TRACK).booleanValue(); }
    public void setDoNotTrackEnabled(boolean enabled) { set(AcidSettings.DO_NOT_TRACK, Boolean.valueOf(enabled)); }

    public boolean isBlockImagesEnabled() { return get(AcidSettings.BLOCK_IMAGES).booleanValue(); }
    public void setBlockImagesEnabled(boolean enabled) { set(AcidSettings.BLOCK_IMAGES, Boolean.valueOf(enabled)); }

    public boolean isMcpServerEnabled() { return get(AcidSettings.MCP_ENABLED).booleanValue(); }
    public void setMcpServerEnabled(boolean enabled) { set(AcidSettings.MCP_ENABLED, Boolean.valueOf(enabled)); }

    public boolean isMcpAuthEnabled() { return get(AcidSettings.MCP_AUTH_ENABLED).booleanValue(); }
    public void setMcpAuthEnabled(boolean enabled) { set(AcidSettings.MCP_AUTH_ENABLED, Boolean.valueOf(enabled)); }

    public boolean isMcpLanEnabled() { return get(AcidSettings.MCP_LAN_ENABLED).booleanValue(); }
    public void setMcpLanEnabled(boolean enabled) { set(AcidSettings.MCP_LAN_ENABLED, Boolean.valueOf(enabled)); }

    public synchronized String getOrCreateMcpToken() {
        String token = get(AcidSettings.MCP_AUTH_TOKEN);
        if (token == null || token.trim().isEmpty()) {
            token = generateNewMcpToken();
        }
        return token;
    }

    public synchronized String generateNewMcpToken() {
        String token = java.util.UUID.randomUUID().toString().replace("-", "")
            + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        set(AcidSettings.MCP_AUTH_TOKEN, token);
        return token;
    }

    public void setMcpToken(String token) {
        set(AcidSettings.MCP_AUTH_TOKEN, token != null ? token.trim() : "");
    }

    // =========================================================================
    // Workspace & Browsing Data Cleanup Service
    // =========================================================================

    private static void deleteDirRecursively(File dir) {
        if (dir == null || !dir.exists()) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        dir.delete();
    }

    /**
     * Removes temporary workspace files while strictly preserving user configuration,
     * userscripts, adblock rules, and bundled skills.
     */
    public int clearWorkspaceTempFiles(File workspaceDir) {
        if (workspaceDir == null || !workspaceDir.exists()) return 0;
        File[] files = workspaceDir.listFiles();
        if (files == null) return 0;

        int deletedCount = 0;
        for (File f : files) {
            String name = f.getName();
            if ("settings.json".equals(name) ||
                "bookmarks.json".equals(name) ||
                "history.json".equals(name) ||
                "homepage".equals(name) ||
                "userscripts".equals(name) ||
                "adblock".equals(name) ||
                "skills".equals(name)) {
                continue;
            }
            if (f.isDirectory()) {
                deleteDirRecursively(f);
                deletedCount++;
            } else if (f.delete()) {
                deletedCount++;
            }
        }
        return deletedCount;
    }

    /**
     * Clears selected browser persistence categories and returns the number of categories purged.
     */
    public int clearBrowsingData(
        BrowserController controller,
        boolean clearCache,
        boolean clearCookies,
        boolean clearHistory,
        boolean clearStorage,
        boolean clearWorkspace
    ) {
        int count = 0;
        if (clearCache) {
            if (controller != null) {
                controller.clearAllCache();
            }
            try {
                deleteDirRecursively(mContext.getCacheDir());
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    File codeCache = mContext.getCodeCacheDir();
                    if (codeCache != null) deleteDirRecursively(codeCache);
                }
                android.webkit.WebViewDatabase.getInstance(mContext).clearFormData();
                android.webkit.WebViewDatabase.getInstance(mContext).clearHttpAuthUsernamePassword();
            } catch (Exception e) {
                Log.w(TAG, "Failed to clear disk cache", e);
            }
            count++;
        }
        if (clearCookies) {
            try {
                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                android.webkit.CookieManager.getInstance().flush();
                count++;
            } catch (Exception e) {
                Log.w(TAG, "Failed to clear cookies", e);
            }
        }
        if (clearHistory) {
            clearHistory();
            if (controller != null) {
                controller.clearAllHistory();
            }
            count++;
        }
        if (clearStorage) {
            try {
                android.webkit.WebStorage.getInstance().deleteAllData();
                android.webkit.GeolocationPermissions.getInstance().clearAll();
                count++;
            } catch (Exception e) {
                Log.w(TAG, "Failed to clear web storage", e);
            }
        }
        if (clearWorkspace && controller != null) {
            clearWorkspaceTempFiles(controller.getWorkspaceDir());
            count++;
        }
        return count;
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
        long time = o.optLong("time", 0);
        return new EntryItem(o.optString("title", url), url, time);
    }

    public List<EntryItem> loadBookmarks() {
        SharedPreferences prefs = prefs();
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
            list.add(new EntryItem("Google", "https://www.google.com"));
            list.add(new EntryItem("GitHub", "https://github.com"));
            list.add(new EntryItem("Bilibili", "https://www.bilibili.com"));
            list.add(new EntryItem("知乎", "https://www.zhihu.com"));
            list.add(new EntryItem("Hacker News", "https://news.ycombinator.com"));
            list.add(new EntryItem("百度", "https://www.baidu.com"));
            saveBookmarks(list);
        }
        return list;
    }

    public void saveBookmarks(List<EntryItem> list) {
        saveBookmarks(list, true);
    }

    public synchronized void saveBookmarks(List<EntryItem> list, boolean syncFile) {
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
        prefs()
            .edit()
            .putString(PREF_BOOKMARKS, arr.toString())
            .apply();

        if (syncFile && mBookmarksJsonFile != null) {
            saveBookmarksToJsonFile();
        }
    }

    public synchronized boolean saveBookmarksToJsonFile() {
        if (mBookmarksJsonFile == null) return false;
        try {
            List<EntryItem> list = loadBookmarks();
            JSONArray arr = new JSONArray();
            for (EntryItem item : list) {
                if (item == null) continue;
                JSONObject o = new JSONObject();
                o.put("title", item.title);
                o.put("url", item.url);
                arr.put(o);
            }
            File parent = mBookmarksJsonFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            mLastBookmarksSelfSaveTime = System.currentTimeMillis();
            FileOutputStream fos = new FileOutputStream(mBookmarksJsonFile);
            byte[] bytes;
            try {
                bytes = arr.toString(2).getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                bytes = arr.toString().getBytes(StandardCharsets.UTF_8);
            }
            fos.write(bytes);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to save bookmarks to " + mBookmarksJsonFile, e);
            return false;
        }
    }

    public synchronized boolean reloadBookmarksFromJsonFile() {
        if (mBookmarksJsonFile == null || !mBookmarksJsonFile.exists()) return false;
        try {
            FileInputStream fis = new FileInputStream(mBookmarksJsonFile);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            fis.close();
            String jsonStr = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (jsonStr.isEmpty()) return false;

            JSONArray arr = new JSONArray(jsonStr);
            List<EntryItem> list = new ArrayList<EntryItem>();
            for (int i = 0; i < arr.length(); i++) {
                EntryItem item = decodeEntry(arr.optJSONObject(i), i, "bookmark");
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
        SharedPreferences prefs = prefs();
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
        prefs()
            .edit()
            .putString(PREF_HISTORY, "[]")
            .apply();

        if (mHistoryJsonFile != null) {
            saveHistoryToJsonFile();
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
        if (mHistoryJsonFile == null) return false;
        try {
            List<EntryItem> list = loadHistory();
            JSONArray arr = new JSONArray();
            for (EntryItem item : list) {
                if (item == null) continue;
                JSONObject o = new JSONObject();
                o.put("title", item.title);
                o.put("url", item.url);
                o.put("time", item.time);
                arr.put(o);
            }
            File parent = mHistoryJsonFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            mLastHistorySelfSaveTime = System.currentTimeMillis();
            FileOutputStream fos = new FileOutputStream(mHistoryJsonFile);
            byte[] bytes;
            try {
                bytes = arr.toString(2).getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                bytes = arr.toString().getBytes(StandardCharsets.UTF_8);
            }
            fos.write(bytes);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to save history to " + mHistoryJsonFile, e);
            return false;
        }
    }

    public synchronized boolean reloadHistoryFromJsonFile() {
        if (mHistoryJsonFile == null || !mHistoryJsonFile.exists()) return false;
        try {
            FileInputStream fis = new FileInputStream(mHistoryJsonFile);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            fis.close();
            String jsonStr = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (jsonStr.isEmpty()) return false;

            JSONArray arr = new JSONArray(jsonStr);
            List<EntryItem> list = new ArrayList<EntryItem>();
            for (int i = 0; i < arr.length(); i++) {
                EntryItem item = decodeEntry(arr.optJSONObject(i), i, "history");
                if (item != null) list.add(item);
            }

            JSONArray next = new JSONArray();
            for (EntryItem item : list) {
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

    public void recordHistory(String url, String title, boolean incognitoMode) {
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

        // Full unconstrained history retention, skipping corrupt items
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
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
            saveHistoryToJsonFile();
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

    public List<BrowserController.DownloadItem> loadDownloads() {
        SharedPreferences prefs = prefs();
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
        prefs()
            .edit()
            .putString(PREF_DOWNLOADS, arr.toString())
            .apply();
    }

    public void clearDownloads() {
        prefs()
            .edit()
            .putString(PREF_DOWNLOADS, "[]")
            .apply();
    }
}
