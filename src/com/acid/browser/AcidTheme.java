package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * Unified Theme & UI primitive helper for Acid Browser.
 * Encapsulates light/dark palettes and shape drawing helpers.
 */
public final class AcidTheme {

    private AcidTheme() {}

    // Day/Light Theme Colors
    public static final int LIGHT_BG = Color.parseColor("#FFFFFF");
    public static final int LIGHT_BAR = Color.parseColor("#FFFFFF");
    public static final int LIGHT_BORDER = Color.parseColor("#E5E7EB");
    public static final int LIGHT_SEARCH_BG = Color.parseColor("#F1F5F9");
    public static final int LIGHT_SEARCH_BORDER = Color.parseColor("#E2E8F0");
    public static final int LIGHT_TEXT = Color.parseColor("#0F172A");
    public static final int LIGHT_MUTED = Color.parseColor("#64748B");
    public static final int LIGHT_ICON = Color.parseColor("#334155");
    public static final int LIGHT_ICON_DISABLED = Color.parseColor("#CBD5E1");
    public static final int LIGHT_ACCENT = Color.parseColor("#00BA76");
    public static final int LIGHT_MENU_ITEM_BG = Color.parseColor("#F8FAFC");
    public static final int COLOR_TAB_ACTIVE_BORDER = Color.parseColor("#0284C7");
    public static final int COLOR_OVERVIEW_BG = Color.parseColor("#F1F5F9");

    // Night/Dark Theme Colors
    public static final int DARK_BG = Color.parseColor("#0F172A");
    public static final int DARK_BAR = Color.parseColor("#1E293B");
    public static final int DARK_BORDER = Color.parseColor("#334155");
    public static final int DARK_SEARCH_BG = Color.parseColor("#0F172A");
    public static final int DARK_SEARCH_BORDER = Color.parseColor("#334155");
    public static final int DARK_TEXT = Color.parseColor("#F8FAFC");
    public static final int DARK_MUTED = Color.parseColor("#94A3B8");
    public static final int DARK_ICON = Color.parseColor("#E2E8F0");
    public static final int DARK_ICON_DISABLED = Color.parseColor("#475569");
    public static final int DARK_ACCENT = Color.parseColor("#00FF88");
    public static final int DARK_MENU_ITEM_BG = Color.parseColor("#0F172A");

    // Accent & Status Colors
    public static final int COLOR_AMBER = Color.parseColor("#F59E0B");
    public static final int COLOR_BLUE = Color.parseColor("#0284C7");
    public static final int COLOR_RED = Color.parseColor("#EF4444");
    public static final int COLOR_GREEN = Color.parseColor("#10B981");
    public static final int COLOR_PURPLE = Color.parseColor("#8B5CF6");
    public static final int COLOR_ORANGE = Color.parseColor("#F97316");

    public static int bg(boolean dark) { return dark ? DARK_BG : LIGHT_BG; }
    public static int bar(boolean dark) { return dark ? DARK_BAR : LIGHT_BAR; }
    public static int border(boolean dark) { return dark ? DARK_BORDER : LIGHT_BORDER; }
    public static int searchBg(boolean dark) { return dark ? DARK_SEARCH_BG : LIGHT_SEARCH_BG; }
    public static int searchBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    public static int text(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }
    public static int muted(boolean dark) { return dark ? DARK_MUTED : LIGHT_MUTED; }
    public static int icon(boolean dark) { return dark ? DARK_ICON : LIGHT_ICON; }
    public static int iconDisabled(boolean dark) { return dark ? DARK_ICON_DISABLED : LIGHT_ICON_DISABLED; }
    public static int accent(boolean dark) { return dark ? DARK_ACCENT : LIGHT_ACCENT; }
    public static int menuItemBg(boolean dark) { return dark ? DARK_MENU_ITEM_BG : LIGHT_MENU_ITEM_BG; }
    public static int cardItemBg(boolean dark) { return menuItemBg(dark); }

    public static int dp(Context context, float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable pillBg(Context context, int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != 0) {
            gd.setStroke(dp(context, 1), strokeColor);
        }
        return gd;
    }

    public static GradientDrawable cardBorderBg(Context context, int bgColor, int borderColor, float strokeWidthDp, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(context, radiusDp));
        if (borderColor != 0 && strokeWidthDp > 0) {
            gd.setStroke(dp(context, strokeWidthDp), borderColor);
        }
        return gd;
    }

    public static GradientDrawable topRoundedSheetBg(Context context, int bgColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        float r = dp(context, radiusDp);
        gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return gd;
    }
}
