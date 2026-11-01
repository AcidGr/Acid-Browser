package com.acid.browser;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages the embedded agent_bridge.js runtime and the userscript filesystem/injection.
 *
 * <p>Scripts are real userscripts: the {@code ==UserScript==} header is parsed and honoured,
 * so {@code @match} decides which sites a script runs on and {@code @run-at} decides when.
 * Before this the header was decorative — every script ran everywhere, at one fixed moment.
 *
 * <p>Write policy: every write reports success honestly. A failed write returns false and
 * logs, instead of being swallowed and reported as success to the caller.
 */
public class UserscriptManager {

    private static final String TAG = "AcidUserscripts";

    /**
     * Hard ceiling of 20 MB so complex web-app userscripts (like Immersive Translate ~5.6MB)
     * install smoothly, while guarding against Android Dalvik/ART OutOfMemory crashes.
     */
    private static final int MAX_SCRIPT_BYTES = 20 * 1024 * 1024; // 20 MB

    private final Context mContext;
    private final File mWorkspaceDir;
    private final File mUserscriptsDir;
    private final UserscriptStorage mStorage;
    private final UserscriptHttpHandler mHttpHandler;
    /** One bridge per WebView so HTTP callbacks return to the tab that asked. */
    private final java.util.Map<WebView, UserscriptNativeBridge> mBridges =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<WebView, UserscriptNativeBridge>());
    /** Per-process token; embedded only inside injected closures. */
    private final String mSecret = newSecret();
    private UserscriptNativeBridge.Host mHost;
    private final UserscriptDependencyResolver mDepResolver;
    private String mBridgeJs = "";

    /**
     * Parsed scripts, refreshed from disk when the directory changes. Guarded by itself
     * because injection happens on the UI thread while saves may arrive from the agent.
     */
    private final List<UserscriptMeta> mScripts = new ArrayList<UserscriptMeta>();
    private boolean mCacheLoaded = false;

    public UserscriptManager(Context context) {
        mContext = context.getApplicationContext();
        mWorkspaceDir = new File(mContext.getFilesDir(), "acid_workspace");
        if (!mWorkspaceDir.exists()) {
            mWorkspaceDir.mkdirs();
        }
        mUserscriptsDir = new File(mWorkspaceDir, "userscripts");
        if (!mUserscriptsDir.exists()) {
            mUserscriptsDir.mkdirs();
        }
        mStorage = new UserscriptStorage(mWorkspaceDir);
        mHttpHandler = new UserscriptHttpHandler();
        mDepResolver = new UserscriptDependencyResolver(mUserscriptsDir);
        loadBridgeAndDefaultUserscripts();
    }

    public File getWorkspaceDir() {
        return mWorkspaceDir;
    }

    public File getUserscriptsDir() {
        return mUserscriptsDir;
    }

    public UserscriptStorage getStorage() {
        return mStorage;
    }

    public UserscriptDependencyResolver getDependencyResolver() {
        return mDepResolver;
    }

    /** Hosts that get the {@code window.external.Tampermonkey} facade (same list as Via). */
    private static final String[] SCRIPT_SITES = {
        "greasyfork.org", "userscript.zone", "openuserjs.org", "sleazyfork.org"
    };

    public static boolean isScriptSite(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        int scheme = u.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int slash = u.indexOf('/', start);
        String hostPart = slash < 0 ? u.substring(start) : u.substring(start, slash);
        for (String site : SCRIPT_SITES) {
            if (hostPart.equals(site) || hostPart.endsWith("." + site)) return true;
        }
        return false;
    }

    /**
     * Installs the phase bootstrap (and the Tampermonkey facade on script sites) as early as
     * the WebView allows. Safe to call more than once per navigation; the JS is guarded.
     */
    public void injectBootstrap(WebView wv, String url) {
        if (wv == null || url == null || url.isEmpty()) return;
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("file://")) return;
        attachToWebView(wv);
        try {
            wv.evaluateJavascript(UserscriptShaper.bootstrap(mSecret), null);
            if (isScriptSite(url)) {
                wv.evaluateJavascript(UserscriptShaper.tampermonkeyFacade(mSecret), null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject bootstrap", e);
        }
    }

    /** Finds an installed, enabled script by {@code @name} + {@code @namespace}. */
    public UserscriptMeta findInstalled(String name, String namespace) {
        if (name == null || name.isEmpty()) return null;
        for (UserscriptMeta m : loadedScripts()) {
            if (!m.enabled) continue;
            if (!m.name.equals(name)) continue;
            if (namespace == null || namespace.isEmpty() || m.namespace.equals(namespace)) return m;
        }
        return null;
    }

    /** Runs every script scheduled for one bootstrap phase (1 start, 2 end, 4 idle). */
    public void injectPhase(WebView wv, String url, int phase) {
        String runAt = phase == 1 ? UserscriptMeta.RUN_AT_START
            : phase == 2 ? UserscriptMeta.RUN_AT_END
            : UserscriptMeta.RUN_AT_IDLE;
        injectRuntimeAndUserscripts(wv, url, runAt);
    }

    /** Browser-side actions (open tab, download, notify) a script may trigger. */
    public void setHost(UserscriptNativeBridge.Host host) {
        mHost = host;
        synchronized (mBridges) {
            for (UserscriptNativeBridge b : mBridges.values()) configureBridge(b);
        }
    }

    private static String newSecret() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private UserscriptNativeBridge.ScriptResolver resolver() {
        return new UserscriptNativeBridge.ScriptResolver() {
            @Override
            public UserscriptMeta findScript(String scriptId) {
                for (UserscriptMeta m : loadedScripts()) {
                    if (m.fileName.equals(scriptId)) return m;
                }
                return null;
            }
        };
    }

    private void configureBridge(UserscriptNativeBridge bridge) {
        bridge.configure(resolver(), mDepResolver, mHost, new UserscriptNativeBridge.ValueChangeSink() {
            @Override
            public void onValueChanged(UserscriptNativeBridge origin, String scriptId, String name,
                                       Object oldValue, Object newValue) {
                broadcastValueChange(origin, scriptId, name, oldValue, newValue);
            }
        });
    }

    /** Tells every tab's listeners about a write; the writing tab sees remote=false. */
    private void broadcastValueChange(final UserscriptNativeBridge origin, final String scriptId,
                                      final String name, final Object oldValue, final Object newValue) {
        final java.util.List<UserscriptNativeBridge> all;
        synchronized (mBridges) {
            all = new ArrayList<UserscriptNativeBridge>(mBridges.values());
        }
        final String oldJs = jsLiteral(oldValue);
        final String newJs = jsLiteral(newValue);
        AcidAsync.postToMain(new Runnable() {
            @Override
            public void run() {
                for (UserscriptNativeBridge b : all) {
                    WebView wv = b.getWebView();
                    if (wv == null) continue;
                    String js = "try{var f=window.__acid_gm_vc&&window.__acid_gm_vc["
                        + JSONObject.quote(scriptId) + "];if(f)f(" + JSONObject.quote(name) + ","
                        + oldJs + "," + newJs + "," + (b != origin) + ");}catch(e){}";
                    try {
                        wv.evaluateJavascript(js, null);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private static String jsLiteral(Object v) {
        if (v == null || v == JSONObject.NULL) return "undefined";
        if (v instanceof String) return JSONObject.quote((String) v);
        return String.valueOf(v);
    }

    public void attachToWebView(WebView wv) {
        if (wv == null) return;
        try {
            UserscriptNativeBridge bridge = mBridges.get(wv);
            if (bridge == null) {
                bridge = new UserscriptNativeBridge(mContext, mStorage, mHttpHandler, mSecret);
                configureBridge(bridge);
                mBridges.put(wv, bridge);
                bridge.attachWebView(wv);
                wv.addJavascriptInterface(bridge, UserscriptNativeBridge.JS_INTERFACE_NAME);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to attach UserscriptNativeBridge to WebView", e);
        }
    }

    private void loadBridgeAndDefaultUserscripts() {
        mBridgeJs = AcidIo.readAssetUtf8OrEmpty(mContext, "agent_bridge.js");
        if (mBridgeJs.isEmpty()) {
            Log.e(TAG, "agent_bridge.js asset is missing or empty; page automation will be unavailable");
        }

        File defaultScript = new File(mUserscriptsDir, "anti_app_hijack.user.js");
        if (!defaultScript.exists()) {
            String src = AcidIo.readAssetUtf8OrEmpty(mContext, "userscripts/anti_app_hijack.user.js");
            if (src.isEmpty()) {
                Log.w(TAG, "Bundled userscript asset missing; skipping default install");
            } else if (!AcidIo.writeFileUtf8Safe(defaultScript, src)) {
                Log.w(TAG, "Could not install default userscript to " + defaultScript.getAbsolutePath());
            }
        }
    }

    public String getBridgeJs() {
        return mBridgeJs;
    }

    // =========================================================================
    // Script discovery
    // =========================================================================

    /** Drops the parsed cache so the next injection re-reads the directory. */
    public void invalidateCache() {
        synchronized (mScripts) {
            mCacheLoaded = false;
        }
    }

    private List<UserscriptMeta> loadedScripts() {
        synchronized (mScripts) {
            if (!mCacheLoaded) {
                mScripts.clear();
                File[] files = mUserscriptsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile() || !f.getName().endsWith(".js")) continue;
                        String code = AcidIo.readFileUtf8OrEmpty(f, MAX_SCRIPT_BYTES);
                        if (code.isEmpty()) continue;
                        mScripts.add(UserscriptMeta.parse(f.getName(), code));
                    }
                }
                Collections.sort(mScripts, new Comparator<UserscriptMeta>() {
                    @Override
                    public int compare(UserscriptMeta a, UserscriptMeta b) {
                        return a.fileName.compareTo(b.fileName);
                    }
                });
                mCacheLoaded = true;
            }
            return new ArrayList<UserscriptMeta>(mScripts);
        }
    }

    /** Every parsed script, for the management sheet and the agent. */
    public List<UserscriptMeta> getScripts() {
        return loadedScripts();
    }

    // =========================================================================
    // Injection
    // =========================================================================

    /**
     * Injects the bridge plus every script scheduled for this phase.
     *
     * @param runAt one of the {@link UserscriptMeta#RUN_AT_START} / {@code END} / {@code IDLE}
     *              constants. Scripts declared for other phases are skipped.
     */
    public void injectRuntimeAndUserscripts(WebView wv, String url, String runAt) {
        if (wv == null) return;
        try {
            for (UserscriptMeta meta : loadedScripts()) {
                if (!meta.enabled) continue;
                if (!runAt.equals(meta.runAt)) continue;
                if (!meta.appliesTo(url)) continue;
                injectOne(wv, meta);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject userscripts at " + runAt, e);
        }
    }

    /**
     * Runs a single script on demand, ignoring its {@code @run-at} phase but still honouring
     * its {@code @match}. Used by the menu buttons and by the agent's preview action.
     */
    public boolean runScriptNow(WebView wv, UserscriptMeta meta) {
        if (wv == null || meta == null) return false;
        String url = null;
        try {
            url = wv.getUrl();
        } catch (Exception e) {
            Log.w(TAG, "Could not read the current URL before running " + meta.fileName, e);
        }
        if (!meta.appliesTo(url)) {
            Log.i(TAG, "Script " + meta.fileName + " does not apply to " + url);
            return false;
        }
        injectOne(wv, meta);
        return true;
    }

    /**
     * Wraps the script body in an IIFE with a per-file guard so repeated injections on the
     * same page do not run the body twice.
     *
     * <p>The body is passed through {@code evaluateJavascript} rather than appended as a
     * {@code <script>} element, which keeps working on pages with a strict CSP.
     */
    private void injectOne(final WebView wv, final UserscriptMeta meta) {
        if (wv == null || meta == null || meta.body == null || meta.body.trim().isEmpty()) return;
        attachToWebView(wv);

        List<String> cached = mDepResolver.loadRequiresCachedOnly(meta);
        if (cached != null) {
            evaluate(wv, meta, cached);
            return;
        }

        String urlBeforeFetch = null;
        try {
            urlBeforeFetch = wv.getUrl();
        } catch (Exception ignored) {}
        final String originUrl = urlBeforeFetch;

        // Some @require is not on disk yet: fetch off the UI thread, then inject.
        AcidAsync.run(new AcidAsync.Task<List<String>>() {
            @Override
            public List<String> execute() {
                return mDepResolver.loadRequiresForScript(meta);
            }
        }, new AcidAsync.Callback<List<String>>() {
            @Override
            public void onSuccess(List<String> requires) {
                if (!isPageStillValid(wv, meta, originUrl)) return;
                evaluate(wv, meta, requires);
            }

            @Override
            public void onError(Exception error) {
                Log.w(TAG, "Dependency fetch failed for " + meta.fileName, error);
                if (!isPageStillValid(wv, meta, originUrl)) return;
                evaluate(wv, meta, new ArrayList<String>());
            }
        });
    }

    private boolean isPageStillValid(WebView wv, UserscriptMeta meta, String originUrl) {
        if (wv == null) return false;
        String currentUrl = null;
        try {
            currentUrl = wv.getUrl();
        } catch (Exception ignored) {}
        if (currentUrl == null || currentUrl.isEmpty()) return false;
        if (!meta.appliesTo(currentUrl)) {
            Log.i(TAG, "Aborted async userscript injection for " + meta.fileName
                + ": page drifted from " + originUrl + " to " + currentUrl);
            return false;
        }
        return true;
    }

    private void evaluate(WebView wv, UserscriptMeta meta, List<String> requires) {
        String shapedJs = UserscriptShaper.shape(meta, requires, mSecret);
        try {
            wv.evaluateJavascript(shapedJs, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject " + meta.fileName, e);
        }
    }

    // =========================================================================
    // GM_registerMenuCommand surface
    // =========================================================================

    /** One command a script registered on the current page. */
    public static final class MenuCommand {
        public final String scriptId;
        public final String scriptName;
        public final String title;

        MenuCommand(String scriptId, String scriptName, String title) {
            this.scriptId = scriptId;
            this.scriptName = scriptName;
            this.title = title;
        }
    }

    public interface MenuCommandsCallback {
        void onCommands(List<MenuCommand> commands);
    }

    /** Asks the page which commands its scripts registered. Callback runs on the UI thread. */
    public void queryMenuCommands(WebView wv, final MenuCommandsCallback cb) {
        if (cb == null) return;
        if (wv == null) {
            cb.onCommands(new ArrayList<MenuCommand>());
            return;
        }
        String js = "(function(){try{var r={},m=window.__acid_gm_menus||{};"
            + "for(var k in m){r[k]=Object.keys(m[k]);}return JSON.stringify(r);}catch(e){return '{}';}})()";
        try {
            wv.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    cb.onCommands(parseMenuCommands(value));
                }
            });
        } catch (Exception e) {
            cb.onCommands(new ArrayList<MenuCommand>());
        }
    }

    private List<MenuCommand> parseMenuCommands(String raw) {
        List<MenuCommand> out = new ArrayList<MenuCommand>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return out;
        try {
            // evaluateJavascript returns the JSON string itself JSON-encoded.
            String json = raw;
            if (json.startsWith("\"")) {
                json = (String) new org.json.JSONTokener(json).nextValue();
            }
            JSONObject root = new JSONObject(json);
            java.util.Iterator<?> keys = root.keys();
            while (keys.hasNext()) {
                String sid = (String) keys.next();
                org.json.JSONArray names = root.optJSONArray(sid);
                if (names == null) continue;
                String label = sid;
                for (UserscriptMeta m : loadedScripts()) {
                    if (m.fileName.equals(sid)) {
                        label = m.name;
                        if (!m.enabled) label = null;
                        break;
                    }
                }
                if (label == null) continue;
                for (int i = 0; i < names.length(); i++) {
                    out.add(new MenuCommand(sid, label, names.optString(i)));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not parse menu commands: " + raw, e);
        }
        return out;
    }

    /** Invokes a registered command inside the page. */
    public void runMenuCommand(WebView wv, MenuCommand cmd) {
        if (wv == null || cmd == null) return;
        String js = "try{var f=window.__acid_gm_menus[" + JSONObject.quote(cmd.scriptId) + "]["
            + JSONObject.quote(cmd.title) + "];if(typeof f==='function')f();}catch(e){console.error(e);}";
        try {
            wv.evaluateJavascript(js, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to run menu command " + cmd.title, e);
        }
    }

    // =========================================================================
    // Management
    // =========================================================================

    /**
     * Installs a script after validating it.
     *
     * @return null on success, or a human-readable error explaining why it was rejected.
     */
    public String installUserscript(String fileName, String content) {
        if (content == null || content.trim().isEmpty()) {
            return "脚本内容为空";
        }
        if (content.length() > MAX_SCRIPT_BYTES) {
            return "脚本超过 " + (MAX_SCRIPT_BYTES / (1024 * 1024)) + " MB 上限";
        }

        UserscriptMeta meta = UserscriptMeta.parse(fileName, content);
        if (!meta.hasMetadata) {
            return "缺少 ==UserScript== 元数据块，无法确定生效范围";
        }
        if (meta.matches.isEmpty() && meta.includes.isEmpty()) {
            return "缺少 @match 或 @include，无法确定生效范围";
        }

        String safeName = safeFileName(fileName);
        File f = new File(mUserscriptsDir, safeName);
        if (!AcidIo.writeFileUtf8Safe(f, content)) {
            return "写入文件失败: " + f.getAbsolutePath();
        }
        mDepResolver.prefetchDependenciesAsync(meta, null);
        invalidateCache();
        Log.i(TAG, "Installed " + safeName + " (" + meta.describeScope() + ")");
        return null;
    }

    /** Outcome of checking one script against its {@code @updateURL}. */
    public static final class UpdateInfo {
        public final UserscriptMeta script;
        public final String remoteVersion;
        public final String remoteContent;
        public final boolean newer;

        UpdateInfo(UserscriptMeta s, String v, String c, boolean n) {
            script = s; remoteVersion = v; remoteContent = c; newer = n;
        }
    }

    /** URL a script should be re-fetched from, or empty when it declares none. */
    public static String updateUrlOf(UserscriptMeta meta) {
        if (meta == null) return "";
        if (!meta.updateUrl.isEmpty()) return meta.updateUrl;
        return meta.downloadUrl;
    }

    /** Numeric-aware version compare: true when {@code remote} is newer than {@code local}. */
    public static boolean isNewerVersion(String remote, String local) {
        if (remote == null || remote.trim().isEmpty()) return false;
        if (local == null || local.trim().isEmpty()) return true;
        String[] a = remote.trim().split("[.\\-+]");
        String[] b = local.trim().split("[.\\-+]");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? leadingInt(a[i]) : 0;
            int y = i < b.length ? leadingInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int leadingInt(String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Fetches a script's {@code @updateURL} and parses its version. Blocking: call off the UI
     * thread. Returns null when the script declares no update source or the fetch fails.
     */
    public UpdateInfo checkForUpdate(UserscriptMeta meta) {
        String url = updateUrlOf(meta);
        if (url.isEmpty()) return null;
        try {
            String content = AcidHttp.fetchText(url, 8000, 15000, MAX_SCRIPT_BYTES);
            if (content == null || content.trim().isEmpty()) return null;
            UserscriptMeta remote = UserscriptMeta.parse(meta.fileName, content);
            if (!remote.hasMetadata) return null;
            boolean newer = isNewerVersion(remote.version, meta.version);
            return new UpdateInfo(meta, remote.version, content, newer);
        } catch (Exception e) {
            Log.w(TAG, "Update check failed for " + meta.fileName, e);
            return null;
        }
    }

    /** Overwrites a script with new source, keeping the user's enabled state. */
    public String applyUpdate(UserscriptMeta meta, String content) {
        if (meta == null || content == null || content.trim().isEmpty()) return "更新内容为空";
        boolean wasEnabled = meta.enabled;
        UserscriptMeta fresh = UserscriptMeta.parse(meta.fileName, content);
        if (!fresh.hasMetadata) return "更新内容缺少元数据块";
        String err = installUserscript(meta.fileName, content);
        if (err != null) return err;
        if (!wasEnabled) setScriptEnabled(meta.fileName, false);
        invalidateCache();
        return null;
    }

    public boolean deleteUserscript(String name) {
        if (name == null) return false;
        File f = new File(mUserscriptsDir, name);
        if (f.exists() && f.isFile()) {
            boolean ok = f.delete();
            if (ok) invalidateCache();
            return ok;
        }
        return false;
    }

    public boolean setScriptEnabled(String fileName, boolean enabled) {
        if (fileName == null) return false;
        File f = new File(mUserscriptsDir, fileName);
        if (!f.exists()) return false;
        String content = AcidIo.readFileUtf8OrEmpty(f, MAX_SCRIPT_BYTES);
        if (content.isEmpty()) return false;

        String pattern = "(?m)^\\s*//+\\s*@acid-enabled\\s+.*$";
        String replacement = "// @acid-enabled  " + enabled;
        String newContent;
        if (Pattern.compile(pattern).matcher(content).find()) {
            newContent = content.replaceAll(pattern, replacement);
        } else {
            int end = content.indexOf("==/UserScript==");
            if (end >= 0) {
                int lineStart = content.lastIndexOf('\n', end);
                if (lineStart >= 0) {
                    newContent = content.substring(0, lineStart + 1) + replacement + "\n" + content.substring(lineStart + 1);
                } else {
                    newContent = replacement + "\n" + content;
                }
            } else {
                newContent = replacement + "\n" + content;
            }
        }
        boolean ok = AcidIo.writeFileUtf8Safe(f, newContent);
        if (ok) invalidateCache();
        return ok;
    }

    private String safeFileName(String name) {
        String base = name != null ? name.trim() : "";
        if (base.isEmpty()) base = "script";
        base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!base.endsWith(".js")) base += ".user.js";
        return base;
    }
}
