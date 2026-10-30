package com.acid.browser;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

/**
 * Unified Theme, Typography Scale, Radius Tokens & UI Shape Helper for Acid Browser.
 *
 * <p>Single source of truth for:
 * <ul>
 *   <li>Neutral Day/Night OLED palettes (eliminating mixed warm/cool dark greys)</li>
 *   <li>5-level Typography Hierarchy ({@code SP_H1_SHEET} .. {@code SP_BADGE})</li>
 *   <li>3-tier Corner Radius Scale ({@code RADIUS_SHEET}, {@code RADIUS_CARD}, {@code RADIUS_CONTROL})</li>
 *   <li>Stateful press-feedback drawables ({@link #interactiveBg})</li>
 * </ul>
 */
public final class AcidTheme {

    private static final String TAG = "AcidTheme";

    private AcidTheme() {}

    // =====================================================================
    // 1. Semantic Typography Scale (in SP) — Visually Distinct Roles
    // =====================================================================

    /** L1/L2 BottomSheet top-bar title (17sp Bold). */
    public static final float SP_SHEET_TITLE = 17f;
    /** L3 Modal Dialog title (16sp Bold) — visually subordinate to Sheet Title, above Hero. */
    public static final float SP_DIALOG_TITLE = 16f;
    /** Hero / status card lead headline (15sp Bold) — full 1sp above row items. */
    public static final float SP_HERO = 15f;
    /** Standard list item / settings row title (14sp Bold). */
    public static final float SP_ITEM = 14f;
    /** Body copy, dialog message, input field & button text (13sp Regular/Bold). */
    public static final float SP_BODY = 13f;
    /** Section header placed outside above a card group (12.5sp Bold, above inner field labels). */
    public static final float SP_OVERLINE = 12.5f;
    /** Form field label above inputs & segmented tab label (12sp Bold, muted). */
    public static final float SP_FIELD_LABEL = 12f;
    /** Secondary subtitle, URL, metadata & helper hint (11.5sp Regular, muted). */
    public static final float SP_CAPTION = 11.5f;
    /** Compact status pill, tag & code preview text (10.5sp Bold/Monospace). */
    public static final float SP_BADGE = 10.5f;
    /** Micro badge / unread counter (9.5sp Bold). */
    public static final float SP_MICRO = 9.5f;

    // Legacy compatibility aliases
    public static final float SP_H1_SHEET = SP_SHEET_TITLE;
    public static final float SP_H2_SECTION = SP_OVERLINE;
    public static final float SP_H3_CARD = SP_HERO;
    public static final float SP_H4_ITEM = SP_ITEM;
    public static final float SP_LABEL = SP_FIELD_LABEL;

    // =====================================================================
    // 2. Corner Radius & Layout Geometry Scale (in DP)
    // =====================================================================

    /** Modal containers: BottomSheet top corners, AcidDialog, Floating Agent drawer. */
    public static final float RADIUS_SHEET = 16f;
    /** Grouped cards, context menus, tab cards & hero blocks. */
    public static final float RADIUS_CARD = 12f;
    /** Interactive controls: list rows, input boxes, buttons, menu tiles. */
    public static final float RADIUS_CONTROL = 8f;
    /** Micro badges, inline chips & code blocks. */
    public static final float RADIUS_BADGE = 6f;
    /** Full capsule pills: address bar, filter chips, chat input, indicator dots. */
    public static final float RADIUS_PILL = 999f;

    /** Standard content card horizontal/vertical padding (dp). */
    public static final float CARD_PAD_H_DP = 14f;
    public static final float CARD_PAD_V_DP = 12f;
    public static final float HERO_PAD_DP = 16f;

