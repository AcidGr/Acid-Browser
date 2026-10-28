package com.acid.browser;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Base64;
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
 */
public class UserscriptManager {

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
        try {
            mBridgeJs = readAssetText("agent_bridge.js");
        } catch (Exception ignored) {}

        File defaultScript = new File(mUserscriptsDir, "anti_app_hijack.user.js");
        if (!defaultScript.exists()) {
            try {
                String src = readAssetText("userscripts/anti_app_hijack.user.js");
                writeFileText(defaultScript, src);
            } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
    }

    public JSONArray listUserscriptsJson() {
        JSONArray arr = new JSONArray();
        File[] files = mUserscriptsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".js")) {
                    try {
                        JSONObject item = new JSONObject();
                        item.put("name", f.getName());
                        item.put("sizeBytes", f.length());
                        arr.put(item);
                    } catch (Exception ignored) {}
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
        writeFileText(f, code != null ? code : "");
        return true;
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

    public void writeFileText(File f, String content) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Exception ignored) {}
    }
}
