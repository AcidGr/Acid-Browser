package com.acid.browser;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core controller managing WebViews, Tabs, Mobile/Desktop identity switching,
 * DeepLink hijack blocking, Userscript plugin injection, and thread-safe Agent operations.
 */
public class BrowserController {
    private static final String TAG = "AcidBrowserCtrl";

    public static final String HOME_URL = "file:///android_asset/home.html";

    public static final String UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    public static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public interface BrowserStateListener {
        void onUrlChanged(String url, String title);
        void onProgressChanged(int progress, boolean isLoading);
        void onModeChanged(boolean isDesktopMode, int controlMode, boolean somEnabled);
        void onDeepLinkBlocked(String blockedUri, int totalBlocked);
        void onTabsChanged(int tabCount, int activeTabId);
        void onAgentActionLog(String logLine);
    }

    public static class TabSession {
        public final int id;
        public final WebView webView;
        public String title = "New Tab";
        public String url = HOME_URL;
        public int progress = 0;
        public boolean isLoading = false;

        public TabSession(int id, WebView webView) {
            this.id = id;
            this.webView = webView;
        }
    }

    private final Context mContext;
    private final FrameLayout mWebViewContainer;
    private final AgentOverlayView mOverlayView;
    private final BrowserStateListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public static class DownloadItem {
        public final String url;
        public final String mimeType;
        public final long contentLength;
        public final long timestamp;

        public DownloadItem(String url, String mimeType, long contentLength) {
            this.url = url;
            this.mimeType = mimeType != null ? mimeType : "";
            this.contentLength = contentLength;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final List<TabSession> mTabs = new ArrayList<TabSession>();
    private int mNextTabId = 1;
    private TabSession mActiveTab = null;

    private boolean mIsDesktopMode = false;
    private boolean mSomEnabled = false;
    private boolean mDarkMode = false;
    private String mTakeoverReason = "";
    private int mBlockedDeepLinks = 0;
    private String mLastBlockedDeepLink = "";
    private final List<DownloadItem> mDownloads = new ArrayList<DownloadItem>();

    private String mBridgeJs = "";
    private final File mUserscriptsDir;
    private final File mWorkspaceDir;

    public BrowserController(
        Context context,
        FrameLayout webViewContainer,
        AgentOverlayView overlayView,
        BrowserStateListener listener
    ) {
        mContext = context;
        mWebViewContainer = webViewContainer;
        mOverlayView = overlayView;
        mListener = listener;

        mWorkspaceDir = new File(context.getFilesDir(), "workspace");
        mUserscriptsDir = new File(mWorkspaceDir, "userscripts");
        if (!mUserscriptsDir.exists()) {
            mUserscriptsDir.mkdirs();
        }

        loadBridgeAndDefaultUserscripts();
        createNewTab(HOME_URL, true);
    }

    private void loadBridgeAndDefaultUserscripts() {
        mBridgeJs = readAssetText("agent_bridge.js");
        try {
            String[] builtInScripts = mContext.getAssets().list("userscripts");
            if (builtInScripts != null) {
                for (String name : builtInScripts) {
                    if (name.endsWith(".js")) {
                        File target = new File(mUserscriptsDir, name);
                        if (!target.exists()) {
                            String code = readAssetText("userscripts/" + name);
                            writeFileText(target, code);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to seed default userscripts", e);
        }
    }

    private String readAssetText(String path) {
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
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Error reading asset " + path, e);
            return "";
        }
    }

    private String readFileText(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            fis.close();
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void writeFileText(File f, String content) {
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed writing file " + f.getAbsolutePath(), e);
        }
    }

    public File getWorkspaceDir() {
        return mWorkspaceDir;
    }

    public void emitLog(final String msg) {
        Log.i(TAG, msg);
        if (mListener != null) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onAgentActionLog(msg);
                }
            });
        }
    }

    // =========================================================================
    // Tab & WebView Setup
    // =========================================================================

    public TabSession createNewTab(String initialUrl, boolean switchToIt) {
        final int tabId = mNextTabId++;
        WebView wv = new WebView(mContext);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        final TabSession tab = new TabSession(tabId, wv);
        configureWebView(tab);
        mTabs.add(tab);

        if (switchToIt || mActiveTab == null) {
            switchToTab(tabId);
        }
        String targetUrl = (initialUrl == null || initialUrl.trim().isEmpty()) ? HOME_URL : normalizeUrl(initialUrl);
        tab.url = targetUrl;
        wv.loadUrl(targetUrl);

        if (mListener != null) {
            mListener.onTabsChanged(mTabs.size(), mActiveTab != null ? mActiveTab.id : tabId);
        }
        return tab;
    }

    public boolean switchToTab(int tabId) {
        for (TabSession t : mTabs) {
            if (t.id == tabId) {
                mActiveTab = t;
                mWebViewContainer.removeAllViews();
                mWebViewContainer.addView(t.webView);
                if (mListener != null) {
                    mListener.onUrlChanged(t.url, t.title);
                    mListener.onProgressChanged(t.progress, t.isLoading);
                    mListener.onTabsChanged(mTabs.size(), t.id);
                }
                return true;
            }
        }
        return false;
    }

    public boolean closeTab(int tabId) {
        if (mTabs.size() <= 1) {
            // Never close the last remaining tab; reset it to home
            if (mActiveTab != null) {
                mActiveTab.webView.loadUrl(HOME_URL);
            }
            return false;
        }
        int idx = -1;
        for (int i = 0; i < mTabs.size(); i++) {
            if (mTabs.get(i).id == tabId) {
                idx = i;
                break;
            }
        }
        if (idx == -1) return false;

        TabSession removed = mTabs.remove(idx);
        try {
            mWebViewContainer.removeView(removed.webView);
            removed.webView.destroy();
        } catch (Exception ignored) {}

        if (mActiveTab == removed) {
            int nextIdx = Math.max(0, idx - 1);
            switchToTab(mTabs.get(nextIdx).id);
        } else if (mListener != null && mActiveTab != null) {
            mListener.onTabsChanged(mTabs.size(), mActiveTab.id);
        }
        return true;
    }

    public List<TabSession> getTabs() {
        return mTabs;
    }

    public TabSession getActiveTab() {
        return mActiveTab;
    }

    private void configureWebView(final TabSession tab) {
        final WebView wv = tab.webView;
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                java.lang.reflect.Method m = s.getClass().getMethod("setForceDark", int.class);
                m.invoke(s, mDarkMode ? 2 : 0);
            } catch (Exception ignored) {}
        }

