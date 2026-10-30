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
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private AdBlockSheet mAdBlockSheet;
    private BrowserSettingsSheet mSettingsSheet;
    private AboutSheet mAboutSheet;
    private DownloadConfirmSheet mDownloadConfirmSheet;

    private BroadcastReceiver mDownloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mDataStore = new BrowserDataStore(this);
        mIsDarkMode = mDataStore.isDarkMode();
        getWindow().setBackgroundDrawable(new ColorDrawable(AcidTheme.bg(mIsDarkMode)));

        setContentView(buildContentView());

        mController = new BrowserController(this, mWebViewContainer, mOverlayView, this, mDataStore, mIsDarkMode);
        mDataStore.setOnSettingsChangedListener(new BrowserDataStore.OnSettingsChangedListener() {
            @Override
            public void onSettingsChanged() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        syncFromHotReloadedSettings();
                    }
                });
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
        initModalSheets();

        mHttpServer = new HttpControlServer(mController, HttpControlServer.DEFAULT_PORT);
        if (mDataStore.isMcpServerEnabled()) {
            mHttpServer.start();
        }

        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (mController != null) {
                        mController.onDownloadCompleted(id);
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
                updateTopAgentButton(running, pausedForTakeover);
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
            }

            @Override
            public void onUiItemUpdated(AcidSessionStore.UiMessageItem item) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.updateUiMessageItem(item);
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
        mDownloadsSheet = new DownloadsSheet(this, mController);
        attachModalSheet(mDownloadsSheet);

        // 4. Download Confirmation
        mDownloadConfirmSheet = new DownloadConfirmSheet(this, mController);
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
                    mController.startDownload(
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
                            Toast.makeText(BrowserActivity.this, "已执行「" + meta.name + "」", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(
                                BrowserActivity.this,
                                "「" + meta.name + "」不适用于当前页面 (" + meta.describeScope() + ")",
                                Toast.LENGTH_LONG
                            ).show();
                        }
                    }
                }

                @Override
                public void onScriptsChanged() {
                    updateMenuSheetCards();
                }
            }
        );
        attachModalSheet(mUserscriptSheet);

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
                    mProviderConfigSheet.showConfig();
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

        // 10. Settings Center (Hierarchical L1/L2)
        mSettingsSheet = new BrowserSettingsSheet(
            this, mDataStore,
            new BrowserSettingsSheet.OnSettingsActionListener() {
                @Override
                public void onOpenModelConfig() {
                    mProviderConfigSheet.showConfig();
                    mOverlayStack.pushModal(mSettingsSheet, mProviderConfigSheet);
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
                public void onOpenAbout() {
                    mAboutSheet.showAbout();
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
                    Toast.makeText(BrowserActivity.this, msg, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onClearWorkspaceOnlyRequested() {
                    int cleaned = mDataStore.clearWorkspaceTempFiles(mController.getWorkspaceDir());
                    String msg = cleaned > 0
                        ? ("已清理 AI 工作区 (" + cleaned + " 个临时项，settings.json 与脚本已保留)")
                        : "AI 工作区当前无临时残留 (settings.json 与脚本已安全保留)";
                    Toast.makeText(BrowserActivity.this, msg, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onSearchEngineChanged(int index, String customUrl) {}

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
                    Toast.makeText(
                        BrowserActivity.this,
                        enabled ? "已开启网页远程调试 (Chrome DevTools)" : "已关闭网页远程调试",
                        Toast.LENGTH_SHORT
                    ).show();
                }

                @Override
                public void onDoNotTrackChanged(boolean enabled) {
                    Toast.makeText(
                        BrowserActivity.this,
                        enabled ? "已开启「不跟踪 (DNT)」请求" : "已关闭「不跟踪」请求",
                        Toast.LENGTH_SHORT
                    ).show();
                }

                @Override
                public void onBlockImagesChanged(boolean enabled) {
                    if (mController != null) {
                        mController.setBlockImages(enabled);
                    }
                    Toast.makeText(
                        BrowserActivity.this,
                        enabled ? "已开启无图省流模式" : "已恢复正常图片加载",
                        Toast.LENGTH_SHORT
                    ).show();
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
                mBookmarksHistorySheet.showBookmarks(
                    cur != null ? cur.title : "",
                    cur != null ? cur.url : ""
                );
                mOverlayStack.openRootModal(mBookmarksHistorySheet);
            }

            @Override
            public void onOpenHistory() {
                BrowserController.TabSession cur = mController.getActiveTab();
                mBookmarksHistorySheet.showHistory(
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
                Toast.makeText(
                    BrowserActivity.this,
                    nextDesktop ? "已切换为电脑端视口 (1280px)" : "已切换为移动端视口",
                    Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onOpenDownloads() {
                mDownloadsSheet.showDownloads();
                mOverlayStack.openRootModal(mDownloadsSheet);
            }

            @Override
            public void onOpenAgentFloatingWindow() {
                mAgentFloatingView.toggle();
            }

            @Override
            public void onOpenModelProviderConfig() {
                mProviderConfigSheet.showConfig();
                mOverlayStack.openRootModal(mProviderConfigSheet);
            }

            @Override
            public void onOpenAbout() {
                mAboutSheet.showAbout();
                mOverlayStack.openRootModal(mAboutSheet);
            }

            @Override
            public void onToggleMcpServer() {
                toggleMcpServer();
            }

            @Override
            public void onOpenUserscripts() {
                mUserscriptSheet.showScripts();
                mOverlayStack.openRootModal(mUserscriptSheet);
            }

            @Override
            public void onOpenAdBlock() {
                mAdBlockSheet.showAdBlock();
                mOverlayStack.openRootModal(mAdBlockSheet);
            }

            @Override
            public void onOpenSettings() {
                mSettingsSheet.setMcpRunning(mHttpServer != null && mHttpServer.isRunning());
                mSettingsSheet.showSettings();
                mOverlayStack.openRootModal(mSettingsSheet);
            }
        });
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

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && mController != null) {
                mController.loadUrl(data.toString());
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
                    mAdBlockSheet.showAdBlock();
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
        mEtUrl.setHint("搜索或输入网址...");
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
                        mTabsOverview.close();
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
        mAgentUnreadBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        mAgentUnreadBadge.setTypeface(Typeface.DEFAULT_BOLD);
        mAgentUnreadBadge.setGravity(Gravity.CENTER);
        int badgeSize = dp(13);
        mAgentUnreadBadge.setBackground(AcidTheme.pillBg(this, AcidTheme.COLOR_RED, Color.WHITE, 6.5f));
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
        return mRootContainer;
    }

    private LinearLayout buildTakeoverBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));

        mTakeoverIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HUMAN, AcidTheme.COLOR_AMBER, 16);
        bar.addView(mTakeoverIcon);

        mTvTakeoverStatus = new TextView(this);
        mTvTakeoverStatus.setText("AI 请求接管");
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
                    mTabsOverview.close();
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
                    mTabsOverview.close();
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
                    mTabsOverview.close();
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
        updateMenuSheetCards();
        if (mSettingsSheet != null) {
            mSettingsSheet.setMcpRunning(mHttpServer != null && mHttpServer.isRunning());
            mSettingsSheet.refresh();
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
        Toast.makeText(
            this,
            mIncognitoMode
                ? "已开启无痕模式 (禁用历史记录、表单保存与磁盘缓存)"
                : "已退出无痕模式 (已自动清理临时缓存与会话 Cookie)",
            Toast.LENGTH_SHORT
        ).show();
    }

    private void toggleMcpServer() {
        if (mHttpServer == null) return;
        boolean currentlyRunning = mHttpServer.isRunning();
        if (currentlyRunning) {
            mHttpServer.stop();
            mDataStore.setMcpServerEnabled(false);
            Toast.makeText(this, "MCP 服务已停止 (3090 端口已释放)", Toast.LENGTH_SHORT).show();
        } else {
            mHttpServer.start();
            mDataStore.setMcpServerEnabled(true);
            Toast.makeText(this, "MCP 服务已启动 (127.0.0.1:3090)", Toast.LENGTH_SHORT).show();
        }
        updateMenuSheetCards();
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
            providerCount,
            modelCount
        );
        mMenuSheet.updateMcpState(mHttpServer != null && mHttpServer.isRunning());
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
        mSearchContainer.setBackground(AcidTheme.pillBg(this, searchBg, searchBorder, 18));
        mSearchIcon.setIcon(mIncognitoMode ? AcidIcons.ICON_INCOGNITO : AcidIcons.ICON_SEARCH);
        mSearchIcon.setColor(mIncognitoMode ? AcidTheme.COLOR_PURPLE : AcidTheme.muted(mIsDarkMode));
        mEtUrl.setHint(mIncognitoMode ? "无痕模式 · 搜索或输入网址..." : "搜索或输入网址...");
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
        mReloadBox.setBackground(AcidTheme.interactiveBg(this, Color.TRANSPARENT, pressed, 0, 14));
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
    }

    private void updateTopAgentButton(boolean running, boolean pausedForTakeover) {
        if (mIconTopAgent == null || mBtnTopAgent == null) return;
        if (running) {
            int yellow = AcidTheme.COLOR_AMBER;
            mIconTopAgent.setColor(yellow);
            mBtnTopAgent.setBackground(AcidTheme.interactiveBg(
                this, AcidTheme.softTint(yellow, 32), AcidTheme.softTint(yellow, 64), yellow, 12
            ));
        } else if (pausedForTakeover) {
            int orange = AcidTheme.COLOR_ORANGE;
            mIconTopAgent.setColor(orange);
            mBtnTopAgent.setBackground(AcidTheme.interactiveBg(
                this, AcidTheme.softTint(orange, 32), AcidTheme.softTint(orange, 64), orange, 12
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
                    mEtUrl.setText(BrowserController.HOME_URL.equals(url) ? "acid://home" : url);
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
                mProgressBar.setVisibility(isLoading ? View.VISIBLE : View.INVISIBLE);
                mBtnReload.setIcon(isLoading ? AcidIcons.ICON_CLOSE : AcidIcons.ICON_RELOAD);
                updateNavButtons();
            }
        });
    }

    @Override
    public void onModeChanged(final boolean isDesktopMode, final int controlMode, final boolean somEnabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mController == null) return;
                int accent = AcidTheme.accent(mIsDarkMode);
                updateTopAgentButton(
                    mAgentLoop != null && mAgentLoop.isRunning(),
                    mAgentLoop != null && mAgentLoop.isPausedForTakeover()
                );

                int blocked = mController.getBlockedDeepLinksCount();
                if (controlMode == AgentOverlayView.MODE_AGENT) {
                    mTakeoverBar.setVisibility(View.VISIBLE);
                    mTakeoverIcon.setIcon(AcidIcons.ICON_AGENT);
                    mTakeoverIcon.setColor(accent);
                    boolean isDrivingCurrent = mController.isAgentDrivingCurrentTab();
                    mTvTakeoverStatus.setText(isDrivingCurrent
                        ? ("AI 自动驾驶中 (当前页已锁) | 拦跳: " + blocked)
                        : "AI 正在后台标签页执行自动化 (当前页可自由浏览)");
                    mTvTakeoverStatus.setTextColor(accent);
                    mBtnTakeoverAction.update(AcidIcons.ICON_HUMAN, "点此接管", AcidTheme.COLOR_AMBER);
                } else if (controlMode == AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
                    mTakeoverBar.setVisibility(View.VISIBLE);
                    String reason = mController.getTakeoverReason();
                    mTakeoverIcon.setIcon(AcidIcons.ICON_HUMAN);
                    mTakeoverIcon.setColor(AcidTheme.COLOR_AMBER);
                    mTvTakeoverStatus.setText("请人工协助: " + (reason.isEmpty() ? "请完成页面操作" : reason));
                    mTvTakeoverStatus.setTextColor(AcidTheme.COLOR_AMBER);
                    mBtnTakeoverAction.update(AcidIcons.ICON_AGENT, "交还AI", accent);
                } else {
                    mTakeoverBar.setVisibility(View.GONE);
                }
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
                Toast.makeText(BrowserActivity.this, "已拦截 App 唤端跳转 (共 " + totalBlocked + " 次)", Toast.LENGTH_SHORT).show();
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
    public void onAgentActionLog(final String logLine) {}

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
                    mDownloadConfirmSheet.showConfirm(
                        url, fileName, userAgent, contentDisposition, mimeType, contentLength
                    );
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
    public void onBackPressed() {
        if (mOverlayStack.handleBackPressed()) {
            return;
        }
        BrowserController.TabSession tab = mController != null ? mController.getActiveTab() : null;
        if (tab != null && tab.webView.canGoBack()) {
            tab.webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (mDataStore != null && mDataStore.isClearOnExit()) {
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
        super.onDestroy();
    }
}
