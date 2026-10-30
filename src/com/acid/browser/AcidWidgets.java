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
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Standard UI Component Primitives Factory for Acid Browser.
 *
 * <p>Enforces the 5-level typography hierarchy, 3-tier corner radius scale, neutral Day/Night
 * surfaces, and stateful touch feedback across all sheets and dialogs without boilerplate.
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
    // 1. Typography & Section Headers
    // =====================================================================

    /**
     * L2 Section Header ({@code 12sp Bold Muted}) placed outside above a card group.
     */
    public static TextView sectionHeader(Context ctx, boolean dark, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title != null ? title : "");
        tv.setTextColor(AcidTheme.muted(dark));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H2_SECTION);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(
            AcidTheme.dp(ctx, 4),
            AcidTheme.dp(ctx, 10),
            AcidTheme.dp(ctx, 4),
            AcidTheme.dp(ctx, 6)
        );
        return tv;
    }

    /**
     * L2 Section Header row with a right-aligned status badge/label.
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
            AcidTheme.dp(ctx, 10),
            AcidTheme.dp(ctx, 4),
            AcidTheme.dp(ctx, 6)
        );

        TextView tvLeft = new TextView(ctx);
        tvLeft.setText(leftTitle != null ? leftTitle : "");
        tvLeft.setTextColor(AcidTheme.muted(dark));
        tvLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H2_SECTION);
        tvLeft.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tvLeft, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        if (!TextUtils.isEmpty(rightBadge)) {
            TextView tvRight = new TextView(ctx);
            tvRight.setText(rightBadge);
            tvRight.setTextColor(rightColor != 0 ? rightColor : AcidTheme.muted(dark));
            tvRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            tvRight.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(tvRight);
        }
        return row;
    }

    /**
     * Helper description banner ({@code 11.5sp Regular Muted}).
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
    // 2. Card Groups & Dividers
    // =====================================================================

    /**
     * Standard {@code 12dp} rounded card container for grouped settings or form sections.
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
     * Adds an indented {@code 1px} hairline divider between rows inside a {@link #cardGroup}.
     */
    public static void addDivider(Context ctx, boolean dark, ViewGroup parent, float leftIndentDp) {
        View div = new View(ctx);
        div.setBackgroundColor(AcidTheme.border(dark));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        );
        lp.leftMargin = AcidTheme.dp(ctx, leftIndentDp);
        lp.rightMargin = AcidTheme.dp(ctx, 14);
        parent.addView(div, lp);
    }

    // =====================================================================
    // 3. List & Settings Rows (L4 = 14sp Bold)
    // =====================================================================

    private static LinearLayout createIconBadgeBox(Context ctx, boolean dark, int iconId, int iconTint) {
        LinearLayout box = new LinearLayout(ctx);
        box.setGravity(Gravity.CENTER);
        int size = AcidTheme.dp(ctx, 32);
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

    /**
     * Standard clickable navigation row inside a {@link #cardGroup}:
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
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 14);
        int padV = AcidTheme.dp(ctx, 12);
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
        tLp.leftMargin = AcidTheme.dp(ctx, 12);
        tLp.rightMargin = AcidTheme.dp(ctx, 8);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title != null ? title : "");
        tvTitle.setTextColor(AcidTheme.text(dark));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
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

        if (!TextUtils.isEmpty(rightValue)) {
            TextView tvVal = new TextView(ctx);
            tvVal.setText(rightValue);
            tvVal.setTextColor(AcidTheme.muted(dark));
            tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvVal.setSingleLine(true);
            tvVal.setEllipsize(TextUtils.TruncateAt.END);
            tvVal.setMaxWidth(AcidTheme.dp(ctx, 130));
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
     * Applies consistent thumb and track tinting to an Android {@link Switch}.
     */
    public static void styleSwitch(Switch sw, boolean dark, boolean checked) {
        if (Build.VERSION.SDK_INT >= 23) {
            int accent = AcidTheme.accent(dark);
            int offThumb = dark ? Color.parseColor("#A1A1AA") : Color.parseColor("#94A3B8");
            int onTrack = AcidTheme.softTint(accent, dark ? 95 : 85);
            int offTrack = dark ? Color.parseColor("#2A2A32") : Color.parseColor("#CBD5E1");
            sw.setThumbTintList(ColorStateList.valueOf(checked ? accent : offThumb));
            sw.setTrackTintList(ColorStateList.valueOf(checked ? onTrack : offTrack));
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
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 14);
        int padV = AcidTheme.dp(ctx, 12);
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
        tLp.leftMargin = AcidTheme.dp(ctx, 12);
        tLp.rightMargin = AcidTheme.dp(ctx, 8);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title != null ? title : "");
        tvTitle.setTextColor(AcidTheme.text(dark));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
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
     * Standard standalone list-item card ({@code 8dp} radius, stateful press highlight).
     */
    public static LinearLayout listItemCard(Context ctx, boolean dark, boolean selected, int selectedBorderColor) {
        LinearLayout row = new LinearLayout(ctx);
        int fill = selected ? AcidTheme.selectedBg(dark) : AcidTheme.menuItemBg(dark);
        int pressed = AcidTheme.rowPressed(dark);
        int stroke = selected ? selectedBorderColor : AcidTheme.border(dark);
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

    // =====================================================================
    // 4. Form Fields, Search Bar, Segmented Tabs & Buttons
    // =====================================================================

    /**
     * Standard Form Field Label ({@code 12sp Bold Muted}, colon-free).
     */
    public static TextView fieldLabel(Context ctx, boolean dark, String label) {
        TextView tv = new TextView(ctx);
        tv.setText(label != null ? label : "");
        tv.setTextColor(AcidTheme.muted(dark));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_LABEL);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, AcidTheme.dp(ctx, 2), 0, AcidTheme.dp(ctx, 5));
        return tv;
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
            ctx, AcidTheme.searchBg(dark), AcidTheme.border(dark), AcidTheme.RADIUS_CONTROL + 2
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
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_LABEL);
            tab.setTypeface(isActive ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            int tPadV = AcidTheme.dp(ctx, 7);
            int tPadH = AcidTheme.dp(ctx, 8);
            tab.setPadding(tPadH, tPadV, tPadH, tPadV);
            if (isActive) {
                tab.setTextColor(dark && highlight == AcidTheme.DARK_ACCENT ? Color.BLACK : Color.WHITE);
                tab.setBackground(AcidTheme.pillBg(ctx, highlight, 0, AcidTheme.RADIUS_CONTROL));
            } else {
                tab.setTextColor(AcidTheme.muted(dark));
                tab.setBackground(AcidTheme.interactiveBg(
                    ctx, Color.TRANSPARENT, AcidTheme.rowPressed(dark), 0, AcidTheme.RADIUS_CONTROL
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
     * Solid primary CTA button with stateful press feedback.
     */
    public static TextView primaryButton(
        Context ctx,
        boolean dark,
        String text,
        int fillColor,
        View.OnClickListener onClick
    ) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        boolean darkTextOnNeon = dark && fillColor == AcidTheme.DARK_ACCENT;
        btn.setTextColor(darkTextOnNeon ? Color.BLACK : Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int padV = AcidTheme.dp(ctx, 10);
        int padH = AcidTheme.dp(ctx, 14);
        btn.setPadding(padH, padV, padH, padV);
        btn.setBackground(AcidTheme.interactiveBg(
            ctx,
            fillColor,
            AcidTheme.adjustPressFill(fillColor, dark),
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
            boolean darkText = dark && fgColor == AcidTheme.DARK_ACCENT;
            btn.setTextColor(darkText ? Color.BLACK : Color.WHITE);
            btn.setBackground(AcidTheme.interactiveBg(
                ctx, fgColor, AcidTheme.adjustPressFill(fgColor, dark), 0, AcidTheme.RADIUS_BADGE
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

    // =====================================================================
    // 5. Empty State & Monospace Code Preview
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
