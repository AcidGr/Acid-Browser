package com.acid.browser;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.acid.browser.download.DownloadCoordinator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Core controller managing Tab sessions, WebView lifecycle, Mobile/Desktop identity,
 * DeepLink hijack blocking, and coordinating with {@link UserscriptManager},
 * {@link WebAutomationDriver}, and {@link DownloadCoordinator}.
 */
public class BrowserController implements WebAutomationDriver.AutomationHost {
    private static final String TAG = "AcidBrowserCtrl";

    public static final String HOME_URL = "file:///android_asset/homepage/index.html";

    public static final String UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    public static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public interface BrowserStateListener {
        void onUrlChanged(String url, String title);
        void onProgressChanged(int progress, boolean isLoading);
        void onModeChanged(boolean isDesktopMode, int controlMode, boolean somEnabled);
        void onDeepLinkBlocked(String blockedUri, int totalBlocked);
        void onAppLaunchConfirmRequested(String url, String appName, Intent launchIntent);
        void onTabsChanged(int tabCount, int activeTabId);
        void onDownloadRequested(String url, String fileName, String userAgent, String contentDisposition, String mimeType, long contentLength);
        void onUserscriptInstallRequested(String url, String fileName);
        void onSettingsReloaded();
        void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback);
        void onHideCustomView();
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

    private final Context mContext;
    private final FrameLayout mWebViewContainer;
    private final AgentOverlayView mOverlayView;
    private final BrowserStateListener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final UserscriptManager mUserscriptManager;
    private final SkillManager mSkillManager;
    private final WebAutomationDriver mAutomationDriver;
    private final DownloadCoordinator mDownloadCoordinator;

    private final List<TabSession> mTabs = new ArrayList<TabSession>();
    private int mNextTabId = 1;
    private TabSession mActiveTab = null;
    private int mAgentBoundTabId = -1;
    private boolean mOverviewShowing = false;

    private boolean mIsDesktopMode = false;
    private boolean mIncognitoMode = false;
    private boolean mSomEnabled = false;
    private boolean mDarkMode = false;
    private String mTakeoverReason = "";
    private int mBlockedDeepLinks = 0;
    private String mLastBlockedDeepLink = "";
    private String mLastAskedAppLaunchUrl = "";
    private long mLastAskedAppLaunchTimeMs = 0L;
    private volatile int mTriggeredDownloads = 0;
    private volatile String mLastTriggeredDownloadInfo = "";
    private View mCustomView = null;
    private WebChromeClient.CustomViewCallback mCustomViewCallback = null;
    private BrowserDataStore mDataStore = null;

    public void setDataStore(BrowserDataStore dataStore) {
        mDataStore = dataStore;
        mDownloadCoordinator.setDataStore(dataStore);
        if (mDataStore != null) {
            mDataStore.setOnBookmarksMutatedListener(new Runnable() {
                @Override
                public void run() {
                    pushBookmarksToActiveHomeTabs();
                }
            });
        }
        if (mDataStore != null && Build.VERSION.SDK_INT >= 19) {
            try {
                WebView.setWebContentsDebuggingEnabled(mDataStore.isWebDebuggingEnabled());
            } catch (Exception e) {
                Log.w(TAG, "Failed to set web contents debugging", e);
            }
        }
    }

    public BrowserController(
        Context context,
        FrameLayout webViewContainer,
        AgentOverlayView overlayView,
        BrowserStateListener listener
    ) {
        this(context, webViewContainer, overlayView, listener, null, false);
    }

    public BrowserController(
        Context context,
        FrameLayout webViewContainer,
        AgentOverlayView overlayView,
        BrowserStateListener listener,
        boolean isDarkMode
    ) {
        this(context, webViewContainer, overlayView, listener, null, isDarkMode);
    }