    /** Standard group row geometry used to derive divider indentation automatically. */
    public static final float ROW_PAD_H_DP = 14f;
    public static final float ROW_PAD_V_DP = 12f;
    public static final float ROW_ICON_BOX_DP = 32f;
    public static final float ROW_ICON_GAP_DP = 12f;
    public static final float ROW_DIVIDER_INDENT_DP = ROW_PAD_H_DP + ROW_ICON_BOX_DP + ROW_ICON_GAP_DP;

    // =====================================================================
    // 3. Day / Light Palette
    // =====================================================================

    public static final int LIGHT_BG = Color.parseColor("#FFFFFF");
    public static final int LIGHT_BAR = Color.parseColor("#FFFFFF");
    public static final int LIGHT_SHEET_BG = Color.parseColor("#FFFFFF");
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

    // =====================================================================
    // 4. Night / Dark Palette (Unified Neutral Carbon on True OLED Black)
    // =====================================================================

    public static final int DARK_BG = Color.parseColor("#000000");
    public static final int DARK_BAR = Color.parseColor("#000000");
    public static final int DARK_SHEET_BG = Color.parseColor("#0B0B0E");
    public static final int DARK_SHEET_STROKE = Color.parseColor("#222228");
    public static final int DARK_BORDER = Color.parseColor("#222228");
    public static final int DARK_SEARCH_BG = Color.parseColor("#141418");
    public static final int DARK_SEARCH_BORDER = Color.parseColor("#2A2A32");
    public static final int DARK_TEXT = Color.parseColor("#EDEDEF");
    public static final int DARK_MUTED = Color.parseColor("#8A8A93");
    public static final int DARK_ICON = Color.parseColor("#D4D4D8");
    public static final int DARK_ICON_DISABLED = Color.parseColor("#3F3F46");
    public static final int DARK_ACCENT = Color.parseColor("#00E68A");
    public static final int DARK_MENU_ITEM_BG = Color.parseColor("#151519");

    // =====================================================================
    // 5. Semantic Accent & Status Colors
    // =====================================================================

    public static final int COLOR_AMBER = Color.parseColor("#F59E0B");
    public static final int COLOR_AMBER_ALERT = Color.parseColor("#FFB300");
    public static final int COLOR_BLUE = Color.parseColor("#0284C7");
    public static final int COLOR_RED = Color.parseColor("#EF4444");
    public static final int COLOR_GREEN = Color.parseColor("#10B981");
    public static final int COLOR_PURPLE = Color.parseColor("#8B5CF6");
    public static final int COLOR_ORANGE = Color.parseColor("#F97316");
    public static final int COLOR_CODE_SUCCESS = Color.parseColor("#A7F3D0");

    /** Modal scrim behind bottom sheets and dialogs. */
    public static final int SCRIM = Color.parseColor("#88000000");
    /** Takeover banner background. */
    public static final int TAKEOVER_BG = Color.parseColor("#FFFBEB");
    public static final int DARK_TAKEOVER_BG = Color.parseColor("#261B06");
    /** Floating HUD label background above the web view. */
    public static final int OVERLAY_LABEL_BG = Color.parseColor("#E60A0E14");

    // Unified neutral surfaces (no blue-slate vs carbon clash in dark mode)
    private static final int LIGHT_SUBTLE = Color.parseColor("#F1F5F9");
    private static final int DARK_SUBTLE = Color.parseColor("#1A1A20");

    private static final int LIGHT_RAISED = Color.parseColor("#FFFFFF");
    private static final int DARK_RAISED = Color.parseColor("#16161B");

    private static final int LIGHT_FORM_INPUT = Color.parseColor("#FFFFFF");
    private static final int DARK_FORM_INPUT = Color.parseColor("#09090C");

    private static final int LIGHT_THINKING = Color.parseColor("#F1F5F9");
    private static final int DARK_THINKING = Color.parseColor("#131822");

    private static final int LIGHT_ERROR_BG = Color.parseColor("#FEF2F2");
    private static final int DARK_ERROR_BG = Color.parseColor("#3B0A0A");

