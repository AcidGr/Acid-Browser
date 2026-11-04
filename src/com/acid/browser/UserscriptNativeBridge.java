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
 * persistent storage, cross-origin HTTP, clipboard access, resources, downloads, tabs.
 *
 * <p>One instance exists per WebView: HTTP callbacks must return to the tab that issued the
 * request, which a single shared instance could not guarantee.
 *
 * <p>Security: every call must carry the per-process {@code secret} that only the injected
 * userscript closure knows, and the target script must hold the {@code @grant} for the API.
 * A page's own JavaScript can see this object but cannot get past either check.
 */
public class UserscriptNativeBridge {

    private static final String TAG = "AcidUserscriptBridge";
    public static final String JS_INTERFACE_NAME = "__acid_gm_host__";

    /** Looks up an installed script by id (its file name). */
    public interface ScriptResolver {
        UserscriptMeta findScript(String scriptId);
    }

    /** Browser-side capabilities a script can trigger. Implementations marshal to the UI thread. */
    public interface Host {
        void openInTab(String url, boolean active);
        void download(String url, String fileName);
        void notify(String title, String text);
        /** The page's bootstrap asked for a phase: 1 = document-start, 2 = end, 4 = idle. */
        void onInjectionRequested(UserscriptNativeBridge bridge, int phase);
        /** {@code window.external.Tampermonkey.isInstalled} probe from a script site. */
        String isScriptInstalled(String name, String namespace);
    }

    /** Receives storage writes so other tabs can be told about them. */
    public interface ValueChangeSink {
        void onValueChanged(UserscriptNativeBridge origin, String scriptId, String name,
                            Object oldValue, Object newValue);
    }

    private final Context mContext;
    private final UserscriptStorage mStorage;
    private final UserscriptHttpHandler mHttpHandler;
    private final String mSecret;
    private ScriptResolver mResolver;
    private UserscriptDependencyResolver mDeps;
    private Host mHost;
    private ValueChangeSink mValueSink;
    private WeakReference<WebView> mWebViewRef;

    /** Permissive form (no token, no grant checks); kept for tests and tooling. */
    public UserscriptNativeBridge(
        Context context,
        UserscriptStorage storage,
        UserscriptHttpHandler httpHandler
    ) {
        this(context, storage, httpHandler, null);
    }

    public UserscriptNativeBridge(
        Context context,
        UserscriptStorage storage,
        UserscriptHttpHandler httpHandler,
        String secret
    ) {
        mContext = context != null ? context.getApplicationContext() : null;
        mStorage = storage;
        mHttpHandler = httpHandler;
        mSecret = (secret == null || secret.isEmpty()) ? null : secret;
    }

    /**
     * Supplies the per-script token that {@link #call} validates against.
     *
     * <p>Set by {@link UserscriptManager} so tokens can be derived per script id. When absent
     * (unit tests and tooling that construct a bridge directly), validation falls back to the
     * single token passed to the constructor, preserving the older contract.
     */
    public interface TokenProvider {
        String tokenFor(String scriptId);
    }

    private TokenProvider mTokenProvider;

    public UserscriptNativeBridge setTokenProvider(TokenProvider provider) {
        mTokenProvider = provider;
        return this;
    }

    /**
     * Resolves the token a call for {@code scriptId} must present.
     *
     * @return the expected token, or {@code null} when no token is enforced at all
     */
    private String expectedTokenFor(String scriptId, UserscriptMeta meta) {
        if (mTokenProvider != null) {
            // An unknown script id has no token, so a call naming one can never authenticate.
            // Returning null here would mean "no check"; return an unmatchable value instead.
            String derived = mTokenProvider.tokenFor(scriptId);
            return derived != null ? derived : "";
        }
        return mSecret;
    }

    public UserscriptNativeBridge configure(
        ScriptResolver resolver,
        UserscriptDependencyResolver deps,
        Host host,
        ValueChangeSink sink
    ) {
        mResolver = resolver;
        mDeps = deps;
        mHost = host;
        mValueSink = sink;
        return this;
    }

    public void attachWebView(WebView wv) {
        mWebViewRef = new WeakReference<WebView>(wv);
    }

    public WebView getWebView() {
        return mWebViewRef != null ? mWebViewRef.get() : null;
    }

    /** Maps a bridge action to the GM API names that authorise it; empty = always allowed. */
    static String[] grantsFor(String action) {
        if ("setValue".equals(action)) return new String[]{"GM_setValue", "GM_setValues"};
        if ("getValue".equals(action)) return new String[]{"GM_getValue", "GM_getValues"};
        if ("deleteValue".equals(action)) return new String[]{"GM_deleteValue", "GM_deleteValues"};
        if ("listValues".equals(action)) return new String[]{"GM_listValues"};
        if ("setClipboard".equals(action)) return new String[]{"GM_setClipboard"};
        if ("xmlhttpRequest".equals(action) || "abortRequest".equals(action)) {
            return new String[]{"GM_xmlhttpRequest"};
        }
        if ("download".equals(action)) return new String[]{"GM_download"};
        if ("openInTab".equals(action)) return new String[]{"GM_openInTab"};
        if ("getResourceText".equals(action)) return new String[]{"GM_getResourceText"};
        if ("getResourceURL".equals(action)) return new String[]{"GM_getResourceURL"};
        if ("notification".equals(action)) return new String[]{"GM_notification"};
        return new String[0];
    }

