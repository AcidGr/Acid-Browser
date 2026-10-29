package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 2-Column Grid Tab Overview overlay with snapshot thumbnails, active tab indicator, and quick actions.
 */
public class TabsOverviewView extends FrameLayout {

    public interface OnTabsOverviewListener {
        void onOverviewClosed();
    }

    private final BrowserController mController;
    private final TextView mTvTabsCountTitle;
    private final LinearLayout mTabsGridContainer;
    private final LinearLayout mTopBar;
    private final View mDivider;
    private final AcidIcons.SvgIconView mBtnNewIcon;
    private final AcidIcons.SvgIconView mBtnClearIcon;
    private final AcidIcons.SvgIconView mBtnCloseIcon;

    private OnTabsOverviewListener mListener;
    private boolean mIsDarkMode = false;

    public TabsOverviewView(Context context, BrowserController controller) {
        super(context);
        mController = controller;
        setVisibility(View.GONE);
        setBackgroundColor(AcidTheme.COLOR_OVERVIEW_BG);

        LinearLayout contentCol = new LinearLayout(context);
        contentCol.setOrientation(LinearLayout.VERTICAL);

        // 1. Top bar: "标签页 (N)" + [+] New + [Trash] Clear + [X] Close
        mTopBar = new LinearLayout(context);
        mTopBar.setOrientation(LinearLayout.HORIZONTAL);
        mTopBar.setGravity(Gravity.CENTER_VERTICAL);
        mTopBar.setBackgroundColor(Color.WHITE);
        int padH = AcidTheme.dp(context, 16);
        int padV = AcidTheme.dp(context, 8);
        mTopBar.setPadding(padH, padV, padH, padV);

        mTvTabsCountTitle = new TextView(context);
        mTvTabsCountTitle.setText("标签页 (1)");
        mTvTabsCountTitle.setTextColor(AcidTheme.LIGHT_TEXT);
        mTvTabsCountTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mTvTabsCountTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mTopBar.addView(mTvTabsCountTitle, tLp);

        // Action: New Tab (+)
        LinearLayout newBox = new LinearLayout(context);
        newBox.setGravity(Gravity.CENTER);
        newBox.setPadding(AcidTheme.dp(context, 7), AcidTheme.dp(context, 6), AcidTheme.dp(context, 7), AcidTheme.dp(context, 6));
        mBtnNewIcon = new AcidIcons.SvgIconView(context, AcidIcons.ICON_PLUS, AcidTheme.LIGHT_ACCENT, 18);
        newBox.addView(mBtnNewIcon);
        newBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.createNewTab(BrowserController.HOME_URL, true);
                }
                close();
            }
        });
        mTopBar.addView(newBox);

        // Action: Clear others (Trash)
        LinearLayout clearBox = new LinearLayout(context);
        clearBox.setGravity(Gravity.CENTER);
        clearBox.setPadding(AcidTheme.dp(context, 7), AcidTheme.dp(context, 6), AcidTheme.dp(context, 7), AcidTheme.dp(context, 6));
        mBtnClearIcon = new AcidIcons.SvgIconView(context, AcidIcons.ICON_TRASH, AcidTheme.LIGHT_MUTED, 18);
        clearBox.addView(mBtnClearIcon);
        clearBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null && mController.getActiveTab() != null) {
                    mController.closeOtherTabs(mController.getActiveTab().id);
                    renderTabsGrid();
                    Toast.makeText(getContext(), "已清除其他标签页", Toast.LENGTH_SHORT).show();
                }
            }
        });
        mTopBar.addView(clearBox);

        // Action: Exit overview (X)
        LinearLayout closeBox = new LinearLayout(context);
        closeBox.setGravity(Gravity.CENTER);
        closeBox.setPadding(AcidTheme.dp(context, 7), AcidTheme.dp(context, 6), AcidTheme.dp(context, 7), AcidTheme.dp(context, 6));
        mBtnCloseIcon = new AcidIcons.SvgIconView(context, AcidIcons.ICON_CLOSE, AcidTheme.LIGHT_MUTED, 18);
        closeBox.addView(mBtnCloseIcon);
        closeBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        mTopBar.addView(closeBox);

        contentCol.addView(mTopBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mDivider = new View(context);
        mDivider.setBackgroundColor(AcidTheme.LIGHT_BORDER);
        contentCol.addView(mDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(context, 1)));

        // 2. Scrollable 2-Column Grid
        ScrollView scroll = new ScrollView(context);
        mTabsGridContainer = new LinearLayout(context);
        mTabsGridContainer.setOrientation(LinearLayout.HORIZONTAL);
        int padG = AcidTheme.dp(context, 12);
        mTabsGridContainer.setPadding(padG, padG, padG, padG);
        scroll.addView(mTabsGridContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        contentCol.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(contentCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setListener(OnTabsOverviewListener listener) {
        mListener = listener;
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    public void toggle() {
        if (isShowing()) {
            close();
        } else {
            show();
        }
    }

    public void show() {
        if (mController != null) {
            mController.captureActiveTabSnapshot();
        }
        renderTabsGrid();
        setVisibility(View.VISIBLE);
    }

    public void close() {
        setVisibility(View.GONE);
        if (mListener != null) {
            mListener.onOverviewClosed();
        }
    }

    public void renderTabsGrid() {
        mTabsGridContainer.removeAllViews();
        if (mController == null) return;

        List<BrowserController.TabSession> tabs = mController.getTabs();
        BrowserController.TabSession activeTab = mController.getActiveTab();
        mTvTabsCountTitle.setText("标签页 (" + tabs.size() + ")");

        LinearLayout leftCol = new LinearLayout(getContext());
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(getContext());
        rightCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams colLpL = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLpL.rightMargin = AcidTheme.dp(getContext(), 6);
        mTabsGridContainer.addView(leftCol, colLpL);

        LinearLayout.LayoutParams colLpR = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLpR.leftMargin = AcidTheme.dp(getContext(), 6);
        mTabsGridContainer.addView(rightCol, colLpR);

        for (int i = 0; i < tabs.size(); i++) {
            final BrowserController.TabSession t = tabs.get(i);
            boolean isActive = (activeTab != null && activeTab.id == t.id);

            View card = buildSingleTabCard(t, isActive);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = AcidTheme.dp(getContext(), 12);

            if (i % 2 == 0) {
                leftCol.addView(card, cLp);
            } else {
                rightCol.addView(card, cLp);
            }
        }
    }

    private View buildSingleTabCard(final BrowserController.TabSession tab, boolean isActive) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);

        int cardBg = mIsDarkMode ? AcidTheme.DARK_BAR : Color.WHITE;
        int borderCol = isActive ? AcidTheme.COLOR_TAB_ACTIVE_BORDER : (mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER);
        card.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, 0, 0, 14));
        // Clip header / thumbnail to the rounded card, and draw the border as a foreground overlay so
        // child views can no longer paint over it (this is why the selected tab showed no frame before).
        card.setClipToOutline(true);
        card.setForeground(AcidTheme.cardBorderBg(ctx, Color.TRANSPARENT, borderCol, isActive ? 3 : 1, 14));
        card.setClickable(true);
        card.setFocusable(true);

        // Header: Globe + Title + 'X' close
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(AcidTheme.dp(ctx, 10), 0, AcidTheme.dp(ctx, 2), 0);

        GradientDrawable headGd = new GradientDrawable();
        headGd.setColor(mIsDarkMode ? Color.parseColor("#121212") : Color.parseColor("#F8FAFC"));
        float r = AcidTheme.dp(ctx, 14);
        headGd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        head.setBackground(headGd);

        int globeColor = isActive ? AcidTheme.COLOR_TAB_ACTIVE_BORDER : AcidTheme.muted(mIsDarkMode);
        AcidIcons.SvgIconView globe = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_GLOBE, globeColor, 14);
        head.addView(globe);

        TextView title = new TextView(ctx);
        title.setText(tab.title);
        title.setTextColor(AcidTheme.text(mIsDarkMode));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 5);
        tLp.rightMargin = AcidTheme.dp(ctx, 4);
        head.addView(title, tLp);

        LinearLayout closeBtn = new LinearLayout(ctx);
        closeBtn.setGravity(Gravity.CENTER);
        AcidIcons.SvgIconView closeIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CLOSE, AcidTheme.muted(mIsDarkMode), 14);
        closeBtn.addView(closeIcon);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController == null) return;
                boolean wasLastTab = mController.getTabs().size() <= 1;
                mController.closeTab(tab.id);
                if (wasLastTab) {
                    // Last tab closed: leave the overview; the controller already created a fresh Home tab.
                    close();
                } else {
                    renderTabsGrid();
                }
            }
        });
        // 40x40dp touch target (icon itself stays 14dp) to avoid mis-taps
        head.addView(closeBtn, new LinearLayout.LayoutParams(AcidTheme.dp(ctx, 40), AcidTheme.dp(ctx, 40)));
        card.addView(head);

        // Body: Live Web Snapshot Thumbnail
        FrameLayout body = new FrameLayout(ctx);
        body.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(ctx, 185)));

        if (tab.snapshot != null && !tab.snapshot.isRecycled()) {
            ImageView img = new ImageView(ctx);
            img.setImageBitmap(tab.snapshot);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            body.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout empty = new LinearLayout(ctx);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setBackgroundColor(mIsDarkMode ? AcidTheme.DARK_BG : Color.parseColor("#F8FAFC"));

            AcidIcons.SvgIconView emptyIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_GLOBE, Color.parseColor("#CBD5E1"), 28);
            empty.addView(emptyIcon);

            TextView tip = new TextView(ctx);
            tip.setText("空白页面");
            tip.setTextColor(Color.parseColor("#94A3B8"));
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tipLp.topMargin = AcidTheme.dp(ctx, 4);
            empty.addView(tip, tipLp);

            body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        card.addView(body);

        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.switchToTab(tab.id);
                }
                close();
            }
        });

        return card;
    }

    public void applyTheme(boolean isDark) {
        mIsDarkMode = isDark;
        setBackgroundColor(isDark ? AcidTheme.DARK_BG : AcidTheme.COLOR_OVERVIEW_BG);
        mTopBar.setBackgroundColor(AcidTheme.bar(isDark));
        mDivider.setBackgroundColor(AcidTheme.border(isDark));
        mTvTabsCountTitle.setTextColor(AcidTheme.text(isDark));
        mBtnNewIcon.setColor(AcidTheme.accent(isDark));
        mBtnClearIcon.setColor(AcidTheme.muted(isDark));
        mBtnCloseIcon.setColor(AcidTheme.muted(isDark));
        if (isShowing()) {
            renderTabsGrid();
        }
    }
}
