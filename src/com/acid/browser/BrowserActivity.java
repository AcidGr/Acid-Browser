package com.acid.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;

import org.json.JSONObject;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Modern Clean White Browser Edition (Phase 3.2 Modular Architecture):
 * - Thin orchestrator activity managing lifecycle, navigation bars, and top-level modals
 * - Pure SVG Path vector icons via AcidIcons
 * - Classic 5-button bottom toolbar: [Back] [Forward] [Home (Center)] [Tabs] [Menu (Right)]
 * - Full modularization: Views and Dialogs encapsulated in independent components
 */
public class BrowserActivity extends Activity implements BrowserController.BrowserStateListener {

    private BrowserDataStore mDataStore;
    private BrowserController mController;
    private HttpControlServer mHttpServer;
    private AcidAgentLoop mAgentLoop;

    private boolean mIsDarkMode = false;
    private boolean mIncognitoMode = false;

    // Root Containers
    private FrameLayout mRootContainer;
    private LinearLayout mMainContentLayout;

    // Top Bar
    private LinearLayout mTopBar;
    private View mTopBarDivider;
    private AcidIcons.SvgIconView mBtnShield;
    private LinearLayout mSearchContainer;
    private AcidIcons.SvgIconView mSearchIcon;
    private EditText mEtUrl;
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

    // Modular Sub-Components
    private TabsOverviewView mTabsOverview;
    private AgentFloatingView mAgentFloatingView;
    private AgentSessionsSheet mSessionsSheet;
    private BrowserMenuSheet mMenuSheet;
    private BrowserListsSheet mListsSheet;
    private AgentModelSelectorSheet mModelSelectorSheet;
    private ModelProviderConfigSheet mProviderConfigSheet;
    private AcidBottomSheetView mAboutSheet;
    private AcidBottomSheetView mDownloadConfirmSheet;
    private BroadcastReceiver mDownloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mDataStore = new BrowserDataStore(this);
        mIsDarkMode = mDataStore.isDarkMode();
        getWindow().setBackgroundDrawable(new ColorDrawable(AcidTheme.bg(mIsDarkMode)));

        View root = buildContentView();
        setContentView(root);

