package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pure Native Tier-3 Modal Dialog for Acid Browser.
 *
 * <p>Replaces every legacy {@code android.app.AlertDialog} across the codebase so confirmations,
 * input prompts, single-choice pickers, and multi-choice checklists share the exact same
 * {@code 16dp} rounded card surface, {@code H1 (16sp Bold)} title scale, and Day/Night OLED
 * palette without system Holo/Material theme mismatches.
 */
public final class AcidDialog {

    public interface OnActionListener {
        /**
         * @return {@code true} to dismiss the dialog automatically, {@code false} to keep it open
         *         (e.g. when validation fails on an empty input field).
         */
        boolean onAction(Dialog dialog);
    }

    public interface OnSingleChoiceListener {
        void onItemSelected(Dialog dialog, int which);
    }

    public interface OnMultiChoiceConfirmListener {
        void onConfirmed(boolean[] checkedItems);
    }

    private static final class CappedScrollView extends ScrollView {
        private final int mMaxHeightPx;

        CappedScrollView(Context context, int maxHeightPx) {
            super(context);
            mMaxHeightPx = maxHeightPx;
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (mMaxHeightPx > 0) {
                int size = MeasureSpec.getSize(heightMeasureSpec);
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int capped = (mode == MeasureSpec.UNSPECIFIED) ? mMaxHeightPx : Math.min(size, mMaxHeightPx);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private final Context mContext;
    private final boolean mDark;
    private String mTitle = "";
    private String mMessage = null;
    private View mCustomView = null;

    private String[] mSingleItems = null;
    private int mSingleCheckedIndex = -1;
    private OnSingleChoiceListener mSingleListener = null;

    private String[] mMultiItems = null;
    private boolean[] mMultiChecked = null;

    private String mPositiveText = null;
    private boolean mPositiveDanger = false;
    private OnActionListener mPositiveListener = null;

    private String mNegativeText = null;
    private OnActionListener mNegativeListener = null;

    private String mNeutralText = null;
    private boolean mNeutralDanger = false;
    private OnActionListener mNeutralListener = null;

    private DialogInterface.OnCancelListener mCancelListener = null;
    private boolean mCancelable = true;

    public AcidDialog(Context context, boolean dark) {
        mContext = context;
        mDark = dark;
    }

    public AcidDialog setTitle(String title) {
        mTitle = title != null ? title : "";
        return this;
    }

    public AcidDialog setMessage(String message) {
        mMessage = message;
        return this;
    }

    public AcidDialog setCustomView(View view) {
        mCustomView = view;
        return this;
    }

    public AcidDialog setSingleChoiceItems(String[] items, int checkedIndex, OnSingleChoiceListener listener) {
        mSingleItems = items;
        mSingleCheckedIndex = checkedIndex;
        mSingleListener = listener;
        return this;
    }

    public AcidDialog setMultiChoiceItems(String[] items, boolean[] checked) {
        mMultiItems = items;
        mMultiChecked = checked;
        return this;
    }

    public AcidDialog setPositiveButton(String text, OnActionListener listener) {
        mPositiveText = text;
        mPositiveDanger = false;
        mPositiveListener = listener;
        return this;
    }

    public AcidDialog setDangerButton(String text, OnActionListener listener) {
        mPositiveText = text;
        mPositiveDanger = true;
        mPositiveListener = listener;
        return this;
    }

    public AcidDialog setNegativeButton(String text, OnActionListener listener) {
        mNegativeText = text;
        mNegativeListener = listener;
        return this;
    }

    public AcidDialog setNeutralButton(String text, boolean danger, OnActionListener listener) {
        mNeutralText = text;
        mNeutralDanger = danger;
        mNeutralListener = listener;
        return this;
    }

    public AcidDialog setCancelable(boolean cancelable) {
        mCancelable = cancelable;
        return this;
    }

    public AcidDialog setOnCancelListener(DialogInterface.OnCancelListener listener) {
        mCancelListener = listener;
        return this;
    }

    public Dialog show() {
        final Dialog dialog = new Dialog(mContext);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(mCancelable);
        dialog.setCanceledOnTouchOutside(mCancelable);
        if (mCancelListener != null) {
            dialog.setOnCancelListener(mCancelListener);
        }

        DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        int dialogWidth = Math.min(
            Math.round(dm.widthPixels * 0.88f),
            AcidTheme.dp(mContext, 360)
        );
        int maxBodyH = Math.round(dm.heightPixels * 0.56f);

        LinearLayout card = new LinearLayout(mContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(AcidTheme.cardBorderBg(
            mContext,
            AcidTheme.sheetBg(mDark),
            AcidTheme.sheetStroke(mDark),
            1f,
            AcidTheme.RADIUS_SHEET
        ));
        int padH = AcidTheme.dp(mContext, 18);
        int padTop = AcidTheme.dp(mContext, 16);
        int padBot = AcidTheme.dp(mContext, 16);
        card.setPadding(padH, padTop, padH, padBot);

        // 1. Header Title (H1 = 16sp Bold)
        if (!TextUtils.isEmpty(mTitle)) {
            TextView tvTitle = new TextView(mContext);
            tvTitle.setText(mTitle);
            tvTitle.setTextColor(AcidTheme.text(mDark));
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H1_SHEET);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            tvTitle.setMaxLines(2);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tLp.bottomMargin = AcidTheme.dp(mContext, 10);
            card.addView(tvTitle, tLp);
        }

        // 2. Scrollable Body
        CappedScrollView scroll = new CappedScrollView(mContext, maxBodyH);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout body = new LinearLayout(mContext);
        body.setOrientation(LinearLayout.VERTICAL);

        if (!TextUtils.isEmpty(mMessage)) {
            TextView tvMsg = new TextView(mContext);
            tvMsg.setText(mMessage);
            tvMsg.setTextColor(AcidTheme.text(mDark));
            tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
            tvMsg.setLineSpacing(AcidTheme.dp(mContext, 3f), 1f);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            mLp.bottomMargin = AcidTheme.dp(mContext, 8);
            body.addView(tvMsg, mLp);
        }

        if (mSingleItems != null && mSingleItems.length > 0) {
            body.addView(buildSingleChoiceList(dialog));
        }

        if (mMultiItems != null && mMultiItems.length > 0 && mMultiChecked != null) {
            body.addView(buildMultiChoiceList());
        }

        if (mCustomView != null) {
            body.addView(mCustomView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        scroll.addView(body, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // 3. Action Buttons Footer
        boolean hasButtons = !TextUtils.isEmpty(mPositiveText)
            || !TextUtils.isEmpty(mNegativeText)
            || !TextUtils.isEmpty(mNeutralText);

        if (hasButtons) {
            LinearLayout footer = new LinearLayout(mContext);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            fLp.topMargin = AcidTheme.dp(mContext, 14);
            card.addView(footer, fLp);

            if (!TextUtils.isEmpty(mNeutralText)) {
                int col = mNeutralDanger ? AcidTheme.COLOR_RED : AcidTheme.muted(mDark);
                TextView btnNeutral = AcidWidgets.secondaryButton(mContext, mDark, mNeutralText, col, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AcidTheme.hideKeyboard(v);
                        boolean close = (mNeutralListener == null) || mNeutralListener.onAction(dialog);
                        if (close) dialog.dismiss();
                    }
                });
                LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                nLp.rightMargin = AcidTheme.dp(mContext, 8);
                footer.addView(btnNeutral, nLp);
            }

            View spacer = new View(mContext);
            footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            if (!TextUtils.isEmpty(mNegativeText)) {
                TextView btnNeg = AcidWidgets.secondaryButton(
                    mContext, mDark, mNegativeText, AcidTheme.muted(mDark),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            AcidTheme.hideKeyboard(v);
                            boolean close = (mNegativeListener == null) || mNegativeListener.onAction(dialog);
                            if (close) dialog.dismiss();
                        }
                    }
                );
                LinearLayout.LayoutParams negLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                if (!TextUtils.isEmpty(mPositiveText)) {
                    negLp.rightMargin = AcidTheme.dp(mContext, 8);
                }
                footer.addView(btnNeg, negLp);
            }

            if (!TextUtils.isEmpty(mPositiveText)) {
                int fill = mPositiveDanger ? AcidTheme.COLOR_RED : AcidTheme.accent(mDark);
                TextView btnPos = AcidWidgets.primaryButton(
                    mContext, mDark, mPositiveText, fill,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            AcidTheme.hideKeyboard(v);
                            boolean close = (mPositiveListener == null) || mPositiveListener.onAction(dialog);
                            if (close) dialog.dismiss();
                        }
                    }
                );
                footer.addView(btnPos, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        }

        dialog.setContentView(card);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.55f);
            WindowManager.LayoutParams wlp = win.getAttributes();
            wlp.width = dialogWidth;
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            wlp.gravity = Gravity.CENTER;
            win.setAttributes(wlp);
        }
        dialog.show();
        return dialog;
    }

