package com.acid.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
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
import android.widget.Toast;

/**
 * Full-featured Settings Modal Sheet ("设置" - AcidSettingsSheet).
 * Provides structured categories:
 *  1. 通用浏览 (Search engine, Homepage, Clear data, Clear on exit)
 *  2. AI 与自动化 (Model providers, MCP Server, Agent floating window)
 *  3. 防护与扩展 (AdBlock, Userscript manager)
 *  4. 隐私与高级 (Web debugging, Do Not Track, Block images)
 *  5. 关于 (About & update checks)
 */
public class BrowserSettingsSheet extends AcidBottomSheetView {

    public interface OnSettingsActionListener {
        void onOpenModelConfig();
        void onToggleMcpServer();
        void onOpenAgentFloatingWindow();
        void onOpenAdBlock();
        void onOpenUserscripts();
        void onOpenAbout();
        void onClearDataRequested(boolean clearCache, boolean clearCookies, boolean clearHistory, boolean clearStorage);
        void onSearchEngineChanged(int index, String customUrl);
        void onHomeUrlChanged(int type, String customUrl);
        void onWebDebuggingChanged(boolean enabled);
        void onDoNotTrackChanged(boolean enabled);
        void onBlockImagesChanged(boolean enabled);
    }

    private final BrowserDataStore mDataStore;
    private final OnSettingsActionListener mListener;
    private boolean mIsDarkMode = false;
    private boolean mIsMcpRunning = false;

    public BrowserSettingsSheet(Context context, BrowserDataStore dataStore, OnSettingsActionListener listener) {
        super(context);
        mDataStore = dataStore;
        mListener = listener;
    }

    public void setMcpRunning(boolean running) {
        mIsMcpRunning = running;
    }

    @Override
    public void applyTheme(boolean isDark) {
        super.applyTheme(isDark);
        mIsDarkMode = isDark;
    }

    public void showSettings() {
        setTitle("设置");
        renderContent();
        show();
    }

    public void refresh() {
        if (isShowing()) {
            renderContent();
        }
    }

