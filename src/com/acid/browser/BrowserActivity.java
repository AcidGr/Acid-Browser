package com.acid.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Thin Browser Activity Orchestrator:
 * - Manages Android lifecycle, top address bar, takeover banner, and 5-button bottom toolbar
 * - Coordinates all Tier-0 viewport overlays and Tier-1/2 modal sheets via {@link OverlayStackManager}
 * - Delegates all modal UI rendering, storage persistence, and script/OTA I/O to dedicated components
 */
public class BrowserActivity extends Activity implements BrowserController.BrowserStateListener {

    private static final String TAG = "BrowserActivity";

    private BrowserDataStore mDataStore;
    private BrowserController mController;
    private HttpControlServer mHttpServer;
    private AcidAgentLoop mAgentLoop;
    private final OverlayStackManager mOverlayStack = new OverlayStackManager();

    private boolean mIsDarkMode = false;
    private boolean mIncognitoMode = false;

    // Root Containers
    private FrameLayout mRootContainer;
    private LinearLayout mMainContentLayout;

    // Top Bar
    private LinearLayout mTopBar;
    private View mTopBarDivider;
    private LinearLayout mShieldBox;
    private AcidIcons.SvgIconView mBtnShield;
    private LinearLayout mSearchContainer;
    private AcidIcons.SvgIconView mSearchIcon;
    private EditText mEtUrl;
    private LinearLayout mReloadBox;
    private AcidIcons.SvgIconView mBtnReload;
    private FrameLayout mBtnTopAgent;
    private AcidIcons.SvgIconView mIconTopAgent;
    private TextView mAgentUnreadBadge;
    private View mProgressBar;

    // Center Viewport
    private FrameLayout mCenterFrame;
    private FrameLayout mWebViewContainer;
    private AgentOverlayView mOverlayView;

    // Takeover Bar
    private LinearLayout mTakeoverBar;
    private AcidIcons.SvgIconView mTakeoverIcon;
    private TextView mTvTakeoverStatus;
    private AcidIcons.BadgeButton mBtnTakeoverAction;

    // Bottom 5-Button Toolbar
    private View mBottomDivider;
    private LinearLayout mBottomBar;
    private LinearLayout mBtnBack;
    private AcidIcons.SvgIconView mIconBack;
    private LinearLayout mBtnForward;
    private AcidIcons.SvgIconView mIconForward;
    private LinearLayout mBtnHome;
    private AcidIcons.SvgIconView mIconHome;
    private LinearLayout mBtnTabs;
    private AcidIcons.TabBoxView mIconTabs;
    private LinearLayout mBtnMenu;
    private AcidIcons.SvgIconView mIconMenu;

    // Tier-0 Viewport Overlays
    private TabsOverviewView mTabsOverview;
    private AgentFloatingView mAgentFloatingView;

    // Tier-1 / Tier-2 Modal Sheets
    private BrowserMenuSheet mMenuSheet;
    private BookmarksHistorySheet mBookmarksHistorySheet;
    private DownloadsSheet mDownloadsSheet;
    private AgentSessionsSheet mSessionsSheet;
    private AgentModelSelectorSheet mModelSelectorSheet;
    private ModelProviderConfigSheet mProviderConfigSheet;
    private UserscriptSheet mUserscriptSheet;
    private SkillSheet mSkillSheet;
    private AdBlockSheet mAdBlockSheet;
    private BrowserSettingsSheet mSettingsSheet;
    private AboutSheet mAboutSheet;
    private McpSheet mMcpSheet;
    private DownloadConfirmSheet mDownloadConfirmSheet;

    // Fullscreen and CustomView Video
    private FrameLayout mCustomViewContainer;
    private int mOriginalOrientation;
    private boolean mIsFullscreenMode = false;
    private FrameLayout mBtnExitFullscreen;
    private boolean mSyncingHotReload = false;
    private volatile String mLastAgentActivitySummary = "";

    private BroadcastReceiver mDownloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mDataStore = new BrowserDataStore(this);
        AcidLocale.syncFromDataStore(this, mDataStore);
        mDataStore.setCallbackDispatcher(new BrowserDataStore.CallbackDispatcher() {
            @Override
            public void dispatch(Runnable action) {
                runOnUiThread(action);
            }
        });
        mIsDarkMode = mDataStore.isDarkMode();
        getWindow().setBackgroundDrawable(new ColorDrawable(AcidTheme.bg(mIsDarkMode)));

        setContentView(buildContentView());

