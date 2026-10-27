package com.acid.browser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import java.util.List;
import java.util.Locale;

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
    private AcidIcons.BadgeButton mBtnSom;
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

    // Inspector Drawer
    private LinearLayout mDrawerPanel;
    private EditText mEtTargetRef;
    private EditText mEtFillText;
    private TextView mTvConsoleOutput;
    private ScrollView mConsoleScroll;
    private final StringBuilder mLogBuffer = new StringBuilder();

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

        mHttpServer = new HttpControlServer(mController, HttpControlServer.DEFAULT_PORT);
        mHttpServer.start();

        applyTheme(mIsDarkMode);
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

        // SoM Toggle Badge Button on Top Bar right
        mBtnSom = new AcidIcons.BadgeButton(this, AcidIcons.ICON_SOM, "SoM", LIGHT_MUTED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nextSom = !mController.isSomEnabled();
                mController.setSomEnabled(nextSom);
            }
        });
        mTopBar.addView(mBtnSom);

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

        mMainContentLayout.addView(mCenterFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Collapsible Inspector Drawer ---
        mDrawerPanel = buildInspectorDrawer();
        mDrawerPanel.setVisibility(View.GONE);
        mMainContentLayout.addView(mDrawerPanel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

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
            this, AcidIcons.ICON_TERMINAL, "Agent调试", ":3090控制台",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    boolean show = mDrawerPanel.getVisibility() != View.VISIBLE;
                    mDrawerPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            }
        );
        LinearLayout.LayoutParams itemLp7 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp7.leftMargin = dp(4);
        row2.addView(mMenuItemAgent, itemLp7);

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

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340));
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
    // Collapsible Inspector Drawer
    // =========================================================================

    private LinearLayout buildInspectorDrawer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#0F172A"));
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        AcidIcons.BadgeButton btnObserve = new AcidIcons.BadgeButton(this, AcidIcons.ICON_EYE, "Observe", Color.parseColor("#00FF88"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String compact = obs.optString("compactText", obs.toString());
                                appendConsole("--- [OBSERVE SNAPSHOT] ---\n" + compact);
                            }
                        });
                    }
                }).start();
            }
        });
        actionRow.addView(btnObserve);

        mEtTargetRef = new EditText(this);
        mEtTargetRef.setText("e1");
        mEtTargetRef.setHint("e1");
        mEtTargetRef.setTextColor(Color.parseColor("#00FF88"));
        mEtTargetRef.setHintTextColor(Color.parseColor("#64748B"));
        mEtTargetRef.setSingleLine(true);
        mEtTargetRef.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mEtTargetRef.setPadding(dp(6), dp(3), dp(6), dp(3));
        mEtTargetRef.setBackground(pillBg(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 4));
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT);
        refLp.setMargins(dp(4), 0, dp(2), 0);
        actionRow.addView(mEtTargetRef, refLp);

        AcidIcons.BadgeButton btnClickRef = new AcidIcons.BadgeButton(this, AcidIcons.ICON_CLICK, "Click", Color.parseColor("#00E5FF"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String ref = mEtTargetRef.getText().toString().trim();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final JSONObject res = mController.clickRefSync(ref, true);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                appendConsole("Click(" + ref + ") -> " + res.toString());
                            }
                        });
                    }
                }).start();
            }
        });
        actionRow.addView(btnClickRef);

        mEtFillText = new EditText(this);
        mEtFillText.setText("Acid Agent");
        mEtFillText.setHint("输入文本...");
        mEtFillText.setTextColor(Color.WHITE);
        mEtFillText.setHintTextColor(Color.parseColor("#64748B"));
        mEtFillText.setSingleLine(true);
        mEtFillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mEtFillText.setPadding(dp(6), dp(3), dp(6), dp(3));
        mEtFillText.setBackground(pillBg(Color.parseColor("#1E293B"), Color.parseColor("#334155"), 4));
        LinearLayout.LayoutParams fillLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fillLp.setMargins(dp(4), 0, dp(2), 0);
        actionRow.addView(mEtFillText, fillLp);

        AcidIcons.BadgeButton btnFillRef = new AcidIcons.BadgeButton(this, AcidIcons.ICON_FILL, "Fill", Color.parseColor("#F59E0B"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String ref = mEtTargetRef.getText().toString().trim();
                final String txt = mEtFillText.getText().toString();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final JSONObject res = mController.fillSync(ref, txt, true, false);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                appendConsole("Fill(" + ref + ") -> " + res.toString());
                            }
                        });
                    }
                }).start();
            }
        });
        actionRow.addView(btnFillRef);

        panel.addView(actionRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mConsoleScroll = new ScrollView(this);
        mTvConsoleOutput = new TextView(this);
        mTvConsoleOutput.setTypeface(Typeface.MONOSPACE);
        mTvConsoleOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        mTvConsoleOutput.setTextColor(Color.parseColor("#00FF88"));
        mTvConsoleOutput.setPadding(dp(6), dp(6), dp(6), dp(6));
        mTvConsoleOutput.setText("Acid-broswer Inspector Ready (127.0.0.1:3090).\nTap [Observe] to inspect live [e1..eN] handles.");
        mConsoleScroll.addView(mTvConsoleOutput);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(4);
        panel.addView(mConsoleScroll, scrollLp);

        return panel;
    }

    private void appendConsole(String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        mLogBuffer.append("[").append(time).append("] ").append(text).append("\n");
        if (mLogBuffer.length() > 12000) {
            mLogBuffer.delete(0, mLogBuffer.length() - 9000);
        }
        mTvConsoleOutput.setText(mLogBuffer.toString());
        mConsoleScroll.post(new Runnable() {
            @Override
            public void run() {
                mConsoleScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
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
                if (somEnabled) {
                    mBtnSom.update(AcidIcons.ICON_SOM, "SoM:ON", accent);
                } else {
                    mBtnSom.update(AcidIcons.ICON_SOM, "SoM", muted);
                }

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
            mDrawerPanel.setVisibility(View.GONE);
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
