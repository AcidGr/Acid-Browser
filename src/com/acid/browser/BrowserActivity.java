package com.acid.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modern Clean White Browser Edition (Phase 3.1):
 * - Clean white theme by default (Normal browser look & feel)
 * - Classic 5-button bottom toolbar: [Back] [Forward] [Home (Center)] [Tabs] [Menu (Right)]
 * - Toggle Tabs Overview: Tapping [Tabs] button again immediately exits tab overview!
 * - Bottom 5 buttons stay permanent & stationary on screen
 * - "无痕模式" moved into "功能" menu sheet
 * - Beautiful custom BottomSheet for Bookmarks and History
 */
public class BrowserActivity extends Activity implements BrowserController.BrowserStateListener {

    // Day/Light Theme Colors (Clean White Browser Style)
    private static final int LIGHT_BG = Color.parseColor("#FFFFFF");
    private static final int LIGHT_BAR = Color.parseColor("#FFFFFF");
    private static final int LIGHT_BORDER = Color.parseColor("#E5E7EB");
    private static final int LIGHT_SEARCH_BG = Color.parseColor("#F1F5F9");
    private static final int LIGHT_SEARCH_BORDER = Color.parseColor("#E2E8F0");
    private static final int LIGHT_TEXT = Color.parseColor("#0F172A");
    private static final int LIGHT_MUTED = Color.parseColor("#64748B");
    private static final int LIGHT_ICON = Color.parseColor("#334155");
    private static final int LIGHT_ICON_DISABLED = Color.parseColor("#CBD5E1");
    private static final int LIGHT_ACCENT = Color.parseColor("#00BA76");
    private static final int LIGHT_MENU_ITEM_BG = Color.parseColor("#F8FAFC");
    private static final int COLOR_TAB_ACTIVE_BORDER = Color.parseColor("#0284C7");
    private static final int COLOR_OVERVIEW_BG = Color.parseColor("#F1F5F9");

    // Night/Dark Theme Colors
    private static final int DARK_BG = Color.parseColor("#0F172A");
    private static final int DARK_BAR = Color.parseColor("#1E293B");
    private static final int DARK_BORDER = Color.parseColor("#334155");
    private static final int DARK_SEARCH_BG = Color.parseColor("#0F172A");
    private static final int DARK_SEARCH_BORDER = Color.parseColor("#334155");
    private static final int DARK_TEXT = Color.parseColor("#F8FAFC");
    private static final int DARK_MUTED = Color.parseColor("#94A3B8");
    private static final int DARK_ICON = Color.parseColor("#E2E8F0");
    private static final int DARK_ICON_DISABLED = Color.parseColor("#475569");
    private static final int DARK_ACCENT = Color.parseColor("#00FF88");
    private static final int DARK_MENU_ITEM_BG = Color.parseColor("#0F172A");

    private static final int COLOR_AMBER = Color.parseColor("#F59E0B");

    private static final String PREF_NAME = "acid_browser_data";
    private static final String PREF_BOOKMARKS = "bookmarks";
    private static final String PREF_HISTORY = "history";
    private static final String PREF_DARK_MODE = "dark_mode";

    private BrowserController mController;
    private HttpControlServer mHttpServer;
    private AcidAgentLoop mAgentLoop;
    private boolean mIsDarkMode = false;
    private boolean mIncognitoMode = false;

    // Root views
    private FrameLayout mRootContainer;
    private LinearLayout mMainContentLayout;

    // Top bar
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
    private boolean mHasUnreadAgentMessage = false;
    private View mProgressBar;

    // Center Viewport
    private FrameLayout mCenterFrame;
    private FrameLayout mWebViewContainer;
    private AgentOverlayView mOverlayView;

    // Takeover Bar (Co-Pilot notification)
    private LinearLayout mTakeoverBar;
    private AcidIcons.SvgIconView mTakeoverIcon;
    private TextView mTvTakeoverStatus;
    private AcidIcons.BadgeButton mBtnTakeoverAction;

    // Bottom 5-Button Bar: Back, Forward, Home (Center), Tabs, Menu
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

    // Bottom Pop-up Menu Panel ("功能")
    private FrameLayout mMenuOverlay;
    private View mMenuScrim;
    private LinearLayout mMenuCard;
    private TextView mMenuTitle;
    private AcidIcons.SvgIconView mBtnMenuClose;
    private MenuCardItem mMenuItemNight;
    private MenuCardItem mMenuItemIncognito;
    private MenuCardItem mMenuItemBookmark;
    private MenuCardItem mMenuItemHistory;
    private MenuCardItem mMenuItemDownload;
    private MenuCardItem mMenuItemDesktop;
    private MenuCardItem mMenuItemAgent;
    private MenuCardItem mMenuItemModelConfig;

    // Tab Overview Layout (Inside centerFrame, overlaying webview)
    private FrameLayout mTabsOverviewLayout;
    private LinearLayout mTabsGridContainer;
    private TextView mTvTabsCountTitle;

    // Custom List Dialog Layer (Bookmarks & History)
    private FrameLayout mListDialogOverlay;
    private LinearLayout mListDialogCard;
    private TextView mTvListDialogTitle;
    private LinearLayout mListItemsContainer;
    private AcidIcons.BadgeButton mBtnListAction;

    // DSH-Style Floating Agent Window
    private LinearLayout mDrawerPanel;
    private View mAgentHeaderBar;
    private TextView mTvAgentSessionTitle;
    private TextView mTvAgentLoopStatus;
    private ScrollView mChatScrollView;
    private LinearLayout mChatStreamContainer;
    private LinearLayout mAgentInputBar;
    private EditText mEtAgentTaskInput;
    private AcidIcons.BadgeButton mBtnAgentSendStop;
    private final Map<String, ToolCardHolder> mToolCardHolders = new HashMap<String, ToolCardHolder>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        mIsDarkMode = prefs.getBoolean(PREF_DARK_MODE, false);

        View root = buildContentView();
        setContentView(root);

        mController = new BrowserController(this, mWebViewContainer, mOverlayView, this);
        mController.setDarkMode(mIsDarkMode);

        mAgentLoop = new AcidAgentLoop(this, mController, new AcidAgentLoop.AgentLoopListener() {
            @Override
            public void onLoopStateChanged(boolean running, boolean pausedForTakeover, int step) {
                int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
                int muted = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
                int yellow = Color.parseColor("#F59E0B");
                if (mTvAgentLoopStatus != null) {
                    if (running) {
                        mTvAgentLoopStatus.setText("运行中 · Step " + step);
                        mTvAgentLoopStatus.setTextColor(yellow);
                    } else if (pausedForTakeover) {
                        mTvAgentLoopStatus.setText("等待人工接管协助");
                        mTvAgentLoopStatus.setTextColor(COLOR_AMBER);
                    } else {
                        mTvAgentLoopStatus.setText(mAgentLoop.getModelStatusDisplay());
                        mTvAgentLoopStatus.setTextColor(muted);
                    }
                }
                if (!running && !pausedForTakeover) {
                    if (mDrawerPanel == null || mDrawerPanel.getVisibility() != View.VISIBLE) {
                        mHasUnreadAgentMessage = true;
                    }
                }
                updateTopAgentButton(running, pausedForTakeover);
                if (mTvAgentSessionTitle != null && mAgentLoop.getActiveSession() != null) {
                    mTvAgentSessionTitle.setText(mAgentLoop.getActiveSession().title);
                }
                if (mBtnAgentSendStop != null) {
                    if (running) {
                        mBtnAgentSendStop.update(AcidIcons.ICON_CLOSE, "停止", Color.parseColor("#EF4444"));
                    } else if (pausedForTakeover) {
                        mBtnAgentSendStop.update(AcidIcons.ICON_AGENT, "继续", accent);
                    } else {
                        mBtnAgentSendStop.update(AcidIcons.ICON_SEND, "发送", accent);
                    }
                }
            }

            @Override
            public void onSessionLoaded(AcidSessionStore.AgentSession session) {
                renderSessionChatStream(session);
            }

            @Override
            public void onUiItemAdded(AcidSessionStore.UiMessageItem item) {
                if (mTvAgentSessionTitle != null && mAgentLoop.getActiveSession() != null) {
                    mTvAgentSessionTitle.setText(mAgentLoop.getActiveSession().title);
                }
                if (mDrawerPanel == null || mDrawerPanel.getVisibility() != View.VISIBLE) {
                    if (item != null && (AcidSessionStore.TYPE_ASSISTANT.equals(item.type) || AcidSessionStore.TYPE_ERROR.equals(item.type))) {
                        mHasUnreadAgentMessage = true;
                        updateTopAgentButton(mAgentLoop != null && mAgentLoop.isRunning(), mAgentLoop != null && mAgentLoop.isPausedForTakeover());
                    }
                }
                appendUiMessageItemView(item, true);
            }

            @Override
            public void onUiItemUpdated(AcidSessionStore.UiMessageItem item) {
                if (item != null && item.toolCallId != null) {
                    ToolCardHolder holder = mToolCardHolders.get(item.toolCallId);
                    if (holder != null) {
                        holder.bind(item, mIsDarkMode);
                    }
                }
                scrollChatToBottom();
            }
        });

        mHttpServer = new HttpControlServer(mController, HttpControlServer.DEFAULT_PORT);
        mHttpServer.start();

