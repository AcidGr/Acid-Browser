package com.acid.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Reusable BottomSheet Modal Overlay for Acid Browser.
 * Provides scrim, top drag handle, header with title & action button, and scrollable content area.
 */
public class AcidBottomSheetView extends FrameLayout implements AcidOverlay {

    public interface OnDismissListener {
        void onDismiss();
    }

    private final View mScrim;
    private final LinearLayout mCardLayout;
    private final View mHandle;
    private final TextView mTitleView;
    private final AcidIcons.BadgeButton mBtnAction;
    private final AcidIcons.SvgIconView mBtnCloseIcon;
    private final ScrollView mScrollView;
    private final LinearLayout mContentContainer;

    private OnDismissListener mDismissListener;
    private boolean mIsDarkMode = false;

    public AcidBottomSheetView(Context context) {
        super(context);
        setVisibility(View.GONE);

        // 1. Semi-transparent Scrim
        mScrim = new View(context);
        mScrim.setBackgroundColor(AcidTheme.SCRIM);
        mScrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        addView(mScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Bottom Card Layout
        mCardLayout = new LinearLayout(context);
        mCardLayout.setOrientation(LinearLayout.VERTICAL);
        int padH = AcidTheme.dp(context, 16);
        int padTop = AcidTheme.dp(context, 10);
        int padBottom = AcidTheme.dp(context, 16);
        mCardLayout.setPadding(padH, padTop, padH, padBottom);

        // Top Handle
        mHandle = new View(context);
        mHandle.setBackground(AcidTheme.pillBg(context, AcidTheme.handle(false), 0, 2));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(AcidTheme.dp(context, 36), AcidTheme.dp(context, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = AcidTheme.dp(context, 10);
        mCardLayout.addView(mHandle, hLp);

        // Header Row: [Title] ... [Action Button] [Close Button]
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        mTitleView = new TextView(context);
        mTitleView.setText("");
        mTitleView.setTextColor(AcidTheme.LIGHT_TEXT);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(mTitleView, titleLp);

        mBtnAction = new AcidIcons.BadgeButton(context, AcidIcons.ICON_PLUS, "", AcidTheme.LIGHT_ACCENT, null);
        mBtnAction.setVisibility(View.GONE);
        head.addView(mBtnAction);

        LinearLayout closeBox = new LinearLayout(context);
        closeBox.setGravity(Gravity.CENTER);
        int padClose = AcidTheme.dp(context, 6);
        closeBox.setPadding(padClose, padClose, padClose, padClose);
        mBtnCloseIcon = new AcidIcons.SvgIconView(context, AcidIcons.ICON_CLOSE, AcidTheme.LIGHT_MUTED, 18);
        closeBox.addView(mBtnCloseIcon);
        closeBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        head.addView(closeBox);
        mCardLayout.addView(head);

        // ScrollView & Content Container
        mScrollView = new ScrollView(context);
        mContentContainer = new LinearLayout(context);
        mContentContainer.setOrientation(LinearLayout.VERTICAL);
        mContentContainer.setPadding(0, AcidTheme.dp(context, 10), 0, AcidTheme.dp(context, 4));
        mScrollView.addView(mContentContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(context, 430)
        );
        mCardLayout.addView(mScrollView, sLp);

        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        );
        addView(mCardLayout, cLp);

        applyTheme(false);
    }

    public void setWrapContentHeight() {
        ViewGroup.LayoutParams lp = mScrollView.getLayoutParams();
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mScrollView.setLayoutParams(lp);
        }
    }

    public void setTitle(String title) {
        mTitleView.setText(title != null ? title : "");
    }

    public void setAction(int iconId, String text, int color, OnClickListener listener) {
        mBtnAction.setVisibility(View.VISIBLE);
        mBtnAction.update(iconId, text, color);
        mBtnAction.setOnClickListener(listener);
    }

    public void hideAction() {
        mBtnAction.setVisibility(View.GONE);
    }

    public LinearLayout getContentContainer() {
        return mContentContainer;
    }

    public ScrollView getScrollView() {
        return mScrollView;
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    @Override
    public void show() {
        // Raise above siblings so a sheet opened from another sheet is actually visible
        // and tappable (the download confirmation over the download list relies on this).
        bringToFront();
        setVisibility(View.VISIBLE);
    }

    @Override
    public void dismiss() {
        if (getVisibility() == View.VISIBLE) {
            setVisibility(View.GONE);
            if (mDismissListener != null) {
                mDismissListener.onDismiss();
            }
        }
    }

    @Override
    public boolean handleBackPressed() {
        if (!isShowing()) return false;
        dismiss();
        return true;
    }

    @Override
    public void applyTheme(boolean isDark) {
        mIsDarkMode = isDark;
        int bar = AcidTheme.bar(isDark);
        int text = AcidTheme.text(isDark);
        int muted = AcidTheme.muted(isDark);

        mCardLayout.setBackground(AcidTheme.topRoundedSheetBg(getContext(), bar, 16));
        mTitleView.setTextColor(text);
        mBtnCloseIcon.setColor(muted);
        mHandle.setBackground(AcidTheme.pillBg(
            getContext(),
            AcidTheme.handle(isDark),
            0,
            2
        ));
    }
}
