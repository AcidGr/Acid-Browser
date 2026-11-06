package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Userscript Management Sheet & Installer Coordinator built on {@link AcidBottomSheetView}'s
 * sub-page stack, {@link AcidWidgets}, {@link AcidContextMenu}, and {@link AcidDialogs}.
 *
 * <p>Improvements:
 * <ul>
 *   <li>Toggling a script switch updates the row in-place without tearing down the list mid-animation.</li>
 *   <li>Script Detail is promoted from a cramped 3-button dialog to a proper Level-2 {@link SheetPage}
 *       with a pinned footer toolbar.</li>
 *   <li>Remote {@code .user.js} downloads use {@link AcidAsync} and {@link AcidHttp}.</li>
 * </ul>
 */
public class UserscriptSheet extends AcidBottomSheetView {

    public interface OnScriptActionListener {
        void onRunScript(UserscriptMeta meta);
        void onScriptsChanged();
        /** Lists commands scripts registered on the current page; null callback result = none. */
        void onQueryCommands(UserscriptManager.MenuCommandsCallback cb);
        void onRunCommand(UserscriptManager.MenuCommand cmd);
    }

    private final UserscriptManager mManager;
    private final OnScriptActionListener mActionListener;

    public UserscriptSheet(
        Context context,
        UserscriptManager manager,
        OnScriptActionListener listener
    ) {
        super(context);
        mManager = manager;
        mActionListener = listener;
        setRootPage(buildScriptsListPage());
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildScriptsListPage());
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildScriptsListPage());
        } else {
            refreshActivePage();
        }
        super.show();
    }

    public void renderScripts() {
        setRootPage(buildScriptsListPage());
    }

    // =========================================================================
    // LEVEL 1: Installed Scripts List Page
    // =========================================================================

    private SheetPage buildScriptsListPage() {
        return new SheetPage("脚本管理", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                if (mManager == null) return;
                mManager.invalidateCache();

                Context ctx = getContext();
                final LinearLayout commandBox = new LinearLayout(ctx);
                commandBox.setOrientation(LinearLayout.VERTICAL);
                container.addView(commandBox);
                loadCommands(ctx, commandBox);

                List<UserscriptMeta> scripts = mManager.getScripts();
                if (scripts.isEmpty()) {
                    container.addView(AcidWidgets.emptyState(
                        ctx, mIsDarkMode, AcidIcons.ICON_FILE, "尚未安装任何用户脚本，点击右上角可导入"
                    ));
                    return;
                }

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "已安装脚本 (" + scripts.size() + ")"));
                for (int i = 0; i < scripts.size(); i++) {
                    container.addView(buildScriptRow(ctx, scripts.get(i)));
                }
            }
        }).withAction(AcidIcons.ICON_PLUS, "导入脚本", new OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportDialog();
            }
        });
    }

    /** Fills {@code box} with the commands scripts registered on the page behind this sheet. */
    private void loadCommands(final Context ctx, final LinearLayout box) {
        if (mActionListener == null) return;
        mActionListener.onQueryCommands(new UserscriptManager.MenuCommandsCallback() {
            @Override
            public void onCommands(List<UserscriptManager.MenuCommand> commands) {
                if (commands == null || commands.isEmpty()) return;
                box.removeAllViews();
                box.addView(AcidWidgets.sectionHeader(
                    ctx, mIsDarkMode, "当前页面的脚本命令 (" + commands.size() + ")"));
                for (final UserscriptManager.MenuCommand cmd : commands) {
                    LinearLayout row = new AcidWidgets.ListRow()
                        .leadingIcon(AcidIcons.ICON_CLICK, AcidTheme.accent(mIsDarkMode), 19)
                        .title(cmd.title)
                        .subtitle(cmd.scriptName)
                        .build(ctx, mIsDarkMode);
                    row.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            closeEntireModalFlow();
                            mActionListener.onRunCommand(cmd);
                        }
                    });
                    box.addView(row);
                }
            }
        });
    }

    private static String formatScriptSubtitle(UserscriptMeta meta) {
        StringBuilder sb = new StringBuilder();
        if (!meta.enabled) sb.append("已停用 · ");
        sb.append(meta.describeScope());
        if (!TextUtils.isEmpty(meta.version)) sb.append(" · v").append(meta.version);
        sb.append(" · ").append(meta.runAt);
        return sb.toString();
    }

    private View buildScriptRow(final Context ctx, final UserscriptMeta meta) {
        final Switch sw = new Switch(ctx);
        sw.setChecked(meta.enabled);
        AcidWidgets.styleSwitch(sw, mIsDarkMode, meta.enabled);

        final AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
            .leadingIcon(
                AcidIcons.ICON_FILE,
                meta.enabled ? AcidTheme.accent(mIsDarkMode) : AcidTheme.iconDisabled(mIsDarkMode),
                19
            )
            .title(meta.name)
            .subtitle(formatScriptSubtitle(meta))
            .dimmed(!meta.enabled)
            .trailingView(sw);

        final LinearLayout card = rowBuilder.build(ctx, mIsDarkMode);

        // In-place visual update on toggle so the Switch thumb animation completes smoothly!
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AcidWidgets.styleSwitch(sw, mIsDarkMode, isChecked);
                if (mManager != null && meta.enabled != isChecked) {
                    mManager.setScriptEnabled(meta.fileName, isChecked);
                    meta.enabled = isChecked;
                    if (rowBuilder.iconView != null) {
                        rowBuilder.iconView.setColor(
                            isChecked ? AcidTheme.accent(mIsDarkMode) : AcidTheme.iconDisabled(mIsDarkMode)
                        );
                    }
                    if (rowBuilder.titleView != null) {
                        rowBuilder.titleView.setTextColor(
                            isChecked ? AcidTheme.text(mIsDarkMode) : AcidTheme.muted(mIsDarkMode)
                        );
                        rowBuilder.titleView.setTypeface(isChecked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    }
                    if (rowBuilder.subtitleView != null) {
                        rowBuilder.subtitleView.setText(AcidLocale.tr(formatScriptSubtitle(meta)));
                    }
                    card.setAlpha(isChecked ? 1f : 0.75f);
                    AcidFeedback.info(ctx, (isChecked ? "已启用「" : "已停用「") + meta.name + "」");
                    if (mActionListener != null) {
                        mActionListener.onScriptsChanged();
                    }
                }
            }
        });

        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pushPage(buildScriptDetailPage(meta));
            }
        });

        card.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showScriptContextMenu(card, meta);
                return true;
            }
        });

        return card;
    }

    private void showScriptContextMenu(View anchor, final UserscriptMeta meta) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CLICK, "在当前页运行一次", new Runnable() {
            @Override
            public void run() {
                if (mActionListener != null) mActionListener.onRunScript(meta);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_EDIT, "编辑源码", new Runnable() {
            @Override
            public void run() {
                showEditor(meta);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "检查更新", new Runnable() {
            @Override
            public void run() {
                checkUpdate(meta);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_INFO, "查看脚本详情", new Runnable() {
            @Override
            public void run() {
                pushPage(buildScriptDetailPage(meta));
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(
            AcidIcons.ICON_CHECK,
            meta.enabled ? "停用脚本" : "启用脚本",
            new Runnable() {
                @Override
                public void run() {
                    boolean next = !meta.enabled;
                    mManager.setScriptEnabled(meta.fileName, next);
                    meta.enabled = next;
                    refreshActivePage();
                    if (mActionListener != null) mActionListener.onScriptsChanged();
                }
            }
        ));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除脚本", true, new Runnable() {
            @Override
            public void run() {
                confirmDelete(meta);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, meta.name, actions);
    }

    // =========================================================================
    // LEVEL 2: Script Detail Page (Promoted from Cramped Dialog)
    // =========================================================================

    private SheetPage buildScriptDetailPage(final UserscriptMeta meta) {
        return new SheetPage(meta.name, new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container, LinearLayout footer) {
                final Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                boolean en = AcidLocale.isEn();

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "基本信息"));
                LinearLayout infoCard = AcidWidgets.contentCard(ctx, mIsDarkMode);

                if (!TextUtils.isEmpty(meta.description)) {
                    TextView desc = new TextView(ctx);
                    desc.setText(meta.description);
                    desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
                    desc.setTextColor(AcidTheme.text(mIsDarkMode));
                    desc.setPadding(0, 0, 0, AcidTheme.dp(ctx, 8));
                    infoCard.addView(desc);
                }

                TextView info = new TextView(ctx);
                StringBuilder sb = new StringBuilder();
                sb.append(en ? "Status: " : "状态: ").append(meta.enabled
                    ? (en ? "Enabled (Auto-runs on matching pages)" : "已启用 (随匹配页面自动执行)")
                    : (en ? "Disabled" : "已停用")).append("\n");
                sb.append(en ? "Scope: " : "生效范围: ").append(AcidLocale.tr(meta.describeScope())).append("\n");
                sb.append(en ? "Run At: " : "注入时机: ").append(meta.runAt).append("\n");
                if (!meta.requires.isEmpty()) {
                    sb.append(en ? "Requires: " : "依赖脚本: ").append(meta.requires.size())
                      .append(en ? " external libraries\n" : " 个外部库\n");
                }
                if (!meta.grants.isEmpty()) {
                    sb.append(en ? "Grants: " : "权限声明: ");
                    for (int i = 0; i < meta.grants.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(meta.grants.get(i));
                    }
                    sb.append("\n");
                }
                if (!TextUtils.isEmpty(meta.author)) sb.append(en ? "Author: " : "作者: ").append(meta.author).append("\n");
                if (!TextUtils.isEmpty(meta.version)) sb.append(en ? "Version: v" : "版本: v").append(meta.version).append("\n");
                if (!TextUtils.isEmpty(meta.homepageUrl)) sb.append(en ? "Homepage: " : "主页: ").append(meta.homepageUrl).append("\n");
                if (!TextUtils.isEmpty(meta.supportUrl)) sb.append(en ? "Support: " : "反馈: ").append(meta.supportUrl).append("\n");
                if (!TextUtils.isEmpty(UserscriptManager.updateUrlOf(meta))) {
                    sb.append(en ? "Update URL: " : "更新源: ").append(UserscriptManager.updateUrlOf(meta)).append("\n");
                }
                if (meta.unwrap) sb.append(en ? "Execution: @unwrap (Page Scope)\n" : "运行方式: @unwrap (页面作用域)\n");
                sb.append(en ? "File: " : "文件名: ").append(meta.fileName);
                info.setText(sb.toString());
                info.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                info.setTextColor(AcidTheme.muted(mIsDarkMode));
                info.setLineSpacing(AcidTheme.dp(ctx, 2.5f), 1f);
                infoCard.addView(info);
                container.addView(infoCard);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "源码预览"));
                container.addView(AcidWidgets.codePreviewBox(ctx, mIsDarkMode, meta.body, 2400));

                TextView btnToggle = AcidWidgets.secondaryButton(
                    ctx, mIsDarkMode,
                    meta.enabled ? "停用脚本" : "启用脚本",
                    AcidTheme.text(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean next = !meta.enabled;
                            mManager.setScriptEnabled(meta.fileName, next);
                            meta.enabled = next;
                            AcidFeedback.info(ctx, (next ? "已启用「" : "已停用「") + meta.name + "」");
                            if (mActionListener != null) mActionListener.onScriptsChanged();
                            refreshActivePage();
                        }
                    }
                );
                TextView btnRun = AcidWidgets.primaryButton(
                    ctx, mIsDarkMode, "在当前页运行", accent,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            closeEntireModalFlow();
                            if (mActionListener != null) mActionListener.onRunScript(meta);
                        }
                    }
                );
                footer.addView(AcidWidgets.buttonRow(ctx, btnToggle, btnRun));

                TextView btnEdit = AcidWidgets.secondaryButton(
                    ctx, mIsDarkMode, "编辑源码", AcidTheme.text(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showEditor(meta);
                        }
                    }
                );
                TextView btnUpdate = AcidWidgets.secondaryButton(
                    ctx, mIsDarkMode, "检查更新", AcidTheme.text(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            checkUpdate(meta);
                        }
                    }
                );
                View secondRow = AcidWidgets.buttonRow(ctx, btnEdit, btnUpdate);
                LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                editLp.topMargin = AcidTheme.dp(ctx, 10);
                footer.addView(secondRow, editLp);
            }
        }).withRawTitle().withAction(AcidIcons.ICON_TRASH, "删除", AcidTheme.COLOR_RED, new OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(meta);
            }
        });
    }

    /** Full-screen source editor; saves back to the same file and reloads the engine. */
    private void showEditor(final UserscriptMeta meta) {
        final Context ctx = getContext();
        final String original = readScriptSource(meta);
        if (original == null) {
            AcidFeedback.error(ctx, "无法读取脚本文件");
            return;
        }
        AcidDialogs.inputMultiLine(
            ctx, mIsDarkMode,
            "编辑「" + meta.name + "」",
            "脚本源码",
            original,
            "// ==UserScript== ...",
            12, 40,
            "保存",
            "脚本内容不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String input) {
                    if (input.equals(original.trim())) return true;
                    UserscriptMeta fresh = UserscriptMeta.parse(meta.fileName, input);
                    if (!fresh.hasMetadata) {
                        AcidFeedback.warn(ctx, "缺少 ==UserScript== 元数据块");
                        return false;
                    }
                    String err = mManager.installUserscript(meta.fileName, input);
                    if (err != null) {
                        AcidFeedback.error(ctx, "保存失败: " + err);
                        return false;
                    }
                    AcidFeedback.ok(ctx, "已保存「" + fresh.name + "」");
                    if (mActionListener != null) mActionListener.onScriptsChanged();
                    refreshActivePage();
                    return true;
                }
            }
        );
    }

    private String readScriptSource(UserscriptMeta meta) {
        java.io.File f = new java.io.File(mManager.getUserscriptsDir(), meta.fileName);
        if (!f.exists()) return null;
        String src = AcidIo.readFileUtf8OrEmpty(f, 20 * 1024 * 1024);
        return src.isEmpty() ? null : src;
    }

    /** Checks {@code @updateURL} in the background and offers to install a newer version. */
    private void checkUpdate(final UserscriptMeta meta) {
        final Context ctx = getContext();
        if (UserscriptManager.updateUrlOf(meta).isEmpty()) {
            AcidFeedback.warn(ctx, "该脚本未声明 @updateURL / @downloadURL");
            return;
        }
        AcidFeedback.info(ctx, "正在检查更新...");
        AcidAsync.run(new AcidAsync.Task<UserscriptManager.UpdateInfo>() {
            @Override
            public UserscriptManager.UpdateInfo execute() {
                return mManager.checkForUpdate(meta);
            }
        }, new AcidAsync.Callback<UserscriptManager.UpdateInfo>() {
            @Override
            public void onSuccess(final UserscriptManager.UpdateInfo info) {
                if (info == null) {
                    AcidFeedback.warn(ctx, "检查更新失败，请检查网络或更新源");
                    return;
                }
                if (!info.newer) {
                    AcidFeedback.ok(ctx, "已是最新版本 v" + meta.version);
                    return;
                }
                AcidDialogs.confirmDanger(
                    ctx, mIsDarkMode,
                    "发现新版本",
                    "「" + meta.name + "」v" + meta.version + " → v" + info.remoteVersion + "，是否更新？",
                    "更新",
                    new Runnable() {
                        @Override
                        public void run() {
                            String err = mManager.applyUpdate(meta, info.remoteContent);
                            if (err == null) {
                                AcidFeedback.ok(ctx, "已更新到 v" + info.remoteVersion);
                                if (mActionListener != null) mActionListener.onScriptsChanged();
                                setRootPage(buildScriptsListPage());
                            } else {
                                AcidFeedback.error(ctx, "更新失败: " + err);
                            }
                        }
                    }
                );
            }

            @Override
            public void onError(Exception error) {
                AcidFeedback.error(ctx, "检查更新失败");
            }
        });
    }

    private void confirmDelete(final UserscriptMeta meta) {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "删除脚本",
            "确定删除用户脚本「" + meta.name + "」(" + meta.fileName + ") 吗？",
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    boolean ok = mManager != null && mManager.deleteUserscript(meta.fileName);
                    AcidFeedback.info(ctx, ok ? "已删除「" + meta.name + "」" : "删除失败");
                    if (isShowing()) {
                        setRootPage(buildScriptsListPage());
                    }
                    if (mActionListener != null) {
                        mActionListener.onScriptsChanged();
                    }
                }
            }
        );
    }

    // =========================================================================
    // Script Import & Remote .user.js Installer
    // =========================================================================

    public void showImportDialog() {
        final Context ctx = getContext();
        AcidDialogs.inputMultiLine(
            ctx, mIsDarkMode,
            "导入用户脚本",
            "在线脚本 URL (.user.js) 或完整脚本源码",
            "",
            "https://.../script.user.js 或 // ==UserScript==...",
            4, 8,
            "解析并安装",
            "请输入脚本链接或源码",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String input) {
                    if (input.startsWith("http://") || input.startsWith("https://")) {
                        requestInstallFromUrl(input, "script.user.js");
                        return true;
                    } else if (input.contains("==UserScript==")) {
                        showInstallConfirmDialog(
                            "手动导入",
                            "custom_" + (System.currentTimeMillis() % 10000) + ".user.js",
                            input
                        );
                        return true;
                    } else {
                        AcidFeedback.warn(ctx, "未检测到有效 URL 或 ==UserScript== 元数据头");
                        return false;
                    }
                }
            }
        );
    }

    public void requestInstallFromUrl(final String url, final String fileName) {
        final Context ctx = getContext();
        AcidFeedback.info(ctx, "正在拉取脚本: " + fileName + "...");
        AcidAsync.run(new AcidAsync.Task<String>() {
            @Override
            public String execute() throws Exception {
                return AcidHttp.fetchText(url, 8000, 12000, AcidHttp.DEFAULT_MAX_BYTES);
            }
        }, new AcidAsync.Callback<String>() {
            @Override
            public void onSuccess(String scriptContent) {
                if (scriptContent == null || scriptContent.trim().isEmpty()) {
                    AcidFeedback.warn(ctx, "获取脚本失败，内容为空");
                    return;
                }
                showInstallConfirmDialog(url, fileName, scriptContent);
            }

            @Override
            public void onError(Exception error) {
                AcidFeedback.error(ctx, "获取脚本失败，请检查网络连接");
            }
        });
    }

    public void showInstallConfirmDialog(
        String sourceLabel,
        final String fileName,
        final String content
    ) {
        final Context ctx = getContext();
        final UserscriptMeta meta = UserscriptMeta.parse(fileName, content);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView tvName = new TextView(ctx);
        tvName.setText(meta.name + (!TextUtils.isEmpty(meta.version) ? " v" + meta.version : ""));
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setTextColor(AcidTheme.text(mIsDarkMode));
        box.addView(tvName);

        if (!TextUtils.isEmpty(meta.description)) {
            TextView tvDesc = new TextView(ctx);
            tvDesc.setText(meta.description);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
            tvDesc.setTextColor(AcidTheme.text(mIsDarkMode));
            tvDesc.setPadding(0, AcidTheme.dp(ctx, 4), 0, 0);
            box.addView(tvDesc);
        }

        boolean en = AcidLocale.isEn();
        StringBuilder scopeSb = new StringBuilder();
        scopeSb.append(en ? "Scope: " : "生效范围: ").append(AcidLocale.tr(meta.describeScope()))
            .append(en ? "\nRun At: " : "\n注入时机: ").append(meta.runAt);
        if (!meta.requires.isEmpty()) {
            scopeSb.append(en ? "\nRequires: " : "\n外部依赖: ").append(meta.requires.size())
                .append(en ? " libraries (auto-cached in background)" : " 个库 (安装后自动后台缓存)");
        }
        if (!meta.grants.isEmpty()) {
            scopeSb.append(en ? "\nGrants: " : "\n权限声明: ");
            for (int i = 0; i < meta.grants.size(); i++) {
                if (i > 0) scopeSb.append(", ");
                scopeSb.append(meta.grants.get(i));
            }
        }
        scopeSb.append(en ? "\nSource: " : "\n脚本来源: ").append(AcidLocale.tr(sourceLabel));

        TextView tvScope = new TextView(ctx);
        tvScope.setText(scopeSb.toString());
        tvScope.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        tvScope.setTextColor(AcidTheme.muted(mIsDarkMode));
        tvScope.setLineSpacing(AcidTheme.dp(ctx, 2.5f), 1f);
        tvScope.setPadding(0, AcidTheme.dp(ctx, 8), 0, AcidTheme.dp(ctx, 10));
        box.addView(tvScope);

        box.addView(AcidWidgets.codePreviewBox(ctx, mIsDarkMode, content, 360));

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("安装用户脚本")
            .setCustomView(box)
            .setPositiveButton("安装", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (mManager == null) return true;
                    String err = mManager.installUserscript(fileName, content);
                    if (err == null) {
                        AcidFeedback.ok(ctx, "已成功安装脚本「" + meta.name + "」");
                        if (isShowing()) {
                            setRootPage(buildScriptsListPage());
                        }
                        if (mActionListener != null) {
                            mActionListener.onScriptsChanged();
                        }
                        return true;
                    } else {
                        AcidFeedback.error(ctx, "安装失败: " + err);
                        return false;
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
