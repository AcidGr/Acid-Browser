package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.Log;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Userscript Management Sheet & Installer Coordinator.
 *
 * <p>Consolidates script listing, toggling, detail inspection, manual code/URL import,
 * and remote {@code .user.js} install confirmation into one cohesive component using
 * {@link AcidBottomSheetView}, {@link AcidWidgets}, {@link AcidContextMenu}, and {@link AcidDialog}.
 */
public class UserscriptSheet extends AcidBottomSheetView {

    private static final String TAG = "AcidUserscriptSheet";

    public interface OnScriptActionListener {
        void onRunScript(UserscriptMeta meta);
        void onScriptsChanged();
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
    }

    public void showScripts() {
        renderScripts();
        show();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            renderScripts();
        }
    }

    public void renderScripts() {
        if (mManager == null) return;
        mManager.invalidateCache();

        setTitle("脚本管理");
        setAction(AcidIcons.ICON_PLUS, "导入脚本", AcidTheme.accent(mIsDarkMode), new OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportDialog();
            }
        });

        clearPinnedHeader();
        LinearLayout container = getContentContainer();
        container.removeAllViews();

        Context ctx = getContext();
        List<UserscriptMeta> scripts = mManager.getScripts();
        if (scripts.isEmpty()) {
            container.addView(AcidWidgets.emptyState(
                ctx, mIsDarkMode, AcidIcons.ICON_FILE, "尚未安装任何用户脚本，点击右上角可导入"
            ));
            return;
        }

        container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "已安装脚本 (" + scripts.size() + ")"));
        for (final UserscriptMeta meta : scripts) {
            container.addView(buildScriptRow(ctx, meta));
        }
    }

    private View buildScriptRow(final Context ctx, final UserscriptMeta meta) {
        final LinearLayout row = AcidWidgets.listItemCard(ctx, mIsDarkMode, false, 0);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int glyphColor = meta.enabled ? AcidTheme.accent(mIsDarkMode) : AcidTheme.iconDisabled(mIsDarkMode);
        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_FILE, glyphColor, 19);
        row.addView(icon);

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        tLp.leftMargin = AcidTheme.dp(ctx, 10);
        tLp.rightMargin = AcidTheme.dp(ctx, 8);

        TextView title = new TextView(ctx);
        title.setText(meta.name);
        title.setTextColor(meta.enabled ? AcidTheme.text(mIsDarkMode) : AcidTheme.muted(mIsDarkMode));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
        title.setTypeface(meta.enabled ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textBox.addView(title);

        TextView sub = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        if (!meta.enabled) sb.append("已停用 · ");
        sb.append(meta.describeScope());
        if (!TextUtils.isEmpty(meta.version)) sb.append(" · v").append(meta.version);
        sb.append(" · ").append(meta.runAt);
        sub.setText(sb.toString());
        sub.setTextColor(AcidTheme.muted(mIsDarkMode));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
        textBox.addView(sub);

        row.addView(textBox, tLp);

        final Switch sw = new Switch(ctx);
        sw.setChecked(meta.enabled);
        AcidWidgets.styleSwitch(sw, mIsDarkMode, meta.enabled);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AcidWidgets.styleSwitch(sw, mIsDarkMode, isChecked);
                if (mManager != null && meta.enabled != isChecked) {
                    mManager.setScriptEnabled(meta.fileName, isChecked);
                    meta.enabled = isChecked;
                    Toast.makeText(ctx, (isChecked ? "已启用「" : "已停用「") + meta.name + "」", Toast.LENGTH_SHORT).show();
                    renderScripts();
                    if (mActionListener != null) {
                        mActionListener.onScriptsChanged();
                    }
                }
            }
        });
        row.addView(sw);

        if (!meta.enabled) {
            row.setAlpha(0.72f);
        }

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showScriptDetail(meta);
            }
        });

        row.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showScriptContextMenu(row, meta);
                return true;
            }
        });

        return row;
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
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_INFO, "查看脚本详情", new Runnable() {
            @Override
            public void run() {
                showScriptDetail(meta);
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
                    renderScripts();
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

    private void showScriptDetail(final UserscriptMeta meta) {
        final Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        if (!TextUtils.isEmpty(meta.description)) {
            TextView desc = new TextView(ctx);
            desc.setText(meta.description);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
            desc.setTextColor(AcidTheme.text(mIsDarkMode));
            desc.setPadding(0, 0, 0, AcidTheme.dp(ctx, 8));
            box.addView(desc);
        }

        TextView info = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(meta.enabled ? "已启用 (随匹配页面自动执行)" : "已停用").append("\n");
        sb.append("生效范围: ").append(meta.describeScope()).append("\n");
        sb.append("注入时机: ").append(meta.runAt).append("\n");
        if (!TextUtils.isEmpty(meta.version)) sb.append("版本: v").append(meta.version).append("\n");
        sb.append("文件名: ").append(meta.fileName);
        info.setText(sb.toString());
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        info.setTextColor(AcidTheme.muted(mIsDarkMode));
        info.setLineSpacing(AcidTheme.dp(ctx, 2.5f), 1f);
        info.setPadding(0, 0, 0, AcidTheme.dp(ctx, 10));
        box.addView(info);

        box.addView(AcidWidgets.codePreviewBox(ctx, mIsDarkMode, meta.body, 400));

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle(meta.name)
            .setCustomView(box)
            .setNeutralButton("删除", true, new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    dialog.dismiss();
                    confirmDelete(meta);
                    return false;
                }
            })
            .setNegativeButton("关闭", null)
            .setPositiveButton("当前页运行", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (mActionListener != null) mActionListener.onRunScript(meta);
                    return true;
                }
            })
            .show();
    }

    private void confirmDelete(final UserscriptMeta meta) {
        final Context ctx = getContext();
        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("删除脚本")
            .setMessage("确定删除用户脚本「" + meta.name + "」(" + meta.fileName + ") 吗？")
            .setDangerButton("删除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    boolean ok = mManager != null && mManager.deleteUserscript(meta.fileName);
                    Toast.makeText(ctx, ok ? "已删除「" + meta.name + "」" : "删除失败", Toast.LENGTH_SHORT).show();
                    if (isShowing()) {
                        renderScripts();
                    }
                    if (mActionListener != null) {
                        mActionListener.onScriptsChanged();
                    }
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // =========================================================================
    // Script Import & Remote .user.js Installer
    // =========================================================================

    public void showImportDialog() {
        final Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "在线脚本 URL (.user.js) 或完整脚本源码"));
        final EditText etInput = AcidWidgets.inputField(
            ctx, mIsDarkMode, "", "https://.../script.user.js 或 // ==UserScript==...", false
        );
        etInput.setMinLines(4);
        etInput.setMaxLines(8);
        etInput.setGravity(Gravity.TOP | Gravity.START);
        box.addView(etInput);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("导入用户脚本")
            .setCustomView(box)
            .setPositiveButton("解析并安装", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String input = etInput.getText() != null ? etInput.getText().toString().trim() : "";
                    if (input.isEmpty()) {
                        Toast.makeText(ctx, "请输入脚本链接或源码", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    if (input.startsWith("http://") || input.startsWith("https://")) {
                        requestInstallFromUrl(input, "script.user.js");
                        return true;
                    } else if (input.contains("==UserScript==")) {
                        showInstallConfirmDialog("手动导入", "custom_" + (System.currentTimeMillis() % 10000) + ".user.js", input);
                        return true;
                    } else {
                        Toast.makeText(ctx, "未检测到有效 URL 或 ==UserScript== 元数据头", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public void requestInstallFromUrl(final String url, final String fileName) {
        final Context ctx = getContext();
        Toast.makeText(ctx, "正在拉取脚本: " + fileName + "...", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String fetchedContent = null;
                try {
                    URL u = new URL(url);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    conn.setRequestProperty("User-Agent", "AcidBrowser/" + AcidTheme.appVersionName(ctx));
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[2048];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            if (bos.size() > 20 * 1024 * 1024) break;
                            bos.write(buf, 0, len);
                        }
                        in.close();
                        fetchedContent = new String(bos.toByteArray(), "UTF-8");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to download userscript from " + url, e);
                }

                final String scriptContent = fetchedContent;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (scriptContent == null || scriptContent.trim().isEmpty()) {
                            Toast.makeText(ctx, "获取脚本失败，请检查网络连接", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showInstallConfirmDialog(url, fileName, scriptContent);
                    }
                });
            }
        }).start();
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
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H3_CARD);
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

        TextView tvScope = new TextView(ctx);
        tvScope.setText("生效范围: " + meta.describeScope() + "\n注入时机: " + meta.runAt + "\n脚本来源: " + sourceLabel);
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
                        Toast.makeText(ctx, "已成功安装脚本「" + meta.name + "」", Toast.LENGTH_SHORT).show();
                        if (isShowing()) {
                            renderScripts();
                        }
                        if (mActionListener != null) {
                            mActionListener.onScriptsChanged();
                        }
                        return true;
                    } else {
                        Toast.makeText(ctx, "安装失败: " + err, Toast.LENGTH_LONG).show();
                        return false;
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
