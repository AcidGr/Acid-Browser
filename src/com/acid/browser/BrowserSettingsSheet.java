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
 * Hierarchical Settings Modal Sheet (一级/二级分类设置中心).
 *
 * Level 1 (设置主页 / 一级目录):
 *  1. 通用设置 (搜索引擎、主页与启动、清除数据)
 *  2. AI 与自动化 (大模型配置、MCP 远程服务、Agent 助手)
 *  3. 防护与扩展 (广告拦截系统、用户脚本中心)
 *  4. 隐私与高级 (网页开发者调试、不跟踪协议、无图省流)
 *  5. 关于与系统 (版本信息、检查 OTA 更新、配置文件)
 *
 * Level 2 (二级设置详情页):
 *  - 顶部显示 [ < 返回 ] + 分类标题 + [ X 关闭 ]
 *  - 提供对应分类的细化配置项与交互弹窗
 *  - 拦截物理返回键或点击顶部返回键平滑退回一级目录
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

    public static final int LEVEL_MAIN = 0;
    public static final int LEVEL_GENERAL = 1;
    public static final int LEVEL_AI = 2;
    public static final int LEVEL_PROTECTION = 3;
    public static final int LEVEL_ADVANCED = 4;
    public static final int LEVEL_ABOUT = 5;

    private final BrowserDataStore mDataStore;
    private final OnSettingsActionListener mListener;
    private boolean mIsDarkMode = false;
    private boolean mIsMcpRunning = false;
    private int mCurrentLevel = LEVEL_MAIN;

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
        goToLevel(LEVEL_MAIN);
        show();
    }

    public void refresh() {
        if (isShowing()) {
            renderContent();
        }
    }

    public void goToLevel(int level) {
        mCurrentLevel = level;
        renderContent();
        getScrollView().scrollTo(0, 0);
    }

    @Override
    public boolean handleBackPressed() {
        if (!isShowing()) return false;
        if (mCurrentLevel != LEVEL_MAIN) {
            goToLevel(LEVEL_MAIN);
            return true;
        }
        return super.handleBackPressed();
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

        OnClickListener backToMainListener = new OnClickListener() {
            @Override
            public void onClick(View v) {
                goToLevel(LEVEL_MAIN);
            }
        };

        if (mCurrentLevel == LEVEL_MAIN) {
            setTitle("设置");
            setHeaderNavigation(false, null);
            renderLevel1Main(container, ctx, textColor, mutedColor, accentColor, cardBg, border);
        } else if (mCurrentLevel == LEVEL_GENERAL) {
            setTitle("通用");
            setHeaderNavigation(true, backToMainListener);
            renderLevel2General(container, ctx, textColor, mutedColor, accentColor, cardBg, border, divider);
        } else if (mCurrentLevel == LEVEL_AI) {
            setTitle("AI");
            setHeaderNavigation(true, backToMainListener);
            renderLevel2Ai(container, ctx, textColor, mutedColor, accentColor, cardBg, border, divider);
        } else if (mCurrentLevel == LEVEL_PROTECTION) {
            setTitle("防护");
            setHeaderNavigation(true, backToMainListener);
            renderLevel2Protection(container, ctx, textColor, mutedColor, accentColor, cardBg, border, divider);
        } else if (mCurrentLevel == LEVEL_ADVANCED) {
            setTitle("高级");
            setHeaderNavigation(true, backToMainListener);
            renderLevel2Advanced(container, ctx, textColor, mutedColor, accentColor, cardBg, border, divider);
        } else if (mCurrentLevel == LEVEL_ABOUT) {
            setTitle("关于");
            setHeaderNavigation(true, backToMainListener);
            renderLevel2About(container, ctx, textColor, mutedColor, accentColor, cardBg, border, divider);
        }
    }

    // =========================================================================
    // LEVEL 1: 设置主页 / 一级目录
    // =========================================================================

    private void renderLevel1Main(LinearLayout container, Context ctx,
                                  int textColor, int mutedColor, int accentColor,
                                  int cardBg, int border) {
        LinearLayout menuCard = createCardLayout(ctx, cardBg, border);
        int divider = AcidTheme.border(mIsDarkMode);

        // 1. 通用
        addCategoryEntry(menuCard, AcidIcons.ICON_SETTINGS, "通用",
            textColor, mutedColor, accentColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToLevel(LEVEL_GENERAL);
                }
            });
        addDivider(menuCard, divider);

        // 2. AI
        addCategoryEntry(menuCard, AcidIcons.ICON_AGENT, "AI",
            textColor, mutedColor, accentColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToLevel(LEVEL_AI);
                }
            });
        addDivider(menuCard, divider);

        // 3. 防护
        addCategoryEntry(menuCard, AcidIcons.ICON_ADBLOCK, "防护",
            textColor, mutedColor, accentColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToLevel(LEVEL_PROTECTION);
                }
            });
        addDivider(menuCard, divider);

        // 4. 高级
        addCategoryEntry(menuCard, AcidIcons.ICON_SHIELD, "高级",
            textColor, mutedColor, accentColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToLevel(LEVEL_ADVANCED);
                }
            });
        addDivider(menuCard, divider);

        // 5. 关于
        addCategoryEntry(menuCard, AcidIcons.ICON_INFO, "关于",
            textColor, mutedColor, accentColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToLevel(LEVEL_ABOUT);
                }
            });

        container.addView(menuCard);
    }

    private void addCategoryEntry(ViewGroup parent, int iconId, String title,
                                  int textColor, int mutedColor, int accentColor, OnClickListener listener) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 16);
        int padV = AcidTheme.dp(ctx, 14);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);

        // Icon Box with pill background
        LinearLayout iconBox = new LinearLayout(ctx);
        iconBox.setGravity(Gravity.CENTER);
        int boxSize = AcidTheme.dp(ctx, 32);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(boxSize, boxSize));
        int iconBg = AcidTheme.cardItemBg(mIsDarkMode);
        iconBox.setBackground(AcidTheme.pillBg(ctx, iconBg, 0, 8));

        AcidIcons.SvgIconView iconView = new AcidIcons.SvgIconView(ctx, iconId, accentColor, 18);
        iconBox.addView(iconView);
        row.addView(iconBox);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(textColor);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 14);
        row.addView(tvTitle, tLp);

        // Right arrow
        AcidIcons.SvgIconView arrow = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_FORWARD, mutedColor, 14);
        row.addView(arrow);

        if (listener != null) {
            row.setOnClickListener(listener);
        }
        parent.addView(row);
    }

    // =========================================================================
    // LEVEL 2: 二级设置详情页
    // =========================================================================

    // --- 2.1 通用设置 ---
    private void renderLevel2General(LinearLayout container, Context ctx,
                                     int textColor, int mutedColor, int accentColor,
                                     int cardBg, int border, int divider) {
        addSectionHeader(container, "浏览与检索", mutedColor);
        LinearLayout cardSearch = createCardLayout(ctx, cardBg, border);

        // 搜索引擎
        int seIdx = mDataStore.getSearchEngineIndex();
        String seName = (seIdx >= 0 && seIdx < BrowserDataStore.SEARCH_ENGINE_NAMES.length) ?
            BrowserDataStore.SEARCH_ENGINE_NAMES[seIdx] : "Google";
        if (seIdx == BrowserDataStore.SEARCH_CUSTOM && !TextUtils.isEmpty(mDataStore.getCustomSearchEngineUrl())) {
            seName = "自定义 (" + mDataStore.getCustomSearchEngineUrl() + ")";
        }
        addClickableItem(cardSearch, AcidIcons.ICON_SEARCH, "搜索引擎", seName, textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchEngineDialog();
            }
        });
        addDivider(cardSearch, divider);

        // 主页设置
        int homeType = mDataStore.getHomeType();
        String homeDesc = (homeType >= 0 && homeType < BrowserDataStore.HOME_TYPE_NAMES.length) ?
            BrowserDataStore.HOME_TYPE_NAMES[homeType] : "Acid 默认首页";
        if (homeType == BrowserDataStore.HOME_CUSTOM && !TextUtils.isEmpty(mDataStore.getCustomHomeUrl())) {
            homeDesc = "自定义 (" + mDataStore.getCustomHomeUrl() + ")";
        }
        addClickableItem(cardSearch, AcidIcons.ICON_GLOBE, "主页设置", homeDesc, textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showHomepageDialog();
            }
        });
        container.addView(cardSearch);

        addSectionHeader(container, "数据与隐私维护", mutedColor);
        LinearLayout cardData = createCardLayout(ctx, cardBg, border);

        // 清除浏览数据
        addClickableItem(cardData, AcidIcons.ICON_TRASH, "清除浏览数据", "清理网络缓存、Cookies、历史与本地存储", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showClearDataDialog();
            }
        });
        addDivider(cardData, divider);

        // 退出时自动清理
        addSwitchItem(cardData, AcidIcons.ICON_SHIELD, "退出时自动清理", "退出浏览器时自动清理缓存与 Cookie",
            mDataStore.isClearOnExit(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setClearOnExit(isChecked);
                }
            }
        );
        container.addView(cardData);
    }

    // --- 2.2 AI 与自动化 ---
    private void renderLevel2Ai(LinearLayout container, Context ctx,
                                int textColor, int mutedColor, int accentColor,
                                int cardBg, int border, int divider) {
        addSectionHeader(container, "大模型与推理引擎", mutedColor);
        LinearLayout cardModel = createCardLayout(ctx, cardBg, border);

        // 大模型配置
        addClickableItem(cardModel, AcidIcons.ICON_MODEL, "模型服务商与 API", "配置 DeepSeek、OpenAI、Claude 等", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenModelConfig();
            }
        });
        container.addView(cardModel);

        addSectionHeader(container, "自动化能力与外部交互", mutedColor);
        LinearLayout cardAgent = createCardLayout(ctx, cardBg, border);

        // MCP 远程控制服务
        addSwitchItem(cardAgent, AcidIcons.ICON_TERMINAL, "MCP 远程自动化服务",
            mIsMcpRunning ? "服务运行中 (HTTP 8765)" : "服务已停止",
            mIsMcpRunning, textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (mListener != null) mListener.onToggleMcpServer();
                }
            }
        );
        addDivider(cardAgent, divider);

        // Agent 悬浮助手窗口
        addClickableItem(cardAgent, AcidIcons.ICON_AGENT, "Acid Agent 悬浮助手", "悬浮呼出 AI 网页助手与多轮对话", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenAgentFloatingWindow();
            }
        });
        addDivider(cardAgent, divider);

        // 清理 AI 工作区
        addClickableItem(cardAgent, AcidIcons.ICON_TRASH, "清理工作区临时文件", "清理 AI 报告与抓取数据，保护 settings.json 与脚本", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showClearWorkspaceConfirmDialog();
            }
        });
        container.addView(cardAgent);
    }

    // --- 2.3 防护与扩展 ---
    private void renderLevel2Protection(LinearLayout container, Context ctx,
                                        int textColor, int mutedColor, int accentColor,
                                        int cardBg, int border, int divider) {
        addSectionHeader(container, "广告与干扰元素防护", mutedColor);
        LinearLayout cardAd = createCardLayout(ctx, cardBg, border);

        boolean adEnabled = mDataStore.isAdBlockEnabled();
        int blockedCount = mDataStore.getAdBlockBlockedCount();
        String adSub = (adEnabled ? "已开启防护" : "防护已暂停") + " · 已累计拦截 " + blockedCount + " 次广告";
        addClickableItem(cardAd, AcidIcons.ICON_ADBLOCK, "广告与隐私追踪拦截", adSub, textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenAdBlock();
            }
        });
        container.addView(cardAd);

        addSectionHeader(container, "网页脚本与插件扩展", mutedColor);
        LinearLayout cardScript = createCardLayout(ctx, cardBg, border);

        addClickableItem(cardScript, AcidIcons.ICON_FILE, "脚本管理 (Userscripts)", "自定义扩展与特定网页脚本增强", textColor, mutedColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) mListener.onOpenUserscripts();
            }
        });
        container.addView(cardScript);
    }

    // --- 2.4 隐私与高级 ---
    private void renderLevel2Advanced(LinearLayout container, Context ctx,
                                      int textColor, int mutedColor, int accentColor,
                                      int cardBg, int border, int divider) {
        addSectionHeader(container, "开发者与调试", mutedColor);
        LinearLayout cardDev = createCardLayout(ctx, cardBg, border);

        addSwitchItem(cardDev, AcidIcons.ICON_TERMINAL, "允许网页开发者调试", "支持 Chrome DevTools 远程审查移动端网页",
            mDataStore.isWebDebuggingEnabled(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setWebDebuggingEnabled(isChecked);
                    if (mListener != null) mListener.onWebDebuggingChanged(isChecked);
                }
            }
        );
        container.addView(cardDev);

        addSectionHeader(container, "隐私偏好与省流", mutedColor);
        LinearLayout cardPrivacy = createCardLayout(ctx, cardBg, border);

        addSwitchItem(cardPrivacy, AcidIcons.ICON_INCOGNITO, "发送「不跟踪」请求 (DNT)", "向所有访问网站请求保护隐私",
            mDataStore.isDoNotTrackEnabled(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setDoNotTrackEnabled(isChecked);
                    if (mListener != null) mListener.onDoNotTrackChanged(isChecked);
                }
            }
        );
        addDivider(cardPrivacy, divider);

        addSwitchItem(cardPrivacy, AcidIcons.ICON_FILE_IMAGE, "无图省流模式", "不自动加载图片以节省网络流量",
            mDataStore.isBlockImagesEnabled(), textColor, mutedColor, accentColor,
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mDataStore.setBlockImagesEnabled(isChecked);
                    if (mListener != null) mListener.onBlockImagesChanged(isChecked);
                }
            }
        );
        container.addView(cardPrivacy);
    }

    // --- 2.5 关于与系统 ---
    private void renderLevel2About(LinearLayout container, Context ctx,
                                   int textColor, int mutedColor, int accentColor,
                                   int cardBg, int border, int divider) {
        addSectionHeader(container, "应用信息", mutedColor);
        LinearLayout cardAbout = createCardLayout(ctx, cardBg, border);

        addClickableItem(cardAbout, AcidIcons.ICON_INFO, "关于 Acid Browser", "版本 0.4.3 (build 223) · 检查 OTA 更新",
            textColor, mutedColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mListener != null) mListener.onOpenAbout();
                }
            });
        container.addView(cardAbout);

        addSectionHeader(container, "配置文件与 Agent 热重载", mutedColor);
        LinearLayout cardCfg = createCardLayout(ctx, cardBg, border);
        addClickableItem(cardCfg, AcidIcons.ICON_FILE, "设置文件 (settings.json)", "位于工作区，支持 AI Agent 直接修改并实时热重载",
            textColor, mutedColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(getContext(), "配置文件已就绪: settings.json (支持热重载)", Toast.LENGTH_SHORT).show();
                }
            });
        container.addView(cardCfg);
    }

    // =========================================================================
    // UI Helpers
    // =========================================================================

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
        lp.topMargin = AcidTheme.dp(ctx, 10);
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

    private void showClearWorkspaceConfirmDialog() {
        Context ctx = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("清理 AI 工作区");
        builder.setMessage("将清理 Agent 生成的历史报告、抓取数据与临时脚本。\n\n安全保护：不会删除 settings.json 配置文件、用户脚本 (userscripts) 与广告拦截规则 (adblock)。");
        builder.setPositiveButton("立即清理", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mListener != null) {
                    mListener.onClearWorkspaceOnlyRequested();
                }
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
            "网页本地存储 (WebStorage)",
            "AI 工作区文件 (保留设置与脚本)"
        };
        final boolean[] checked = new boolean[] { true, false, true, false, false };

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
                    mListener.onClearDataRequested(checked[0], checked[1], checked[2], checked[3], checked[4]);
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
}