    private static final int LIGHT_SELECTED = Color.parseColor("#E0F2FE");
    private static final int DARK_SELECTED = Color.parseColor("#0C3552");

    private static final int LIGHT_ROW_PRESSED = Color.parseColor("#E2E8F0");
    private static final int DARK_ROW_PRESSED = Color.parseColor("#26262E");

    private static final int[] AVATAR_COLORS = new int[]{
        Color.parseColor("#0284C7"), // sky
        Color.parseColor("#7C3AED"), // violet
        Color.parseColor("#059669"), // emerald
        Color.parseColor("#D97706"), // amber
        Color.parseColor("#DB2777"), // pink
        Color.parseColor("#0891B2"), // cyan
        Color.parseColor("#4F46E5")  // indigo
    };

    // Floating agent HUD panel and code panels
    public static final int LIGHT_PANEL_BG = Color.parseColor("#F8FAFC");
    public static final int DARK_PANEL_BG = Color.parseColor("#0E0E12");
    public static final int LIGHT_PANEL_BORDER = Color.parseColor("#CBD5E1");
    public static final int DARK_PANEL_BORDER = Color.parseColor("#2A2A32");

    // Markdown code blocks & tables
    private static final int LIGHT_CODE_BLOCK_BG = Color.parseColor("#1E293B");
    private static final int DARK_CODE_BLOCK_BG = Color.parseColor("#090C12");
    private static final int LIGHT_CODE_BLOCK_BORDER = Color.parseColor("#334155");
    private static final int DARK_CODE_BLOCK_BORDER = Color.parseColor("#222630");
    private static final int LIGHT_INLINE_CODE_BG = Color.parseColor("#E2E8F0");
    private static final int DARK_INLINE_CODE_BG = Color.parseColor("#1E2430");
    private static final int LIGHT_INLINE_CODE_FG = Color.parseColor("#0369A1");
    public static final int DARK_INLINE_CODE_FG = Color.parseColor("#38BDF8");
    public static final int DARK_CODE_TEXT = Color.parseColor("#E2E8F0");

    public static final int DARK_TEXT_SECONDARY = Color.parseColor("#A1A1AA");

    // =====================================================================
    // 6. Semantic Color Accessors
    // =====================================================================

    public static int bg(boolean dark) { return dark ? DARK_BG : LIGHT_BG; }
    public static int bar(boolean dark) { return dark ? DARK_BAR : LIGHT_BAR; }
    public static int sheetBg(boolean dark) { return dark ? DARK_SHEET_BG : LIGHT_SHEET_BG; }
    public static int sheetStroke(boolean dark) { return dark ? DARK_SHEET_STROKE : LIGHT_BORDER; }
    public static int border(boolean dark) { return dark ? DARK_BORDER : LIGHT_BORDER; }
    public static int searchBg(boolean dark) { return dark ? DARK_SEARCH_BG : LIGHT_SEARCH_BG; }
    public static int searchBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    public static int formInputBg(boolean dark) { return dark ? DARK_FORM_INPUT : LIGHT_FORM_INPUT; }
    public static int formInputBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    public static int text(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }
    public static int muted(boolean dark) { return dark ? DARK_MUTED : LIGHT_MUTED; }
    public static int icon(boolean dark) { return dark ? DARK_ICON : LIGHT_ICON; }
    public static int iconDisabled(boolean dark) { return dark ? DARK_ICON_DISABLED : LIGHT_ICON_DISABLED; }
    public static int accent(boolean dark) { return dark ? DARK_ACCENT : LIGHT_ACCENT; }
    public static int menuItemBg(boolean dark) { return dark ? DARK_MENU_ITEM_BG : LIGHT_MENU_ITEM_BG; }
    public static int cardItemBg(boolean dark) { return menuItemBg(dark); }
    public static int takeoverBg(boolean dark) { return dark ? DARK_TAKEOVER_BG : TAKEOVER_BG; }

