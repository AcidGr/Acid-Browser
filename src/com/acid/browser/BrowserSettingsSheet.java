package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Hierarchical Settings Modal Sheet built on {@link AcidBottomSheetView}'s sub-page stack,
 * {@link AcidWidgets} primitives, and {@link AcidDialog} Tier-3 modals.
 *
 * <p>Eliminates all unstyled native {@code AlertDialog} calls, hardcoded version strings,
 * and duplicated row construction boilerplate.
 */
public class BrowserSettingsSheet extends AcidBottomSheetView {

    public interface OnSettingsActionListener {
        void onOpenModelConfig();
        void onToggleMcpServer();
        void onOpenAgentFloatingWindow();
        void onOpenAdBlock();
        void onOpenUserscripts();
        void onOpenAbout();
        void onClearDataRequested(boolean clearCache, boolean clearCookies, boolean clearHistory, boolean clearStorage, boolean clearWorkspace);
        void onClearWorkspaceOnlyRequested();
        void onSearchEngineChanged(int index, String customUrl);
        void onHomeUrlChanged(int type, String customUrl);
        void onWebDebuggingChanged(boolean enabled);
        void onDoNotTrackChanged(boolean enabled);
        void onBlockImagesChanged(boolean enabled);
    }

    private final BrowserDataStore mDataStore;
    private final OnSettingsActionListener mListener;
    private boolean mIsMcpRunning = false;

    public BrowserSettingsSheet(Context context, BrowserDataStore dataStore, OnSettingsActionListener listener) {
        super(context);
        mDataStore = dataStore;
        mListener = listener;
    }

    public void setMcpRunning(boolean running) {
        mIsMcpRunning = running;
    }

    public void showSettings() {
        setRootPage(buildMainPage());
        show();
    }

    public void refresh() {
        if (isShowing()) {
            refreshActivePage();
        }
    }

    // =========================================================================
    // LEVEL 1: 设置总览 (Root Page)
    // =========================================================================

