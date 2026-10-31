package com.acid.browser;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        loadBridgeAndDefaultUserscripts();
    }

    public File getWorkspaceDir() {
        return mWorkspaceDir;
    }

    public File getUserscriptsDir() {
        return mUserscriptsDir;
    }

    private void loadBridgeAndDefaultUserscripts() {
        mBridgeJs = readAssetText("agent_bridge.js");
        if (mBridgeJs.isEmpty()) {
            Log.e(TAG, "agent_bridge.js asset is missing or empty; page automation will be unavailable");
        }

        File defaultScript = new File(mUserscriptsDir, "anti_app_hijack.user.js");
        if (!defaultScript.exists()) {
            String src = readAssetText("userscripts/anti_app_hijack.user.js");
            if (src.isEmpty()) {
                Log.w(TAG, "Bundled userscript asset missing; skipping default install");
            } else if (!writeFileText(defaultScript, src)) {
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
                        String code = readFileText(f);
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

    /** Convenience overload for callers that only have the WebView. */
    public void injectRuntimeAndUserscripts(WebView wv) {
        String url = null;
        try {
            url = wv != null ? wv.getUrl() : null;
        } catch (Exception e) {
            Log.w(TAG, "Could not read the current URL before injection", e);
        }
        injectRuntimeAndUserscripts(wv, url, UserscriptMeta.RUN_AT_IDLE);
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
    private void injectOne(WebView wv, UserscriptMeta meta) {
        String code = meta.body;
        if (code == null || code.trim().isEmpty()) return;

        String guardKey = JSONObject.quote(meta.fileName);
        String injectPlugin = "(function(){\n" +
            "try {\n" +
            "  window.__acidInjectedUserscripts = window.__acidInjectedUserscripts || {};\n" +
            "  if (!window.__acidInjectedUserscripts[" + guardKey + "]) {\n" +
            "    window.__acidInjectedUserscripts[" + guardKey + "] = true;\n" +
            code + "\n" +
            "  }\n" +
            "} catch(e) { console.error('[AcidUserscriptError]', e); }\n" +
            "})();";
        try {
            wv.evaluateJavascript(injectPlugin, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject " + meta.fileName, e);
        }
    }

    // =========================================================================
    // Management
    // =========================================================================

    public JSONArray listUserscriptsJson() {
        JSONArray arr = new JSONArray();
        for (UserscriptMeta m : loadedScripts()) {
            arr.put(m.toJson());
        }
        return arr;
    }

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
        if (!writeFileText(f, content)) {
            return "写入文件失败: " + f.getAbsolutePath();
        }
        invalidateCache();
        Log.i(TAG, "Installed " + safeName + " (" + meta.describeScope() + ")");
        return null;
    }

    public boolean saveUserscript(String name, String code) {
        return installUserscript(name, code) == null;
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
        String content = readFileText(f);
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
        boolean ok = writeFileText(f, newContent);
        if (ok) invalidateCache();
        return ok;
    }

    /** Replaces the body of an existing script while preserving its metadata header. */
    public boolean updateScriptBody(String fileName, String newBody) {
        File f = new File(mUserscriptsDir, fileName);
        if (!f.exists()) return false;
        String original = readFileText(f);
        if (original.isEmpty()) return false;

        int end = original.indexOf("==/UserScript==");
        if (end < 0) return false;
        String header = original.substring(0, end + "==/UserScript==".length());
        boolean ok = writeFileText(f, header + "\n\n" + (newBody != null ? newBody : ""));
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

    // =========================================================================
    // File helpers
    // =========================================================================

    public String readAssetText(String path) {
        try {
            AssetManager am = mContext.getAssets();
            InputStream is = am.open(path);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            is.close();
            return baos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public String readFileText(File f) {
        if (f == null) return "";
        FileInputStream fis = null;
        try {
            if (!f.exists()) return "";
            long len = f.length();
            if (len > MAX_SCRIPT_BYTES) {
                Log.w(TAG, "File exceeds MAX_SCRIPT_BYTES (" + len + " > " + MAX_SCRIPT_BYTES + "): " + f.getAbsolutePath());
                return "";
            }
            fis = new FileInputStream(f);
            byte[] data = new byte[(int) len];
            int offset = 0;
            int n;
            while (offset < len && (n = fis.read(data, offset, (int) len - offset)) != -1) {
                offset += n;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read " + (f != null ? f.getAbsolutePath() : "?"), e);
            return "";
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to close input stream", e);
                }
            }
        }
    }

    /**
     * Writes UTF-8 text and reports whether it actually landed on disk.
     * Returns false (with a log line) instead of silently discarding the failure.
     */
    public boolean writeFileText(File f, String content) {
        if (f == null) return false;
        FileOutputStream fos = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.e(TAG, "Cannot create parent directory: " + parent.getAbsolutePath());
                return false;
            }
            fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to write " + f.getAbsolutePath(), e);
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to close " + f.getAbsolutePath(), e);
                }
            }
        }
    }
}