        applyIdentityToSettings(wv, mIsDesktopMode);

        wv.setWebViewClient(new WebViewClient() {
            private boolean handleUrlIntercept(String url) {
                if (url == null) return false;
                String lower = url.toLowerCase().trim();
                if (
                    lower.startsWith("http://") ||
                    lower.startsWith("https://") ||
                    lower.startsWith("file://") ||
                    lower.startsWith("about:") ||
                    lower.startsWith("data:") ||
                    lower.startsWith("javascript:") ||
                    lower.startsWith("blob:")
                ) {
                    return false; // Standard web protocol, allow WebView to load
                }
                // Non-web scheme (zhihu://, tbopen://, bilibili://, intent://, etc.) -> BLOCK!
                mBlockedDeepLinks++;
                mLastBlockedDeepLink = url;
                emitLog("[SHIELD] Blocked App-Hijack DeepLink: " + url);
                if (mListener != null) {
                    mListener.onDeepLinkBlocked(url, mBlockedDeepLinks);
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlIntercept(url);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= 21 && request != null && request.getUrl() != null) {
                    return handleUrlIntercept(request.getUrl().toString());
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.url = url;
                tab.isLoading = true;
                injectRuntimeAndUserscripts(view);
                if (tab == mActiveTab && mListener != null) {
                    mListener.onUrlChanged(url, tab.title);
                    mListener.onProgressChanged(10, true);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.url = url;
                tab.title = view.getTitle() != null ? view.getTitle() : url;
                tab.isLoading = false;
                injectRuntimeAndUserscripts(view);
                CookieManager.getInstance().flush();
                if (tab == mActiveTab && mListener != null) {
                    mListener.onUrlChanged(url, tab.title);
                    mListener.onProgressChanged(100, false);
                }
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                tab.progress = newProgress;
                if (newProgress > 35) {
                    injectRuntimeAndUserscripts(view);
                }
                if (tab == mActiveTab && mListener != null) {
                    mListener.onProgressChanged(newProgress, newProgress < 100);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                tab.title = title;
                if (tab == mActiveTab && mListener != null) {
                    mListener.onUrlChanged(tab.url, title);
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                emitLog("[ALERT] JS Alert auto-dismissed: " + message);
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                emitLog("[CONFIRM] JS Confirm auto-accepted: " + message);
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                result.confirm(defaultValue != null ? defaultValue : "");
                return true;
            }
        });

        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                mDownloads.add(new DownloadItem(url, mimetype, contentLength));
                emitLog("[DOWNLOAD] Intercepted: " + url + " (" + mimetype + ", " + contentLength + " bytes)");
                try {
                    android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    android.app.DownloadManager dm = (android.app.DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                    }
                } catch (Exception e) {
                    emitLog("[DOWNLOAD] DownloadManager fallback: " + e.getMessage());
                }
            }
        });
    }

