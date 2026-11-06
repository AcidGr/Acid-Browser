package com.acid.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
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

import java.util.List;

/**
 * 2-Column Grid Tab Overview overlay with snapshot thumbnails, active tab indicator, and quick actions.
 */
public class TabsOverviewView extends FrameLayout implements AcidOverlay {

    public interface OnTabsOverviewListener {
        void onOverviewShown();
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
        mTopBar.setBackgroundColor(AcidTheme.bar(false));
        int padH = AcidTheme.dp(context, 16);
        int padV = AcidTheme.dp(context, 8);
        mTopBar.setPadding(padH, padV, padH, padV);

        mTvTabsCountTitle = new TextView(context);
        mTvTabsCountTitle.setText("标签页 (1)");
        mTvTabsCountTitle.setTextColor(AcidTheme.LIGHT_TEXT);
        mTvTabsCountTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_SHEET_TITLE);
        mTvTabsCountTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mTopBar.addView(mTvTabsCountTitle, tLp);

        // Action: New Tab (+)
        LinearLayout newBox = AcidIcons.createToolbarIconBtn(context, AcidIcons.ICON_PLUS, AcidTheme.LIGHT_ACCENT, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.createNewTab(mController.getHomeUrl(), true);
                }
                dismiss();
            }
        });
        mBtnNewIcon = (AcidIcons.SvgIconView) newBox.getChildAt(0);
        mTopBar.addView(newBox);

        // Action: Clear others (Trash) with confirmation
        LinearLayout clearBox = AcidIcons.createToolbarIconBtn(context, AcidIcons.ICON_TRASH, AcidTheme.LIGHT_MUTED, new OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmCloseOtherTabs();
            }
        });
        mBtnClearIcon = (AcidIcons.SvgIconView) clearBox.getChildAt(0);
        mTopBar.addView(clearBox);

        // Action: Exit overview (X)
        LinearLayout closeBox = AcidIcons.createToolbarIconBtn(context, AcidIcons.ICON_CLOSE, AcidTheme.LIGHT_MUTED, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        mBtnCloseIcon = (AcidIcons.SvgIconView) closeBox.getChildAt(0);
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

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    public void toggle() {
        if (isShowing()) {
            dismiss();
        } else {
            show();
        }
    }

    public void setOnTabsOverviewListener(OnTabsOverviewListener listener) {
        mListener = listener;
    }

    private void confirmCloseOtherTabs() {
        if (mController == null || mController.getActiveTab() == null) return;
        int activeId = mController.getActiveTab().id;
        int closableCount = 0;
        int agentTabCount = 0;
        for (BrowserController.TabSession t : mController.getTabs()) {
            if (t.id != activeId) {
                if (mController.isTabDrivenByAgent(t.id)) {
                    agentTabCount++;
                } else {
                    closableCount++;
                }
            }
        }
        if (closableCount <= 0) {
            if (agentTabCount > 0) {
                AcidFeedback.info(getContext(), "其余标签页正在执行 AI 自动化任务，已受保护");
            } else {
                AcidFeedback.info(getContext(), "当前没有其他标签页可关闭");
            }
            return;
        }

        String message = (agentTabCount > 0)
            ? ("确定关闭除当前页及运行中的 AI 标签页以外的其余 " + closableCount + " 个标签页吗？")
            : ("确定关闭除当前页以外的其余 " + closableCount + " 个标签页吗？");

        final int finalAgentTabCount = agentTabCount;
        AcidDialogs.confirmDanger(
            getContext(), mIsDarkMode,
            "关闭其他标签页",
            message,
            "全部关闭",
            new Runnable() {
                @Override
                public void run() {
                    if (mController != null && mController.getActiveTab() != null) {
                        mController.closeOtherTabs(mController.getActiveTab().id);
                        renderTabsGrid();
                        String toast = (finalAgentTabCount > 0)
                            ? "已关闭其余闲置标签页（AI 标签页已保留）"
                            : "已关闭其余标签页";
                        AcidFeedback.info(getContext(), toast);
                    }
                }
            }
        );
    }

    @Override
    public void show() {
        if (mController != null) {
            mController.captureActiveTabSnapshot();
            mController.setOverviewShowing(true);
        }
        renderTabsGrid();
        bringToFront();
        setVisibility(View.VISIBLE);
        if (mListener != null) {
            mListener.onOverviewShown();
        }
    }

    @Override
    public void dismiss() {
        if (mController != null) {
            mController.setOverviewShowing(false);
        }
        setVisibility(View.GONE);
        if (mListener != null) {
            mListener.onOverviewClosed();
        }
    }

    @Override
    public boolean handleBackPressed() {
        if (!isShowing()) return false;
        dismiss();
        return true;
    }

    public void renderTabsGrid() {
        mTabsGridContainer.removeAllViews();
        if (mController == null) return;

        List<BrowserController.TabSession> tabs = mController.getTabs();
        BrowserController.TabSession activeTab = mController.getActiveTab();
        mTvTabsCountTitle.setText(AcidLocale.tr("标签页 (" + tabs.size() + ")"));

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

    private static class AgentCardBorderDrawable extends Drawable implements Runnable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mBounds = new RectF();
        private final float mCornerRadius;
        private final float mStrokeWidth;
        private static final long FRAME_INTERVAL_MS = 50L; // 20 FPS breathing pulse prevents 120Hz CPU/GPU overheating loop

        public AgentCardBorderDrawable(float cornerRadius, float strokeWidth) {
            mCornerRadius = cornerRadius;
            mStrokeWidth = strokeWidth;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(strokeWidth);
        }

        @Override
        public void draw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            double phase = (now % 1500) / 1500.0 * Math.PI * 2.0;
            float factor = (float) (0.35 + 0.65 * (0.5 + 0.5 * Math.sin(phase)));
            int alpha = (int) (255 * factor);
            mPaint.setColor(Color.argb(alpha, 16, 185, 129)); // Acid emerald green #10B981

            Rect r = getBounds();
            float half = mStrokeWidth / 2f;
            mBounds.set(r.left + half, r.top + half, r.right - half, r.bottom - half);
            canvas.drawRoundRect(mBounds, mCornerRadius, mCornerRadius, mPaint);

            if (isVisible()) {
                unscheduleSelf(this);
                scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_INTERVAL_MS);
            }
        }

        @Override
        public void run() {
            if (isVisible()) {
                invalidateSelf();
            }
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            if (!visible) {
                unscheduleSelf(this);
            } else if (changed || restart) {
                scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_INTERVAL_MS);
            }
            return changed;
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private View buildSingleTabCard(final BrowserController.TabSession tab, boolean isActive) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);

        final boolean isAgentDriving = (mController != null && mController.isTabDrivenByAgent(tab.id));

        int cardBg = AcidTheme.bar(mIsDarkMode);
        int borderCol = isActive ? AcidTheme.COLOR_TAB_ACTIVE_BORDER : AcidTheme.border(mIsDarkMode);
        card.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, 0, 0, AcidTheme.RADIUS_CARD));
        card.setClipToOutline(true);

        if (isAgentDriving) {
            card.setForeground(new AgentCardBorderDrawable(AcidTheme.dp(ctx, AcidTheme.RADIUS_CARD), AcidTheme.dp(ctx, 3)));
        } else {
            card.setForeground(AcidTheme.cardBorderBg(ctx, Color.TRANSPARENT, borderCol, isActive ? 3 : 1, AcidTheme.RADIUS_CARD));
        }
        card.setClickable(true);
        card.setFocusable(true);

        // Header: Globe + Title + [AI badge] + 'X' close
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(AcidTheme.dp(ctx, 10), 0, AcidTheme.dp(ctx, 2), 0);

        GradientDrawable headGd = new GradientDrawable();
        headGd.setColor(AcidTheme.overviewHeaderBg(mIsDarkMode));
        float r = AcidTheme.dp(ctx, AcidTheme.RADIUS_CARD);
        headGd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        head.setBackground(headGd);

        int globeColor = isAgentDriving ? AcidTheme.DARK_ACCENT : (isActive ? AcidTheme.COLOR_TAB_ACTIVE_BORDER : AcidTheme.muted(mIsDarkMode));
        AcidIcons.SvgIconView globe = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_GLOBE, globeColor, 14);
        head.addView(globe);

        TextView title = new TextView(ctx);
        title.setText(tab.title);
        title.setTextColor(AcidTheme.text(mIsDarkMode));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 5);
        tLp.rightMargin = AcidTheme.dp(ctx, 4);
        head.addView(title, tLp);

        if (isAgentDriving) {
            head.addView(AcidWidgets.statusTag(ctx, mIsDarkMode, "● AI操作中", AcidTheme.DARK_ACCENT));
        }

        LinearLayout closeBtn = new LinearLayout(ctx);
        closeBtn.setGravity(Gravity.CENTER);
        int closeColor = isAgentDriving ? AcidTheme.iconDisabled(mIsDarkMode) : AcidTheme.muted(mIsDarkMode);
        AcidIcons.SvgIconView closeIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CLOSE, closeColor, 14);
        closeBtn.addView(closeIcon);
        if (isAgentDriving) {
            closeBtn.setAlpha(0.35f);
        }
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController == null) return;
                if (isAgentDriving) {
                    AcidFeedback.warn(getContext(), "该标签页正在被 Agent 使用，无法关闭。");
                    return;
                }
                boolean wasLastTab = mController.getTabs().size() <= 1;
                mController.closeTab(tab.id);
                if (wasLastTab) {
                    dismiss();
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
            empty.setBackgroundColor(mIsDarkMode ? AcidTheme.DARK_BG : AcidTheme.LIGHT_MENU_ITEM_BG);

            AcidIcons.SvgIconView emptyIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_GLOBE, AcidTheme.handle(mIsDarkMode), 28);
            empty.addView(emptyIcon);

            TextView tip = new TextView(ctx);
            tip.setText(AcidLocale.tr("空白页面"));
            tip.setTextColor(AcidTheme.muted(mIsDarkMode));
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
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
                dismiss();
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
