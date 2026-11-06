package com.acid.browser;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Skill Management Sheet supporting Level-1 list (with enable/disable switches),
 * Level-2 detail viewing/editing (including multi-file Skill Package companion files),
 * Level-3 companion file inspection/editing, and importing skills from GitHub folders,
 * GitHub files, ZIP archives, or Markdown URLs into {@code acid_workspace/skills/}.
 */
public class SkillSheet extends AcidBottomSheetView {

    private final SkillManager mManager;
    private boolean mSuppressRefreshOnToggle = false;

    public SkillSheet(Context context, SkillManager manager) {
        super(context);
        mManager = manager;
        if (mManager != null) {
            mManager.addListener(new SkillManager.OnSkillsChangedListener() {
                @Override
                public void onSkillsChanged() {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (mSuppressRefreshOnToggle) return;
                            if (isShowing()) {
                                if (getPageDepth() <= 1) {
                                    setRootPage(buildSkillsListPage());
                                } else {
                                    refreshActivePage();
                                }
                            }
                        }
                    });
                }
            });
        }
        setRootPage(buildSkillsListPage());
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildSkillsListPage());
    }

    public void renderContent() {
        setRootPage(buildSkillsListPage());
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildSkillsListPage());
        } else {
            refreshActivePage();
        }
        super.show();
    }

    // =========================================================================
    // LEVEL 1: Skills List Page
    // =========================================================================

    private SheetPage buildSkillsListPage() {
        return new SheetPage("技能管理 (Skills)", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                if (mManager == null) return;
                Context ctx = getContext();
                List<SkillManager.SkillMeta> skills = mManager.getSkills();

                if (skills.isEmpty()) {
                    container.addView(AcidWidgets.emptyState(
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, "暂无任何技能，点击右上角可添加或从 GitHub 导入"
                    ));
                    return;
                }

                List<SkillManager.SkillMeta> builtins = new ArrayList<SkillManager.SkillMeta>();
                List<SkillManager.SkillMeta> customs = new ArrayList<SkillManager.SkillMeta>();
                for (SkillManager.SkillMeta m : skills) {
                    if (m.isBuiltin) builtins.add(m);
                    else customs.add(m);
                }

                if (!builtins.isEmpty()) {
                    container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "内置核心技能 (" + builtins.size() + ")"));
                    for (SkillManager.SkillMeta m : builtins) {
                        container.addView(buildSkillRow(ctx, m));
                    }
                }

                if (!customs.isEmpty()) {
                    container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "自定义与导入技能 (" + customs.size() + ")"));
                    for (SkillManager.SkillMeta m : customs) {
                        container.addView(buildSkillRow(ctx, m));
                    }
                }
            }
        }).withAction(AcidIcons.ICON_PLUS, "添加技能", new OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddOrImportSkillPicker();
            }
        });
    }

    private static String formatSkillSubtitle(SkillManager.SkillMeta meta) {
        StringBuilder sb = new StringBuilder();
        if (!meta.enabled) {
            sb.append("已停用 · ");
        }
        if (meta.isBuiltin) {
            sb.append("[官方核心] ");
        } else if (meta.isDirectoryPackage && !meta.companionFiles.isEmpty()) {
            sb.append("[技能包 · ").append(meta.companionFiles.size() + 1).append("个文件] ");
        } else if (meta.isDirectoryPackage) {
            sb.append("[技能包] ");
        }
        sb.append(meta.description);
        return sb.toString();
    }

    private View buildSkillRow(final Context ctx, final SkillManager.SkillMeta meta) {
        final Switch sw = new Switch(ctx);
        sw.setChecked(meta.enabled);
        AcidWidgets.styleSwitch(sw, mIsDarkMode, meta.enabled);

        int activeIconCol = meta.isBuiltin ? AcidTheme.accent(mIsDarkMode) : AcidTheme.COLOR_BLUE;
        final AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
            .leadingIcon(
                AcidIcons.ICON_AGENT,
                meta.enabled ? activeIconCol : AcidTheme.iconDisabled(mIsDarkMode),
                19
            )
            .title(meta.title)
            .subtitle(formatSkillSubtitle(meta))
            .dimmed(!meta.enabled)
            .trailingView(sw);

        final LinearLayout card = rowBuilder.build(ctx, mIsDarkMode);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AcidWidgets.styleSwitch(sw, mIsDarkMode, isChecked);
                if (mManager != null && meta.enabled != isChecked) {
                    mSuppressRefreshOnToggle = true;
                    try {
                        mManager.setSkillEnabled(meta.fileName, isChecked);
                    } finally {
                        mSuppressRefreshOnToggle = false;
                    }
                    meta.enabled = isChecked;
                    if (rowBuilder.iconView != null) {
                        rowBuilder.iconView.setColor(
                            isChecked ? activeIconCol : AcidTheme.iconDisabled(mIsDarkMode)
                        );
                    }
                    if (rowBuilder.titleView != null) {
                        rowBuilder.titleView.setTextColor(
                            isChecked ? AcidTheme.text(mIsDarkMode) : AcidTheme.muted(mIsDarkMode)
                        );
                        rowBuilder.titleView.setTypeface(isChecked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    }
                    if (rowBuilder.subtitleView != null) {
                        rowBuilder.subtitleView.setText(AcidLocale.tr(formatSkillSubtitle(meta)));
                    }
                    card.setAlpha(isChecked ? 1f : 0.75f);
                    AcidFeedback.info(ctx, (isChecked ? "已启用技能「" : "已停用技能「") + meta.title + "」");
                }
            }
        });

        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pushPage(buildSkillDetailPage(meta));
            }
        });

        card.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showSkillContextMenu(card, meta);
                return true;
            }
        });

        return card;
    }

    private void showSkillContextMenu(View anchor, final SkillManager.SkillMeta meta) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_INFO, "查看技能详情", new Runnable() {
            @Override
            public void run() {
                pushPage(buildSkillDetailPage(meta));
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(
            AcidIcons.ICON_CHECK,
            meta.enabled ? "停用技能" : "启用技能",
            new Runnable() {
                @Override
                public void run() {
                    if (mManager != null) {
                        boolean next = !meta.enabled;
                        mManager.setSkillEnabled(meta.fileName, next);
                        meta.enabled = next;
                        refreshActivePage();
                    }
                }
            }
        ));
        if (meta.isBuiltin) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "重置为官方默认", new Runnable() {
                @Override
                public void run() {
                    confirmResetBuiltin(meta);
                }
            }));
        } else {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除技能", true, new Runnable() {
                @Override
                public void run() {
                    confirmDelete(meta);
                }
            }));
        }
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, meta.title, actions);
    }

    // =========================================================================
    // LEVEL 2: Skill Detail Page (with Bundled Companion Files Support)
    // =========================================================================

    private SheetPage buildSkillDetailPage(final SkillManager.SkillMeta meta) {
        SheetPage page = new SheetPage(meta.title, new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container, LinearLayout footer) {
                final Context ctx = getContext();
                final String content = mManager != null ? mManager.readSkill(meta.fileName) : "";
                boolean en = AcidLocale.isEn();

                // 1. Metadata Card
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "技能元数据"));
                LinearLayout metaCard = AcidWidgets.contentCard(ctx, mIsDarkMode);
                TextView tvMeta = new TextView(ctx);
                StringBuilder sb = new StringBuilder();
                sb.append(en ? "Name: " : "技能标识 (Name): ").append(meta.name).append("\n");
                sb.append(en ? "Entry Path: skills/" : "入口路径: skills/").append(meta.fileName).append("\n");
                sb.append(en ? "Status: " : "技能状态: ").append(meta.enabled
                    ? (en ? "Enabled (Injected on demand)" : "已启用 (按需注入 Agent)")
                    : (en ? "Disabled (Not injected)" : "已停用 (不注入 System Prompt)")).append("\n");
                if (meta.isBuiltin) {
                    sb.append(en ? "Type: Official Core Builtin\n" : "技能类型: 官方核心预置 (Core Builtin)\n");
                } else if (meta.isDirectoryPackage) {
                    sb.append(en ? "Type: Multi-file Skill Package (" : "技能类型: 标准多文件技能包 (含 ")
                      .append(meta.companionFiles.size()).append(en ? " companion files)\n" : " 个附属文件)\n");
                } else {
                    sb.append(en ? "Type: Single-file Markdown Skill\n" : "技能类型: 单文件扩展技能 (Markdown)\n");
                }
                sb.append(en ? "Total Size: " : "总计大小: ").append(String.format(Locale.US, "%.1f KB", meta.fileSize / 1024.0)).append("\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                sb.append(en ? "Last Modified: " : "最后修改: ").append(sdf.format(new Date(meta.lastModified)));
                tvMeta.setText(sb.toString());
                tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvMeta.setTextColor(AcidTheme.muted(mIsDarkMode));
                tvMeta.setLineSpacing(AcidTheme.dp(ctx, 3), 1f);
                metaCard.addView(tvMeta);
                container.addView(metaCard);

                // 2. Companion Files Section (for Directory Skill Packages)
                if (meta.isDirectoryPackage && !meta.companionFiles.isEmpty()) {
                    container.addView(AcidWidgets.sectionHeader(
                        ctx, mIsDarkMode,
                        "附属参考文档与脚本 (" + meta.companionFiles.size() + ")"
                    ));
                    LinearLayout compGroup = AcidWidgets.cardGroup(ctx, mIsDarkMode);
                    for (int i = 0; i < meta.companionFiles.size(); i++) {
                        final String compRel = meta.companionFiles.get(i);
                        final String fullRelUnderSkills = meta.packageDirName + "/" + compRel;
                        File compFile = mManager != null ? mManager.resolveSkillFile(fullRelUnderSkills) : null;
                        long compLen = compFile != null && compFile.exists() ? compFile.length() : 0L;
                        String sub = String.format(Locale.getDefault(), "skills/%s · %.1f KB", fullRelUnderSkills, compLen / 1024.0);
                        if (i > 0) {
                            AcidWidgets.addDivider(ctx, mIsDarkMode, compGroup);
                        }
                        compGroup.addView(AcidWidgets.navRow(
                            ctx, mIsDarkMode,
                            compRel.startsWith("scripts/") || compRel.endsWith(".sh") || compRel.endsWith(".js") || compRel.endsWith(".py")
                                ? AcidIcons.ICON_TERMINAL : AcidIcons.ICON_FILE,
                            AcidTheme.icon(mIsDarkMode),
                            compRel,
                            sub,
                            new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    pushPage(buildCompanionFilePage(meta, compRel, fullRelUnderSkills));
                                }
                            }
                        ));
                    }
                    container.addView(compGroup);
                }

                // 3. Entry Markdown Preview Box
                container.addView(AcidWidgets.sectionHeader(
                    ctx, mIsDarkMode,
                    meta.isDirectoryPackage ? "主入口文档 (SKILL.md)" : "Markdown 规则全文"
                ));
                container.addView(AcidWidgets.codePreviewBox(ctx, mIsDarkMode, content, 6000));

                // 4. Footer Buttons
                TextView btnCopy = AcidWidgets.secondaryButton(
                    ctx, mIsDarkMode, "复制全文", AcidTheme.text(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            AcidIo.copyToClipboard(ctx, "Acid Skill", content, "已复制技能 Markdown 全文");
                        }
                    }
                );

                TextView btnEdit = AcidWidgets.primaryButton(
                    ctx, mIsDarkMode, "编辑修改", AcidTheme.accent(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showEditSkillDialog(meta.fileName, content);
                        }
                    }
                );

                footer.addView(AcidWidgets.buttonRow(ctx, btnCopy, btnEdit));
            }
        });

        if (meta.isBuiltin) {
            page.withAction(AcidIcons.ICON_RELOAD, "重置默认", new OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmResetBuiltin(meta);
                }
            });
        } else {
            page.withAction(AcidIcons.ICON_TRASH, "删除", AcidTheme.COLOR_RED, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(meta);
                }
            });
        }

        return page.withRawTitle();
    }

    // =========================================================================
    // LEVEL 3: Companion File Viewer & Editor Page
    // =========================================================================

    private SheetPage buildCompanionFilePage(
        final SkillManager.SkillMeta parentMeta,
        final String companionRelPath,
        final String fullRelUnderSkills
    ) {
        return new SheetPage(companionRelPath, new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container, LinearLayout footer) {
                final Context ctx = getContext();
                final String content = mManager != null ? mManager.readSkill(fullRelUnderSkills) : "";

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "附属文件路径"));
                container.addView(AcidWidgets.descriptionText(ctx, mIsDarkMode, "skills/" + fullRelUnderSkills));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "文件内容"));
                container.addView(AcidWidgets.codePreviewBox(ctx, mIsDarkMode, content, 6000));

                TextView btnCopy = AcidWidgets.secondaryButton(
                    ctx, mIsDarkMode, "复制内容", AcidTheme.text(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            AcidIo.copyToClipboard(ctx, companionRelPath, content, "已复制附属文件内容");
                        }
                    }
                );

                TextView btnEdit = AcidWidgets.primaryButton(
                    ctx, mIsDarkMode, "编辑文件", AcidTheme.accent(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showEditSkillDialog(fullRelUnderSkills, content);
                        }
                    }
                );

                footer.addView(AcidWidgets.buttonRow(ctx, btnCopy, btnEdit));
            }
        }).withRawTitle();
    }

    private void confirmResetBuiltin(final SkillManager.SkillMeta meta) {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "重置内置技能",
            "确定将「" + meta.title + "」恢复为初始官方版本吗？你所做的自定义修改将被覆盖。",
            "重置",
            new Runnable() {
                @Override
                public void run() {
                    boolean ok = mManager != null && mManager.resetBuiltinSkill(meta.fileName);
                    AcidFeedback.info(ctx, ok ? "已重置为官方默认版本" : "重置失败");
                    refreshActivePage();
                }
            }
        );
    }

    private void confirmDelete(final SkillManager.SkillMeta meta) {
        final Context ctx = getContext();
        String msg = meta.isDirectoryPackage
            ? "确定删除技能包「" + meta.title + "」(skills/" + meta.packageDirName + "/ 及其全部附属文件) 吗？"
            : "确定删除技能「" + meta.title + "」(" + meta.fileName + ") 吗？";
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "删除技能",
            msg,
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    boolean ok = mManager != null && mManager.deleteSkill(meta.fileName);
                    AcidFeedback.info(ctx, ok ? "已删除技能「" + meta.title + "」" : "删除失败");
                    popPage();
                }
            }
        );
    }

    // =========================================================================
    // Dialogs: Import from GitHub / URL or Paste / Create Skill
    // =========================================================================

    private void showAddOrImportSkillPicker() {
        final Context ctx = getContext();
        String[] options = new String[] {
            "从 GitHub / 外部链接导入 (支持文件夹/单文件/ZIP)",
            "手动粘贴 / 编写 Markdown 技能"
        };
        AcidDialogs.chooseSingle(
            ctx, mIsDarkMode,
            "添加或导入技能",
            options,
            -1,
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int index) {
                    if (index == 0) {
                        showImportFromUrlDialog();
                    } else if (index == 1) {
                        showAddSkillDialog();
                    }
                }
            }
        );
    }

    private void showImportFromUrlDialog() {
        final Context ctx = getContext();
        String initialUrl = "";
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
            if (text != null) {
                String clip = text.toString().trim();
                if (clip.startsWith("http://") || clip.startsWith("https://") || clip.startsWith("github.com/")) {
                    initialUrl = clip;
                }
            }
        }

        AcidDialogs.inputSingleLine(
            ctx, mIsDarkMode,
            "从 GitHub / 外部链接导入技能",
            "支持 GitHub 技能目录链接 (tree/... 自動拉取整包附属文件)、单文件链接 (SKILL.md) 或 .zip 链接",
            initialUrl,
            "https://github.com/owner/repo/tree/main/skills/my-skill",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
            "立即导入",
            "请输入有效的 GitHub 或技能文件链接",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(final String url) {
                    if (mManager == null) return true;
                    AcidFeedback.info(ctx, "正在从远程拉取技能及附属文件...");
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            final SkillManager.ImportResult res = mManager.importSkillFromUrl(url);
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    if (res.success) {
                                        String msg = res.fileCount > 1
                                            ? "已导入技能包「" + res.title + "」(共 " + res.fileCount + " 个文件)"
                                            : "已导入技能「" + res.title + "」";
                                        AcidFeedback.ok(ctx, msg);
                                        setRootPage(buildSkillsListPage());
                                    } else {
                                        AcidFeedback.error(ctx, res.errorMessage);
                                    }
                                }
                            });
                        }
                    }, "AcidSkillUrlImporter").start();
                    return true;
                }
            }
        );
    }

    private void showAddSkillDialog() {
        final Context ctx = getContext();
        String initialContent = "";
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
            if (text != null && text.length() > 0) {
                String clip = text.toString();
                if (clip.startsWith("---") || clip.startsWith("#") || clip.contains("Skill")) {
                    initialContent = clip;
                }
            }
        }

        AcidDialogs.inputMultiLine(
            ctx, mIsDarkMode,
            "添加自定义技能 (Markdown)",
            "支持标准 YAML Frontmatter (--- name / description ---) 或 Markdown 标题格式...",
            initialContent,
            "---\nname: my-skill\ndescription: 简短描述何时触发该技能...\n---\n\n# 技能详细指南\n...",
            5, 12,
            "下一步",
            "内容不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(final String content) {
                    AcidDialogs.inputSingleLine(
                        ctx, mIsDarkMode,
                        "输入技能路径或名称",
                        "支持单文件名 (如 my_skill.md) 或技能包路径 (如 my-skill/SKILL.md)",
                        "custom_skill.md",
                        "custom_skill.md 或 my-skill/SKILL.md",
                        InputType.TYPE_CLASS_TEXT,
                        "保存",
                        "技能名不能为空",
                        new AcidDialogs.InputSubmitListener() {
                            @Override
                            public boolean onSubmit(String fileName) {
                                String cleanName = fileName.trim();
                                if (!cleanName.contains("/") && !cleanName.toLowerCase(Locale.US).endsWith(".md")) {
                                    cleanName += ".md";
                                }
                                boolean ok = mManager != null && mManager.saveSkill(cleanName, content);
                                if (ok) {
                                    AcidFeedback.ok(ctx, "技能「" + cleanName + "」已保存");
                                    setRootPage(buildSkillsListPage());
                                    return true;
                                } else {
                                    AcidFeedback.warn(ctx, "保存技能失败");
                                    return false;
                                }
                            }
                        }
                    );
                    return true;
                }
            }
        );
    }

    private void showEditSkillDialog(final String relSkillPath, String currentContent) {
        final Context ctx = getContext();
        AcidDialogs.inputMultiLine(
            ctx, mIsDarkMode,
            "编辑: " + relSkillPath,
            "编辑文件内容...",
            currentContent,
            "",
            6, 15,
            "保存修改",
            "内容不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String content) {
                    boolean ok = mManager != null && mManager.saveSkill(relSkillPath, content);
                    if (ok) {
                        AcidFeedback.ok(ctx, "已更新 " + relSkillPath);
                        refreshActivePage();
                        return true;
                    } else {
                        AcidFeedback.warn(ctx, "保存失败");
                        return false;
                    }
                }
            }
        );
    }
}