    private View buildSingleChoiceList(final Dialog dialog) {
        LinearLayout list = new LinearLayout(mContext);
        list.setOrientation(LinearLayout.VERTICAL);
        int accent = AcidTheme.accent(mDark);

        for (int i = 0; i < mSingleItems.length; i++) {
            final int idx = i;
            boolean selected = (i == mSingleCheckedIndex);

            LinearLayout row = new LinearLayout(mContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = AcidTheme.dp(mContext, 12);
            int padV = AcidTheme.dp(mContext, 11);
            row.setPadding(padH, padV, padH, padV);

            int fill = selected ? AcidTheme.softTint(accent, mDark ? 36 : 24) : AcidTheme.menuItemBg(mDark);
            int stroke = selected ? accent : AcidTheme.border(mDark);
            row.setBackground(AcidTheme.interactiveBg(
                mContext, fill, AcidTheme.rowPressed(mDark), stroke, AcidTheme.RADIUS_CONTROL
            ));

            TextView tv = new TextView(mContext);
            tv.setText(mSingleItems[i]);
            tv.setTextColor(AcidTheme.text(mDark));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
            tv.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));

            if (selected) {
                AcidIcons.SvgIconView check = new AcidIcons.SvgIconView(
                    mContext, AcidIcons.ICON_CHECK, accent, 17
                );
                row.addView(check);
            }

            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mSingleListener != null) {
                        mSingleListener.onItemSelected(dialog, idx);
                    } else {
                        dialog.dismiss();
                    }
                }
            });

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i < mSingleItems.length - 1) {
                rLp.bottomMargin = AcidTheme.dp(mContext, 6);
            }
            list.addView(row, rLp);
        }
        return list;
    }

    private View buildMultiChoiceList() {
        LinearLayout list = new LinearLayout(mContext);
        list.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < mMultiItems.length; i++) {
            final int idx = i;
            LinearLayout row = createCheckableRow(
                mContext,
                mDark,
                mMultiItems[i],
                idx < mMultiChecked.length && mMultiChecked[idx],
                new AcidWidgets.OnToggleListener() {
                    @Override
                    public void onToggle(boolean isChecked) {
                        if (idx < mMultiChecked.length) {
                            mMultiChecked[idx] = isChecked;
                        }
                    }
                }
            );
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i < mMultiItems.length - 1) {
                rLp.bottomMargin = AcidTheme.dp(mContext, 6);
            }
            list.addView(row, rLp);
        }
        return list;
    }

    /**
     * Reusable themed checkable row (used in multi-choice dialogs and delete-with-file prompts).
     */
    public static LinearLayout createCheckableRow(
        final Context ctx,
        final boolean dark,
        String label,
        boolean initialChecked,
        final AcidWidgets.OnToggleListener listener
    ) {
        final boolean[] state = new boolean[]{initialChecked};
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 12);
        int padV = AcidTheme.dp(ctx, 10);
        row.setPadding(padH, padV, padH, padV);

        final LinearLayout box = new LinearLayout(ctx);
        box.setGravity(Gravity.CENTER);
        int boxSize = AcidTheme.dp(ctx, 20);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(boxSize, boxSize);
        bLp.rightMargin = AcidTheme.dp(ctx, 10);
        box.setLayoutParams(bLp);

        final AcidIcons.SvgIconView checkIcon = new AcidIcons.SvgIconView(
            ctx, AcidIcons.ICON_CHECK, dark ? Color.BLACK : Color.WHITE, 13
        );
        box.addView(checkIcon);
        row.addView(box);

        final TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(AcidTheme.text(dark));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        row.addView(tv, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        final Runnable refreshVisual = new Runnable() {
            @Override
            public void run() {
                boolean checked = state[0];
                int accent = AcidTheme.accent(dark);
                if (checked) {
                    box.setBackground(AcidTheme.pillBg(ctx, accent, 0, 5));
                    checkIcon.setVisibility(View.VISIBLE);
                    row.setBackground(AcidTheme.interactiveBg(
                        ctx,
                        AcidTheme.softTint(accent, dark ? 30 : 20),
                        AcidTheme.rowPressed(dark),
                        accent,
                        AcidTheme.RADIUS_CONTROL
                    ));
                } else {
                    box.setBackground(AcidTheme.pillBg(
                        ctx, AcidTheme.formInputBg(dark), AcidTheme.muted(dark), 5
                    ));
                    checkIcon.setVisibility(View.INVISIBLE);
                    row.setBackground(AcidTheme.interactiveBg(
                        ctx,
                        AcidTheme.menuItemBg(dark),
                        AcidTheme.rowPressed(dark),
                        AcidTheme.border(dark),
                        AcidTheme.RADIUS_CONTROL
                    ));
                }
            }
        };
        refreshVisual.run();

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                refreshVisual.run();
                if (listener != null) {
                    listener.onToggle(state[0]);
                }
            }
        });
        return row;
    }
}
