package com.acid.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
 * Modern Clean White Browser Edition (Phase 2):
 * - Clean white theme by default (Normal browser look & feel)
 * - Classic 5-button bottom toolbar (Back, Forward, Menu, Tabs, Home)
 * - Central popup menu sheet (Night mode, Bookmarks, History, Downloads, PC/Mobile toggle, Agent Debug)
 * - Real-time synchronized SoM & Agent HUD loop
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

    // Bottom 5-Button Bar
    private View mBottomDivider;
    private LinearLayout mBottomBar;
    private LinearLayout mBtnBack;
    private AcidIcons.SvgIconView mIconBack;
    private LinearLayout mBtnForward;
    private AcidIcons.SvgIconView mIconForward;
    private LinearLayout mBtnMenu;
    private AcidIcons.SvgIconView mIconMenu;
    private LinearLayout mBtnTabs;
    private AcidIcons.TabBoxView mIconTabs;
    private LinearLayout mBtnHome;
    private AcidIcons.SvgIconView mIconHome;

    // Bottom Pop-up Menu Panel
    private FrameLayout mMenuOverlay;
    private View mMenuScrim;
    private LinearLayout mMenuCard;
    private TextView mMenuTitle;
    private AcidIcons.SvgIconView mBtnMenuClose;
    private MenuCardItem mMenuItemNight;
    private MenuCardItem mMenuItemBookmark;
    private MenuCardItem mMenuItemHistory;
    private MenuCardItem mMenuItemDownload;
    private MenuCardItem mMenuItemDesktop;
    private MenuCardItem mMenuItemAgent;

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

    // =========================================================================
    // UI Construction
    // =========================================================================

    private View buildContentView() {
        mRootContainer = new FrameLayout(this);
        mRootContainer.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. Main vertical layout (TopBar + Viewport + Drawer + BottomBar)
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
                    mController.loadUrl(mEtUrl.getText().toString());
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mSearchContainer.addView(mEtUrl, etLp);

        // Reload Button inside search container
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

        // --- Center Viewport ---
        mCenterFrame = new FrameLayout(this);
        mWebViewContainer = new FrameLayout(this);
        mOverlayView = new AgentOverlayView(this);
        mCenterFrame.addView(mWebViewContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCenterFrame.addView(mOverlayView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mMainContentLayout.addView(mCenterFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Collapsible Inspector Drawer ---
        mDrawerPanel = buildInspectorDrawer();
        mDrawerPanel.setVisibility(View.GONE);
        mMainContentLayout.addView(mDrawerPanel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        // --- Bottom 5-Button Toolbar ---
        mBottomDivider = new View(this);
        mBottomDivider.setBackgroundColor(LIGHT_BORDER);
        mMainContentLayout.addView(mBottomDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        mBottomBar = buildBottomToolbar();
        mMainContentLayout.addView(mBottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        mRootContainer.addView(mMainContentLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Fullscreen Menu Sheet Layer (Modal pop-up)
        mMenuOverlay = buildMenuOverlay();
        mMenuOverlay.setVisibility(View.GONE);
        mRootContainer.addView(mMenuOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
    // Bottom 5-Button Toolbar: Back, Forward, Menu, Tabs, Home
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
                BrowserController.TabSession tab = mController.getActiveTab();
                if (tab != null && tab.webView.canGoForward()) {
                    tab.webView.goForward();
                }
            }
        });
        bar.addView(mBtnForward, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 3. Menu (Center Core)
        mBtnMenu = createBottomTabItem();
        mIconMenu = new AcidIcons.SvgIconView(this, AcidIcons.ICON_MENU, LIGHT_ICON, 21);
        mBtnMenu.addView(mIconMenu);
        mBtnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMenuSheet();
            }
        });
        bar.addView(mBtnMenu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 4. Tabs
        mBtnTabs = createBottomTabItem();
        mIconTabs = new AcidIcons.TabBoxView(this, LIGHT_ICON, 18);
        mBtnTabs.addView(mIconTabs);
        mBtnTabs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTabsDialog();
            }
        });
        bar.addView(mBtnTabs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 5. Home
        mBtnHome = createBottomTabItem();
        mIconHome = new AcidIcons.SvgIconView(this, AcidIcons.ICON_HOME, LIGHT_ICON, 20);
        mBtnHome.addView(mIconHome);
        mBtnHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mController.loadUrl(BrowserController.HOME_URL);
            }
        });
        bar.addView(mBtnHome, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

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
    // Pop-up Menu Panel (Modal Bottom Sheet)
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
        mMenuCard.setPadding(dp(16), dp(10), dp(16), dp(20));

        // Top drag handle
        View handle = new View(this);
        handle.setBackground(pillBg(Color.parseColor("#CBD5E1"), 0, 2));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = dp(10);
        mMenuCard.addView(handle, hLp);

        // Title row with close button
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        mMenuTitle = new TextView(this);
        mMenuTitle.setText("Acid 功能与工具");
        mMenuTitle.setTextColor(LIGHT_TEXT);
        mMenuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
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

        // Menu items grid (Row 1: Night, Bookmarks, History)
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
        itemLp1.rightMargin = dp(6);
        row1.addView(mMenuItemNight, itemLp1);

        mMenuItemBookmark = new MenuCardItem(
            this, AcidIcons.ICON_BOOKMARK, "书签收藏", "快速直达",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    showBookmarksDialog();
                }
            }
        );
        LinearLayout.LayoutParams itemLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp2.leftMargin = dp(3);
        itemLp2.rightMargin = dp(3);
        row1.addView(mMenuItemBookmark, itemLp2);

        mMenuItemHistory = new MenuCardItem(
            this, AcidIcons.ICON_HISTORY, "历史浏览", "足迹记录",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    showHistoryDialog();
                }
            }
        );
        LinearLayout.LayoutParams itemLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp3.leftMargin = dp(6);
        row1.addView(mMenuItemHistory, itemLp3);

        mMenuCard.addView(row1);

        // Menu items grid (Row 2: Downloads, Desktop/Mobile, Agent Debug)
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, dp(6));

        mMenuItemDownload = new MenuCardItem(
            this, AcidIcons.ICON_DOWNLOAD, "下载管理", "文件查看",
            LIGHT_ACCENT, LIGHT_TEXT, LIGHT_MUTED, LIGHT_MENU_ITEM_BG,
            new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeMenuSheet();
                    showDownloadsDialog();
                }
            }
        );
        LinearLayout.LayoutParams itemLp4 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp4.rightMargin = dp(6);
        row2.addView(mMenuItemDownload, itemLp4);

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
        itemLp5.leftMargin = dp(3);
        itemLp5.rightMargin = dp(3);
        row2.addView(mMenuItemDesktop, itemLp5);

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
        LinearLayout.LayoutParams itemLp6 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp6.leftMargin = dp(6);
        row2.addView(mMenuItemAgent, itemLp6);

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

        mIconBack.setColor(mController != null && mController.getActiveTab() != null && mController.getActiveTab().webView.canGoBack() ? icon : (dark ? DARK_ICON_DISABLED : LIGHT_ICON_DISABLED));
        mIconForward.setColor(mController != null && mController.getActiveTab() != null && mController.getActiveTab().webView.canGoForward() ? icon : (dark ? DARK_ICON_DISABLED : LIGHT_ICON_DISABLED));
        mIconMenu.setColor(icon);
        mIconTabs.setColor(icon);
        mIconHome.setColor(icon);

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

    // =========================================================================
    // Dialogs: Tabs, Bookmarks, History, Downloads
    // =========================================================================

    private void showTabsDialog() {
        final List<BrowserController.TabSession> tabs = mController.getTabs();
        BrowserController.TabSession active = mController.getActiveTab();
        String[] items = new String[tabs.size() + 2];
        for (int i = 0; i < tabs.size(); i++) {
            BrowserController.TabSession t = tabs.get(i);
            String prefix = (active != null && active.id == t.id) ? "[*] " : "[ ] ";
            items[i] = prefix + "Tab #" + t.id + " - " + t.title;
        }
        items[tabs.size()] = "[+] 新建标签页";
        items[tabs.size() + 1] = "[x] 关闭当前标签页";

        new AlertDialog.Builder(this)
            .setTitle("标签页管理 (" + tabs.size() + ")")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which < tabs.size()) {
                        mController.switchToTab(tabs.get(which).id);
                    } else if (which == tabs.size()) {
                        mController.createNewTab(BrowserController.HOME_URL, true);
                    } else {
                        BrowserController.TabSession cur = mController.getActiveTab();
                        if (cur != null) {
                            mController.closeTab(cur.id);
                        }
                    }
                }
            })
            .show();
    }

    private void showBookmarksDialog() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String raw = prefs.getString(PREF_BOOKMARKS, "[]");
        final List<BookmarkItem> list = new ArrayList<BookmarkItem>();
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

        String[] items = new String[list.size() + 1];
        items[0] = "[+] 收藏当前网页到书签";
        for (int i = 0; i < list.size(); i++) {
            items[i + 1] = list.get(i).title + " (" + list.get(i).url + ")";
        }

        new AlertDialog.Builder(this)
            .setTitle("书签收藏 (" + list.size() + ")")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        BrowserController.TabSession cur = mController.getActiveTab();
                        if (cur != null && !TextUtils.isEmpty(cur.url)) {
                            list.add(0, new BookmarkItem(cur.title, cur.url));
                            saveBookmarks(list);
                            Toast.makeText(BrowserActivity.this, "已添加当前页到书签！", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        BookmarkItem bm = list.get(which - 1);
                        mController.loadUrl(bm.url);
                    }
                }
            })
            .show();
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

    private void showHistoryDialog() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String raw = prefs.getString(PREF_HISTORY, "[]");
        final List<BookmarkItem> list = new ArrayList<BookmarkItem>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new BookmarkItem(o.getString("title"), o.getString("url")));
            }
        } catch (Exception ignored) {}

        if (list.isEmpty()) {
            Toast.makeText(this, "暂无历史浏览记录", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = new String[list.size() + 1];
        items[0] = "[x] 清空所有浏览历史";
        for (int i = 0; i < list.size(); i++) {
            items[i + 1] = list.get(i).title + " (" + list.get(i).url + ")";
        }

        new AlertDialog.Builder(this)
            .setTitle("历史浏览记录 (" + list.size() + ")")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_HISTORY, "[]").apply();
                        Toast.makeText(BrowserActivity.this, "历史记录已清空", Toast.LENGTH_SHORT).show();
                    } else {
                        mController.loadUrl(list.get(which - 1).url);
                    }
                }
            })
            .show();
    }

    private void recordHistory(String url, String title) {
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

    private void showDownloadsDialog() {
        List<BrowserController.DownloadItem> list = mController.getDownloads();
        if (list.isEmpty()) {
            Toast.makeText(this, "暂无下载任务记录", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            BrowserController.DownloadItem it = list.get(i);
            items[i] = it.url + " (" + it.mimeType + ", " + it.contentLength + "B)";
        }
        new AlertDialog.Builder(this)
            .setTitle("下载任务记录 (" + list.size() + ")")
            .setItems(items, null)
            .setPositiveButton("确定", null)
            .show();
    }

    private static class BookmarkItem {
        public final String title;
        public final String url;
        public BookmarkItem(String title, String url) {
            this.title = title;
            this.url = url;
        }
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
    // Menu Card Item View Component
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
            int padH = Math.round(8 * d);
            int padV = Math.round(12 * d);
            setPadding(padH, padV, padH, padV);

            applyCardBg(cardBgColor);

            iconView = new AcidIcons.SvgIconView(context, iconId, iconColor, 24);
            addView(iconView);

            titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setGravity(Gravity.CENTER);
            LayoutParams tLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = Math.round(6 * d);
            addView(titleView, tLp);

            descView = new TextView(context);
            descView.setText(desc);
            descView.setTextColor(mutedColor);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
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
