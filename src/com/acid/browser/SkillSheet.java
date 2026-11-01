package com.acid.browser;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Skill Management Sheet supporting Level-1 list, Level-2 detail viewing/editing,
 * and importing third-party Markdown skills into {@code acid_workspace/skills/}.
 */
public class SkillSheet extends AcidBottomSheetView {

    private final SkillManager mManager;

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
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, "暂无任何技能，点击右上角可添加"
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
                    container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "自定义扩展技能 (" + customs.size() + ")"));
                    for (SkillManager.SkillMeta m : customs) {
                        container.addView(buildSkillRow(ctx, m));
                    }
                }
            }
        }).withAction(AcidIcons.ICON_PLUS, "添加技能", new OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddSkillDialog();
            }
        });
    }

    private View buildSkillRow(final Context ctx, final SkillManager.SkillMeta meta) {
        int iconCol = meta.isBuiltin ? AcidTheme.accent(mIsDarkMode) : AcidTheme.icon(mIsDarkMode);
        String subtitle = meta.description;
        if (meta.isBuiltin) {
            subtitle = "[官方核心] " + subtitle;
        }

        return AcidWidgets.navRow(
            ctx, mIsDarkMode, AcidIcons.ICON_AGENT, iconCol,
            meta.title, subtitle,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pushPage(buildSkillDetailPage(meta));
                }
            }
        );
    }

    // =========================================================================
    // LEVEL 2: Skill Detail Page
    // =========================================================================

    private SheetPage buildSkillDetailPage(final SkillManager.SkillMeta meta) {
        SheetPage page = new SheetPage(meta.title, new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container, LinearLayout footer) {
                final Context ctx = getContext();
                final String content = mManager != null ? mManager.readSkill(meta.fileName) : "";

                // Metadata Card
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "技能元数据"));
                LinearLayout metaCard = AcidWidgets.contentCard(ctx, mIsDarkMode);
                TextView tvMeta = new TextView(ctx);
                StringBuilder sb = new StringBuilder();
                sb.append("文件名称: ").append(meta.fileName).append("\n");
                sb.append("技能类型: ").append(meta.isBuiltin ? "官方核心预置 (Core Builtin)" : "自定义扩展 (Custom)").append("\n");
                sb.append("文件大小: ").append(String.format(Locale.getDefault(), "%.1f KB", meta.fileSize / 1024.0)).append("\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                sb.append("最后修改: ").append(sdf.format(new Date(meta.lastModified)));
                tvMeta.setText(sb.toString());
                tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvMeta.setTextColor(AcidTheme.muted(mIsDarkMode));
                tvMeta.setLineSpacing(AcidTheme.dp(ctx, 3), 1f);
                metaCard.addView(tvMeta);
                container.addView(metaCard);

                // Code/Markdown Preview Box
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "Markdown 规则全文"));
                container.addView(AcidWidgets.codePreviewBox(ctx, mIsDarkMode, content, 5000));

                // Footer Buttons
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
                            showEditSkillDialog(meta, content);
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

        return page;
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
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "删除技能",
            "确定删除技能「" + meta.title + "」(" + meta.fileName + ") 吗？",
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
    // Dialogs: Add & Edit Skill
    // =========================================================================

    private void showAddSkillDialog() {
        final Context ctx = getContext();
        String initialContent = "";
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
            if (text != null && text.length() > 0 && (text.toString().startsWith("#") || text.toString().contains("Skill"))) {
                initialContent = text.toString();
            }
        }

        AcidDialogs.inputMultiLine(
            ctx, mIsDarkMode,
            "添加自定义技能 (Markdown)",
            "粘贴完整的 Skill Markdown 规则...",
            initialContent,
            "# Skill: ...\n\n> 简短描述...\n",
            5, 12,
            "保存技能",
            "内容不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(final String content) {
                    // Ask for file name
                    AcidDialogs.inputSingleLine(
                        ctx, mIsDarkMode,
                        "输入技能文件名",
                        "文件名 (如 my_skill.md)",
                        "custom_skill.md",
                        "custom_skill.md",
                        android.text.InputType.TYPE_CLASS_TEXT,
                        "保存",
                        "文件名不能为空",
                        new AcidDialogs.InputSubmitListener() {
                            @Override
                            public boolean onSubmit(String fileName) {
                                String cleanName = fileName.trim();
                                if (!cleanName.endsWith(".md")) cleanName += ".md";
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

    private void showEditSkillDialog(final SkillManager.SkillMeta meta, String currentContent) {
        final Context ctx = getContext();
        AcidDialogs.inputMultiLine(
            ctx, mIsDarkMode,
            "编辑技能: " + meta.fileName,
            "编辑 Markdown 规则...",
            currentContent,
            "",
            6, 15,
            "保存修改",
            "内容不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String content) {
                    boolean ok = mManager != null && mManager.saveSkill(meta.fileName, content);
                    if (ok) {
                        AcidFeedback.ok(ctx, "技能已更新");
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