        applyTheme(mIsDarkMode);
        renderSessionChatStream(mAgentLoop.getActiveSession());
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
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable pillBg(int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            gd.setStroke(dp(1), strokeColor);
        }
        return gd;
    }

    private GradientDrawable cardBorderBg(int fillColor, int strokeColor, int strokeWidthDp, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(strokeWidthDp), strokeColor);
        return gd;
    }

    // =========================================================================
    // UI Construction
    // =========================================================================

    private View buildContentView() {
        mRootContainer = new FrameLayout(this);
        mRootContainer.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. Main vertical layout (TopBar + CenterFrame + Drawer + BottomBar)
        mMainContentLayout = new LinearLayout(this);
        mMainContentLayout.setOrientation(LinearLayout.VERTICAL);
        mMainContentLayout.setBackgroundColor(LIGHT_BG);

        // --- Top Bar ---
        mTopBar = new LinearLayout(this);
        mTopBar.setOrientation(LinearLayout.HORIZONTAL);
        mTopBar.setGravity(Gravity.CENTER_VERTICAL);
        mTopBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        // Shield (Security & DeepLink status)
        LinearLayout shieldBox = new LinearLayout(this);
        shieldBox.setGravity(Gravity.CENTER);
        shieldBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        mBtnShield = new AcidIcons.SvgIconView(this, AcidIcons.ICON_SHIELD, LIGHT_ACCENT, 18);
        shieldBox.addView(mBtnShield);
        shieldBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int blocked = mController.getBlockedDeepLinksCount();
                Toast.makeText(BrowserActivity.this, "Acid 安全护盾: 已成功拦截 " + blocked + " 次恶意唤醒跳转", Toast.LENGTH_SHORT).show();
            }
        });
        mTopBar.addView(shieldBox);

        // URL / Search Capsule Container
        mSearchContainer = new LinearLayout(this);
        mSearchContainer.setOrientation(LinearLayout.HORIZONTAL);
        mSearchContainer.setGravity(Gravity.CENTER_VERTICAL);
        mSearchContainer.setPadding(dp(8), dp(2), dp(8), dp(2));
        mSearchContainer.setBackground(pillBg(LIGHT_SEARCH_BG, LIGHT_SEARCH_BORDER, 18));

        mSearchIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_SEARCH, LIGHT_MUTED, 14);
        mSearchContainer.addView(mSearchIcon);

        mEtUrl = new EditText(this);
        mEtUrl.setSingleLine(true);
        mEtUrl.setHint("搜索或输入网址...");
        mEtUrl.setHintTextColor(LIGHT_MUTED);
        mEtUrl.setTextColor(LIGHT_TEXT);
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
                    if (mTabsOverviewLayout != null && mTabsOverviewLayout.getVisibility() == View.VISIBLE) {
                        closeTabsOverview();
                    }
                    mController.loadUrl(mEtUrl.getText().toString());
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mSearchContainer.addView(mEtUrl, etLp);

        // Reload Button inside search container (Crisp reload circle)
        LinearLayout reloadBox = new LinearLayout(this);
        reloadBox.setGravity(Gravity.CENTER);
        reloadBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        mBtnReload = new AcidIcons.SvgIconView(this, AcidIcons.ICON_RELOAD, LIGHT_MUTED, 15);
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

        // Agent Trigger Icon Button on Top Bar right (dynamic status color + unread bubble)
        mBtnTopAgent = new FrameLayout(this);
        int btnW = dp(34);
        int btnH = dp(34);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnW, btnH);
        btnLp.gravity = Gravity.CENTER_VERTICAL;
        btnLp.leftMargin = dp(2);
        btnLp.rightMargin = dp(2);
        mBtnTopAgent.setLayoutParams(btnLp);

        int iconSize = dp(19);
        mIconTopAgent = new AcidIcons.SvgIconView(this, AcidIcons.ICON_AGENT, LIGHT_ICON, 19);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
        mBtnTopAgent.addView(mIconTopAgent, iconLp);

        // Unread notification badge: red circular bubble with white exclamation mark '!'
        mAgentUnreadBadge = new TextView(this);
        mAgentUnreadBadge.setText("!");
        mAgentUnreadBadge.setTextColor(Color.WHITE);
        mAgentUnreadBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        mAgentUnreadBadge.setTypeface(Typeface.DEFAULT_BOLD);
        mAgentUnreadBadge.setGravity(Gravity.CENTER);
        int badgeSize = dp(13);
        mAgentUnreadBadge.setBackground(pillBg(Color.parseColor("#EF4444"), Color.WHITE, 6.5f));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP | Gravity.END);
        mAgentUnreadBadge.setLayoutParams(badgeLp);
        mAgentUnreadBadge.setVisibility(View.GONE);
        mBtnTopAgent.addView(mAgentUnreadBadge);

        mBtnTopAgent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAgentFloatingWindow();
            }
        });
        mTopBar.addView(mBtnTopAgent);

        mMainContentLayout.addView(mTopBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Thin divider below top bar
        mTopBarDivider = new View(this);
        mTopBarDivider.setBackgroundColor(LIGHT_BORDER);
        mMainContentLayout.addView(mTopBarDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Loading Progress Bar
        mProgressBar = new View(this);
        mProgressBar.setBackgroundColor(LIGHT_ACCENT);
        mMainContentLayout.addView(mProgressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        // Takeover / Co-Pilot Banner (appears during Agent run or takeover)
        mTakeoverBar = buildTakeoverBanner();
        mTakeoverBar.setVisibility(View.GONE);
        mMainContentLayout.addView(mTakeoverBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Center Viewport (contains WebView, Overlay and TabsOverviewLayout) ---
        mCenterFrame = new FrameLayout(this);
        mWebViewContainer = new FrameLayout(this);
        mOverlayView = new AgentOverlayView(this);
        mCenterFrame.addView(mWebViewContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCenterFrame.addView(mOverlayView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Tab Overview Layer sits directly inside centerFrame, leaving bottom bar fully visible and permanent!
        mTabsOverviewLayout = buildTabsOverviewLayout();
        mTabsOverviewLayout.setVisibility(View.GONE);
        mCenterFrame.addView(mTabsOverviewLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating Agent Conversation Window (Overlays WebView without compressing viewport!)
        mDrawerPanel = buildInspectorDrawer();
        mDrawerPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams floatPanelLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(360), Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        floatPanelLp.setMargins(dp(10), 0, dp(10), 0);
        mCenterFrame.addView(mDrawerPanel, floatPanelLp);

        mCenterFrame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int oldH = oldBottom - oldTop;
                int newH = bottom - top;
                if (newH > 0 && newH != oldH && mDrawerPanel != null && mDrawerPanel.getVisibility() == View.VISIBLE) {
                    updateFloatingWindowPosition(oldH > 0);
                }
            }
        });

        mMainContentLayout.addView(mCenterFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Bottom 5-Button Toolbar: [Back] [Forward] [Home (Center)] [Tabs] [Menu (Right)] ---
        mBottomDivider = new View(this);
        mBottomDivider.setBackgroundColor(LIGHT_BORDER);
        mMainContentLayout.addView(mBottomDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        mBottomBar = buildBottomToolbar();
        mMainContentLayout.addView(mBottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        mRootContainer.addView(mMainContentLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Fullscreen Menu Sheet Layer ("功能" 弹窗面板)
        mMenuOverlay = buildMenuOverlay();
        mMenuOverlay.setVisibility(View.GONE);
        mRootContainer.addView(mMenuOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Custom List Dialog Layer (书签与历史浏览专属优雅弹窗)
        mListDialogOverlay = buildListDialogOverlay();
        mListDialogOverlay.setVisibility(View.GONE);
        mRootContainer.addView(mListDialogOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return mRootContainer;
    }

    private LinearLayout buildTakeoverBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#FFFBEB"));
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));

        mTakeoverIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HUMAN, COLOR_AMBER, 16);
        bar.addView(mTakeoverIcon);

        mTvTakeoverStatus = new TextView(this);
        mTvTakeoverStatus.setText("AI 请求接管");
        mTvTakeoverStatus.setTextColor(COLOR_AMBER);
        mTvTakeoverStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        mTvTakeoverStatus.setSingleLine(true);
        mTvTakeoverStatus.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.leftMargin = dp(6);
        bar.addView(mTvTakeoverStatus, tvLp);

        mBtnTakeoverAction = new AcidIcons.BadgeButton(this, AcidIcons.ICON_AGENT, "交还AI", COLOR_AMBER, new View.OnClickListener() {
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

    // =========================================================================
    // Bottom 5-Button Toolbar: [Back] [Forward] [Home (Center)] [Tabs] [Menu (Right)]
    // =========================================================================

    private LinearLayout buildBottomToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(LIGHT_BAR);

        // 1. Back
        mBtnBack = createBottomTabItem();
        mIconBack = new AcidIcons.SvgIconView(this, AcidIcons.ICON_BACK, LIGHT_ICON_DISABLED, 20);
        mBtnBack.addView(mIconBack);
        mBtnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverviewLayout != null && mTabsOverviewLayout.getVisibility() == View.VISIBLE) {
                    closeTabsOverview();
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
        mIconForward = new AcidIcons.SvgIconView(this, AcidIcons.ICON_FORWARD, LIGHT_ICON_DISABLED, 20);
        mBtnForward.addView(mIconForward);
        mBtnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverviewLayout != null && mTabsOverviewLayout.getVisibility() == View.VISIBLE) {
                    closeTabsOverview();
                    return;
                }
                BrowserController.TabSession tab = mController.getActiveTab();
                if (tab != null && tab.webView.canGoForward()) {
                    tab.webView.goForward();
                }
            }
        });
        bar.addView(mBtnForward, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 3. Home (Placed squarely in the center as requested!)
        mBtnHome = createBottomTabItem();
        mIconHome = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HOME, LIGHT_ICON, 21);
        mBtnHome.addView(mIconHome);
        mBtnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTabsOverviewLayout != null && mTabsOverviewLayout.getVisibility() == View.VISIBLE) {
                    closeTabsOverview();
                }
                mController.loadUrl(BrowserController.HOME_URL);
            }
        });
        bar.addView(mBtnHome, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 4. Tabs (Click again to EXIT tab overview!)
        mBtnTabs = createBottomTabItem();
        mIconTabs = new AcidIcons.TabBoxView(this, LIGHT_ICON, 18);
        mBtnTabs.addView(mIconTabs);
        mBtnTabs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTabsOverview();
            }
        });
        bar.addView(mBtnTabs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 5. Menu / "功能" (Moved to the 5th spot on the right)
        mBtnMenu = createBottomTabItem();
        mIconMenu = new AcidIcons.SvgIconView(this, AcidIcons.ICON_MENU, LIGHT_ICON, 20);
        mBtnMenu.addView(mIconMenu);
        mBtnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMenuSheet();
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
    // Pop-up Menu Panel ("功能" - Modal Bottom Sheet with Incognito Mode)
    // =========================================================================

    private FrameLayout buildMenuOverlay() {
        FrameLayout overlay = new FrameLayout(this);

        // Semi-transparent scrim
        mMenuScrim = new View(this);
        mMenuScrim.setBackgroundColor(Color.parseColor("#80000000"));
        mMenuScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenuSheet();
            }
        });
        overlay.addView(mMenuScrim, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Bottom rounded card
        mMenuCard = new LinearLayout(this);
        mMenuCard.setOrientation(LinearLayout.VERTICAL);
        mMenuCard.setPadding(dp(14), dp(10), dp(14), dp(18));

        // Top drag handle
        View handle = new View(this);
        handle.setBackground(pillBg(Color.parseColor("#CBD5E1"), 0, 2));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = dp(10);
        mMenuCard.addView(handle, hLp);

        // Title row with close button ("功能")
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        mMenuTitle = new TextView(this);
        mMenuTitle.setText("功能");
        mMenuTitle.setTextColor(LIGHT_TEXT);
        mMenuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mMenuTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(mMenuTitle, titleLp);

        LinearLayout closeBox = new LinearLayout(this);
        closeBox.setGravity(Gravity.CENTER);
        closeBox.setPadding(dp(6), dp(6), dp(6), dp(6));
        mBtnMenuClose = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CLOSE, LIGHT_MUTED, 18);
        closeBox.addView(mBtnMenuClose);
        closeBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenuSheet();
            }
        });
        titleRow.addView(closeBox);
        mMenuCard.addView(titleRow);

        // Menu items grid (Row 1: 4 items - Night, Incognito, Bookmarks, History)
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(12), 0, dp(6));

        mMenuItemNight = new MenuCardItem(
            this,
            mIsDarkMode ? AcidIcons.ICON_SUN : AcidIcons.ICON_MOON,
            "夜间模式",
            mIsDarkMode ? "已开启" : "已关闭",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleDarkMode();
                }
            }
        );
        LinearLayout.LayoutParams itemLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp1.rightMargin = dp(4);
        row1.addView(mMenuItemNight, itemLp1);

        mMenuItemIncognito = new MenuCardItem(
            this, AcidIcons.ICON_INCOGNITO, "无痕模式",
            mIncognitoMode ? "已开启" : "已关闭",
            mIncognitoMode ? Color.parseColor("#8B5CF6") : LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mIncognitoMode = !mIncognitoMode;
                    updateMenuCards();
                    Toast.makeText(
                        BrowserActivity.this,
                        mIncognitoMode ? "已开启无痕模式 (不记录浏览足迹与缓存)" : "已恢复正常浏览模式",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }
        );
        LinearLayout.LayoutParams itemLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp2.leftMargin = dp(2);
        itemLp2.rightMargin = dp(2);
        row1.addView(mMenuItemIncognito, itemLp2);

        mMenuItemBookmark = new MenuCardItem(
            this, AcidIcons.ICON_BOOKMARK, "书签收藏", "快速直达",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    showBookmarksSheet();
                }
            }
        );
        LinearLayout.LayoutParams itemLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp3.leftMargin = dp(2);
        itemLp3.rightMargin = dp(2);
        row1.addView(mMenuItemBookmark, itemLp3);

        mMenuItemHistory = new MenuCardItem(
            this, AcidIcons.ICON_HISTORY, "历史浏览", "足迹记录",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    showHistorySheet();
                }
            }
        );
        LinearLayout.LayoutParams itemLp4 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp4.leftMargin = dp(4);
        row1.addView(mMenuItemHistory, itemLp4);

        mMenuCard.addView(row1);

        // Menu items grid (Row 2: 3 items - Desktop, Downloads, Agent Debug)
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, dp(6));

        mMenuItemDesktop = new MenuCardItem(
            this,
            mController != null && mController.isDesktopMode() ? AcidIcons.ICON_MOBILE : AcidIcons.ICON_DESKTOP,
            "电脑模式",
            "切换视口",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean nextDesktop = !mController.isDesktopMode();
                    mController.setDesktopMode(nextDesktop, true);
                    updateMenuCards();
                    Toast.makeText(
                        BrowserActivity.this,
                        nextDesktop ? "已切换为电脑端 (1280px 宽视口)" : "已切换为移动端",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }
        );
        LinearLayout.LayoutParams itemLp5 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp5.rightMargin = dp(4);
        row2.addView(mMenuItemDesktop, itemLp5);

        mMenuItemDownload = new MenuCardItem(
            this, AcidIcons.ICON_DOWNLOAD, "下载管理", "任务查看",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    showDownloadsSheet();
                }
            }
        );
        LinearLayout.LayoutParams itemLp6 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp6.leftMargin = dp(2);
        itemLp6.rightMargin = dp(2);
        row2.addView(mMenuItemDownload, itemLp6);

        mMenuItemAgent = new MenuCardItem(
            this, AcidIcons.ICON_AGENT, "Acid Agent", "悬浮对话窗",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    toggleAgentFloatingWindow();
                }
            }
        );
        LinearLayout.LayoutParams itemLp7 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp7.leftMargin = dp(2);
        itemLp7.rightMargin = dp(2);
        row2.addView(mMenuItemAgent, itemLp7);

        mMenuItemModelConfig = new MenuCardItem(
            this, AcidIcons.ICON_MODEL, "模型配置", "供应商管理",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    showModelProviderConfigSheet();
                }
            }
        );
        LinearLayout.LayoutParams itemLp8 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp8.leftMargin = dp(4);
        row2.addView(mMenuItemModelConfig, itemLp8);

        mMenuCard.addView(row2);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        );
        overlay.addView(mMenuCard, cardLp);

        return overlay;
    }

    private void toggleMenuSheet() {
        if (mMenuOverlay.getVisibility() == View.VISIBLE) {
            closeMenuSheet();
        } else {
            updateMenuCards();
            mMenuOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void closeMenuSheet() {
        mMenuOverlay.setVisibility(View.GONE);
    }

    private void updateMenuCards() {
        int iconCol = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        int itemBg = mIsDarkMode ? DARK_MENU_ITEM_BG : LIGHT_MENU_ITEM_BG;

        if (mMenuItemNight != null) {
            mMenuItemNight.update(
                mIsDarkMode ? AcidIcons.ICON_SUN : AcidIcons.ICON_MOON,
                "夜间模式",
                mIsDarkMode ? "已开启" : "已关闭",
                iconCol, textCol, mutedCol, itemBg
            );
        }

        if (mMenuItemIncognito != null) {
            mMenuItemIncognito.update(
                AcidIcons.ICON_INCOGNITO,
                "无痕模式",
                mIncognitoMode ? "已开启" : "已关闭",
                mIncognitoMode ? Color.parseColor("#8B5CF6") : iconCol, textCol, mutedCol, itemBg
            );
        }

        if (mMenuItemDesktop != null && mController != null) {
            boolean isPc = mController.isDesktopMode();
            mMenuItemDesktop.update(
                isPc ? AcidIcons.ICON_MOBILE : AcidIcons.ICON_DESKTOP,
                isPc ? "切换手机端" : "切换电脑端",
                isPc ? "当前: 1280px" : "当前: 手机版",
                iconCol, textCol, mutedCol, itemBg
            );
        }

        if (mMenuItemBookmark != null) {
            mMenuItemBookmark.update(AcidIcons.ICON_BOOKMARK, "书签收藏", "快速直达", iconCol, textCol, mutedCol, itemBg);
        }
        if (mMenuItemHistory != null) {
            mMenuItemHistory.update(AcidIcons.ICON_HISTORY, "历史浏览", "足迹记录", iconCol, textCol, mutedCol, itemBg);
        }
        if (mMenuItemDownload != null) {
            mMenuItemDownload.update(AcidIcons.ICON_DOWNLOAD, "下载管理", "任务查看", iconCol, textCol, mutedCol, itemBg);
        }
        if (mMenuItemAgent != null) {
            mMenuItemAgent.update(AcidIcons.ICON_AGENT, "Acid Agent", "悬浮对话窗", iconCol, textCol, mutedCol, itemBg);
        }
        if (mMenuItemModelConfig != null) {
            int providerCount = (mAgentLoop != null && mAgentLoop.getModelStore() != null)
                ? mAgentLoop.getModelStore().getProviders().size() : 1;
            int modelCount = (mAgentLoop != null && mAgentLoop.getModelStore() != null)
                ? mAgentLoop.getModelStore().getTotalLoadedModelsCount() : 1;
            mMenuItemModelConfig.update(
                AcidIcons.ICON_MODEL,
                "模型配置",
                providerCount + " 供应商 · " + modelCount + " 模型",
                iconCol, textCol, mutedCol, itemBg
            );
        }
    }

    private void toggleDarkMode() {
        mIsDarkMode = !mIsDarkMode;
        SharedPreferences.Editor ed = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        ed.putBoolean(PREF_DARK_MODE, mIsDarkMode);
        ed.apply();

        if (mController != null) {
            mController.setDarkMode(mIsDarkMode);
        }
        applyTheme(mIsDarkMode);
        updateMenuCards();
    }

    private void applyTheme(boolean dark) {
        int bg = dark ? DARK_BG : LIGHT_BG;
        int bar = dark ? DARK_BAR : LIGHT_BAR;
        int border = dark ? DARK_BORDER : LIGHT_BORDER;
        int searchBg = dark ? DARK_SEARCH_BG : LIGHT_SEARCH_BG;
        int searchBorder = dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER;
        int text = dark ? DARK_TEXT : LIGHT_TEXT;
        int muted = dark ? DARK_MUTED : LIGHT_MUTED;
        int icon = dark ? DARK_ICON : LIGHT_ICON;
        int accent = dark ? DARK_ACCENT : LIGHT_ACCENT;

        // System Bars
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

        mMainContentLayout.setBackgroundColor(bg);
        mTopBar.setBackgroundColor(bar);
        mTopBarDivider.setBackgroundColor(border);
        mBottomBar.setBackgroundColor(bar);
        mBottomDivider.setBackgroundColor(border);

        mSearchContainer.setBackground(pillBg(searchBg, searchBorder, 18));
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

        // Menu Card styling
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(bar);
        float r = dp(16);
        cardGd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        mMenuCard.setBackground(cardGd);
        mMenuTitle.setTextColor(text);
        mBtnMenuClose.setColor(muted);

        if (mListDialogCard != null) {
            GradientDrawable listGd = new GradientDrawable();
            listGd.setColor(bar);
            listGd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            mListDialogCard.setBackground(listGd);
        }
        if (mTvListDialogTitle != null) {
            mTvListDialogTitle.setTextColor(text);
        }

        // Agent Floating Window styling (Elevated Card with High-Contrast Border and Tinted Body)
        if (mDrawerPanel != null) {
            int floatBg = dark ? Color.parseColor("#0F172A") : Color.parseColor("#F1F5F9");
            int floatBorder = dark ? Color.parseColor("#334155") : Color.parseColor("#64748B");
            mDrawerPanel.setBackground(cardBorderBg(floatBg, floatBorder, 2, 16));
        }
        if (mAgentHeaderBar != null) {
            GradientDrawable headGd = new GradientDrawable();
            headGd.setColor(dark ? DARK_BAR : Color.parseColor("#FFFFFF"));
            float hr = dp(14);
            headGd.setCornerRadii(new float[]{hr, hr, hr, hr, 0, 0, 0, 0});
            mAgentHeaderBar.setBackground(headGd);
        }
        if (mTvAgentSessionTitle != null) {
            mTvAgentSessionTitle.setTextColor(text);
        }
        if (mAgentInputBar != null) {
            GradientDrawable inGd = new GradientDrawable();
            inGd.setColor(dark ? DARK_BAR : Color.parseColor("#FFFFFF"));
            float ir = dp(14);
            inGd.setCornerRadii(new float[]{0, 0, 0, 0, ir, ir, ir, ir});
            mAgentInputBar.setBackground(inGd);
        }
        if (mEtAgentTaskInput != null) {
            mEtAgentTaskInput.setBackground(pillBg(searchBg, searchBorder, 18));
            mEtAgentTaskInput.setTextColor(text);
            mEtAgentTaskInput.setHintTextColor(muted);
        }
        updateTopAgentButton(mAgentLoop != null && mAgentLoop.isRunning(), mAgentLoop != null && mAgentLoop.isPausedForTakeover());
        if (mAgentLoop != null && mAgentLoop.getActiveSession() != null) {
            renderSessionChatStream(mAgentLoop.getActiveSession());
        }
    }

    // =========================================================================
    // Tab Overview (2-Column Grid with Snapshots, 'X' Close & Permanent Bottom Bar)
    // =========================================================================

    private FrameLayout buildTabsOverviewLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_OVERVIEW_BG);

        LinearLayout contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);

        // 1. Top bar for Overview: "标签页 (N)" + [+] New + [Trash] Clear + [X] Close
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.WHITE);
        topBar.setPadding(dp(16), dp(8), dp(16), dp(8));

        mTvTabsCountTitle = new TextView(this);
        mTvTabsCountTitle.setText("标签页 (1)");
        mTvTabsCountTitle.setTextColor(LIGHT_TEXT);
        mTvTabsCountTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mTvTabsCountTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(mTvTabsCountTitle, tLp);

        // Action: New Tab (+)
        LinearLayout newBox = new LinearLayout(this);
        newBox.setGravity(Gravity.CENTER);
        newBox.setPadding(dp(7), dp(6), dp(7), dp(6));
        AcidIcons.SvgIconView btnNew = new AcidIcons.SvgIconView(this, AcidIcons.ICON_PLUS, LIGHT_ACCENT, 18);
        newBox.addView(btnNew);
        newBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.createNewTab(BrowserController.HOME_URL, true);
                closeTabsOverview();
            }
        });
        topBar.addView(newBox);

        // Action: Clear others (Trash)
        LinearLayout clearBox = new LinearLayout(this);
        clearBox.setGravity(Gravity.CENTER);
        clearBox.setPadding(dp(7), dp(6), dp(7), dp(6));
        AcidIcons.SvgIconView btnClear = new AcidIcons.SvgIconView(this, AcidIcons.ICON_TRASH, LIGHT_MUTED, 18);
        clearBox.addView(btnClear);
        clearBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null && mController.getActiveTab() != null) {
                    mController.closeOtherTabs(mController.getActiveTab().id);
                    renderTabsGrid();
                    Toast.makeText(BrowserActivity.this, "已清除其他标签页", Toast.LENGTH_SHORT).show();
                }
            }
        });
        topBar.addView(clearBox);

        // Action: Exit overview (X)
        LinearLayout closeBox = new LinearLayout(this);
        closeBox.setGravity(Gravity.CENTER);
        closeBox.setPadding(dp(7), dp(6), dp(7), dp(6));
        AcidIcons.SvgIconView btnClose = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CLOSE, LIGHT_MUTED, 18);
        closeBox.addView(btnClose);
        closeBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeTabsOverview();
            }
        });
        topBar.addView(closeBox);

        contentCol.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View div = new View(this);
        div.setBackgroundColor(LIGHT_BORDER);
        contentCol.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 2. Scrollable 2-Column Grid
        ScrollView scroll = new ScrollView(this);
        mTabsGridContainer = new LinearLayout(this);
        mTabsGridContainer.setOrientation(LinearLayout.HORIZONTAL);
        mTabsGridContainer.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(mTabsGridContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        contentCol.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(contentCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void toggleTabsOverview() {
        if (mTabsOverviewLayout != null && mTabsOverviewLayout.getVisibility() == View.VISIBLE) {
            closeTabsOverview();
        } else {
            openTabsOverview();
        }
    }

    private void openTabsOverview() {
        if (mController != null) {
            mController.captureActiveTabSnapshot();
        }
        renderTabsGrid();
        mTabsOverviewLayout.setVisibility(View.VISIBLE);
    }

    private void closeTabsOverview() {
        mTabsOverviewLayout.setVisibility(View.GONE);
    }

    private void renderTabsGrid() {
        mTabsGridContainer.removeAllViews();
        if (mController == null) return;

        List<BrowserController.TabSession> tabs = mController.getTabs();
        BrowserController.TabSession activeTab = mController.getActiveTab();
        mTvTabsCountTitle.setText("标签页 (" + tabs.size() + ")");

        // Left and Right columns for 2-column card grid
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams colLpL = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLpL.rightMargin = dp(6);
        mTabsGridContainer.addView(leftCol, colLpL);

        LinearLayout.LayoutParams colLpR = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLpR.leftMargin = dp(6);
        mTabsGridContainer.addView(rightCol, colLpR);

        for (int i = 0; i < tabs.size(); i++) {
            final BrowserController.TabSession t = tabs.get(i);
            boolean isActive = (activeTab != null && activeTab.id == t.id);

            View card = buildSingleTabCard(t, isActive);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = dp(12);

            if (i % 2 == 0) {
                leftCol.addView(card, cLp);
            } else {
                rightCol.addView(card, cLp);
            }
        }
    }

    private View buildSingleTabCard(final BrowserController.TabSession tab, boolean isActive) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        // Highlighting active card with blue border like reference screenshot
        if (isActive) {
            card.setBackground(cardBorderBg(Color.WHITE, COLOR_TAB_ACTIVE_BORDER, 2, 14));
        } else {
            card.setBackground(cardBorderBg(Color.WHITE, LIGHT_BORDER, 1, 14));
        }
        card.setClickable(true);
        card.setFocusable(true);

        // Header: Favicon/Globe + Title + Direct Close 'X'
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(7), dp(6), dp(7));
        GradientDrawable headGd = new GradientDrawable();
        headGd.setColor(Color.parseColor("#F8FAFC"));
        float r = dp(14);
        headGd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        head.setBackground(headGd);

        AcidIcons.SvgIconView globe = new AcidIcons.SvgIconView(this, AcidIcons.ICON_GLOBE, isActive ? COLOR_TAB_ACTIVE_BORDER : LIGHT_MUTED, 14);
        head.addView(globe);

        TextView title = new TextView(this);
        title.setText(tab.title);
        title.setTextColor(LIGHT_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(5);
        tLp.rightMargin = dp(4);
        head.addView(title, tLp);

        // Direct 'X' close button on card
        LinearLayout closeBtn = new LinearLayout(this);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        AcidIcons.SvgIconView closeIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CLOSE, LIGHT_MUTED, 13);
        closeBtn.addView(closeIcon);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.closeTab(tab.id);
                renderTabsGrid();
            }
        });
        head.addView(closeBtn);
        card.addView(head);

        // Body: Live Web Snapshot Thumbnail (or elegant placeholder)
        FrameLayout body = new FrameLayout(this);
        body.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(185)));

        if (tab.snapshot != null && !tab.snapshot.isRecycled()) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(tab.snapshot);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            body.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setBackgroundColor(Color.parseColor("#F8FAFC"));

            AcidIcons.SvgIconView emptyIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_GLOBE, Color.parseColor("#CBD5E1"), 28);
            empty.addView(emptyIcon);

            TextView tip = new TextView(this);
            tip.setText("空白页面");
            tip.setTextColor(Color.parseColor("#94A3B8"));
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tipLp.topMargin = dp(4);
            empty.addView(tip, tipLp);

            body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        card.addView(body);

        // Clicking the card body switches to this tab and dismisses overview
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.switchToTab(tab.id);
                closeTabsOverview();
            }
        });

        return card;
    }

    // =========================================================================
    // Custom List Dialog Layer (Bookmarks & History BottomSheet)
    // =========================================================================

    private FrameLayout buildListDialogOverlay() {
        FrameLayout overlay = new FrameLayout(this);

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.parseColor("#80000000"));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeListDialog();
            }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mListDialogCard = new LinearLayout(this);
        mListDialogCard.setOrientation(LinearLayout.VERTICAL);
        mListDialogCard.setPadding(dp(16), dp(10), dp(16), dp(16));
        mListDialogCard.setBackground(cardBorderBg(Color.WHITE, 0, 0, 16));

        // Handle
        View handle = new View(this);
        handle.setBackground(pillBg(Color.parseColor("#CBD5E1"), 0, 2));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = dp(10);
        mListDialogCard.addView(handle, hLp);

        // Header row
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        mTvListDialogTitle = new TextView(this);
        mTvListDialogTitle.setText("书签收藏");
        mTvListDialogTitle.setTextColor(LIGHT_TEXT);
        mTvListDialogTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mTvListDialogTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(mTvListDialogTitle, titleLp);

        mBtnListAction = new AcidIcons.BadgeButton(this, AcidIcons.ICON_PLUS, "添加当前页", LIGHT_ACCENT, null);
        head.addView(mBtnListAction);

        LinearLayout closeBox = new LinearLayout(this);
        closeBox.setGravity(Gravity.CENTER);
        closeBox.setPadding(dp(6), dp(6), dp(6), dp(6));
        AcidIcons.SvgIconView btnClose = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CLOSE, LIGHT_MUTED, 18);
        closeBox.addView(btnClose);
        closeBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeListDialog();
            }
        });
        head.addView(closeBox);
        mListDialogCard.addView(head);

        // Items Container ScrollView
        ScrollView scroll = new ScrollView(this);
        mListItemsContainer = new LinearLayout(this);
        mListItemsContainer.setOrientation(LinearLayout.VERTICAL);
        mListItemsContainer.setPadding(0, dp(10), 0, dp(4));
        scroll.addView(mListItemsContainer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430));
        mListDialogCard.addView(scroll, sLp);

        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        overlay.addView(mListDialogCard, cLp);

        return overlay;
    }

    private void closeListDialog() {
        mListDialogOverlay.setVisibility(View.GONE);
    }

    private void showBookmarksSheet() {
        mTvListDialogTitle.setText("书签收藏");
        mBtnListAction.setVisibility(View.VISIBLE);
        mBtnListAction.update(AcidIcons.ICON_PLUS, "添加当前页", LIGHT_ACCENT);
        mBtnListAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserController.TabSession cur = mController.getActiveTab();
                if (cur != null && !TextUtils.isEmpty(cur.url)) {
                    List<BookmarkItem> list = loadBookmarks();
                    list.add(0, new BookmarkItem(cur.title, cur.url));
                    saveBookmarks(list);
                    renderBookmarkItems(list);
                    Toast.makeText(BrowserActivity.this, "已添加当前页到书签！", Toast.LENGTH_SHORT).show();
                }
            }
        });

        List<BookmarkItem> list = loadBookmarks();
        renderBookmarkItems(list);
        mListDialogOverlay.setVisibility(View.VISIBLE);
    }

    private List<BookmarkItem> loadBookmarks() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String raw = prefs.getString(PREF_BOOKMARKS, "[]");
        List<BookmarkItem> list = new ArrayList<BookmarkItem>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new BookmarkItem(o.getString("title"), o.getString("url")));
            }
        } catch (Exception ignored) {}

        if (list.isEmpty()) {
            list.add(new BookmarkItem("Acid 靶场首页", BrowserController.HOME_URL));
            list.add(new BookmarkItem("Hacker News", "https://news.ycombinator.com"));
            list.add(new BookmarkItem("GitHub", "https://github.com"));
            list.add(new BookmarkItem("百度搜索", "https://www.baidu.com"));
            saveBookmarks(list);
        }
        return list;
    }

    private void renderBookmarkItems(final List<BookmarkItem> list) {
        mListItemsContainer.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            final BookmarkItem item = list.get(i);
            final int index = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setBackground(pillBg(LIGHT_MENU_ITEM_BG, 0, 8));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(6);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_BOOKMARK, LIGHT_ACCENT, 17);
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = dp(8);
            tLp.rightMargin = dp(8);

            TextView title = new TextView(this);
            title.setText(item.title);
            title.setTextColor(LIGHT_TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView url = new TextView(this);
            url.setText(item.url);
            url.setTextColor(LIGHT_MUTED);
            url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(url);

            row.addView(textCol, tLp);

            // Delete item button
            LinearLayout delBox = new LinearLayout(this);
            delBox.setGravity(Gravity.CENTER);
            delBox.setPadding(dp(4), dp(4), dp(4), dp(4));
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CLOSE, LIGHT_MUTED, 13);
            delBox.addView(delIcon);
            delBox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    list.remove(index);
                    saveBookmarks(list);
                    renderBookmarkItems(list);
                }
            });
            row.addView(delBox);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeListDialog();
                    mController.loadUrl(item.url);
                }
            });

            mListItemsContainer.addView(row, rLp);
        }
    }

    private void showHistorySheet() {
        mTvListDialogTitle.setText("历史浏览");
        mBtnListAction.setVisibility(View.VISIBLE);
        mBtnListAction.update(AcidIcons.ICON_TRASH, "清空历史", Color.parseColor("#EF4444"));
        mBtnListAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_HISTORY, "[]").apply();
                renderHistoryItems(new ArrayList<BookmarkItem>());
                Toast.makeText(BrowserActivity.this, "历史记录已清空", Toast.LENGTH_SHORT).show();
            }
        });

        List<BookmarkItem> list = loadHistory();
        renderHistoryItems(list);
        mListDialogOverlay.setVisibility(View.VISIBLE);
    }

    private List<BookmarkItem> loadHistory() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String raw = prefs.getString(PREF_HISTORY, "[]");
        List<BookmarkItem> list = new ArrayList<BookmarkItem>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new BookmarkItem(o.getString("title"), o.getString("url")));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void renderHistoryItems(final List<BookmarkItem> list) {
        mListItemsContainer.removeAllViews();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无历史浏览记录");
            empty.setTextColor(LIGHT_MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(30), 0, dp(30));
            mListItemsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            final BookmarkItem item = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setBackground(pillBg(LIGHT_MENU_ITEM_BG, 0, 8));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(6);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HISTORY, Color.parseColor("#F59E0B"), 17);
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = dp(8);
            tLp.rightMargin = dp(8);

            TextView title = new TextView(this);
            title.setText(item.title);
            title.setTextColor(LIGHT_TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView url = new TextView(this);
            url.setText(item.url);
            url.setTextColor(LIGHT_MUTED);
            url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(url);

            row.addView(textCol, tLp);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeListDialog();
                    mController.loadUrl(item.url);
                }
            });

            mListItemsContainer.addView(row, rLp);
        }
    }

    private void showDownloadsSheet() {
        mTvListDialogTitle.setText("下载管理");
        mBtnListAction.setVisibility(View.GONE);
        mListItemsContainer.removeAllViews();

        List<BrowserController.DownloadItem> list = mController.getDownloads();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无下载任务记录");
            empty.setTextColor(LIGHT_MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(30), 0, dp(30));
            mListItemsContainer.addView(empty);
        } else {
            for (BrowserController.DownloadItem it : list) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(10), dp(8), dp(10));
                row.setBackground(pillBg(LIGHT_MENU_ITEM_BG, 0, 8));
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rLp.bottomMargin = dp(6);

                AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_DOWNLOAD, LIGHT_ACCENT, 17);
                row.addView(icon);

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tLp.leftMargin = dp(8);

                TextView url = new TextView(this);
                url.setText(it.url);
                url.setTextColor(LIGHT_TEXT);
                url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                url.setTypeface(Typeface.DEFAULT_BOLD);
                url.setSingleLine(true);
                url.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(url);

                TextView info = new TextView(this);
                info.setText((it.mimeType.isEmpty() ? "文件" : it.mimeType) + " | " + it.contentLength + " B");
                info.setTextColor(LIGHT_MUTED);
                info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                textCol.addView(info);

                row.addView(textCol, tLp);
                mListItemsContainer.addView(row, rLp);
            }
        }
        mListDialogOverlay.setVisibility(View.VISIBLE);
    }

    private void saveBookmarks(List<BookmarkItem> list) {
        JSONArray arr = new JSONArray();
        for (BookmarkItem item : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", item.title);
                o.put("url", item.url);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_BOOKMARKS, arr.toString()).apply();
    }

    private void recordHistory(String url, String title) {
        if (mIncognitoMode) return; // Incognito mode: never save browsing history
        if (url == null || url.isEmpty() || url.equals(BrowserController.HOME_URL)) return;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String raw = prefs.getString(PREF_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            JSONArray next = new JSONArray();
            JSONObject cur = new JSONObject();
            cur.put("title", TextUtils.isEmpty(title) ? url : title);
            cur.put("url", url);
            cur.put("time", System.currentTimeMillis());
            next.put(cur);
            for (int i = 0; i < arr.length() && next.length() < 100; i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optString("url").equals(url)) {
                    next.put(o);
                }
            }
            prefs.edit().putString(PREF_HISTORY, next.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static class BookmarkItem {
        public final String title;
        public final String url;
        public BookmarkItem(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    // =========================================================================
    // DSH-Style Floating Agent Window (Zero WebView Compression)
    // =========================================================================

    private float mUserTargetY = -1;

    private void openAgentFloatingWindow() {
        mHasUnreadAgentMessage = false;
        if (mAgentUnreadBadge != null) {
            mAgentUnreadBadge.setVisibility(View.GONE);
        }
        if (mDrawerPanel != null) {
            mDrawerPanel.setVisibility(View.VISIBLE);
            updateFloatingWindowPosition(false);
        }
        scrollChatToBottom();
    }

    private void closeAgentFloatingWindow() {
        hideKeyboard();
        if (mDrawerPanel != null) {
            mDrawerPanel.setVisibility(View.GONE);
        }
    }

    private void toggleAgentFloatingWindow() {
        if (mDrawerPanel == null) return;
        if (mDrawerPanel.getVisibility() == View.VISIBLE) {
            closeAgentFloatingWindow();
        } else {
            openAgentFloatingWindow();
        }
    }

    private void updateFloatingWindowPosition(boolean animated) {
        if (mDrawerPanel == null || mCenterFrame == null) return;
        int centerH = mCenterFrame.getHeight();
        if (centerH <= 0) return;

        int topPadding = dp(8);
        int bottomPadding = dp(12);
        int maxAvailableH = centerH - topPadding - bottomPadding;
        int targetCardH = Math.min(dp(380), Math.max(dp(220), maxAvailableH));

        ViewGroup.LayoutParams lp = mDrawerPanel.getLayoutParams();
        if (lp != null && lp.height != targetCardH) {
            lp.height = targetCardH;
            mDrawerPanel.setLayoutParams(lp);
        }

        float minY = topPadding;
        float defaultBottomY = Math.max(minY, centerH - targetCardH - bottomPadding);

        // If user hasn't dragged, default to bottom resting position
        float baseTargetY = (mUserTargetY < 0) ? defaultBottomY : mUserTargetY;

        // When space is constrained (e.g. keyboard open), max allowed Y so card isn't covered
        float maxAllowedY = Math.max(minY, centerH - targetCardH - bottomPadding);

        final float finalY;
        if (baseTargetY <= maxAllowedY) {
            // Already placed up high: DO NOT push up! Stay at user's desired position.
            finalY = Math.max(minY, baseTargetY);
        } else {
            // Lower than maxAllowedY: push up to the fixed height (maxAllowedY)
            finalY = maxAllowedY;
        }

        if (animated && Build.VERSION.SDK_INT >= 12) {
            mDrawerPanel.animate()
                .translationY(finalY)
                .setDuration(180)
                .start();
        } else {
            mDrawerPanel.setTranslationY(finalY);
        }
    }

    private void updateTopAgentButton(boolean running, boolean pausedForTakeover) {
        if (mIconTopAgent == null || mBtnTopAgent == null) return;
        if (running) {
            int yellow = Color.parseColor("#F59E0B"); // Bright amber yellow for in-progress running
            mIconTopAgent.setColor(yellow);
            int bg = Color.argb(32, 245, 158, 11);
            mBtnTopAgent.setBackground(pillBg(bg, yellow, 12));
        } else if (pausedForTakeover) {
            int orange = Color.parseColor("#F97316"); // Alert orange for takeover
            mIconTopAgent.setColor(orange);
            int bg = Color.argb(32, 249, 115, 22);
            mBtnTopAgent.setBackground(pillBg(bg, orange, 12));
        } else {
            int iconCol = mIsDarkMode ? DARK_ICON : LIGHT_ICON;
            mIconTopAgent.setColor(iconCol);
            mBtnTopAgent.setBackground(null);
        }

        if (mAgentUnreadBadge != null) {
            boolean showBadge = mHasUnreadAgentMessage && (mDrawerPanel == null || mDrawerPanel.getVisibility() != View.VISIBLE);
            mAgentUnreadBadge.setVisibility(showBadge ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout buildInspectorDrawer() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int floatBg = mIsDarkMode ? Color.parseColor("#0F172A") : Color.parseColor("#F1F5F9");
        int floatBorder = mIsDarkMode ? Color.parseColor("#334155") : Color.parseColor("#64748B");
        panel.setBackground(cardBorderBg(floatBg, floatBorder, 2, 16));
        if (Build.VERSION.SDK_INT >= 21) {
            panel.setElevation(dp(16));
        }
        // Prevent touches inside floating window from leaking through to WebView underneath
        panel.setClickable(true);

        // 1. Header Bar (Draggable to reposition the floating window vertically):
        // [Agent Icon + Title + Model Subtitle] | [Sessions] [New] [Settings] [Minimize to Pill]
        LinearLayout headerRow = new LinearLayout(this);
        mAgentHeaderBar = headerRow;
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable headGd = new GradientDrawable();
        headGd.setColor(LIGHT_BAR);
        float hr = dp(15);
        headGd.setCornerRadii(new float[]{hr, hr, hr, hr, 0, 0, 0, 0});
        headerRow.setBackground(headGd);
        headerRow.setPadding(dp(12), dp(8), dp(8), dp(8));

        // Allow vertical dragging of the floating window by dragging the header bar
        headerRow.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private float startTransY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        startTransY = panel.getTranslationY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dy) > dp(6)) {
                            dragging = true;
                        }
                        if (dragging) {
                            int centerH = mCenterFrame != null ? mCenterFrame.getHeight() : 0;
                            int topPadding = dp(8);
                            int bottomPadding = dp(12);
                            int cardH = panel.getHeight() > 0 ? panel.getHeight() : dp(360);
                            float minY = topPadding;
                            float maxY = Math.max(minY, centerH - cardH - bottomPadding);
                            float nextY = Math.min(maxY, Math.max(minY, startTransY + dy));
                            panel.setTranslationY(nextY);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            showAgentSessionsSheet();
                        } else {
                            mUserTargetY = panel.getTranslationY();
                        }
                        return true;
                }
                return false;
            }
        });

        AcidIcons.SvgIconView agentIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_AGENT, LIGHT_ACCENT, 18);
        headerRow.addView(agentIcon);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleColLp.leftMargin = dp(8);
        titleColLp.rightMargin = dp(6);

        mTvAgentSessionTitle = new TextView(this);
        mTvAgentSessionTitle.setText("新对话");
        mTvAgentSessionTitle.setTextColor(LIGHT_TEXT);
        mTvAgentSessionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mTvAgentSessionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTvAgentSessionTitle.setSingleLine(true);
        mTvAgentSessionTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(mTvAgentSessionTitle);

        mTvAgentLoopStatus = new TextView(this);
        mTvAgentLoopStatus.setText("DeepSeek / DeepSeek-V3 (Chat)");
        mTvAgentLoopStatus.setTextColor(LIGHT_MUTED);
        mTvAgentLoopStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        mTvAgentLoopStatus.setSingleLine(true);
        mTvAgentLoopStatus.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(mTvAgentLoopStatus);

        headerRow.addView(titleCol, titleColLp);

        // Button: Session History List
        AcidIcons.BadgeButton btnSessions = new AcidIcons.BadgeButton(this, AcidIcons.ICON_CHAT_LIST, "会话", Color.parseColor("#0284C7"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAgentSessionsSheet();
            }
        });
        headerRow.addView(btnSessions);

        // Button: New Session (+)
        AcidIcons.BadgeButton btnNewSession = new AcidIcons.BadgeButton(this, AcidIcons.ICON_PLUS, "新对话", LIGHT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAgentLoop != null) {
                    mAgentLoop.startNewSession();
                }
            }
        });
        headerRow.addView(btnNewSession);

        // Button: Pure Model Selector (Right next to 'X' Close button)
        LinearLayout btnConfig = AcidIcons.createToolbarIconBtn(this, AcidIcons.ICON_MODEL, LIGHT_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAgentModelSelectorSheet();
            }
        });
        headerRow.addView(btnConfig);

        // Button: Close Floating Window
        LinearLayout btnCloseDrawer = AcidIcons.createToolbarIconBtn(this, AcidIcons.ICON_CLOSE, LIGHT_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAgentFloatingWindow();
            }
        });
        headerRow.addView(btnCloseDrawer);

        panel.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(LIGHT_BORDER);
        panel.addView(headerDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 2. Chat Stream ScrollView
        mChatScrollView = new ScrollView(this);
        mChatStreamContainer = new LinearLayout(this);
        mChatStreamContainer.setOrientation(LinearLayout.VERTICAL);
        mChatStreamContainer.setPadding(dp(12), dp(10), dp(12), dp(10));
        mChatScrollView.addView(mChatStreamContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(mChatScrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View inputDivider = new View(this);
        inputDivider.setBackgroundColor(LIGHT_BORDER);
        panel.addView(inputDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 3. Bottom Composer Input Bar
        mAgentInputBar = new LinearLayout(this);
        mAgentInputBar.setOrientation(LinearLayout.HORIZONTAL);
        mAgentInputBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable inGd = new GradientDrawable();
        inGd.setColor(LIGHT_BAR);
        float ir = dp(15);
        inGd.setCornerRadii(new float[]{0, 0, 0, 0, ir, ir, ir, ir});
        mAgentInputBar.setBackground(inGd);
        mAgentInputBar.setPadding(dp(10), dp(6), dp(10), dp(6));

        mEtAgentTaskInput = new EditText(this);
        mEtAgentTaskInput.setHint("向 Acid Agent 发送网页或沙盒任务...");
        mEtAgentTaskInput.setHintTextColor(LIGHT_MUTED);
        mEtAgentTaskInput.setTextColor(LIGHT_TEXT);
        mEtAgentTaskInput.setSingleLine(true);
        mEtAgentTaskInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mEtAgentTaskInput.setPadding(dp(12), dp(6), dp(12), dp(6));
        mEtAgentTaskInput.setBackground(pillBg(LIGHT_SEARCH_BG, LIGHT_SEARCH_BORDER, 18));
        mEtAgentTaskInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mEtAgentTaskInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    triggerAgentSubmitOrStop();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams promptInputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        promptInputLp.rightMargin = dp(6);
        mAgentInputBar.addView(mEtAgentTaskInput, promptInputLp);

        mBtnAgentSendStop = new AcidIcons.BadgeButton(this, AcidIcons.ICON_SEND, "发送", LIGHT_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerAgentSubmitOrStop();
            }
        });
        mAgentInputBar.addView(mBtnAgentSendStop);

        panel.addView(mAgentInputBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return panel;
    }

    private void renderSessionChatStream(AcidSessionStore.AgentSession session) {
        if (mChatStreamContainer == null) return;
        mChatStreamContainer.removeAllViews();
        mToolCardHolders.clear();

        if (session != null && mTvAgentSessionTitle != null) {
            mTvAgentSessionTitle.setText(session.title);
        }
        if (mTvAgentLoopStatus != null && mAgentLoop != null && !mAgentLoop.isRunning()) {
            mTvAgentLoopStatus.setText(mAgentLoop.getModelStatusDisplay());
        }

        if (session == null || session.uiItems.isEmpty()) {
            addEmptyChatWelcomeCard();
            return;
        }

        for (AcidSessionStore.UiMessageItem item : session.uiItems) {
            appendUiMessageItemView(item, false);
        }
        scrollChatToBottom();
    }

    private void addEmptyChatWelcomeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#FFFFFF");
        int borderCol = mIsDarkMode ? Color.parseColor("#334155") : Color.parseColor("#E2E8F0");
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;

        card.setBackground(cardBorderBg(cardBg, borderCol, 1, 12));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("Acid Agent 自主智能体");
        title.setTextColor(textCol);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("内置 browser (网页感知与操控)、read、write、edit、bash 五大沙盒工具。您可以直接发送指令让它跨网页调研、填写表单、编写油猴脚本或生成本地数据报告。");
        desc.setTextColor(mutedCol);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        desc.setPadding(0, dp(4), 0, dp(8));
        card.addView(desc);

        // Quick-prompt chips
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        addQuickPromptChip(chipRow, "分析当前网页结构与核心内容");
        addQuickPromptChip(chipRow, "测试当前页面的表单填写与点击");
        card.addView(chipRow);

        mChatStreamContainer.addView(card, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addQuickPromptChip(LinearLayout parent, final String promptText) {
        TextView chip = new TextView(this);
        chip.setText(promptText);
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        chip.setTextColor(accent);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        int bg = Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent));
        chip.setBackground(pillBg(bg, accent, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.rightMargin = dp(6);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mEtAgentTaskInput.setText(promptText);
                triggerAgentSubmitOrStop();
            }
        });
        parent.addView(chip, lp);
    }

    private void appendUiMessageItemView(AcidSessionStore.UiMessageItem item, boolean autoScroll) {
        if (mChatStreamContainer == null || item == null) return;
        // Remove welcome card if it is the only child and session just received its first item
        if (mChatStreamContainer.getChildCount() == 1 && mAgentLoop != null &&
            mAgentLoop.getActiveSession() != null && mAgentLoop.getActiveSession().uiItems.size() == 1) {
            mChatStreamContainer.removeAllViews();
        }

        String type = item.type != null ? item.type : AcidSessionStore.TYPE_ASSISTANT;
        if (AcidSessionStore.TYPE_USER.equals(type)) {
            mChatStreamContainer.addView(buildUserBubbleView(item));
        } else if (AcidSessionStore.TYPE_THINKING.equals(type)) {
            mChatStreamContainer.addView(buildThinkingCardView(item));
        } else if (AcidSessionStore.TYPE_TOOL.equals(type)) {
            ToolCardHolder holder = new ToolCardHolder(this);
            holder.bind(item, mIsDarkMode);
            if (item.toolCallId != null && !item.toolCallId.isEmpty()) {
                mToolCardHolders.put(item.toolCallId, holder);
            }
            mChatStreamContainer.addView(holder.rootView);
        } else if (AcidSessionStore.TYPE_ERROR.equals(type)) {
            mChatStreamContainer.addView(buildErrorBubbleView(item));
        } else {
            mChatStreamContainer.addView(buildAssistantMarkdownBubbleView(item));
        }

        if (autoScroll) {
            scrollChatToBottom();
        }
    }

    private View buildUserBubbleView(AcidSessionStore.UiMessageItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = dp(6);
        rowLp.bottomMargin = dp(6);
        row.setLayoutParams(rowLp);

        TextView bubble = new TextView(this);
        bubble.setText(item.text);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setBackground(pillBg(Color.parseColor("#0284C7"), 0, 14));
        bubble.setMaxWidth(dp(280));
        bubble.setTextIsSelectable(true);

        row.addView(bubble, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return row;
    }

    private View buildThinkingCardView(AcidSessionStore.UiMessageItem item) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int bg = mIsDarkMode ? Color.parseColor("#162032") : Color.parseColor("#F1F5F9");
        int border = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#E2E8F0");
        int muted = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;

        card.setBackground(cardBorderBg(bg, border, 1, 8));
        card.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.bottomMargin = dp(5);
        card.setLayoutParams(cardLp);

        // Header row (click to expand/collapse)
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_AGENT, muted, 13);
        head.addView(icon);

        TextView title = new TextView(this);
        String preview = item.text != null ? item.text.replace("\n", " ").trim() : "";
        if (preview.length() > 36) preview = preview.substring(0, 36) + "...";
        title.setText("思考过程 · " + preview);
        title.setTextColor(muted);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(6);
        head.addView(title, tLp);

        final AcidIcons.SvgIconView chevron = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CHEVRON_DOWN, muted, 14);
        head.addView(chevron);
        card.addView(head);

        final TextView body = new TextView(this);
        body.setText(item.text);
        body.setTextColor(muted);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setPadding(0, dp(6), 0, dp(2));
        body.setVisibility(View.GONE);
        body.setTextIsSelectable(true);
        card.addView(body);

        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean expand = body.getVisibility() != View.VISIBLE;
                body.setVisibility(expand ? View.VISIBLE : View.GONE);
                chevron.setIcon(expand ? AcidIcons.ICON_CHEVRON_UP : AcidIcons.ICON_CHEVRON_DOWN);
            }
        });

        return card;
    }

    private View buildAssistantMarkdownBubbleView(AcidSessionStore.UiMessageItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int bg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#FFFFFF");
        int border = mIsDarkMode ? Color.parseColor("#334155") : Color.parseColor("#E2E8F0");

        card.setBackground(cardBorderBg(bg, border, 1, 12));
        card.setPadding(dp(12), dp(10), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);

        AcidMarkdownView.renderInto(card, item.text, mIsDarkMode);
        return card;
    }

    private View buildErrorBubbleView(AcidSessionStore.UiMessageItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int errCol = Color.parseColor("#EF4444");
        int bg = Color.argb(24, 239, 68, 68);
        card.setBackground(cardBorderBg(bg, errCol, 1, 8));
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(item.text);
        tv.setTextColor(errCol);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        card.addView(tv);
        return card;
    }

    /**
     * DSH-Style Expandable Tool Call Card Component:
     * Shows [Tool Icon] + [Tool Name Badge] + [One-line Args Summary] + [Status Pill] + [Chevron].
     * Tapping expands full JSON Arguments and Tool Execution Output.
     */
    private class ToolCardHolder {
        public final LinearLayout rootView;
        private final AcidIcons.SvgIconView mToolIcon;
        private final TextView mTvToolName;
        private final TextView mTvSummary;
        private final TextView mTvStatusBadge;
        private final AcidIcons.SvgIconView mChevron;
        private final LinearLayout mDetailContainer;
        private final TextView mTvArgsCode;
        private final TextView mTvResultCode;

        public ToolCardHolder(Context ctx) {
            rootView = new LinearLayout(ctx);
            rootView.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rootLp.bottomMargin = dp(5);
            rootView.setLayoutParams(rootLp);

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(10), dp(7), dp(10), dp(7));

            mToolIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_GLOBE, LIGHT_ACCENT, 14);
            header.addView(mToolIcon);

            mTvToolName = new TextView(ctx);
            mTvToolName.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            mTvToolName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            nameLp.leftMargin = dp(5);
            header.addView(mTvToolName, nameLp);

            mTvSummary = new TextView(ctx);
            mTvSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            mTvSummary.setSingleLine(true);
            mTvSummary.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sumLp.leftMargin = dp(6);
            sumLp.rightMargin = dp(6);
            header.addView(mTvSummary, sumLp);

            mTvStatusBadge = new TextView(ctx);
            mTvStatusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            mTvStatusBadge.setTypeface(Typeface.DEFAULT_BOLD);
            mTvStatusBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            header.addView(mTvStatusBadge);

            mChevron = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CHEVRON_DOWN, LIGHT_MUTED, 14);
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(dp(14), dp(14));
            chLp.leftMargin = dp(4);
            header.addView(mChevron, chLp);

            rootView.addView(header);

            // Expandable detail box (Input Args + Output Result)
            mDetailContainer = new LinearLayout(ctx);
            mDetailContainer.setOrientation(LinearLayout.VERTICAL);
            mDetailContainer.setPadding(dp(10), dp(4), dp(10), dp(8));
            mDetailContainer.setVisibility(View.GONE);

            TextView lblArgs = new TextView(ctx);
            lblArgs.setText("调用参数 (Arguments):");
            lblArgs.setTextColor( Color.parseColor("#64748B"));
            lblArgs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            lblArgs.setTypeface(Typeface.DEFAULT_BOLD);
            mDetailContainer.addView(lblArgs);

            mTvArgsCode = new TextView(ctx);
            mTvArgsCode.setTypeface(Typeface.MONOSPACE);
            mTvArgsCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            mTvArgsCode.setTextColor(Color.parseColor("#E2E8F0"));
            mTvArgsCode.setBackground(pillBg(Color.parseColor("#0F172A"), Color.parseColor("#334155"), 6));
            mTvArgsCode.setPadding(dp(8), dp(6), dp(8), dp(6));
            mTvArgsCode.setTextIsSelectable(true);
            LinearLayout.LayoutParams argsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            argsLp.topMargin = dp(3);
            argsLp.bottomMargin = dp(6);
            mDetailContainer.addView(mTvArgsCode, argsLp);

            TextView lblRes = new TextView(ctx);
            lblRes.setText("返回结果 (Output):");
            lblRes.setTextColor(Color.parseColor("#64748B"));
            lblRes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            lblRes.setTypeface(Typeface.DEFAULT_BOLD);
            mDetailContainer.addView(lblRes);

            mTvResultCode = new TextView(ctx);
            mTvResultCode.setTypeface(Typeface.MONOSPACE);
            mTvResultCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f);
            mTvResultCode.setTextColor(Color.parseColor("#A7F3D0"));
            mTvResultCode.setBackground(pillBg(Color.parseColor("#0F172A"), Color.parseColor("#334155"), 6));
            mTvResultCode.setPadding(dp(8), dp(6), dp(8), dp(6));
            mTvResultCode.setTextIsSelectable(true);
            LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            resLp.topMargin = dp(3);
            mDetailContainer.addView(mTvResultCode, resLp);

            rootView.addView(mDetailContainer);

            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean expand = mDetailContainer.getVisibility() != View.VISIBLE;
                    mDetailContainer.setVisibility(expand ? View.VISIBLE : View.GONE);
                    mChevron.setIcon(expand ? AcidIcons.ICON_CHEVRON_UP : AcidIcons.ICON_CHEVRON_DOWN);
                }
            });
        }

        public void bind(AcidSessionStore.UiMessageItem item, boolean dark) {
            int cardBg = dark ? Color.parseColor("#1E293B") : Color.parseColor("#FFFFFF");
            int borderCol = dark ? Color.parseColor("#334155") : Color.parseColor("#E2E8F0");
            int textCol = dark ? DARK_TEXT : LIGHT_TEXT;
            int mutedCol = dark ? DARK_MUTED : LIGHT_MUTED;
            int accent = dark ? DARK_ACCENT : LIGHT_ACCENT;

            rootView.setBackground(cardBorderBg(cardBg, borderCol, 1, 8));

            String tName = item.toolName != null ? item.toolName : "tool";
            int iconId = AcidIcons.ICON_GLOBE;
            int toolColor = accent;
            if ("bash".equals(tName)) {
                iconId = AcidIcons.ICON_TERMINAL;
                toolColor = Color.parseColor("#8B5CF6");
            } else if ("read".equals(tName) || "write".equals(tName) || "edit".equals(tName)) {
                iconId = AcidIcons.ICON_FILE;
                toolColor = Color.parseColor("#0284C7");
            }
            mToolIcon.setIcon(iconId);
            mToolIcon.setColor(toolColor);

            mTvToolName.setText(tName);
            mTvToolName.setTextColor(toolColor);

            mTvSummary.setText(item.toolSummary != null ? item.toolSummary : "");
            mTvSummary.setTextColor(textCol);

            if (item.isRunning) {
                mTvStatusBadge.setText("执行中");
                mTvStatusBadge.setTextColor(COLOR_AMBER);
                mTvStatusBadge.setBackground(pillBg(Color.argb(30, 245, 158, 11), COLOR_AMBER, 4));
            } else if (item.isError) {
                int errCol = Color.parseColor("#EF4444");
                mTvStatusBadge.setText("失败");
                mTvStatusBadge.setTextColor(errCol);
                mTvStatusBadge.setBackground(pillBg(Color.argb(30, 239, 68, 68), errCol, 4));
            } else {
                mTvStatusBadge.setText("完成");
                mTvStatusBadge.setTextColor(accent);
                mTvStatusBadge.setBackground(pillBg(Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent)), 0, 4));
            }

            mChevron.setColor(mutedCol);
            mTvArgsCode.setText(item.toolArgsJson != null ? item.toolArgsJson : "{}");
            String resText = item.toolResult != null ? item.toolResult : "";
            if (resText.length() > 3500) {
                resText = resText.substring(0, 3500) + "\n... [内容已截断，共 " + item.toolResult.length() + " 字符]";
            }
            mTvResultCode.setText(resText);
        }
    }

    private void scrollChatToBottom() {
        if (mChatScrollView == null) return;
        mChatScrollView.post(new Runnable() {
            @Override
            public void run() {
                mChatScrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /**
     * Opens the Multi-Session Switcher BottomSheet using the custom ListDialogOverlay.
     */
    private void showAgentSessionsSheet() {
        if (mAgentLoop == null) return;
        mTvListDialogTitle.setText("Agent 会话管理");
        mBtnListAction.setVisibility(View.VISIBLE);
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        mBtnListAction.update(AcidIcons.ICON_PLUS, "新建对话", accent);
        mBtnListAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeListDialog();
                mAgentLoop.startNewSession();
            }
        });

        renderAgentSessionListItems();
        mListDialogOverlay.setVisibility(View.VISIBLE);
    }

    private void renderAgentSessionListItems() {
        mListItemsContainer.removeAllViews();
        final List<AcidSessionStore.SessionMeta> sessions = mAgentLoop.getSessionStore().listSessions();
        final AcidSessionStore.AgentSession active = mAgentLoop.getActiveSession();

        for (int i = 0; i < sessions.size(); i++) {
            final AcidSessionStore.SessionMeta meta = sessions.get(i);
            boolean isCurrent = active != null && active.id.equals(meta.id);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            int rowBg = isCurrent ? Color.parseColor("#E0F2FE") : LIGHT_MENU_ITEM_BG;
            int rowBorder = isCurrent ? COLOR_TAB_ACTIVE_BORDER : 0;
            row.setBackground(pillBg(rowBg, rowBorder, 8));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = dp(6);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
                this, AcidIcons.ICON_CHAT_LIST, isCurrent ? COLOR_TAB_ACTIVE_BORDER : LIGHT_ACCENT, 17
            );
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = dp(8);
            tLp.rightMargin = dp(8);

            TextView title = new TextView(this);
            title.setText(meta.title + (isCurrent ? " (当前)" : ""));
            title.setTextColor(LIGHT_TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView sub = new TextView(this);
            sub.setText(meta.getFormattedTime() + " · " + meta.messageCount + " 条记录");
            sub.setTextColor(LIGHT_MUTED);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            textCol.addView(sub);

            row.addView(textCol, tLp);

            LinearLayout delBox = new LinearLayout(this);
            delBox.setGravity(Gravity.CENTER);
            delBox.setPadding(dp(6), dp(6), dp(6), dp(6));
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_TRASH, LIGHT_MUTED, 14);
            delBox.addView(delIcon);
            delBox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mAgentLoop.deleteSession(meta.id);
                    renderAgentSessionListItems();
                }
            });
            row.addView(delBox);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeListDialog();
                    mAgentLoop.switchSession(meta.id);
                }
            });

            mListItemsContainer.addView(row, rLp);
        }
    }

    private void triggerAgentSubmitOrStop() {
        if (mAgentLoop == null) return;
        if (mAgentLoop.isRunning()) {
            mAgentLoop.stopLoop();
            Toast.makeText(this, "已停止当前 Agent 任务", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mAgentLoop.isPausedForTakeover()) {
            mAgentLoop.resumeFromTakeover();
            return;
        }
        String task = mEtAgentTaskInput.getText().toString().trim();
        if (task.isEmpty()) {
            Toast.makeText(this, "请输入要让 Agent 执行的任务指令", Toast.LENGTH_SHORT).show();
            return;
        }
        mEtAgentTaskInput.setText("");
        hideKeyboard();
        mAgentLoop.submitUserTask(task);
    }

    private void refreshAgentStatusLabel() {
        if (mTvAgentLoopStatus != null && mAgentLoop != null && !mAgentLoop.isRunning() && !mAgentLoop.isPausedForTakeover()) {
            mTvAgentLoopStatus.setText(mAgentLoop.getModelStatusDisplay());
        }
        updateMenuCards();
    }

    // =========================================================================
    // 1. Agent Card Top-Right Button: Pure Model Selector + Reasoning Effort
    //    (Reference: DSH dsh-client-ui-model-selection)
    // =========================================================================

    private String mModelSelectorFilterQuery = "";

    private void showAgentModelSelectorSheet() {
        if (mAgentLoop == null) return;
        mModelSelectorFilterQuery = "";
        mTvListDialogTitle.setText("选择模型与思考深度");
        mBtnListAction.setVisibility(View.VISIBLE);
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        mBtnListAction.update(AcidIcons.ICON_SETTINGS, "模型配置", accent);
        mBtnListAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelProviderConfigSheet();
            }
        });

        renderAgentModelSelectorContent();
        mListDialogOverlay.setVisibility(View.VISIBLE);
    }

    private void renderAgentModelSelectorContent() {
        mListItemsContainer.removeAllViews();
        if (mAgentLoop == null || mAgentLoop.getModelStore() == null) return;

        final AcidModelStore store = mAgentLoop.getModelStore();
        final List<AcidModelStore.ConfiguredProvider> providers = store.getProviders();
        final AcidModelStore.ConfiguredProvider activeProvider = store.getActiveProvider();
        final AcidModelStore.ModelEntry activeModel = store.getActiveModel();
        final String activeEffort = store.getSelectedReasoningEffort();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? DARK_BORDER : LIGHT_BORDER;
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        int blue = Color.parseColor("#0284C7");

        // --- Section A: Active Selection & Reasoning Effort (思考深度) Card ---
        LinearLayout topCard = new LinearLayout(this);
        topCard.setOrientation(LinearLayout.VERTICAL);
        topCard.setBackground(cardBorderBg(cardBg, blue, 1, 12));
        topCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topLp.bottomMargin = dp(10);
        topCard.setLayoutParams(topLp);

        TextView lblCur = new TextView(this);
        lblCur.setText("当前使用模型 (供应商 + 模型名称)");
        lblCur.setTextColor(mutedCol);
        lblCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        topCard.addView(lblCur);

        TextView tvCurVal = new TextView(this);
        if (activeProvider != null && activeModel != null) {
            tvCurVal.setText(activeProvider.displayName + "  /  " + activeModel.name + " (" + activeModel.id + ")");
            tvCurVal.setTextColor(textCol);
        } else {
            tvCurVal.setText("暂无已装载模型，请点击右上角 [模型配置] 添加");
            tvCurVal.setTextColor(COLOR_AMBER);
        }
        tvCurVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tvCurVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvCurVal.setPadding(0, dp(3), 0, dp(8));
        topCard.addView(tvCurVal);

        // Reasoning Effort (思考深度) Selector Row
        if (activeModel != null) {
            LinearLayout effortHead = new LinearLayout(this);
            effortHead.setOrientation(LinearLayout.HORIZONTAL);
            effortHead.setGravity(Gravity.CENTER_VERTICAL);

            TextView lblEffort = new TextView(this);
            lblEffort.setText("思考深度 (Reasoning Effort):");
            lblEffort.setTextColor(textCol);
            lblEffort.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            lblEffort.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams leLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            effortHead.addView(lblEffort, leLp);

            if (activeModel.supportsReasoning) {
                TextView curEffortBadge = new TextView(this);
                curEffortBadge.setText("当前: " + AcidModelStore.getEffortDisplayName(activeEffort));
                curEffortBadge.setTextColor(blue);
                curEffortBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                curEffortBadge.setTypeface(Typeface.DEFAULT_BOLD);
                effortHead.addView(curEffortBadge);
                topCard.addView(effortHead);

                // 2 rows of 3 effort pills: [默认] [关闭] [低] / [中] [高] [极高]
                String[] effortIds = AcidModelStore.EFFORT_IDS;
                for (int r = 0; r < 2; r++) {
                    LinearLayout eRow = new LinearLayout(this);
                    eRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams erLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    erLp.topMargin = dp(6);
                    eRow.setLayoutParams(erLp);

                    for (int c = 0; c < 3; c++) {
                        int idx = r * 3 + c;
                        if (idx >= effortIds.length) break;
                        final String efId = effortIds[idx];
                        boolean isSelectedEffort = efId.equals(activeEffort);

                        TextView pill = new TextView(this);
                        pill.setText(AcidModelStore.getEffortDisplayName(efId));
                        pill.setGravity(Gravity.CENTER);
                        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                        pill.setTypeface(isSelectedEffort ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                        pill.setPadding(dp(6), dp(6), dp(6), dp(6));
                        if (isSelectedEffort) {
                            pill.setTextColor(Color.WHITE);
                            pill.setBackground(pillBg(blue, blue, 8));
                        } else {
                            pill.setTextColor(textCol);
                            pill.setBackground(pillBg(mIsDarkMode ? DARK_BG : Color.WHITE, borderCol, 8));
                        }
                        pill.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                store.setReasoningEffort(efId);
                                refreshAgentStatusLabel();
                                renderAgentModelSelectorContent();
                            }
                        });

                        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        if (c > 0) pLp.leftMargin = dp(6);
                        eRow.addView(pill, pLp);
                    }
                    topCard.addView(eRow);
                }
            } else {
                TextView btnEnableThink = new TextView(this);
                btnEnableThink.setText("开启思考深度调节");
                btnEnableThink.setTextColor(blue);
                btnEnableThink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                btnEnableThink.setPadding(dp(8), dp(3), dp(8), dp(3));
                btnEnableThink.setBackground(pillBg(Color.argb(24, 2, 132, 199), blue, 6));
                btnEnableThink.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activeModel.supportsReasoning = true;
                        store.saveToPrefs();
                        refreshAgentStatusLabel();
                        renderAgentModelSelectorContent();
                    }
                });
                effortHead.addView(btnEnableThink);
                topCard.addView(effortHead);

                TextView tvNoReason = new TextView(this);
                tvNoReason.setText("当前模型未开启思考深度选项（标准对话模式）。如该模型支持推理思考，可点击右侧按钮开启。");
                tvNoReason.setTextColor(mutedCol);
                tvNoReason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                tvNoReason.setPadding(0, dp(4), 0, 0);
                topCard.addView(tvNoReason);
            }
        }
        mListItemsContainer.addView(topCard);

        // --- Section B: Search Box (Shown when > 4 models loaded, per DSH ModelSelect) ---
        int totalModels = store.getTotalLoadedModelsCount();
        final LinearLayout groupsContainer = new LinearLayout(this);
        groupsContainer.setOrientation(LinearLayout.VERTICAL);

        if (totalModels > 4) {
            final EditText etSearch = new EditText(this);
            etSearch.setSingleLine(true);
            etSearch.setHint("搜索已装载的供应商或模型名称...");
            etSearch.setHintTextColor(mutedCol);
            etSearch.setTextColor(textCol);
            etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            etSearch.setPadding(dp(12), dp(7), dp(12), dp(7));
            etSearch.setBackground(pillBg(cardBg, borderCol, 10));
            etSearch.setText(mModelSelectorFilterQuery);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            sLp.bottomMargin = dp(8);
            mListItemsContainer.addView(etSearch, sLp);

            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    mModelSelectorFilterQuery = s != null ? s.toString() : "";
                    populateModelSelectorGroups(groupsContainer, store, providers, activeProvider, activeModel);
                }
                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        mListItemsContainer.addView(groupsContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        populateModelSelectorGroups(groupsContainer, store, providers, activeProvider, activeModel);
    }

    private void populateModelSelectorGroups(
        LinearLayout container,
        final AcidModelStore store,
        List<AcidModelStore.ConfiguredProvider> providers,
        AcidModelStore.ConfiguredProvider activeProvider,
        AcidModelStore.ModelEntry activeModel
    ) {
        container.removeAllViews();
        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? DARK_BORDER : LIGHT_BORDER;
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        int blue = Color.parseColor("#0284C7");

        String query = mModelSelectorFilterQuery != null ? mModelSelectorFilterQuery.trim().toLowerCase(Locale.US) : "";
        int matchedCount = 0;

        for (int i = 0; i < providers.size(); i++) {
            final AcidModelStore.ConfiguredProvider p = providers.get(i);
            if (p.models.isEmpty()) continue;

            List<AcidModelStore.ModelEntry> matchedModels = new ArrayList<AcidModelStore.ModelEntry>();
            for (AcidModelStore.ModelEntry m : p.models) {
                if (query.isEmpty() ||
                    p.displayName.toLowerCase(Locale.US).contains(query) ||
                    m.name.toLowerCase(Locale.US).contains(query) ||
                    m.id.toLowerCase(Locale.US).contains(query)) {
                    matchedModels.add(m);
                }
            }
            if (matchedModels.isEmpty()) continue;

            // Provider Group Header
            LinearLayout groupHead = new LinearLayout(this);
            groupHead.setOrientation(LinearLayout.HORIZONTAL);
            groupHead.setGravity(Gravity.CENTER_VERTICAL);
            groupHead.setPadding(dp(4), dp(6), dp(4), dp(4));

            TextView tvGroupName = new TextView(this);
            tvGroupName.setText("供应商: " + p.displayName);
            tvGroupName.setTextColor(mutedCol);
            tvGroupName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvGroupName.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams gnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            groupHead.addView(tvGroupName, gnLp);

            boolean hasKey = p.hasApiKey();
            TextView tvKeyBadge = new TextView(this);
            tvKeyBadge.setText(hasKey ? "● 密钥已就绪" : "● 待填 API Key");
            tvKeyBadge.setTextColor(hasKey ? accent : COLOR_AMBER);
            tvKeyBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            groupHead.addView(tvKeyBadge);

            container.addView(groupHead);

            // Models inside this provider
            for (int j = 0; j < matchedModels.size(); j++) {
                final AcidModelStore.ModelEntry m = matchedModels.get(j);
                matchedCount++;
                final boolean isSelected = (activeProvider != null && activeModel != null &&
                    p.id.equals(activeProvider.id) && m.id.equals(activeModel.id));

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                int rBg = isSelected ? (mIsDarkMode ? Color.parseColor("#0C4A6E") : Color.parseColor("#E0F2FE")) : cardBg;
                int rStroke = isSelected ? blue : borderCol;
                row.setBackground(pillBg(rBg, rStroke, 10));
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rLp.bottomMargin = dp(6);

                AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
                    this, AcidIcons.ICON_MODEL, isSelected ? blue : accent, 17
                );
                row.addView(icon);

                LinearLayout textColView = new LinearLayout(this);
                textColView.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tcLp.leftMargin = dp(8);
                tcLp.rightMargin = dp(8);

                // Display: 供应商 + 模型名字
                TextView tvTitle = new TextView(this);
                tvTitle.setText(p.displayName + "  ·  " + m.name);
                tvTitle.setTextColor(textCol);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
                tvTitle.setSingleLine(true);
                tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                textColView.addView(tvTitle);

                TextView tvSub = new TextView(this);
                String subInfo = "ID: " + m.id + (m.supportsReasoning ? "  ·  支持思考深度" : "  ·  标准对话");
                tvSub.setText(subInfo);
                tvSub.setTextColor(m.supportsReasoning ? blue : mutedCol);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                tvSub.setSingleLine(true);
                tvSub.setEllipsize(TextUtils.TruncateAt.END);
                textColView.addView(tvSub);

                row.addView(textColView, tcLp);

                if (isSelected) {
                    AcidIcons.SvgIconView check = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CHECK, blue, 18);
                    row.addView(check);
                } else {
                    TextView tvPick = new TextView(this);
                    tvPick.setText("选择");
                    tvPick.setTextColor(mutedCol);
                    tvPick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    row.addView(tvPick);
                }

                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        store.selectModel(p.id, m.id, null);
                        refreshAgentStatusLabel();
                        renderAgentModelSelectorContent();
                        Toast.makeText(
                            BrowserActivity.this,
                            "已切换模型: " + p.displayName + " / " + m.name,
                            Toast.LENGTH_SHORT
                        ).show();
                    }
                });

                container.addView(row, rLp);
            }
        }

        if (matchedCount == 0) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText(query.isEmpty()
                ? "尚未装载任何模型。请点击右上角 [模型配置] 或浏览器右下角 [功能 -> 模型配置] 添加供应商。"
                : "没有匹配的已装载模型。");
            tvEmpty.setTextColor(mutedCol);
            tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(dp(12), dp(24), dp(12), dp(24));
            container.addView(tvEmpty);
        }
    }

    // =========================================================================
    // 2. Bottom-Right Menu -> "模型配置": Provider & Model Management Page
    //    (Reference: DSH dsh-client-ui-settings-models)
    // =========================================================================

    private void showModelProviderConfigSheet() {
        if (mAgentLoop == null) return;
        hideKeyboard();
        mTvListDialogTitle.setText("模型配置");
        mBtnListAction.setVisibility(View.VISIBLE);
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        mBtnListAction.update(AcidIcons.ICON_PLUS, "添加供应商", accent);
        mBtnListAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddProviderView(false);
            }
        });

        renderConfiguredProvidersList();
        mListDialogOverlay.setVisibility(View.VISIBLE);
    }

    private void renderConfiguredProvidersList() {
        mListItemsContainer.removeAllViews();
        if (mAgentLoop == null || mAgentLoop.getModelStore() == null) return;

        final AcidModelStore store = mAgentLoop.getModelStore();
        final List<AcidModelStore.ConfiguredProvider> providers = store.getProviders();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? DARK_BORDER : LIGHT_BORDER;
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        int blue = Color.parseColor("#0284C7");

        // Intro description banner (per DSH Models page)
        TextView intro = new TextView(this);
        intro.setText("填入各供应商的 API 密钥即可在 Agent 模型选择器中使用其模型。点击右上角 [+ 添加供应商] 可选择内置默认供应商或添加自定义 OpenAI 兼容接口。");
        intro.setTextColor(mutedCol);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        intro.setPadding(dp(2), 0, dp(2), dp(10));
        mListItemsContainer.addView(intro);

        for (int i = 0; i < providers.size(); i++) {
            final AcidModelStore.ConfiguredProvider p = providers.get(i);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(cardBorderBg(cardBg, borderCol, 1, 12));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cLp.bottomMargin = dp(10);
            card.setLayoutParams(cLp);

            // Row 1: Provider Name + Tag (默认/自定义) + API Key Status Dot
            LinearLayout headRow = new LinearLayout(this);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(this);
            tvName.setText(p.displayName);
            tvName.setTextColor(textCol);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(tvName);

            TextView tvTag = new TextView(this);
            tvTag.setText(p.isCustom ? "自定义" : "内置目录");
            tvTag.setTextColor(p.isCustom ? Color.parseColor("#8B5CF6") : blue);
            tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            tvTag.setPadding(dp(6), dp(2), dp(6), dp(2));
            tvTag.setBackground(pillBg(
                p.isCustom ? Color.argb(26, 139, 92, 246) : Color.argb(26, 2, 132, 199),
                0, 4
            ));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tagLp.leftMargin = dp(6);
            headRow.addView(tvTag, tagLp);

            View spacer = new View(this);
            headRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            boolean hasKey = p.hasApiKey();
            TextView tvKeyState = new TextView(this);
            tvKeyState.setText(hasKey ? "● API 密钥已配置" : "● API 密钥缺失");
            tvKeyState.setTextColor(hasKey ? accent : Color.parseColor("#EF4444"));
            tvKeyState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvKeyState.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(tvKeyState);

            card.addView(headRow);

            // Row 2: Base URL
            TextView tvUrl = new TextView(this);
            tvUrl.setText("API 地址: " + (p.baseUrl.isEmpty() ? "默认" : p.baseUrl));
            tvUrl.setTextColor(mutedCol);
            tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvUrl.setSingleLine(true);
            tvUrl.setEllipsize(TextUtils.TruncateAt.END);
            tvUrl.setPadding(0, dp(4), 0, dp(4));
            card.addView(tvUrl);

            // Row 3: Loaded Models Summary
            StringBuilder mSb = new StringBuilder();
            for (int j = 0; j < p.models.size(); j++) {
                AcidModelStore.ModelEntry me = p.models.get(j);
                if (j > 0) mSb.append("  |  ");
                mSb.append(me.name);
                if (me.supportsReasoning) mSb.append(" [思考]");
            }
            TextView tvModels = new TextView(this);
            tvModels.setText("已装载模型 (" + p.models.size() + "): " + (mSb.length() > 0 ? mSb.toString() : "暂无模型"));
            tvModels.setTextColor(textCol);
            tvModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvModels.setPadding(0, dp(2), 0, dp(8));
            card.addView(tvModels);

            // Row 4: Action Buttons ([编辑 / 配置密钥]  [删除])
            LinearLayout actionRow = new LinearLayout(this);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

            AcidIcons.BadgeButton btnEdit = new AcidIcons.BadgeButton(
                this, AcidIcons.ICON_EDIT, "编辑供应商与模型", blue,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showEditProviderView(p, null);
                    }
                }
            );
            actionRow.addView(btnEdit);

            if (providers.size() > 1 || p.isCustom) {
                AcidIcons.BadgeButton btnDel = new AcidIcons.BadgeButton(
                    this, AcidIcons.ICON_TRASH, "删除", Color.parseColor("#EF4444"),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            new AlertDialog.Builder(BrowserActivity.this, mIsDarkMode ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                                .setTitle("删除 " + p.displayName + "？")
                                .setMessage("删除 " + p.displayName + " 会移除其配置和存储的 API 密钥。")
                                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        store.deleteProvider(p.id);
                                        refreshAgentStatusLabel();
                                        renderConfiguredProvidersList();
                                        Toast.makeText(BrowserActivity.this, "已删除供应商: " + p.displayName, Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                        }
                    }
                );
                actionRow.addView(btnDel);
            }

            card.addView(actionRow);
            mListItemsContainer.addView(card);
        }

        // Bottom prominent button to add provider
        TextView btnAddBottom = new TextView(this);
        btnAddBottom.setText("+ 添加模型供应商 (默认供应商目录 / 自定义接口)");
        btnAddBottom.setTextColor(Color.WHITE);
        btnAddBottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnAddBottom.setTypeface(Typeface.DEFAULT_BOLD);
        btnAddBottom.setGravity(Gravity.CENTER);
        btnAddBottom.setPadding(dp(12), dp(11), dp(12), dp(11));
        btnAddBottom.setBackground(pillBg(accent, 0, 10));
        btnAddBottom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddProviderView(false);
            }
        });
        mListItemsContainer.addView(btnAddBottom, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    /**
     * DSH-Style "Add Model Provider" View with Segmented Mode Switch:
     * Mode 0: 内置默认供应商 (Catalog Providers: DeepSeek, OpenAI, Kimi, Zhipu, Qwen, Claude, Gemini, etc.)
     * Mode 1: 自定义供应商 API (Custom OpenAI-Compatible Provider)
     */
    private void showAddProviderView(final boolean customMode) {
        if (mAgentLoop == null) return;
        hideKeyboard();
        mTvListDialogTitle.setText("添加模型供应商");
        mBtnListAction.setVisibility(View.VISIBLE);
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        mBtnListAction.update(AcidIcons.ICON_BACK, "返回列表", mutedCol);
        mBtnListAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelProviderConfigSheet();
            }
        });

        mListItemsContainer.removeAllViews();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? DARK_BORDER : LIGHT_BORDER;
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int blue = Color.parseColor("#0284C7");

        // Segmented Mode Switch: [第三方默认供应商] vs [自定义模型 API]
        LinearLayout segBar = new LinearLayout(this);
        segBar.setOrientation(LinearLayout.HORIZONTAL);
        segBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        segBar.setBackground(pillBg(cardBg, borderCol, 10));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        segLp.bottomMargin = dp(8);
        segBar.setLayoutParams(segLp);

        TextView tabCatalog = new TextView(this);
        tabCatalog.setText("默认供应商目录 (10)");
        tabCatalog.setGravity(Gravity.CENTER);
        tabCatalog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tabCatalog.setTypeface(!customMode ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabCatalog.setPadding(dp(8), dp(8), dp(8), dp(8));
        tabCatalog.setTextColor(!customMode ? Color.WHITE : textCol);
        tabCatalog.setBackground(!customMode ? pillBg(blue, 0, 8) : null);
        tabCatalog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (customMode) showAddProviderView(false);
            }
        });
        segBar.addView(tabCatalog, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tabCustom = new TextView(this);
        tabCustom.setText("自定义供应商 API");
        tabCustom.setGravity(Gravity.CENTER);
        tabCustom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tabCustom.setTypeface(customMode ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabCustom.setPadding(dp(8), dp(8), dp(8), dp(8));
        tabCustom.setTextColor(customMode ? Color.WHITE : textCol);
        tabCustom.setBackground(customMode ? pillBg(blue, 0, 8) : null);
        tabCustom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!customMode) showAddProviderView(true);
            }
        });
        segBar.addView(tabCustom, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mListItemsContainer.addView(segBar);

        // One-line purpose description beneath segmented switch (per DSH README)
        TextView hint = new TextView(this);
        hint.setText(!customMode
            ? "从内置目录中选择 DeepSeek、OpenAI、Kimi、智谱 GLM、通义千问等主流提供商，填入 API 密钥即可装载使用。"
            : "连接中转站、自部署服务 (Ollama / vLLM) 或其他兼容 OpenAI 协议的接口，需填写供应商名称、API 地址和模型。");
        hint.setTextColor(mutedCol);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hint.setPadding(dp(2), 0, dp(2), dp(10));
        mListItemsContainer.addView(hint);

        if (!customMode) {
            renderBuiltinCatalogPresetsList();
        } else {
            AcidModelStore.ConfiguredProvider draftCustom = new AcidModelStore.ConfiguredProvider(
                "", "", true, "", ""
            );
            renderProviderEditorForm(draftCustom, null, true);
        }
    }

    private void renderBuiltinCatalogPresetsList() {
        final AcidModelStore store = mAgentLoop.getModelStore();
        List<AcidModelStore.CatalogProviderPreset> presets = AcidModelStore.getBuiltinCatalogPresets();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? DARK_BORDER : LIGHT_BORDER;
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        int blue = Color.parseColor("#0284C7");

        for (int i = 0; i < presets.size(); i++) {
            final AcidModelStore.CatalogProviderPreset preset = presets.get(i);
            final AcidModelStore.ConfiguredProvider existing = store.getProvider(preset.id);
            boolean isLoaded = (existing != null);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(cardBorderBg(cardBg, isLoaded ? accent : borderCol, 1, 10));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cLp.bottomMargin = dp(8);
            card.setLayoutParams(cLp);

            LinearLayout rowTop = new LinearLayout(this);
            rowTop.setOrientation(LinearLayout.HORIZONTAL);
            rowTop.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvTitle = new TextView(this);
            tvTitle.setText(preset.displayName);
            tvTitle.setTextColor(textCol);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rowTop.addView(tvTitle, ttLp);

            TextView tvAction = new TextView(this);
            tvAction.setText(isLoaded ? "已装载 · 点击编辑" : "+ 选择并配置");
            tvAction.setTextColor(isLoaded ? accent : blue);
            tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvAction.setTypeface(Typeface.DEFAULT_BOLD);
            tvAction.setPadding(dp(8), dp(3), dp(8), dp(3));
            tvAction.setBackground(pillBg(
                isLoaded ? Color.argb(26, 0, 186, 118) : Color.argb(26, 2, 132, 199),
                0, 6
            ));
            rowTop.addView(tvAction);
            card.addView(rowTop);

            TextView tvDesc = new TextView(this);
            tvDesc.setText(preset.description);
            tvDesc.setTextColor(mutedCol);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvDesc.setPadding(0, dp(3), 0, dp(2));
            card.addView(tvDesc);

            StringBuilder mListStr = new StringBuilder("预设模型: ");
            for (int j = 0; j < preset.defaultModels.size(); j++) {
                if (j > 0) mListStr.append(", ");
                mListStr.append(preset.defaultModels.get(j).name);
            }
            TextView tvModels = new TextView(this);
            tvModels.setText(mListStr.toString());
            tvModels.setTextColor(blue);
            tvModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f);
            card.addView(tvModels);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditProviderView(existing, preset);
                }
            });

            mListItemsContainer.addView(card);
        }
    }

    /**
     * Detailed Provider Editor View (Used for configuring a Catalog Provider, editing an existing Provider,
     * or creating a Custom Provider).
     */
    private void showEditProviderView(
        final AcidModelStore.ConfiguredProvider existing,
        final AcidModelStore.CatalogProviderPreset preset
    ) {
        if (mAgentLoop == null) return;
        hideKeyboard();

        String title = existing != null
            ? ("编辑 " + existing.displayName)
            : (preset != null ? ("配置 " + preset.displayName) : "创建自定义供应商");
        mTvListDialogTitle.setText(title);
        mBtnListAction.setVisibility(View.VISIBLE);
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        mBtnListAction.update(AcidIcons.ICON_BACK, "返回", mutedCol);
        mBtnListAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelProviderConfigSheet();
            }
        });

        mListItemsContainer.removeAllViews();

        AcidModelStore.ConfiguredProvider workingDraft;
        if (existing != null) {
            workingDraft = new AcidModelStore.ConfiguredProvider(
                existing.id, existing.displayName, existing.isCustom, existing.baseUrl, existing.apiKey
            );
            for (AcidModelStore.ModelEntry m : existing.models) {
                workingDraft.models.add(m.copy());
            }
        } else if (preset != null) {
            workingDraft = new AcidModelStore.ConfiguredProvider(
                preset.id, preset.displayName, false, preset.defaultBaseUrl, ""
            );
            for (AcidModelStore.ModelEntry m : preset.defaultModels) {
                workingDraft.models.add(m.copy());
            }
        } else {
            workingDraft = new AcidModelStore.ConfiguredProvider("", "", true, "", "");
        }

        renderProviderEditorForm(workingDraft, preset, false);
    }

    private void renderProviderEditorForm(
        final AcidModelStore.ConfiguredProvider draft,
        final AcidModelStore.CatalogProviderPreset preset,
        final boolean embeddedInAddTab
    ) {
        final AcidModelStore store = mAgentLoop.getModelStore();
        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int inputBg = mIsDarkMode ? DARK_BG : Color.WHITE;
        int borderCol = mIsDarkMode ? DARK_BORDER : LIGHT_BORDER;
        final int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        final int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        final int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
        final int blue = Color.parseColor("#0284C7");

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(cardBorderBg(cardBg, borderCol, 1, 12));
        form.setPadding(dp(12), dp(12), dp(12), dp(12));

        // 1. Provider Display Name (Editable for Custom, or shown as header for Built-in)
        TextView lblName = new TextView(this);
        lblName.setText(draft.isCustom ? "供应商显示名称 (必填):" : ("供应商: " + draft.displayName + " (ID: " + draft.id + ")"));
        lblName.setTextColor(textCol);
        lblName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblName.setTypeface(Typeface.DEFAULT_BOLD);
        form.addView(lblName);

        final EditText etDisplayName = new EditText(this);
        etDisplayName.setSingleLine(true);
        etDisplayName.setText(draft.displayName);
        etDisplayName.setHint("例如: 我的中转网关 / Ollama 本地服务");
        etDisplayName.setTextColor(textCol);
        etDisplayName.setHintTextColor(mutedCol);
        etDisplayName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        etDisplayName.setPadding(dp(10), dp(7), dp(10), dp(7));
        etDisplayName.setBackground(pillBg(inputBg, borderCol, 8));
        if (draft.isCustom) {
            LinearLayout.LayoutParams dnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            dnLp.topMargin = dp(4);
            dnLp.bottomMargin = dp(8);
            form.addView(etDisplayName, dnLp);
        } else {
            lblName.setPadding(0, 0, 0, dp(8));
        }

        // 2. API Key with Show/Hide Toggle (per DSH settings-models API Key field)
        LinearLayout keyLabelRow = new LinearLayout(this);
        keyLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        keyLabelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblKey = new TextView(this);
        lblKey.setText("API 密钥 (API Key):");
        lblKey.setTextColor(textCol);
        lblKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblKey.setTypeface(Typeface.DEFAULT_BOLD);
        keyLabelRow.addView(lblKey, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView btnToggleKeyVis = new TextView(this);
        btnToggleKeyVis.setText("显示密钥");
        btnToggleKeyVis.setTextColor(blue);
        btnToggleKeyVis.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnToggleKeyVis.setPadding(dp(6), dp(2), dp(6), dp(2));
        keyLabelRow.addView(btnToggleKeyVis);
        form.addView(keyLabelRow);

        final EditText etApiKey = new EditText(this);
        etApiKey.setSingleLine(true);
        etApiKey.setText(draft.apiKey);
        etApiKey.setHint(preset != null ? ("输入 API Key (" + preset.envKeyHint + ")") : "输入 sk-... (本地免鉴权接口可留空)");
        etApiKey.setTextColor(textCol);
        etApiKey.setHintTextColor(mutedCol);
        etApiKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        etApiKey.setPadding(dp(10), dp(7), dp(10), dp(7));
        etApiKey.setBackground(pillBg(inputBg, borderCol, 8));
        etApiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        keyLp.topMargin = dp(4);
        keyLp.bottomMargin = dp(8);
        form.addView(etApiKey, keyLp);

        btnToggleKeyVis.setOnClickListener(new View.OnClickListener() {
            private boolean revealed = false;
            @Override
            public void onClick(View v) {
                revealed = !revealed;
                etApiKey.setTransformationMethod(revealed
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
                btnToggleKeyVis.setText(revealed ? "隐藏密钥" : "显示密钥");
                etApiKey.setSelection(etApiKey.getText().length());
            }
        });

        // 3. Base URL
        TextView lblUrl = new TextView(this);
        lblUrl.setText("API 地址 (Base URL - OpenAI 兼容接口):");
        lblUrl.setTextColor(textCol);
        lblUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblUrl.setTypeface(Typeface.DEFAULT_BOLD);
        form.addView(lblUrl);

        final EditText etBaseUrl = new EditText(this);
        etBaseUrl.setSingleLine(true);
        etBaseUrl.setText(draft.baseUrl);
        etBaseUrl.setHint(preset != null ? preset.defaultBaseUrl : "https://gateway.example/v1");
        etBaseUrl.setTextColor(textCol);
        etBaseUrl.setHintTextColor(mutedCol);
        etBaseUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        etBaseUrl.setPadding(dp(10), dp(7), dp(10), dp(7));
        etBaseUrl.setBackground(pillBg(inputBg, borderCol, 8));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        urlLp.topMargin = dp(4);
        urlLp.bottomMargin = dp(10);
        form.addView(etBaseUrl, urlLp);

        // 4. Models Catalog Section inside Provider Editor
        LinearLayout modelHeaderRow = new LinearLayout(this);
        modelHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        modelHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblModels = new TextView(this);
        lblModels.setText("装载的模型列表");
        lblModels.setTextColor(textCol);
        lblModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lblModels.setTypeface(Typeface.DEFAULT_BOLD);
        modelHeaderRow.addView(lblModels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (preset != null) {
            TextView btnResetModels = new TextView(this);
            btnResetModels.setText("恢复默认模型");
            btnResetModels.setTextColor(blue);
            btnResetModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            btnResetModels.setPadding(dp(6), dp(2), dp(6), dp(2));
            modelHeaderRow.addView(btnResetModels);
            final LinearLayout modelsListBoxRef = new LinearLayout(this);
            btnResetModels.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.models.clear();
                    for (AcidModelStore.ModelEntry m : preset.defaultModels) {
                        draft.models.add(m.copy());
                    }
                    renderDraftModelsList(modelsListBoxRef, draft);
                    Toast.makeText(BrowserActivity.this, "已恢复预设模型目录", Toast.LENGTH_SHORT).show();
                }
            });
            form.addView(modelHeaderRow);

            // Action bar for models: [+ 手动添加模型]  [从接口获取可用模型 (/models)]
            form.addView(buildModelDiscoveryActionBar(draft, etBaseUrl, etApiKey, modelsListBoxRef));
            modelsListBoxRef.setOrientation(LinearLayout.VERTICAL);
            form.addView(modelsListBoxRef);
            renderDraftModelsList(modelsListBoxRef, draft);
        } else {
            form.addView(modelHeaderRow);
            final LinearLayout modelsListBox = new LinearLayout(this);
            modelsListBox.setOrientation(LinearLayout.VERTICAL);
            form.addView(buildModelDiscoveryActionBar(draft, etBaseUrl, etApiKey, modelsListBox));
            form.addView(modelsListBox);
            renderDraftModelsList(modelsListBox, draft);
        }

        // 5. Save / Apply Button
        TextView btnSave = new TextView(this);
        btnSave.setText(draft.isCustom && draft.id.isEmpty() ? "创建并装载供应商" : "保存并装载供应商");
        btnSave.setTextColor(Color.WHITE);
        btnSave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btnSave.setTypeface(Typeface.DEFAULT_BOLD);
        btnSave.setGravity(Gravity.CENTER);
        btnSave.setPadding(dp(12), dp(11), dp(12), dp(11));
        btnSave.setBackground(pillBg(accent, 0, 10));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        saveLp.topMargin = dp(12);
        btnSave.setLayoutParams(saveLp);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String dispName = draft.isCustom ? etDisplayName.getText().toString().trim() : draft.displayName;
                if (dispName.isEmpty()) {
                    Toast.makeText(BrowserActivity.this, "请填写供应商显示名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                String url = etBaseUrl.getText().toString().trim();
                if (url.isEmpty() && preset != null) {
                    url = preset.defaultBaseUrl;
                }
                if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                    Toast.makeText(BrowserActivity.this, "请输入有效的 HTTP/HTTPS API 地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (draft.models.isEmpty()) {
                    Toast.makeText(BrowserActivity.this, "至少需要添加 1 个模型", Toast.LENGTH_SHORT).show();
                    return;
                }

                draft.displayName = dispName;
                if (draft.id.isEmpty()) {
                    draft.id = "custom-" + System.currentTimeMillis();
                }
                draft.baseUrl = url;
                draft.apiKey = etApiKey.getText().toString().trim();

                store.upsertProvider(draft);
                // Automatically select the first model of this newly saved provider
                store.selectModel(draft.id, draft.models.get(0).id, null);
                refreshAgentStatusLabel();
                Toast.makeText(BrowserActivity.this, "已保存并装载: " + draft.displayName, Toast.LENGTH_SHORT).show();
                showModelProviderConfigSheet();
            }
        });
        form.addView(btnSave);

        mListItemsContainer.addView(form);
    }

    private LinearLayout buildModelDiscoveryActionBar(
        final AcidModelStore.ConfiguredProvider draft,
        final EditText etBaseUrl,
        final EditText etApiKey,
        final LinearLayout modelsListBox
    ) {
        int blue = Color.parseColor("#0284C7");
        int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, dp(6));

        // Button 1: Manual Add Model (+ 添加模型)
        TextView btnManualAdd = new TextView(this);
        btnManualAdd.setText("+ 手动添加模型");
        btnManualAdd.setTextColor(accent);
        btnManualAdd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnManualAdd.setTypeface(Typeface.DEFAULT_BOLD);
        btnManualAdd.setGravity(Gravity.CENTER);
        btnManualAdd.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnManualAdd.setBackground(pillBg(Color.argb(24, 0, 186, 118), accent, 8));
        btnManualAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualAddModelDialog(draft, modelsListBox);
            }
        });
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1Lp.rightMargin = dp(4);
        bar.addView(btnManualAdd, b1Lp);

        // Button 2: Fetch Available Models from Endpoint (获取可用模型 /models)
        final TextView btnFetch = new TextView(this);
        btnFetch.setText("从接口获取模型 (/models)");
        btnFetch.setTextColor(blue);
        btnFetch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnFetch.setTypeface(Typeface.DEFAULT_BOLD);
        btnFetch.setGravity(Gravity.CENTER);
        btnFetch.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnFetch.setBackground(pillBg(Color.argb(24, 2, 132, 199), blue, 8));
        btnFetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = etBaseUrl.getText().toString().trim();
                String key = etApiKey.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(BrowserActivity.this, "请先填写 API 地址，再获取可用模型", Toast.LENGTH_SHORT).show();
                    return;
                }
                btnFetch.setText("正在询问提供商...");
                btnFetch.setEnabled(false);
                AcidModelStore.discoverModelsAsync(url, key, new AcidModelStore.ModelDiscoveryCallback() {
                    @Override
                    public void onModelsDiscovered(final List<AcidModelStore.ModelEntry> discovered) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnFetch.setText("从接口获取模型 (/models)");
                                btnFetch.setEnabled(true);
                                showDiscoveredModelsPickerDialog(draft, discovered, modelsListBox);
                            }
                        });
                    }

                    @Override
                    public void onDiscoveryFailed(final String errorMsg) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnFetch.setText("从接口获取模型 (/models)");
                                btnFetch.setEnabled(true);
                                Toast.makeText(BrowserActivity.this, "获取失败: " + errorMsg, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b2Lp.leftMargin = dp(4);
        bar.addView(btnFetch, b2Lp);

        return bar;
    }

    private void renderDraftModelsList(final LinearLayout container, final AcidModelStore.ConfiguredProvider draft) {
        container.removeAllViews();
        int rowBg = mIsDarkMode ? DARK_BG : Color.WHITE;
        int borderCol = mIsDarkMode ? DARK_BORDER : LIGHT_BORDER;
        int textCol = mIsDarkMode ? DARK_TEXT : LIGHT_TEXT;
        int mutedCol = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
        int blue = Color.parseColor("#0284C7");

        if (draft.models.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("尚未添加模型，请点击上方 [+ 手动添加模型] 或 [从接口获取模型]。");
            tvEmpty.setTextColor(mutedCol);
            tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvEmpty.setPadding(dp(4), dp(8), dp(4), dp(8));
            container.addView(tvEmpty);
            return;
        }

        for (int i = 0; i < draft.models.size(); i++) {
            final int index = i;
            final AcidModelStore.ModelEntry m = draft.models.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            row.setBackground(pillBg(rowBg, borderCol, 8));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = dp(5);

            LinearLayout textColView = new LinearLayout(this);
            textColView.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

            TextView tvName = new TextView(this);
            tvName.setText(m.name);
            tvName.setTextColor(textCol);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            tvName.setSingleLine(true);
            textColView.addView(tvName);

            TextView tvId = new TextView(this);
            tvId.setText("ID: " + m.id);
            tvId.setTextColor(mutedCol);
            tvId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            tvId.setSingleLine(true);
            textColView.addView(tvId);

            row.addView(textColView, tcLp);

            // Toggle whether this model supports reasoning effort (思考深度)
            TextView btnReasonToggle = new TextView(this);
            btnReasonToggle.setText(m.supportsReasoning ? "思考深度: 开" : "思考深度: 关");
            btnReasonToggle.setTextColor(m.supportsReasoning ? blue : mutedCol);
            btnReasonToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            btnReasonToggle.setTypeface(m.supportsReasoning ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            btnReasonToggle.setPadding(dp(7), dp(3), dp(7), dp(3));
            btnReasonToggle.setBackground(pillBg(
                m.supportsReasoning ? Color.argb(26, 2, 132, 199) : Color.TRANSPARENT,
                m.supportsReasoning ? blue : borderCol, 6
            ));
            btnReasonToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    m.supportsReasoning = !m.supportsReasoning;
                    renderDraftModelsList(container, draft);
                }
            });
            row.addView(btnReasonToggle);

            // Remove model button
            LinearLayout delBox = new LinearLayout(this);
            delBox.setGravity(Gravity.CENTER);
            delBox.setPadding(dp(6), dp(4), dp(2), dp(4));
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(this, AcidIcons.ICON_CLOSE, mutedCol, 13);
            delBox.addView(delIcon);
            delBox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.models.remove(index);
                    renderDraftModelsList(container, draft);
                }
            });
            row.addView(delBox);

            container.addView(row, rLp);
        }
    }

    private void showManualAddModelDialog(final AcidModelStore.ConfiguredProvider draft, final LinearLayout modelsListBox) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView tvId = new TextView(this);
        tvId.setText("模型 ID (必填，请求接口时发送的 model 字段):");
        tvId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        box.addView(tvId);

        final EditText etId = new EditText(this);
        etId.setSingleLine(true);
        etId.setHint("例如: deepseek-reasoner / gpt-4.1 / claude-sonnet-4");
        etId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        box.addView(etId);

        TextView tvName = new TextView(this);
        tvName.setText("显示名称 (选填，留空则使用模型 ID):");
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvName.setPadding(0, dp(8), 0, 0);
        box.addView(tvName);

        final EditText etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setHint("例如: DeepSeek R1 思考模型");
        etName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        box.addView(etName);

        final android.widget.CheckBox cbReasoning = new android.widget.CheckBox(this);
        cbReasoning.setText("支持调节思考深度 (Reasoning Effort)");
        cbReasoning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        cbReasoning.setChecked(true);
        box.addView(cbReasoning);

        new AlertDialog.Builder(this, mIsDarkMode ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
            .setTitle("手动添加模型")
            .setView(box)
            .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String id = etId.getText().toString().trim();
                    if (id.isEmpty()) {
                        Toast.makeText(BrowserActivity.this, "模型 ID 不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = id;
                    if (draft.findModel(id) != null) {
                        Toast.makeText(BrowserActivity.this, "该模型 ID 已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    draft.models.add(new AcidModelStore.ModelEntry(id, name, cbReasoning.isChecked()));
                    renderDraftModelsList(modelsListBox, draft);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showDiscoveredModelsPickerDialog(
        final AcidModelStore.ConfiguredProvider draft,
        final List<AcidModelStore.ModelEntry> discovered,
        final LinearLayout modelsListBox
    ) {
        final String[] labels = new String[discovered.size()];
        final boolean[] checked = new boolean[discovered.size()];
        for (int i = 0; i < discovered.size(); i++) {
            AcidModelStore.ModelEntry m = discovered.get(i);
            labels[i] = m.id + (m.supportsReasoning ? " [支持思考]" : "");
            checked[i] = (draft.findModel(m.id) != null);
        }

        new AlertDialog.Builder(this, mIsDarkMode ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
            .setTitle("选择要装载的模型 (共发现 " + discovered.size() + " 个)")
            .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                    checked[which] = isChecked;
                }
            })
            .setPositiveButton("添加所选", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int added = 0;
                    for (int i = 0; i < discovered.size(); i++) {
                        if (checked[i]) {
                            AcidModelStore.ModelEntry m = discovered.get(i);
                            if (draft.findModel(m.id) == null) {
                                draft.models.add(m.copy());
                                added++;
                            }
                        }
                    }
                    renderDraftModelsList(modelsListBox, draft);
                    Toast.makeText(BrowserActivity.this, "已添加 " + added + " 个模型", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void appendConsole(String text) {
        // Internal controller debug logs stay silent in chat stream to keep UI clean
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
                recordHistory(url, title);
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

    private void updateNavButtons() {
        BrowserController.TabSession tab = mController != null ? mController.getActiveTab() : null;
        boolean canBack = tab != null && tab.webView.canGoBack();
        boolean canForward = tab != null && tab.webView.canGoForward();
        int activeIcon = mIsDarkMode ? DARK_ICON : LIGHT_ICON;
        int disabledIcon = mIsDarkMode ? DARK_ICON_DISABLED : LIGHT_ICON_DISABLED;
        mIconBack.setColor(canBack ? activeIcon : disabledIcon);
        mIconForward.setColor(canForward ? activeIcon : disabledIcon);
    }

    @Override
    public void onModeChanged(final boolean isDesktopMode, final int controlMode, final boolean somEnabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int accent = mIsDarkMode ? DARK_ACCENT : LIGHT_ACCENT;
                int muted = mIsDarkMode ? DARK_MUTED : LIGHT_MUTED;
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
                    mBtnTakeoverAction.update(AcidIcons.ICON_HUMAN, "点此接管", COLOR_AMBER);
                } else if (controlMode == AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
                    mTakeoverBar.setVisibility(View.VISIBLE);
                    String reason = mController.getTakeoverReason();
                    mTakeoverIcon.setIcon(AcidIcons.ICON_HUMAN);
                    mTakeoverIcon.setColor(COLOR_AMBER);
                    mTvTakeoverStatus.setText("请人类协助: " + (reason.isEmpty() ? "请完成页面操作" : reason));
                    mTvTakeoverStatus.setTextColor(COLOR_AMBER);
                    mBtnTakeoverAction.update(AcidIcons.ICON_AGENT, "交还AI", accent);
                } else {
                    mTakeoverBar.setVisibility(View.GONE);
                }
                updateMenuCards();
            }
        });
    }

    @Override
    public void onDeepLinkBlocked(final String blockedUri, final int totalBlocked) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBtnShield.setColor(COLOR_AMBER);
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
                if (mTabsOverviewLayout != null && mTabsOverviewLayout.getVisibility() == View.VISIBLE) {
                    renderTabsGrid();
                }
            }
        });
    }

    @Override
    public void onAgentActionLog(final String logLine) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendConsole(logLine);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (mTabsOverviewLayout != null && mTabsOverviewLayout.getVisibility() == View.VISIBLE) {
            closeTabsOverview();
            return;
        }
        if (mListDialogOverlay != null && mListDialogOverlay.getVisibility() == View.VISIBLE) {
            closeListDialog();
            return;
        }
        if (mMenuOverlay != null && mMenuOverlay.getVisibility() == View.VISIBLE) {
            closeMenuSheet();
            return;
        }
        if (mDrawerPanel != null && mDrawerPanel.getVisibility() == View.VISIBLE) {
            closeAgentFloatingWindow();
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
        if (mAgentLoop != null) {
            mAgentLoop.stopLoop();
        }
        if (mHttpServer != null) {
            mHttpServer.stop();
        }
        super.onDestroy();
    }

    // =========================================================================
    // Menu Card Item View Component (Compact 20dp icon version)
    // =========================================================================

    private static class MenuCardItem extends LinearLayout {
        public final AcidIcons.SvgIconView iconView;
        public final TextView titleView;
        public final TextView descView;

        public MenuCardItem(Context context, int iconId, String title, String desc, int iconColor, int textColor, int mutedColor, int cardBgColor, View.OnClickListener listener) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            float d = context.getResources().getDisplayMetrics().density;
            int padH = Math.round(6 * d);
            int padV = Math.round(10 * d);
            setPadding(padH, padV, padH, padV);

            applyCardBg(cardBgColor);

            // Refined icon size: 20dp (compact & elegant)
            iconView = new AcidIcons.SvgIconView(context, iconId, iconColor, 20);
            addView(iconView);

            titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setGravity(Gravity.CENTER);
            LayoutParams tLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = Math.round(5 * d);
            addView(titleView, tLp);

            descView = new TextView(context);
            descView.setText(desc);
            descView.setTextColor(mutedColor);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            descView.setSingleLine(true);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            descView.setGravity(Gravity.CENTER);
            LayoutParams dLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = Math.round(2 * d);
            addView(descView, dLp);

            if (listener != null) {
                setOnClickListener(listener);
            }
        }

        private void applyCardBg(int cardBgColor) {
            float d = getContext().getResources().getDisplayMetrics().density;
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(cardBgColor);
            gd.setCornerRadius(10 * d);
            setBackground(gd);
        }

        public void update(int iconId, String title, String desc, int iconColor, int textColor, int mutedColor, int cardBgColor) {
            iconView.setIcon(iconId);
            iconView.setColor(iconColor);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            descView.setText(desc);
            descView.setTextColor(mutedColor);
            applyCardBg(cardBgColor);
        }
    }
}