    /** Drag handle / inactive page dot. */
    public static int handle(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_ICON_DISABLED; }
    /** Subtle surface sitting inside a card or sheet. */
    public static int subtle(boolean dark) { return dark ? DARK_SUBTLE : LIGHT_SUBTLE; }
    /** Raised card surface. */
    public static int raised(boolean dark) { return dark ? DARK_RAISED : LIGHT_RAISED; }
    public static int thinkingBg(boolean dark) { return dark ? DARK_THINKING : LIGHT_THINKING; }
    public static int thinkingBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    public static int errorBg(boolean dark) { return dark ? DARK_ERROR_BG : LIGHT_ERROR_BG; }
    public static int selectedBg(boolean dark) { return dark ? DARK_SELECTED : LIGHT_SELECTED; }
    public static int panelBg(boolean dark) { return dark ? DARK_PANEL_BG : LIGHT_PANEL_BG; }
    public static int panelBorder(boolean dark) { return dark ? DARK_PANEL_BORDER : LIGHT_PANEL_BORDER; }
    public static int raisedBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    public static int subtleBorder(boolean dark) { return dark ? DARK_BORDER : LIGHT_SEARCH_BORDER; }
    public static int codeBlockBg(boolean dark) { return dark ? DARK_CODE_BLOCK_BG : LIGHT_CODE_BLOCK_BG; }
    public static int codeBlockBorder(boolean dark) { return dark ? DARK_CODE_BLOCK_BORDER : LIGHT_CODE_BLOCK_BORDER; }
    public static int inlineCodeBg(boolean dark) { return dark ? DARK_INLINE_CODE_BG : LIGHT_INLINE_CODE_BG; }
    public static int inlineCodeFg(boolean dark) { return dark ? DARK_INLINE_CODE_FG : LIGHT_INLINE_CODE_FG; }
    public static int markdownText(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }
    public static int markdownMuted(boolean dark) { return dark ? DARK_TEXT_SECONDARY : LIGHT_MUTED; }
    public static int markdownTableBg(boolean dark) { return dark ? DARK_PANEL_BG : LIGHT_BG; }
    public static int markdownTableHeaderBg(boolean dark) { return dark ? DARK_RAISED : LIGHT_MENU_ITEM_BG; }
    public static int markdownTableBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    public static int markdownTableRowDivider(boolean dark) { return dark ? 0x332A2A32 : 0x14000000; }
    public static int markdownTableAltRowBg(boolean dark) { return dark ? 0x0AFFFFFF : 0x04000000; }

    /** Popup / context-menu surface. */
    public static int popupBg(boolean dark) { return dark ? DARK_RAISED : LIGHT_BG; }
    public static int popupBorder(boolean dark) { return dark ? DARK_SEARCH_BORDER : LIGHT_SEARCH_BORDER; }
    public static int popupText(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }

    /** Card surface used by grouped lists and settings cards. */
    public static int cardSurface(boolean dark) { return dark ? DARK_MENU_ITEM_BG : LIGHT_MENU_ITEM_BG; }
    public static int pressedBg(boolean dark) { return dark ? DARK_ROW_PRESSED : LIGHT_ROW_PRESSED; }
    public static int overviewHeaderBg(boolean dark) { return dark ? DARK_MENU_ITEM_BG : LIGHT_MENU_ITEM_BG; }
    public static int rowPressed(boolean dark) { return dark ? DARK_ROW_PRESSED : LIGHT_ROW_PRESSED; }

    public static int switchThumb(boolean dark, boolean checked) {
        if (checked) return accent(dark);
        return dark ? DARK_TEXT_SECONDARY : LIGHT_MUTED;
    }

    public static int switchTrack(boolean dark, boolean checked) {
        if (checked) return softTint(accent(dark), dark ? 95 : 85);
        return dark ? DARK_SEARCH_BORDER : LIGHT_ICON_DISABLED;
    }

