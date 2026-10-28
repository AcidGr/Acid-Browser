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
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bottom Pop-up Menu Panel ("功能" - Modal Bottom Sheet).
 * Displays an 8-card grid: Night Mode, Incognito Mode, Bookmarks, History,
 * Desktop Mode, Downloads, Acid Agent, Model Config.
 */
public class BrowserMenuSheet extends FrameLayout {

    public interface MenuActionListener {
        void onToggleDarkMode();
        void onToggleIncognitoMode();
        void onOpenBookmarks();
        void onOpenHistory();
        void onToggleDesktopMode();
        void onOpenDownloads();
        void onOpenAgentFloatingWindow();
        void onOpenModelProviderConfig();
    }

    public static class MenuCardItem extends LinearLayout {
        public final AcidIcons.SvgIconView iconView;
        public final TextView titleView;
        public final TextView descView;

        public MenuCardItem(Context context, int iconId, String title, String desc,
                            int iconColor, int textColor, int mutedColor, int cardBgColor,
                            OnClickListener listener) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            float d = context.getResources().getDisplayMetrics().density;
            int padH = Math.round(6 * d);
            int padV = Math.round(10 * d);
            setPadding(padH, padV, padH, padV);

            applyCardBg(cardBgColor);

            iconView = new AcidIcons.SvgIconView(context, iconId, iconColor, 20);
            addView(iconView);

            titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setGravity(Gravity.CENTER);
            LayoutParams tLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = Math.round(5 * d);
            addView(titleView, tLp);

            descView = new TextView(context);
            descView.setText(desc);
            descView.setTextColor(mutedColor);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            descView.setSingleLine(true);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            descView.setGravity(Gravity.CENTER);
            LayoutParams dLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = Math.round(2 * d);
            addView(descView, dLp);

            if (listener != null) {
                setOnClickListener(listener);
            }
        }

        private void applyCardBg(int cardBgColor) {
            float d = getContext().getResources().getDisplayMetrics().density;
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(cardBgColor);
            gd.setCornerRadius(10 * d);
            setBackground(gd);
        }

