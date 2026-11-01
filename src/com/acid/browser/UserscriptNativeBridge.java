package com.acid.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Native bridge object exposed to WebView JavaScript context as {@code __acid_gm_host__}.
 *
 * <p>Acts as an RPC dispatcher for GreaseMonkey / Tampermonkey user script operations:
 * persistent storage, cross-origin HTTP, clipboard access, and script logs.
 */
public class UserscriptNativeBridge {

    private static final String TAG = "AcidUserscriptBridge";
    public static final String JS_INTERFACE_NAME = "__acid_gm_host__";

    private final Context mContext;
    private final UserscriptStorage mStorage;
    private final UserscriptHttpHandler mHttpHandler;
    private WeakReference<WebView> mWebViewRef;

    public UserscriptNativeBridge(
        Context context,
        UserscriptStorage storage,
        UserscriptHttpHandler httpHandler
    ) {
        mContext = context != null ? context.getApplicationContext() : null;
        mStorage = storage;
        mHttpHandler = httpHandler;
    }

    public void attachWebView(WebView wv) {
        mWebViewRef = new WeakReference<WebView>(wv);
    }

    @JavascriptInterface
    public String call(String action, String jsonArgs) {
        if (action == null) return "";
        try {
            JSONObject args = jsonArgs != null && !jsonArgs.isEmpty()
                ? new JSONObject(jsonArgs)
                : new JSONObject();

            if ("setValue".equals(action)) {
                String scriptId = args.optString("scriptId", "default");
                String name = args.optString("name", "");
                Object value = args.opt("value");
                if (!name.isEmpty() && mStorage != null) {
                    mStorage.setValue(scriptId, name, value);
                }
                return "true";
            }

            if ("getValue".equals(action)) {
                String scriptId = args.optString("scriptId", "default");
                String name = args.optString("name", "");
                Object defaultValue = args.has("defaultValue") ? args.opt("defaultValue") : null;
                if (!name.isEmpty() && mStorage != null) {
                    Object val = mStorage.getValue(scriptId, name, defaultValue);
                    JSONObject res = new JSONObject();
                    res.put("value", val == null ? JSONObject.NULL : val);
                    return res.toString();
                }
                return "{\"value\":null}";
            }

            if ("deleteValue".equals(action)) {
                String scriptId = args.optString("scriptId", "default");
                String name = args.optString("name", "");
                if (!name.isEmpty() && mStorage != null) {
                    boolean ok = mStorage.deleteValue(scriptId, name);
                    return ok ? "true" : "false";
                }
                return "false";
            }

            if ("listValues".equals(action)) {
                String scriptId = args.optString("scriptId", "default");
                JSONArray arr = new JSONArray();
                if (mStorage != null) {
                    List<String> list = mStorage.listValues(scriptId);
                    for (String k : list) {
                        arr.put(k);
                    }
                }
                return arr.toString();
            }

            if ("setClipboard".equals(action)) {
                String data = args.optString("data", "");
                if (mContext != null && !data.isEmpty()) {
                    ClipboardManager cm = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Userscript", data));
                        return "true";
                    }
                }
                return "false";
            }

            if ("xmlhttpRequest".equals(action)) {
                WebView wv = mWebViewRef != null ? mWebViewRef.get() : null;
                if (wv != null && mHttpHandler != null) {
                    mHttpHandler.executeRequest(wv, args);
                    return "true";
                }
                return "false";
            }

            if ("log".equals(action)) {
                String msg = args.optString("message", "");
                Log.i(TAG, "[UserscriptLog] " + msg);
                return "true";
            }

        } catch (Exception e) {
            Log.w(TAG, "Error executing native bridge action: " + action, e);
        }
        return "";
    }
}
