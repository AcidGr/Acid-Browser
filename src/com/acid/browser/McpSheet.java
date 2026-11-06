package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Model Context Protocol (MCP) Control Gateway & Authentication Sheet
 * built on {@link AcidBottomSheetView}'s {@link SheetPage} template and {@link AcidWidgets}.
 *
 * <p>Provides:
 * <ul>
 *   <li>Hero server state card with on/off switch and safety notice.</li>
 *   <li>Loopback (127.0.0.1) and Local Network (LAN) endpoint display with one-tap copy.</li>
 *   <li>Bearer token authentication management (toggle, token inspection, copy, and re-generation).</li>
 *   <li>LAN accessibility control (0.0.0.0 vs 127.0.0.1 binding).</li>
 *   <li>Integration instructions for external AI hosts (Cursor, Claude Desktop, DSH).</li>
 * </ul>
 */
public class McpSheet extends AcidBottomSheetView {

    public interface OnMcpActionListener {
        void onToggleMcpServer(boolean enable);
        void onRestartMcpServer();
    }

    private final BrowserDataStore mDataStore;
    private final HttpControlServer mServer;
    private final OnMcpActionListener mListener;

    public McpSheet(
        Context context,
        BrowserDataStore dataStore,
        HttpControlServer server,
        OnMcpActionListener listener
    ) {
        super(context);
        mDataStore = dataStore;
        mServer = server;
        mListener = listener;
        setRootPage(buildMcpPage());
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildMcpPage());
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildMcpPage());
        } else {
            refreshActivePage();
        }
        super.show();
    }

    public void renderContent() {
        setRootPage(buildMcpPage());
    }

    private SheetPage buildMcpPage() {
        return new SheetPage("MCP 服务", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = getContext();
                final int textColor = AcidTheme.text(mIsDarkMode);
                final int mutedColor = AcidTheme.muted(mIsDarkMode);
                final int accentColor = AcidTheme.accent(mIsDarkMode);

                final boolean isRunning = mServer != null && mServer.isRunning();
                final int port = mServer != null ? mServer.getPort() : HttpControlServer.DEFAULT_PORT;
                final boolean lanEnabled = mDataStore != null && mDataStore.isMcpLanEnabled();
                final boolean authEnabled = mDataStore != null && mDataStore.isMcpAuthEnabled();
                final String token = mDataStore != null ? mDataStore.getOrCreateMcpToken() : "";
                final String lanIp = HttpControlServer.getLocalIpAddress();

                // 1. Hero Status Card (SP_HERO = 15sp Bold)
                LinearLayout hero = AcidWidgets.heroCard(
                    ctx, mIsDarkMode,
                    isRunning ? accentColor : AcidTheme.border(mIsDarkMode)
                );

                LinearLayout topRow = new LinearLayout(ctx);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                AcidIcons.SvgIconView iconView = new AcidIcons.SvgIconView(
                    ctx, AcidIcons.ICON_TERMINAL, isRunning ? accentColor : mutedColor, 28
                );
                topRow.addView(iconView);

                LinearLayout titleCol = new LinearLayout(ctx);
                titleCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                );
                tLp.leftMargin = AcidTheme.dp(ctx, 10);
                tLp.rightMargin = AcidTheme.dp(ctx, 8);

                TextView tvTitle = new TextView(ctx);
                tvTitle.setText(AcidLocale.tr(isRunning ? "MCP 服务运行中" : "MCP 服务已停止"));
                tvTitle.setTextColor(textColor);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
                tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
                titleCol.addView(tvTitle);

                TextView tvDesc = new TextView(ctx);
                tvDesc.setText(AcidLocale.tr("允许外部 AI 工具连接并协同遥控浏览器。请开启时确保你知道 MCP 是什么。"));
                tvDesc.setTextColor(mutedColor);
                tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvDesc.setPadding(0, AcidTheme.dp(ctx, 3), 0, 0);
                titleCol.addView(tvDesc);

                topRow.addView(titleCol, tLp);

                final Switch sw = new Switch(ctx);
                sw.setChecked(isRunning);
                AcidWidgets.styleSwitch(sw, mIsDarkMode, isRunning);
                sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (mListener != null) {
                            mListener.onToggleMcpServer(isChecked);
                        }
                        post(new Runnable() {
                            @Override public void run() { refreshActivePage(); }
                        });
                    }
                });
                topRow.addView(sw);
                hero.addView(topRow);
                hero.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        sw.setChecked(!sw.isChecked());
                    }
                });
                container.addView(hero);

                // 2. Connection Endpoints Section
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "连接地址"));

                final String localUrl = "http://127.0.0.1:" + port + "/mcp";
                final String lanUrl = (lanIp != null) ? ("http://" + lanIp + ":" + port + "/mcp") : null;

                String localSub = isRunning ? "点击复制本机地址" : "服务未启动";
                String lanSub;
                if (!isRunning) {
                    lanSub = "服务未启动";
                } else if (!lanEnabled) {
                    lanSub = "局域网监听未开启 (仅本机可连)";
                } else if (lanIp != null) {
                    lanSub = "点击复制局域网地址";
                } else {
                    lanSub = "未连接 Wi-Fi / 无局域网 IP";
                }

                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_MOBILE, accentColor,
                        "本机地址", localSub, isRunning ? localUrl : "未启动", false,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (isRunning) {
                                    copyToClipboard(ctx, localUrl, "本机地址");
                                } else {
                                    AcidFeedback.info(ctx, "MCP 服务未启动，请先在上方启动服务");
                                }
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_GLOBE, lanEnabled ? accentColor : mutedColor,
                        "局域网地址", lanSub, (isRunning && lanEnabled && lanUrl != null) ? lanUrl : "未就绪", false,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (!isRunning) {
                                    AcidFeedback.info(ctx, "MCP 服务未启动，请先在上方启动服务");
                                } else if (!lanEnabled) {
                                    AcidFeedback.info(ctx, "请先在下方开启「允许局域网设备连接」");
                                } else if (lanUrl != null) {
                                    copyToClipboard(ctx, lanUrl, "局域网地址");
                                } else {
                                    AcidFeedback.warn(ctx, "当前未检测到局域网 IP，请确认已连接 Wi-Fi");
                                }
                            }
                        }
                    ),
                    AcidWidgets.valueRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TERMINAL, mutedColor,
                        "服务端口", String.valueOf(port), null
                    )
                ));

                // 3. Security & Bearer Token Authentication Section
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "安全认证 (Bearer Token)"));

                String maskedToken = (token.length() > 16)
                    ? (token.substring(0, 8) + "••••••••" + token.substring(token.length() - 8))
                    : token;

                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, accentColor,
                        "启用 Bearer 令牌认证",
                        "外部客户端必须携带令牌才能访问，防止恶意网页与未授权调用",
                        authEnabled,
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                if (mDataStore != null) {
                                    mDataStore.setMcpAuthEnabled(isChecked);
                                }
                                AcidFeedback.info(ctx, isChecked ? "已启用 MCP 令牌认证" : "已停用令牌认证 (允许匿名访问)");
                                refreshActivePage();
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_EDIT, accentColor,
                        "访问令牌 (Token)", "点击查看与复制完整令牌", maskedToken, true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                copyToClipboard(ctx, token, "访问令牌");
                                showTokenDetailDialog(ctx, token);
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_RELOAD, mutedColor,
                        "重新生成访问令牌", "原有令牌将立即失效，需在客户端更新", null, true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                AcidDialogs.confirm(
                                    ctx, mIsDarkMode, "重置令牌确认",
                                    "重新生成后，已连接的外部客户端需要更新为新令牌才能继续调用。确认生成新令牌吗？",
                                    "确认重置",
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mDataStore != null) {
                                                String newToken = mDataStore.generateNewMcpToken();
                                                copyToClipboard(ctx, newToken, "新访问令牌");
                                                AcidFeedback.ok(ctx, "新令牌已生成并复制到剪贴板");
                                            }
                                            refreshActivePage();
                                        }
                                    }
                                );
                            }
                        }
                    )
                ));

                // 4. Network Exposure Policy Section
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "网络访问控制"));

                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_GLOBE, accentColor,
                        "允许局域网设备连接",
                        "监听 0.0.0.0，使同一 Wi-Fi 下的电脑等设备能访问 MCP",
                        lanEnabled,
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                if (mDataStore != null) {
                                    mDataStore.setMcpLanEnabled(isChecked);
                                }
                                if (isChecked && !authEnabled) {
                                    AcidFeedback.warn(ctx, "已允许局域网连接，建议开启上方令牌认证以保安全");
                                } else {
                                    AcidFeedback.info(ctx, isChecked ? "已允许局域网连接" : "已限制仅本机回环访问");
                                }
                                if (mServer != null && mServer.isRunning() && mListener != null) {
                                    mListener.onRestartMcpServer();
                                }
                                refreshActivePage();
                            }
                        }
                    )
                ));

                // 5. Instruction & Usage Guidance Card
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "连接与使用说明"));

                LinearLayout tipCard = AcidWidgets.contentCard(ctx, mIsDarkMode);
                TextView tvTip = new TextView(ctx);
                tvTip.setTextColor(mutedColor);
                tvTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvTip.setLineSpacing(AcidTheme.dp(ctx, 3), 1.0f);
                tvTip.setText(
                    "1. 本机/跨机连接：\n"
                    + "   • 同一手机上的 AI 工具使用「本机地址」。\n"
                    + "   • 电脑上的 Cursor、Claude Desktop 等请使用「局域网地址」，两台设备需在同一 Wi-Fi 下。\n\n"
                    + "2. 客户端认证配置：\n"
                    + "   • 开启认证后，客户端需在请求头中配置：\n"
                    + "     Authorization: Bearer <访问令牌>\n"
                    + "   • 或在连接 URL 结尾追加参数：?token=<访问令牌>\n\n"
                    + "3. 安全注意：\n"
                    + "   • 认证通信不加密，请在可信任的家庭或公司局域网中使用，切勿在公共开放 Wi-Fi 下开启局域网连接。"
                );
                tipCard.addView(tvTip);
                container.addView(tipCard);
            }
        });
    }

    private void showTokenDetailDialog(final Context ctx, final String token) {
        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("访问令牌 (Token)")
            .setMessage(token)
            .setPositiveButton("复制令牌", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    copyToClipboard(ctx, token, "访问令牌");
                    return true;
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private static void copyToClipboard(Context context, String content, String label) {
        AcidIo.copyToClipboard(context, "mcp_" + label, content, "已复制" + label + "到剪贴板");
    }
}