        public void update(int iconId, String title, String desc, int iconColor, int textColor, int mutedColor, int cardBgColor) {
            iconView.setIcon(iconId);
            iconView.setColor(iconColor);
            titleView.setText(title);
            titleView.setTextColor(textColor);
            descView.setText(desc);
            descView.setTextColor(mutedColor);
            applyCardBg(cardBgColor);
        }
    }

    private final View mMenuScrim;
    private final LinearLayout mMenuCard;
    private final View mHandle;
    private final TextView mMenuTitle;
    private final AcidIcons.SvgIconView mBtnMenuClose;

    private final MenuCardItem mMenuItemNight;
    private final MenuCardItem mMenuItemIncognito;
    private final MenuCardItem mMenuItemBookmark;
    private final MenuCardItem mMenuItemHistory;
    private final MenuCardItem mMenuItemDesktop;
    private final MenuCardItem mMenuItemDownload;
    private final MenuCardItem mMenuItemAgent;
    private final MenuCardItem mMenuItemModelConfig;

    private final MenuActionListener mListener;
    private boolean mIsDarkMode = false;

    public BrowserMenuSheet(Context context, MenuActionListener listener) {
        super(context);
        mListener = listener;
        setVisibility(View.GONE);

        // 1. Semi-transparent scrim
        mMenuScrim = new View(context);
        mMenuScrim.setBackgroundColor(Color.parseColor("#80000000"));
        mMenuScrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        addView(mMenuScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Bottom rounded card
        mMenuCard = new LinearLayout(context);
        mMenuCard.setOrientation(LinearLayout.VERTICAL);
        int padH = AcidTheme.dp(context, 14);
        int padTop = AcidTheme.dp(context, 10);
        int padBottom = AcidTheme.dp(context, 18);
        mMenuCard.setPadding(padH, padTop, padH, padBottom);

        // Top drag handle
        mHandle = new View(context);
        mHandle.setBackground(AcidTheme.pillBg(context, Color.parseColor("#CBD5E1"), 0, 2));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(AcidTheme.dp(context, 36), AcidTheme.dp(context, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = AcidTheme.dp(context, 10);
        mMenuCard.addView(mHandle, hLp);

        // Title row with close button ("功能")
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        mMenuTitle = new TextView(context);
        mMenuTitle.setText("功能");
        mMenuTitle.setTextColor(AcidTheme.LIGHT_TEXT);
        mMenuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mMenuTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(mMenuTitle, titleLp);

        LinearLayout closeBox = new LinearLayout(context);
        closeBox.setGravity(Gravity.CENTER);
        int padClose = AcidTheme.dp(context, 6);
        closeBox.setPadding(padClose, padClose, padClose, padClose);
        mBtnMenuClose = new AcidIcons.SvgIconView(context, AcidIcons.ICON_CLOSE, AcidTheme.LIGHT_MUTED, 18);
        closeBox.addView(mBtnMenuClose);
        closeBox.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        titleRow.addView(closeBox);
        mMenuCard.addView(titleRow);

        // Row 1: Night, Incognito, Bookmarks, History
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, AcidTheme.dp(context, 12), 0, AcidTheme.dp(context, 6));

        mMenuItemNight = new MenuCardItem(
            context, AcidIcons.ICON_MOON, "夜间模式", "已关闭",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onToggleDarkMode();
                }
            }
        );
        LinearLayout.LayoutParams itemLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp1.rightMargin = AcidTheme.dp(context, 4);
        row1.addView(mMenuItemNight, itemLp1);

        mMenuItemIncognito = new MenuCardItem(
            context, AcidIcons.ICON_INCOGNITO, "无痕模式", "已关闭",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onToggleIncognitoMode();
                }
            }
        );
        LinearLayout.LayoutParams itemLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp2.leftMargin = AcidTheme.dp(context, 2);
        itemLp2.rightMargin = AcidTheme.dp(context, 2);
        row1.addView(mMenuItemIncognito, itemLp2);

        mMenuItemBookmark = new MenuCardItem(
            context, AcidIcons.ICON_BOOKMARK, "书签收藏", "快速直达",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenBookmarks();
                }
            }
        );
        LinearLayout.LayoutParams itemLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp3.leftMargin = AcidTheme.dp(context, 2);
        itemLp3.rightMargin = AcidTheme.dp(context, 2);
        row1.addView(mMenuItemBookmark, itemLp3);

        mMenuItemHistory = new MenuCardItem(
            context, AcidIcons.ICON_HISTORY, "历史浏览", "足迹记录",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenHistory();
                }
            }
        );
        LinearLayout.LayoutParams itemLp4 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp4.leftMargin = AcidTheme.dp(context, 4);
        row1.addView(mMenuItemHistory, itemLp4);
        mMenuCard.addView(row1);

        // Row 2: Desktop, Downloads, Agent, Model Config
        LinearLayout row2 = new LinearLayout(context);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, AcidTheme.dp(context, 6), 0, AcidTheme.dp(context, 6));

        mMenuItemDesktop = new MenuCardItem(
            context, AcidIcons.ICON_DESKTOP, "电脑模式", "切换视口",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onToggleDesktopMode();
                }
            }
        );
        LinearLayout.LayoutParams itemLp5 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp5.rightMargin = AcidTheme.dp(context, 4);
        row2.addView(mMenuItemDesktop, itemLp5);

        mMenuItemDownload = new MenuCardItem(
            context, AcidIcons.ICON_DOWNLOAD, "下载管理", "任务查看",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenDownloads();
                }
            }
        );
        LinearLayout.LayoutParams itemLp6 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp6.leftMargin = AcidTheme.dp(context, 2);
        itemLp6.rightMargin = AcidTheme.dp(context, 2);
        row2.addView(mMenuItemDownload, itemLp6);

        mMenuItemAgent = new MenuCardItem(
            context, AcidIcons.ICON_AGENT, "Acid Agent", "悬浮对话窗",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenAgentFloatingWindow();
                }
            }
        );
        LinearLayout.LayoutParams itemLp7 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp7.leftMargin = AcidTheme.dp(context, 2);
        itemLp7.rightMargin = AcidTheme.dp(context, 2);
        row2.addView(mMenuItemAgent, itemLp7);

        mMenuItemModelConfig = new MenuCardItem(
            context, AcidIcons.ICON_MODEL, "模型配置", "供应商管理",
            AcidTheme.LIGHT_ACCENT, AcidTheme.LIGHT_TEXT, AcidTheme.LIGHT_MUTED, AcidTheme.LIGHT_MENU_ITEM_BG,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenModelProviderConfig();
                }
            }
        );
        LinearLayout.LayoutParams itemLp8 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemLp8.leftMargin = AcidTheme.dp(context, 4);
        row2.addView(mMenuItemModelConfig, itemLp8);
        mMenuCard.addView(row2);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        );
        addView(mMenuCard, cardLp);

        applyTheme(false);
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    public void show() {
        setVisibility(View.VISIBLE);
    }

    public void dismiss() {
        setVisibility(View.GONE);
    }

    public void toggle() {
        if (isShowing()) {
            dismiss();
        } else {
            show();
        }
    }

    public void updateCards(boolean isDarkMode, boolean isIncognito, boolean isDesktop, int providerCount, int modelCount) {
        mIsDarkMode = isDarkMode;
        int iconCol = AcidTheme.accent(isDarkMode);
        int textCol = AcidTheme.text(isDarkMode);
        int mutedCol = AcidTheme.muted(isDarkMode);
        int itemBg = AcidTheme.menuItemBg(isDarkMode);

        mMenuItemNight.update(
            isDarkMode ? AcidIcons.ICON_SUN : AcidIcons.ICON_MOON,
            "夜间模式",
            isDarkMode ? "已开启" : "已关闭",
            iconCol, textCol, mutedCol, itemBg
        );

        mMenuItemIncognito.update(
            AcidIcons.ICON_INCOGNITO,
            "无痕模式",
            isIncognito ? "已开启" : "已关闭",
            isIncognito ? AcidTheme.COLOR_PURPLE : iconCol, textCol, mutedCol, itemBg
        );

        mMenuItemDesktop.update(
            isDesktop ? AcidIcons.ICON_MOBILE : AcidIcons.ICON_DESKTOP,
            isDesktop ? "切换手机端" : "切换电脑端",
            isDesktop ? "当前: 1280px" : "当前: 手机版",
            iconCol, textCol, mutedCol, itemBg
        );

        mMenuItemBookmark.update(AcidIcons.ICON_BOOKMARK, "书签收藏", "快速直达", iconCol, textCol, mutedCol, itemBg);
        mMenuItemHistory.update(AcidIcons.ICON_HISTORY, "历史浏览", "足迹记录", iconCol, textCol, mutedCol, itemBg);
        mMenuItemDownload.update(AcidIcons.ICON_DOWNLOAD, "下载管理", "任务查看", iconCol, textCol, mutedCol, itemBg);
        mMenuItemAgent.update(AcidIcons.ICON_AGENT, "Acid Agent", "悬浮对话窗", iconCol, textCol, mutedCol, itemBg);

        int pCount = Math.max(1, providerCount);
        int mCount = Math.max(1, modelCount);
        mMenuItemModelConfig.update(
            AcidIcons.ICON_MODEL,
            "模型配置",
            pCount + " 供应商 · " + mCount + " 模型",
            iconCol, textCol, mutedCol, itemBg
        );
    }

    public void applyTheme(boolean isDark) {
        mIsDarkMode = isDark;
        int bar = AcidTheme.bar(isDark);
        int text = AcidTheme.text(isDark);
        int muted = AcidTheme.muted(isDark);

        mMenuCard.setBackground(AcidTheme.topRoundedSheetBg(getContext(), bar, 16));
        mMenuTitle.setTextColor(text);
        mBtnMenuClose.setColor(muted);
        mHandle.setBackground(AcidTheme.pillBg(
            getContext(),
            isDark ? AcidTheme.DARK_BORDER : Color.parseColor("#CBD5E1"),
            0,
            2
        ));
    }
}
