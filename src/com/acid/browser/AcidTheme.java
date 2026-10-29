package com.acid.browser;

import android.app.AlertDialog;
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

    // Night/Dark Theme Colors (True OLED Black, zero screen emission)
    public static final int DARK_BG = Color.parseColor("#000000");
    public static final int DARK_BAR = Color.parseColor("#000000");
    public static final int DARK_BORDER = Color.parseColor("#1C1C1E");
    public static final int DARK_SEARCH_BG = Color.parseColor("#121212");
    public static final int DARK_SEARCH_BORDER = Color.parseColor("#27272A");
    public static final int DARK_TEXT = Color.parseColor("#EDEDED");
    public static final int DARK_MUTED = Color.parseColor("#71717A");
    public static final int DARK_ICON = Color.parseColor("#D4D4D8");
    public static final int DARK_ICON_DISABLED = Color.parseColor("#3F3F46");
    public static final int DARK_ACCENT = Color.parseColor("#00FF88");
    public static final int DARK_MENU_ITEM_BG = Color.parseColor("#121212");

    // Accent & Status Colors
    public static final int COLOR_AMBER = Color.parseColor("#F59E0B");
    public static final int COLOR_AMBER_ALERT = Color.parseColor("#FFB300");
    public static final int COLOR_BLUE = Color.parseColor("#0284C7");
    public static final int COLOR_RED = Color.parseColor("#EF4444");
    public static final int COLOR_GREEN = Color.parseColor("#10B981");
    public static final int COLOR_PURPLE = Color.parseColor("#8B5CF6");
    public static final int COLOR_ORANGE = Color.parseColor("#F97316");
    public static final int COLOR_CODE_SUCCESS = Color.parseColor("#A7F3D0");

    // ---------------------------------------------------------------------
    // Semantic surfaces. Each role exists in a light and a dark variant so a
    // component never has to inline a hex literal to get a correct dark mode.
    // ---------------------------------------------------------------------

    /** Modal scrim behind bottom sheets and popups. */
    public static final int SCRIM = Color.parseColor("#80000000");
    /** Takeover banner background (deliberately light in both themes). */
    public static final int TAKEOVER_BG = Color.parseColor("#FFFBEB");
    /** Floating HUD label background above the web view. */
    public static final int OVERLAY_LABEL_BG = Color.parseColor("#E60A0E14");

    // Subtle card on top of the page background (search-bar tint in light mode)
    private static final int LIGHT_SUBTLE = Color.parseColor("#F1F5F9");
    private static final int DARK_SUBTLE = Color.parseColor("#1E293B");

    // Card that reads as "raised" above the page (pure white in light mode)
    private static final int LIGHT_RAISED = Color.parseColor("#FFFFFF");
    private static final int DARK_RAISED = Color.parseColor("#1E293B");

    // Blue-tinted "thinking" card used by the agent transcript
    private static final int LIGHT_THINKING = Color.parseColor("#F1F5F9");
    private static final int DARK_THINKING = Color.parseColor("#162032");

    // Error bubble
    private static final int LIGHT_ERROR_BG = Color.parseColor("#FEF2F2");
    private static final int DARK_ERROR_BG = Color.parseColor("#450A0A");

    // Selected row / chip highlight
    private static final int LIGHT_SELECTED = Color.parseColor("#E0F2FE");
    private static final int DARK_SELECTED = Color.parseColor("#0C4A6E");

    // Floating agent HUD panel and dark code panels
    public static final int LIGHT_PANEL_BG = Color.parseColor("#F1F5F9");
    public static final int DARK_PANEL_BG = Color.parseColor("#0F172A");
    public static final int LIGHT_PANEL_BORDER = Color.parseColor("#64748B");
    public static final int DARK_PANEL_BORDER = Color.parseColor("#334155");

    // Markdown code blocks
    private static final int LIGHT_CODE_BLOCK_BG = Color.parseColor("#1E293B");
    private static final int DARK_CODE_BLOCK_BG = Color.parseColor("#090D16");
    private static final int LIGHT_CODE_BLOCK_BORDER = Color.parseColor("#334155");
    private static final int DARK_CODE_BLOCK_BORDER = Color.parseColor("#1E293B");
    private static final int LIGHT_INLINE_CODE_BG = Color.parseColor("#E2E8F0");
    private static final int DARK_INLINE_CODE_BG = Color.parseColor("#1E293B");
    private static final int LIGHT_INLINE_CODE_FG = Color.parseColor("#0369A1");
    public static final int DARK_INLINE_CODE_FG = Color.parseColor("#38BDF8");
    private static final int LIGHT_CODE_TEXT = Color.parseColor("#334155");
    public static final int DARK_CODE_TEXT = Color.parseColor("#E2E8F0");

    // Secondary text (a step lighter than `muted` in dark mode)
    public static final int DARK_TEXT_SECONDARY = Color.parseColor("#94A3B8");

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

    /** Drag handle / inactive page dot. */
    public static int handle(boolean dark) { return dark ? DARK_BORDER : LIGHT_ICON_DISABLED; }
    /** Subtle card sitting directly on the page background. */
    public static int subtle(boolean dark) { return dark ? DARK_SUBTLE : LIGHT_SUBTLE; }
    /** Card that should read as raised above the page. */
    public static int raised(boolean dark) { return dark ? DARK_RAISED : LIGHT_RAISED; }
    public static int thinkingBg(boolean dark) { return dark ? DARK_THINKING : LIGHT_THINKING; }
    public static int thinkingBorder(boolean dark) { return dark ? DARK_SUBTLE : LIGHT_SEARCH_BORDER; }
    public static int errorBg(boolean dark) { return dark ? DARK_ERROR_BG : LIGHT_ERROR_BG; }
    public static int selectedBg(boolean dark) { return dark ? DARK_SELECTED : LIGHT_SELECTED; }
    public static int panelBg(boolean dark) { return dark ? DARK_PANEL_BG : LIGHT_PANEL_BG; }
    public static int panelBorder(boolean dark) { return dark ? DARK_PANEL_BORDER : LIGHT_PANEL_BORDER; }
    public static int raisedBorder(boolean dark) { return dark ? DARK_PANEL_BORDER : LIGHT_SEARCH_BORDER; }
    public static int subtleBorder(boolean dark) { return dark ? DARK_PANEL_BORDER : LIGHT_ICON_DISABLED; }
    public static int codeBlockBg(boolean dark) { return dark ? DARK_CODE_BLOCK_BG : LIGHT_CODE_BLOCK_BG; }
    public static int codeBlockBorder(boolean dark) { return dark ? DARK_CODE_BLOCK_BORDER : LIGHT_CODE_BLOCK_BORDER; }
    public static int inlineCodeBg(boolean dark) { return dark ? DARK_INLINE_CODE_BG : LIGHT_INLINE_CODE_BG; }
    public static int inlineCodeFg(boolean dark) { return dark ? DARK_INLINE_CODE_FG : LIGHT_INLINE_CODE_FG; }
    public static int codeText(boolean dark) { return dark ? DARK_CODE_TEXT : LIGHT_CODE_TEXT; }
    public static int markdownText(boolean dark) { return dark ? LIGHT_SUBTLE : LIGHT_TEXT; }
    public static int markdownMuted(boolean dark) { return dark ? DARK_TEXT_SECONDARY : LIGHT_MUTED; }
    /** Popup / context-menu surface. */
    public static int popupBg(boolean dark) { return dark ? DARK_SEARCH_BG : LIGHT_BG; }
    public static int popupBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    /** Popup body text: #F1F5F9 in dark, #1E293B in light (one step softer than body text). */
    public static int popupText(boolean dark) { return dark ? LIGHT_SUBTLE : DARK_SUBTLE; }
    /** Card surface used by settings-style lists: #1E293B in dark, #F8FAFC in light. */
    public static int cardSurface(boolean dark) { return dark ? DARK_RAISED : LIGHT_MENU_ITEM_BG; }
    public static int pressedBg(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BG; }
    /** Header of the tabs overview grid. */
    public static int overviewHeaderBg(boolean dark) { return dark ? DARK_SEARCH_BG : LIGHT_MENU_ITEM_BG; }

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

    public static int dialogTheme(boolean dark) {
        return dark ? AlertDialog.THEME_DEVICE_DEFAULT_DARK : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT;
    }

    public static AlertDialog.Builder dialogBuilder(Context context, boolean dark) {
        return new AlertDialog.Builder(context, dialogTheme(dark));
    }
}
