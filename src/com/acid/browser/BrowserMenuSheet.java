package com.acid.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bottom Pop-up Menu Panel ("功能") built directly on {@link AcidBottomSheetView}.
 *
 * <p>Inherits the standard scrim, drag handle, {@code H1 (16sp Bold)} header bar, and close
 * target instead of maintaining a divergent hand-rolled sheet shell.
 */
public class BrowserMenuSheet extends AcidBottomSheetView {

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
        void onOpenAdBlock();
        void onOpenSettings();
    }

    /**
     * Compact 2-line tool tile: [Icon] + [Title] with immediate press-state highlight.
     */
    public static class MenuCardItem extends LinearLayout {
        public final AcidIcons.SvgIconView iconView;
        public final TextView titleView;

        public MenuCardItem(
            Context context,
            int iconId,
            String title,
            int iconColor,
            int textColor,
            boolean dark,
            OnClickListener listener
        ) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            int padH = AcidTheme.dp(context, 4);
            int padV = AcidTheme.dp(context, 10);
            setPadding(padH, padV, padH, padV);

            applyCardBg(dark, false);

            iconView = new AcidIcons.SvgIconView(context, iconId, iconColor, 22);
            addView(iconView);

            titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(Gravity.CENTER);
            LayoutParams tLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tLp.topMargin = AcidTheme.dp(context, 5);
            addView(titleView, tLp);

            setClickable(true);
            setFocusable(true);
            if (listener != null) {
                setOnClickListener(listener);
            }
        }

        private void applyCardBg(boolean dark, boolean activeHighlight) {
            Context ctx = getContext();
            int accent = AcidTheme.accent(dark);
            int fill = activeHighlight
                ? AcidTheme.softTint(accent, dark ? 30 : 20)
                : AcidTheme.menuItemBg(dark);
            int stroke = activeHighlight ? AcidTheme.softTint(accent, 90) : AcidTheme.border(dark);
            setBackground(AcidTheme.interactiveBg(
                ctx, fill, AcidTheme.rowPressed(dark), stroke, AcidTheme.RADIUS_CONTROL
            ));
        }

        public void update(int iconId, String title, boolean isActive, boolean dark) {
            int accent = AcidTheme.accent(dark);
            int iconCol = isActive ? accent : AcidTheme.icon(dark);
            int textCol = isActive ? accent : AcidTheme.text(dark);
            iconView.setIcon(iconId);
            iconView.setColor(iconCol);
            titleView.setText(title);
            titleView.setTextColor(textCol);
            applyCardBg(dark, isActive);
        }
    }

    /**
     * Paging HorizontalScrollView that snaps smoothly and symmetrically to page boundaries.
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
        private final int mMinimumFlingVelocity;
        private final int mMaximumFlingVelocity;

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
            // Disable unconstrained flings to snap cleanly on page boundaries
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

            int totalPages = 2;
            View child = getChildAt(0);
            if (child instanceof ViewGroup) {
                int count = ((ViewGroup) child).getChildCount();
                if (count > 0) totalPages = count;
            }

            int current = mCurrentPage;
            int scrollX = getScrollX();
            int targetPage;

            float xVelocity = 0;
            if (mVelocityTracker != null) {
                mVelocityTracker.computeCurrentVelocity(1000, mMaximumFlingVelocity);
                xVelocity = mVelocityTracker.getXVelocity();
            }

            float flingThreshold = Math.max(mMinimumFlingVelocity * 1.2f, 400f);
            float dragThreshold = pw * 0.20f;

            if (xVelocity < -flingThreshold) {
                targetPage = current + 1;
            } else if (xVelocity > flingThreshold) {
                targetPage = current - 1;
            } else {
                float pageOffset = (float) scrollX / (float) pw;
                float deltaFromCurrent = pageOffset - (float) current;
                if (deltaFromCurrent > 0.20f || deltaX < -dragThreshold) {
                    targetPage = current + 1;
                } else if (deltaFromCurrent < -0.20f || deltaX > dragThreshold) {
                    targetPage = current - 1;
                } else {
                    targetPage = Math.round(pageOffset);
                }
            }

            targetPage = Math.max(0, Math.min(targetPage, totalPages - 1));
            goToPage(targetPage);
        }
    }

    private final PagingHorizontalScrollView mHorizontalPager;
    private final LinearLayout mPage1;
    private final LinearLayout mPage2;
    private final View mDot1;
    private final View mDot2;

    // Page 1 (4x2)
    private final MenuCardItem mMenuItemNight;
    private final MenuCardItem mMenuItemIncognito;
    private final MenuCardItem mMenuItemBookmark;
    private final MenuCardItem mMenuItemHistory;
    private final MenuCardItem mMenuItemDesktop;
    private final MenuCardItem mMenuItemDownload;
    private final MenuCardItem mMenuItemAgent;
    private final MenuCardItem mMenuItemSettings;

    // Page 2
    private final MenuCardItem mMenuItemModelConfig;
    private final MenuCardItem mMenuItemAdBlock;
    private final MenuCardItem mMenuItemScripts;
    private final MenuCardItem mMenuItemMcp;
    private final MenuCardItem mMenuItemAbout;

    private final MenuActionListener mListener;
    private boolean mIsIncognito = false;
    private boolean mIsDesktop = false;
    private boolean mIsMcpRunning = false;
    private int mCurrentPage = 0;

    public BrowserMenuSheet(Context context, MenuActionListener listener) {
        super(context);
        mListener = listener;
        setTitle("功能");
        getScrollView().setVisibility(View.GONE);

        LinearLayout host = getPinnedHeaderContainer();
        host.setPadding(0, AcidTheme.dp(context, 4), 0, 0);

        mHorizontalPager = new PagingHorizontalScrollView(context);
        LinearLayout pagesContainer = new LinearLayout(context);
        pagesContainer.setOrientation(LinearLayout.HORIZONTAL);

        // --- PAGE 1 ---
        mPage1 = new LinearLayout(context);
        mPage1.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = createGridRow(context);
        mMenuItemNight = createTile(context, AcidIcons.ICON_SUN, "夜间模式", false, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onToggleDarkMode(); }
        });
        mMenuItemIncognito = createTile(context, AcidIcons.ICON_INCOGNITO_OFF, "无痕模式", false, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onToggleIncognitoMode(); }
        });
        mMenuItemBookmark = createTile(context, AcidIcons.ICON_BOOKMARK, "书签收藏", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenBookmarks(); }
        });
        mMenuItemHistory = createTile(context, AcidIcons.ICON_HISTORY, "历史浏览", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenHistory(); }
        });
        addFourColumns(context, row1, mMenuItemNight, mMenuItemIncognito, mMenuItemBookmark, mMenuItemHistory);
        mPage1.addView(row1);

        LinearLayout row2 = createGridRow(context);
        mMenuItemDesktop = createTile(context, AcidIcons.ICON_MOBILE, "手机模式", false, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onToggleDesktopMode(); }
        });
        mMenuItemDownload = createTile(context, AcidIcons.ICON_DOWNLOAD, "下载管理", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenDownloads(); }
        });
        mMenuItemAgent = createTile(context, AcidIcons.ICON_AGENT, "Acid Agent", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenAgentFloatingWindow(); }
        });
        mMenuItemSettings = createTile(context, AcidIcons.ICON_SETTINGS, "设置", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenSettings(); }
        });
        addFourColumns(context, row2, mMenuItemDesktop, mMenuItemDownload, mMenuItemAgent, mMenuItemSettings);
        mPage1.addView(row2);

        // --- PAGE 2 ---
        mPage2 = new LinearLayout(context);
        mPage2.setOrientation(LinearLayout.VERTICAL);

        LinearLayout p2Row1 = createGridRow(context);
        mMenuItemModelConfig = createTile(context, AcidIcons.ICON_MODEL, "模型配置", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenModelProviderConfig(); }
        });
        mMenuItemAdBlock = createTile(context, AcidIcons.ICON_ADBLOCK, "广告拦截", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenAdBlock(); }
        });
        mMenuItemScripts = createTile(context, AcidIcons.ICON_FILE, "脚本管理", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenUserscripts(); }
        });
        mMenuItemMcp = createTile(context, AcidIcons.ICON_TERMINAL, "MCP 服务", false, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onToggleMcpServer(); }
        });
        addFourColumns(context, p2Row1, mMenuItemModelConfig, mMenuItemAdBlock, mMenuItemScripts, mMenuItemMcp);
        mPage2.addView(p2Row1);

        LinearLayout p2Row2 = createGridRow(context);
        mMenuItemAbout = createTile(context, AcidIcons.ICON_INFO, "关于", true, new Runnable() {
            @Override public void run() { if (mListener != null) mListener.onOpenAbout(); }
        });
        addFourColumns(
            context, p2Row2,
            mMenuItemAbout,
            new android.widget.Space(context),
            new android.widget.Space(context),
            new android.widget.Space(context)
        );
        mPage2.addView(p2Row2);

        pagesContainer.addView(mPage1);
        pagesContainer.addView(mPage2);

        mHorizontalPager.addView(pagesContainer, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        host.addView(mHorizontalPager, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Page Indicator Dots
        LinearLayout indicatorBar = new LinearLayout(context);
        indicatorBar.setOrientation(LinearLayout.HORIZONTAL);
        indicatorBar.setGravity(Gravity.CENTER);
        indicatorBar.setPadding(0, AcidTheme.dp(context, 8), 0, AcidTheme.dp(context, 2));

        int dotSize = AcidTheme.dp(context, 7);
        mDot1 = new View(context);
        LinearLayout.LayoutParams dLp1 = new LinearLayout.LayoutParams(dotSize, dotSize);
        dLp1.rightMargin = AcidTheme.dp(context, 6);
        mDot1.setLayoutParams(dLp1);
        mDot1.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { switchPage(0); }
        });
        indicatorBar.addView(mDot1);

        mDot2 = new View(context);
        mDot2.setLayoutParams(new LinearLayout.LayoutParams(dotSize, dotSize));
        mDot2.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { switchPage(1); }
        });
        indicatorBar.addView(mDot2);
        host.addView(indicatorBar);

        mHorizontalPager.setOnPageChangeListener(new PagingHorizontalScrollView.OnPageChangeListener() {
            @Override
            public void onPageChanged(int page) {
                updateIndicator(page);
            }
        });

        updateIndicator(0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int totalW = MeasureSpec.getSize(widthMeasureSpec);
        int contentW = totalW - AcidTheme.dp(getContext(), 16) * 2;
        if (contentW > 0) {
            mHorizontalPager.setPageWidth(contentW);
            setWidthIfChanged(mPage1, contentW);
            setWidthIfChanged(mPage2, contentW);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private static void setWidthIfChanged(View page, int width) {
        ViewGroup.LayoutParams lp = page.getLayoutParams();
        if (lp != null && lp.width != width) {
            lp.width = width;
            page.setLayoutParams(lp);
        }
    }

    private static LinearLayout createGridRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, AcidTheme.dp(ctx, 4), 0, AcidTheme.dp(ctx, 4));
        return row;
    }

    private static void addFourColumns(Context ctx, LinearLayout row, View c1, View c2, View c3, View c4) {
        View[] cols = new View[]{c1, c2, c3, c4};
        int gap = AcidTheme.dp(ctx, 3);
        for (int i = 0; i < cols.length; i++) {
            int colH = (cols[i] instanceof MenuCardItem) ? ViewGroup.LayoutParams.WRAP_CONTENT : 1;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, colH, 1f);
            if (i > 0) lp.leftMargin = gap;
            if (i < cols.length - 1) lp.rightMargin = gap;
            row.addView(cols[i], lp);
        }
    }

    private MenuCardItem createTile(
        Context ctx,
        int iconId,
        String title,
        final boolean dismissOnTap,
        final Runnable action
    ) {
        return new MenuCardItem(
            ctx, iconId, title,
            AcidTheme.icon(false), AcidTheme.text(false), false,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dismissOnTap) {
                        dismiss();
                    }
                    if (action != null) {
                        action.run();
                    }
                }
            }
        );
    }

    public void switchPage(int page) {
        mCurrentPage = page;
        mHorizontalPager.goToPage(page);
        updateIndicator(page);
    }

    private void updateIndicator(int page) {
        mCurrentPage = page;
        Context ctx = getContext();
        if (mDot1 == null || mDot2 == null) return;
        int activeDot = AcidTheme.accent(mIsDarkMode);
        int inactiveDot = AcidTheme.handle(mIsDarkMode);
        mDot1.setBackground(AcidTheme.pillBg(ctx, page == 0 ? activeDot : inactiveDot, 0, 3.5f));
        mDot2.setBackground(AcidTheme.pillBg(ctx, page == 1 ? activeDot : inactiveDot, 0, 3.5f));
    }

    @Override
    public void show() {
        switchPage(0);
        super.show();
    }

    public void toggle() {
        if (isShowing()) {
            dismiss();
        } else {
            show();
        }
    }

    public void updateCards(
        boolean isDarkMode,
        boolean isIncognito,
        boolean isDesktop,
        int providerCount,
        int modelCount
    ) {
        mIsDarkMode = isDarkMode;
        mIsIncognito = isIncognito;
        mIsDesktop = isDesktop;
        refreshTileVisuals();
    }

    public void updateMcpState(boolean isRunning) {
        mIsMcpRunning = isRunning;
        if (mMenuItemMcp != null) {
            mMenuItemMcp.update(
                AcidIcons.ICON_TERMINAL,
                mIsMcpRunning ? "MCP 运行中" : "MCP 已关闭",
                mIsMcpRunning,
                mIsDarkMode
            );
        }
    }

    private void refreshTileVisuals() {
        if (mMenuItemNight == null) return;
        mMenuItemNight.update(
            mIsDarkMode ? AcidIcons.ICON_MOON : AcidIcons.ICON_SUN,
            mIsDarkMode ? "日间模式" : "夜间模式",
            mIsDarkMode,
            mIsDarkMode
        );
        mMenuItemIncognito.update(
            mIsIncognito ? AcidIcons.ICON_INCOGNITO : AcidIcons.ICON_INCOGNITO_OFF,
            "无痕模式",
            mIsIncognito,
            mIsDarkMode
        );
        mMenuItemDesktop.update(
            mIsDesktop ? AcidIcons.ICON_DESKTOP : AcidIcons.ICON_MOBILE,
            mIsDesktop ? "电脑模式" : "手机模式",
            mIsDesktop,
            mIsDarkMode
        );

        mMenuItemBookmark.update(AcidIcons.ICON_BOOKMARK, "书签收藏", false, mIsDarkMode);
        mMenuItemHistory.update(AcidIcons.ICON_HISTORY, "历史浏览", false, mIsDarkMode);
        mMenuItemDownload.update(AcidIcons.ICON_DOWNLOAD, "下载管理", false, mIsDarkMode);
        mMenuItemAgent.update(AcidIcons.ICON_AGENT, "Acid Agent", false, mIsDarkMode);
        mMenuItemSettings.update(AcidIcons.ICON_SETTINGS, "设置", false, mIsDarkMode);
        mMenuItemModelConfig.update(AcidIcons.ICON_MODEL, "模型配置", false, mIsDarkMode);
        mMenuItemScripts.update(AcidIcons.ICON_FILE, "脚本管理", false, mIsDarkMode);
        mMenuItemAbout.update(AcidIcons.ICON_INFO, "关于", false, mIsDarkMode);

        boolean adBlockEnabled = AdBlockManager.getInstance(getContext()).isEnabled();
        mMenuItemAdBlock.update(AcidIcons.ICON_ADBLOCK, "广告拦截", adBlockEnabled, mIsDarkMode);
        updateMcpState(mIsMcpRunning);
        updateIndicator(mCurrentPage);
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        refreshTileVisuals();
    }
}