        mController = new BrowserController(this, mWebViewContainer, mOverlayView, this, mIsDarkMode);
        mController.setDataStore(mDataStore);

        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }, 101);
            }
        }

        // 1. Initialize Tabs Overview View
        mTabsOverview = new TabsOverviewView(this, mController);
        mCenterFrame.addView(mTabsOverview, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 2. Initialize Agent Loop & Agent Floating View
        mAgentLoop = new AcidAgentLoop(this, mController, new AcidAgentLoop.AgentLoopListener() {
            @Override
            public void onLoopStateChanged(boolean running, boolean pausedForTakeover, int step) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.onLoopStateChanged(running, pausedForTakeover, step);
                }
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
                    if (!mAgentFloatingView.isShowing()) {
                        if (item != null && (AcidSessionStore.TYPE_ASSISTANT.equals(item.type) || AcidSessionStore.TYPE_ERROR.equals(item.type))) {
                            mAgentFloatingView.setHasUnread(true);
                        }
                    }
                    mAgentFloatingView.appendUiMessageItemView(item, true);
                }
            }

            @Override
            public void onUiItemUpdated(AcidSessionStore.UiMessageItem item) {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.updateToolCard(item);
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
                    mModelSelectorSheet.showSelector();
                }
            }

            @Override
            public void onOpenSessionsSheet() {
                if (mSessionsSheet != null) {
                    mSessionsSheet.showSessions();
                }
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

        // 3. Initialize Top-Level Modal Sheets
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
        mRootContainer.addView(mSessionsSheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mMenuSheet = new BrowserMenuSheet(this, new BrowserMenuSheet.MenuActionListener() {
            @Override
            public void onToggleDarkMode() {
                toggleDarkMode();
            }

            @Override
            public void onToggleIncognitoMode() {
                mIncognitoMode = !mIncognitoMode;
                updateMenuSheetCards();
                Toast.makeText(
                    BrowserActivity.this,
                    mIncognitoMode ? "已开启无痕模式 (不记录浏览足迹与缓存)" : "已恢复正常浏览模式",
                    Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onOpenBookmarks() {
                BrowserController.TabSession cur = mController.getActiveTab();
                mListsSheet.showBookmarks(cur != null ? cur.title : "", cur != null ? cur.url : "");
            }

            @Override
            public void onOpenHistory() {
                mListsSheet.showHistory();
            }

            @Override
            public void onToggleDesktopMode() {
                boolean nextDesktop = !mController.isDesktopMode();
                mController.setDesktopMode(nextDesktop, true);
                updateMenuSheetCards();
                Toast.makeText(
                    BrowserActivity.this,
                    nextDesktop ? "已切换为电脑端 (1280px 宽视口)" : "已切换为移动端",
                    Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onOpenDownloads() {
                mListsSheet.showDownloads();
            }

            @Override
            public void onOpenAgentFloatingWindow() {
                mAgentFloatingView.toggle();
            }

            @Override
            public void onOpenModelProviderConfig() {
                mProviderConfigSheet.showConfig();
            }

            @Override
            public void onOpenAbout() {
                showAboutSheet();
            }
        });
        mRootContainer.addView(mMenuSheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mAboutSheet = new AcidBottomSheetView(this);
        mAboutSheet.setTitle("关于");
        mRootContainer.addView(mAboutSheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mDownloadConfirmSheet = new AcidBottomSheetView(this);
        mDownloadConfirmSheet.setTitle("下载确认");
        mRootContainer.addView(mDownloadConfirmSheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mListsSheet = new BrowserListsSheet(this, mDataStore, mController, new BrowserListsSheet.OnListActionListener() {
            @Override
            public void onNavigateUrl(String url) {
                mController.loadUrl(url);
            }
        });
        mRootContainer.addView(mListsSheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mModelSelectorSheet = new AgentModelSelectorSheet(this, mAgentLoop.getModelStore(), new AgentModelSelectorSheet.ModelSelectorListener() {
            @Override
            public void onOpenModelProviderConfig() {
                mProviderConfigSheet.showConfig();
            }

            @Override
            public void onModelConfigChanged() {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.refreshAgentStatusLabel();
                }
                updateMenuSheetCards();
            }
        });
        mRootContainer.addView(mModelSelectorSheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mProviderConfigSheet = new ModelProviderConfigSheet(this, mAgentLoop.getModelStore(), new ModelProviderConfigSheet.OnConfigChangeListener() {
            @Override
            public void onProviderConfigChanged() {
                if (mAgentFloatingView != null) {
                    mAgentFloatingView.refreshAgentStatusLabel();
                }
                updateMenuSheetCards();
            }
        });
        mRootContainer.addView(mProviderConfigSheet, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mHttpServer = new HttpControlServer(mController, HttpControlServer.DEFAULT_PORT);
        mHttpServer.start();

        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (mController != null) {
                        mController.onDownloadCompleted(id);
                    }
                    if (mListsSheet != null && mListsSheet.isShowing()) {
                        mListsSheet.refreshDownloads();
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

        // Shield
        LinearLayout shieldBox = new LinearLayout(this);
        shieldBox.setGravity(Gravity.CENTER);
        shieldBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        mBtnShield = new AcidIcons.SvgIconView(this, AcidIcons.ICON_SHIELD, AcidTheme.LIGHT_ACCENT, 18);
        shieldBox.addView(mBtnShield);
        shieldBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int blocked = mController.getBlockedDeepLinksCount();
                Toast.makeText(BrowserActivity.this, "Acid 安全护盾: 已成功拦截 " + blocked + " 次恶意唤醒跳转", Toast.LENGTH_SHORT).show();
            }
        });
        mTopBar.addView(shieldBox);

        // Search Container
        mSearchContainer = new LinearLayout(this);
        mSearchContainer.setOrientation(LinearLayout.HORIZONTAL);
        mSearchContainer.setGravity(Gravity.CENTER_VERTICAL);
        mSearchContainer.setPadding(dp(8), dp(2), dp(8), dp(2));
        mSearchContainer.setBackground(AcidTheme.pillBg(this, AcidTheme.LIGHT_SEARCH_BG, AcidTheme.LIGHT_SEARCH_BORDER, 18));

        mSearchIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_SEARCH, AcidTheme.LIGHT_MUTED, 14);
        mSearchContainer.addView(mSearchIcon);

        mEtUrl = new EditText(this);
        mEtUrl.setSingleLine(true);
        mEtUrl.setHint("搜索或输入网址...");
        mEtUrl.setHintTextColor(AcidTheme.LIGHT_MUTED);
        mEtUrl.setTextColor(AcidTheme.LIGHT_TEXT);
        mEtUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mEtUrl.setBackgroundColor(Color.TRANSPARENT);
        mEtUrl.setPadding(dp(6), dp(4), dp(6), dp(4));
        mEtUrl.setImeOptions(EditorInfo.IME_ACTION_GO);
        mEtUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mEtUrl.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    hideKeyboard();
                    if (mTabsOverview != null && mTabsOverview.isShowing()) {
                        mTabsOverview.close();
                    }
                    mController.loadUrl(mEtUrl.getText().toString());
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mSearchContainer.addView(mEtUrl, etLp);

        // Reload
        LinearLayout reloadBox = new LinearLayout(this);
        reloadBox.setGravity(Gravity.CENTER);
        reloadBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        mBtnReload = new AcidIcons.SvgIconView(this, AcidIcons.ICON_RELOAD, AcidTheme.LIGHT_MUTED, 15);
        reloadBox.addView(mBtnReload);
        reloadBox.setOnClickListener(new View.OnClickListener() {
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
        mSearchContainer.addView(reloadBox);

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchLp.setMargins(dp(6), 0, dp(6), 0);
        mTopBar.addView(mSearchContainer, searchLp);

        // Agent Trigger Button
        mBtnTopAgent = new FrameLayout(this);
        int btnW = dp(34);
        int btnH = dp(34);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnW, btnH);
        btnLp.gravity = Gravity.CENTER_VERTICAL;
        btnLp.leftMargin = dp(2);
        btnLp.rightMargin = dp(2);
        mBtnTopAgent.setLayoutParams(btnLp);

        int iconSize = dp(19);
        mIconTopAgent = new AcidIcons.SvgIconView(this, AcidIcons.ICON_AGENT, AcidTheme.LIGHT_ICON, 19);
        mBtnTopAgent.addView(mIconTopAgent, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        mAgentUnreadBadge = new TextView(this);
        mAgentUnreadBadge.setText("!");
        mAgentUnreadBadge.setTextColor(Color.WHITE);
        mAgentUnreadBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        mAgentUnreadBadge.setTypeface(Typeface.DEFAULT_BOLD);
        mAgentUnreadBadge.setGravity(Gravity.CENTER);
        int badgeSize = dp(13);
        mAgentUnreadBadge.setBackground(AcidTheme.pillBg(this, AcidTheme.COLOR_RED, Color.WHITE, 6.5f));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP | Gravity.END);
        mAgentUnreadBadge.setLayoutParams(badgeLp);
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

        mMainContentLayout.addView(mTopBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mTopBarDivider = new View(this);
        mTopBarDivider.setBackgroundColor(AcidTheme.LIGHT_BORDER);
        mMainContentLayout.addView(mTopBarDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        mProgressBar = new View(this);
        mProgressBar.setBackgroundColor(AcidTheme.LIGHT_ACCENT);
        mMainContentLayout.addView(mProgressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        mTakeoverBar = buildTakeoverBanner();
        mTakeoverBar.setVisibility(View.GONE);
        mMainContentLayout.addView(mTakeoverBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Center Viewport ---
        mCenterFrame = new FrameLayout(this);
        mCenterFrame.setBackgroundColor(AcidTheme.bg(mIsDarkMode));
        mWebViewContainer = new FrameLayout(this);
        mWebViewContainer.setBackgroundColor(AcidTheme.bg(mIsDarkMode));
        mOverlayView = new AgentOverlayView(this);
        mCenterFrame.addView(mWebViewContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCenterFrame.addView(mOverlayView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        mMainContentLayout.addView(mCenterFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Bottom 5-Button Toolbar ---
        mBottomDivider = new View(this);
        mBottomDivider.setBackgroundColor(AcidTheme.LIGHT_BORDER);
        mMainContentLayout.addView(mBottomDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        mBottomBar = buildBottomToolbar();
        mMainContentLayout.addView(mBottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        mRootContainer.addView(mMainContentLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return mRootContainer;
    }

    private LinearLayout buildTakeoverBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#FFFBEB"));
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));

        mTakeoverIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HUMAN, AcidTheme.COLOR_AMBER, 16);
        bar.addView(mTakeoverIcon);

        mTvTakeoverStatus = new TextView(this);
        mTvTakeoverStatus.setText("AI 请求接管");
        mTvTakeoverStatus.setTextColor(AcidTheme.COLOR_AMBER);
        mTvTakeoverStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mTvTakeoverStatus.setSingleLine(true);
        mTvTakeoverStatus.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
        bar.setBackgroundColor(AcidTheme.LIGHT_BAR);

        // 1. Back
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

        // 2. Forward
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

        // 3. Home
        mBtnHome = createBottomTabItem();
        mIconHome = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HOME, AcidTheme.LIGHT_ICON, 21);
        mBtnHome.addView(mIconHome);
        mBtnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverview != null && mTabsOverview.isShowing()) {
                    mTabsOverview.close();
                }
                mController.loadUrl(BrowserController.HOME_URL);
            }
        });
        bar.addView(mBtnHome, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 4. Tabs
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

        // 5. Menu
        mBtnMenu = createBottomTabItem();
        mIconMenu = new AcidIcons.SvgIconView(this, AcidIcons.ICON_MENU, AcidTheme.LIGHT_ICON, 20);
        mBtnMenu.addView(mIconMenu);
        mBtnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMenuSheet != null) {
                    updateMenuSheetCards();
                    mMenuSheet.toggle();
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

    private void applyTheme(boolean dark) {
        int bg = AcidTheme.bg(dark);
        int bar = AcidTheme.bar(dark);
        int border = AcidTheme.border(dark);
        int searchBg = AcidTheme.searchBg(dark);
        int searchBorder = AcidTheme.searchBorder(dark);
        int text = AcidTheme.text(dark);
        int muted = AcidTheme.muted(dark);
        int icon = AcidTheme.icon(dark);
        int accent = AcidTheme.accent(dark);

        // System Status & Nav Bar
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

        mSearchContainer.setBackground(AcidTheme.pillBg(this, searchBg, searchBorder, 18));
        mSearchIcon.setColor(muted);
        mEtUrl.setTextColor(text);
        mEtUrl.setHintTextColor(muted);
        mBtnReload.setColor(muted);

        mProgressBar.setBackgroundColor(accent);
        mBtnShield.setColor(accent);

        updateNavButtons();
        mIconHome.setColor(icon);
        mIconTabs.setColor(icon);
        mIconMenu.setColor(icon);

        updateTopAgentButton(mAgentLoop != null && mAgentLoop.isRunning(), mAgentLoop != null && mAgentLoop.isPausedForTakeover());

        // Propagate theme to modular sub-components
        if (mTabsOverview != null) mTabsOverview.applyTheme(dark);
        if (mAgentFloatingView != null) mAgentFloatingView.applyTheme(dark);
        if (mSessionsSheet != null) mSessionsSheet.applyTheme(dark);
        if (mMenuSheet != null) mMenuSheet.applyTheme(dark);
        if (mListsSheet != null) mListsSheet.applyTheme(dark);
        if (mModelSelectorSheet != null) mModelSelectorSheet.applyTheme(dark);
        if (mProviderConfigSheet != null) mProviderConfigSheet.applyTheme(dark);
        if (mAboutSheet != null) mAboutSheet.applyTheme(dark);
        if (mDownloadConfirmSheet != null) mDownloadConfirmSheet.applyTheme(dark);
    }

    private void updateTopAgentButton(boolean running, boolean pausedForTakeover) {
        if (mIconTopAgent == null || mBtnTopAgent == null) return;
        if (running) {
            int yellow = AcidTheme.COLOR_AMBER;
            mIconTopAgent.setColor(yellow);
            int bg = Color.argb(32, 245, 158, 11);
            mBtnTopAgent.setBackground(AcidTheme.pillBg(this, bg, yellow, 12));
        } else if (pausedForTakeover) {
            int orange = AcidTheme.COLOR_ORANGE;
            mIconTopAgent.setColor(orange);
            int bg = Color.argb(32, 249, 115, 22);
            mBtnTopAgent.setBackground(AcidTheme.pillBg(this, bg, orange, 12));
        } else {
            int iconCol = AcidTheme.icon(mIsDarkMode);
            mIconTopAgent.setColor(iconCol);
            mBtnTopAgent.setBackground(null);
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

    private void showAboutSheet() {
        if (mAboutSheet == null) return;
        mAboutSheet.setTitle("关于 Acid Browser");
        mAboutSheet.hideAction();
        LinearLayout container = mAboutSheet.getContentContainer();
        container.removeAllViews();

        int accent = AcidTheme.accent(mIsDarkMode);
        int text = AcidTheme.text(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);
        int cardBg = AcidTheme.cardItemBg(mIsDarkMode);
        int border = AcidTheme.border(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // Hero Card: Logo + App Name + Version Badge
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        hero.setBackground(AcidTheme.cardBorderBg(this, cardBg, border, 1, 14));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hLp.bottomMargin = dp(10);
        hero.setLayoutParams(hLp);

        AcidIcons.SvgIconView logo = new AcidIcons.SvgIconView(this, AcidIcons.ICON_LOGO, accent, 42);
        hero.addView(logo);

        TextView tvName = new TextView(this);
        tvName.setText("Acid Browser");
        tvName.setTextColor(text);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setPadding(0, dp(8), 0, dp(2));
        hero.addView(tvName);

        // Version Row with Version badge and [检查更新] button
        LinearLayout versionRow = new LinearLayout(this);
        versionRow.setOrientation(LinearLayout.HORIZONTAL);
        versionRow.setGravity(Gravity.CENTER_VERTICAL);
        versionRow.setPadding(0, dp(4), 0, 0);

        String curVer = "v0.2.4";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            curVer = "v" + pi.versionName;
        } catch (Exception ignored) {}

        TextView tvVersion = new TextView(this);
        tvVersion.setText("版本: " + curVer);
        tvVersion.setTextColor(accent);
        tvVersion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvVersion.setTypeface(Typeface.DEFAULT_BOLD);
        tvVersion.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvVersion.setBackground(AcidTheme.pillBg(this, Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent)), 0, 6));
        versionRow.addView(tvVersion);

        final TextView btnCheckUpdate = new TextView(this);
        btnCheckUpdate.setText("检查更新");
        btnCheckUpdate.setTextColor(AcidTheme.COLOR_BLUE);
        btnCheckUpdate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnCheckUpdate.setTypeface(Typeface.DEFAULT_BOLD);
        btnCheckUpdate.setBackground(AcidTheme.pillBg(this, cardBg, AcidTheme.searchBorder(mIsDarkMode), 6));
        btnCheckUpdate.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams cuLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cuLp.leftMargin = dp(8);
        btnCheckUpdate.setLayoutParams(cuLp);
        btnCheckUpdate.setClickable(true);
        btnCheckUpdate.setFocusable(true);
        btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAppUpdate(btnCheckUpdate);
            }
        });
        versionRow.addView(btnCheckUpdate);
        hero.addView(versionRow);

        container.addView(hero);

        // Author & GitHub Info Card
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        infoCard.setBackground(AcidTheme.cardBorderBg(this, cardBg, border, 1, 12));
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        acLp.bottomMargin = dp(10);
        infoCard.setLayoutParams(acLp);

        TextView lblAuthor = new TextView(this);
        lblAuthor.setText("作者 / 开发者");
        lblAuthor.setTextColor(muted);
        lblAuthor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        infoCard.addView(lblAuthor);

        TextView tvAuthor = new TextView(this);
        tvAuthor.setText("酸小明");
        tvAuthor.setTextColor(text);
        tvAuthor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        tvAuthor.setTypeface(Typeface.DEFAULT_BOLD);
        tvAuthor.setPadding(0, dp(2), 0, dp(8));
        infoCard.addView(tvAuthor);

        TextView lblGh = new TextView(this);
        lblGh.setText("GitHub 主页");
        lblGh.setTextColor(muted);
        lblGh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        infoCard.addView(lblGh);

        TextView tvGh = new TextView(this);
        tvGh.setText("https://github.com/AcidGr");
        tvGh.setTextColor(blue);
        tvGh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvGh.setPadding(0, dp(2), 0, dp(10));
        infoCard.addView(tvGh);

        TextView btnVisit = new TextView(this);
        btnVisit.setText("访问作者 GitHub 主页");
        btnVisit.setTextColor(Color.WHITE);
        btnVisit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btnVisit.setTypeface(Typeface.DEFAULT_BOLD);
        btnVisit.setGravity(Gravity.CENTER);
        btnVisit.setPadding(dp(12), dp(9), dp(12), dp(9));
        btnVisit.setBackground(AcidTheme.pillBg(this, blue, blue, 8));
        btnVisit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAboutSheet.dismiss();
                mController.loadUrl("https://github.com/AcidGr");
            }
        });
        infoCard.addView(btnVisit);

        container.addView(infoCard);

        // Features Description
        LinearLayout descCard = new LinearLayout(this);
        descCard.setOrientation(LinearLayout.VERTICAL);
        descCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        descCard.setBackground(AcidTheme.cardBorderBg(this, cardBg, border, 1, 12));

        TextView tvDesc = new TextView(this);
        tvDesc.setText("Acid Browser 是一款超轻量 (~197 KB)、零敏感权限、原生支持 Agent Loop 与多端视口自由切换的现代 AI 浏览器内核。");
        tvDesc.setTextColor(muted);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvDesc.setLineSpacing(dp(3), 1f);
        descCard.addView(tvDesc);

        container.addView(descCard);

        mAboutSheet.show();
    }

    public static final String UPDATE_PROBE_URL = "https://api.github.com/repos/AcidGr/Acid-Browser/releases/latest";

    private void checkAppUpdate(final TextView triggerBtn) {
        if (triggerBtn != null) {
            triggerBtn.setText("正在检查...");
            triggerBtn.setEnabled(false);
        }
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String jsonStr = null;
                try {
                    java.net.URL u = new java.net.URL(UPDATE_PROBE_URL);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");
                    if (conn.getResponseCode() == 200) {
                        java.io.InputStream in = conn.getInputStream();
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[1024];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            bos.write(buf, 0, len);
                        }
                        in.close();
                        jsonStr = new String(bos.toByteArray(), "UTF-8");
                    }
                } catch (Exception ignored) {}

                final String resultJson = jsonStr;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (triggerBtn != null) {
                            triggerBtn.setText("检查更新");
                            triggerBtn.setEnabled(true);
                        }

                        if (resultJson == null) {
                            Toast.makeText(BrowserActivity.this, "检查更新失败", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        try {
                            JSONObject obj = new JSONObject(resultJson);
                            int remoteCode = obj.optInt("versionCode", 0);
                            String remoteName = obj.optString("versionName", "");
                            String downloadUrl = obj.optString("downloadUrl", "");
                            String apkSize = obj.optString("apkSize", "");
                            String changelog = obj.optString("changelog", "");

                            int currentCode = 0;
                            String currentName = "0.2.4";
                            try {
                                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                                currentCode = pi.versionCode;
                                currentName = pi.versionName;
                            } catch (Exception ignored) {}

                            if (remoteCode > currentCode) {
                                showUpdateDialog(remoteName, remoteCode, apkSize, changelog, downloadUrl);
                            } else {
                                Toast.makeText(BrowserActivity.this, "当前已是最新版本 (v" + currentName + ")", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(BrowserActivity.this, "检查更新失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void showUpdateDialog(
        final String remoteName,
        final int remoteCode,
        final String apkSize,
        final String changelog,
        final String downloadUrl
    ) {
        AlertDialog.Builder builder = AcidTheme.dialogBuilder(this, mIsDarkMode);
        builder.setTitle("发现新版本 v" + remoteName);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(12), pad, dp(4));

        TextView tvSize = new TextView(this);
        tvSize.setText("安装包大小: " + (apkSize.isEmpty() ? "未知" : apkSize));
        tvSize.setTextColor(AcidTheme.text(mIsDarkMode));
        tvSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvSize.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(tvSize);

        TextView tvLogTitle = new TextView(this);
        tvLogTitle.setText("更新日志:");
        tvLogTitle.setTextColor(AcidTheme.accent(mIsDarkMode));
        tvLogTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvLogTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvLogTitle.setPadding(0, dp(10), 0, dp(4));
        content.addView(tvLogTitle);

        TextView tvLog = new TextView(this);
        tvLog.setText(changelog.isEmpty() ? "修复已知问题，提升性能" : changelog);
        tvLog.setTextColor(AcidTheme.muted(mIsDarkMode));
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvLog.setLineSpacing(dp(3), 1f);
        content.addView(tvLog);

        builder.setView(content);

        builder.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mController != null) {
                    mController.startDownload(
                        downloadUrl,
                        "Acid-browser-v" + remoteName + ".apk",
                        null,
                        null,
                        "application/vnd.android.package-archive",
                        0
                    );
                }
            }
        });
        builder.setNegativeButton("稍后再说", null);
        builder.show();
    }

    private void showDownloadConfirmDialog(
        final String url,
        final String fileName,
        final String userAgent,
        final String contentDisposition,
        final String mimeType,
        final long contentLength
    ) {
        if (mDownloadConfirmSheet == null) return;
        mDownloadConfirmSheet.setTitle("下载确认");
        mDownloadConfirmSheet.hideAction();
        mDownloadConfirmSheet.setWrapContentHeight();

        LinearLayout container = mDownloadConfirmSheet.getContentContainer();
        container.removeAllViews();

        int accent = AcidTheme.accent(mIsDarkMode);
        int text = AcidTheme.text(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);
        int cardBg = AcidTheme.cardItemBg(mIsDarkMode);
        int border = AcidTheme.border(mIsDarkMode);
        int searchBg = AcidTheme.searchBg(mIsDarkMode);
        int searchBorder = AcidTheme.searchBorder(mIsDarkMode);
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);

        // Main Card
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        infoCard.setBackground(AcidTheme.cardBorderBg(this, cardBg, border, 1, 14));
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        iLp.bottomMargin = dp(14);
        infoCard.setLayoutParams(iLp);

        // Header inside card: Download icon + Title
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        AcidIcons.SvgIconView dlIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_DOWNLOAD, accent, 24);
        titleRow.addView(dlIcon);

        TextView tvCardTitle = new TextView(this);
        tvCardTitle.setText("新建下载任务");
        tvCardTitle.setTextColor(text);
        tvCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvCardTitle.setPadding(dp(10), 0, 0, 0);
        titleRow.addView(tvCardTitle);
        infoCard.addView(titleRow);

        // Editable File Name Field
        TextView lblName = new TextView(this);
        lblName.setText("文件名 (可自定义修改)");
        lblName.setTextColor(muted);
        lblName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        lblName.setTypeface(Typeface.DEFAULT_BOLD);
        lblName.setPadding(0, dp(12), 0, dp(6));
        infoCard.addView(lblName);

        final EditText etFileName = new EditText(this);
        etFileName.setText(fileName);
        etFileName.setTextColor(text);
        etFileName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        etFileName.setSingleLine(true);
        etFileName.setPadding(dp(12), dp(10), dp(12), dp(10));
        etFileName.setBackground(AcidTheme.pillBg(this, searchBg, searchBorder, 8));
        infoCard.addView(etFileName);

        // Details Block
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, dp(12), 0, 0);

        // 1. File size line
        TextView tvSize = new TextView(this);
        tvSize.setText("预估大小: " + BrowserController.DownloadItem.formatSize(contentLength) + (mimeType != null && !mimeType.isEmpty() ? " (" + mimeType + ")" : ""));
        tvSize.setTextColor(muted);
        tvSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        details.addView(tvSize);

        // 2. Storage Directory with [浏览目录] button
        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.setPadding(0, dp(8), 0, 0);

        TextView tvPath = new TextView(this);
        tvPath.setText("存储位置: /storage/emulated/0/Download/");
        tvPath.setTextColor(muted);
        tvPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pathRow.addView(tvPath, pLp);

        TextView btnBrowseDir = new TextView(this);
        btnBrowseDir.setText("浏览目录");
        btnBrowseDir.setTextColor(AcidTheme.COLOR_BLUE);
        btnBrowseDir.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnBrowseDir.setTypeface(Typeface.DEFAULT_BOLD);
        btnBrowseDir.setBackground(AcidTheme.pillBg(this, itemBg, searchBorder, 6));
        btnBrowseDir.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnBrowseDir.setClickable(true);
        btnBrowseDir.setFocusable(true);
        btnBrowseDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.openDownloadFolder();
                }
            }
        });
        pathRow.addView(btnBrowseDir);
        details.addView(pathRow);

        // 3. Source URL with [复制链接] button
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.setPadding(0, dp(8), 0, 0);

        TextView tvUrl = new TextView(this);
        tvUrl.setText("来源网址: " + url);
        tvUrl.setTextColor(muted);
        tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvUrl.setSingleLine(true);
        tvUrl.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        uLp.rightMargin = dp(8);
        urlRow.addView(tvUrl, uLp);

        TextView btnCopyUrl = new TextView(this);
        btnCopyUrl.setText("复制链接");
        btnCopyUrl.setTextColor(accent);
        btnCopyUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnCopyUrl.setTypeface(Typeface.DEFAULT_BOLD);
        btnCopyUrl.setBackground(AcidTheme.pillBg(this, itemBg, searchBorder, 6));
        btnCopyUrl.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnCopyUrl.setClickable(true);
        btnCopyUrl.setFocusable(true);
        btnCopyUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.copyToClipboard(url);
                }
            }
        });
        urlRow.addView(btnCopyUrl);
        details.addView(urlRow);

        infoCard.addView(details);
        container.addView(infoCard);

        // Action Buttons Row (Cancel / Download)
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, dp(2), 0, dp(2));

        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(muted);
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnCancel.setTypeface(Typeface.DEFAULT_BOLD);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setBackground(AcidTheme.pillBg(this, itemBg, 0, 10));
        btnCancel.setPadding(0, dp(12), 0, dp(12));
        btnCancel.setClickable(true);
        btnCancel.setFocusable(true);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDownloadConfirmSheet.dismiss();
            }
        });

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.rightMargin = dp(8);
        btnRow.addView(btnCancel, cancelLp);

        TextView btnConfirm = new TextView(this);
        btnConfirm.setText("立即下载");
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnConfirm.setTypeface(Typeface.DEFAULT_BOLD);
        btnConfirm.setGravity(Gravity.CENTER);
        btnConfirm.setBackground(AcidTheme.pillBg(this, accent, 0, 10));
        btnConfirm.setPadding(0, dp(12), 0, dp(12));
        btnConfirm.setClickable(true);
        btnConfirm.setFocusable(true);
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDownloadConfirmSheet.dismiss();
                if (mController != null) {
                    String customName = etFileName.getText() != null ? etFileName.getText().toString().trim() : "";
                    if (customName.isEmpty()) customName = fileName;
                    mController.startDownload(url, customName, userAgent, contentDisposition, mimeType, contentLength);
                }
            }
        });

        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        confirmLp.leftMargin = dp(8);
        btnRow.addView(btnConfirm, confirmLp);

        container.addView(btnRow);
        mDownloadConfirmSheet.show();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
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
                    mTvTakeoverStatus.setText("AI 自动驾驶中 (防误触已锁) | 拦跳: " + blocked);
                    mTvTakeoverStatus.setTextColor(accent);
                    mBtnTakeoverAction.update(AcidIcons.ICON_HUMAN, "点此接管", AcidTheme.COLOR_AMBER);
                } else if (controlMode == AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
                    mTakeoverBar.setVisibility(View.VISIBLE);
                    String reason = mController.getTakeoverReason();
                    mTakeoverIcon.setIcon(AcidIcons.ICON_HUMAN);
                    mTakeoverIcon.setColor(AcidTheme.COLOR_AMBER);
                    mTvTakeoverStatus.setText("请人类协助: " + (reason.isEmpty() ? "请完成页面操作" : reason));
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
                Toast.makeText(BrowserActivity.this, "已拦截 App 唤醒 (共 " + totalBlocked + " 次)", Toast.LENGTH_SHORT).show();
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
            }
        });
    }

    @Override
    public void onAgentActionLog(final String logLine) {
        // Internal agent log handled directly by listener
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
                showDownloadConfirmDialog(url, fileName, userAgent, contentDisposition, mimeType, contentLength);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (mDownloadConfirmSheet != null && mDownloadConfirmSheet.isShowing()) {
            mDownloadConfirmSheet.dismiss();
            return;
        }
        if (mAboutSheet != null && mAboutSheet.isShowing()) {
            mAboutSheet.dismiss();
            return;
        }
        if (mSessionsSheet != null && mSessionsSheet.isShowing()) {
            mSessionsSheet.dismiss();
            return;
        }
        if (mProviderConfigSheet != null && mProviderConfigSheet.handleBackPressed()) {
            return;
        }
        if (mModelSelectorSheet != null && mModelSelectorSheet.isShowing()) {
            mModelSelectorSheet.dismiss();
            return;
        }
        if (mListsSheet != null && mListsSheet.isShowing()) {
            mListsSheet.dismiss();
            return;
        }
        if (mMenuSheet != null && mMenuSheet.isShowing()) {
            mMenuSheet.dismiss();
            return;
        }
        if (mTabsOverview != null && mTabsOverview.isShowing()) {
            mTabsOverview.close();
            return;
        }
        if (mAgentFloatingView != null && mAgentFloatingView.handleBackPressed()) {
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
        if (mDownloadReceiver != null) {
            try {
                unregisterReceiver(mDownloadReceiver);
            } catch (Exception ignored) {}
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
