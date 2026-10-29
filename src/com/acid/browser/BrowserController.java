package com.acid.browser;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

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
        void onDownloadRequested(String url, String fileName, String userAgent, String contentDisposition, String mimeType, long contentLength);
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
        public long downloadId;
        public String fileName;
        public final String url;
        public final String mimeType;
        public long contentLength;
        public long downloadedBytes;
        public int status;
        public final long timestamp;

        public DownloadItem(long downloadId, String fileName, String url, String mimeType, long contentLength, long downloadedBytes, int status, long timestamp) {
            this.downloadId = downloadId;
            this.fileName = (fileName != null && !fileName.isEmpty()) ? fileName : "file";
            this.url = url != null ? url : "";
            this.mimeType = mimeType != null ? mimeType : "";
            this.contentLength = contentLength;
            this.downloadedBytes = downloadedBytes;
            this.status = status;
            this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        }

        public DownloadItem(String url, String mimeType, long contentLength) {
            this(0, "download", url, mimeType, contentLength, 0, 0, System.currentTimeMillis());
        }

        public static String formatSize(long bytes) {
            if (bytes <= 0) return "未知大小";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }

        public static final int CAT_ALL = 0;
        public static final int CAT_APK = 1;
        public static final int CAT_ARCHIVE = 2;
        public static final int CAT_DOC = 3;
        public static final int CAT_IMAGE = 4;
        /**
         * Legacy bucket that used to hold both audio and video. Never returned by
         * {@link #getCategory()} any more, but kept so old serialized records and
         * external callers still resolve to a valid value.
         */
        @Deprecated
        public static final int CAT_MEDIA = 5;
        public static final int CAT_VIDEO = 6;
        public static final int CAT_AUDIO = 7;
        public static final int CAT_OTHER = 8;

        private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".m4v", ".3gp", ".wmv", ".mpg", ".mpeg", ".ts"
        };
        private static final String[] AUDIO_EXT = {
            ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".amr", ".mid", ".midi"
        };
        private static final String[] ARCHIVE_EXT = {
            ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz", ".zst", ".tgz", ".tbz", ".lz", ".lzma"
        };
        private static final String[] DOC_EXT = {
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv",
            ".json", ".xml", ".md", ".log", ".rtf", ".epub", ".html", ".htm"
        };
        private static final String[] IMAGE_EXT = {
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".svg", ".heic", ".heif", ".avif", ".ico"
        };

        private static boolean matches(String name, String[] exts) {
            for (String e : exts) {
                if (name.endsWith(e)) return true;
            }
            return false;
        }

        /**
         * Classifies the download by extension first, then MIME type, and finally
         * falls back to {@link #CAT_OTHER}. Unknown files used to be reported as
         * documents, which made .exe / .iso / .bin show up under "文档".
         */
        public int getCategory() {
            String name = fileName != null ? fileName.toLowerCase(java.util.Locale.US) : "";
            String mime = mimeType != null ? mimeType.toLowerCase(java.util.Locale.US) : "";

            if (name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")
                || name.endsWith(".apkm") || mime.contains("package-archive")) {
                return CAT_APK;
            }
            if (matches(name, ARCHIVE_EXT) || mime.contains("zip") || mime.contains("compressed")
                || mime.contains("x-tar") || mime.contains("x-7z") || mime.contains("x-rar")) {
                return CAT_ARCHIVE;
            }
            if (matches(name, DOC_EXT) || mime.contains("pdf") || mime.contains("document")
                || mime.contains("sheet") || mime.contains("presentation") || mime.startsWith("text/")) {
                return CAT_DOC;
            }
            if (matches(name, IMAGE_EXT) || mime.startsWith("image/")) {
                return CAT_IMAGE;
            }
            if (matches(name, VIDEO_EXT) || mime.startsWith("video/")) {
                return CAT_VIDEO;
            }
            if (matches(name, AUDIO_EXT) || mime.startsWith("audio/")) {
                return CAT_AUDIO;
            }
            return CAT_OTHER;
        }

        /** Short human label used in the list subtitle and the filter pills. */
        public String getCategoryLabel() {
            switch (getCategory()) {
                case CAT_APK: return "安装包";
                case CAT_ARCHIVE: return "压缩包";
                case CAT_DOC: return "文档";
                case CAT_IMAGE: return "图片";
                case CAT_VIDEO: return "视频";
                case CAT_AUDIO: return "音频";
                default: return "其它";
            }
        }

        /** Vector glyph for the list row icon. */
        public int getCategoryIcon() {
            switch (getCategory()) {
                case CAT_APK: return AcidIcons.ICON_FILE_APK;
                case CAT_ARCHIVE: return AcidIcons.ICON_FILE_ARCHIVE;
                case CAT_DOC: return AcidIcons.ICON_FILE_DOC;
                case CAT_IMAGE: return AcidIcons.ICON_FILE_IMAGE;
                case CAT_VIDEO: return AcidIcons.ICON_FILE_VIDEO;
                case CAT_AUDIO: return AcidIcons.ICON_FILE_AUDIO;
                default: return AcidIcons.ICON_FILE_OTHER;
            }
        }

        public int getProgressPercent() {
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 100;
            if (contentLength > 0 && downloadedBytes > 0) {
                int p = (int) ((downloadedBytes * 100L) / contentLength);
                return Math.max(0, Math.min(100, p));
            }
            return status == DownloadManager.STATUS_RUNNING ? 5 : 0;
        }

        public String getStatusLabel() {
            switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    return "已完成";
                case DownloadManager.STATUS_RUNNING:
                    return "下载中";
                case DownloadManager.STATUS_PENDING:
                    return "排队中";
                case DownloadManager.STATUS_PAUSED:
                    return "已暂停";
                case DownloadManager.STATUS_FAILED:
                    return "下载失败";
                default:
                    return "已保存";
            }
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
    private BrowserDataStore mDataStore = null;

    public void setDataStore(BrowserDataStore dataStore) {
        mDataStore = dataStore;
        if (mDataStore != null) {
            List<DownloadItem> saved = mDataStore.loadDownloads();
            synchronized (mDownloads) {
                mDownloads.clear();
                mDownloads.addAll(saved);
            }
        }
    }

    public BrowserController(
        Context context,
        FrameLayout webViewContainer,
        AgentOverlayView overlayView,
        BrowserStateListener listener
    ) {
        this(context, webViewContainer, overlayView, listener, false);
    }

    public BrowserController(
        Context context,
        FrameLayout webViewContainer,
        AgentOverlayView overlayView,
        BrowserStateListener listener,
        boolean isDarkMode
    ) {
        mContext = context;
        mWebViewContainer = webViewContainer;
        mOverlayView = overlayView;
        mListener = listener;
        mDarkMode = isDarkMode;

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
            createNewTab(HOME_URL, true);
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
        applyDarkModeToSettings(wv, mDarkMode);
        wv.addJavascriptInterface(mAutomationDriver.getNativeHostBridge(), "__acidNativeHost");

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
            public void onDownloadStart(final String url, final String userAgent, final String contentDisposition, final String mimeType, final long contentLength) {
                final String fileName = smartGuessFileName(url, contentDisposition, mimeType);
                final String resolvedMime = resolveMimeType(fileName, mimeType);
                emitLog("[DOWNLOAD] Triggered: " + fileName + " (" + contentLength + " B, " + resolvedMime + ")");
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mListener != null) {
                            mListener.onDownloadRequested(url, fileName, userAgent, contentDisposition, resolvedMime, contentLength);
                        } else {
                            startDownload(url, fileName, userAgent, contentDisposition, resolvedMime, contentLength);
                        }
                    }
                });
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
        if (raw == null) return HOME_URL;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return HOME_URL;
        if ("acid://home".equalsIgnoreCase(trimmed) || "about:home".equalsIgnoreCase(trimmed)) {
            return HOME_URL;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://") || trimmed.startsWith("javascript:") || trimmed.startsWith("data:")) {
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

    public String evaluateJsAsync(String code, long timeoutMs) {
        return mAutomationDriver.evaluateJsAsync(code, timeoutMs);
    }

    public JSONObject waitSync(String condition, String target, long quietMs, long timeoutMs) {
        return mAutomationDriver.waitConditionSync(condition, target, quietMs, timeoutMs);
    }

    public JSONObject clickTargetSync(JSONObject targetOpts, boolean useNative) {
        return mAutomationDriver.clickTargetSync(targetOpts, useNative);
    }

    public JSONObject fillTargetSync(JSONObject targetOpts, String text, boolean clear, boolean submit) {
        return mAutomationDriver.fillSync(targetOpts, text, clear, submit);
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
            applyDarkModeToSettings(t.webView, dark);
            injectDarkModeCss(t.webView);
        }
    }

    public static String smartGuessFileName(String url, String contentDisposition, String mimeType) {
        String effectiveMime = mimeType;
        if (effectiveMime != null) {
            String lower = effectiveMime.trim().toLowerCase();
            if (lower.equals("application/octet-stream") ||
                lower.equals("binary/octet-stream") ||
                lower.equals("application/force-download") ||
                lower.equals("*/*") ||
                lower.isEmpty()) {
                effectiveMime = null;
            }
        }

        String guessed = URLUtil.guessFileName(url, contentDisposition, effectiveMime);

        // Extra guard: If URL path or contentDisposition has an explicit known extension,
        // but URLUtil forced a .bin extension, restore the original extension.
        if (guessed != null && guessed.toLowerCase().endsWith(".bin")) {
            String candidate = null;
            if (contentDisposition != null) {
                int fnIndex = contentDisposition.toLowerCase().indexOf("filename=");
                if (fnIndex >= 0) {
                    String part = contentDisposition.substring(fnIndex + 9).trim();
                    if (part.startsWith("\"") && part.length() > 1) {
                        int endQuote = part.indexOf('\"', 1);
                        if (endQuote > 1) candidate = part.substring(1, endQuote);
                    } else {
                        int semi = part.indexOf(';');
                        candidate = semi > 0 ? part.substring(0, semi).trim() : part;
                    }
                }
            }
            if (candidate == null && url != null) {
                try {
                    String path = Uri.parse(url).getPath();
                    if (path != null) {
                        int slash = path.lastIndexOf('/');
                        if (slash >= 0 && slash < path.length() - 1) {
                            candidate = path.substring(slash + 1);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to derive a file name from the URL", e);
                }
            }

            if (candidate != null) {
                int dot = candidate.lastIndexOf('.');
                if (dot >= 0 && dot < candidate.length() - 1) {
                    String ext = candidate.substring(dot).toLowerCase();
                    if (!ext.equals(".bin")) {
                        if (guessed.toLowerCase().endsWith(ext + ".bin")) {
                            guessed = guessed.substring(0, guessed.length() - 4);
                        } else if (guessed.toLowerCase().endsWith(".bin")) {
                            guessed = guessed.substring(0, guessed.length() - 4) + ext;
                        }
                    }
                }
            }
        }

        return guessed;
    }

    public static String resolveMimeType(String fileName, String mimeType) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase();
                if ("apk".equals(ext)) {
                    return "application/vnd.android.package-archive";
                }
                String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (guessed != null && !guessed.isEmpty()) {
                    return guessed;
                }
            }
        }
        if (mimeType != null && !mimeType.isEmpty() && !"*/*".equals(mimeType) && !"application/octet-stream".equals(mimeType)) {
            return mimeType;
        }
        return "*/*";
    }

    public List<DownloadItem> getDownloads() {
        syncDownloadStatus();
        synchronized (mDownloads) {
            return new ArrayList<DownloadItem>(mDownloads);
        }
    }

    public void startDownload(String url, String fileName, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (url == null || url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(mContext, "当前暂不支持下载此协议资源: " + url, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(mContext, "系统下载管理器不可用", Toast.LENGTH_SHORT).show();
                return;
            }

            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = smartGuessFileName(url, contentDisposition, mimeType);
            }

            String effectiveMime = resolveMimeType(fileName, mimeType);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (effectiveMime != null && !effectiveMime.isEmpty() && !"*/*".equals(effectiveMime)) {
                request.setMimeType(effectiveMime);
            }

            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }

            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setTitle(fileName);
            request.setDescription("Acid Browser 下载");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            long downloadId = dm.enqueue(request);

            DownloadItem item = new DownloadItem(
                downloadId,
                fileName,
                url,
                effectiveMime,
                contentLength,
                0,
                DownloadManager.STATUS_RUNNING,
                System.currentTimeMillis()
            );

            synchronized (mDownloads) {
                mDownloads.add(0, item);
            }

            if (mDataStore != null) {
                mDataStore.saveDownloads(mDownloads);
            }

            emitLog("[DOWNLOAD] Enqueued: " + fileName + " (id=" + downloadId + ") -> /Download/");
            Toast.makeText(mContext, "已开始下载: " + fileName + "\n保存至手机 Download 目录", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            emitLog("[DOWNLOAD] Failed to enqueue: " + e.getMessage());
            Toast.makeText(mContext, "下载发起失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void onDownloadCompleted(long downloadId) {
        syncDownloadStatus();
        synchronized (mDownloads) {
            for (DownloadItem it : mDownloads) {
                if (it.downloadId == downloadId) {
                    emitLog("[DOWNLOAD] Complete: " + it.fileName);
                    break;
                }
            }
        }
    }

    public void syncDownloadStatus() {
        DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;

        boolean changed = false;
        synchronized (mDownloads) {
            for (DownloadItem it : mDownloads) {
                if (it.downloadId <= 0) continue;
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(it.downloadId);
                Cursor c = null;
                try {
                    c = dm.query(q);
                    if (c != null && c.moveToFirst()) {
                        int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int bytesCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);

                        if (statusCol != -1) {
                            int status = c.getInt(statusCol);
                            if (it.status != status) {
                                it.status = status;
                                changed = true;
                            }
                        }
                        if (bytesCol != -1) {
                            long bytes = c.getLong(bytesCol);
                            if (it.downloadedBytes != bytes) {
                                it.downloadedBytes = bytes;
                                changed = true;
                            }
                        }
                        if (totalCol != -1) {
                            long total = c.getLong(totalCol);
                            if (total > 0 && (it.contentLength <= 0 || it.contentLength != total)) {
                                it.contentLength = total;
                                changed = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist download state", e);
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to close download-status cursor", e);
                        }
                    }
                }
            }
        }
        if (changed && mDataStore != null) {
            mDataStore.saveDownloads(mDownloads);
        }
    }

    public void openDownloadedFile(DownloadItem it) {
        openDownloadedFile(it, false);
    }

    public void openDownloadedFile(DownloadItem it, boolean forceChooser) {
        if (it == null) return;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(dir, it.fileName);

            Uri uri = null;
            if (file.exists()) {
                uri = AcidFileProvider.getUriForFile(file);
            }
            if (uri == null) {
                DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null && it.downloadId > 0) {
                    uri = dm.getUriForDownloadedFile(it.downloadId);
                }
            }

            if (uri == null) {
                Toast.makeText(mContext, "未找到下载文件: " + it.fileName, Toast.LENGTH_SHORT).show();
                return;
            }

            String mime = it.mimeType;
            String lower = it.fileName != null ? it.fileName.toLowerCase() : "";
            if (lower.endsWith(".apk")) {
                mime = "application/vnd.android.package-archive";
            } else if (mime == null || mime.isEmpty() || "*/*".equals(mime) || "application/octet-stream".equals(mime)) {
                int dot = lower.lastIndexOf('.');
                if (dot >= 0 && dot < lower.length() - 1) {
                    String ext = lower.substring(dot + 1);
                    String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    if (guessed != null) {
                        mime = guessed;
                    }
                }
            }
            if (mime == null || mime.isEmpty()) {
                mime = "*/*";
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            if (forceChooser) {
                Intent chooser = Intent.createChooser(intent, "选择打开方式: " + it.fileName);
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(chooser);
            } else {
                mContext.startActivity(intent);
            }
        } catch (ActivityNotFoundException anfe) {
            Toast.makeText(mContext, "系统未找到可打开此类型文件的应用 (" + it.fileName + ")", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(mContext, "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void openDownloadFolder() {
        try {
            Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } catch (Exception e1) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload");
                intent.setDataAndType(uri, "vnd.android.document/directory");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                mContext.startActivity(intent);
            } catch (Exception e2) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                Toast.makeText(mContext, "存储目录: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        }
    }

    public void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("download_url", text));
                Toast.makeText(mContext, "已复制下载链接到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(mContext, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public boolean removeDownload(long downloadId) {
        return deleteDownload(downloadId, false);
    }

    public boolean deleteDownload(long downloadId, boolean deletePhysicalFile) {
        boolean removed = false;
        DownloadItem target = null;
        synchronized (mDownloads) {
            for (int i = 0; i < mDownloads.size(); i++) {
                DownloadItem it = mDownloads.get(i);
                if (it.downloadId == downloadId) {
                    target = it;
                    mDownloads.remove(i);
                    removed = true;
                    break;
                }
            }
        }

        if (deletePhysicalFile && target != null) {
            try {
                File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), target.fileName);
                if (file.exists()) {
                    file.delete();
                }
                DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null && downloadId > 0) {
                    dm.remove(downloadId);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete downloaded file for id " + downloadId, e);
            }
        }

        if (removed && mDataStore != null) {
            mDataStore.saveDownloads(mDownloads);
        }
        return removed;
    }

    public void clearDownloads() {
        synchronized (mDownloads) {
            mDownloads.clear();
        }
        if (mDataStore != null) {
            mDataStore.clearDownloads();
        }
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
