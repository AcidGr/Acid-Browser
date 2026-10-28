package com.acid.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Core controller managing Tab sessions, WebView lifecycle, Mobile/Desktop identity,
 * DeepLink hijack blocking, and coordinating with UserscriptManager and WebAutomationDriver.
 */
public class BrowserController implements WebAutomationDriver.AutomationHost {
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
        public Bitmap snapshot = null;

        public TabSession(int id, WebView webView) {
            this.id = id;
            this.webView = webView;
        }
    }

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

    private final Context mContext;
    private final FrameLayout mWebViewContainer;
    private final AgentOverlayView mOverlayView;
    private final BrowserStateListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final UserscriptManager mUserscriptManager;
    private final WebAutomationDriver mAutomationDriver;

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

        mUserscriptManager = new UserscriptManager(context);
        mAutomationDriver = new WebAutomationDriver(context, this);

        createNewTab(HOME_URL, true);
    }

    public UserscriptManager getUserscriptManager() {
        return mUserscriptManager;
    }

    public WebAutomationDriver getAutomationDriver() {
        return mAutomationDriver;
    }

    public File getWorkspaceDir() {
        return mUserscriptManager.getWorkspaceDir();
    }

    public File getUserscriptsDir() {
        return mUserscriptManager.getUserscriptsDir();
    }

    @Override
    public AgentOverlayView getOverlayView() {
        return mOverlayView;
    }

    @Override
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
    // Tab Management
    // =========================================================================

    public TabSession createNewTab(String initialUrl, boolean switchToIt) {
        final int tabId = mNextTabId++;
        WebView wv = new WebView(mContext);
        final TabSession tab = new TabSession(tabId, wv);
        configureWebView(tab);

        mTabs.add(tab);
        mWebViewContainer.addView(wv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        wv.setVisibility(android.view.View.GONE);

        if (switchToIt || mActiveTab == null) {
            switchToTab(tabId);
        }

        String targetUrl = (initialUrl != null && !initialUrl.isEmpty()) ? initialUrl : HOME_URL;
        tab.url = targetUrl;
        wv.loadUrl(targetUrl);

        if (mListener != null) {
            mListener.onTabsChanged(mTabs.size(), mActiveTab != null ? mActiveTab.id : -1);
        }
        return tab;
    }

    public void captureActiveTabSnapshot() {
        if (mActiveTab == null || mActiveTab.webView == null) return;
        try {
            WebView wv = mActiveTab.webView;
            int w = wv.getWidth();
            int h = wv.getHeight();
            if (w > 0 && h > 0) {
                float density = mContext.getResources().getDisplayMetrics().density;
                int thumbW = Math.round(180 * density);
                int thumbH = Math.round(240 * density);
                Bitmap thumb = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(thumb);
                float scale = Math.max((float) thumbW / w, (float) thumbH / h);
                canvas.scale(scale, scale);
                wv.draw(canvas);
                mActiveTab.snapshot = thumb;
            }
        } catch (Exception ignored) {}
    }

    public boolean switchToTab(int tabId) {
        TabSession target = null;
        for (TabSession t : mTabs) {
            if (t.id == tabId) {
                target = t;
                break;
            }
        }
        if (target == null) return false;

        captureActiveTabSnapshot();

        for (TabSession t : mTabs) {
            t.webView.setVisibility(t.id == tabId ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        mActiveTab = target;

        if (mListener != null) {
            mListener.onTabsChanged(mTabs.size(), mActiveTab.id);
            mListener.onUrlChanged(mActiveTab.url, mActiveTab.title);
            mListener.onProgressChanged(mActiveTab.progress, mActiveTab.isLoading);
        }
        return true;
    }

    public boolean closeTab(int tabId) {
        if (mTabs.size() <= 1) {
            // Last tab: reset to Home instead of destroying browser
            if (mActiveTab != null) {
                mActiveTab.title = "Acid 靶场首页";
                mActiveTab.url = HOME_URL;
                mActiveTab.webView.loadUrl(HOME_URL);
                if (mListener != null) {
                    mListener.onUrlChanged(mActiveTab.url, mActiveTab.title);
                }
            }
            return false;
        }

        TabSession toRemove = null;
        int removeIndex = -1;
        for (int i = 0; i < mTabs.size(); i++) {
            if (mTabs.get(i).id == tabId) {
                toRemove = mTabs.get(i);
                removeIndex = i;
                break;
            }
        }
        if (toRemove == null) return false;

        mWebViewContainer.removeView(toRemove.webView);
        toRemove.webView.stopLoading();
        toRemove.webView.destroy();
        mTabs.remove(removeIndex);

        if (mActiveTab != null && mActiveTab.id == tabId) {
            int newActiveIndex = Math.min(removeIndex, mTabs.size() - 1);
            switchToTab(mTabs.get(newActiveIndex).id);
        } else {
            if (mListener != null) {
                mListener.onTabsChanged(mTabs.size(), mActiveTab != null ? mActiveTab.id : -1);
            }
        }
        return true;
    }

    public void closeOtherTabs(int keepTabId) {
        for (int i = mTabs.size() - 1; i >= 0; i--) {
            TabSession t = mTabs.get(i);
            if (t.id != keepTabId) {
                mWebViewContainer.removeView(t.webView);
                t.webView.stopLoading();
                t.webView.destroy();
                mTabs.remove(i);
            }
        }
        switchToTab(keepTabId);
    }

    public List<TabSession> getTabs() {
        return new ArrayList<TabSession>(mTabs);
    }

    @Override
    public TabSession getActiveTab() {
        return mActiveTab;
    }

    // =========================================================================
    // WebView Configuration & Callbacks
    // =========================================================================

    private void configureWebView(final TabSession tab) {
        final WebView wv = tab.webView;
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }

        applyIdentityToSettings(wv, mIsDesktopMode);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlIntercept(url);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= 24 && request != null && request.getUrl() != null) {
                    return handleUrlIntercept(request.getUrl().toString());
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.isLoading = true;
                tab.url = url;
                if (mActiveTab != null && mActiveTab.id == tab.id && mListener != null) {
                    mListener.onUrlChanged(url, tab.title);
                    mListener.onProgressChanged(0, true);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.isLoading = false;
                tab.url = url;
                mUserscriptManager.injectRuntimeAndUserscripts(view);

                if (mDarkMode) {
                    injectDarkModeCss(view);
                }

                if (mActiveTab != null && mActiveTab.id == tab.id && mListener != null) {
                    mListener.onUrlChanged(url, tab.title);
                    mListener.onProgressChanged(100, false);
                }
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mActiveTab != null && mActiveTab.id == tab.id) {
                            captureActiveTabSnapshot();
                        }
                    }
                }, 800);
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                tab.progress = newProgress;
                tab.isLoading = (newProgress < 100);
                if (newProgress > 40) {
                    mUserscriptManager.injectRuntimeAndUserscripts(view);
                }
                if (mActiveTab != null && mActiveTab.id == tab.id && mListener != null) {
                    mListener.onProgressChanged(newProgress, tab.isLoading);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                tab.title = (title != null && !title.isEmpty()) ? title : tab.url;
                if (mActiveTab != null && mActiveTab.id == tab.id && mListener != null) {
                    mListener.onUrlChanged(tab.url, tab.title);
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                emitLog("[JS_ALERT] " + message);
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                emitLog("[JS_CONFIRM] " + message + " -> AUTO_CONFIRM");
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                emitLog("[JS_PROMPT] " + message + " -> CONFIRM(" + defaultValue + ")");
                result.confirm(defaultValue != null ? defaultValue : "");
                return true;
            }
        });

        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                mDownloads.add(0, new DownloadItem(url, mimeType, contentLength));
                emitLog("[DOWNLOAD] Tracked: " + url + " (" + contentLength + " B)");
            }
        });
    }

    private boolean handleUrlIntercept(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file://") || lower.startsWith("about:") || lower.startsWith("javascript:")) {
            return false;
        }

        mBlockedDeepLinks++;
        mLastBlockedDeepLink = url;
        emitLog("[HIJACK_SHIELD] Blocked DeepLink -> " + url + " (Total: " + mBlockedDeepLinks + ")");

        if (mListener != null) {
            mListener.onDeepLinkBlocked(url, mBlockedDeepLinks);
        }
        return true;
    }

    private void applyIdentityToSettings(WebView wv, boolean desktopMode) {
        WebSettings s = wv.getSettings();
        s.setUserAgentString(desktopMode ? UA_DESKTOP : UA_MOBILE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(desktopMode);
    }

    private void injectDarkModeCss(WebView wv) {
        String darkCss = "html { filter: invert(90%) hue-rotate(180deg) !important; background: #0f172a !important; }" +
            "img, video, iframe, canvas, svg { filter: invert(100%) hue-rotate(180deg) !important; }";
        String js = "javascript:(function(){" +
            "var el=document.getElementById('__acid_dark_mode');" +
            "if(!el){" +
            "el=document.createElement('style');" +
            "el.id='__acid_dark_mode';" +
            "el.innerText='" + darkCss + "';" +
            "(document.head||document.documentElement).appendChild(el);" +
            "}" +
            "})()";
        wv.evaluateJavascript(js, null);
    }

    public String normalizeUrl(String raw) {
        if (raw == null) return HOME_URL;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return HOME_URL;
        if ("acid://home".equalsIgnoreCase(trimmed) || "about:home".equalsIgnoreCase(trimmed)) {
            return HOME_URL;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://") || trimmed.startsWith("javascript:")) {
            return trimmed;
        }
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            try {
                return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8");
            } catch (Exception e) {
                return "https://www.google.com/search?q=" + trimmed;
            }
        }
        return "https://" + trimmed;
    }

    @Override
    public void loadUrl(String url) {
        final String finalUrl = normalizeUrl(url);
        if (mActiveTab != null) {
            mActiveTab.webView.loadUrl(finalUrl);
        }
    }

    public void setDesktopMode(boolean desktop, boolean reloadCurrent) {
        mIsDesktopMode = desktop;
        for (TabSession t : mTabs) {
            applyIdentityToSettings(t.webView, desktop);
        }
        if (reloadCurrent && mActiveTab != null) {
            mActiveTab.webView.reload();
        }
        if (mListener != null) {
            mListener.onModeChanged(mIsDesktopMode, mOverlayView.getControlMode(), mSomEnabled);
        }
    }

    public boolean isDesktopMode() {
        return mIsDesktopMode;
    }

    public void setControlMode(int mode, String reason) {
        mTakeoverReason = (reason != null) ? reason : "";
        mOverlayView.setControlMode(mode);
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
        evaluateJsSync("window.__acidBridge && window.__acidBridge.setSomEnabled(" + enabled + ");", 1500);
        if (mListener != null) {
            mListener.onModeChanged(mIsDesktopMode, mOverlayView.getControlMode(), mSomEnabled);
        }
    }

    @Override
    public boolean isSomEnabled() {
        return mSomEnabled;
    }

    public boolean isDarkMode() {
        return mDarkMode;
    }

    public void setDarkMode(boolean dark) {
        mDarkMode = dark;
        for (TabSession t : mTabs) {
            if (dark) {
                injectDarkModeCss(t.webView);
            } else {
                t.webView.evaluateJavascript(
                    "javascript:(function(){var el=document.getElementById('__acid_dark_mode');if(el)el.remove();})()",
                    null
                );
            }
        }
    }

    public List<DownloadItem> getDownloads() {
        return new ArrayList<DownloadItem>(mDownloads);
    }

    public String getCookieForUrl(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean setCookieForUrl(String url, String cookieString) {
        try {
            CookieManager.getInstance().setCookie(url, cookieString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void clearAllCookies() {
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }
    }

    public void flushCookies() {
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().flush();
        }
    }

    @Override
    public int getBlockedDeepLinksCount() {
        return mBlockedDeepLinks;
    }

    @Override
    public String getLastBlockedDeepLink() {
        return mLastBlockedDeepLink;
    }

    // =========================================================================
    // Delegation to WebAutomationDriver & UserscriptManager
    // =========================================================================

    public String evaluateJsSync(String jsExpr, long timeoutMs) {
        return mAutomationDriver.evaluateJsSync(jsExpr, timeoutMs);
    }

    public JSONObject observeSync(boolean includeSom, boolean viewportOnly, boolean includeScreenshot) {
        return mAutomationDriver.observeSync(includeSom, viewportOnly, includeScreenshot);
    }

    public JSONObject navigateSync(String url, long waitSettleMs) {
        return mAutomationDriver.navigateSync(url, waitSettleMs);
    }

    public JSONObject clickRefSync(String targetRef, boolean useNativeMotionEvent) {
        return mAutomationDriver.clickRefSync(targetRef, useNativeMotionEvent);
    }

    public JSONObject clickCoordinateSync(float viewX, float viewY) {
        return mAutomationDriver.clickCoordinateSync(viewX, viewY);
    }

    public JSONObject fillSync(String ref, String text, boolean clear, boolean submit) {
        return mAutomationDriver.fillSync(ref, text, clear, submit);
    }

    public JSONObject fillFormSync(JSONArray fields, boolean submitLast) {
        return mAutomationDriver.fillFormSync(fields, submitLast);
    }

    public JSONObject selectOptionSync(String ref, String valueOrLabel) {
        return mAutomationDriver.selectOptionSync(ref, valueOrLabel);
    }

    public JSONObject scrollSync(String direction, double amount, String targetRef) {
        return mAutomationDriver.scrollSync(direction, amount, targetRef);
    }

    public String captureScreenshotBase64Sync(int jpegQuality) {
        return mAutomationDriver.captureScreenshotBase64Sync(jpegQuality);
    }

    public JSONArray listUserscriptsJson() {
        return mUserscriptManager.listUserscriptsJson();
    }

    public boolean saveUserscript(String name, String code) {
        boolean ok = mUserscriptManager.saveUserscript(name, code);
        if (ok) emitLog("[PLUGIN] Userscript installed: " + name);
        return ok;
    }

    public boolean deleteUserscript(String name) {
        return mUserscriptManager.deleteUserscript(name);
    }
}
