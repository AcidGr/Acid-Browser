package com.acid.browser;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shapes userscript source into a sandboxed, protected closure containing
 * GreaseMonkey / Tampermonkey compatibility shims ({@code unsafeWindow}, {@code GM_*},
 * and Promise-based {@code GM.*}), pre-bundled {@code @require} dependencies,
 * and re-entrance execution guards.
 *
 * <p>Shims and bootstraps are loaded from assets templates ({@code assets/userscripts/*.js})
 * rather than hardcoded string concatenation.
 */
public class UserscriptShaper {

    private static final Map<String, String> sTemplateCache = new ConcurrentHashMap<String, String>();
    private static Context sContext;

    /**
     * Initializes the shaper with an application context for resolving assets inside the APK.
     */
    public static void init(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
    }

    private static final String[] APIS = {
        "GM_setValue", "GM_getValue", "GM_deleteValue", "GM_listValues",
        "GM_setValues", "GM_getValues", "GM_deleteValues",
        "GM_addValueChangeListener", "GM_removeValueChangeListener",
        "GM_addStyle", "GM_addElement", "GM_setClipboard", "GM_log", "GM_notification",
        "GM_openInTab", "GM_download", "GM_getResourceText", "GM_getResourceURL",
        "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_xmlhttpRequest"
    };

    /** Convenience form without token; tests and legacy callers. */
    public static String shape(UserscriptMeta meta, List<String> requires) {
        return shape(meta, requires, null);
    }

    /**
     * Builds executable JavaScript code ready for {@code WebView.evaluateJavascript}.
     *
     * @param meta     userscript metadata and body
     * @param requires list of pre-fetched dependency script sources
     * @param secret   bridge token the native side expects on every call; may be null
     */
    public static String shape(UserscriptMeta meta, List<String> requires, String secret) {
        if (meta == null || meta.body == null || meta.body.trim().isEmpty()) {
            return "";
        }

        String scriptId = meta.fileName != null && !meta.fileName.isEmpty()
            ? meta.fileName
            : "custom_script";
        String guardKey = JSONObject.quote(scriptId);
        String safeScriptId = JSONObject.quote(scriptId);
        String safeSecret = JSONObject.quote(secret == null ? "" : secret);

        JSONObject gmInfo = new JSONObject();
        try {
            JSONObject scriptObj = new JSONObject();
            scriptObj.put("name", meta.name);
            scriptObj.put("namespace", meta.namespace);
            scriptObj.put("version", meta.version);
            scriptObj.put("description", meta.description);
            scriptObj.put("runAt", meta.runAt);
            org.json.JSONArray grantArr = new org.json.JSONArray();
            for (String g : meta.grants) grantArr.put(g);
            scriptObj.put("grant", grantArr);
            gmInfo.put("script", scriptObj);
            gmInfo.put("scriptHandler", "Acid");
            gmInfo.put("version", BuildInfo.VERSION_NAME);
        } catch (Exception ignored) {}
        String gmInfoJson = gmInfo.toString();

        String template = getTemplate("userscripts/runtime_shim.js");
        String shimHeader = template
            .replace("/*__GUARD_KEY__*/", guardKey)
            .replace("/*__SAFE_SID__*/\"\"", safeScriptId)
            .replace("/*__SAFE_SID__*/", safeScriptId)
            .replace("/*__SAFE_SECRET__*/\"\"", safeSecret)
            .replace("/*__GM_INFO__*/{}", gmInfoJson);

        if (meta.unwrap) {
            StringBuilder sb = new StringBuilder();
            sb.append(shimHeader);
            sb.append("\n  window.__acid_expose_unwrapped = function() {\n");
            for (String api : APIS) {
                if (meta.isGranted(api)) {
                    sb.append("    try { window.").append(api).append(" = ").append(api).append("; } catch(e){}\n");
                }
            }
            sb.append("    try { window.GM = GM; window.GM_info = GM_info;"
                + " window.unsafeWindow = window; window.wrappedJSObject = window; } catch(e){}\n");
            sb.append("  };\n");
            sb.append("  window.__acid_expose_unwrapped();\n");
            sb.append("  try { delete window.__acid_expose_unwrapped; } catch(e){}\n");
            sb.append("} catch(e) {\n");
            sb.append("  console.error('[AcidUserscriptError in ")
                .append(scriptId.replace("'", "\\'")).append("]', e);\n");
            sb.append("}\n})();\n");

            sb.append("/* === @require dependencies === */\n");
            if (requires != null) {
                for (String dep : requires) {
                    if (dep != null && !dep.trim().isEmpty()) {
                        sb.append("try {\n").append(dep).append("\n} catch(e) {"
                            + " console.error('[AcidUserscriptRequireError]', e); }\n");
                    }
                }
            }
            sb.append("/* === Script Body (@unwrap: page scope) === */\n");
            sb.append(meta.body).append("\n");
            return sb.toString();
        }

        StringBuilder params = new StringBuilder("window, unsafeWindow, GM, GM_info");
        StringBuilder argsOut = new StringBuilder("unsafeWindow, unsafeWindow, GM, GM_info");
        for (String api : APIS) {
            params.append(", ").append(api);
            argsOut.append(", ").append(grantedExpr(meta, api));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(shimHeader);
        sb.append("\n  (function(").append(params).append(") {\n");
        if (requires != null && !requires.isEmpty()) {
            sb.append("\n    /* === Pre-bundled @require Dependencies === */\n");
            for (String dep : requires) {
                if (dep != null && !dep.trim().isEmpty()) {
                    sb.append("    try {\n");
                    sb.append(dep).append("\n");
                    sb.append("    } catch(e) { console.error('[AcidUserscriptRequireError]', e); }\n");
                }
            }
        }
        sb.append("\n    /* === Script Body === */\n");
        sb.append("    ").append(meta.body).append("\n");
        sb.append("  })(").append(argsOut).append(");\n");
        sb.append("} catch(e) {\n");
        sb.append("  console.error('[AcidUserscriptError in ").append(scriptId.replace("'", "\\'")).append("]', e);\n");
        sb.append("}\n");
        sb.append("})();");

        return sb.toString();
    }

    /**
     * Bootstrap injected at the earliest possible moment on every page.
     * Note: Does not embed the secret token so untrusted pages cannot steal it via bridge hook.
     */
    public static String bootstrap() {
        return getTemplate("userscripts/page_bootstrap.js");
    }

    public static String bootstrap(String secret) {
        return bootstrap();
    }

    /**
     * Minimal {@code window.external.Tampermonkey} facade so script sites (GreasyFork and
     * friends) show "installed" state and offer a one-tap install.
     * Note: Does not embed the secret token so untrusted pages cannot steal it via bridge hook.
     */
    public static String tampermonkeyFacade() {
        return getTemplate("userscripts/tampermonkey_facade.js");
    }

    public static String tampermonkeyFacade(String secret) {
        return tampermonkeyFacade();
    }

    private static String grantedExpr(UserscriptMeta meta, String api) {
        return meta.isGranted(api) ? api : "undefined";
    }

    private static String getTemplate(String relativeAssetPath) {
        String cached = sTemplateCache.get(relativeAssetPath);
        if (cached != null) return cached;

        String content = loadFromDiskOrStream(relativeAssetPath);
        if (content != null && !content.isEmpty()) {
            sTemplateCache.put(relativeAssetPath, content);
            return content;
        }
        return "";
    }

    private static String loadFromDiskOrStream(String path) {
        if (sContext != null) {
            String asset = AcidIo.readAssetUtf8OrEmpty(sContext, path);
            if (asset != null && !asset.isEmpty()) {
                return asset;
            }
        }

        try {
            File f = new File("assets", path);
            if (f.exists() && f.length() > 0) {
                return AcidIo.readFileUtf8OrEmpty(f, 512 * 1024);
            }
        } catch (Exception ignored) {}

        try {
            InputStream in = UserscriptShaper.class.getClassLoader().getResourceAsStream("assets/" + path);
            if (in == null) {
                in = UserscriptShaper.class.getClassLoader().getResourceAsStream(path);
            }
            if (in != null) {
                return AcidIo.readStreamUtf8(in);
            }
        } catch (Exception ignored) {}

        return "";
    }
}