    public BrowserController(
        Context context,
        FrameLayout webViewContainer,
        AgentOverlayView overlayView,
        BrowserStateListener listener,
        BrowserDataStore dataStore,
        boolean isDarkMode
    ) {
        mContext = context;
        mWebViewContainer = webViewContainer;
        mOverlayView = overlayView;
        mListener = listener;
        mDarkMode = isDarkMode;
        mDataStore = dataStore;

        mUserscriptManager = new UserscriptManager(context);
        mUserscriptManager.setHost(new UserscriptNativeBridge.Host() {
            @Override
            public void openInTab(final String url, final boolean active) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        createNewTab(url, active);
                    }
                });
            }

            @Override
            public void download(final String url, final String fileName) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        requestDownload(url, fileName, null, null, null, 0);
                    }
                });
            }

            @Override
            public void onInjectionRequested(final UserscriptNativeBridge bridge, final int phase) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        WebView wv = bridge.getWebView();
                        if (wv == null) return;
                        String url;
                        try {
                            url = wv.getUrl();
                        } catch (Exception e) {
                            return;
                        }
                        mUserscriptManager.injectPhase(wv, url, phase);
                    }
                });
            }

            @Override
            public String isScriptInstalled(String name, String namespace) {
                UserscriptMeta m = mUserscriptManager.findInstalled(name, namespace);
                return m == null ? null : m.fileName;
            }

            @Override
            public void notify(final String title, final String text) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        String msg = (title == null || title.isEmpty()) ? text : (title + "：" + text);
                        if (msg != null && !msg.trim().isEmpty()) AcidFeedback.info(mContext, msg);
                    }
                });
            }
        });
        mSkillManager = new SkillManager(context, getWorkspaceDir());
        AdBlockManager adBlock = AdBlockManager.getInstance(context);
        adBlock.setWorkspaceDir(getWorkspaceDir());
        if (mDataStore != null) {
            mDataStore.setWorkspaceDir(getWorkspaceDir());
            adBlock.bindDataStore(mDataStore);
        }
        mAutomationDriver = new WebAutomationDriver(context, this);
        mDownloadCoordinator = new DownloadCoordinator(context, mDataStore);

        if (mOverlayView != null) {
            mOverlayView.setTabStateProvider(new AgentOverlayView.TabStateProvider() {
                @Override
                public boolean isAgentDrivingCurrentTab() {
                    return BrowserController.this.isAgentDrivingCurrentTab();
                }

                @Override
                public boolean isOverviewShowing() {
                    return BrowserController.this.isOverviewShowing();
                }
            });
        }

        if (mDataStore != null && Build.VERSION.SDK_INT >= 19) {
            try {
                WebView.setWebContentsDebuggingEnabled(mDataStore.isWebDebuggingEnabled());
            } catch (Exception e) {
                Log.w(TAG, "Failed to set web contents debugging", e);
            }
        }

        createNewTab(getHomeUrl(), true);
    }

    public Context getContext() {
        return mContext;
    }

    @Override
    public UserscriptManager getUserscriptManager() {
        return mUserscriptManager;
    }

    public WebAutomationDriver getAutomationDriver() {
        return mAutomationDriver;
    }

    public DownloadCoordinator getDownloadCoordinator() {
        return mDownloadCoordinator;
    }

    public File getWorkspaceDir() {
        return mUserscriptManager.getWorkspaceDir();
    }

    @Override
    public AgentOverlayView getOverlayView() {
        return mOverlayView;
    }

    @Override
    public void emitLog(final String msg) {
        Log.i(TAG, msg);
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

        String targetUrl = (initialUrl != null && !initialUrl.isEmpty()) ? initialUrl : getHomeUrl();
        tab.url = targetUrl;
        wv.loadUrl(targetUrl);

        if (mListener != null) {
            mListener.onTabsChanged(mTabs.size(), mActiveTab != null ? mActiveTab.id : -1);
        }
        return tab;
    }

    public TabSession createNewTabBlank(boolean switchToIt) {
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
        } catch (Exception e) {
            Log.w(TAG, "Failed to capture tab thumbnail", e);
        }
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
            t.webView.setVisibility(t.id == tabId ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        }
        target.webView.bringToFront();
        mActiveTab = target;

        if (mOverlayView != null) {
            if (!mOverviewShowing) {
                mOverlayView.bringToFront();
            }
            mOverlayView.postInvalidateOnAnimation();
        }

        if (mListener != null) {
            mListener.onTabsChanged(mTabs.size(), mActiveTab.id);
            mListener.onUrlChanged(mActiveTab.url, mActiveTab.title);
            mListener.onProgressChanged(mActiveTab.progress, mActiveTab.isLoading);
        }
        return true;
    }

    public boolean closeTab(int tabId) {
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

        if (mAgentBoundTabId == tabId) {
            mAgentBoundTabId = -1;
        }

        boolean wasActive = mActiveTab != null && mActiveTab.id == tabId;
        if (wasActive) {
            // The WebView is about to be destroyed: never snapshot / switch away from it.
            mActiveTab = null;
        }

        mWebViewContainer.removeView(toRemove.webView);
        toRemove.webView.stopLoading();
        toRemove.webView.destroy();
        mTabs.remove(removeIndex);

        if (mTabs.isEmpty()) {
            // Fallback: the browser must always own one tab. Closing the last tab destroys it completely
            // (history included) and a brand-new Home tab takes over, so Back can never return to the old page.
            createNewTab(getHomeUrl(), true);
            return true;
        }

        if (wasActive) {
            int newActiveIndex = Math.min(removeIndex, mTabs.size() - 1);
            switchToTab(mTabs.get(newActiveIndex).id);
        } else if (mListener != null) {
            mListener.onTabsChanged(mTabs.size(), mActiveTab != null ? mActiveTab.id : -1);
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

    public void setAgentBoundTabId(int tabId) {
        mAgentBoundTabId = tabId;
        if (mOverlayView != null) {
            mOverlayView.postInvalidateOnAnimation();
        }
    }

    public int getAgentBoundTabId() {
        return mAgentBoundTabId;
    }

    public TabSession getTabById(int tabId) {
        for (TabSession t : mTabs) {
            if (t.id == tabId) return t;
        }
        return null;
    }

    @Override
    public TabSession getTargetTab() {
        if (mAgentBoundTabId > 0) {
            TabSession bound = getTabById(mAgentBoundTabId);
            if (bound != null && bound.webView != null) {
                return bound;
            }
        }
        return mActiveTab;
    }

    public void setOverviewShowing(boolean showing) {
        mOverviewShowing = showing;
        if (mOverlayView != null) {
            mOverlayView.postInvalidateOnAnimation();
        }
    }

    public boolean isOverviewShowing() {
        return mOverviewShowing;
    }

    public boolean isTabDrivenByAgent(int tabId) {
        int mode = getControlMode();
        return (mAgentBoundTabId > 0 && mAgentBoundTabId == tabId)
            && (mode == AgentOverlayView.MODE_AGENT || mode == AgentOverlayView.MODE_TAKEOVER_REQUESTED);
    }

    public boolean isAgentDrivingCurrentTab() {
        if (mOverviewShowing) return false;
        if (mAgentBoundTabId <= 0) return true;
        return mActiveTab != null && mActiveTab.id == mAgentBoundTabId;
    }

    @Override
    public boolean isTargetTabActive() {
        return isAgentDrivingCurrentTab();
    }

    // =========================================================================
    // WebView Configuration & Callbacks
    // =========================================================================

    private void configureWebView(final TabSession tab) {
        final WebView wv = tab.webView;
        wv.setBackgroundColor(AcidTheme.bg(mDarkMode));
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            cm.setAcceptThirdPartyCookies(wv, true);
        }

        applyIdentityToSettings(wv, mIsDesktopMode);
        applyDarkModeToSettings(wv, mDarkMode);
        applyIncognitoToSettings(wv, mIncognitoMode);
        boolean blockImages = mDataStore != null && mDataStore.isBlockImagesEnabled();
        s.setLoadsImagesAutomatically(!blockImages);
        s.setBlockNetworkImage(blockImages);
        // Attach Userscript Native Bridge for GM_* API support
        if (mUserscriptManager != null) {
            mUserscriptManager.attachToWebView(wv);
        }

        // Track touch coordinates for floating element context menu (PopupWindow)
        final float[] lastTouch = new float[2];
        wv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                }
                return false;
            }
        });

        // Long-click context menu near touch point without emojis (links, images)
        wv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(final View v) {
                final WebView.HitTestResult result = wv.getHitTestResult();
                if (result == null) return false;
                int type = result.getType();
                if (type == WebView.HitTestResult.UNKNOWN_TYPE || type == WebView.HitTestResult.EDIT_TEXT_TYPE) {
                    return false;
                }

                final int touchX = Math.round(lastTouch[0]);
                final int touchY = Math.round(lastTouch[1]);
                final String extra = result.getExtra();

                if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    Message msg = mMainHandler.obtainMessage();
                    msg.setTarget(new Handler(Looper.getMainLooper()) {
                        @Override
                        public void handleMessage(Message m) {
                            String linkUrl = null;
                            String imageUrl = null;
                            if (m.getData() != null) {
                                linkUrl = m.getData().getString("url");
                                imageUrl = m.getData().getString("src");
                            }
                            if (imageUrl == null || imageUrl.isEmpty()) {
                                imageUrl = extra;
                            }
                            ElementContextMenu.show(mContext, BrowserController.this, v, touchX, touchY, linkUrl, imageUrl, mDarkMode);
                        }
                    });
                    wv.requestFocusNodeHref(msg);
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    return true;
                }

                String linkUrl = null;
                String imageUrl = null;

                if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    linkUrl = extra;
                } else if (type == WebView.HitTestResult.IMAGE_TYPE) {
                    imageUrl = extra;
                } else if (type == WebView.HitTestResult.PHONE_TYPE || type == WebView.HitTestResult.EMAIL_TYPE) {
                    linkUrl = extra;
                } else {
                    return false;
                }

                ElementContextMenu.show(mContext, BrowserController.this, v, touchX, touchY, linkUrl, imageUrl, mDarkMode);
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
        });

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    Uri u = request.getUrl();
                    if ("acid-favicon.local".equalsIgnoreCase(u.getHost())) {
                        return interceptFaviconRequest(u);
                    }
                    WebResourceResponse resp = AdBlockManager.getInstance(mContext).shouldIntercept(u);
                    if (resp != null) {
                        return resp;
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null) {
                    Uri u = Uri.parse(url);
                    if ("acid-favicon.local".equalsIgnoreCase(u.getHost())) {
                        return interceptFaviconRequest(u);
                    }
                    WebResourceResponse resp = AdBlockManager.getInstance(mContext).shouldIntercept(u);
                    if (resp != null) {
                        return resp;
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.endsWith(".user.js") || url.contains(".user.js?"))) {
                    String guessName = DownloadCoordinator.smartGuessFileName(url, null, "application/javascript");
                    if (mListener != null) {
                        mListener.onUserscriptInstallRequested(url, guessName);
                    }
                    return true;
                }
                return handleUrlIntercept(url);
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= 24 && request != null && request.getUrl() != null) {
                    String u = request.getUrl().toString();
                    if (u.endsWith(".user.js") || u.contains(".user.js?")) {
                        String guessName = DownloadCoordinator.smartGuessFileName(u, null, "application/javascript");
                        if (mListener != null) {
                            mListener.onUserscriptInstallRequested(u, guessName);
                        }
                        return true;
                    }
                    return handleUrlIntercept(u);
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.isLoading = true;
                tab.url = url;
                // Install the phase bootstrap first; it reports document-start / end / idle
                // back to us when the page really reaches them.
                mUserscriptManager.injectBootstrap(view, url);
                AdBlockManager.getInstance(mContext).injectElementHidingCss(view, url);
                if (mDataStore != null && mDataStore.isDoNotTrackEnabled()) {
                    view.evaluateJavascript("try{Object.defineProperty(navigator,'doNotTrack',{get:function(){return'1';},configurable:true,enumerable:true});}catch(e){}", null);
                }
                if (mActiveTab != null && mActiveTab.id == tab.id && mListener != null) {
                    mListener.onUrlChanged(url, tab.title);
                    mListener.onProgressChanged(0, true);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.isLoading = false;
                tab.url = url;
                // Userscript phases are driven by the page bootstrap now; onPageFinished is
                // only a safety net for pages whose bootstrap never fired (e.g. JS disabled).
                mUserscriptManager.injectBootstrap(view, url);
                AdBlockManager.getInstance(mContext).injectElementHidingCss(view, url);

                if (Build.VERSION.SDK_INT >= 21) {
                    CookieManager.getInstance().flush();
                }

                if (mDarkMode) {
                    injectDarkModeCss(view);
                }

                if (url != null && (url.equals(HOME_URL) || url.endsWith("/homepage/index.html"))) {
                    pushBookmarksToActiveHomeTabs();
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
            public void onReceivedIcon(WebView view, Bitmap icon) {
                super.onReceivedIcon(view, icon);
                if (icon != null && view != null && view.getUrl() != null && !mIncognitoMode) {
                    FaviconManager.getInstance(mContext).cacheVisitedFavicon(view.getUrl(), icon);
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                emitLog("[JS_ALERT] " + message);
                if (getControlMode() == AgentOverlayView.MODE_AGENT) {
                    result.confirm();
                    return true;
                }
                showHumanJsAlert(url, message, result);
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                if (getControlMode() == AgentOverlayView.MODE_AGENT) {
                    emitLog("[JS_CONFIRM] " + message + " -> AUTO_CONFIRM");
                    result.confirm();
                    return true;
                }
                emitLog("[JS_CONFIRM] " + message);
                showHumanJsConfirm(url, message, result);
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
                if (getControlMode() == AgentOverlayView.MODE_AGENT) {
                    emitLog("[JS_PROMPT] " + message + " -> CONFIRM(" + defaultValue + ")");
                    result.confirm(defaultValue != null ? defaultValue : "");
                    return true;
                }
                emitLog("[JS_PROMPT] " + message);
                showHumanJsPrompt(url, message, defaultValue, result);
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (resultMsg == null) return false;
                try {
                    TabSession newTab = createNewTabBlank(true);
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    if (transport != null) {
                        transport.setWebView(newTab.webView);
                        resultMsg.sendToTarget();
                        return true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to handle onCreateWindow", e);
                }
                return false;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (window == null) return;
                for (TabSession t : mTabs) {
                    if (t.webView == window) {
                        closeTab(t.id);
                        break;
                    }
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (mCustomView != null) {
                    if (callback != null) {
                        try {
                            callback.onCustomViewHidden();
                        } catch (Exception ignored) {}
                    }
                    return;
                }
                mCustomView = view;
                mCustomViewCallback = callback;
                if (mListener != null) {
                    mListener.onShowCustomView(view, callback);
                }
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(final String url, final String userAgent, final String contentDisposition, final String mimeType, final long contentLength) {
                requestDownload(url, null, userAgent, contentDisposition, mimeType, contentLength);
            }
        });
    }

    /**
     * Unified download entry point: guesses clean UTF-8 filename/size/MIME, records trigger receipt
     * for Agent perception, releases anti-touch lock, and opens the DownloadConfirmSheet.
     */
    public String requestDownload(
        final String url,
        final String customFileName,
        final String userAgent,
        final String contentDisposition,
        final String mimeType,
        final long contentLength
    ) {
        final String fileName = (customFileName != null && !customFileName.trim().isEmpty())
            ? com.acid.browser.download.HttpProbe.decodeAndSanitizeFileName(customFileName.trim())
            : DownloadCoordinator.smartGuessFileName(url, contentDisposition, mimeType);
        final String resolvedMime = DownloadCoordinator.resolveMimeType(fileName, mimeType);
        final long resolvedLength = contentLength > 0
            ? contentLength
            : DownloadCoordinator.extractContentLengthFromUrlQuery(url);
        emitLog("[DOWNLOAD] Triggered: " + fileName + " (" + resolvedLength + " B, " + resolvedMime + ")");

        // Intercept userscript downloads directly into the script installer.
        if (fileName.endsWith(".user.js") || (url != null && url.contains(".user.js"))) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mListener != null) {
                        mListener.onUserscriptInstallRequested(url, fileName);
                    }
                }
            });
            return fileName + " (userscript installer)";
        }

        mTriggeredDownloads++;
        mLastTriggeredDownloadInfo = fileName + " ("
            + com.acid.browser.download.DownloadItem.formatSize(resolvedLength)
            + ", " + resolvedMime + ")";

        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (getControlMode() == AgentOverlayView.MODE_AGENT) {
                    setControlMode(AgentOverlayView.MODE_HUMAN, "");
                }
                if (mListener != null) {
                    mListener.onDownloadRequested(url, fileName, userAgent, contentDisposition, resolvedMime, resolvedLength);
                } else {
                    mDownloadCoordinator.startDownload(url, fileName, userAgent, contentDisposition, resolvedMime, resolvedLength);
                }
            }
        });
        return mLastTriggeredDownloadInfo;
    }

    public static int resolveEffectiveAppLaunchPolicy(int configuredPolicy, int controlMode, int agentBoundTabId) {
        if (controlMode == AgentOverlayView.MODE_AGENT) {
            return AcidSettings.APP_LAUNCH_BLOCK;
        }
        if (configuredPolicy < AcidSettings.APP_LAUNCH_BLOCK || configuredPolicy > AcidSettings.APP_LAUNCH_ALLOW) {
            return AcidSettings.APP_LAUNCH_BLOCK;
        }
        return configuredPolicy;
    }

    public int getEffectiveAppLaunchPolicy() {
        int configured = mDataStore != null ? mDataStore.getAppLaunchPolicy() : AcidSettings.APP_LAUNCH_BLOCK;
        int controlMode = mOverlayView != null ? mOverlayView.getControlMode() : AgentOverlayView.MODE_HUMAN;
        return resolveEffectiveAppLaunchPolicy(configured, controlMode, mAgentBoundTabId);
    }

    public void recordBlockedDeepLink(String url) {
        mBlockedDeepLinks++;
        mLastBlockedDeepLink = url != null ? url : "";
        emitLog("[HIJACK_SHIELD] Blocked DeepLink -> " + url + " (Total: " + mBlockedDeepLinks + ")");
        if (mListener != null) {
            mListener.onDeepLinkBlocked(url, mBlockedDeepLinks);
        }
    }

    private boolean handleUrlIntercept(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.startsWith("acid://") || lower.startsWith("via://")) {
            loadUrl(normalizeUrl(url));
            return true;
        }
        // Intercept known cloud-drive direct CDN file streams before WebView sends Chrome UA (which gets HTTP 403)
        if (DownloadCoordinator.isCloudDriveDirectStreamUrl(url)) {
            requestDownload(url, null, null, null, null, -1);
            return true;
        }
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file://")
                || lower.startsWith("about:") || lower.startsWith("javascript:")
                || lower.startsWith("data:") || lower.startsWith("blob:")) {
            return false;
        }

        // Parse external app Intent (supports both intent:// URI_INTENT_SCHEME and custom schemes like bilibili://, bdnetdisk://, etc.)
        Intent intent = null;
        String fallbackUrl = null;
        try {
            if (lower.startsWith("intent:")) {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    fallbackUrl = intent.getStringExtra("browser_fallback_url");
                }
            } else {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            if (intent != null) {
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                if (Build.VERSION.SDK_INT >= 15) {
                    intent.setSelector(null);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse external app URI: " + url, e);
        }

        int policy = getEffectiveAppLaunchPolicy();

        // Level 1: Complete Block (also enforced automatically whenever Agent mode is active)
        if (policy == AcidSettings.APP_LAUNCH_BLOCK) {
            recordBlockedDeepLink(url);
            return true;
        }

        // Check if an installed app on the device can handle this Intent
        PackageManager pm = mContext != null ? mContext.getPackageManager() : null;
        android.content.pm.ResolveInfo ri = (intent != null && pm != null) ? pm.resolveActivity(intent, 0) : null;
        if (ri == null) {
            if (fallbackUrl != null && (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                loadUrl(fallbackUrl);
                return true;
            }
            recordBlockedDeepLink(url);
            return true;
        }

        String appName = "";
        try {
            CharSequence label = ri.loadLabel(pm);
            if (label != null) appName = label.toString().trim();
        } catch (Exception ignored) {}
        if (appName.isEmpty()) {
            String scheme = Uri.parse(url).getScheme();
            appName = scheme != null ? scheme : "第三方应用";
        }

        // Level 3: Allow Without Blocking
        if (policy == AcidSettings.APP_LAUNCH_ALLOW) {
            try {
                mContext.startActivity(intent);
                emitLog("[HIJACK_SHIELD] Allowed launching external app (" + appName + ") -> " + url);
            } catch (Exception e) {
                Log.w(TAG, "Failed to launch external app: " + url, e);
            }
            return true;
        }

        // Level 2: Ask Confirmation Dialog ("确定" / "取消")
        long now = System.currentTimeMillis();
        if (url.equals(mLastAskedAppLaunchUrl) && (now - mLastAskedAppLaunchTimeMs) < 1500) {
            return true;
        }
        mLastAskedAppLaunchUrl = url;
        mLastAskedAppLaunchTimeMs = now;
        if (mListener != null) {
            mListener.onAppLaunchConfirmRequested(url, appName, intent);
        } else {
            recordBlockedDeepLink(url);
        }
        return true;
    }

    private String mDefaultUserAgent = null;

    public String getDefaultUserAgent() {
        if (mDefaultUserAgent == null && mContext != null) {
            try {
                String raw = WebSettings.getDefaultUserAgent(mContext);
                if (raw != null) {
                    // Standardize to standalone Chrome: strip embedded WebView markers "; wv" and "Version/X.X"
                    raw = raw.replace("; wv", "").replaceAll("Version/\\d+\\.\\d+\\s*", "");
                }
                mDefaultUserAgent = raw;
            } catch (Exception e) {
                mDefaultUserAgent = UA_MOBILE;
            }
        }
        return mDefaultUserAgent != null ? mDefaultUserAgent : UA_MOBILE;
    }

    private void applyIdentityToSettings(WebView wv, boolean desktopMode) {
        if (wv == null) return;
        WebSettings s = wv.getSettings();
        if (desktopMode) {
            s.setUserAgentString(UA_DESKTOP);
        } else {
            // Restore genuine native WebView default UA - "do not spoof, just be yourself"
            String defaultUa = getDefaultUserAgent();
            s.setUserAgentString(defaultUa != null ? defaultUa : null);
        }
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(desktopMode);
    }

    private void applyIncognitoToSettings(WebView wv, boolean incognito) {
        if (wv == null) return;
        WebSettings s = wv.getSettings();
        s.setCacheMode(incognito ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
        s.setSaveFormData(!incognito);
    }

    public boolean isIncognitoMode() {
        return mIncognitoMode;
    }

    public void setIncognitoMode(boolean incognito) {
        boolean wasIncognito = mIncognitoMode;
        mIncognitoMode = incognito;
        for (TabSession t : mTabs) {
            applyIncognitoToSettings(t.webView, incognito);
        }
        if (wasIncognito && !incognito) {
            clearAllCache();
            if (Build.VERSION.SDK_INT >= 21) {
                CookieManager.getInstance().removeSessionCookies(null);
                CookieManager.getInstance().flush();
            }
        }
    }

    private void showHumanJsAlert(String url, String message, final JsResult result) {
        JsDialogHelper.showAlert(mContext, mDarkMode, url, message, result);
    }

    private void showHumanJsConfirm(String url, String message, final JsResult result) {
        JsDialogHelper.showConfirm(mContext, mDarkMode, url, message, result);
    }

    private void showHumanJsPrompt(String url, String message, String defaultValue, final JsPromptResult result) {
        JsDialogHelper.showPrompt(mContext, mDarkMode, url, message, defaultValue, result);
    }

    private void applyDarkModeToSettings(WebView wv, boolean dark) {
        wv.setBackgroundColor(AcidTheme.bg(dark));
        WebSettings s = wv.getSettings();
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+ 原生 Force Dark (通过反射调用，兼容 SDK 23 基础编译)
            try {
                java.lang.reflect.Method setForceDark = s.getClass().getMethod("setForceDark", int.class);
                // 2 = WebSettings.FORCE_DARK_ON, 0 = WebSettings.FORCE_DARK_OFF
                setForceDark.invoke(s, dark ? 2 : 0);
            } catch (Exception e) {
                // Expected on ROMs that strip the hidden API; fall back to injected CSS.
                Log.w(TAG, "WebSettings.setForceDark unavailable; relying on injected dark CSS", e);
            }

            try {
                java.lang.reflect.Method setForceDarkStrategy = s.getClass().getMethod("setForceDarkStrategy", int.class);
                // 1 = DARK_STRATEGY_USER_AGENT_DARKENING_ONLY (智能变暗，不影响图片和原色)
                setForceDarkStrategy.invoke(s, 1);
            } catch (Exception e) {
                Log.w(TAG, "WebSettings.setForceDarkStrategy unavailable", e);
            }
        }
    }

    private void injectDarkModeCss(WebView wv) {
        if (wv == null) return;
        boolean dark = mDarkMode;
        String js = "javascript:(function(){" +
            "var dark=" + (dark ? "true" : "false") + ";" +
            "if(document.documentElement){" +
            "  document.documentElement.style.colorScheme=dark?'dark':'';" +
            "}" +
            "var meta=document.querySelector('meta[name=\"color-scheme\"]');" +
            "if(dark){" +
            "  if(!meta){" +
            "    meta=document.createElement('meta');" +
            "    meta.name='color-scheme';" +
            "    (document.head||document.documentElement).appendChild(meta);" +
            "  }" +
            "  meta.content='dark light';" +
            "  var el=document.getElementById('__acid_dark_mode');" +
            "  if(!el){" +
            "    el=document.createElement('style');" +
            "    el.id='__acid_dark_mode';" +
            "    el.textContent='@media(prefers-color-scheme:light){:root{color-scheme:dark!important;}}html,body{background-color:#000000!important;}';" +
            "    (document.head||document.documentElement).appendChild(el);" +
            "  }" +
            "}else{" +
            "  if(meta)meta.content='light';" +
            "  var el=document.getElementById('__acid_dark_mode');" +
            "  if(el)el.remove();" +
            "}" +
            "})()";
        wv.evaluateJavascript(js, null);
    }

    public String normalizeUrl(String raw) {
        if (raw == null) return getHomeUrl();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return getHomeUrl();
        if ("acid://home".equalsIgnoreCase(trimmed) || "about:home".equalsIgnoreCase(trimmed)) {
            return getHomeUrl();
        }

        // Native search protocol: acid://search?q=..., about:search?q=..., via://search?q=...
        if (trimmed.startsWith("acid://search") || trimmed.startsWith("about:search") || trimmed.startsWith("via://search")) {
            String query = "";
            try {
                int qIdx = trimmed.indexOf("?q=");
                if (qIdx < 0) qIdx = trimmed.indexOf("&q=");
                if (qIdx < 0) qIdx = trimmed.indexOf("?wd=");
                if (qIdx < 0) qIdx = trimmed.indexOf("&wd=");
                if (qIdx >= 0) {
                    int valStart = trimmed.indexOf('=', qIdx) + 1;
                    int ampIdx = trimmed.indexOf('&', valStart);
                    String rawVal = ampIdx > 0 ? trimmed.substring(valStart, ampIdx) : trimmed.substring(valStart);
                    query = java.net.URLDecoder.decode(rawVal, "UTF-8").trim();
                }
            } catch (Exception e) {
                query = "";
            }
            if (!query.isEmpty()) {
                if (mDataStore != null) {
                    return mDataStore.buildSearchUrl(query);
                }
                try {
                    return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8");
                } catch (Exception e) {
                    return "https://www.google.com/search?q=" + query;
                }
            }
            return getHomeUrl();
        }

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://") || trimmed.startsWith("javascript:") || trimmed.startsWith("data:") || trimmed.startsWith("about:")) {
            return trimmed;
        }
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            if (mDataStore != null) {
                return mDataStore.buildSearchUrl(trimmed);
            }
            try {
                return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8");
            } catch (Exception e) {
                return "https://www.google.com/search?q=" + trimmed;
            }
        }
        return "https://" + trimmed;
    }

    public boolean isHomeUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (trimmed.isEmpty() || "acid://home".equalsIgnoreCase(trimmed) || "about:home".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (HOME_URL.equals(trimmed)) {
            return true;
        }
        String effectiveHome = getHomeUrl();
        if (trimmed.equals(effectiveHome)) {
            return true;
        }
        if (trimmed.contains("/acid_workspace/homepage/index.html") || trimmed.startsWith("workspace://homepage/index.html")) {
            return true;
        }
        return false;
    }

    public boolean isCustomViewShowing() {
        return mCustomView != null;
    }

    public boolean hideCustomView() {
        if (mCustomView != null) {
            if (mCustomViewCallback != null) {
                try {
                    mCustomViewCallback.onCustomViewHidden();
                } catch (Exception ignored) {}
                mCustomViewCallback = null;
            }
            mCustomView = null;
            if (mListener != null) {
                mListener.onHideCustomView();
            }
            return true;
        }
        return false;
    }

    public SkillManager getSkillManager() {
        return mSkillManager;
    }

    private WebResourceResponse interceptFaviconRequest(Uri reqUri) {
        if (reqUri == null) return null;
        String path = reqUri.getPath();
        if (path != null && path.endsWith("/preload.js")) {
            String js = "window.__INITIAL_BOOKMARKS=" + buildBookmarksJsonWithCachedIcons().toString() + ";";
            byte[] jsBytes = js.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            WebResourceResponse resp = new WebResourceResponse(
                "application/javascript", "UTF-8", new java.io.ByteArrayInputStream(jsBytes)
            );
            if (Build.VERSION.SDK_INT >= 21) {
                java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
                headers.put("Cache-Control", "no-store");
                headers.put("Access-Control-Allow-Origin", "*");
                resp.setResponseHeaders(headers);
            }
            return resp;
        }
        return FaviconManager.getInstance(mContext).interceptFaviconWebRequest(reqUri);
    }
    private org.json.JSONArray buildBookmarksJsonWithCachedIcons() {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (mDataStore == null) return arr;
        List<BrowserDataStore.EntryItem> items = mDataStore.loadBookmarks();
        FaviconManager fm = FaviconManager.getInstance(mContext);
        if (items != null) {
            for (BrowserDataStore.EntryItem it : items) {
                if (it == null) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("title", it.title);
                    o.put("url", it.url);
                    String dataUri = fm.getFaviconDataUriFast(it.url);
                    if (dataUri != null) {
                        o.put("cachedIcon", dataUri);
                    }
                    arr.put(o);
                } catch (Exception ignored) {}
            }
        }
        return arr;
    }

    public void pushBookmarksToActiveHomeTabs() {
        if (mDataStore == null) return;
        org.json.JSONArray arr = buildBookmarksJsonWithCachedIcons();
        final String js = "try{if(window.__updateAcidBookmarks){window.__updateAcidBookmarks(" + arr.toString() + ");}}catch(e){}";
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (TabSession t : mTabs) {
                    if (t != null && t.webView != null && t.url != null
                            && (t.url.equals(HOME_URL) || t.url.endsWith("/homepage/index.html"))) {
                        if (Build.VERSION.SDK_INT >= 19) {
                            t.webView.evaluateJavascript(js, null);
                        } else {
                            t.webView.loadUrl("javascript:" + js);
                        }
                    }
                }
            }
        });
    }

    public String getHomeUrl() {
        if (mDataStore != null) {
            return mDataStore.getEffectiveHomeUrl();
        }
        return HOME_URL;
    }

    public void clearAllCache() {
        for (TabSession t : mTabs) {
            if (t.webView != null) {
                t.webView.clearCache(true);
            }
        }
    }

    public void clearAllHistory() {
        for (TabSession t : mTabs) {
            if (t.webView != null) {
                t.webView.clearHistory();
            }
        }
    }

    public BrowserDataStore getDataStore() {
        return mDataStore;
    }

    public void setWebDebuggingEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                WebView.setWebContentsDebuggingEnabled(enabled);
            } catch (Exception e) {
                Log.w(TAG, "Failed to toggle web contents debugging", e);
            }
        }
    }

    public void setBlockImages(boolean block) {
        for (TabSession t : mTabs) {
            if (t.webView != null) {
                WebSettings s = t.webView.getSettings();
                s.setLoadsImagesAutomatically(!block);
                s.setBlockNetworkImage(block);
            }
        }
        if (mActiveTab != null && mActiveTab.webView != null) {
            mActiveTab.webView.reload();
        }
    }

    @Override
    public void loadUrl(String url) {
        final String finalUrl = normalizeUrl(url);
        if (mActiveTab != null) {
            mActiveTab.webView.loadUrl(finalUrl);
        }
    }

    public void applySettingsHotReload() {
        if (mDataStore == null) return;
        setWebDebuggingEnabled(mDataStore.isWebDebuggingEnabled());
        setBlockImages(mDataStore.isBlockImagesEnabled());
        AdBlockManager.getInstance(mContext).syncFromDataStore();
        if (mDarkMode != mDataStore.isDarkMode()) {
            setDarkMode(mDataStore.isDarkMode());
        }
        if (mListener != null) {
            mListener.onSettingsReloaded();
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
        mAutomationDriver.evaluateJsSync("window.__acidBridge && window.__acidBridge.setSomEnabled(" + enabled + ");", 1500);
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
            applyDarkModeToSettings(t.webView, dark);
            injectDarkModeCss(t.webView);
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

    @Override
    public int getTriggeredDownloadsCount() {
        return mTriggeredDownloads;
    }

    @Override
    public String getLastTriggeredDownloadInfo() {
        return mLastTriggeredDownloadInfo;
    }

    /**
     * Installs a userscript after validation.
     *
     * @return null on success, otherwise a human-readable rejection reason.
     */
    public String installUserscript(String fileName, String content) {
        String error = mUserscriptManager.installUserscript(fileName, content);
        if (error == null) {
            emitLog("[PLUGIN] Userscript installed: " + fileName);
        } else {
            emitLog("[PLUGIN] Userscript rejected: " + error);
        }
        return error;
    }

    /** Runs a script immediately on the active tab, honouring its {@code @match}. */
    public boolean runUserscriptNow(UserscriptMeta meta) {
        TabSession tab = getActiveTab();
        if (tab == null || tab.webView == null) return false;
        boolean ok = mUserscriptManager.runScriptNow(tab.webView, meta);
        emitLog(ok
            ? "[PLUGIN] Ran " + meta.fileName
            : "[PLUGIN] " + meta.fileName + " does not apply to this page");
        return ok;
    }

    /** Menu commands registered by userscripts on the active page. */
    public void queryUserscriptCommands(UserscriptManager.MenuCommandsCallback cb) {
        TabSession tab = getActiveTab();
        mUserscriptManager.queryMenuCommands(tab != null ? tab.webView : null, cb);
    }

    public void runUserscriptCommand(UserscriptManager.MenuCommand cmd) {
        TabSession tab = getActiveTab();
        if (tab != null) mUserscriptManager.runMenuCommand(tab.webView, cmd);
    }
}
