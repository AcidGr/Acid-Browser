package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * Unified Floating Context Menu for Acid Browser.
 *
 * <p>Provides one consistent {@code 12dp} rounded popup surface for both WebView element
 * long-presses ({@link #showAtPoint}) and list-item long-presses ({@link #showForAnchor}),
 * supporting optional header titles, vector icons, and red danger-item tinting.
 */
public final class AcidContextMenu {

    public static final class ActionItem {
        public final int iconId;
        public final String label;
        public final boolean isDanger;
        public final Runnable onClick;

        public ActionItem(int iconId, String label, Runnable onClick) {
            this(iconId, label, false, onClick);
        }

        public ActionItem(int iconId, String label, boolean isDanger, Runnable onClick) {
            this.iconId = iconId;
            this.label = label != null ? label : "";
            this.isDanger = isDanger;
            this.onClick = onClick;
        }
    }

    private AcidContextMenu() {}

    /**
     * Shows a context menu anchored near a tapped/long-pressed row view.
     */
    public static void showForAnchor(
        Context context,
        View anchorView,
        boolean dark,
        String headerTitle,
        List<ActionItem> items
    ) {
        if (context == null || anchorView == null || items == null || items.isEmpty()) return;
        int[] loc = new int[2];
        anchorView.getLocationOnScreen(loc);
        int centerX = loc[0] + anchorView.getWidth() / 2;
        int touchY = loc[1] + anchorView.getHeight() / 2;
        showAtPoint(context, anchorView, centerX, touchY, dark, headerTitle, items);
    }

    /**
     * Shows a context menu near screen coordinates {@code (screenX, screenY)} with automatic
     * viewport clamping so it never clips off-screen.
     */
    public static void showAtPoint(
        Context context,
        View hostAnchor,
        int screenX,
        int screenY,
        boolean dark,
        String headerTitle,
        List<ActionItem> items
    ) {
        if (context == null || hostAnchor == null || items == null || items.isEmpty()) return;

        final PopupWindow popup = new PopupWindow(context);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(AcidTheme.dp(context, 14));
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(AcidTheme.cardBorderBg(
            context,
            AcidTheme.popupBg(dark),
            AcidTheme.popupBorder(dark),
            1f,
            AcidTheme.RADIUS_CARD
        ));
        int pad = AcidTheme.dp(context, 6);
        root.setPadding(pad, pad, pad, pad);

        if (!TextUtils.isEmpty(headerTitle)) {
            TextView tvHead = new TextView(context);
            tvHead.setText(AcidLocale.tr(headerTitle));
            tvHead.setTextColor(AcidTheme.muted(dark));
            tvHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvHead.setTypeface(Typeface.DEFAULT_BOLD);
            tvHead.setSingleLine(true);
            tvHead.setEllipsize(TextUtils.TruncateAt.END);
            int hpH = AcidTheme.dp(context, 12);
            int hpV = AcidTheme.dp(context, 6);
            tvHead.setPadding(hpH, hpV, hpH, hpV);
            root.addView(tvHead);

            View div = new View(context);
            div.setBackgroundColor(AcidTheme.border(dark));
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            );
            dLp.leftMargin = AcidTheme.dp(context, 8);
            dLp.rightMargin = AcidTheme.dp(context, 8);
            dLp.bottomMargin = AcidTheme.dp(context, 3);
            root.addView(div, dLp);
        }

        for (final ActionItem item : items) {
            if (item == null) continue;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = AcidTheme.dp(context, 12);
            row.setPadding(padH, 0, padH, 0);
            row.setBackground(AcidTheme.interactiveBg(
                context, Color.TRANSPARENT, AcidTheme.pressedBg(dark), 0, AcidTheme.RADIUS_CONTROL
            ));
            row.setClickable(true);
            row.setFocusable(true);

            int fg = item.isDanger ? AcidTheme.COLOR_RED : AcidTheme.popupText(dark);
            int iconColor = item.isDanger ? AcidTheme.COLOR_RED : AcidTheme.muted(dark);

            if (item.iconId != 0) {
                AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(context, item.iconId, iconColor, 16);
                LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                iLp.rightMargin = AcidTheme.dp(context, 10);
                row.addView(icon, iLp);
            }

            TextView tv = new TextView(context);
            tv.setText(AcidLocale.tr(item.label));
            tv.setTextColor(fg);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
            tv.setTypeface(item.isDanger ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    if (item.onClick != null) {
                        item.onClick.run();
                    }
                }
            });

            root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                AcidTheme.dp(context, 42)
            ));
        }

        popup.setContentView(root);

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int menuWidth = AcidTheme.dp(context, 196);
        popup.setWidth(menuWidth);
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);

        root.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int menuHeight = root.getMeasuredHeight();

        int margin = AcidTheme.dp(context, 12);
        int posX = screenX - menuWidth / 2;
        if (posX + menuWidth > dm.widthPixels - margin) {
            posX = dm.widthPixels - margin - menuWidth;
        }
        if (posX < margin) {
            posX = margin;
        }

        int posY = screenY + AcidTheme.dp(context, 8);
        if (posY + menuHeight > dm.heightPixels - AcidTheme.dp(context, 56)) {
            posY = screenY - menuHeight - AcidTheme.dp(context, 8);
        }
        if (posY < AcidTheme.dp(context, 42)) {
            posY = AcidTheme.dp(context, 42);
        }

        try {
            popup.showAtLocation(hostAnchor, Gravity.NO_GRAVITY, posX, posY);
        } catch (Exception e) {
            Log.w("AcidContextMenu", "Failed to show context menu popup: host window detached or token invalid", e);
        }
    }
}