    public void renderContent() {
        LinearLayout container = getContentContainer();
        container.removeAllViews();
        Context ctx = getContext();

        int textColor = AcidTheme.text(mIsDarkMode);
        int mutedColor = AcidTheme.muted(mIsDarkMode);
        int accentColor = AcidTheme.accent(mIsDarkMode);
        int cardBg = AcidTheme.cardSurface(mIsDarkMode);
        int border = AcidTheme.subtleBorder(mIsDarkMode);
        int divider = AcidTheme.border(mIsDarkMode);

        // ================= SECTION 1: 通用浏览 =================
        addSectionHeader(container, "通用浏览", mutedColor);
        LinearLayout cardGeneral = createCardLayout(ctx, cardBg, border);

        // 1.1 搜索引擎
        int seIdx = mDataStore.getSearchEngineIndex();
        String seName = (seIdx >= 0 && seIdx < BrowserDataStore.SEARCH_ENGINE_NAMES.length) ?
            BrowserDataStore.SEARCH_ENGINE_NAMES[seIdx] : "Google";
        if (seIdx == BrowserDataStore.SEARCH_CUSTOM && !TextUtils.isEmpty(mDataStore.getCustomSearchEngineUrl())) {
            seName = "自定义 (" + mDataStore.getCustomSearchEngineUrl() + ")";
        }
        addClickableItem(cardGeneral, AcidIcons.ICON_SEARCH, "搜索引擎", seName, textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchEngineDialog();
            }
        });
        addDivider(cardGeneral, divider);

        // 1.2 主页设置
        int homeType = mDataStore.getHomeType();
        String homeDesc = (homeType >= 0 && homeType < BrowserDataStore.HOME_TYPE_NAMES.length) ?
            BrowserDataStore.HOME_TYPE_NAMES[homeType] : "Acid 默认首页";
        if (homeType == BrowserDataStore.HOME_CUSTOM && !TextUtils.isEmpty(mDataStore.getCustomHomeUrl())) {
            homeDesc = "自定义 (" + mDataStore.getCustomHomeUrl() + ")";
        }
        addClickableItem(cardGeneral, AcidIcons.ICON_GLOBE, "主页设置", homeDesc, textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showHomepageDialog();
            }
        });
        addDivider(cardGeneral, divider);

        // 1.3 清除浏览数据
        addClickableItem(cardGeneral, AcidIcons.ICON_TRASH, "清除浏览数据", "清理缓存、Cookie、足迹与本地存储", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showClearDataDialog();
            }
        });
        addDivider(cardGeneral, divider);

        // 1.4 退出时自动清理
        addSwitchItem(cardGeneral, AcidIcons.ICON_SHIELD, "退出时自动清理", "退出浏览器时自动清理缓存与 Cookie",
            mDataStore.isClearOnExit(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setClearOnExit(isChecked);
                }
            }
        );
        container.addView(cardGeneral);

        // ================= SECTION 2: AI 与自动化 =================
        addSectionHeader(container, "AI 与自动化", mutedColor);
        LinearLayout cardAi = createCardLayout(ctx, cardBg, border);

        // 2.1 大模型配置
        addClickableItem(cardAi, AcidIcons.ICON_MODEL, "模型服务商与 API", "配置 DeepSeek、OpenAI、Claude 等", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenModelConfig();
            }
        });
        addDivider(cardAi, divider);

        // 2.2 MCP 远程控制服务
        addSwitchItem(cardAi, AcidIcons.ICON_TERMINAL, "MCP 远程自动化服务",
            mIsMcpRunning ? "服务运行中 (HTTP 8765)" : "服务已停止",
            mIsMcpRunning, textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (mListener != null) mListener.onToggleMcpServer();
                }
            }
        );
        addDivider(cardAi, divider);

        // 2.3 Agent 悬浮助手窗口
        addClickableItem(cardAi, AcidIcons.ICON_AGENT, "Acid Agent 悬浮助手", "悬浮呼出 AI 网页助手与多轮对话", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenAgentFloatingWindow();
            }
        });
        container.addView(cardAi);

        // ================= SECTION 3: 防护与扩展 =================
        addSectionHeader(container, "防护与扩展", mutedColor);
        LinearLayout cardExt = createCardLayout(ctx, cardBg, border);

        // 3.1 广告拦截
        boolean adEnabled = mDataStore.isAdBlockEnabled();
        int blockedCount = mDataStore.getAdBlockBlockedCount();
        String adSub = (adEnabled ? "已启用" : "已暂停") + " · 已累计拦截 " + blockedCount + " 次广告";
        addClickableItem(cardExt, AcidIcons.ICON_ADBLOCK, "广告与隐私追踪拦截", adSub, textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenAdBlock();
            }
        });
        addDivider(cardExt, divider);

        // 3.2 脚本管理
        addClickableItem(cardExt, AcidIcons.ICON_FILE, "脚本管理 (Userscripts)", "用户扩展与特定网页脚本增强", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenUserscripts();
            }
        });
        container.addView(cardExt);

        // ================= SECTION 4: 隐私与高级 =================
        addSectionHeader(container, "隐私与高级", mutedColor);
        LinearLayout cardAdv = createCardLayout(ctx, cardBg, border);

        // 4.1 允许网页调试
        addSwitchItem(cardAdv, AcidIcons.ICON_TERMINAL, "允许网页开发者调试", "启用 Chrome DevTools 远程审查网页",
            mDataStore.isWebDebuggingEnabled(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setWebDebuggingEnabled(isChecked);
                    if (mListener != null) mListener.onWebDebuggingChanged(isChecked);
                }
            }
        );
        addDivider(cardAdv, divider);

        // 4.2 Do Not Track
        addSwitchItem(cardAdv, AcidIcons.ICON_INCOGNITO, "发送「不跟踪」请求 (DNT)", "向所有访问网站请求保护隐私",
            mDataStore.isDoNotTrackEnabled(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setDoNotTrackEnabled(isChecked);
                    if (mListener != null) mListener.onDoNotTrackChanged(isChecked);
                }
            }
        );
        addDivider(cardAdv, divider);

        // 4.3 无图省流模式
        addSwitchItem(cardAdv, AcidIcons.ICON_FILE_IMAGE, "无图省流模式", "不自动加载图片以节省网络流量",
            mDataStore.isBlockImagesEnabled(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setBlockImagesEnabled(isChecked);
                    if (mListener != null) mListener.onBlockImagesChanged(isChecked);
                }
            }
        );
        container.addView(cardAdv);

        // ================= SECTION 5: 关于 =================
        addSectionHeader(container, "关于与系统", mutedColor);
        LinearLayout cardAbout = createCardLayout(ctx, cardBg, border);
        addClickableItem(cardAbout, AcidIcons.ICON_INFO, "关于 Acid Browser", "版本 0.4.3 · 检查 OTA 更新", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenAbout();
            }
        });
        container.addView(cardAbout);
    }

    private void addSectionHeader(LinearLayout container, String title, int color) {
        Context ctx = getContext();
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = AcidTheme.dp(ctx, 12);
        lp.bottomMargin = AcidTheme.dp(ctx, 6);
        lp.leftMargin = AcidTheme.dp(ctx, 4);
        container.addView(tv, lp);
    }

    private LinearLayout createCardLayout(Context ctx, int cardBg, int border) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, border, 1, 12));
        card.setPadding(0, AcidTheme.dp(ctx, 2), 0, AcidTheme.dp(ctx, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = AcidTheme.dp(ctx, 4);
        card.setLayoutParams(lp);
        return card;
    }

    private void addDivider(ViewGroup parent, int color) {
        Context ctx = getContext();
        View div = new View(ctx);
        div.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        );
        lp.leftMargin = AcidTheme.dp(ctx, 44);
        lp.rightMargin = AcidTheme.dp(ctx, 14);
        parent.addView(div, lp);
    }

    private void addClickableItem(ViewGroup card, int iconId, String title, String subtitle,
                                  int textColor, int mutedColor, OnClickListener listener) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 14);
        int padV = AcidTheme.dp(ctx, 10);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);

        AcidIcons.SvgIconView iconView = new AcidIcons.SvgIconView(ctx, iconId, mutedColor, 20);
        row.addView(iconView);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 12);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(textColor);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tvTitle);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextColor(mutedColor);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subLp.topMargin = AcidTheme.dp(ctx, 2);
            textCol.addView(tvSub, subLp);
        }
        row.addView(textCol, tLp);

        AcidIcons.SvgIconView arrow = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_FORWARD, mutedColor, 14);
        row.addView(arrow);

        if (listener != null) {
            row.setOnClickListener(listener);
        }
        card.addView(row);
    }

    private void addSwitchItem(ViewGroup card, int iconId, String title, String subtitle,
                               boolean isChecked, int textColor, int mutedColor, int accentColor,
                               final CompoundButton.OnCheckedChangeListener listener) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 14);
        int padV = AcidTheme.dp(ctx, 10);
        row.setPadding(padH, padV, padH, padV);

        AcidIcons.SvgIconView iconView = new AcidIcons.SvgIconView(ctx, iconId, mutedColor, 20);
        row.addView(iconView);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 12);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(textColor);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tvTitle);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextColor(mutedColor);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subLp.topMargin = AcidTheme.dp(ctx, 2);
            textCol.addView(tvSub, subLp);
        }
        row.addView(textCol, tLp);

        final Switch sw = new Switch(ctx);
        sw.setChecked(isChecked);
        if (Build.VERSION.SDK_INT >= 23) {
            sw.setThumbTintList(ColorStateList.valueOf(isChecked ? accentColor : mutedColor));
        }
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                if (Build.VERSION.SDK_INT >= 23) {
                    sw.setThumbTintList(ColorStateList.valueOf(checked ? accentColor : mutedColor));
                }
                if (listener != null) {
                    listener.onCheckedChanged(buttonView, checked);
                }
            }
        });
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });

        row.addView(sw);
        card.addView(row);
    }

    // ================= INTERACTIVE DIALOGS =================

    private void showSearchEngineDialog() {
        Context ctx = getContext();
        final int currentIdx = mDataStore.getSearchEngineIndex();
        final String[] items = BrowserDataStore.SEARCH_ENGINE_NAMES;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("选择默认搜索引擎");
        builder.setSingleChoiceItems(items, currentIdx, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == BrowserDataStore.SEARCH_CUSTOM) {
                    dialog.dismiss();
                    showCustomSearchEngineInputDialog();
                } else {
                    mDataStore.setSearchEngineIndex(which);
                    renderContent();
                    dialog.dismiss();
                    if (mListener != null) {
                        mListener.onSearchEngineChanged(which, "");
                    }
                    Toast.makeText(ctx, "默认搜索引擎已切换为: " + items[which], Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showCustomSearchEngineInputDialog() {
        Context ctx = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("自定义搜索引擎 URL");

        final EditText et = new EditText(ctx);
        et.setHint("如: https://example.com/search?q=%s");
        et.setText(mDataStore.getCustomSearchEngineUrl());
        int pad = AcidTheme.dp(ctx, 16);
        et.setPadding(pad, pad, pad, pad);
        builder.setView(et);

        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String input = et.getText().toString().trim();
                if (TextUtils.isEmpty(input)) {
                    Toast.makeText(ctx, "自定义 URL 不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                mDataStore.setSearchEngineIndex(BrowserDataStore.SEARCH_CUSTOM);
                mDataStore.setCustomSearchEngineUrl(input);
                renderContent();
                if (mListener != null) {
                    mListener.onSearchEngineChanged(BrowserDataStore.SEARCH_CUSTOM, input);
                }
                Toast.makeText(ctx, "已设置自定义搜索引擎", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showHomepageDialog() {
        Context ctx = getContext();
        final int currentType = mDataStore.getHomeType();
        final String[] items = BrowserDataStore.HOME_TYPE_NAMES;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("选择主页类型");
        builder.setSingleChoiceItems(items, currentType, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == BrowserDataStore.HOME_CUSTOM) {
                    dialog.dismiss();
                    showCustomHomepageInputDialog();
                } else {
                    mDataStore.setHomeType(which);
                    renderContent();
                    dialog.dismiss();
                    if (mListener != null) {
                        mListener.onHomeUrlChanged(which, "");
                    }
                    Toast.makeText(ctx, "主页已切换为: " + items[which], Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showCustomHomepageInputDialog() {
        Context ctx = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("自定义主页网址");

        final EditText et = new EditText(ctx);
        et.setHint("如: https://www.bing.com");
        et.setText(mDataStore.getCustomHomeUrl());
        int pad = AcidTheme.dp(ctx, 16);
        et.setPadding(pad, pad, pad, pad);
        builder.setView(et);

        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String input = et.getText().toString().trim();
                if (TextUtils.isEmpty(input)) {
                    Toast.makeText(ctx, "主页网址不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                mDataStore.setHomeType(BrowserDataStore.HOME_CUSTOM);
                mDataStore.setCustomHomeUrl(input);
                renderContent();
                if (mListener != null) {
                    mListener.onHomeUrlChanged(BrowserDataStore.HOME_CUSTOM, input);
                }
                Toast.makeText(ctx, "已设置自定义主页", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showClearDataDialog() {
        Context ctx = getContext();
        final String[] options = new String[] {
            "网页缓存 (Cache)",
            "Cookies 与登录状态",
            "历史浏览记录",
            "网页本地存储 (WebStorage)"
        };
        final boolean[] checked = new boolean[] { true, false, true, false };

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("选择要清除的浏览数据");
        builder.setMultiChoiceItems(options, checked, new DialogInterface.OnMultiChoiceClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                checked[which] = isChecked;
            }
        });
        builder.setPositiveButton("立即清除", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mListener != null) {
                    mListener.onClearDataRequested(checked[0], checked[1], checked[2], checked[3]);
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
}