    /**
     * Computes high-contrast foreground text/icon color ({@code BLACK} or {@code WHITE})
     * against any solid fill color using perceived luminance.
     */
    public static int onColor(int fillColor) {
        int r = Color.red(fillColor);
        int g = Color.green(fillColor);
        int b = Color.blue(fillColor);
        int yiq = (r * 299 + g * 587 + b * 114) / 1000;
        return yiq >= 145 ? Color.BLACK : Color.WHITE;
    }

    /** Translucent tint background for badges and soft buttons (alpha ~12%). */
    public static int softTint(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int avatarColor(String name) {
        if (name == null || name.isEmpty()) return AVATAR_COLORS[0];
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            hash = 31 * hash + name.charAt(i);
        }
        return AVATAR_COLORS[Math.abs(hash % AVATAR_COLORS.length)];
    }

    // =====================================================================
    // 7. Dimension, Shape & Stateful Drawable Helpers
    // =====================================================================

    public static int dp(Context context, float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    public static float spPx(Context context, float sp) {
        return sp * context.getResources().getDisplayMetrics().scaledDensity;
    }

    public static GradientDrawable pillBg(Context context, int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != 0) {
            gd.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        }
        return gd;
    }

    public static GradientDrawable cardBorderBg(Context context, int bgColor, int borderColor, float strokeWidthDp, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(context, radiusDp));
        if (borderColor != 0 && strokeWidthDp > 0) {
            gd.setStroke(Math.max(1, dp(context, strokeWidthDp)), borderColor);
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

    public static GradientDrawable topRoundedSheetBg(Context context, boolean dark) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(sheetBg(dark));
        float r = dp(context, RADIUS_SHEET);
        gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        if (dark) {
            gd.setStroke(Math.max(1, dp(context, 1)), DARK_SHEET_STROKE);
        }
        return gd;
    }

    /**
     * Stateful background with immediate finger-down highlight.
     */
    public static StateListDrawable interactiveBg(
        Context context,
        int normalFill,
        int pressedFill,
        int strokeColor,
        float radiusDp
    ) {
        GradientDrawable normal = pillBg(context, normalFill, strokeColor, radiusDp);
        GradientDrawable pressed = pillBg(context, pressedFill, strokeColor, radiusDp);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** Standard interactive row surface for lists and cards. */
    public static StateListDrawable interactiveRowBg(Context context, boolean dark, float radiusDp) {
        return interactiveBg(context, menuItemBg(dark), rowPressed(dark), 0, radiusDp);
    }

    /** Darkens or lightens a solid fill colour slightly for button press feedback. */
    public static int adjustPressFill(int color, boolean dark) {
        int a = Color.alpha(color);
        if (a == 0) {
            return dark ? DARK_ROW_PRESSED : LIGHT_ROW_PRESSED;
        }
        float factor = dark ? 1.18f : 0.88f;
        int r = Math.min(255, Math.round(Color.red(color) * factor));
        int g = Math.min(255, Math.round(Color.green(color) * factor));
        int b = Math.min(255, Math.round(Color.blue(color) * factor));
        return Color.argb(a, r, g, b);
    }

    public static String appVersionName(Context context) {
        if (context == null) return BuildInfo.VERSION_NAME;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.isEmpty()) {
                return pi.versionName;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read package versionName", e);
        }
        return BuildInfo.VERSION_NAME;
    }

    public static void hideKeyboard(View anyView) {
        if (anyView == null) return;
        try {
            Context ctx = anyView.getContext();
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                View focused = anyView.findFocus();
                View tokenSource = focused != null ? focused : anyView;
                imm.hideSoftInputFromWindow(tokenSource.getWindowToken(), 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hide soft keyboard", e);
        }
    }

    public static void hideKeyboard(Activity activity) {
        if (activity == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = activity.getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hide soft keyboard from activity", e);
        }
    }
}
