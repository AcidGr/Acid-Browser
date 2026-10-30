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
 * <p>Capabilities:
 * <ul>
 *   <li>Unified scrim, {@code H1 (16sp Bold)} header bar, optional action badge,
 *       and 34x34dp close button with press feedback (no fake drag handle on non-draggable sheets).</li>
 *   <li><b>Adaptive height with max-height ceiling</b>: wraps short content naturally while
 *       capping tall content at 78% of screen height (eliminating the rigid 430dp blank gap).</li>
 *   <li><b>Pinned sub-header slot</b> ({@link #getPinnedHeaderContainer()}): holds search bars or
 *       segmented tabs above the scroll area so filtering never destroys input focus.</li>
 *   <li><b>Built-in Tier-2 sub-page navigation stack</b> ({@link #setRootPage}, {@link #pushPage},
 *       {@link #popPage}): automatically renders {@code [← Back]} in the top-left header and
 *       handles system Back presses without ad-hoc state flags.</li>
 *   <li><b>Live theme rebuild hook</b> ({@link #onThemeChanged(boolean)}): subclasses automatically
 *       re-render dynamic content when day/night mode flips.</li>
 * </ul>
 */
public class AcidBottomSheetView extends FrameLayout implements AcidOverlay {

    public interface OnDismissListener {
        void onDismiss();
    }

    public interface LifecycleObserver {
        void onSheetShown(AcidBottomSheetView sheet);
        void onSheetDismissed(AcidBottomSheetView sheet);
    }

    public interface PageRenderer {
        void render(LinearLayout pinnedHeader, LinearLayout contentContainer);
    }

    public static final class SheetPage {
        public final String title;
        public final int actionIconId;
        public final String actionText;
        public final int actionColor;
        public final OnClickListener actionListener;
        public final PageRenderer renderer;
        public int savedScrollY = 0;

        public SheetPage(String title, PageRenderer renderer) {
            this(title, 0, null, 0, null, renderer);
        }

        public SheetPage(
            String title,
            int actionIconId,
            String actionText,
            int actionColor,
            OnClickListener actionListener,
            PageRenderer renderer
        ) {
            this.title = title != null ? title : "";
            this.actionIconId = actionIconId;
            this.actionText = actionText;
            this.actionColor = actionColor;
            this.actionListener = actionListener;
            this.renderer = renderer;
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

    private final List<SheetPage> mPageStack = new ArrayList<SheetPage>();
    private final List<LifecycleObserver> mObservers = new ArrayList<LifecycleObserver>();
    private OnDismissListener mDismissListener;

    protected boolean mIsDarkMode = false;
    private float mMaxHeightRatio = 0.75f;

    public AcidBottomSheetView(Context context) {
        super(context);
        setVisibility(View.GONE);

        // 1. Semi-transparent Scrim
        mScrim = new View(context);
        mScrim.setBackgroundColor(AcidTheme.SCRIM);
        mScrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AcidTheme.hideKeyboard(AcidBottomSheetView.this);
                dismiss();
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
        int padTop = AcidTheme.dp(context, 13);
        int padBottom = AcidTheme.dp(context, 16);
        mCardLayout.setPadding(padH, padTop, padH, padBottom);

        // Header Row: [NavTitleBox: ← Title] ... [Action Pill] [Close X]
        mHeaderRow = new LinearLayout(context);
        mHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        mHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
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
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H1_SHEET);
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
        int closeSize = AcidTheme.dp(context, 34);
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
                AcidTheme.hideKeyboard(AcidBottomSheetView.this);
                dismiss();
            }
        });
        mHeaderRow.addView(mCloseBox);
        mCardLayout.addView(mHeaderRow);

        // Subtle Header Divider
        mHeaderDivider = new View(context);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AcidTheme.dp(context, 1))
        );
        divLp.topMargin = AcidTheme.dp(context, 8);
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
        updateScrollCeiling();

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

        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        );
        addView(mCardLayout, cLp);

        applyTheme(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h != oldh) {
            updateScrollCeiling();
        }
    }

    private void updateScrollCeiling() {
        int hostH = getHeight();
        if (hostH <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            hostH = dm.heightPixels;
        }
        int chromeReserve = AcidTheme.dp(getContext(), 92);
        if (mPinnedHeaderContainer != null && mPinnedHeaderContainer.getVisibility() == View.VISIBLE) {
            int measuredPinned = mPinnedHeaderContainer.getMeasuredHeight();
            chromeReserve += measuredPinned > 0 ? measuredPinned : AcidTheme.dp(getContext(), 56);
        }
        int maxTotal = Math.round(hostH * mMaxHeightRatio);
        int maxScroll = Math.max(AcidTheme.dp(getContext(), 180), maxTotal - chromeReserve);
        mScrollView.setMaxHeightPx(maxScroll);
    }

    public void setMaxHeightRatio(float ratio) {
        mMaxHeightRatio = Math.max(0.4f, Math.min(0.92f, ratio));
        updateScrollCeiling();
    }

    /**
     * Stabilizes height for sheets with live search filters so typing never shrinks/jumps the sheet.
     */
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

    public void setTitle(String title) {
        mTitleView.setText(title != null ? title : "");
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

    public void setAction(int iconId, String text, int color, OnClickListener listener) {
        mBtnAction.setVisibility(View.VISIBLE);
        mBtnAction.update(iconId, text, color);
        mBtnAction.setOnClickListener(listener);
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
        mPageStack.clear();
        if (rootPage != null) {
            mPageStack.add(rootPage);
            renderActivePage(false);
        }
    }

    public void pushPage(SheetPage subPage) {
        if (subPage == null) return;
        if (!mPageStack.isEmpty()) {
            mPageStack.get(mPageStack.size() - 1).savedScrollY = mScrollView.getScrollY();
        }
        AcidTheme.hideKeyboard(this);
        mPageStack.add(subPage);
        renderActivePage(false);
    }

    public boolean popPage() {
        if (mPageStack.size() <= 1) return false;
        AcidTheme.hideKeyboard(this);
        mPageStack.remove(mPageStack.size() - 1);
        renderActivePage(true);
        return true;
    }

    public int getPageDepth() {
        return mPageStack.size();
    }

    public void refreshActivePage() {
        if (!mPageStack.isEmpty()) {
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
        }
    }

    private void renderActivePage(final boolean restoreScroll) {
        if (mPageStack.isEmpty()) return;
        final SheetPage top = mPageStack.get(mPageStack.size() - 1);
        setTitle(top.title);

        if (mPageStack.size() > 1) {
            setHeaderNavigation(true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    popPage();
                }
            });
        } else {
            setHeaderNavigation(false, null);
        }

        if (top.actionText != null && !top.actionText.isEmpty()) {
            int col = top.actionColor != 0 ? top.actionColor : AcidTheme.accent(mIsDarkMode);
            setAction(top.actionIconId, top.actionText, col, top.actionListener);
        } else {
            hideAction();
        }

        clearPinnedHeader();
        mContentContainer.removeAllViews();
        if (top.renderer != null) {
            top.renderer.render(mPinnedHeaderContainer, mContentContainer);
            if (mPinnedHeaderContainer.getChildCount() > 0) {
                mPinnedHeaderContainer.setVisibility(View.VISIBLE);
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
        updateScrollCeiling();
        bringToFront();
        setVisibility(View.VISIBLE);
        for (LifecycleObserver obs : mObservers) {
            obs.onSheetShown(this);
        }
    }

    @Override
    public void dismiss() {
        if (getVisibility() == View.VISIBLE) {
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

    @Override
    public boolean handleBackPressed() {
        if (!isShowing()) return false;
        if (popPage()) {
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

        if (!mPageStack.isEmpty() && (isShowing() || changed)) {
            refreshActivePage();
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