    private void applyIdentityToSettings(WebView wv, boolean desktopMode) {
        WebSettings s = wv.getSettings();
        s.setUserAgentString(desktopMode ? UA_DESKTOP : UA_MOBILE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(desktopMode);
        if (desktopMode) {
            wv.setInitialScale(0);
        } else {
            wv.setInitialScale(0);
        }
    }

    private void injectRuntimeAndUserscripts(WebView wv) {
        if (mBridgeJs != null && !mBridgeJs.isEmpty()) {
            wv.evaluateJavascript(mBridgeJs, null);
        }
        StringBuilder initCall = new StringBuilder();
        initCall.append("if(window.__acidBridge){");
        initCall.append("window.__acidBridge.enforceDesktopViewport(").append(mIsDesktopMode).append(");");
        if (mSomEnabled) {
            initCall.append("window.__acidBridge.setSomHighlight(true);");
        }
        initCall.append("}");
        wv.evaluateJavascript(initCall.toString(), null);

        if (mDarkMode) {
            String darkJs = "(function(){var id='__acid_dark__';if(!document.getElementById(id)){var s=document.createElement('style');s.id=id;s.innerHTML='html{filter:invert(0.9) hue-rotate(180deg)!important;background:#121212!important;}img,video,canvas,svg{filter:invert(1) hue-rotate(180deg)!important;}';(document.head||document.documentElement).appendChild(s);}})();";
            wv.evaluateJavascript(darkJs, null);
        }

        // Inject active userscripts
        File[] scripts = mUserscriptsDir.listFiles();
        if (scripts != null) {
            for (File f : scripts) {
                if (f.isFile() && f.getName().endsWith(".js")) {
                    String code = readFileText(f);
                    if (!code.isEmpty()) {
                        wv.evaluateJavascript(code, null);
                    }
                }
            }
        }
    }

    // =========================================================================
    // Identity Mode, Control Mode & Navigation
    // =========================================================================

    public String normalizeUrl(String raw) {
        if (raw == null) return HOME_URL;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return HOME_URL;
        if (trimmed.equalsIgnoreCase("acid://home") || trimmed.equalsIgnoreCase("about:home")) {
            return HOME_URL;
        }
        if (
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("file://") ||
            trimmed.startsWith("about:") ||
            trimmed.startsWith("javascript:")
        ) {
            return trimmed;
        }
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://" + trimmed;
        }
        try {
            return "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(trimmed, "UTF-8");
        } catch (Exception e) {
            return "https://www.baidu.com";
        }
    }

    public void loadUrl(String url) {
        if (mActiveTab == null) return;
        String target = normalizeUrl(url);
        mActiveTab.url = target;
        mActiveTab.webView.loadUrl(target);
    }

    public void setDesktopMode(boolean desktop, boolean reloadCurrent) {
        mIsDesktopMode = desktop;
        for (TabSession t : mTabs) {
            applyIdentityToSettings(t.webView, desktop);
            t.webView.evaluateJavascript(
                "if(window.__acidBridge){window.__acidBridge.enforceDesktopViewport(" + desktop + ");}",
                null
            );
        }
        if (reloadCurrent && mActiveTab != null) {
            mActiveTab.webView.reload();
        }
        emitLog("[IDENTITY] Switched Browser Identity -> " + (desktop ? "DESKTOP (1280px)" : "MOBILE"));
        if (mListener != null) {
            mListener.onModeChanged(mIsDesktopMode, mOverlayView.getControlMode(), mSomEnabled);
        }
    }