    @JavascriptInterface
    public String call(String action, String jsonArgs) {
        if (action == null) return "";
        try {
            JSONObject args = jsonArgs != null && !jsonArgs.isEmpty()
                ? new JSONObject(jsonArgs)
                : new JSONObject();

            boolean isPublicAction = "requestInjection".equals(action) || "isInstalled".equals(action);

            String scriptId = args.optString("scriptId", "default");
            UserscriptMeta meta = (mResolver != null) ? mResolver.findScript(scriptId) : null;

            // Token check runs against the token minted for THIS script id, never a shared one.
            // Checking before resolving the id (the previous order) let any script that could
            // read its own embedded token impersonate a more privileged script by passing that
            // script's id, since every script used to share a single process-wide token.
            if (!isPublicAction) {
                String expected = expectedTokenFor(scriptId, meta);
                if (expected != null && !expected.equals(args.optString("secret", ""))) {
                    Log.w(TAG, "Rejected bridge call '" + action + "': bad or missing token for " + scriptId);
                    return "";
                }
            }

            String[] needed = grantsFor(action);
            if (needed.length > 0) {
                if (mResolver != null && (meta == null || !meta.isGranted(needed))) {
                    Log.w(TAG, "Rejected '" + action + "' for " + scriptId + ": unregistered script or missing @grant");
                    return "";
                }
            }

            if ("setValue".equals(action)) {
                String name = args.optString("name", "");
                Object value = args.opt("value");
                if (!name.isEmpty() && mStorage != null) {
                    Object old = mStorage.getValue(scriptId, name, null);
                    mStorage.setValue(scriptId, name, value, true);
                    if (mValueSink != null) {
                        mValueSink.onValueChanged(this, scriptId, name, old, value);
                    }
                }
                return "true";
            }

            if ("getValue".equals(action)) {
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
                String name = args.optString("name", "");
                if (!name.isEmpty() && mStorage != null) {
                    Object old = mStorage.getValue(scriptId, name, null);
                    boolean ok = mStorage.deleteValue(scriptId, name, true);
                    if (ok && mValueSink != null) {
                        mValueSink.onValueChanged(this, scriptId, name, old, null);
                    }
                    return ok ? "true" : "false";
                }
                return "false";
            }

            if ("listValues".equals(action)) {
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
                final String data = args.optString("data", "");
                if (mContext != null && !data.isEmpty()) {
                    AcidAsync.postToMain(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ClipboardManager cm = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("Userscript", data));
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    return "true";
                }
                return "false";
            }

            if ("xmlhttpRequest".equals(action)) {
                WebView wv = getWebView();
                if (wv != null && mHttpHandler != null) {
                    mHttpHandler.executeRequest(wv, args);
                    return "true";
                }
                return "false";
            }

            if ("abortRequest".equals(action)) {
                if (mHttpHandler != null) {
                    mHttpHandler.abort(getWebView(), args.optString("id", ""));
                }
                return "true";
            }

            if ("getResourceText".equals(action) || "getResourceURL".equals(action)) {
                JSONObject res = new JSONObject();
                String value = null;
                if (meta != null && mDeps != null) {
                    String url = meta.findResourceUrl(args.optString("resource", ""));
                    if (!url.isEmpty()) {
                        value = "getResourceText".equals(action)
                            ? mDeps.resolveResourceText(url)
                            : mDeps.resolveResourceDataUrl(url);
                    }
                }
                res.put("value", value == null ? JSONObject.NULL : value);
                return res.toString();
            }

            if ("download".equals(action)) {
                String url = args.optString("url", "");
                if (mHost != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    mHost.download(url, args.optString("name", ""));
                    return "true";
                }
                return "false";
            }

            if ("openInTab".equals(action)) {
                String url = args.optString("url", "");
                if (mHost != null && !url.isEmpty()) {
                    mHost.openInTab(url, args.optBoolean("active", true));
                    return "true";
                }
                return "false";
            }

            if ("requestInjection".equals(action)) {
                if (mHost != null) {
                    mHost.onInjectionRequested(this, args.optInt("runAt", 0));
                }
                return "true";
            }

            if ("isInstalled".equals(action)) {
                JSONObject res = new JSONObject();
                boolean installed = mHost != null
                    && mHost.isScriptInstalled(args.optString("name", ""), args.optString("namespace", "")) != null;
                res.put("value", installed ? Boolean.TRUE : Boolean.FALSE);
                return res.toString();
            }

            if ("notification".equals(action)) {
                if (mHost != null) {
                    mHost.notify(args.optString("title", ""), args.optString("text", ""));
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
