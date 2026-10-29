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

/**
 * Manages embedded agent_bridge.js runtime and userscripts filesystem/injection.
 *
 * Write policy: every write reports success honestly. A failed write returns false and
 * logs, instead of being swallowed and reported as success to the caller.
 */
public class UserscriptManager {

    private static final String TAG = "AcidUserscripts";

    private final Context mContext;
    private final File mWorkspaceDir;
    private final File mUserscriptsDir;
    private String mBridgeJs = "";

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

    public void injectRuntimeAndUserscripts(WebView wv) {
        if (wv == null) return;
        try {
            // 1. Inject Agent Bridge core runtime directly via privileged evaluateJavascript
            // (100% immune to page Content-Security-Policy script-src and TrustedHTML restrictions)
            if (!mBridgeJs.isEmpty()) {
                wv.evaluateJavascript(mBridgeJs, null);
            }

            // 2. Inject userscript plugins directly via IIFE wrappers (no DOM script elements)
            File[] files = mUserscriptsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".js")) {
                        String code = readFileText(f);
                        if (!code.isEmpty()) {
                            String safeName = f.getName();
                            String injectPlugin = "(function(){\n" +
                                "try {\n" +
                                "  window.__acidInjectedUserscripts = window.__acidInjectedUserscripts || {};\n" +
                                "  if (!window.__acidInjectedUserscripts[" + JSONObject.quote(safeName) + "]) {\n" +
                                "    window.__acidInjectedUserscripts[" + JSONObject.quote(safeName) + "] = true;\n" +
                                code + "\n" +
                                "  }\n" +
                                "} catch(e) { console.error('[AcidUserscriptError]', e); }\n" +
                                "})();";
                            wv.evaluateJavascript(injectPlugin, null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject bridge/userscripts into the current page", e);
        }
    }

    public JSONArray listUserscriptsJson() {
        JSONArray arr = new JSONArray();
        File[] files = mUserscriptsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".js")) {
                    JSONObject item = new JSONObject();
                    try {
                        item.put("name", f.getName());
                        item.put("sizeBytes", f.length());
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to encode userscript metadata for " + f.getName(), e);
                        continue;
                    }
                    arr.put(item);
                }
            }
        }
        return arr;
    }

    public boolean saveUserscript(String name, String code) {
        if (name == null || name.trim().isEmpty()) return false;
        String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safeName.endsWith(".js")) safeName += ".user.js";
        File f = new File(mUserscriptsDir, safeName);
        return writeFileText(f, code != null ? code : "");
    }

    public boolean deleteUserscript(String name) {
        if (name == null) return false;
        File f = new File(mUserscriptsDir, name);
        if (f.exists() && f.isFile()) {
            return f.delete();
        }
        return false;
    }

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
        try {
            if (!f.exists()) return "";
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            fis.close();
            return baos.toString("UTF-8");
        } catch (Exception e) {
            return "";
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
