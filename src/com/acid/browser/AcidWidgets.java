package com.acid.browser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Standard UI Component Primitives & Template Builders for Acid Browser.
 *
 * <p>Provides declarative builders for section overlines, auto-divided group cards, content/hero
 * cards, settings rows (nav, value, danger, reactive {@link StateSource.BoolState} switch),
 * unified {@link ListRow} cards, status tags, text/icon action buttons, filter chip bars,
 * and {@link LoadingButton} controllers.
 */
public final class AcidWidgets {

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    public interface OnToggleListener {
        void onToggle(boolean isChecked);
    }

    private AcidWidgets() {}

    // =====================================================================
    // 1. Typography: Section Overlines, Field Labels & Descriptions
    // =====================================================================

    /**
     * Section Header ({@code 12.5sp Bold}, crisp icon-level contrast) placed outside above a card group.
     */
    public static TextView sectionHeader(Context ctx, boolean dark, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title != null ? title : "");
        tv.setTextColor(AcidTheme.icon(dark));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_OVERLINE);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 21) {
            tv.setLetterSpacing(0.02f);
        }
        tv.setPadding(
            AcidTheme.dp(ctx, 4),
            AcidTheme.dp(ctx, 12),
            AcidTheme.dp(ctx, 4),
            AcidTheme.dp(ctx, 6)
        );
        return tv;
    }

    /**
     * Section Header row with a right-aligned status badge/label.
     */
    public static LinearLayout sectionHeaderRow(
        Context ctx,
        boolean dark,
        String leftTitle,
        String rightBadge,
        int rightColor
    ) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            AcidTheme.dp(ctx, 4),
            AcidTheme.dp(ctx, 12),
            AcidTheme.dp(ctx, 4),
            AcidTheme.dp(ctx, 6)
        );

        TextView tvLeft = new TextView(ctx);
        tvLeft.setText(leftTitle != null ? leftTitle : "");
        tvLeft.setTextColor(AcidTheme.icon(dark));
        tvLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_OVERLINE);
        tvLeft.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 21) {
            tvLeft.setLetterSpacing(0.02f);
        }
        row.addView(tvLeft, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        if (!TextUtils.isEmpty(rightBadge)) {
            row.addView(statusTag(ctx, dark, rightBadge, rightColor != 0 ? rightColor : AcidTheme.muted(dark)));
        }
        return row;
    }

    /**
     * Helper description banner or section footnote ({@code 11.5sp Regular Muted}).
     */
    public static TextView descriptionText(Context ctx, boolean dark, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text != null ? text : "");
        tv.setTextColor(AcidTheme.muted(dark));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        tv.setLineSpacing(AcidTheme.dp(ctx, 2.5f), 1f);
        tv.setPadding(AcidTheme.dp(ctx, 2), 0, AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 8));
        return tv;
    }

    // =====================================================================
    // 2. Card Containers & Auto-Divided Group Cards
    // =====================================================================

    /**
     * Standard {@code 12dp} rounded card container for grouped rows (zero internal padding).
     */
    public static LinearLayout cardGroup(Context ctx, boolean dark) {
        return cardGroup(ctx, dark, AcidTheme.border(dark), 1f);
    }

    public static LinearLayout cardGroup(Context ctx, boolean dark, int strokeColor, float strokeDp) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(AcidTheme.cardBorderBg(
            ctx,
            AcidTheme.cardSurface(dark),
            strokeColor,
            strokeDp,
            AcidTheme.RADIUS_CARD
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = AcidTheme.dp(ctx, 10);
        card.setLayoutParams(lp);
        return card;
    }

    /**
     * Builds a grouped settings card and automatically inserts indented hairline dividers
     * ({@link AcidTheme#ROW_DIVIDER_INDENT_DP}) between rows — eliminating manual divider calls.
     */
    public static LinearLayout groupCard(Context ctx, boolean dark, View... rows) {
        LinearLayout card = cardGroup(ctx, dark);
        if (rows != null) {
            int added = 0;
            for (int i = 0; i < rows.length; i++) {
                View r = rows[i];
                if (r == null) continue;
                if (added > 0) {
                    addDivider(ctx, dark, card);
                }
                card.addView(r);
                added++;
            }
        }
        return card;
    }

    /**
     * Content card with standard {@code 14dp x 12dp} internal padding pre-applied.
     */
    public static LinearLayout contentCard(Context ctx, boolean dark) {
        return contentCard(ctx, dark, AcidTheme.border(dark));
    }

    public static LinearLayout contentCard(Context ctx, boolean dark, int strokeColor) {
        LinearLayout card = cardGroup(ctx, dark, strokeColor, 1f);
        int padH = AcidTheme.dp(ctx, AcidTheme.CARD_PAD_H_DP);
        int padV = AcidTheme.dp(ctx, AcidTheme.CARD_PAD_V_DP);
        card.setPadding(padH, padV, padH, padV);
        return card;
    }

    /**
     * Hero / status card with standard {@code 16dp} internal padding pre-applied.
     */
    public static LinearLayout heroCard(Context ctx, boolean dark, int strokeColor) {
        LinearLayout card = cardGroup(ctx, dark, strokeColor != 0 ? strokeColor : AcidTheme.border(dark), 1f);
        int pad = AcidTheme.dp(ctx, AcidTheme.HERO_PAD_DP);
        card.setPadding(pad, AcidTheme.dp(ctx, 14), pad, AcidTheme.dp(ctx, 14));
        return card;
    }

    /**
     * Adds an indented {@code 1px} hairline divider using {@link AcidTheme#ROW_DIVIDER_INDENT_DP}.
     */
    public static void addDivider(Context ctx, boolean dark, ViewGroup parent) {
        addDivider(ctx, dark, parent, AcidTheme.ROW_DIVIDER_INDENT_DP);
    }

    public static void addDivider(Context ctx, boolean dark, ViewGroup parent, float leftIndentDp) {
        View div = new View(ctx);
        div.setBackgroundColor(AcidTheme.border(dark));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        );
        lp.leftMargin = AcidTheme.dp(ctx, leftIndentDp);
        lp.rightMargin = AcidTheme.dp(ctx, AcidTheme.ROW_PAD_H_DP);
        parent.addView(div, lp);
    }

    // =====================================================================
    // 3. Grouped Settings Rows (Shared Base Anatomy + Reactive Switch Binding)
    // =====================================================================

    public static LinearLayout createIconBadgeBox(Context ctx, boolean dark, int iconId, int iconTint) {
        LinearLayout box = new LinearLayout(ctx);
        box.setGravity(Gravity.CENTER);
        int size = AcidTheme.dp(ctx, AcidTheme.ROW_ICON_BOX_DP);
        box.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        box.setBackground(AcidTheme.pillBg(
            ctx,
            AcidTheme.softTint(iconTint, dark ? 32 : 24),
            0,
            AcidTheme.RADIUS_CONTROL
        ));
        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, iconId, iconTint, 17);
        box.addView(icon);
        return box;
    }

    private static LinearLayout buildBaseGroupRow(
        Context ctx,
        boolean dark,
        int iconId,
        int iconTint,
        String title,
        String subtitle,
        boolean dangerText
    ) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, AcidTheme.ROW_PAD_H_DP);
        int padV = AcidTheme.dp(ctx, AcidTheme.ROW_PAD_V_DP);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(AcidTheme.interactiveBg(
            ctx, Color.TRANSPARENT, AcidTheme.rowPressed(dark), 0, AcidTheme.RADIUS_CONTROL
        ));
        row.setClickable(true);
        row.setFocusable(true);

        row.addView(createIconBadgeBox(ctx, dark, iconId, iconTint));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        tLp.leftMargin = AcidTheme.dp(ctx, AcidTheme.ROW_ICON_GAP_DP);
        tLp.rightMargin = AcidTheme.dp(ctx, 8);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title != null ? title : "");
        tvTitle.setTextColor(dangerText ? AcidTheme.COLOR_RED : AcidTheme.text(dark));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_ITEM);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(tvTitle);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextColor(AcidTheme.muted(dark));
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            tvSub.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
            textCol.addView(tvSub);
        }
        row.addView(textCol, tLp);
        return row;
    }

    /**
     * Concise 1-line navigation row with chevron and optional right value.
     */
    public static LinearLayout navRow(
        Context ctx,
        boolean dark,
        int iconId,
        int iconTint,
        String title,
        String rightValue,
        View.OnClickListener onClick
    ) {
        return navRow(ctx, dark, iconId, iconTint, title, null, rightValue, true, onClick);
    }

    /**
     * Standard clickable navigation row inside a {@link #groupCard}:
     * {@code [IconBox] [Title + Optional Subtitle] ... [Value] [>]}
     */
    public static LinearLayout navRow(
        Context ctx,
        boolean dark,
        int iconId,
        int iconTint,
        String title,
        String subtitle,
        String rightValue,
        boolean showChevron,
        View.OnClickListener onClick
    ) {
        LinearLayout row = buildBaseGroupRow(ctx, dark, iconId, iconTint, title, subtitle, false);

        if (!TextUtils.isEmpty(rightValue)) {
            TextView tvVal = new TextView(ctx);
            tvVal.setText(rightValue);
            tvVal.setTextColor(AcidTheme.muted(dark));
            tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvVal.setSingleLine(true);
            tvVal.setEllipsize(TextUtils.TruncateAt.END);
            tvVal.setMaxWidth(AcidTheme.dp(ctx, 140));
            LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (showChevron) vLp.rightMargin = AcidTheme.dp(ctx, 6);
            row.addView(tvVal, vLp);
        }

        if (showChevron) {
            AcidIcons.SvgIconView arrow = new AcidIcons.SvgIconView(
                ctx, AcidIcons.ICON_FORWARD, AcidTheme.muted(dark), 14
            );
            row.addView(arrow);
        }

        if (onClick != null) {
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /**
     * Read-only / informational value row without a trailing chevron.
     */
    public static LinearLayout valueRow(
        Context ctx,
        boolean dark,
        int iconId,
        int iconTint,
        String title,
        String rightValue,
        View.OnClickListener onClick
    ) {
        return navRow(ctx, dark, iconId, iconTint, title, null, rightValue, false, onClick);
    }

    /**
     * Destructive action row inside a {@link #groupCard} with red icon badge and title.
     */
    public static LinearLayout dangerRow(
        Context ctx,
        boolean dark,
        int iconId,
        String title,
        View.OnClickListener onClick
    ) {
        LinearLayout row = buildBaseGroupRow(ctx, dark, iconId, AcidTheme.COLOR_RED, title, null, true);
        AcidIcons.SvgIconView arrow = new AcidIcons.SvgIconView(
            ctx, AcidIcons.ICON_FORWARD, AcidTheme.muted(dark), 14
        );
        row.addView(arrow);
        if (onClick != null) {
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /**
     * Applies consistent thumb and track tinting to an Android {@link Switch} using {@link AcidTheme}.
     */
    public static void styleSwitch(Switch sw, boolean dark, boolean checked) {
        if (Build.VERSION.SDK_INT >= 23) {
            sw.setThumbTintList(ColorStateList.valueOf(AcidTheme.switchThumb(dark, checked)));
            sw.setTrackTintList(ColorStateList.valueOf(AcidTheme.switchTrack(dark, checked)));
        }
    }

    /**
     * Standard toggle switch row where tapping anywhere on the row flips the switch.
     */
    public static LinearLayout switchRow(
        Context ctx,
        final boolean dark,
        int iconId,
        int iconTint,
        String title,
        String subtitle,
        boolean checked,
        final OnToggleListener listener
    ) {
        LinearLayout row = buildBaseGroupRow(ctx, dark, iconId, iconTint, title, subtitle, false);

        final Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        styleSwitch(sw, dark, checked);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                styleSwitch(sw, dark, isChecked);
                if (listener != null) {
                    listener.onToggle(isChecked);
                }
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });

        row.addView(sw);
        return row;
    }

    /**
     * Declarative 1-line switch row bound directly to a {@link StateSource.BoolState}.
     */
    public static LinearLayout switchRow(
        final Context ctx,
        boolean dark,
        int iconId,
        int iconTint,
        String title,
        final StateSource.BoolState state,
        final String enableFeedback,
        final String disableFeedback
    ) {
        boolean initial = state != null && state.get();
        return switchRow(ctx, dark, iconId, iconTint, title, null, initial, new OnToggleListener() {
            @Override
            public void onToggle(boolean isChecked) {
                if (state != null && state.get() != isChecked) {
                    state.set(isChecked);
                    String fb = isChecked ? enableFeedback : disableFeedback;
                    if (!TextUtils.isEmpty(fb)) {
                        AcidFeedback.info(ctx, fb);
                    }
                }
            }
        });
    }

    /**
     * Declarative 1-line switch row bound directly to a registered {@link AcidSettings.BoolKey}.
     * Reads the display title and persisted state automatically from {@code key} and {@code store}.
     */
    public static LinearLayout switchRow(
        Context ctx,
        boolean dark,
        int iconId,
        int iconTint,
        AcidSettings.BoolKey key,
        BrowserDataStore store,
        String enableFeedback,
        String disableFeedback
    ) {
        return switchRow(
            ctx,
            dark,
            iconId,
            iconTint,
            key.title,
            store.boolState(key),
            enableFeedback,
            disableFeedback
        );
    }

    // =====================================================================
    // 4. Unified ListRow Card Builder (Replaces 8 Hand-Rolled List Row Implementations)
    // =====================================================================

    /**
     * Standard standalone list-item card ({@code 8dp} radius, stateful press highlight).
     */
    public static LinearLayout listItemCard(Context ctx, boolean dark, boolean selected, int selectedBorderColor) {
        LinearLayout row = new LinearLayout(ctx);
        int fill = selected ? AcidTheme.selectedBg(dark) : AcidTheme.menuItemBg(dark);
        int pressed = AcidTheme.rowPressed(dark);
        int stroke = selected
            ? (selectedBorderColor != 0 ? selectedBorderColor : AcidTheme.accent(dark))
            : AcidTheme.border(dark);
        row.setBackground(AcidTheme.interactiveBg(ctx, fill, pressed, stroke, AcidTheme.RADIUS_CONTROL));
        int padH = AcidTheme.dp(ctx, 12);
        int padV = AcidTheme.dp(ctx, 10);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = AcidTheme.dp(ctx, 6);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * Fluent builder for standalone list-item cards used in Bookmarks, History, Downloads,
     * Userscripts, Sessions, Model Selector, and Provider Presets.
     */
    public static final class ListRow {
        private int mIconId = 0;
        private int mIconColor = 0;
        private int mIconSizeDp = 18;
        private View mLeadingCustomView = null;

        private String mTitle = "";
        private String mSubtitle = null;
        private int mSubtitleColor = 0;
        private boolean mDimmed = false;

        private boolean mSelected = false;
        private int mSelectedBorderColor = 0;

        private View mTrailingView = null;
        private View mBottomView = null;
        private float mBottomViewLeftMarginDp = 0f;

        private View.OnClickListener mOnClick = null;
        private View.OnLongClickListener mOnLongClick = null;

        // Exposed subviews after build() for in-place updates (e.g., download progress, script switch)
        public LinearLayout rootCard;
        public AcidIcons.SvgIconView iconView;
        public TextView titleView;
        public TextView subtitleView;

        public ListRow leadingIcon(int iconId, int iconColor) {
            return leadingIcon(iconId, iconColor, 18);
        }

        public ListRow leadingIcon(int iconId, int iconColor, int sizeDp) {
            mIconId = iconId;
            mIconColor = iconColor;
            mIconSizeDp = sizeDp;
            return this;
        }

        public ListRow leadingAvatar(Context ctx, String title, String url) {
            mLeadingCustomView = AcidIcons.createLetterAvatar(
                ctx, title, url, 34, AcidTheme.avatarColor(title), Color.WHITE
            );
            return this;
        }

        public ListRow title(String title) {
            mTitle = title != null ? title : "";
            return this;
        }

        public ListRow subtitle(String subtitle) {
            mSubtitle = subtitle;
            return this;
        }

        public ListRow subtitleColor(int color) {
            mSubtitleColor = color;
            return this;
        }

        public ListRow dimmed(boolean dimmed) {
            mDimmed = dimmed;
            return this;
        }

        public ListRow selected(boolean selected, int borderColor) {
            mSelected = selected;
            mSelectedBorderColor = borderColor;
            return this;
        }

        public ListRow trailingView(View view) {
            mTrailingView = view;
            return this;
        }

        public ListRow trailingText(Context ctx, boolean dark, String text) {
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextColor(AcidTheme.muted(dark));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            mTrailingView = tv;
            return this;
        }

        public ListRow trailingCheck(Context ctx, int color) {
            mTrailingView = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CHECK, color, 18);
            return this;
        }

        public ListRow trailingBadge(Context ctx, boolean dark, String text, int color) {
            mTrailingView = statusTag(ctx, dark, text, color);
            return this;
        }

        public ListRow trailingIconAction(Context ctx, boolean dark, int iconId, int color, View.OnClickListener onClick) {
            mTrailingView = iconAction(ctx, dark, iconId, color, onClick);
            return this;
        }

        public ListRow bottomView(View view, float leftMarginDp) {
            mBottomView = view;
            mBottomViewLeftMarginDp = leftMarginDp;
            return this;
        }

        public ListRow onClick(View.OnClickListener listener) {
            mOnClick = listener;
            return this;
        }

        public ListRow onLongClick(View.OnLongClickListener listener) {
            mOnLongClick = listener;
            return this;
        }

        public LinearLayout build(Context ctx, boolean dark) {
            LinearLayout card = listItemCard(ctx, dark, mSelected, mSelectedBorderColor);
            rootCard = card;

            LinearLayout mainRow;
            if (mBottomView != null) {
                card.setOrientation(LinearLayout.VERTICAL);
                mainRow = new LinearLayout(ctx);
                mainRow.setOrientation(LinearLayout.HORIZONTAL);
                mainRow.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(mainRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            } else {
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                mainRow = card;
            }

            boolean hasLeading = false;
            if (mLeadingCustomView != null) {
                mainRow.addView(mLeadingCustomView);
                hasLeading = true;
            } else if (mIconId != 0) {
                iconView = new AcidIcons.SvgIconView(
                    ctx,
                    mIconId,
                    mIconColor != 0 ? mIconColor : AcidTheme.icon(dark),
                    mIconSizeDp
                );
                mainRow.addView(iconView);
                hasLeading = true;
            }

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            if (hasLeading) {
                tLp.leftMargin = AcidTheme.dp(ctx, 10);
            }
            if (mTrailingView != null) {
                tLp.rightMargin = AcidTheme.dp(ctx, 8);
            }

            titleView = new TextView(ctx);
            titleView.setText(mTitle);
            titleView.setTextColor(mDimmed ? AcidTheme.muted(dark) : AcidTheme.text(dark));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_ITEM);
            titleView.setTypeface(mDimmed ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(titleView);

            if (!TextUtils.isEmpty(mSubtitle)) {
                subtitleView = new TextView(ctx);
                subtitleView.setText(mSubtitle);
                subtitleView.setTextColor(mSubtitleColor != 0 ? mSubtitleColor : AcidTheme.muted(dark));
                subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                subtitleView.setSingleLine(true);
                subtitleView.setEllipsize(TextUtils.TruncateAt.END);
                subtitleView.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
                textCol.addView(subtitleView);
            }
            mainRow.addView(textCol, tLp);

            if (mTrailingView != null) {
                mainRow.addView(mTrailingView);
            }

            if (mBottomView != null) {
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(ctx, 4)
                );
                bLp.topMargin = AcidTheme.dp(ctx, 6);
                if (mBottomViewLeftMarginDp > 0) {
                    bLp.leftMargin = AcidTheme.dp(ctx, mBottomViewLeftMarginDp);
                }
                card.addView(mBottomView, bLp);
            }

            if (mDimmed) {
                card.setAlpha(0.75f);
            }

            if (mOnClick != null) {
                card.setOnClickListener(mOnClick);
            }
            if (mOnLongClick != null) {
                card.setOnLongClickListener(mOnLongClick);
            }
            return card;
        }
    }

    // =====================================================================
    // 5. Form Fields, Search Bar, Segmented Tabs & Filter Chips
    // =====================================================================

    /**
     * Standard Form Field Label ({@code 12sp Bold Muted}, visually subordinate to outer section headers).
     */
    public static TextView fieldLabel(Context ctx, boolean dark, String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label != null ? label : "");
        tv.setTextColor(AcidTheme.muted(dark));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, AcidTheme.dp(ctx, 2), 0, AcidTheme.dp(ctx, 5));
        return tv;
    }

    /**
     * Form field header row with a left label and a right interactive text action (e.g. "显示密钥").
     */
    public static LinearLayout fieldLabelWithAction(
        Context ctx,
        boolean dark,
        String label,
        TextView actionButton
    ) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(fieldLabel(ctx, dark, label), new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        if (actionButton != null) {
            row.addView(actionButton);
        }
        return row;
    }

    /**
     * Standard styled {@link EditText} ({@code 13sp}, {@code 8dp} radius, crisp border).
     */
    public static EditText inputField(
        Context ctx,
        boolean dark,
        String initialValue,
        String hint,
        boolean singleLine
    ) {
        EditText et = new EditText(ctx);
        et.setSingleLine(singleLine);
        if (initialValue != null) et.setText(initialValue);
        if (hint != null) et.setHint(hint);
        et.setTextColor(AcidTheme.text(dark));
        et.setHintTextColor(AcidTheme.muted(dark));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        int padH = AcidTheme.dp(ctx, 12);
        int padV = AcidTheme.dp(ctx, 9);
        et.setPadding(padH, padV, padH, padV);
        et.setBackground(AcidTheme.pillBg(
            ctx,
            AcidTheme.formInputBg(dark),
            AcidTheme.formInputBorder(dark),
            AcidTheme.RADIUS_CONTROL
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = AcidTheme.dp(ctx, 10);
        et.setLayoutParams(lp);
        return et;
    }

    /**
     * Search box bar with leading search icon and embedded {@link EditText}.
     */
    public static LinearLayout searchBox(
        Context ctx,
        boolean dark,
        EditText targetEditText,
        String hint
    ) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 2));
        box.setBackground(AcidTheme.pillBg(
            ctx, AcidTheme.searchBg(dark), AcidTheme.searchBorder(dark), AcidTheme.RADIUS_CONTROL
        ));

        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
            ctx, AcidIcons.ICON_SEARCH, AcidTheme.muted(dark), 15
        );
        box.addView(icon);

        targetEditText.setSingleLine(true);
        targetEditText.setHint(hint);
        targetEditText.setHintTextColor(AcidTheme.muted(dark));
        targetEditText.setTextColor(AcidTheme.text(dark));
        targetEditText.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        targetEditText.setBackground(null);
        targetEditText.setPadding(AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 6));
        box.addView(targetEditText, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        return box;
    }

    /**
     * Segmented tab switcher (2..N tabs) with pill highlight.
     */
    public static LinearLayout segmentedTabs(
        Context ctx,
        boolean dark,
        String[] labels,
        int activeIndex,
        int activeColor,
        final OnTabSelectedListener listener
    ) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = AcidTheme.dp(ctx, 3);
        bar.setPadding(pad, pad, pad, pad);
        bar.setBackground(AcidTheme.pillBg(
            ctx, AcidTheme.searchBg(dark), AcidTheme.border(dark), AcidTheme.RADIUS_CONTROL
        ));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        barLp.bottomMargin = AcidTheme.dp(ctx, 8);
        bar.setLayoutParams(barLp);

        int highlight = activeColor != 0 ? activeColor : AcidTheme.accent(dark);
        for (int i = 0; i < labels.length; i++) {
            final int tabIdx = i;
            boolean isActive = (i == activeIndex);
            TextView tab = new TextView(ctx);
            tab.setText(labels[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
            tab.setTypeface(isActive ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            int tPadV = AcidTheme.dp(ctx, 7);
            int tPadH = AcidTheme.dp(ctx, 8);
            tab.setPadding(tPadH, tPadV, tPadH, tPadV);
            if (isActive) {
                tab.setTextColor(AcidTheme.onColor(highlight));
                tab.setBackground(AcidTheme.pillBg(ctx, highlight, 0, AcidTheme.RADIUS_BADGE));
            } else {
                tab.setTextColor(AcidTheme.muted(dark));
                tab.setBackground(AcidTheme.interactiveBg(
                    ctx, Color.TRANSPARENT, AcidTheme.rowPressed(dark), 0, AcidTheme.RADIUS_BADGE
                ));
            }
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onTabSelected(tabIdx);
                }
            });
            bar.addView(tab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));
        }
        return bar;
    }

    /**
     * Horizontal scrollable filter chip bar using {@link AcidTheme#RADIUS_PILL} and {@link AcidTheme#onColor}.
     */
    public static HorizontalScrollView filterChipBar(
        final Context ctx,
        final boolean dark,
        String[] labels,
        int initialIndex,
        final OnTabSelectedListener listener
    ) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);

        final LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[labels.length];

        final Runnable[] styleUpdater = new Runnable[1];
        final int[] activeIdx = new int[]{initialIndex};

        styleUpdater[0] = new Runnable() {
            @Override
            public void run() {
                int accent = AcidTheme.accent(dark);
                for (int i = 0; i < chips.length; i++) {
                    TextView chip = chips[i];
                    boolean sel = (i == activeIdx[0]);
                    if (sel) {
                        chip.setBackground(AcidTheme.interactiveBg(
                            ctx, accent, AcidTheme.adjustPressFill(accent, dark), 0, AcidTheme.RADIUS_PILL
                        ));
                        chip.setTextColor(AcidTheme.onColor(accent));
                    } else {
                        chip.setBackground(AcidTheme.interactiveBg(
                            ctx,
                            AcidTheme.menuItemBg(dark),
                            AcidTheme.rowPressed(dark),
                            AcidTheme.border(dark),
                            AcidTheme.RADIUS_PILL
                        ));
                        chip.setTextColor(AcidTheme.muted(dark));
                    }
                }
            }
        };

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView pill = new TextView(ctx);
            pill.setText(labels[i]);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setGravity(Gravity.CENTER);
            int pH = AcidTheme.dp(ctx, 12);
            int pV = AcidTheme.dp(ctx, 5);
            pill.setPadding(pH, pV, pH, pV);
            pill.setClickable(true);
            pill.setFocusable(true);
            pill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (activeIdx[0] != idx) {
                        activeIdx[0] = idx;
                        styleUpdater[0].run();
                        if (listener != null) listener.onTabSelected(idx);
                    }
                }
            });
            chips[i] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) lp.leftMargin = AcidTheme.dp(ctx, 6);
            bar.addView(pill, lp);
        }
        styleUpdater[0].run();
        hsv.addView(bar);
        return hsv;
    }

    // =====================================================================
    // 6. Buttons, Status Tags, Text/Icon Actions & LoadingButton
    // =====================================================================

    /**
     * Compact status tag pill ({@code 10.5sp Bold}, {@code 6dp} radius, soft-tinted background).
     */
    public static TextView statusTag(Context ctx, boolean dark, String text, int color) {
        int fg = color != 0 ? color : AcidTheme.accent(dark);
        TextView tv = new TextView(ctx);
        tv.setText(text != null ? text : "");
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setPadding(AcidTheme.dp(ctx, 7), AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 7), AcidTheme.dp(ctx, 2));
        tv.setBackground(AcidTheme.pillBg(ctx, AcidTheme.softTint(fg, dark ? 32 : 24), 0, AcidTheme.RADIUS_BADGE));
        return tv;
    }

    /**
     * Interactive inline text action link with press highlight (e.g. "显示密钥", "恢复默认模型").
     */
    public static TextView textAction(
        Context ctx,
        boolean dark,
        String text,
        int color,
        View.OnClickListener onClick
    ) {
        TextView btn = new TextView(ctx);
        btn.setText(text != null ? text : "");
        btn.setTextColor(color != 0 ? color : AcidTheme.accent(dark));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 3), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 3));
        btn.setBackground(AcidTheme.interactiveBg(
            ctx, Color.TRANSPARENT, AcidTheme.rowPressed(dark), 0, AcidTheme.RADIUS_BADGE
        ));
        btn.setClickable(true);
        btn.setFocusable(true);
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    /**
     * Unified {@code 34dp x 34dp} icon action button with press feedback (e.g. row delete button).
     */
    public static LinearLayout iconAction(
        Context ctx,
        boolean dark,
        int iconId,
        int iconColor,
        View.OnClickListener onClick
    ) {
        LinearLayout box = new LinearLayout(ctx);
        box.setGravity(Gravity.CENTER);
        int size = AcidTheme.dp(ctx, 34);
        box.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        box.setBackground(AcidTheme.interactiveBg(
            ctx, Color.TRANSPARENT, AcidTheme.rowPressed(dark), 0, AcidTheme.RADIUS_CONTROL
        ));
        box.setClickable(true);
        box.setFocusable(true);
        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
            ctx, iconId, iconColor != 0 ? iconColor : AcidTheme.muted(dark), 15
        );
        box.addView(icon);
        if (onClick != null) {
            box.setOnClickListener(onClick);
        }
        return box;
    }

    /**
     * Solid primary CTA button with stateful press feedback and automatic luminance contrast.
     */
    public static TextView primaryButton(
        Context ctx,
        boolean dark,
        String text,
        int fillColor,
        View.OnClickListener onClick
    ) {
        int fill = fillColor != 0 ? fillColor : AcidTheme.accent(dark);
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(AcidTheme.onColor(fill));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int padV = AcidTheme.dp(ctx, 10);
        int padH = AcidTheme.dp(ctx, 14);
        btn.setPadding(padH, padV, padH, padV);
        btn.setBackground(AcidTheme.interactiveBg(
            ctx,
            fill,
            AcidTheme.adjustPressFill(fill, dark),
            0,
            AcidTheme.RADIUS_CONTROL
        ));
        btn.setClickable(true);
        btn.setFocusable(true);
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    /**
     * Secondary / neutral outlined button with stateful press feedback.
     */
    public static TextView secondaryButton(
        Context ctx,
        boolean dark,
        String text,
        int textColor,
        View.OnClickListener onClick
    ) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(textColor != 0 ? textColor : AcidTheme.text(dark));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int padV = AcidTheme.dp(ctx, 10);
        int padH = AcidTheme.dp(ctx, 14);
        btn.setPadding(padH, padV, padH, padV);
        btn.setBackground(AcidTheme.interactiveBg(
            ctx,
            AcidTheme.subtle(dark),
            AcidTheme.rowPressed(dark),
            AcidTheme.border(dark),
            AcidTheme.RADIUS_CONTROL
        ));
        btn.setClickable(true);
        btn.setFocusable(true);
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    /**
     * Compact inline action pill button ({@code 11.5sp Bold}, {@code 6dp} radius) for card toolbars.
     */
    public static TextView miniButton(
        Context ctx,
        boolean dark,
        String text,
        int fgColor,
        boolean filled,
        View.OnClickListener onClick
    ) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int padH = AcidTheme.dp(ctx, 10);
        int padV = AcidTheme.dp(ctx, 5);
        btn.setPadding(padH, padV, padH, padV);

        if (filled) {
            int fill = fgColor != 0 ? fgColor : AcidTheme.accent(dark);
            btn.setTextColor(AcidTheme.onColor(fill));
            btn.setBackground(AcidTheme.interactiveBg(
                ctx, fill, AcidTheme.adjustPressFill(fill, dark), 0, AcidTheme.RADIUS_BADGE
            ));
        } else {
            btn.setTextColor(fgColor != 0 ? fgColor : AcidTheme.text(dark));
            btn.setBackground(AcidTheme.interactiveBg(
                ctx,
                AcidTheme.formInputBg(dark),
                AcidTheme.rowPressed(dark),
                AcidTheme.border(dark),
                AcidTheme.RADIUS_BADGE
            ));
        }
        btn.setClickable(true);
        btn.setFocusable(true);
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    /**
     * Equal-width 2-button footer bar ({@code [Secondary] [Primary]}).
     */
    public static LinearLayout buttonRow(Context ctx, View leftButton, View rightButton) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        lLp.rightMargin = AcidTheme.dp(ctx, 6);
        row.addView(leftButton, lLp);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        rLp.leftMargin = AcidTheme.dp(ctx, 6);
        row.addView(rightButton, rLp);
        return row;
    }

    /**
     * Stateful button controller for async actions (eliminates {@code final TextView[] ref} arrays).
     */
    public static final class LoadingButton {
        public interface OnAsyncActionListener {
            void onTrigger(LoadingButton button);
        }

        public final TextView view;
        private final String mIdleLabel;
        private final String mLoadingLabel;

        public LoadingButton(TextView buttonView, String idleLabel, String loadingLabel) {
            this.view = buttonView;
            this.mIdleLabel = idleLabel;
            this.mLoadingLabel = loadingLabel;
        }

        public static LoadingButton primary(
            Context ctx,
            boolean dark,
            String idleLabel,
            String loadingLabel,
            int fillColor,
            final OnAsyncActionListener listener
        ) {
            TextView tv = primaryButton(ctx, dark, idleLabel, fillColor, null);
            final LoadingButton lb = new LoadingButton(tv, idleLabel, loadingLabel);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onTrigger(lb);
                }
            });
            return lb;
        }

        public static LoadingButton mini(
            Context ctx,
            boolean dark,
            String idleLabel,
            String loadingLabel,
            int fgColor,
            boolean filled,
            final OnAsyncActionListener listener
        ) {
            TextView tv = miniButton(ctx, dark, idleLabel, fgColor, filled, null);
            final LoadingButton lb = new LoadingButton(tv, idleLabel, loadingLabel);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onTrigger(lb);
                }
            });
            return lb;
        }

        public void setLoading() {
            view.setText(mLoadingLabel);
            view.setEnabled(false);
            view.setAlpha(0.65f);
        }

        public void reset() {
            view.setText(mIdleLabel);
            view.setEnabled(true);
            view.setAlpha(1f);
        }
    }

    // =====================================================================
    // 7. Empty State & Monospace Code Preview
    // =====================================================================

    public static LinearLayout emptyState(Context ctx, boolean dark, int iconId, String message) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int padV = AcidTheme.dp(ctx, 28);
        int padH = AcidTheme.dp(ctx, 16);
        box.setPadding(padH, padV, padH, padV);

        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
            ctx, iconId, AcidTheme.iconDisabled(dark), 28
        );
        box.addView(icon);

        TextView tv = new TextView(ctx);
        tv.setText(message != null ? message : "");
        tv.setTextColor(AcidTheme.muted(dark));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(AcidTheme.dp(ctx, 2), 1f);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tLp.topMargin = AcidTheme.dp(ctx, 8);
        box.addView(tv, tLp);
        return box;
    }

    public static TextView codePreviewBox(Context ctx, boolean dark, String code, int maxChars) {
        TextView tv = new TextView(ctx);
        String preview = code != null ? code : "";
        if (maxChars > 0 && preview.length() > maxChars) {
            preview = preview.substring(0, maxChars) + "\n…";
        }
        tv.setText(preview);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(AcidTheme.DARK_CODE_TEXT);
        int pad = AcidTheme.dp(ctx, 10);
        tv.setPadding(pad, AcidTheme.dp(ctx, 8), pad, AcidTheme.dp(ctx, 8));
        tv.setBackground(AcidTheme.pillBg(
            ctx,
            AcidTheme.codeBlockBg(dark),
            AcidTheme.codeBlockBorder(dark),
            AcidTheme.RADIUS_BADGE
        ));
        return tv;
    }
}