        mController = new BrowserController(this, mWebViewContainer, mOverlayView, this, mDataStore, mIsDarkMode);
        mDataStore.setOnSettingsChangedListener(new BrowserDataStore.OnSettingsChangedListener() {
            @Override
            public void onSettingsChanged() {
                syncFromHotReloadedSettings();
            }
        });
        mDataStore.setOnBookmarksChangedListener(new BrowserDataStore.OnBookmarksChangedListener() {
            @Override
            public void onBookmarksChanged() {
                if (mBookmarksHistorySheet != null && mBookmarksHistorySheet.isShowing()) {
                    mBookmarksHistorySheet.refreshActivePage();
                }
                if (mController != null) {
                    mController.pushBookmarksToActiveHomeTabs();
                }
            }
        });
        mDataStore.setOnHistoryChangedListener(new BrowserDataStore.OnHistoryChangedListener() {
            @Override
            public void onHistoryChanged() {
                if (mBookmarksHistorySheet != null && mBookmarksHistorySheet.isShowing()) {
                    mBookmarksHistorySheet.refreshActivePage();
                }
            }
        });

        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }, 101);
            }
        }

        initViewportOverlays();
        mHttpServer = new HttpControlServer(mController, HttpControlServer.DEFAULT_PORT);
        initModalSheets();

        if (mDataStore.isMcpServerEnabled()) {
            mHttpServer.start();
        }

        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (mController != null) {
                        mController.getDownloadCoordinator().onDownloadCompleted(id);
                    }
                    if (mDownloadsSheet != null && mDownloadsSheet.isShowing()) {
                        mDownloadsSheet.refreshDownloads();
                    }
                }
            }
        };
        registerReceiver(mDownloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        applyTheme(mIsDarkMode);
        if (mAgentLoop != null && mAgentFloatingView != null) {
            mAgentFloatingView.renderSessionChatStream(mAgentLoop.getActiveSession());
        }
        handleIncomingIntent(getIntent());
    }

    private void initViewportOverlays() {
        // 1. Tabs Overview (Tier 0)
        mTabsOverview = new TabsOverviewView(this, mController);
        mTabsOverview.setOnTabsOverviewListener(new TabsOverviewView.OnTabsOverviewListener() {
            @Override
            public void onOverviewShown() {
                mOverlayStack.notifyViewportShown(mTabsOverview);
            }

            @Override
            public void onOverviewClosed() {}
        });
        mCenterFrame.addView(mTabsOverview, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mOverlayStack.registerViewport(mTabsOverview);

        // 2. Agent Loop & Floating Drawer (Tier 0)
        mAgentLoop = new AcidAgentLoop(this, mController, new AcidAgentLoop.AgentLoopListener() {
            private boolean mWasRunning = false;

            @Override
            public void onLoopStateChanged(boolean running, boolean pausedForTakeover, int step) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.onLoopStateChanged(running, pausedForTakeover, step);
                }
                if (mWasRunning && !running && !pausedForTakeover) {
                    if (mAgentLoop != null && !mAgentLoop.isStopRequested()) {
                        if (mAgentFloatingView != null && !mAgentFloatingView.isShowing()) {
                            mAgentFloatingView.setHasUnread(true);
                        }
                    }
                }
                mWasRunning = running;
                if (!running) {
                    mLastAgentActivitySummary = "";
                }
                updateTopAgentButton(running, pausedForTakeover);
                if (mController != null) {
                    onModeChanged(mController.isDesktopMode(), mController.getControlMode(), mController.isSomEnabled());
                }
            }

            @Override
            public void onSessionLoaded(AcidSessionStore.AgentSession session) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.renderSessionChatStream(session);
                }
            }

            @Override
            public void onUiItemAdded(AcidSessionStore.UiMessageItem item) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.appendUiMessageItemView(item, true);
                }
                String s = formatAgentActivitySummary(item);
                if (!s.isEmpty()) {
                    mLastAgentActivitySummary = s;
                    refreshTakeoverBanner();
                }
            }

            @Override
            public void onUiItemUpdated(AcidSessionStore.UiMessageItem item) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.updateUiMessageItem(item);
                }
                String s = formatAgentActivitySummary(item);
                if (!s.isEmpty()) {
                    mLastAgentActivitySummary = s;
                    refreshTakeoverBanner();
                }
            }

            @Override
            public void onContextUsageUpdated(int totalTokens, int promptTokens, int cachedTokens) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.updateContextUsage(totalTokens, promptTokens, cachedTokens);
                }
            }
        });

        mAgentFloatingView = new AgentFloatingView(this, mAgentLoop, new AgentFloatingView.AgentHostCallback() {
            @Override
            public void onUnreadStateChanged(boolean hasUnread, boolean running, boolean pausedForTakeover) {
                updateTopAgentButton(running, pausedForTakeover);
            }

            @Override
            public void onOpenModelSelector() {
                if (mModelSelectorSheet != null) {
                    mOverlayStack.openRootModal(mModelSelectorSheet);
                }
            }

            @Override
            public void onOpenSessionsSheet() {
                if (mSessionsSheet != null) {
                    mOverlayStack.openRootModal(mSessionsSheet);
                }
            }

            @Override
            public void onFloatingShown() {
                mOverlayStack.notifyViewportShown(mAgentFloatingView);
            }
        });
        mAgentFloatingView.setHeightProvider(new AgentFloatingView.HeightProvider() {
            @Override
            public int getAvailableHeight() {
                return mCenterFrame != null ? mCenterFrame.getHeight() : 0;
            }
        });
        mCenterFrame.addView(mAgentFloatingView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mOverlayStack.registerViewport(mAgentFloatingView);
    }

    private void initModalSheets() {
        // 1. Agent Sessions
        mSessionsSheet = new AgentSessionsSheet(this, mAgentLoop, new AgentSessionsSheet.OnSessionActionListener() {
            @Override
            public void onSessionSwitched(String sessionId) {
                if (mAgentFloatingView != null && mAgentLoop != null) {
                    mAgentFloatingView.renderSessionChatStream(mAgentLoop.getActiveSession());
                }
            }

            @Override
            public void onSessionCreated() {
                if (mAgentFloatingView != null && mAgentLoop != null) {
                    mAgentFloatingView.renderSessionChatStream(mAgentLoop.getActiveSession());
                }
            }
        });
        attachModalSheet(mSessionsSheet);

        // 2. Bookmarks & History
        mBookmarksHistorySheet = new BookmarksHistorySheet(
            this, mDataStore, mController,
            new BookmarksHistorySheet.OnNavigateListener() {
                @Override
                public void onNavigateUrl(String url) {
                    mOverlayStack.dismissAllModals();
                    mController.loadUrl(url);
                }
            }
        );
        attachModalSheet(mBookmarksHistorySheet);

        // 3. Downloads Manager
        mDownloadsSheet = new DownloadsSheet(this, mController.getDownloadCoordinator());
        attachModalSheet(mDownloadsSheet);

        // 4. Download Confirmation
        mDownloadConfirmSheet = new DownloadConfirmSheet(this, mController.getDownloadCoordinator());
        attachModalSheet(mDownloadConfirmSheet);

        // 5. About & OTA Update
        mAboutSheet = new AboutSheet(this, new AboutSheet.OnAboutActionListener() {
            @Override
            public void onNavigateToUrl(String url) {
                mOverlayStack.dismissAllModals();
                mController.loadUrl(url);
            }

            @Override
            public void onStartApkDownload(String url, String fileName) {
                mOverlayStack.dismissAllModals();
                if (mController != null) {
                    mController.getDownloadCoordinator().startDownload(
                        url, fileName, null, null,
                        "application/vnd.android.package-archive", 0
                    );
                }
            }
        });
        attachModalSheet(mAboutSheet);

        // 6. Userscripts Manager & Installer
        mUserscriptSheet = new UserscriptSheet(
            this, mController.getUserscriptManager(),
            new UserscriptSheet.OnScriptActionListener() {
                @Override
                public void onRunScript(UserscriptMeta meta) {
                    if (mController != null) {
                        boolean ok = mController.runUserscriptNow(meta);
                        if (ok) {
                            AcidFeedback.ok(BrowserActivity.this, "已执行「" + meta.name + "」");
                        } else {
                            AcidFeedback.warn(
                                BrowserActivity.this,
                                "「" + meta.name + "」不适用于当前页面 (" + meta.describeScope() + ")"
                            );
                        }
                    }
                }

                @Override
                public void onScriptsChanged() {
                    updateMenuSheetCards();
                }

                @Override
                public void onQueryCommands(UserscriptManager.MenuCommandsCallback cb) {
                    if (mController != null) mController.queryUserscriptCommands(cb);
                }

                @Override
                public void onRunCommand(UserscriptManager.MenuCommand cmd) {
                    if (mController != null) mController.runUserscriptCommand(cmd);
                }
            }
        );
        attachModalSheet(mUserscriptSheet);

        mSkillSheet = new SkillSheet(this, mController != null ? mController.getSkillManager() : null);
        attachModalSheet(mSkillSheet);

        // 7. AdBlock Engine
        mAdBlockSheet = new AdBlockSheet(
            this,
            AdBlockManager.getInstance(this),
            new AdBlockSheet.OnAdBlockStateChangeListener() {
                @Override
                public void onAdBlockStateChanged(boolean enabled) {
                    updateShieldVisual();
                    updateMenuSheetCards();
                    if (mSettingsSheet != null) {
                        mSettingsSheet.refresh();
                    }
                }
            }
        );
        attachModalSheet(mAdBlockSheet);

        // 8. Model Provider Configuration
        mProviderConfigSheet = new ModelProviderConfigSheet(
            this, mAgentLoop.getModelStore(),
            new ModelProviderConfigSheet.OnConfigChangeListener() {
                @Override
                public void onProviderConfigChanged() {
                    if (mAgentFloatingView != null) {
                        mAgentFloatingView.refreshAgentStatusLabel();
                    }
                    updateMenuSheetCards();
                }
            }
        );
        attachModalSheet(mProviderConfigSheet);

        // 9. Session Model & Reasoning Effort Selector
        mModelSelectorSheet = new AgentModelSelectorSheet(
            this, mAgentLoop.getModelStore(),
            new AgentModelSelectorSheet.ModelSelectorListener() {
                @Override
                public void onOpenModelProviderConfig() {
                    mOverlayStack.pushModal(mModelSelectorSheet, mProviderConfigSheet);
                }

                @Override
                public void onModelConfigChanged() {
                    if (mAgentFloatingView != null) {
                        mAgentFloatingView.refreshAgentStatusLabel();
                    }
                    updateMenuSheetCards();
                }
            }
        );
        attachModalSheet(mModelSelectorSheet);

        // 10. MCP Control Gateway & Security Sheet
        mMcpSheet = new McpSheet(
            this, mDataStore, mHttpServer,
            new McpSheet.OnMcpActionListener() {
                @Override
                public void onToggleMcpServer(boolean enable) {
                    if (enable) {
                        startMcpServerInternal();
                    } else {
                        stopMcpServerInternal();
                    }
                }

                @Override
                public void onRestartMcpServer() {
                    restartMcpServerInternal();
                }
            }
        );
        attachModalSheet(mMcpSheet);

        // 11. Settings Center (Hierarchical L1/L2)
        mSettingsSheet = new BrowserSettingsSheet(
            this, mDataStore,
            new BrowserSettingsSheet.OnSettingsActionListener() {
                @Override
                public void onOpenModelConfig() {
                    mOverlayStack.pushModal(mSettingsSheet, mProviderConfigSheet);
                }

                @Override
                public void onOpenMcp() {
                    mMcpSheet.renderContent();
                    mOverlayStack.pushModal(mSettingsSheet, mMcpSheet);
                }

                @Override
                public void onToggleMcpServer() {
                    toggleMcpServer();
                    if (mSettingsSheet != null) {
                        mSettingsSheet.setMcpRunning(mHttpServer != null && mHttpServer.isRunning());
                        mSettingsSheet.refresh();
                    }
                }

                @Override
                public void onOpenAgentFloatingWindow() {
                    mOverlayStack.dismissAllModals();
                    mAgentFloatingView.show();
                }

                @Override
                public void onOpenAdBlock() {
                    mAdBlockSheet.renderContent();
                    mOverlayStack.pushModal(mSettingsSheet, mAdBlockSheet);
                }

                @Override
                public void onOpenUserscripts() {
                    mUserscriptSheet.renderScripts();
                    mOverlayStack.pushModal(mSettingsSheet, mUserscriptSheet);
                }

                @Override
                public void onOpenSkills() {
                    mSkillSheet.renderContent();
                    mOverlayStack.pushModal(mSettingsSheet, mSkillSheet);
                }

                @Override
                public void onOpenAbout() {
                    mOverlayStack.pushModal(mSettingsSheet, mAboutSheet);
                }

                @Override
                public void onClearDataRequested(
                    boolean clearCache,
                    boolean clearCookies,
                    boolean clearHistory,
                    boolean clearStorage,
                    boolean clearWorkspace
                ) {
                    int wsDeleted = 0;
                    if (clearWorkspace && mController != null) {
                        wsDeleted = mDataStore.clearWorkspaceTempFiles(mController.getWorkspaceDir());
                    }
                    int cleaned = mDataStore.clearBrowsingData(
                        mController, clearCache, clearCookies, clearHistory, clearStorage, false
                    );
                    if (clearWorkspace) {
                        cleaned++;
                    }
                    if (mSettingsSheet != null) mSettingsSheet.refresh();
                    String msg;
                    if (clearWorkspace) {
                        String wsDetail = wsDeleted > 0
                            ? ("清理 " + wsDeleted + " 个工作区临时项")
                            : "工作区无临时残留";
                        msg = "已清除 " + cleaned + " 类数据 (" + wsDetail + "，配置与脚本已保留)";
                    } else {
                        msg = "已清除所选浏览数据 (" + cleaned + " 类)";
                    }
                    AcidFeedback.ok(BrowserActivity.this, msg);
                }

                @Override
                public void onClearWorkspaceOnlyRequested() {
                    int cleaned = mDataStore.clearWorkspaceTempFiles(mController.getWorkspaceDir());
                    String msg = cleaned > 0
                        ? ("已清理 AI 工作区 (" + cleaned + " 个临时项，settings.json 与脚本已保留)")
                        : "AI 工作区当前无临时残留 (settings.json 与脚本已安全保留)";
                    AcidFeedback.ok(BrowserActivity.this, msg);
                }

                @Override
                public void onHomeUrlChanged(int type, String customUrl) {
                    if (mController != null) {
                        mController.loadUrl(mController.getHomeUrl());
                    }
                }

                @Override
                public void onWebDebuggingChanged(boolean enabled) {
                    if (mController != null) {
                        mController.setWebDebuggingEnabled(enabled);
                    }
                    AcidFeedback.info(
                        BrowserActivity.this,
                        enabled ? "已开启网页远程调试 (Chrome DevTools)" : "已关闭网页远程调试"
                    );
                }

                @Override
                public void onDoNotTrackChanged(boolean enabled) {
                    AcidFeedback.info(
                        BrowserActivity.this,
                        enabled ? "已开启「不跟踪 (DNT)」请求" : "已关闭「不跟踪」请求"
                    );
                }

                @Override
                public void onBlockImagesChanged(boolean enabled) {
                    if (mController != null) {
                        mController.setBlockImages(enabled);
                    }
                    AcidFeedback.info(
                        BrowserActivity.this,
                        enabled ? "已开启无图省流模式" : "已恢复正常图片加载"
                    );
                }

                @Override
                public void onLanguageChanged(int langIndex) {
                    applyLanguageHotSwitch();
                }
            }
        );
        attachModalSheet(mSettingsSheet);

        // 11. Bottom Main Menu Sheet
        mMenuSheet = new BrowserMenuSheet(this, new BrowserMenuSheet.MenuActionListener() {
            @Override
            public void onToggleDarkMode() {
                toggleDarkMode();
            }

            @Override
            public void onToggleIncognitoMode() {
                toggleIncognitoMode();
            }

            @Override
            public void onOpenBookmarks() {
                BrowserController.TabSession cur = mController.getActiveTab();
                mBookmarksHistorySheet.prepareTab(
                    BookmarksHistorySheet.TAB_BOOKMARKS,
                    cur != null ? cur.title : "",
                    cur != null ? cur.url : ""
                );
                mOverlayStack.openRootModal(mBookmarksHistorySheet);
            }

            @Override
            public void onOpenHistory() {
                BrowserController.TabSession cur = mController.getActiveTab();
                mBookmarksHistorySheet.prepareTab(
                    BookmarksHistorySheet.TAB_HISTORY,
                    cur != null ? cur.title : "",
                    cur != null ? cur.url : ""
                );
                mOverlayStack.openRootModal(mBookmarksHistorySheet);
            }

            @Override
            public void onToggleDesktopMode() {
                boolean nextDesktop = !mController.isDesktopMode();
                mController.setDesktopMode(nextDesktop, true);
                updateMenuSheetCards();
                AcidFeedback.info(
                    BrowserActivity.this,
                    nextDesktop ? "已切换为电脑端视口 (1280px)" : "已切换为移动端视口"
                );
            }

            @Override
            public void onOpenDownloads() {
                mOverlayStack.openRootModal(mDownloadsSheet);
            }

            @Override
            public void onOpenAgentFloatingWindow() {
                mAgentFloatingView.toggle();
            }

            @Override
            public void onOpenModelProviderConfig() {
                mOverlayStack.openRootModal(mProviderConfigSheet);
            }

            @Override
            public void onOpenAbout() {
                mOverlayStack.openRootModal(mAboutSheet);
            }

            @Override
            public void onOpenMcp() {
                if (mMcpSheet != null) {
                    mMcpSheet.renderContent();
                    mOverlayStack.openRootModal(mMcpSheet);
                }
            }

            @Override
            public void onToggleMcpServer() {
                toggleMcpServer();
            }

            @Override
            public void onOpenUserscripts() {
                mUserscriptSheet.renderScripts();
                mOverlayStack.openRootModal(mUserscriptSheet);
            }

            @Override
            public void onOpenSkills() {
                mSkillSheet.renderContent();
                mOverlayStack.openRootModal(mSkillSheet);
            }

            @Override
            public void onOpenAdBlock() {
                mAdBlockSheet.renderContent();
                mOverlayStack.openRootModal(mAdBlockSheet);
            }

            @Override
            public void onOpenSettings() {
                mSettingsSheet.setMcpRunning(mHttpServer != null && mHttpServer.isRunning());
                mOverlayStack.openRootModal(mSettingsSheet);
            }

            @Override
            public void onToggleFullscreenMode() {
                toggleFullscreenMode();
            }
        }, mDataStore);
        attachModalSheet(mMenuSheet);
    }

    private void attachModalSheet(AcidBottomSheetView sheet) {
        mRootContainer.addView(sheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mOverlayStack.registerModal(sheet);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    /**
     * Enforces HTTP(S)-only scheme allowlist for external {@code ACTION_VIEW} Intents so exported
     * explicit Intents can never inject {@code javascript:}, {@code file://}, or {@code data:} URIs.
     */
    public static boolean isSafeExternalViewUri(String rawUri) {
        if (rawUri == null) return false;
        String trimmed = rawUri.trim();
        if (trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && mController != null) {
                String targetUrl = data.toString();
                if (!isSafeExternalViewUri(targetUrl)) {
                    android.util.Log.w("AcidBrowserAct", "Blocked unsafe external Intent URI scheme: " + targetUrl);
                    return;
                }
                BrowserController.TabSession active = mController.getActiveTab();
                boolean reuseInitialHome = active != null
                    && mController.getTabs().size() == 1
                    && mController.isHomeUrl(active.url);
                if (reuseInitialHome || !BrowserController.canOpenScriptedTab(mController.getTabs().size())) {
                    mController.loadUrl(targetUrl);
                } else {
                    mController.createNewTab(targetUrl, true);
                }
            }
        }
    }

    private int dp(float v) {
        return AcidTheme.dp(this, v);
    }

    // =========================================================================
    // Browser Chrome Construction (Top Bar, Viewport, Takeover Banner, Toolbar)
    // =========================================================================

    private View buildContentView() {
        mRootContainer = new FrameLayout(this);
        mRootContainer.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mMainContentLayout = new LinearLayout(this);
        mMainContentLayout.setOrientation(LinearLayout.VERTICAL);
        mMainContentLayout.setBackgroundColor(AcidTheme.LIGHT_BG);

        // --- Top Bar ---
        mTopBar = new LinearLayout(this);
        mTopBar.setOrientation(LinearLayout.HORIZONTAL);
        mTopBar.setGravity(Gravity.CENTER_VERTICAL);
        mTopBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        mShieldBox = new LinearLayout(this);
        mShieldBox.setGravity(Gravity.CENTER);
        mShieldBox.setPadding(dp(6), dp(6), dp(6), dp(6));
        mShieldBox.setClickable(true);
        mShieldBox.setFocusable(true);
        mBtnShield = new AcidIcons.SvgIconView(this, AcidIcons.ICON_SHIELD, AcidTheme.LIGHT_ACCENT, 18);
        mShieldBox.addView(mBtnShield);
        mShieldBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAdBlockSheet != null) {
                    mAdBlockSheet.renderContent();
                    mOverlayStack.openRootModal(mAdBlockSheet);
                }
            }
        });
        mTopBar.addView(mShieldBox);

        mSearchContainer = new LinearLayout(this);
        mSearchContainer.setOrientation(LinearLayout.HORIZONTAL);
        mSearchContainer.setGravity(Gravity.CENTER_VERTICAL);
        mSearchContainer.setPadding(dp(10), dp(2), dp(4), dp(2));

        mSearchIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_SEARCH, AcidTheme.LIGHT_MUTED, 14);
        mSearchContainer.addView(mSearchIcon);

        mEtUrl = new EditText(this);
        mEtUrl.setSingleLine(true);
        mEtUrl.setHint(AcidLocale.tr("搜索或输入网址..."));
        mEtUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        mEtUrl.setBackgroundColor(Color.TRANSPARENT);
        mEtUrl.setPadding(dp(6), dp(4), dp(6), dp(4));
        mEtUrl.setImeOptions(EditorInfo.IME_ACTION_GO);
        mEtUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mEtUrl.setSelectAllOnFocus(true);
        mEtUrl.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    AcidTheme.hideKeyboard(BrowserActivity.this);
                    if (mTabsOverview != null && mTabsOverview.isShowing()) {
                        mTabsOverview.dismiss();
                    }
                    mController.loadUrl(mEtUrl.getText().toString());
                    BrowserController.TabSession tab = mController.getActiveTab();
                    if (tab != null && tab.webView != null) {
                        tab.webView.requestFocus();
                    } else {
                        mEtUrl.clearFocus();
                    }
                    return true;
                }
                return false;
            }
        });
        mSearchContainer.addView(mEtUrl, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        mReloadBox = new LinearLayout(this);
        mReloadBox.setGravity(Gravity.CENTER);
        mReloadBox.setClickable(true);
        mReloadBox.setFocusable(true);
        mBtnReload = new AcidIcons.SvgIconView(this, AcidIcons.ICON_RELOAD, AcidTheme.LIGHT_MUTED, 17);
        mBtnReload.setStrokeWidthDp(1.5f);
        mReloadBox.addView(mBtnReload);
        mReloadBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserController.TabSession tab = mController.getActiveTab();
                if (tab != null) {
                    if (tab.isLoading) {
                        tab.webView.stopLoading();
                    } else {
                        tab.webView.reload();
                    }
                }
            }
        });
        mSearchContainer.addView(mReloadBox, new LinearLayout.LayoutParams(dp(36), dp(32)));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        searchLp.setMargins(dp(4), 0, dp(4), 0);
        mTopBar.addView(mSearchContainer, searchLp);

        // Agent Trigger Button
        mBtnTopAgent = new FrameLayout(this);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        btnLp.gravity = Gravity.CENTER_VERTICAL;
        btnLp.leftMargin = dp(2);
        mBtnTopAgent.setLayoutParams(btnLp);
        mBtnTopAgent.setClickable(true);
        mBtnTopAgent.setFocusable(true);

        int iconSize = dp(19);
        mIconTopAgent = new AcidIcons.SvgIconView(this, AcidIcons.ICON_AGENT, AcidTheme.LIGHT_ICON, 19);
        mBtnTopAgent.addView(mIconTopAgent, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        mAgentUnreadBadge = new TextView(this);
        mAgentUnreadBadge.setText("!");
        mAgentUnreadBadge.setTextColor(Color.WHITE);
        mAgentUnreadBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_MICRO);
        mAgentUnreadBadge.setTypeface(Typeface.DEFAULT_BOLD);
        mAgentUnreadBadge.setGravity(Gravity.CENTER);
        int badgeSize = dp(13);
        mAgentUnreadBadge.setBackground(AcidTheme.pillBg(this, AcidTheme.COLOR_RED, Color.WHITE, AcidTheme.RADIUS_PILL));
        mAgentUnreadBadge.setLayoutParams(new FrameLayout.LayoutParams(
            badgeSize, badgeSize, Gravity.TOP | Gravity.END
        ));
        mAgentUnreadBadge.setVisibility(View.GONE);
        mBtnTopAgent.addView(mAgentUnreadBadge);

        mBtnTopAgent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.toggle();
                }
            }
        });
        mTopBar.addView(mBtnTopAgent);

        mMainContentLayout.addView(mTopBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mTopBarDivider = new View(this);
        mMainContentLayout.addView(mTopBarDivider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ));

        mProgressBar = new View(this);
        mProgressBar.setVisibility(View.INVISIBLE);
        mProgressBar.setPivotX(0f);
        mProgressBar.setScaleX(0f);
        mMainContentLayout.addView(mProgressBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(2)
        ));

        mTakeoverBar = buildTakeoverBanner();
        mTakeoverBar.setVisibility(View.GONE);
        mMainContentLayout.addView(mTakeoverBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // --- Center Viewport ---
        mCenterFrame = new FrameLayout(this);
        mWebViewContainer = new FrameLayout(this);
        mOverlayView = new AgentOverlayView(this);
        mCenterFrame.addView(mWebViewContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mCenterFrame.addView(mOverlayView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mCenterFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int oldH = oldBottom - oldTop;
                int newH = bottom - top;
                if (newH > 0 && newH != oldH && mAgentFloatingView != null && mAgentFloatingView.isShowing()) {
                    mAgentFloatingView.updateFloatingWindowPosition(oldH > 0);
                }
            }
        });

        mMainContentLayout.addView(mCenterFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        // --- Bottom 5-Button Toolbar ---
        mBottomDivider = new View(this);
        mMainContentLayout.addView(mBottomDivider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ));

        mBottomBar = buildBottomToolbar();
        mMainContentLayout.addView(mBottomBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ));

        mRootContainer.addView(mMainContentLayout, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Video / CustomView Fullscreen layer
        mCustomViewContainer = new FrameLayout(this);
        mCustomViewContainer.setBackgroundColor(Color.BLACK);
        mCustomViewContainer.setVisibility(View.GONE);
        mRootContainer.addView(mCustomViewContainer, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Floating Exit Fullscreen pill button
        mBtnExitFullscreen = buildExitFullscreenButton();
        mBtnExitFullscreen.setVisibility(View.GONE);
        FrameLayout.LayoutParams exitLp = new FrameLayout.LayoutParams(
            dp(42), dp(42), Gravity.BOTTOM | Gravity.END
        );
        exitLp.rightMargin = dp(16);
        exitLp.bottomMargin = dp(24);
        mRootContainer.addView(mBtnExitFullscreen, exitLp);

        return mRootContainer;
    }

    private FrameLayout buildExitFullscreenButton() {
        FrameLayout btn = new FrameLayout(this);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setBackground(AcidTheme.pillBg(this, Color.parseColor("#99000000"), Color.parseColor("#44FFFFFF"), dp(21)));
        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_FULLSCREEN_EXIT, Color.WHITE, 20);
        btn.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreenMode(false);
            }
        });
        return btn;
    }

    private LinearLayout buildTakeoverBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));

        mTakeoverIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HUMAN, AcidTheme.COLOR_AMBER, 16);
        bar.addView(mTakeoverIcon);

        mTvTakeoverStatus = new TextView(this);
        mTvTakeoverStatus.setText(AcidLocale.tr("AI 请求接管"));
        mTvTakeoverStatus.setTextColor(AcidTheme.COLOR_AMBER);
        mTvTakeoverStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        mTvTakeoverStatus.setSingleLine(true);
        mTvTakeoverStatus.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        tvLp.leftMargin = dp(6);
        bar.addView(mTvTakeoverStatus, tvLp);

        mBtnTakeoverAction = new AcidIcons.BadgeButton(this, AcidIcons.ICON_AGENT, "交还AI", AcidTheme.COLOR_AMBER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = mController.getControlMode();
                if (cur == AgentOverlayView.MODE_TAKEOVER_REQUESTED && mAgentLoop != null && mAgentLoop.isPausedForTakeover()) {
                    mAgentLoop.resumeFromTakeover();
                    return;
                }
                if (cur == AgentOverlayView.MODE_AGENT) {
                    boolean isDrivingCurrent = mController.isAgentDrivingCurrentTab();
                    if (!isDrivingCurrent) {
                        int boundTabId = mController.getAgentBoundTabId();
                        if (boundTabId > 0) {
                            mController.switchToTab(boundTabId);
                        }
                        return;
                    }
                    if (mAgentLoop != null && mAgentLoop.isRunning()) {
                        mAgentLoop.pauseForTakeover("用户主动接管");
                    } else {
                        mController.setControlMode(AgentOverlayView.MODE_HUMAN, "");
                    }
                    return;
                }
                int next = (cur == AgentOverlayView.MODE_AGENT) ? AgentOverlayView.MODE_HUMAN : AgentOverlayView.MODE_AGENT;
                mController.setControlMode(next, "");
            }
        });
        bar.addView(mBtnTakeoverAction);
        return bar;
    }

    private LinearLayout buildBottomToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        mBtnBack = createBottomTabItem();
        mIconBack = new AcidIcons.SvgIconView(this, AcidIcons.ICON_BACK, AcidTheme.LIGHT_ICON_DISABLED, 20);
        mBtnBack.addView(mIconBack);
        mBtnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverview != null && mTabsOverview.isShowing()) {
                    mTabsOverview.dismiss();
                    return;
                }
                BrowserController.TabSession tab = mController.getActiveTab();
                if (tab != null && tab.webView.canGoBack()) {
                    tab.webView.goBack();
                }
            }
        });
        bar.addView(mBtnBack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        mBtnForward = createBottomTabItem();
        mIconForward = new AcidIcons.SvgIconView(this, AcidIcons.ICON_FORWARD, AcidTheme.LIGHT_ICON_DISABLED, 20);
        mBtnForward.addView(mIconForward);
        mBtnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverview != null && mTabsOverview.isShowing()) {
                    mTabsOverview.dismiss();
                    return;
                }
                BrowserController.TabSession tab = mController.getActiveTab();
                if (tab != null && tab.webView.canGoForward()) {
                    tab.webView.goForward();
                }
            }
        });
        bar.addView(mBtnForward, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        mBtnHome = createBottomTabItem();
        mIconHome = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HOME, AcidTheme.LIGHT_ICON, 21);
        mBtnHome.addView(mIconHome);
        mBtnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverview != null && mTabsOverview.isShowing()) {
                    mTabsOverview.dismiss();
                }
                mController.loadUrl(mController.getHomeUrl());
            }
        });
        bar.addView(mBtnHome, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        mBtnTabs = createBottomTabItem();
        mIconTabs = new AcidIcons.TabBoxView(this, AcidTheme.LIGHT_ICON, 18);
        mBtnTabs.addView(mIconTabs);
        mBtnTabs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverview != null) {
                    mTabsOverview.toggle();
                }
            }
        });
        bar.addView(mBtnTabs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        mBtnMenu = createBottomTabItem();
        mIconMenu = new AcidIcons.SvgIconView(this, AcidIcons.ICON_MENU, AcidTheme.LIGHT_ICON, 20);
        mBtnMenu.addView(mIconMenu);
        mBtnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMenuSheet != null) {
                    if (mMenuSheet.isShowing()) {
                        mMenuSheet.dismiss();
                    } else {
                        updateMenuSheetCards();
                        mOverlayStack.openRootModal(mMenuSheet);
                    }
                }
            }
        });
        bar.addView(mBtnMenu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        return bar;
    }

    private LinearLayout createBottomTabItem() {
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        return item;
    }

    // =========================================================================
    // Mode Switches & Theme Propagation
    // =========================================================================

    private void syncFromHotReloadedSettings() {
        if (mSyncingHotReload) return;
        mSyncingHotReload = true;
        try {
            boolean langChanged = AcidLocale.syncFromDataStore(this, mDataStore);
            if (mController != null) {
                mController.applySettingsHotReload();
            }
            boolean storeDark = mDataStore.isDarkMode();
            if (mIsDarkMode != storeDark) {
                mIsDarkMode = storeDark;
                applyTheme(mIsDarkMode);
            }
            if (mHttpServer != null) {
                boolean wantMcp = mDataStore.isMcpServerEnabled();
                if (wantMcp && !mHttpServer.isRunning()) {
                    mHttpServer.start();
                } else if (!wantMcp && mHttpServer.isRunning()) {
                    mHttpServer.stop();
                }
            }
            updateShieldVisual();
            if (mSettingsSheet != null) {
                mSettingsSheet.setMcpRunning(mHttpServer != null && mHttpServer.isRunning());
            }
            if (langChanged) {
                applyLanguageHotSwitch();
            } else {
                updateMenuSheetCards();
                if (mSettingsSheet != null) {
                    mSettingsSheet.refresh();
                }
            }
        } finally {
            mSyncingHotReload = false;
        }
    }

    private void applyLanguageHotSwitch() {
        AcidLocale.syncFromDataStore(this, mDataStore);
        updateAddressBarVisual();
        refreshTakeoverBanner();
        if (mOverlayView != null) {
            mOverlayView.postInvalidate();
        }
        if (mMenuSheet != null) {
            mMenuSheet.refreshLanguage();
            updateMenuSheetCards();
        }
        if (mAgentFloatingView != null) {
            mAgentFloatingView.refreshLanguage();
        }
        if (mTabsOverview != null && mTabsOverview.isShowing()) {
            mTabsOverview.renderTabsGrid();
        }
        AcidBottomSheetView[] allSheets = new AcidBottomSheetView[]{
            mSettingsSheet, mAboutSheet, mMcpSheet, mAdBlockSheet,
            mUserscriptSheet, mSkillSheet, mBookmarksHistorySheet,
            mDownloadsSheet, mDownloadConfirmSheet, mSessionsSheet,
            mModelSelectorSheet, mProviderConfigSheet
        };
        for (AcidBottomSheetView s : allSheets) {
            if (s != null && s.isShowing()) {
                s.refreshActivePage();
            }
        }
        if (mController != null) {
            mController.pushLanguageToActiveHomeTabs();
        }
    }

    private void toggleDarkMode() {
        mIsDarkMode = !mIsDarkMode;
        mDataStore.setDarkMode(mIsDarkMode);
        if (mController != null) {
            mController.setDarkMode(mIsDarkMode);
        }
        applyTheme(mIsDarkMode);
        updateMenuSheetCards();
    }

    private void toggleIncognitoMode() {
        mIncognitoMode = !mIncognitoMode;
        if (mController != null) {
            mController.setIncognitoMode(mIncognitoMode);
        }
        updateAddressBarVisual();
        updateMenuSheetCards();
        AcidFeedback.info(
            this,
            mIncognitoMode
                ? "已开启无痕模式 (禁用历史记录、表单保存与磁盘缓存)"
                : "已退出无痕模式 (已自动清理临时缓存与会话 Cookie)"
        );
    }

    public void setFullscreenMode(boolean enabled) {
        mIsFullscreenMode = enabled;
        if (mDataStore != null) {
            mDataStore.setFullscreenEnabled(enabled);
        }
        applyFullscreenWindowFlags(enabled);

        mTopBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        mTopBarDivider.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (mProgressBar != null) {
            mProgressBar.setVisibility(enabled ? View.GONE : View.INVISIBLE);
        }
        mBottomBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        mBottomDivider.setVisibility(enabled ? View.GONE : View.VISIBLE);

        if (mBtnExitFullscreen != null) {
            mBtnExitFullscreen.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        updateMenuSheetCards();
    }

    public void toggleFullscreenMode() {
        setFullscreenMode(!mIsFullscreenMode);
        AcidFeedback.info(
            this,
            mIsFullscreenMode ? "已开启全屏模式 (按返回键或点右下角退出)" : "已退出全屏模式"
        );
    }

    private void applyFullscreenWindowFlags(boolean fullscreen) {
        Window window = getWindow();
        if (window == null) return;
        View decorView = window.getDecorView();

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                // Release decor insets pinning so webpage naturally occupies the true physical top edge
                java.lang.reflect.Method setDecorFits = window.getClass().getMethod("setDecorFitsSystemWindows", boolean.class);
                setDecorFits.invoke(window, !fullscreen);

                java.lang.reflect.Method getInsetsController = window.getClass().getMethod("getInsetsController");
                Object controller = getInsetsController.invoke(window);
                if (controller != null) {
                    Class<?> insetsClass = controller.getClass();
                    if (fullscreen) {
                        java.lang.reflect.Method setBehavior = insetsClass.getMethod("setSystemBarsBehavior", int.class);
                        setBehavior.invoke(controller, 2); // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2
                        java.lang.reflect.Method hide = insetsClass.getMethod("hide", int.class);
                        hide.invoke(controller, 7); // Type.systemBars() = 7
                    } else {
                        java.lang.reflect.Method show = insetsClass.getMethod("show", int.class);
                        show.invoke(controller, 7);
                    }
                }
            } catch (Exception ignored) {}
        }

        int flags = decorView.getSystemUiVisibility();
        final int FULLSCREEN_FLAGS = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

        if (fullscreen) {
            flags |= FULLSCREEN_FLAGS;
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            flags &= ~FULLSCREEN_FLAGS;
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        decorView.setSystemUiVisibility(flags);
    }

    private void startMcpServerInternal() {
        if (mHttpServer == null) return;
        if (!mHttpServer.isRunning()) {
            mHttpServer.start();
        }
        mDataStore.setMcpServerEnabled(true);
        boolean lan = mDataStore.isMcpLanEnabled();
        AcidFeedback.ok(this, "MCP 服务已启动 (" + (lan ? "0.0.0.0:3090 局域网模式" : "127.0.0.1:3090 本机模式") + ")");
        updateMenuSheetCards();
        if (mSettingsSheet != null) {
            mSettingsSheet.setMcpRunning(true);
            mSettingsSheet.refresh();
        }
        if (mMcpSheet != null) {
            mMcpSheet.renderContent();
        }
    }

    private void stopMcpServerInternal() {
        if (mHttpServer == null) return;
        if (mHttpServer.isRunning()) {
            mHttpServer.stop();
        }
        mDataStore.setMcpServerEnabled(false);
        AcidFeedback.info(this, "MCP 服务已停止 (3090 端口已释放)");
        updateMenuSheetCards();
        if (mSettingsSheet != null) {
            mSettingsSheet.setMcpRunning(false);
            mSettingsSheet.refresh();
        }
        if (mMcpSheet != null) {
            mMcpSheet.renderContent();
        }
    }

    private void restartMcpServerInternal() {
        if (mHttpServer == null) return;
        if (mHttpServer.isRunning()) {
            mHttpServer.stop();
            mHttpServer.start();
            boolean lan = mDataStore.isMcpLanEnabled();
            AcidFeedback.ok(this, "MCP 服务已重启 (" + (lan ? "0.0.0.0:3090 局域网模式" : "127.0.0.1:3090 本机模式") + ")");
        }
        updateMenuSheetCards();
        if (mMcpSheet != null) {
            mMcpSheet.renderContent();
        }
    }

    private void toggleMcpServer() {
        if (mHttpServer == null) return;
        boolean currentlyRunning = mHttpServer.isRunning();
        if (currentlyRunning) {
            stopMcpServerInternal();
        } else {
            startMcpServerInternal();
        }
    }

    private void updateMenuSheetCards() {
        if (mMenuSheet == null) return;
        int providerCount = (mAgentLoop != null && mAgentLoop.getModelStore() != null)
            ? mAgentLoop.getModelStore().getProviders().size() : 1;
        int modelCount = (mAgentLoop != null && mAgentLoop.getModelStore() != null)
            ? mAgentLoop.getModelStore().getTotalLoadedModelsCount() : 1;
        mMenuSheet.updateCards(
            mIsDarkMode,
            mIncognitoMode,
            mController != null && mController.isDesktopMode(),
            mIsFullscreenMode,
            mHttpServer != null && mHttpServer.isRunning(),
            providerCount,
            modelCount
        );
    }

    private void updateShieldVisual() {
        if (mBtnShield == null) return;
        boolean adBlockEnabled = AdBlockManager.getInstance(this).isEnabled();
        mBtnShield.setColor(adBlockEnabled ? AcidTheme.accent(mIsDarkMode) : AcidTheme.muted(mIsDarkMode));
    }

    private void updateAddressBarVisual() {
        if (mSearchContainer == null || mSearchIcon == null || mEtUrl == null) return;
        int searchBg = AcidTheme.searchBg(mIsDarkMode);
        int searchBorder = mIncognitoMode
            ? AcidTheme.COLOR_PURPLE
            : AcidTheme.searchBorder(mIsDarkMode);
        mSearchContainer.setBackground(AcidTheme.pillBg(this, searchBg, searchBorder, AcidTheme.RADIUS_PILL));
        mSearchIcon.setIcon(mIncognitoMode ? AcidIcons.ICON_INCOGNITO : AcidIcons.ICON_SEARCH);
        mSearchIcon.setColor(mIncognitoMode ? AcidTheme.COLOR_PURPLE : AcidTheme.muted(mIsDarkMode));
        mEtUrl.setHint(AcidLocale.tr(mIncognitoMode ? "无痕模式 · 搜索或输入网址..." : "搜索或输入网址..."));
        mEtUrl.setTextColor(AcidTheme.text(mIsDarkMode));
        mEtUrl.setHintTextColor(AcidTheme.muted(mIsDarkMode));
    }

    private void applyTheme(boolean dark) {
        int bg = AcidTheme.bg(dark);
        int bar = AcidTheme.bar(dark);
        int border = AcidTheme.border(dark);
        int muted = AcidTheme.muted(dark);
        int icon = AcidTheme.icon(dark);
        int accent = AcidTheme.accent(dark);
        int pressed = AcidTheme.rowPressed(dark);

        if (Build.VERSION.SDK_INT >= 21) {
            Window window = getWindow();
            window.setStatusBarColor(bar);
            window.setNavigationBarColor(bar);
            if (Build.VERSION.SDK_INT >= 23) {
                int flags = window.getDecorView().getSystemUiVisibility();
                if (!dark) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    final int FLAG_LIGHT_NAV = 0x00000010;
                    if (!dark) {
                        flags |= FLAG_LIGHT_NAV;
                    } else {
                        flags &= ~FLAG_LIGHT_NAV;
                    }
                }
                window.getDecorView().setSystemUiVisibility(flags);
            }
        }

        getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        mMainContentLayout.setBackgroundColor(bg);
        if (mCenterFrame != null) mCenterFrame.setBackgroundColor(bg);
        if (mWebViewContainer != null) mWebViewContainer.setBackgroundColor(bg);
        mTopBar.setBackgroundColor(bar);
        mTopBarDivider.setBackgroundColor(border);
        mBottomBar.setBackgroundColor(bar);
        mBottomDivider.setBackgroundColor(border);
        mTakeoverBar.setBackgroundColor(AcidTheme.takeoverBg(dark));

        mShieldBox.setBackground(AcidTheme.interactiveBg(this, Color.TRANSPARENT, pressed, 0, AcidTheme.RADIUS_CONTROL));
        mReloadBox.setBackground(AcidTheme.interactiveBg(this, Color.TRANSPARENT, pressed, 0, AcidTheme.RADIUS_PILL));
        mBtnReload.setColor(muted);

        updateAddressBarVisual();
        updateShieldVisual();
        mProgressBar.setBackgroundColor(accent);

        View[] bottomBtns = new View[]{mBtnBack, mBtnForward, mBtnHome, mBtnTabs, mBtnMenu};
        for (View b : bottomBtns) {
            b.setBackground(AcidTheme.interactiveBg(this, Color.TRANSPARENT, pressed, 0, AcidTheme.RADIUS_CONTROL));
        }

        updateNavButtons();
        mIconHome.setColor(icon);
        mIconTabs.setColor(icon);
        mIconMenu.setColor(icon);

        updateTopAgentButton(
            mAgentLoop != null && mAgentLoop.isRunning(),
            mAgentLoop != null && mAgentLoop.isPausedForTakeover()
        );

        mOverlayStack.applyTheme(dark);

        if (mIsFullscreenMode || (mController != null && mController.isCustomViewShowing())) {
            applyFullscreenWindowFlags(true);
        }
    }

    private void updateTopAgentButton(boolean running, boolean pausedForTakeover) {
        if (mIconTopAgent == null || mBtnTopAgent == null) return;
        if (running) {
            int yellow = AcidTheme.COLOR_AMBER;
            mIconTopAgent.setColor(yellow);
            mBtnTopAgent.setBackground(AcidTheme.interactiveBg(
                this, AcidTheme.softTint(yellow, 32), AcidTheme.softTint(yellow, 64), yellow, AcidTheme.RADIUS_CARD
            ));
        } else if (pausedForTakeover) {
            int orange = AcidTheme.COLOR_ORANGE;
            mIconTopAgent.setColor(orange);
            mBtnTopAgent.setBackground(AcidTheme.interactiveBg(
                this, AcidTheme.softTint(orange, 32), AcidTheme.softTint(orange, 64), orange, AcidTheme.RADIUS_CARD
            ));
        } else {
            mIconTopAgent.setColor(AcidTheme.icon(mIsDarkMode));
            mBtnTopAgent.setBackground(AcidTheme.interactiveBg(
                this, Color.TRANSPARENT, AcidTheme.rowPressed(mIsDarkMode), 0, AcidTheme.RADIUS_CONTROL
            ));
        }

        if (mAgentUnreadBadge != null) {
            boolean hasUnread = mAgentFloatingView != null && mAgentFloatingView.hasUnread();
            boolean showBadge = hasUnread && (mAgentFloatingView == null || !mAgentFloatingView.isShowing());
            mAgentUnreadBadge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
        }
    }

    private void updateNavButtons() {
        BrowserController.TabSession tab = mController != null ? mController.getActiveTab() : null;
        boolean canBack = tab != null && tab.webView.canGoBack();
        boolean canForward = tab != null && tab.webView.canGoForward();
        int activeIcon = AcidTheme.icon(mIsDarkMode);
        int disabledIcon = AcidTheme.iconDisabled(mIsDarkMode);
        mIconBack.setColor(canBack ? activeIcon : disabledIcon);
        mIconForward.setColor(canForward ? activeIcon : disabledIcon);
    }

    // =========================================================================
    // BrowserController.BrowserStateListener Callbacks
    // =========================================================================

    @Override
    public void onUrlChanged(final String url, final String title) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!mEtUrl.hasFocus()) {
                    mEtUrl.setText(mController != null && mController.isHomeUrl(url) ? "acid://home" : url);
                }
                if (mDataStore != null) {
                    mDataStore.recordHistory(url, title, mIncognitoMode);
                }
                updateNavButtons();
            }
        });
    }

    @Override
    public void onProgressChanged(final int progress, final boolean isLoading) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mProgressBar != null) {
                    if (mIsFullscreenMode) {
                        mProgressBar.setVisibility(View.GONE);
                    } else if (isLoading) {
                        mProgressBar.setPivotX(0f);
                        float scale = Math.max(0.06f, Math.min(1.0f, progress / 100f));
                        mProgressBar.setScaleX(scale);
                        mProgressBar.setVisibility(View.VISIBLE);
                    } else {
                        mProgressBar.setScaleX(1.0f);
                        mProgressBar.setVisibility(View.INVISIBLE);
                    }
                }
                mBtnReload.setIcon(isLoading ? AcidIcons.ICON_CLOSE : AcidIcons.ICON_RELOAD);
                updateNavButtons();
            }
        });
    }

    private static String cleanSingleLine(String text) {
        if (text == null) return "";
        String s = text.replace('\r', ' ').replace('\n', ' ').trim();
        return s.replaceAll("\\s+", " ");
    }

    private String formatAgentActivitySummary(AcidSessionStore.UiMessageItem item) {
        if (item == null) return "";
        if (AcidSessionStore.TYPE_TOOL.equals(item.type)) {
            String s = item.toolSummary;
            if (s == null || s.trim().isEmpty()) s = item.toolName;
            return "[工具] " + (s != null ? s.trim() : "");
        }
        if (AcidSessionStore.TYPE_THINKING.equals(item.type)) {
            String t = cleanSingleLine(item.text);
            if (t.isEmpty()) return "思考中...";
            if (t.length() > 34) t = t.substring(0, 32) + "...";
            return "思考: " + t;
        }
        if (AcidSessionStore.TYPE_ASSISTANT.equals(item.type)) {
            String t = cleanSingleLine(item.text);
            if (t.isEmpty()) return "正在回答...";
            if (t.length() > 34) t = t.substring(0, 32) + "...";
            return "回答: " + t;
        }
        if (AcidSessionStore.TYPE_ERROR.equals(item.type)) {
            String t = cleanSingleLine(item.text);
            if (t.length() > 34) t = t.substring(0, 32) + "...";
            return "异常: " + t;
        }
        return "";
    }

    private void refreshTakeoverBanner() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mController == null || mTakeoverBar == null || mTvTakeoverStatus == null || mBtnTakeoverAction == null) return;
                int controlMode = mController.getControlMode();
                int accent = AcidTheme.accent(mIsDarkMode);

                if (controlMode == AgentOverlayView.MODE_AGENT) {
                    mTakeoverBar.setVisibility(View.VISIBLE);
                    mTakeoverIcon.setIcon(AcidIcons.ICON_AGENT);
                    mTakeoverIcon.setColor(accent);

                    boolean isDrivingCurrent = mController.isAgentDrivingCurrentTab();

                    // 1. 文本内容100%复用单一事实源，前台受控页与后台浏览页均展示当前实时工具/思考/回答摘要
                    String summary = mLastAgentActivitySummary;
                    if (summary == null || summary.trim().isEmpty()) {
                        summary = isDrivingCurrent ? "自动化运行中 · 页面触控已锁定" : "后台自动化进行中 · 当前页可自由浏览";
                    }
                    mTvTakeoverStatus.setText(AcidLocale.tr(summary));
                    mTvTakeoverStatus.setTextColor(accent);

                    // 2. 前台受控与后台非受控唯一的区别：右侧动作按钮不同
                    if (isDrivingCurrent) {
                        mBtnTakeoverAction.update(AcidIcons.ICON_HUMAN, "点此接管", AcidTheme.COLOR_AMBER);
                    } else {
                        mBtnTakeoverAction.update(AcidIcons.ICON_GLOBE, "点击查看", accent);
                    }
                } else if (controlMode == AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
                    mTakeoverBar.setVisibility(View.VISIBLE);
                    String reason = mController.getTakeoverReason();
                    mTakeoverIcon.setIcon(AcidIcons.ICON_HUMAN);
                    mTakeoverIcon.setColor(AcidTheme.COLOR_AMBER);
                    mTvTakeoverStatus.setText(AcidLocale.tr("请人工协助: " + (reason.isEmpty() ? "请完成页面操作" : reason)));
                    mTvTakeoverStatus.setTextColor(AcidTheme.COLOR_AMBER);
                    mBtnTakeoverAction.update(AcidIcons.ICON_AGENT, "交还AI", accent);
                } else {
                    mTakeoverBar.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override
    public void onModeChanged(final boolean isDesktopMode, final int controlMode, final boolean somEnabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mController == null) return;
                updateTopAgentButton(
                    mAgentLoop != null && mAgentLoop.isRunning(),
                    mAgentLoop != null && mAgentLoop.isPausedForTakeover()
                );
                refreshTakeoverBanner();
                updateMenuSheetCards();
            }
        });
    }

    @Override
    public void onDeepLinkBlocked(final String blockedUri, final int totalBlocked) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBtnShield.setColor(AcidTheme.COLOR_AMBER);
                AcidFeedback.info(BrowserActivity.this, "已拦截 App 唤端跳转 (共 " + totalBlocked + " 次)");
            }
        });
    }

    @Override
    public void onAppLaunchConfirmRequested(final String url, final String appName, final Intent launchIntent) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                String shortUrl = url != null && url.length() > 90 ? url.substring(0, 90) + "..." : (url != null ? url : "");
                new AcidDialog(BrowserActivity.this, mIsDarkMode)
                    .setTitle("允许打开第三方应用？")
                    .setMessage("当前网页请求唤起外部应用「" + appName + "」\n\n目标链接: " + shortUrl)
                    .setPositiveButton("确定", new AcidDialog.OnActionListener() {
                        @Override
                        public boolean onAction(android.app.Dialog dialog) {
                            try {
                                startActivity(launchIntent);
                            } catch (Exception e) {
                                AcidFeedback.warn(BrowserActivity.this, "无法启动应用: " + appName);
                            }
                            return true;
                        }
                    })
                    .setNegativeButton("取消", new AcidDialog.OnActionListener() {
                        @Override
                        public boolean onAction(android.app.Dialog dialog) {
                            if (mController != null) {
                                mController.recordBlockedDeepLink(url);
                            }
                            return true;
                        }
                    })
                    .show();
            }
        });
    }

    @Override
    public void onTabsChanged(final int tabCount, final int activeTabId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mIconTabs.setCount(tabCount);
                updateNavButtons();
                if (mTabsOverview != null && mTabsOverview.isShowing()) {
                    mTabsOverview.renderTabsGrid();
                }
                if (mController != null) {
                    onModeChanged(mController.isDesktopMode(), mController.getControlMode(), mController.isSomEnabled());
                }
            }
        });
    }

    @Override
    public void onDownloadRequested(
        final String url,
        final String fileName,
        final String userAgent,
        final String contentDisposition,
        final String mimeType,
        final long contentLength
    ) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mDownloadConfirmSheet != null) {
                    mDownloadConfirmSheet.prepareConfirm(
                        url, fileName, userAgent, contentDisposition, mimeType, contentLength
                    );
                    mOverlayStack.openRootModal(mDownloadConfirmSheet);
                }
            }
        });
    }

    @Override
    public void onUserscriptInstallRequested(final String url, final String fileName) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mUserscriptSheet != null) {
                    mUserscriptSheet.requestInstallFromUrl(url, fileName);
                }
            }
        });
    }

    @Override
    public void onSettingsReloaded() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                syncFromHotReloadedSettings();
            }
        });
    }

    @Override
    public void onShowCustomView(final View view, final WebChromeClient.CustomViewCallback callback) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (view == null) return;
                mOriginalOrientation = getRequestedOrientation();
                mCustomViewContainer.removeAllViews();
                mCustomViewContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ));
                mCustomViewContainer.setVisibility(View.VISIBLE);
                mMainContentLayout.setVisibility(View.INVISIBLE);
                applyFullscreenWindowFlags(true);
                view.setKeepScreenOn(true);
            }
        });
    }

    @Override
    public void onHideCustomView() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCustomViewContainer.setVisibility(View.GONE);
                mCustomViewContainer.removeAllViews();
                mMainContentLayout.setVisibility(View.VISIBLE);
                setRequestedOrientation(mOriginalOrientation);
                applyFullscreenWindowFlags(mIsFullscreenMode);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (mController != null && mController.isCustomViewShowing()) {
            mController.hideCustomView();
            return;
        }
        if (mIsFullscreenMode) {
            setFullscreenMode(false);
            return;
        }
        if (mOverlayStack.handleBackPressed()) {
            return;
        }
        BrowserController.TabSession tab = mController != null ? mController.getActiveTab() : null;
        if (tab != null && tab.webView.canGoBack()) {
            tab.webView.goBack();
            return;
        }
        if (mController != null && mController.getTabs().size() > 1 && tab != null) {
            if (mController.isTabDrivenByAgent(tab.id)) {
                if (mController.switchToPreviousTab(tab.id)) {
                    AcidFeedback.info(this, "Agent 仍在后台继续操作原标签页");
                    return;
                }
                super.onBackPressed();
                return;
            }
            mController.closeTab(tab.id);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mController != null) {
            mController.onResume();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (AcidLocale.onSystemConfigurationChanged(this, newConfig, mDataStore)) {
            applyLanguageHotSwitch();
        }
        if (mIsFullscreenMode) {
            applyFullscreenWindowFlags(true);
        }
        if (mAgentFloatingView != null && mAgentFloatingView.isShowing()) {
            mAgentFloatingView.post(new Runnable() {
                @Override
                public void run() {
                    mAgentFloatingView.requestLayout();
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mController != null) {
            mController.onPause();
        }
        if (mController != null && mController.getUserscriptManager() != null) {
            try {
                mController.getUserscriptManager().getStorage().flushSync();
            } catch (Exception ignored) {}
        }
        // Unconditional: the throttled page-load flush may have skipped recent cookies, and
        // this is the last reliable point before the process can be killed in the background.
        if (mController != null) {
            mController.flushCookiesNow();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mController != null) {
            mController.flushCookiesNow();
        }
    }

    @Override
    protected void onDestroy() {
        if (mController != null && mController.getUserscriptManager() != null) {
            try {
                mController.getUserscriptManager().getStorage().flushSync();
            } catch (Exception ignored) {}
        }
        if (mController != null) {
            mController.flushCookiesNow();
        }
        if (isFinishing() && mDataStore != null && mDataStore.isClearOnExit()) {
            mDataStore.clearBrowsingData(mController, true, true, false, true, false);
        }
        if (mDownloadReceiver != null) {
            try {
                unregisterReceiver(mDownloadReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Download receiver was not registered at teardown", e);
            }
        }
        if (mAgentLoop != null) {
            mAgentLoop.stopLoop();
        }
        if (mHttpServer != null) {
            mHttpServer.stop();
        }
        if (mController != null) {
            mController.destroy();
        }
        if (mDataStore != null) {
            mDataStore.stopWatchingSettingsFile();
        }
        super.onDestroy();
    }
}
