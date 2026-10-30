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
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-Driven Bottom Pop-up Menu Panel ("功能") built on {@link AcidBottomSheetView}.
 *
 * <p>Driven by a declarative {@link List} of {@link TileSpec} descriptors:
 * <ul>
 *   <li>Automatically chunks tiles into {@code 4 x 2} pages and generates matching indicator dots.</li>
 *   <li>Adding or reordering a menu tile is a single {@link TileSpec} entry in the list.</li>
 *   <li>Subscribes to {@link StateSource.BoolState} bindings for reactive highlight updates.</li>
 * </ul>
 */
public class BrowserMenuSheet extends AcidBottomSheetView {

    public static final int COLS_PER_ROW = 4;
    public static final int ROWS_PER_PAGE = 2;
    public static final int TILES_PER_PAGE = COLS_PER_ROW * ROWS_PER_PAGE;

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
        void onOpenMcp();
        void onToggleMcpServer();
        void onOpenUserscripts();
        void onOpenAdBlock();
        void onOpenSettings();
    }

    /**
     * Declarative specification for one tile in the 4x2 menu grid.
     */
    public static final class TileSpec {
        public final int activeIconId;
        public final int inactiveIconId;
        public final String activeTitle;
        public final String inactiveTitle;
        public final boolean dismissOnTap;
        public final StateSource.BoolState stateSource;
        public final Runnable onTap;

        public TileSpec(
            int activeIconId,
            int inactiveIconId,
            String activeTitle,
            String inactiveTitle,
            boolean dismissOnTap,
            StateSource.BoolState stateSource,
            Runnable onTap
        ) {
            this.activeIconId = activeIconId;
            this.inactiveIconId = inactiveIconId;
            this.activeTitle = activeTitle;
            this.inactiveTitle = inactiveTitle;
            this.dismissOnTap = dismissOnTap;
            this.stateSource = stateSource;
            this.onTap = onTap;
        }

        public static TileSpec route(int iconId, String title, Runnable onOpen) {
            return new TileSpec(iconId, iconId, title, title, true, null, onOpen);
        }

        public static TileSpec routeWithStatus(
            int iconId,
            String title,
            StateSource.BoolState highlightState,
            Runnable onOpen
        ) {
            return new TileSpec(iconId, iconId, title, title, true, highlightState, onOpen);
        }

        public static TileSpec toggle(
            final StateSource.BoolState state,
            int activeIconId,
            int inactiveIconId,
            String activeTitle,
            String inactiveTitle
        ) {
            return new TileSpec(
                activeIconId,
                inactiveIconId,
                activeTitle,
                inactiveTitle,
                false,
                state,
                new Runnable() {
                    @Override
                    public void run() {
                        if (state != null) state.toggle();
                    }
                }
            );
        }
    }

    /**
     * Compact 2-line tool tile: {@code [Icon] + [Title]} with immediate press-state highlight.
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
            // Snap cleanly on page boundaries
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

            int totalPages = 1;
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

    private final List<TileSpec> mSpecs = new ArrayList<TileSpec>();
    private final List<MenuCardItem> mTileViews = new ArrayList<MenuCardItem>();
    private final List<LinearLayout> mPageContainers = new ArrayList<LinearLayout>();
    private final List<View> mDots = new ArrayList<View>();

    private final PagingHorizontalScrollView mHorizontalPager;
    private final LinearLayout mPagesRow;
    private final LinearLayout mIndicatorBar;
    private int mCurrentPage = 0;

    // Fallback states when constructed via legacy MenuActionListener
    private final StateSource.MutableBoolState mDarkState;
    private final StateSource.MutableBoolState mIncognitoState;
    private final StateSource.MutableBoolState mDesktopState;
    private final StateSource.MutableBoolState mAdBlockState;
    private final StateSource.MutableBoolState mMcpState;

    public BrowserMenuSheet(Context context, final MenuActionListener listener) {
        this(context, listener, null);
    }

    public BrowserMenuSheet(
        Context context,
        final MenuActionListener listener,
        BrowserDataStore dataStore
    ) {
        super(context);
        setTitle("功能");
        getScrollView().setVisibility(View.GONE);

        mDarkState = new StateSource.MutableBoolState(false);
        mIncognitoState = new StateSource.MutableBoolState(false);
        mDesktopState = new StateSource.MutableBoolState(false);
        mAdBlockState = new StateSource.MutableBoolState(
            dataStore != null ? dataStore.isAdBlockEnabled() : AdBlockManager.getInstance(context).isEnabled()
        );
        mMcpState = new StateSource.MutableBoolState(false);

        if (dataStore != null) {
            dataStore.observe(AcidSettings.ADBLOCK_ENABLED, new AcidSettings.KeyObserver<Boolean>() {
                @Override
                public void onSettingChanged(Boolean newValue) {
                    mAdBlockState.syncExternal(newValue != null && newValue.booleanValue());
                }
            });
        }

        LinearLayout host = getPinnedHeaderContainer();
        host.setPadding(0, AcidTheme.dp(context, 4), 0, 0);

        mHorizontalPager = new PagingHorizontalScrollView(context);
        mPagesRow = new LinearLayout(context);
        mPagesRow.setOrientation(LinearLayout.HORIZONTAL);
        mHorizontalPager.addView(mPagesRow, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        host.addView(mHorizontalPager, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mIndicatorBar = new LinearLayout(context);
        mIndicatorBar.setOrientation(LinearLayout.HORIZONTAL);
        mIndicatorBar.setGravity(Gravity.CENTER);
        mIndicatorBar.setPadding(0, AcidTheme.dp(context, 8), 0, AcidTheme.dp(context, 2));
        host.addView(mIndicatorBar);

        mHorizontalPager.setOnPageChangeListener(new PagingHorizontalScrollView.OnPageChangeListener() {
            @Override
            public void onPageChanged(int page) {
                updateIndicator(page);
            }
        });

        setTiles(buildDefaultTiles(listener));
    }

    private List<TileSpec> buildDefaultTiles(final MenuActionListener listener) {
        List<TileSpec> list = new ArrayList<TileSpec>();

        // Page 1 (8 items)
        list.add(new TileSpec(
            AcidIcons.ICON_MOON, AcidIcons.ICON_SUN, "日间模式", "夜间模式", false, mDarkState,
            new Runnable() {
                @Override public void run() { if (listener != null) listener.onToggleDarkMode(); }
            }
        ));
        list.add(new TileSpec(
            AcidIcons.ICON_INCOGNITO, AcidIcons.ICON_INCOGNITO_OFF, "无痕模式", "无痕模式", false, mIncognitoState,
            new Runnable() {
                @Override public void run() { if (listener != null) listener.onToggleIncognitoMode(); }
            }
        ));
        list.add(TileSpec.route(AcidIcons.ICON_BOOKMARK, "书签收藏", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenBookmarks(); }
        }));
        list.add(TileSpec.route(AcidIcons.ICON_HISTORY, "历史浏览", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenHistory(); }
        }));
        list.add(new TileSpec(
            AcidIcons.ICON_DESKTOP, AcidIcons.ICON_MOBILE, "电脑模式", "手机模式", false, mDesktopState,
            new Runnable() {
                @Override public void run() { if (listener != null) listener.onToggleDesktopMode(); }
            }
        ));
        list.add(TileSpec.route(AcidIcons.ICON_DOWNLOAD, "下载管理", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenDownloads(); }
        }));
        list.add(TileSpec.route(AcidIcons.ICON_AGENT, "Acid Agent", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenAgentFloatingWindow(); }
        }));
        list.add(TileSpec.route(AcidIcons.ICON_SETTINGS, "设置", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenSettings(); }
        }));

        // Page 2 (5 items)
        list.add(TileSpec.route(AcidIcons.ICON_MODEL, "模型配置", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenModelProviderConfig(); }
        }));
        list.add(TileSpec.routeWithStatus(AcidIcons.ICON_ADBLOCK, "广告拦截", mAdBlockState, new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenAdBlock(); }
        }));
        list.add(TileSpec.route(AcidIcons.ICON_FILE, "脚本管理", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenUserscripts(); }
        }));
        list.add(TileSpec.routeWithStatus(
            AcidIcons.ICON_TERMINAL, "MCP", mMcpState,
            new Runnable() {
                @Override public void run() { if (listener != null) listener.onOpenMcp(); }
            }
        ));
        list.add(TileSpec.route(AcidIcons.ICON_INFO, "关于", new Runnable() {
            @Override public void run() { if (listener != null) listener.onOpenAbout(); }
        }));

        return list;
    }

    /**
     * Replaces the menu grid with a declarative list of {@link TileSpec} descriptors and
     * automatically builds the required {@code 4x2} pages and indicator dots.
     */
    public void setTiles(List<TileSpec> specs) {
        mSpecs.clear();
        mTileViews.clear();
        mPageContainers.clear();
        mDots.clear();
        mPagesRow.removeAllViews();
        mIndicatorBar.removeAllViews();

        if (specs != null) {
            mSpecs.addAll(specs);
        }

        final Context ctx = getContext();
        int totalPages = Math.max(1, (mSpecs.size() + TILES_PER_PAGE - 1) / TILES_PER_PAGE);

        for (int p = 0; p < totalPages; p++) {
            LinearLayout pageLayout = new LinearLayout(ctx);
            pageLayout.setOrientation(LinearLayout.VERTICAL);

            for (int r = 0; r < ROWS_PER_PAGE; r++) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, AcidTheme.dp(ctx, 4), 0, AcidTheme.dp(ctx, 4));

                View[] cols = new View[COLS_PER_ROW];
                for (int c = 0; c < COLS_PER_ROW; c++) {
                    int idx = p * TILES_PER_PAGE + r * COLS_PER_ROW + c;
                    if (idx < mSpecs.size()) {
                        final TileSpec spec = mSpecs.get(idx);
                        final MenuCardItem tile = createTileView(ctx, spec);
                        mTileViews.add(tile);
                        cols[c] = tile;

                        if (spec.stateSource != null) {
                            spec.stateSource.observe(new StateSource.Observer() {
                                @Override
                                public void onChanged(boolean value) {
                                    refreshSingleTile(spec, tile);
                                }
                            });
                        }
                    } else {
                        cols[c] = new Space(ctx);
                    }
                }
                addFourColumns(ctx, row, cols);
                pageLayout.addView(row);
            }

            mPageContainers.add(pageLayout);
            mPagesRow.addView(pageLayout);
        }

        // Dynamic N-page indicator dots
        int dotSize = AcidTheme.dp(ctx, 7);
        for (int p = 0; p < totalPages; p++) {
            final int pageIdx = p;
            View dot = new View(ctx);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (p < totalPages - 1) {
                dLp.rightMargin = AcidTheme.dp(ctx, 6);
            }
            dot.setLayoutParams(dLp);
            dot.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchPage(pageIdx);
                }
            });
            mDots.add(dot);
            mIndicatorBar.addView(dot);
        }
        mIndicatorBar.setVisibility(totalPages > 1 ? View.VISIBLE : View.GONE);

        refreshTileVisuals();
        updateIndicator(0);
    }

    private MenuCardItem createTileView(Context ctx, final TileSpec spec) {
        return new MenuCardItem(
            ctx,
            spec.inactiveIconId,
            spec.inactiveTitle,
            AcidTheme.icon(mIsDarkMode),
            AcidTheme.text(mIsDarkMode),
            mIsDarkMode,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (spec.dismissOnTap) {
                        dismiss();
                    }
                    if (spec.onTap != null) {
                        spec.onTap.run();
                    }
                }
            }
        );
    }

    private static void addFourColumns(Context ctx, LinearLayout row, View[] cols) {
        int gap = AcidTheme.dp(ctx, 3);
        for (int i = 0; i < cols.length; i++) {
            int colH = (cols[i] instanceof MenuCardItem) ? ViewGroup.LayoutParams.WRAP_CONTENT : 1;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, colH, 1f);
            if (i > 0) lp.leftMargin = gap;
            if (i < cols.length - 1) lp.rightMargin = gap;
            row.addView(cols[i], lp);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int totalW = MeasureSpec.getSize(widthMeasureSpec);
        int contentW = totalW - AcidTheme.dp(getContext(), 16) * 2;
        if (contentW > 0) {
            mHorizontalPager.setPageWidth(contentW);
            for (int i = 0; i < mPageContainers.size(); i++) {
                setWidthIfChanged(mPageContainers.get(i), contentW);
            }
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
        for (int i = 0; i < mDots.size(); i++) {
            mDots.get(i).setBackground(AcidTheme.pillBg(
                ctx, i == page ? activeDot : inactiveDot, 0, AcidTheme.RADIUS_PILL
            ));
        }
    }

    @Override
    public void show() {
        refreshTileVisuals();
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
        updateCards(isDarkMode, isIncognito, isDesktop, mMcpState != null && mMcpState.get(), providerCount, modelCount);
    }

    public void updateCards(
        boolean isDarkMode,
        boolean isIncognito,
        boolean isDesktop,
        boolean isMcpRunning,
        int providerCount,
        int modelCount
    ) {
        mDarkState.syncExternal(isDarkMode);
        mIncognitoState.syncExternal(isIncognito);
        mDesktopState.syncExternal(isDesktop);
        mAdBlockState.syncExternal(AdBlockManager.getInstance(getContext()).isEnabled());
        mMcpState.syncExternal(isMcpRunning);
        refreshTileVisuals();
    }

    public void updateMcpState(boolean isRunning) {
        mMcpState.syncExternal(isRunning);
        refreshTileVisuals();
    }

    private void refreshSingleTile(TileSpec spec, MenuCardItem tile) {
        boolean active = spec.stateSource != null && spec.stateSource.get();
        tile.update(
            active ? spec.activeIconId : spec.inactiveIconId,
            active ? spec.activeTitle : spec.inactiveTitle,
            active,
            mIsDarkMode
        );
    }

    private void refreshTileVisuals() {
        if (mDarkState == null) return;
        mDarkState.syncExternal(mIsDarkMode);
        for (int i = 0; i < mSpecs.size() && i < mTileViews.size(); i++) {
            refreshSingleTile(mSpecs.get(i), mTileViews.get(i));
        }
        updateIndicator(mCurrentPage);
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        refreshTileVisuals();
    }
}