    private SheetPage buildMainPage() {
        return new SheetPage("设置", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                int blue = AcidTheme.COLOR_BLUE;
                int amber = AcidTheme.COLOR_AMBER;

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "浏览与智能体"));
                LinearLayout group1 = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                group1.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_SETTINGS, accent,
                    "通用与搜索", null,
                    getSearchEngineLabel(), true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { pushPage(buildGeneralPage()); }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, group1, 58);

                group1.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_AGENT, blue,
                    "AI 与自动化", null,
                    mIsMcpRunning ? "MCP 运行中" : null, true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { pushPage(buildAiPage()); }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, group1, 58);

                boolean adBlockOn = AdBlockManager.getInstance(ctx).isEnabled();
                group1.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_ADBLOCK, accent,
                    "防护与扩展", null,
                    adBlockOn ? "已开启" : "已暂停", true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { pushPage(buildProtectionPage()); }
                    }
                ));
                container.addView(group1);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "系统与调试"));
                LinearLayout group2 = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                group2.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, amber,
                    "隐私与高级", null,
                    null, true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { pushPage(buildAdvancedPage()); }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, group2, 58);

                group2.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_INFO, AcidTheme.muted(mIsDarkMode),
                    "关于与配置文件", null,
                    "v" + AcidTheme.appVersionName(ctx), true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { pushPage(buildAboutPage()); }
                    }
                ));
                container.addView(group2);
            }
        });
    }

    // =========================================================================
    // LEVEL 2: 二级设置详情页
    // =========================================================================

    private SheetPage buildGeneralPage() {
        return new SheetPage("通用与搜索", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "地址栏与启动"));
                LinearLayout navCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                navCard.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_SEARCH, accent,
                    "默认搜索引擎", null, getSearchEngineLabel(), true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { showSearchEngineDialog(); }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, navCard, 58);

                navCard.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_HOME, accent,
                    "主页设置", null, getHomeTypeLabel(), true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { showHomepageDialog(); }
                    }
                ));
                container.addView(navCard);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "浏览数据管理"));
                LinearLayout dataCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                dataCard.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_TRASH, AcidTheme.COLOR_RED,
                    "清除浏览数据", null, null, true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { showClearDataDialog(); }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, dataCard, 58);

                dataCard.addView(AcidWidgets.switchRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, accent,
                    "退出时自动清理", null,
                    mDataStore.isClearOnExit(),
                    new AcidWidgets.OnToggleListener() {
                        @Override
                        public void onToggle(boolean isChecked) {
                            mDataStore.setClearOnExit(isChecked);
                        }
                    }
                ));
                container.addView(dataCard);
            }
        });
    }

    private SheetPage buildAiPage() {
        return new SheetPage("AI 与自动化", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                int blue = AcidTheme.COLOR_BLUE;

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "智能体与模型"));
                LinearLayout aiCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                aiCard.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_MODEL, blue,
                    "模型与供应商配置", null, null, true,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (mListener != null) mListener.onOpenModelConfig();
                        }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, aiCard, 58);

                aiCard.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_AGENT, accent,
                    "打开 Agent 对话窗", null, null, true,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismiss();
                            if (mListener != null) mListener.onOpenAgentFloatingWindow();
                        }
                    }
                ));
                container.addView(aiCard);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "远程网关与沙盒存储"));
                LinearLayout mcpCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                mcpCard.addView(AcidWidgets.switchRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_TERMINAL, accent,
                    "本地 MCP 服务", null,
                    mIsMcpRunning,
                    new AcidWidgets.OnToggleListener() {
                        @Override
                        public void onToggle(boolean isChecked) {
                            if (mListener != null) mListener.onToggleMcpServer();
                        }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, mcpCard, 58);

                mcpCard.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_TRASH, AcidTheme.COLOR_RED,
                    "清理 AI 工作区文件", null, null, true,
                    new OnClickListener() {
                        @Override public void onClick(View v) { showClearWorkspaceConfirmDialog(); }
                    }
                ));
                container.addView(mcpCard);
            }
        });
    }

    private SheetPage buildProtectionPage() {
        return new SheetPage("防护与扩展", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                AdBlockManager ab = AdBlockManager.getInstance(ctx);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "内容净化与脚本引擎"));
                LinearLayout card = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                card.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_ADBLOCK, accent,
                    "广告拦截",
                    null,
                    ab.isEnabled() ? ("已拦截 " + ab.getBlockedCount() + " 次") : "已暂停",
                    true,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (mListener != null) mListener.onOpenAdBlock();
                        }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, card, 58);

                card.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_FILE, AcidTheme.COLOR_BLUE,
                    "脚本管理",
                    null,
                    null,
                    true,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (mListener != null) mListener.onOpenUserscripts();
                        }
                    }
                ));
                container.addView(card);
            }
        });
    }

    private SheetPage buildAdvancedPage() {
        return new SheetPage("隐私与高级", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "隐私与省流"));
                LinearLayout privacyCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                privacyCard.addView(AcidWidgets.switchRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_INCOGNITO, accent,
                    "不跟踪 (DNT)", null,
                    mDataStore.isDoNotTrackEnabled(),
                    new AcidWidgets.OnToggleListener() {
                        @Override
                        public void onToggle(boolean isChecked) {
                            mDataStore.setDoNotTrackEnabled(isChecked);
                            if (mListener != null) mListener.onDoNotTrackChanged(isChecked);
                        }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, privacyCard, 58);

                privacyCard.addView(AcidWidgets.switchRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_FILE_IMAGE, AcidTheme.COLOR_AMBER,
                    "无图省流模式", null,
                    mDataStore.isBlockImagesEnabled(),
                    new AcidWidgets.OnToggleListener() {
                        @Override
                        public void onToggle(boolean isChecked) {
                            mDataStore.setBlockImagesEnabled(isChecked);
                            if (mListener != null) mListener.onBlockImagesChanged(isChecked);
                        }
                    }
                ));
                container.addView(privacyCard);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "开发者工具"));
                LinearLayout devCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                devCard.addView(AcidWidgets.switchRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_TERMINAL, AcidTheme.COLOR_BLUE,
                    "网页远程调试", null,
                    mDataStore.isWebDebuggingEnabled(),
                    new AcidWidgets.OnToggleListener() {
                        @Override
                        public void onToggle(boolean isChecked) {
                            mDataStore.setWebDebuggingEnabled(isChecked);
                            if (mListener != null) mListener.onWebDebuggingChanged(isChecked);
                        }
                    }
                ));
                container.addView(devCard);
            }
        });
    }

    private SheetPage buildAboutPage() {
        return new SheetPage("关于与配置文件", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                String ver = "v" + AcidTheme.appVersionName(ctx);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "应用与配置同步"));
                LinearLayout card = AcidWidgets.cardGroup(ctx, mIsDarkMode);

                card.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_INFO, accent,
                    "关于 Acid Browser", null, ver, true,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (mListener != null) mListener.onOpenAbout();
                        }
                    }
                ));
                AcidWidgets.addDivider(ctx, mIsDarkMode, card, 58);

                String filePath = mDataStore.getSettingsJsonFile() != null
                    ? mDataStore.getSettingsJsonFile().getName()
                    : "settings.json";
                card.addView(AcidWidgets.navRow(
                    ctx, mIsDarkMode, AcidIcons.ICON_FILE, AcidTheme.COLOR_BLUE,
                    "设置文件", null, filePath, false,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Toast.makeText(ctx, "工作区配置文件: settings.json (支持实时热重载)", Toast.LENGTH_SHORT).show();
                        }
                    }
                ));
                container.addView(card);
            }
        });
    }

    private String getSearchEngineLabel() {
        int idx = mDataStore.getSearchEngineIndex();
        return (idx >= 0 && idx < BrowserDataStore.SEARCH_ENGINE_NAMES.length)
            ? BrowserDataStore.SEARCH_ENGINE_NAMES[idx]
            : "Google";
    }

    private String getHomeTypeLabel() {
        int type = mDataStore.getHomeType();
        return (type >= 0 && type < BrowserDataStore.HOME_TYPE_NAMES.length)
            ? BrowserDataStore.HOME_TYPE_NAMES[type]
            : "默认";
    }

    // =========================================================================
    // TIER 3: AcidDialog Modals (100% Day/Night Themed)
    // =========================================================================

    private void showSearchEngineDialog() {
        final Context ctx = getContext();
        final int currentIdx = mDataStore.getSearchEngineIndex();
        final String[] items = BrowserDataStore.SEARCH_ENGINE_NAMES;

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("选择默认搜索引擎")
            .setSingleChoiceItems(items, currentIdx, new AcidDialog.OnSingleChoiceListener() {
                @Override
                public void onItemSelected(Dialog dialog, int which) {
                    dialog.dismiss();
                    if (which == BrowserDataStore.SEARCH_CUSTOM) {
                        showCustomSearchEngineInputDialog();
                    } else {
                        mDataStore.setSearchEngineIndex(which);
                        refreshActivePage();
                        if (mListener != null) {
                            mListener.onSearchEngineChanged(which, "");
                        }
                        Toast.makeText(ctx, "默认搜索引擎已切换为: " + items[which], Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showCustomSearchEngineInputDialog() {
        final Context ctx = getContext();
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);

        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "搜索地址模板 (使用 %s 代表搜索词)"));
        final EditText et = AcidWidgets.inputField(
            ctx, mIsDarkMode,
            mDataStore.getCustomSearchEngineUrl(),
            "https://example.com/search?q=%s",
            true
        );
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(et);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("自定义搜索引擎")
            .setCustomView(form)
            .setPositiveButton("保存", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String input = et.getText().toString().trim();
                    if (TextUtils.isEmpty(input)) {
                        Toast.makeText(ctx, "自定义搜索 URL 不能为空", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    mDataStore.setSearchEngineIndex(BrowserDataStore.SEARCH_CUSTOM);
                    mDataStore.setCustomSearchEngineUrl(input);
                    refreshActivePage();
                    if (mListener != null) {
                        mListener.onSearchEngineChanged(BrowserDataStore.SEARCH_CUSTOM, input);
                    }
                    Toast.makeText(ctx, "已保存自定义搜索引擎", Toast.LENGTH_SHORT).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showHomepageDialog() {
        final Context ctx = getContext();
        final int currentType = mDataStore.getHomeType();
        final String[] items = BrowserDataStore.HOME_TYPE_NAMES;

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("选择主页类型")
            .setSingleChoiceItems(items, currentType, new AcidDialog.OnSingleChoiceListener() {
                @Override
                public void onItemSelected(Dialog dialog, int which) {
                    dialog.dismiss();
                    if (which == BrowserDataStore.HOME_CUSTOM) {
                        showCustomHomepageInputDialog();
                    } else {
                        mDataStore.setHomeType(which);
                        refreshActivePage();
                        if (mListener != null) {
                            mListener.onHomeUrlChanged(which, "");
                        }
                        Toast.makeText(ctx, "主页已切换为: " + items[which], Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showCustomHomepageInputDialog() {
        final Context ctx = getContext();
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);

        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "主页网址"));
        final EditText et = AcidWidgets.inputField(
            ctx, mIsDarkMode,
            mDataStore.getCustomHomeUrl(),
            "https://www.bing.com",
            true
        );
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(et);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("自定义主页网址")
            .setCustomView(form)
            .setPositiveButton("保存", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String input = et.getText().toString().trim();
                    if (TextUtils.isEmpty(input)) {
                        Toast.makeText(ctx, "主页网址不能为空", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    mDataStore.setHomeType(BrowserDataStore.HOME_CUSTOM);
                    mDataStore.setCustomHomeUrl(input);
                    refreshActivePage();
                    if (mListener != null) {
                        mListener.onHomeUrlChanged(BrowserDataStore.HOME_CUSTOM, input);
                    }
                    Toast.makeText(ctx, "已保存自定义主页", Toast.LENGTH_SHORT).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showClearWorkspaceConfirmDialog() {
        new AcidDialog(getContext(), mIsDarkMode)
            .setTitle("清理 AI 工作区")
            .setMessage("将清除 Agent 在沙盒中生成的历史报告、抓取数据与临时文件。\n\n安全保护：不会删除 settings.json 配置文件、用户脚本 (userscripts) 与广告拦截规则 (adblock)。")
            .setDangerButton("立即清理", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (mListener != null) {
                        mListener.onClearWorkspaceOnlyRequested();
                    }
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showClearDataDialog() {
        final String[] options = new String[]{
            "网页缓存 (Cache)",
            "Cookies 与网站登录状态",
            "历史浏览记录",
            "网页本地存储 (WebStorage)",
            "AI 工作区临时文件 (保留配置与脚本)"
        };
        final boolean[] checked = new boolean[]{true, false, true, false, false};

        new AcidDialog(getContext(), mIsDarkMode)
            .setTitle("清除浏览数据")
            .setMultiChoiceItems(options, checked)
            .setDangerButton("立即清除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (!checked[0] && !checked[1] && !checked[2] && !checked[3] && !checked[4]) {
                        Toast.makeText(getContext(), "请至少勾选一项要清除的数据", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    if (mListener != null) {
                        mListener.onClearDataRequested(checked[0], checked[1], checked[2], checked[3], checked[4]);
                    }
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
