package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard Tier-1 / Tier-2 BottomSheet Modal Scaffold for Acid Browser.
 *
 * <p>Template Anatomy:
 * <ol>
 *   <li><b>Header Bar</b>: {@code [← Title (16sp Bold)] ... [Action Pill?] [✕ (40x40dp)]}.
 *       {@code [←]} appears automatically whenever the internal sub-page stack depth &gt; 1
 *       OR when this sheet was pushed from a parent sheet via {@link OverlayStackManager#pushModal}.
 *       Tapping {@code [←]} or pressing system Back steps back one level; tapping {@code [✕]}
 *       or the scrim always closes the entire modal flow.</li>
 *   <li><b>Pinned Sub-Header Slot</b> ({@code pinnedHeader}): holds search bars, segmented tabs,
 *       or filter chips above the scroll view so live filtering never steals input focus.</li>
 *   <li><b>Adaptive Scrollable Body</b> ({@code contentContainer}): wraps short content naturally
 *       and clamps tall content at 75% of screen height.</li>
 *   <li><b>Pinned Bottom Footer Slot</b> ({@code pinnedFooter}): holds primary/secondary CTA buttons
 *       below the scroll area so form submit actions never scroll out of view.</li>
 * </ol>
 */
public class AcidBottomSheetView extends FrameLayout implements AcidOverlay {

    private static final float HEADER_CHROME_RESERVE_DP = 76f;
    private static final float FALLBACK_SLOT_RESERVE_DP = 52f;
    private static final float MIN_SCROLL_CEILING_DP = 180f;

    public interface OnDismissListener {
        void onDismiss();
    }

    public interface LifecycleObserver {
        void onSheetShown(AcidBottomSheetView sheet);
        void onSheetDismissed(AcidBottomSheetView sheet);
        void onSheetCloseAllRequested(AcidBottomSheetView sheet);
    }

    public interface PageRenderer {
        void render(LinearLayout pinnedHeader, LinearLayout contentContainer);
    }

    public interface FullPageRenderer {
        void render(LinearLayout pinnedHeader, LinearLayout contentContainer, LinearLayout pinnedFooter);
    }

    public static final class SheetPage {
        public final String title;
        public int actionIconId;
        public String actionText;
        public int actionColor;
        public OnClickListener actionListener;
        public final FullPageRenderer fullRenderer;
        public float minContentHeightDp = 0f;
        public Runnable onPauseListener = null;
        public int savedScrollY = 0;

        public SheetPage(String title, final PageRenderer renderer) {
            this(title, 0, null, 0, null, renderer);
        }

        public SheetPage(String title, FullPageRenderer fullRenderer) {
            this.title = title != null ? title : "";
            this.actionIconId = 0;
            this.actionText = null;
            this.actionColor = 0;
            this.actionListener = null;
            this.fullRenderer = fullRenderer;
        }

        public SheetPage(
            String title,
            int actionIconId,
            String actionText,
            int actionColor,
            OnClickListener actionListener,
            final PageRenderer renderer
        ) {
            this.title = title != null ? title : "";
            this.actionIconId = actionIconId;
            this.actionText = actionText;
            this.actionColor = actionColor;
            this.actionListener = actionListener;
            this.fullRenderer = renderer == null ? null : new FullPageRenderer() {
                @Override
                public void render(LinearLayout pinnedHeader, LinearLayout contentContainer, LinearLayout pinnedFooter) {
                    renderer.render(pinnedHeader, contentContainer);
                }
            };
        }

        public SheetPage withAction(int iconId, String text, OnClickListener listener) {
            return withAction(iconId, text, 0, listener);
        }

        public SheetPage withAction(int iconId, String text, int color, OnClickListener listener) {
            this.actionIconId = iconId;
            this.actionText = text;
            this.actionColor = color;
            this.actionListener = listener;
            return this;
        }

        public SheetPage withMinContentHeightDp(float minDp) {
            this.minContentHeightDp = minDp;
            return this;
        }

        public SheetPage withOnPause(Runnable listener) {
            this.onPauseListener = listener;
            return this;
        }
    }

    /**
     * ScrollView that wraps short content naturally and clamps tall content at {@code maxHeightPx}.
     */
    private static final class AdaptiveScrollView extends ScrollView {
        private int mMaxHeightPx = 0;
        private int mMinHeightPx = 0;

        AdaptiveScrollView(Context context) {
            super(context);
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        }

        void setMaxHeightPx(int maxPx) {
            if (mMaxHeightPx != maxPx) {
                mMaxHeightPx = maxPx;
                requestLayout();
            }
        }

        void setMinContentHeightPx(int minPx) {
            if (mMinHeightPx != minPx) {
                mMinHeightPx = minPx;
                setMinimumHeight(minPx);
                requestLayout();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (mMaxHeightPx > 0) {
                int specSize = MeasureSpec.getSize(heightMeasureSpec);
                int specMode = MeasureSpec.getMode(heightMeasureSpec);
                int capped = (specMode == MeasureSpec.UNSPECIFIED)
                    ? mMaxHeightPx
                    : Math.min(specSize, mMaxHeightPx);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (mMinHeightPx > 0 && getMeasuredHeight() < mMinHeightPx) {
                int target = mMaxHeightPx > 0 ? Math.min(mMinHeightPx, mMaxHeightPx) : mMinHeightPx;
                setMeasuredDimension(getMeasuredWidth(), target);
            }
        }
    }

    private final View mScrim;
    private final LinearLayout mCardLayout;
    private final LinearLayout mHeaderRow;
    private final LinearLayout mNavTitleBox;
    private final AcidIcons.SvgIconView mBtnBackIcon;
    private final TextView mTitleView;
    private final AcidIcons.BadgeButton mBtnAction;
    private final LinearLayout mCloseBox;
    private final AcidIcons.SvgIconView mBtnCloseIcon;
    private final View mHeaderDivider;
    private final LinearLayout mPinnedHeaderContainer;
    private final AdaptiveScrollView mScrollView;
    private final LinearLayout mContentContainer;
    private final LinearLayout mPinnedFooterContainer;

    private final List<SheetPage> mPageStack = new ArrayList<SheetPage>();
    private final List<LifecycleObserver> mObservers = new ArrayList<LifecycleObserver>();
    private OnDismissListener mDismissListener;
    private Runnable mParentBackHandler = null;

    protected boolean mIsDarkMode = false;
    private float mMaxHeightRatio = 0.75f;
    private boolean mNeedsRenderOnShow = false;

    public AcidBottomSheetView(Context context) {
        super(context);
        setVisibility(View.GONE);

        // 1. Semi-transparent Scrim (always closes entire modal flow)
        mScrim = new View(context);
        mScrim.setBackgroundColor(AcidTheme.SCRIM);
        mScrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                closeEntireModalFlow();
            }
        });
        addView(mScrim, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 2. Bottom Card Container
        mCardLayout = new LinearLayout(context);
        mCardLayout.setOrientation(LinearLayout.VERTICAL);
        mCardLayout.setClickable(true);
        mCardLayout.setFocusable(true);
        int padH = AcidTheme.dp(context, 16);
        int padTop = AcidTheme.dp(context, 12);
        int padBottom = AcidTheme.dp(context, 16);
        mCardLayout.setPadding(padH, padTop, padH, padBottom);

        // Header Row: [NavTitleBox: ← Title] ... [Action Pill] [Close X]
        mHeaderRow = new LinearLayout(context);
        mHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        mHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        mHeaderRow.setMinimumHeight(AcidTheme.dp(context, 40));
        mHeaderRow.setClickable(true);

        mNavTitleBox = new LinearLayout(context);
        mNavTitleBox.setOrientation(LinearLayout.HORIZONTAL);
        mNavTitleBox.setGravity(Gravity.CENTER_VERTICAL);
        int padNavH = AcidTheme.dp(context, 4);
        int padNavV = AcidTheme.dp(context, 6);
        mNavTitleBox.setPadding(padNavH, padNavV, AcidTheme.dp(context, 8), padNavV);

        mBtnBackIcon = new AcidIcons.SvgIconView(context, AcidIcons.ICON_BACK, AcidTheme.LIGHT_ICON, 18);
        mBtnBackIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        backLp.rightMargin = AcidTheme.dp(context, 8);
        mNavTitleBox.addView(mBtnBackIcon, backLp);

        mTitleView = new TextView(context);
        mTitleView.setText("");
        mTitleView.setTextColor(AcidTheme.LIGHT_TEXT);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_SHEET_TITLE);
        mTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        mTitleView.setSingleLine(true);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        mNavTitleBox.addView(mTitleView);

        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        mHeaderRow.addView(mNavTitleBox, navLp);

        mBtnAction = new AcidIcons.BadgeButton(context, AcidIcons.ICON_PLUS, "", AcidTheme.LIGHT_ACCENT, null);
        mBtnAction.setVisibility(View.GONE);
        mHeaderRow.addView(mBtnAction);

        mCloseBox = new LinearLayout(context);
        mCloseBox.setGravity(Gravity.CENTER);
        int closeSize = AcidTheme.dp(context, 40);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(closeSize, closeSize);
        closeLp.leftMargin = AcidTheme.dp(context, 6);
        mCloseBox.setLayoutParams(closeLp);
        mCloseBox.setClickable(true);
        mCloseBox.setFocusable(true);
        mBtnCloseIcon = new AcidIcons.SvgIconView(context, AcidIcons.ICON_CLOSE, AcidTheme.LIGHT_MUTED, 18);
        mCloseBox.addView(mBtnCloseIcon);
        mCloseBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                closeEntireModalFlow();
            }
        });
        mHeaderRow.addView(mCloseBox);
        mCardLayout.addView(mHeaderRow);

        // Subtle Header Divider
        mHeaderDivider = new View(context);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AcidTheme.dp(context, 1))
        );
        divLp.topMargin = AcidTheme.dp(context, 6);
        divLp.bottomMargin = AcidTheme.dp(context, 4);
        mCardLayout.addView(mHeaderDivider, divLp);

        // Pinned Header Container (above ScrollView, hidden when empty)
        mPinnedHeaderContainer = new LinearLayout(context);
        mPinnedHeaderContainer.setOrientation(LinearLayout.VERTICAL);
        mPinnedHeaderContainer.setVisibility(View.GONE);
        mPinnedHeaderContainer.setPadding(0, AcidTheme.dp(context, 6), 0, AcidTheme.dp(context, 4));
        mCardLayout.addView(mPinnedHeaderContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Adaptive ScrollView & Content Container
        mScrollView = new AdaptiveScrollView(context);
        mScrollView.setVerticalScrollBarEnabled(false);

        mContentContainer = new LinearLayout(context);
        mContentContainer.setOrientation(LinearLayout.VERTICAL);
        mContentContainer.setPadding(0, AcidTheme.dp(context, 8), 0, AcidTheme.dp(context, 4));
        mScrollView.addView(mContentContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        mCardLayout.addView(mScrollView, sLp);

        // Pinned Footer Container (below ScrollView, hidden when empty)
        mPinnedFooterContainer = new LinearLayout(context);
        mPinnedFooterContainer.setOrientation(LinearLayout.VERTICAL);
        mPinnedFooterContainer.setVisibility(View.GONE);
        mPinnedFooterContainer.setPadding(0, AcidTheme.dp(context, 10), 0, 0);
        mCardLayout.addView(mPinnedFooterContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        );
        addView(mCardLayout, cLp);

        updateScrollCeiling();
        applyTheme(false);
    }

    public boolean isDarkMode() {
        return mIsDarkMode;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h != oldh) {
            updateScrollCeiling();
        }
    }

    private int resolveSlotHeightPx(View slotView) {
        if (slotView == null || slotView.getVisibility() != View.VISIBLE) return 0;
        int measured = slotView.getMeasuredHeight();
        if (measured > 0) return measured;
        try {
            int w = mCardLayout.getWidth() - mCardLayout.getPaddingLeft() - mCardLayout.getPaddingRight();
            if (w <= 0) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                w = dm.widthPixels - AcidTheme.dp(getContext(), 32);
            }
            slotView.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, w), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
            int h = slotView.getMeasuredHeight();
            if (h > 0) return h;
        } catch (Exception ignored) {}
        return AcidTheme.dp(getContext(), FALLBACK_SLOT_RESERVE_DP);
    }

    private void updateScrollCeiling() {
        int hostH = getHeight();
        if (hostH <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            hostH = dm.heightPixels;
        }
        int chromeReserve = AcidTheme.dp(getContext(), HEADER_CHROME_RESERVE_DP);
        chromeReserve += resolveSlotHeightPx(mPinnedHeaderContainer);
        chromeReserve += resolveSlotHeightPx(mPinnedFooterContainer);
        int maxTotal = Math.round(hostH * mMaxHeightRatio);
        int maxScroll = Math.max(AcidTheme.dp(getContext(), MIN_SCROLL_CEILING_DP), maxTotal - chromeReserve);
        mScrollView.setMaxHeightPx(maxScroll);
    }

    public void setMaxHeightRatio(float ratio) {
        mMaxHeightRatio = Math.max(0.4f, Math.min(0.92f, ratio));
        updateScrollCeiling();
    }

    public void setMinContentHeightDp(float minDp) {
        mScrollView.setMinContentHeightPx(minDp > 0 ? AcidTheme.dp(getContext(), minDp) : 0);
    }

    public void setWrapContentHeight() {
        mScrollView.setMinContentHeightPx(0);
    }

    public void setOnDismissListener(OnDismissListener listener) {
        mDismissListener = listener;
    }

    public void addLifecycleObserver(LifecycleObserver observer) {
        if (observer != null && !mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    /**
     * Configured by {@link OverlayStackManager} when this sheet is pushed on top of a parent
     * modal so the header renders {@code [← Title]} and Back returns to the parent modal.
     */
    void setParentBackHandler(Runnable parentBackHandler) {
        mParentBackHandler = parentBackHandler;
        refreshHeaderNavigationState();
    }

    public void setTitle(String title) {
        mTitleView.setText(AcidLocale.tr(title != null ? title : ""));
    }

    public void setHeaderNavigation(boolean showBack, OnClickListener backListener) {
        if (showBack) {
            mBtnBackIcon.setVisibility(View.VISIBLE);
            mNavTitleBox.setClickable(true);
            mNavTitleBox.setFocusable(true);
            mNavTitleBox.setBackground(AcidTheme.interactiveBg(
                getContext(), Color.TRANSPARENT, AcidTheme.rowPressed(mIsDarkMode), 0, AcidTheme.RADIUS_CONTROL
            ));
            mNavTitleBox.setOnClickListener(backListener);
        } else {
            mBtnBackIcon.setVisibility(View.GONE);
            mNavTitleBox.setClickable(false);
            mNavTitleBox.setFocusable(false);
            mNavTitleBox.setBackground(null);
            mNavTitleBox.setOnClickListener(null);
        }
    }

    private void refreshHeaderNavigationState() {
        if (mPageStack.size() > 1) {
            setHeaderNavigation(true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    popPage();
                }
            });
        } else if (mParentBackHandler != null) {
            setHeaderNavigation(true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    AcidTheme.hideKeyboard(AcidBottomSheetView.this);
                    if (mParentBackHandler != null) {
                        mParentBackHandler.run();
                    }
                }
            });
        } else {
            setHeaderNavigation(false, null);
        }
    }

    public void setAction(int iconId, String text, int color, OnClickListener listener) {
        mBtnAction.setVisibility(View.VISIBLE);
        int resolvedColor = resolveDynamicAccentColor(color);
        mBtnAction.update(iconId, AcidLocale.tr(text), resolvedColor);
        mBtnAction.setOnClickListener(listener);
    }

    private int resolveDynamicAccentColor(int color) {
        if (color == 0 || color == AcidTheme.LIGHT_ACCENT || color == AcidTheme.DARK_ACCENT) {
            return AcidTheme.accent(mIsDarkMode);
        }
        return color;
    }

    public void hideAction() {
        mBtnAction.setVisibility(View.GONE);
        mBtnAction.setOnClickListener(null);
    }

    public LinearLayout getPinnedHeaderContainer() {
        mPinnedHeaderContainer.setVisibility(View.VISIBLE);
        return mPinnedHeaderContainer;
    }

    public void clearPinnedHeader() {
        mPinnedHeaderContainer.removeAllViews();
        mPinnedHeaderContainer.setVisibility(View.GONE);
    }

    public LinearLayout getPinnedFooterContainer() {
        mPinnedFooterContainer.setVisibility(View.VISIBLE);
        return mPinnedFooterContainer;
    }

    public void clearPinnedFooter() {
        mPinnedFooterContainer.removeAllViews();
        mPinnedFooterContainer.setVisibility(View.GONE);
    }

    public LinearLayout getContentContainer() {
        return mContentContainer;
    }

    public ScrollView getScrollView() {
        return mScrollView;
    }

    // =====================================================================
    // Built-in Tier-2 Sub-Page Stack API
    // =====================================================================

    public void setRootPage(SheetPage rootPage) {
        notifyTopPagePaused();
        mPageStack.clear();
        if (rootPage != null) {
            mPageStack.add(rootPage);
            if (isShowing()) {
                mNeedsRenderOnShow = false;
                renderActivePage(false);
            } else {
                mNeedsRenderOnShow = true;
            }
        }
    }

    /**
     * Hook invoked when opening this sheet as a fresh modal destination (via
     * {@link OverlayStackManager#openRootModal} or as the child of {@link OverlayStackManager#pushModal}),
     * ensuring it always starts at its L1 root page rather than a stale L2/L3 sub-page.
     */
    protected void onResetToRootPage() {
        // Subclasses override to reset their root SheetPage
    }

    public void pushPage(SheetPage subPage) {
        if (subPage == null) return;
        if (!mPageStack.isEmpty()) {
            SheetPage prev = mPageStack.get(mPageStack.size() - 1);
            prev.savedScrollY = mScrollView.getScrollY();
            if (prev.onPauseListener != null) {
                prev.onPauseListener.run();
            }
        }
        AcidTheme.hideKeyboard(this);
        mPageStack.add(subPage);
        renderActivePage(false);
    }

    public boolean popPage() {
        if (mPageStack.size() <= 1) return false;
        AcidTheme.hideKeyboard(this);
        SheetPage popped = mPageStack.remove(mPageStack.size() - 1);
        if (popped.onPauseListener != null) {
            popped.onPauseListener.run();
        }
        renderActivePage(true);
        return true;
    }

    public int getPageDepth() {
        return mPageStack.size();
    }

    private void notifyTopPagePaused() {
        if (!mPageStack.isEmpty()) {
            SheetPage top = mPageStack.get(mPageStack.size() - 1);
            if (top.onPauseListener != null) {
                top.onPauseListener.run();
            }
        }
    }

    public void refreshActivePage() {
        if (!mPageStack.isEmpty()) {
            notifyTopPagePaused();
            final int scrollY = mScrollView.getScrollY();
            renderActivePage(false);
            if (scrollY > 0) {
                mScrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        mScrollView.scrollTo(0, scrollY);
                    }
                });
            }
        } else {
            refreshHeaderNavigationState();
        }
    }

    private void renderActivePage(final boolean restoreScroll) {
        if (mPageStack.isEmpty()) return;
        final SheetPage top = mPageStack.get(mPageStack.size() - 1);
        setTitle(top.title);
        refreshHeaderNavigationState();

        if (top.actionText != null && !top.actionText.isEmpty()) {
            setAction(top.actionIconId, top.actionText, top.actionColor, top.actionListener);
        } else {
            hideAction();
        }

        if (top.minContentHeightDp > 0) {
            setMinContentHeightDp(top.minContentHeightDp);
        } else {
            setWrapContentHeight();
        }

        clearPinnedHeader();
        clearPinnedFooter();
        mContentContainer.removeAllViews();
        if (top.fullRenderer != null) {
            top.fullRenderer.render(mPinnedHeaderContainer, mContentContainer, mPinnedFooterContainer);
            if (mPinnedHeaderContainer.getChildCount() > 0) {
                mPinnedHeaderContainer.setVisibility(View.VISIBLE);
            }
            if (mPinnedFooterContainer.getChildCount() > 0) {
                mPinnedFooterContainer.setVisibility(View.VISIBLE);
            }
        }
        updateScrollCeiling();

        if (restoreScroll && top.savedScrollY > 0) {
            mScrollView.post(new Runnable() {
                @Override
                public void run() {
                    mScrollView.scrollTo(0, top.savedScrollY);
                }
            });
        } else {
            mScrollView.scrollTo(0, 0);
        }
    }

    // =====================================================================
    // AcidOverlay Lifecycle
    // =====================================================================

    @Override
    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    @Override
    public void show() {
        setVisibility(View.VISIBLE);
        if (mNeedsRenderOnShow && !mPageStack.isEmpty()) {
            mNeedsRenderOnShow = false;
            renderActivePage(false);
        } else {
            refreshHeaderNavigationState();
            updateScrollCeiling();
        }
        bringToFront();
        for (LifecycleObserver obs : mObservers) {
            obs.onSheetShown(this);
        }
    }

    @Override
    public void dismiss() {
        if (getVisibility() == View.VISIBLE) {
            notifyTopPagePaused();
            AcidTheme.hideKeyboard(this);
            setVisibility(View.GONE);
            if (mDismissListener != null) {
                mDismissListener.onDismiss();
            }
            for (LifecycleObserver obs : mObservers) {
                obs.onSheetDismissed(this);
            }
        }
    }

    /**
     * Invoked when the user taps {@code [✕]} or the background scrim: closes the entire
     * modal flow (including any suspended parent sheet) rather than popping one level.
     */
    public void closeEntireModalFlow() {
        AcidTheme.hideKeyboard(this);
        if (!mObservers.isEmpty()) {
            for (LifecycleObserver obs : mObservers) {
                obs.onSheetCloseAllRequested(this);
            }
        } else {
            dismiss();
        }
    }

    @Override
    public boolean handleBackPressed() {
        if (!isShowing()) return false;
        if (popPage()) {
            return true;
        }
        if (mParentBackHandler != null) {
            mParentBackHandler.run();
            return true;
        }
        dismiss();
        return true;
    }

    @Override
    public void applyTheme(boolean isDark) {
        boolean changed = (mIsDarkMode != isDark);
        mIsDarkMode = isDark;
        int text = AcidTheme.text(isDark);
        int muted = AcidTheme.muted(isDark);
        int iconCol = AcidTheme.icon(isDark);

        mCardLayout.setBackground(AcidTheme.topRoundedSheetBg(getContext(), isDark));
        mTitleView.setTextColor(text);
        mBtnCloseIcon.setColor(muted);
        mCloseBox.setBackground(AcidTheme.interactiveBg(
            getContext(), Color.TRANSPARENT, AcidTheme.rowPressed(isDark), 0, AcidTheme.RADIUS_CONTROL
        ));
        mBtnBackIcon.setColor(iconCol);
        if (mBtnBackIcon.getVisibility() == View.VISIBLE) {
            mNavTitleBox.setBackground(AcidTheme.interactiveBg(
                getContext(), Color.TRANSPARENT, AcidTheme.rowPressed(isDark), 0, AcidTheme.RADIUS_CONTROL
            ));
        }
        mHeaderDivider.setBackgroundColor(AcidTheme.border(isDark));

        if (!mPageStack.isEmpty()) {
            if (isShowing()) {
                refreshActivePage();
            } else if (changed) {
                mNeedsRenderOnShow = true;
            }
        }
        onThemeChanged(isDark);
    }

    /**
     * Hook for subclasses that do not use {@link SheetPage} to rebuild their content when
     * dark mode toggles while visible or cached.
     */
    protected void onThemeChanged(boolean isDark) {
        // Default no-op
    }
}