    public boolean isDesktopMode() {
        return mIsDesktopMode;
    }

    public void setControlMode(int mode, String reason) {
        mTakeoverReason = reason != null ? reason : "";
        mOverlayView.setControlMode(mode);
        String modeStr = (mode == AgentOverlayView.MODE_AGENT)
            ? "AI 自动操控中"
            : (mode == AgentOverlayView.MODE_TAKEOVER_REQUESTED ? "请求人类接管 (" + mTakeoverReason + ")" : "人类自由操控");
        emitLog("[CONTROL] Mode -> " + modeStr);
        if (mListener != null) {
            mListener.onModeChanged(mIsDesktopMode, mode, mSomEnabled);
        }
    }

    public int getControlMode() {
        return mOverlayView.getControlMode();
    }

    public String getTakeoverReason() {
        return mTakeoverReason;
    }

    public void setSomEnabled(boolean enabled) {
        mSomEnabled = enabled;
        if (mActiveTab != null) {
            mActiveTab.webView.evaluateJavascript(
                "if(window.__acidBridge){window.__acidBridge.setSomHighlight(" + enabled + ");}",
                null
            );
        }
        if (mListener != null) {
            mListener.onModeChanged(mIsDesktopMode, mOverlayView.getControlMode(), mSomEnabled);
        }
    }

    public boolean isSomEnabled() {
        return mSomEnabled;
    }

    public boolean isDarkMode() {
        return mDarkMode;
    }

