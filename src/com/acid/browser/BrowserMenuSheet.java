package com.acid.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Bottom Pop-up Menu Panel ("功能" - Modal Bottom Sheet).
 * Multi-page layout (8 tools per page, 4x2 grid).
 * Compact 2-line cards (Icon + Title, no description).
 * Distinct ON/OFF states:
 *  - Active: Accent Green (#00BA76/#00FF88) + Active Icon
 *  - Inactive: Muted Slate Gray + Inactive/Off Icon
 */
public class BrowserMenuSheet extends FrameLayout implements AcidOverlay {

    public interface MenuActionListener {
        void onToggleDarkMode();
        void onToggleIncognitoMode();
        void onOpenBookmarks();
        void onOpenHistory();
        void onToggleDesktopMode();
        void onOpenDownloads();
        void onOpenAgentFloatingWindow();
        void onOpenModelProviderConfig();
        void onOpenAbout();
        void onToggleMcpServer();
        void onOpenUserscripts();
        void onRunCustomTool(UserscriptMeta meta);
        void onEnableCustomTool(UserscriptMeta meta);
    }

    /**
     * Compact 2-line tool card: [Icon] then [Title].
     */
    public static class MenuCardItem extends LinearLayout {
        public final AcidIcons.SvgIconView iconView;
        public final TextView titleView;

        public MenuCardItem(Context context, int iconId, String title,
                            int iconColor, int textColor, int cardBgColor,
                            OnClickListener listener) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            float d = context.getResources().getDisplayMetrics().density;
            int padH = Math.round(4 * d);
            int padV = Math.round(9 * d);
            setPadding(padH, padV, padH, padV);

            applyCardBg(cardBgColor);

            iconView = new AcidIcons.SvgIconView(context, iconId, iconColor, 22);
            addView(iconView);

            titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(Gravity.CENTER);
            LayoutParams tLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = Math.round(5 * d);
            addView(titleView, tLp);

            setClickable(true);
            setFocusable(true);
            if (listener != null) {
                setOnClickListener(listener);
            }
        }

        public MenuCardItem(Context context, String svgPath, String title,
                            int iconColor, int textColor, int cardBgColor,
                            OnClickListener listener) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            float d = context.getResources().getDisplayMetrics().density;
            int padH = Math.round(4 * d);
            int padV = Math.round(9 * d);
            setPadding(padH, padV, padH, padV);

            applyCardBg(cardBgColor);

            iconView = new AcidIcons.SvgIconView(context, svgPath, iconColor, 22);
            addView(iconView);

            titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(Gravity.CENTER);
            LayoutParams tLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = Math.round(5 * d);
            addView(titleView, tLp);

            setClickable(true);
            setFocusable(true);
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

        public void update(int iconId, String title, int iconColor, int textColor, int cardBgColor) {
            iconView.setIcon(iconId);
            iconView.setColor(iconColor);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            applyCardBg(cardBgColor);
        }
    }

    /**
     * Paging HorizontalScrollView that snaps smoothly and symmetrically to page boundaries.
     * Supports both fling flick gestures and distance-based threshold dragging.
     */
    public static class PagingHorizontalScrollView extends HorizontalScrollView {
        public interface OnPageChangeListener {
            void onPageChanged(int page);
        }

        private int mPageWidth = 0;
        private int mCurrentPage = 0;
        private OnPageChangeListener mPageListener;

        private VelocityTracker mVelocityTracker;
        private float mDownX;
        private int mMinimumFlingVelocity;
        private int mMaximumFlingVelocity;

        public PagingHorizontalScrollView(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            ViewConfiguration vc = ViewConfiguration.get(context);
            mMinimumFlingVelocity = vc.getScaledMinimumFlingVelocity();
            mMaximumFlingVelocity = vc.getScaledMaximumFlingVelocity();
        }

        public void setPageWidth(int w) {
            mPageWidth = w;
        }

        public void setOnPageChangeListener(OnPageChangeListener listener) {
            mPageListener = listener;
        }

        public void goToPage(int page) {
            mCurrentPage = page;
            int pw = (mPageWidth > 0) ? mPageWidth : getWidth();
            if (pw > 0) {
                smoothScrollTo(page * pw, 0);
            }
            if (mPageListener != null) {
                mPageListener.onPageChanged(mCurrentPage);
            }
        }

        private void initVelocityTrackerIfNotExists() {
            if (mVelocityTracker == null) {
                mVelocityTracker = VelocityTracker.obtain();
            }
        }

        private void recycleVelocityTracker() {
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
        }

        @Override
        public void fling(int velocityX) {
            // Disable free unconstrained flings to prevent landing halfway between pages
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            initVelocityTrackerIfNotExists();
            mVelocityTracker.addMovement(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = ev.getX();
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    recycleVelocityTracker();
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            initVelocityTrackerIfNotExists();
            mVelocityTracker.addMovement(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = ev.getX();
                    break;
                case MotionEvent.ACTION_UP:
                    snapToTargetPage(ev.getX() - mDownX);
                    recycleVelocityTracker();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    goToPage(mCurrentPage);
                    recycleVelocityTracker();
                    return true;
            }
            return super.onTouchEvent(ev);
        }

        private void snapToTargetPage(float deltaX) {
            int pw = (mPageWidth > 0) ? mPageWidth : getWidth();
            if (pw <= 0) return;

            // Total pages: dynamically query child container or default to 2
            int totalPages = 2;
            View child = getChildAt(0);
            if (child instanceof ViewGroup) {
                int count = ((ViewGroup) child).getChildCount();
                if (count > 0) totalPages = count;
            }

            int current = mCurrentPage;
            int scrollX = getScrollX();
            int targetPage = current;

            float xVelocity = 0;
            if (mVelocityTracker != null) {
                mVelocityTracker.computeCurrentVelocity(1000, mMaximumFlingVelocity);
                xVelocity = mVelocityTracker.getXVelocity();
            }

            // Balanced thresholds:
            // 1. Fling: responsive flick threshold
            float flingThreshold = Math.max(mMinimumFlingVelocity * 1.2f, 400f);
            // 2. Drag: 20% of page width is sufficient to turn page
            float dragThreshold = pw * 0.20f;

            if (xVelocity < -flingThreshold) {
                // Flick left -> next page
                targetPage = current + 1;
            } else if (xVelocity > flingThreshold) {
                // Flick right -> previous page
                targetPage = current - 1;
            } else {
                // Drag distance and position based snap
                float pageOffset = (float) scrollX / (float) pw;
                float deltaFromCurrent = pageOffset - (float) current;

                if (deltaFromCurrent > 0.20f || deltaX < -dragThreshold) {
                    // Clearly dragged towards next page
                    targetPage = current + 1;
                } else if (deltaFromCurrent < -0.20f || deltaX > dragThreshold) {
                    // Clearly dragged towards previous page
                    targetPage = current - 1;
                } else {
                    // Small perturbation: snap to nearest page (50% midpoint)
                    targetPage = Math.round(pageOffset);
                }
            }

            // Clamp within valid range
            targetPage = Math.max(0, Math.min(targetPage, totalPages - 1));
            goToPage(targetPage);
        }
    }

    private final View mMenuScrim;
    private final LinearLayout mMenuCard;
    private final View mHandle;
    private final TextView mMenuTitle;
    private final AcidIcons.SvgIconView mBtnMenuClose;

    private final PagingHorizontalScrollView mHorizontalPager;
    private final LinearLayout mPagesContainer;
    private final LinearLayout page1;
    private final LinearLayout page2;
    private final LinearLayout mIndicatorBar;
    private final View mDot1;
    private final View mDot2;

    // Dynamic strip below the pager for agent-authored scripts.
    private final ScrollView mCustomToolsScroll;
    private final LinearLayout mCustomToolsContainer;

    // Page 1: 8 tools (4x2)
    private final MenuCardItem mMenuItemNight;
    private final MenuCardItem mMenuItemIncognito;
    private final MenuCardItem mMenuItemBookmark;
    private final MenuCardItem mMenuItemHistory;
    private final MenuCardItem mMenuItemDesktop;
    private final MenuCardItem mMenuItemDownload;
    private final MenuCardItem mMenuItemAgent;
    private final MenuCardItem mMenuItemModelConfig;

    // Page 2: 9th, 10th & 11th tools (About, MCP Server, Userscripts)
    private final MenuCardItem mMenuItemAbout;
    private final MenuCardItem mMenuItemMcp;
    private final MenuCardItem mMenuItemScripts;
    private boolean mIsMcpRunning = false;

    private final MenuActionListener mListener;
    private boolean mIsDarkMode = false;
    private int mCurrentPage = 0;

    public BrowserMenuSheet(Context context, MenuActionListener listener) {
        super(context);
        mListener = listener;
        setVisibility(View.GONE);

        // 1. Semi-transparent scrim
        mMenuScrim = new View(context);
        mMenuScrim.setBackgroundColor(AcidTheme.SCRIM);
        mMenuScrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        addView(mMenuScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Bottom rounded card
        mMenuCard = new LinearLayout(context);
        mMenuCard.setOrientation(LinearLayout.VERTICAL);
        int padH = AcidTheme.dp(context, 14);
        int padTop = AcidTheme.dp(context, 10);
        int padBottom = AcidTheme.dp(context, 12);
        mMenuCard.setPadding(padH, padTop, padH, padBottom);

        // Top drag handle
        mHandle = new View(context);
        mHandle.setBackground(AcidTheme.pillBg(context, AcidTheme.handle(false), 0, 2));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(AcidTheme.dp(context, 36), AcidTheme.dp(context, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = AcidTheme.dp(context, 8);
        mMenuCard.addView(mHandle, hLp);

        // Header row ("功能" + Close)
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        mMenuTitle = new TextView(context);
        mMenuTitle.setText("功能");
        mMenuTitle.setTextColor(AcidTheme.LIGHT_TEXT);
        mMenuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        mMenuTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(mMenuTitle, titleLp);

        LinearLayout closeBox = new LinearLayout(context);
        closeBox.setGravity(Gravity.CENTER);
        int padClose = AcidTheme.dp(context, 4);
        closeBox.setPadding(padClose, padClose, padClose, padClose);
        mBtnMenuClose = new AcidIcons.SvgIconView(context, AcidIcons.ICON_CLOSE, AcidTheme.LIGHT_MUTED, 18);
        closeBox.addView(mBtnMenuClose);
        closeBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        titleRow.addView(closeBox);
        mMenuCard.addView(titleRow);

        // 3. Multi-page Horizontal Pager
        mHorizontalPager = new PagingHorizontalScrollView(context);

        mPagesContainer = new LinearLayout(context);
        mPagesContainer.setOrientation(LinearLayout.HORIZONTAL);

        // --- PAGE 1 (8 tools, 4x2 grid) ---
        page1 = new LinearLayout(context);
        page1.setOrientation(LinearLayout.VERTICAL);

        // Row 1: Night, Incognito, Bookmarks, History
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, AcidTheme.dp(context, 8), 0, AcidTheme.dp(context, 4));

        mMenuItemNight = new MenuCardItem(
            context, AcidIcons.ICON_SUN, "夜间模式",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onToggleDarkMode();
                }
            }
        );
        LinearLayout.LayoutParams itemLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp1.rightMargin = AcidTheme.dp(context, 4);
        row1.addView(mMenuItemNight, itemLp1);

        mMenuItemIncognito = new MenuCardItem(
            context, AcidIcons.ICON_INCOGNITO_OFF, "无痕模式",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onToggleIncognitoMode();
                }
            }
        );
        LinearLayout.LayoutParams itemLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp2.leftMargin = AcidTheme.dp(context, 2);
        itemLp2.rightMargin = AcidTheme.dp(context, 2);
        row1.addView(mMenuItemIncognito, itemLp2);

        mMenuItemBookmark = new MenuCardItem(
            context, AcidIcons.ICON_BOOKMARK, "书签收藏",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenBookmarks();
                }
            }
        );
        LinearLayout.LayoutParams itemLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp3.leftMargin = AcidTheme.dp(context, 2);
        itemLp3.rightMargin = AcidTheme.dp(context, 2);
        row1.addView(mMenuItemBookmark, itemLp3);

        mMenuItemHistory = new MenuCardItem(
            context, AcidIcons.ICON_HISTORY, "历史浏览",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenHistory();
                }
            }
        );
        LinearLayout.LayoutParams itemLp4 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp4.leftMargin = AcidTheme.dp(context, 4);
        row1.addView(mMenuItemHistory, itemLp4);
        page1.addView(row1);

        // Row 2: Desktop, Downloads, Agent, Model Config
        LinearLayout row2 = new LinearLayout(context);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, AcidTheme.dp(context, 4), 0, AcidTheme.dp(context, 4));

        mMenuItemDesktop = new MenuCardItem(
            context, AcidIcons.ICON_MOBILE, "电脑模式",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onToggleDesktopMode();
                }
            }
        );
        LinearLayout.LayoutParams itemLp5 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp5.rightMargin = AcidTheme.dp(context, 4);
        row2.addView(mMenuItemDesktop, itemLp5);

        mMenuItemDownload = new MenuCardItem(
            context, AcidIcons.ICON_DOWNLOAD, "下载管理",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenDownloads();
                }
            }
        );
        LinearLayout.LayoutParams itemLp6 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp6.leftMargin = AcidTheme.dp(context, 2);
        itemLp6.rightMargin = AcidTheme.dp(context, 2);
        row2.addView(mMenuItemDownload, itemLp6);

        mMenuItemAgent = new MenuCardItem(
            context, AcidIcons.ICON_AGENT, "Acid Agent",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenAgentFloatingWindow();
                }
            }
        );
        LinearLayout.LayoutParams itemLp7 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp7.leftMargin = AcidTheme.dp(context, 2);
        itemLp7.rightMargin = AcidTheme.dp(context, 2);
        row2.addView(mMenuItemAgent, itemLp7);

        mMenuItemModelConfig = new MenuCardItem(
            context, AcidIcons.ICON_MODEL, "模型配置",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenModelProviderConfig();
                }
            }
        );
        LinearLayout.LayoutParams itemLp8 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp8.leftMargin = AcidTheme.dp(context, 4);
        row2.addView(mMenuItemModelConfig, itemLp8);
        page1.addView(row2);

        // --- PAGE 2 (9th tool: About) ---
        page2 = new LinearLayout(context);
        page2.setOrientation(LinearLayout.VERTICAL);

        LinearLayout page2Row1 = new LinearLayout(context);
        page2Row1.setOrientation(LinearLayout.HORIZONTAL);
        page2Row1.setPadding(0, AcidTheme.dp(context, 8), 0, AcidTheme.dp(context, 4));

        mMenuItemAbout = new MenuCardItem(
            context, AcidIcons.ICON_INFO, "关于",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenAbout();
                }
            }
        );
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        aboutLp.rightMargin = AcidTheme.dp(context, 2);
        page2Row1.addView(mMenuItemAbout, aboutLp);

        mMenuItemMcp = new MenuCardItem(
            context, AcidIcons.ICON_TERMINAL, "MCP 服务",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onToggleMcpServer();
                }
            }
        );
        LinearLayout.LayoutParams mcpLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mcpLp.leftMargin = AcidTheme.dp(context, 2);
        mcpLp.rightMargin = AcidTheme.dp(context, 2);
        page2Row1.addView(mMenuItemMcp, mcpLp);

        mMenuItemScripts = new MenuCardItem(
            context, AcidIcons.ICON_FILE, "脚本管理",
            AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenUserscripts();
                }
            }
        );
        LinearLayout.LayoutParams scriptsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scriptsLp.leftMargin = AcidTheme.dp(context, 2);
        scriptsLp.rightMargin = AcidTheme.dp(context, 2);
        page2Row1.addView(mMenuItemScripts, scriptsLp);

        // 1 transparent placeholder cell to maintain identical 4-column widths
        View placeholder = new View(context);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        phLp.leftMargin = AcidTheme.dp(context, 2);
        page2Row1.addView(placeholder, phLp);
        page2.addView(page2Row1);

        // Row 2 placeholder row on Page 2 for identical height stability
        LinearLayout page2Row2 = new LinearLayout(context);
        page2Row2.setOrientation(LinearLayout.HORIZONTAL);
        page2Row2.setPadding(0, AcidTheme.dp(context, 4), 0, AcidTheme.dp(context, 4));
        for (int i = 0; i < 4; i++) {
            View ph = new View(context);
            LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(0, AcidTheme.dp(context, 54), 1f);
            if (i > 0) r2Lp.leftMargin = AcidTheme.dp(context, 2);
            page2Row2.addView(ph, r2Lp);
        }
        page2.addView(page2Row2);

        mPagesContainer.addView(page1);
        mPagesContainer.addView(page2);

        // Exact measured height: 2 rows of cards (~142dp)
        int pagerHeight = AcidTheme.dp(context, 142);
        LinearLayout.LayoutParams pagerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, pagerHeight
        );
        mHorizontalPager.addView(mPagesContainer, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        mMenuCard.addView(mHorizontalPager, pagerLp);

        // 4. Page Indicator Bar (● ○)
        mIndicatorBar = new LinearLayout(context);
        mIndicatorBar.setOrientation(LinearLayout.HORIZONTAL);
        mIndicatorBar.setGravity(Gravity.CENTER);
        mIndicatorBar.setPadding(0, AcidTheme.dp(context, 4), 0, AcidTheme.dp(context, 2));

        int dotSize = AcidTheme.dp(context, 7);
        mDot1 = new View(context);
        mDot1.setBackground(AcidTheme.pillBg(context, AcidTheme.LIGHT_ACCENT, 0, 3.5f));
        LinearLayout.LayoutParams dotLp1 = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp1.rightMargin = AcidTheme.dp(context, 6);
        mDot1.setLayoutParams(dotLp1);
        mDot1.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                switchPage(0);
            }
        });
        mIndicatorBar.addView(mDot1);

        mDot2 = new View(context);
        mDot2.setBackground(AcidTheme.pillBg(context, AcidTheme.handle(false), 0, 3.5f));
        LinearLayout.LayoutParams dotLp2 = new LinearLayout.LayoutParams(dotSize, dotSize);
        mDot2.setLayoutParams(dotLp2);
        mDot2.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                switchPage(1);
            }
        });
        mIndicatorBar.addView(mDot2);

        mMenuCard.addView(mIndicatorBar);

        // 5. Dynamic area for agent-authored scripts.
        // Kept outside the pager: the pager's height is fixed at 142dp so both pages line
        // up, and appending to page 2 would push its content past that clip. A separate
        // scrollable strip below the dots grows without disturbing the paging geometry.
        mCustomToolsScroll = new ScrollView(context);
        mCustomToolsScroll.setVerticalScrollBarEnabled(false);
        mCustomToolsContainer = new LinearLayout(context);
        mCustomToolsContainer.setOrientation(LinearLayout.VERTICAL);
        mCustomToolsScroll.addView(mCustomToolsContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        mCustomToolsScroll.setVisibility(View.GONE);
        mMenuCard.addView(mCustomToolsScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Track page width dynamically
        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int padCard = AcidTheme.dp(getContext(), 14) * 2;
                int contentW = getWidth() - padCard;
                if (contentW > 0) {
                    mHorizontalPager.setPageWidth(contentW);
                    ViewGroup.LayoutParams p1Lp = page1.getLayoutParams();
                    if (p1Lp != null && p1Lp.width != contentW) {
                        p1Lp.width = contentW;
                        page1.setLayoutParams(p1Lp);
                    }
                    ViewGroup.LayoutParams p2Lp = page2.getLayoutParams();
                    if (p2Lp != null && p2Lp.width != contentW) {
                        p2Lp.width = contentW;
                        page2.setLayoutParams(p2Lp);
                    }
                }
            }
        });

        mHorizontalPager.setOnPageChangeListener(new PagingHorizontalScrollView.OnPageChangeListener() {
            @Override
            public void onPageChanged(int page) {
                updateIndicator(page);
            }
        });

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        );
        addView(mMenuCard, cardLp);

        applyTheme(false);
    }

    public void switchPage(int page) {
        mCurrentPage = page;
        mHorizontalPager.goToPage(page);
        updateIndicator(page);
    }

    private void updateIndicator(int page) {
        mCurrentPage = page;
        Context ctx = getContext();
        int activeDot = AcidTheme.accent(mIsDarkMode);
        int inactiveDot = AcidTheme.handle(mIsDarkMode);

        mDot1.setBackground(AcidTheme.pillBg(ctx, page == 0 ? activeDot : inactiveDot, 0, 3.5f));
        mDot2.setBackground(AcidTheme.pillBg(ctx, page == 1 ? activeDot : inactiveDot, 0, 3.5f));
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    @Override
    public void show() {
        switchPage(0);
        bringToFront();
        setVisibility(View.VISIBLE);
    }

    @Override
    public void dismiss() {
        setVisibility(View.GONE);
    }

    @Override
    public boolean handleBackPressed() {
        if (!isShowing()) return false;
        dismiss();
        return true;
    }

    public void toggle() {
        if (isShowing()) {
            dismiss();
        } else {
            show();
        }
    }

    public void updateCards(boolean isDarkMode, boolean isIncognito, boolean isDesktop, int providerCount, int modelCount) {
        mIsDarkMode = isDarkMode;
        int accent = AcidTheme.accent(isDarkMode);
        int muted = AcidTheme.muted(isDarkMode);
        int text = AcidTheme.text(isDarkMode);
        int itemBg = AcidTheme.menuItemBg(isDarkMode);

        // 1. Dark Mode: ON = Green + Moon; OFF = Muted Gray + Sun
        mMenuItemNight.update(
            isDarkMode ? AcidIcons.ICON_MOON : AcidIcons.ICON_SUN,
            "夜间模式",
            isDarkMode ? accent : muted,
            text, itemBg
        );

        // 2. Incognito Mode: ON = Green + Active Incognito; OFF = Muted Gray + Slashed Incognito
        mMenuItemIncognito.update(
            isIncognito ? AcidIcons.ICON_INCOGNITO : AcidIcons.ICON_INCOGNITO_OFF,
            "无痕模式",
            isIncognito ? accent : muted,
            text, itemBg
        );

        // 3. Desktop Mode: ON = Green + Desktop; OFF = Muted Gray + Mobile
        mMenuItemDesktop.update(
            isDesktop ? AcidIcons.ICON_DESKTOP : AcidIcons.ICON_MOBILE,
            isDesktop ? "电脑模式" : "手机模式",
            isDesktop ? accent : muted,
            text, itemBg
        );

        // 4. Operational Tools: Muted Gray (standard clean icon state)
        mMenuItemBookmark.update(AcidIcons.ICON_BOOKMARK, "书签收藏", muted, text, itemBg);
        mMenuItemHistory.update(AcidIcons.ICON_HISTORY, "历史浏览", muted, text, itemBg);
        mMenuItemDownload.update(AcidIcons.ICON_DOWNLOAD, "下载管理", muted, text, itemBg);
        mMenuItemAgent.update(AcidIcons.ICON_AGENT, "Acid Agent", muted, text, itemBg);
        mMenuItemModelConfig.update(AcidIcons.ICON_MODEL, "模型配置", muted, text, itemBg);
        mMenuItemAbout.update(AcidIcons.ICON_INFO, "关于", muted, text, itemBg);
        mMenuItemScripts.update(AcidIcons.ICON_FILE, "脚本管理", muted, text, itemBg);
        updateMcpState(mIsMcpRunning);

        updateIndicator(mCurrentPage);
    }

    public void updateMcpState(boolean isRunning) {
        mIsMcpRunning = isRunning;
        int accent = AcidTheme.accent(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);
        int text = AcidTheme.text(mIsDarkMode);
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        if (mMenuItemMcp != null) {
            mMenuItemMcp.update(
                AcidIcons.ICON_TERMINAL,
                mIsMcpRunning ? "MCP 运行中" : "MCP 已关闭",
                mIsMcpRunning ? accent : muted,
                text,
                itemBg
            );
        }
    }

    public void renderCustomTools(final List<UserscriptMeta> customTools) {
        if (mCustomToolsContainer == null || mCustomToolsScroll == null) return;
        mCustomToolsContainer.removeAllViews();
        if (customTools == null || customTools.isEmpty()) {
            mCustomToolsScroll.setVisibility(View.GONE);
            return;
        }

        mCustomToolsScroll.setVisibility(View.VISIBLE);
        Context ctx = getContext();
        int textCol = AcidTheme.text(mIsDarkMode);
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int accent = AcidTheme.accent(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);

        // Header line: "快捷工具 (N)"
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, AcidTheme.dp(ctx, 8), 0, AcidTheme.dp(ctx, 4));

        TextView tvSection = new TextView(ctx);
        tvSection.setText("快捷工具 (" + customTools.size() + ")");
        tvSection.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvSection.setTypeface(Typeface.DEFAULT_BOLD);
        tvSection.setTextColor(muted);
        header.addView(tvSection);

        mCustomToolsContainer.addView(header);

        int total = customTools.size();
        for (int i = 0; i < total; i += 4) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, AcidTheme.dp(ctx, 4), 0, AcidTheme.dp(ctx, 4));

            for (int col = 0; col < 4; col++) {
                int index = i + col;
                if (index < total) {
                    final UserscriptMeta tool = customTools.get(index);
                    int iconColor = tool.enabled ? accent : AcidTheme.COLOR_AMBER;
                    String title = tool.name;
                    if (!tool.enabled) {
                        title = title + " (待启用)";
                    }
                    MenuCardItem card = new MenuCardItem(
                        ctx,
                        !TextUtils.isEmpty(tool.iconPath) ? tool.iconPath : "M 5 4 H 19 V 20 H 5 Z",
                        title,
                        iconColor,
                        textCol,
                        itemBg,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (!tool.enabled) {
                                    showScriptConfirmDialog(tool);
                                } else {
                                    dismiss();
                                    if (mListener != null) {
                                        mListener.onRunCustomTool(tool);
                                    }
                                }
                            }
                        }
                    );
                    card.setOnLongClickListener(new OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            showScriptConfirmDialog(tool);
                            return true;
                        }
                    });
                    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (col > 0) cardLp.leftMargin = AcidTheme.dp(ctx, 4);
                    row.addView(card, cardLp);
                } else {
                    View ph = new View(ctx);
                    LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (col > 0) phLp.leftMargin = AcidTheme.dp(ctx, 4);
                    row.addView(ph, phLp);
                }
            }
            mCustomToolsContainer.addView(row);
        }
    }

    public void showScriptConfirmDialog(final UserscriptMeta meta) {
        Context ctx = getContext();
        AlertDialog.Builder b = AcidTheme.dialogBuilder(ctx, mIsDarkMode);
        b.setTitle((meta.enabled ? "工具详情: " : "启用工具: ") + meta.name);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = AcidTheme.dp(ctx, 20);
        int padV = AcidTheme.dp(ctx, 14);
        box.setPadding(padH, padV, padH, padV);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        AcidIcons.SvgIconView iconView;
        if (!TextUtils.isEmpty(meta.iconPath)) {
            iconView = new AcidIcons.SvgIconView(ctx, meta.iconPath, AcidTheme.accent(mIsDarkMode), 28);
        } else {
            iconView = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_FILE, AcidTheme.muted(mIsDarkMode), 28);
        }
        header.addView(iconView);

        LinearLayout hText = new LinearLayout(ctx);
        hText.setOrientation(LinearLayout.VERTICAL);
        hText.setPadding(AcidTheme.dp(ctx, 12), 0, 0, 0);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(meta.name);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(AcidTheme.text(mIsDarkMode));
        hText.addView(tvTitle);

        TextView tvScope = new TextView(ctx);
        tvScope.setText("生效范围: " + meta.describeScope() + "\n时机: " + meta.runAt);
        tvScope.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvScope.setTextColor(AcidTheme.muted(mIsDarkMode));
        hText.addView(tvScope);

        header.addView(hText);
        box.addView(header);

        if (!TextUtils.isEmpty(meta.description)) {
            TextView tvDesc = new TextView(ctx);
            tvDesc.setText(meta.description);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tvDesc.setTextColor(AcidTheme.text(mIsDarkMode));
            tvDesc.setPadding(0, AcidTheme.dp(ctx, 8), 0, 0);
            box.addView(tvDesc);
        }

        TextView tvCode = new TextView(ctx);
        String body = meta.body != null ? meta.body : "";
        if (body.length() > 300) body = body.substring(0, 300) + "\n…";
        tvCode.setText(body);
        tvCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tvCode.setTypeface(Typeface.MONOSPACE);
        tvCode.setTextColor(AcidTheme.DARK_CODE_TEXT);
        tvCode.setPadding(AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 8));
        tvCode.setBackground(AcidTheme.pillBg(ctx, AcidTheme.codeBlockBg(mIsDarkMode), 0, 6));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = AcidTheme.dp(ctx, 10);
        box.addView(tvCode, cLp);

        b.setView(box);
        if (!meta.enabled) {
            b.setPositiveButton("启用并运行", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (mListener != null) {
                        mListener.onEnableCustomTool(meta);
                    }
                }
            });
        } else {
            b.setPositiveButton("在当前页运行", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dismiss();
                    if (mListener != null) {
                        mListener.onRunCustomTool(meta);
                    }
                }
            });
        }
        b.setNegativeButton("关闭", null);
        b.show();
    }

    public void applyTheme(boolean isDark) {
        mIsDarkMode = isDark;
        int bar = AcidTheme.bar(isDark);
        int text = AcidTheme.text(isDark);
        int muted = AcidTheme.muted(isDark);

        mMenuCard.setBackground(AcidTheme.topRoundedSheetBg(getContext(), bar, 16));
        mMenuTitle.setTextColor(text);
        mBtnMenuClose.setColor(muted);
        mHandle.setBackground(AcidTheme.pillBg(
            getContext(),
            AcidTheme.handle(isDark),
            0,
            2
        ));
        updateIndicator(mCurrentPage);
    }
}
