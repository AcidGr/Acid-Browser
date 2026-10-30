package com.acid.browser;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Hierarchical Settings Modal Sheet built declaratively on {@link AcidBottomSheetView}'s
 * {@link SheetPage} stack, {@link AcidSettings} registry, {@link AcidWidgets#groupCard},
 * and {@link AcidDialogs}.
 */
public class BrowserSettingsSheet extends AcidBottomSheetView {

    public interface OnSettingsActionListener {
        void onOpenModelConfig();
        void onOpenMcp();
        void onToggleMcpServer();
        void onOpenAgentFloatingWindow();
        void onOpenAdBlock();
        void onOpenUserscripts();
        void onOpenSkills();
        void onOpenAbout();
        void onClearDataRequested(boolean clearCache, boolean clearCookies, boolean clearHistory, boolean clearStorage, boolean clearWorkspace);
        void onClearWorkspaceOnlyRequested();
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
        setRootPage(buildMainPage());
    }

    public void setMcpRunning(boolean running) {
        mIsMcpRunning = running;
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildMainPage());
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
                boolean adBlockOn = mDataStore.get(AcidSettings.ADBLOCK_ENABLED).booleanValue();

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "浏览与智能体"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SETTINGS, accent,
                        "通用与搜索", getSearchEngineLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildGeneralPage()); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, blue,
                        "AI 与自动化", mIsMcpRunning ? "MCP 运行中" : null,
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildAiPage()); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_ADBLOCK, accent,
                        "防护与扩展", adBlockOn ? "已开启" : "已暂停",
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildProtectionPage()); }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "系统与关于"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, amber,
                        "隐私与高级", null,
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildAdvancedPage()); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_INFO, AcidTheme.muted(mIsDarkMode),
                        "关于 Acid Browser", "v" + AcidTheme.appVersionName(ctx),
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenAbout();
                            }
                        }
                    )
                ));
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
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SEARCH, accent,
                        AcidSettings.SEARCH_ENGINE.title, getSearchEngineLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { showSearchEngineDialog(); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_HOME, accent,
                        AcidSettings.HOME_TYPE.title, getHomeTypeLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { showHomepageDialog(); }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "浏览数据管理"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.dangerRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TRASH, "清除浏览数据",
                        new OnClickListener() {
                            @Override public void onClick(View v) { showClearDataDialog(); }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, accent,
                        AcidSettings.CLEAR_ON_EXIT, mDataStore, null, null
                    )
                ));
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
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_MODEL, blue,
                        "模型配置", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenModelConfig();
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, accent,
                        "打开 Agent 对话窗", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                closeEntireModalFlow();
                                if (mListener != null) mListener.onOpenAgentFloatingWindow();
                            }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "远程网关与沙盒存储"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TERMINAL, accent,
                        "MCP 服务与认证", "查看本机/局域网地址、配置 Bearer 令牌",
                        mIsMcpRunning ? "运行中" : "已停止", true,
                        new OnClickListener() {
                            @Override public void onClick(View v) {
                                if (mListener != null) mListener.onOpenMcp();
                            }
                        }
                    ),
                    AcidWidgets.dangerRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TRASH, "清理 AI 工作区文件",
                        new OnClickListener() {
                            @Override public void onClick(View v) { showClearWorkspaceConfirmDialog(); }
                        }
                    )
                ));
            }
        });
    }

    private SheetPage buildProtectionPage() {
        return new SheetPage("防护与扩展", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                boolean abEnabled = mDataStore.get(AcidSettings.ADBLOCK_ENABLED).booleanValue();
                int blockedCount = mDataStore.get(AcidSettings.ADBLOCK_BLOCKED_COUNT).intValue();

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "内容净化与脚本引擎"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_ADBLOCK, accent,
                        "广告拦截",
                        abEnabled ? ("已拦截 " + blockedCount + " 次") : "已暂停",
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenAdBlock();
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_FILE, AcidTheme.COLOR_BLUE,
                        "脚本管理", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenUserscripts();
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, AcidTheme.accent(mIsDarkMode),
                        "技能管理 (Skills)", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenSkills();
                            }
                        }
                    )
                ));
            }
        });
    }

    private SheetPage buildAdvancedPage() {
        return new SheetPage("隐私与高级", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                String filePath = mDataStore.getSettingsJsonFile() != null
                    ? mDataStore.getSettingsJsonFile().getName()
                    : "settings.json";

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "隐私与省流"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_INCOGNITO, accent,
                        AcidSettings.DO_NOT_TRACK.title, null,
                        mDataStore.isDoNotTrackEnabled(),
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                mDataStore.setDoNotTrackEnabled(isChecked);
                                if (mListener != null) mListener.onDoNotTrackChanged(isChecked);
                            }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_FILE_IMAGE, AcidTheme.COLOR_AMBER,
                        AcidSettings.BLOCK_IMAGES.title, null,
                        mDataStore.isBlockImagesEnabled(),
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                mDataStore.setBlockImagesEnabled(isChecked);
                                if (mListener != null) mListener.onBlockImagesChanged(isChecked);
                            }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "开发者与配置同步"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TERMINAL, AcidTheme.COLOR_BLUE,
                        AcidSettings.WEB_DEBUGGING.title, null,
                        mDataStore.isWebDebuggingEnabled(),
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                mDataStore.setWebDebuggingEnabled(isChecked);
                                if (mListener != null) mListener.onWebDebuggingChanged(isChecked);
                            }
                        }
                    ),
                    AcidWidgets.valueRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_FILE, AcidTheme.COLOR_BLUE,
                        "配置文件", filePath,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                AcidFeedback.info(ctx, "工作区配置文件: settings.json (支持实时热重载)");
                            }
                        }
                    )
                ));
            }
        });
    }

    private String getSearchEngineLabel() {
        return AcidSettings.SEARCH_ENGINE.indexToLabel(mDataStore.getSearchEngineIndex());
    }

    private String getHomeTypeLabel() {
        return AcidSettings.HOME_TYPE.indexToLabel(mDataStore.getHomeType());
    }

    // =========================================================================
    // TIER 3: AcidDialogs Modals
    // =========================================================================

    private void showSearchEngineDialog() {
        final Context ctx = getContext();
        final String[] items = AcidSettings.SEARCH_ENGINE.displayLabels;
        AcidDialogs.chooseSingle(
            ctx, mIsDarkMode, "选择默认搜索引擎",
            items, mDataStore.getSearchEngineIndex(),
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int which) {
                    if (which == BrowserDataStore.SEARCH_CUSTOM) {
                        showCustomSearchEngineInputDialog();
                    } else {
                        mDataStore.setSearchEngineIndex(which);
                        refreshActivePage();
                        AcidFeedback.ok(ctx, "默认搜索引擎已切换为: " + items[which]);
                    }
                }
            }
        );
    }

    private void showCustomSearchEngineInputDialog() {
        final Context ctx = getContext();
        AcidDialogs.inputSingleLine(
            ctx, mIsDarkMode,
            "自定义搜索引擎",
            "搜索地址模板 (使用 %s 代表搜索词)",
            mDataStore.getCustomSearchEngineUrl(),
            "https://example.com/search?q=%s",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
            "保存",
            "自定义搜索 URL 不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String input) {
                    mDataStore.setSearchEngineIndex(BrowserDataStore.SEARCH_CUSTOM);
                    mDataStore.setCustomSearchEngineUrl(input);
                    refreshActivePage();
                    AcidFeedback.ok(ctx, "已保存自定义搜索引擎");
                    return true;
                }
            }
        );
    }

    private void showHomepageDialog() {
        final Context ctx = getContext();
        final String[] items = AcidSettings.HOME_TYPE.displayLabels;
        AcidDialogs.chooseSingle(
            ctx, mIsDarkMode, "选择主页类型",
            items, mDataStore.getHomeType(),
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int which) {
                    if (which == BrowserDataStore.HOME_CUSTOM) {
                        showCustomHomepageInputDialog();
                    } else {
                        mDataStore.setHomeType(which);
                        refreshActivePage();
                        if (mListener != null) mListener.onHomeUrlChanged(which, "");
                        AcidFeedback.ok(ctx, "主页已切换为: " + items[which]);
                    }
                }
            }
        );
    }

    private void showCustomHomepageInputDialog() {
        final Context ctx = getContext();
        AcidDialogs.inputSingleLine(
            ctx, mIsDarkMode,
            "自定义主页网址",
            "主页网址",
            mDataStore.getCustomHomeUrl(),
            "https://www.bing.com",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
            "保存",
            "主页网址不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String input) {
                    mDataStore.setHomeType(BrowserDataStore.HOME_CUSTOM);
                    mDataStore.setCustomHomeUrl(input);
                    refreshActivePage();
                    if (mListener != null) mListener.onHomeUrlChanged(BrowserDataStore.HOME_CUSTOM, input);
                    AcidFeedback.ok(ctx, "已保存自定义主页");
                    return true;
                }
            }
        );
    }

    private void showClearWorkspaceConfirmDialog() {
        AcidDialogs.confirmDanger(
            getContext(), mIsDarkMode,
            "清理 AI 工作区",
            "将清除 Agent 在沙盒中生成的历史报告、抓取数据与临时文件。\n\n安全保护：不会删除 settings.json 配置文件、bookmarks.json 书签、history.json 浏览历史、自定义主页 (homepage)、用户脚本 (userscripts) 与广告拦截规则 (adblock)。",
            "立即清理",
            new Runnable() {
                @Override
                public void run() {
                    if (mListener != null) mListener.onClearWorkspaceOnlyRequested();
                }
            }
        );
    }

    private void showClearDataDialog() {
        final Context ctx = getContext();
        final String[] options = new String[]{
            "网页缓存 (Cache)",
            "Cookies 与网站登录状态",
            "历史浏览记录",
            "网页本地存储 (WebStorage)",
            "AI 工作区临时文件 (保留配置与脚本)"
        };
        final boolean[] checked = new boolean[]{true, false, true, false, false};

        AcidDialogs.chooseMulti(
            ctx, mIsDarkMode,
            "清除浏览数据",
            options, checked,
            "立即清除", true,
            new AcidDialogs.MultiChoiceCallback() {
                @Override
                public boolean onConfirmed(boolean[] sel) {
                    if (!sel[0] && !sel[1] && !sel[2] && !sel[3] && !sel[4]) {
                        AcidFeedback.warn(ctx, "请至少勾选一项要清除的数据");
                        return false;
                    }
                    if (mListener != null) {
                        mListener.onClearDataRequested(sel[0], sel[1], sel[2], sel[3], sel[4]);
                    }
                    return true;
                }
            }
        );
    }
}