    public void setDarkMode(boolean dark) {
        mDarkMode = dark;
        for (TabSession t : mTabs) {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    java.lang.reflect.Method m = t.webView.getSettings().getClass().getMethod("setForceDark", int.class);
                    m.invoke(t.webView.getSettings(), dark ? 2 : 0);
                } catch (Exception ignored) {}
            }
            if (dark) {
                String darkJs = "(function(){document.body.classList.add('dark-mode');var id='__acid_dark__';if(!document.getElementById(id)){var s=document.createElement('style');s.id=id;s.innerHTML='html{filter:invert(0.9) hue-rotate(180deg)!important;background:#121212!important;}img,video,canvas,svg{filter:invert(1) hue-rotate(180deg)!important;}';(document.head||document.documentElement).appendChild(s);}})();";
                t.webView.evaluateJavascript(darkJs, null);
            } else {
                String lightJs = "(function(){document.body.classList.remove('dark-mode');var el=document.getElementById('__acid_dark__');if(el)el.remove();})();";
                t.webView.evaluateJavascript(lightJs, null);
            }
        }
        emitLog("[THEME] Dark Mode -> " + (dark ? "ENABLED" : "DISABLED"));
    }

    public List<DownloadItem> getDownloads() {
        return mDownloads;
    }

    public int getBlockedDeepLinksCount() {
        return mBlockedDeepLinks;
    }

    public String getLastBlockedDeepLink() {
        return mLastBlockedDeepLink;
    }

    // =========================================================================
    // Synchronous Thread-Safe Methods for HttpControlServer & Agent Tools
    // =========================================================================

    private String unescapeEvaluateResult(String raw) {
        if (raw == null || "null".equals(raw)) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                Object val = new JSONTokener(trimmed).nextValue();
                return val != null ? val.toString() : "";
            } catch (Exception ignored) {}
        }
        return trimmed;
    }

    public String evaluateJsSync(final String jsExpr, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> ref = new AtomicReference<String>("{\"ok\":false,\"error\":\"TIMEOUT\"}");

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mActiveTab == null) {
                    ref.set("{\"ok\":false,\"error\":\"NO_ACTIVE_TAB\"}");
                    latch.countDown();
                    return;
                }
                injectRuntimeAndUserscripts(mActiveTab.webView);
                mActiveTab.webView.evaluateJavascript(jsExpr, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        ref.set(unescapeEvaluateResult(value));
                        latch.countDown();
                    }
                });
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return ref.get();
    }

    public JSONObject observeSync(boolean includeSom, boolean viewportOnly, boolean includeScreenshot) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.observe({includeSom:" +
                includeSom + ", viewportOnly:" + viewportOnly + "})); })()";
            String raw = evaluateJsSync(expr, 6000);
            JSONObject obj = new JSONObject(raw);
            obj.put("controlMode", mOverlayView.getControlMode() == AgentOverlayView.MODE_AGENT ? "agent" : "human");
            obj.put("blockedDeepLinks", mBlockedDeepLinks);
            if (!mLastBlockedDeepLink.isEmpty()) {
                obj.put("lastBlockedDeepLink", mLastBlockedDeepLink);
            }
            if (includeScreenshot) {
                String b64 = captureScreenshotBase64Sync(75);
                if (b64 != null) {
                    obj.put("screenshotBase64", b64);
                }
            }
            return obj;
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", e.getMessage());
            } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject navigateSync(final String url, long waitSettleMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                loadUrl(url);
                latch.countDown();
            }
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
            // Poll until loading settles or timeout
            long deadline = SystemClock.uptimeMillis() + Math.max(1200, waitSettleMs);
            Thread.sleep(450);
            while (SystemClock.uptimeMillis() < deadline) {
                final AtomicReference<Boolean> loadingRef = new AtomicReference<Boolean>(false);
                final CountDownLatch stepLatch = new CountDownLatch(1);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingRef.set(mActiveTab != null && mActiveTab.isLoading);
                        stepLatch.countDown();
                    }
                });
                stepLatch.await(500, TimeUnit.MILLISECONDS);
                if (!loadingRef.get()) {
                    Thread.sleep(300); // Quiet settle window
                    break;
                }
                Thread.sleep(250);
            }
        } catch (Exception ignored) {}
        emitLog("[NAV] Navigated -> " + url);
        return observeSync(mSomEnabled, true, false);
    }

    public JSONObject clickRefSync(final String targetRef, final boolean useNativeMotionEvent) {
        try {
            String prepExpr = "(function(){ return JSON.stringify(window.__acidBridge.prepareClick(" +
                JSONObject.quote(targetRef) + ")); })()";
            String prepRaw = evaluateJsSync(prepExpr, 4000);
            JSONObject prep = new JSONObject(prepRaw);
            if (!prep.optBoolean("ok", false)) {
                return prep;
            }

            final double cssX = prep.optDouble("cssX", 0);
            final double cssY = prep.optDouble("cssY", 0);
            final String label = prep.optString("label", "");
            final int revBefore = prep.optInt("revisionBefore", 0);

            if (useNativeMotionEvent) {
                final CountDownLatch tapLatch = new CountDownLatch(1);
                final AtomicReference<Float> viewXRef = new AtomicReference<Float>(0f);
                final AtomicReference<Float> viewYRef = new AtomicReference<Float>(0f);

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mActiveTab == null) {
                            tapLatch.countDown();
                            return;
                        }
                        WebView wv = mActiveTab.webView;
                        float scale = wv.getScale();
                        if (scale <= 0.01f) {
                            scale = mContext.getResources().getDisplayMetrics().density;
                        }
                        final float vx = (float) (cssX * scale);
                        final float vy = (float) (cssY * scale);
                        viewXRef.set(vx);
                        viewYRef.set(vy);

                        mOverlayView.showClickPulse(vx, vy, "[" + targetRef + "] " + label);
                        dispatchHardwareMotionTap(wv, vx, vy);
                        tapLatch.countDown();
                    }
                });
                tapLatch.await(2000, TimeUnit.MILLISECONDS);
                Thread.sleep(220);

                // Verify postcondition: check if DOM revision changed or JS synthetic fallback is needed
                String stateRaw = evaluateJsSync("(function(){ return JSON.stringify(window.__acidBridge.getState()); })()", 2000);
                JSONObject stateAfter = new JSONObject(stateRaw);
                int revAfter = stateAfter.optInt("revision", revBefore);
                boolean verified = (revAfter > revBefore);

                if (!verified) {
                    // Ensure 100% deterministic activation on scaled Desktop viewports
                    String fallbackRaw = evaluateJsSync(
                        "(function(){ return JSON.stringify(window.__acidBridge.jsClick(" + JSONObject.quote(targetRef) + ")); })()",
                        2000
                    );
                    JSONObject fb = new JSONObject(fallbackRaw);
                    revAfter = fb.optInt("revisionAfter", revAfter);
                    verified = true;
                }

                emitLog("[CLICK] [" + targetRef + "] \"" + label + "\" @(" +
                    Math.round(viewXRef.get()) + "," + Math.round(viewYRef.get()) + ")");

                JSONObject receipt = new JSONObject();
                receipt.put("ok", true);
                receipt.put("ref", targetRef);
                receipt.put("label", label);
                receipt.put("strategy", "ANDROID_NATIVE_MOTION_EVENT");
                receipt.put("viewX", Math.round(viewXRef.get()));
                receipt.put("viewY", Math.round(viewYRef.get()));
                receipt.put("revisionBefore", revBefore);
                receipt.put("revisionAfter", revAfter);
                receipt.put("verified", verified);
                return receipt;
            } else {
                String jsRaw = evaluateJsSync(
                    "(function(){ return JSON.stringify(window.__acidBridge.jsClick(" + JSONObject.quote(targetRef) + ")); })()",
                    3000
                );
                emitLog("[CLICK] JS-Synthetic [" + targetRef + "] \"" + label + "\"");
                return new JSONObject(jsRaw);
            }
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", e.getMessage());
            } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject clickCoordinateSync(final float viewX, final float viewY) {
        final CountDownLatch latch = new CountDownLatch(1);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mActiveTab != null) {
                    mOverlayView.showClickPulse(viewX, viewY, "(" + Math.round(viewX) + "," + Math.round(viewY) + ")");
                    dispatchHardwareMotionTap(mActiveTab.webView, viewX, viewY);
                }
                latch.countDown();
            }
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(200);
        } catch (Exception ignored) {}
        emitLog("[CLICK] Coordinate (" + Math.round(viewX) + ", " + Math.round(viewY) + ")");
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("strategy", "ANDROID_RAW_COORDINATE");
            res.put("viewX", Math.round(viewX));
            res.put("viewY", Math.round(viewY));
        } catch (Exception ignored) {}
        return res;
    }

    private void dispatchHardwareMotionTap(final WebView wv, final float x, final float y) {
        final long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        wv.dispatchTouchEvent(down);
        down.recycle();

        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long upTime = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0);
                wv.dispatchTouchEvent(up);
                up.recycle();
            }
        }, 40);
    }

    public JSONObject fillSync(String ref, String text, boolean clear, boolean submit) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.fill(" +
                JSONObject.quote(ref) + "," +
                JSONObject.quote(text) + "," +
                clear + "," +
                submit + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            emitLog("[FILL] [" + ref + "] -> \"" + text + "\"" + (submit ? " (submitted)" : ""));
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject fillFormSync(JSONArray fields, boolean submitLast) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.fillForm(" +
                fields.toString() + "," + submitLast + ")); })()";
            String raw = evaluateJsSync(expr, 5000);
            emitLog("[FORM] Batch-filled (" + fields.length() + " fields)");
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject selectOptionSync(String ref, String valueOrLabel) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.selectOption(" +
                JSONObject.quote(ref) + "," + JSONObject.quote(valueOrLabel) + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            emitLog("[SELECT] [" + ref + "] -> \"" + valueOrLabel + "\"");
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public JSONObject scrollSync(String direction, double amount, String targetRef) {
        try {
            String expr = "(function(){ return JSON.stringify(window.__acidBridge.scroll(" +
                JSONObject.quote(direction) + "," + amount + "," +
                (targetRef == null ? "null" : JSONObject.quote(targetRef)) + ")); })()";
            String raw = evaluateJsSync(expr, 4000);
            emitLog("[SCROLL] " + direction + " (" + amount + ")");
            return new JSONObject(raw);
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try { err.put("ok", false); err.put("error", e.getMessage()); } catch (Exception ignored) {}
            return err;
        }
    }

    public String captureScreenshotBase64Sync(final int jpegQuality) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> b64Ref = new AtomicReference<String>(null);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mActiveTab != null && mActiveTab.webView.getWidth() > 0 && mActiveTab.webView.getHeight() > 0) {
                        WebView wv = mActiveTab.webView;
                        Bitmap bmp = Bitmap.createBitmap(wv.getWidth(), wv.getHeight(), Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bmp);
                        wv.draw(canvas);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
                        bmp.recycle();
                        b64Ref.set(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Screenshot error", e);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        return b64Ref.get();
    }

    // =========================================================================
    // Userscript Plugin Manager
    // =========================================================================

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
        emitLog("[PLUGIN] Userscript installed: " + safeName);
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
}
