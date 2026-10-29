package com.acid.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Bottom sheet listing every installed userscript with its declared scope.
 *
 * <p>Before this the engine had {@code listUserscriptsJson()} but nothing called it, so a
 * user had no way to see which scripts were running, on which sites, or to remove one.
 */
public class UserscriptSheet extends AcidBottomSheetView {

    /** Host callbacks; keeps the sheet free of browser state. */
    public interface OnScriptActionListener {
        /** Runs a script once on the current page. */
        void onRunScript(UserscriptMeta meta);
        /** Asks the host to open the install dialog for a local file. */
        void onImportRequested();
        /** Notifies host when a script's enabled state was toggled. */
        void onScriptToggle(UserscriptMeta meta, boolean enabled);
    }

    private final UserscriptManager mManager;
    private final OnScriptActionListener mActionListener;
    private boolean mIsDarkMode = false;

    public UserscriptSheet(Context context, UserscriptManager manager,
                           OnScriptActionListener listener) {
        super(context);
        mManager = manager;
        mActionListener = listener;
    }

    @Override
    public void applyTheme(boolean isDark) {
        super.applyTheme(isDark);
        mIsDarkMode = isDark;
    }

    public void showScripts() {
        setTitle("脚本管理");
        int accent = AcidTheme.accent(mIsDarkMode);
        setAction(AcidIcons.ICON_PLUS, "导入脚本", accent, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mActionListener != null) mActionListener.onImportRequested();
            }
        });
        renderScripts();
        show();
    }

    /** Re-reads the directory and rebuilds the list; called after install/delete. */
    public void renderScripts() {
        if (mManager == null) return;
        mManager.invalidateCache();

        LinearLayout container = getContentContainer();
        container.removeAllViews();

        Context ctx = getContext();
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        List<UserscriptMeta> scripts = mManager.getScripts();
        if (scripts.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("还没有安装任何脚本");
            empty.setTextColor(mutedCol);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, AcidTheme.dp(ctx, 30), 0, AcidTheme.dp(ctx, 30));
            container.addView(empty);
            return;
        }

        for (final UserscriptMeta meta : scripts) {
            container.addView(buildScriptRow(ctx, meta, textCol, mutedCol));
        }
    }

    private View buildScriptRow(final Context ctx, final UserscriptMeta meta,
                                int textCol, int mutedCol) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 10);
        int padV = AcidTheme.dp(ctx, 10);
        row.setPadding(padH, padV, padH, padV);
        applyRowBackground(row);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.bottomMargin = AcidTheme.dp(ctx, 6);

        // Glyph: the agent-drawn icon when present, otherwise a generic file icon.
        AcidIcons.SvgIconView icon;
        int glyphColor = meta.enabled
            ? (!TextUtils.isEmpty(meta.iconPath) ? AcidTheme.accent(mIsDarkMode) : AcidTheme.muted(mIsDarkMode))
            : AcidTheme.iconDisabled(mIsDarkMode);
        if (!TextUtils.isEmpty(meta.iconPath)) {
            icon = new AcidIcons.SvgIconView(ctx, meta.iconPath, glyphColor, 19);
        } else {
            icon = new AcidIcons.SvgIconView(
                ctx, AcidIcons.ICON_FILE, glyphColor, 19);
        }
        row.addView(icon);

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 10);
        tLp.rightMargin = AcidTheme.dp(ctx, 8);

        TextView title = new TextView(ctx);
        title.setText(meta.name);
        title.setTextColor(meta.enabled ? textCol : mutedCol);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTypeface(meta.enabled ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textBox.addView(title);

        TextView sub = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        if (!meta.enabled) {
            sb.append("[已停用] · ");
        }
        sb.append(meta.describeScope());
        if (!TextUtils.isEmpty(meta.version)) sb.append(" · v").append(meta.version);
        sb.append(" · ").append(meta.runAt);
        sub.setText(sb.toString());
        sub.setTextColor(mutedCol);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
        textBox.addView(sub);

        row.addView(textBox, tLp);

        // Right side: Toggle Switch
        final Switch sw = new Switch(ctx);
        sw.setChecked(meta.enabled);
        if (Build.VERSION.SDK_INT >= 23) {
            int accent = AcidTheme.accent(mIsDarkMode);
            sw.setThumbTintList(ColorStateList.valueOf(meta.enabled ? accent : AcidTheme.muted(mIsDarkMode)));
        }
        sw.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean isChecked = sw.isChecked();
                if (mManager != null) {
                    mManager.setScriptEnabled(meta.fileName, isChecked);
                    meta.enabled = isChecked;
                    Toast.makeText(getContext(),
                        (isChecked ? "已启用「" : "已停用「") + meta.name + "」",
                        Toast.LENGTH_SHORT).show();
                    renderScripts();
                    if (mActionListener != null) {
                        mActionListener.onScriptToggle(meta, isChecked);
                    }
                }
            }
        });
        row.addView(sw);

        if (!meta.enabled) {
            row.setAlpha(0.65f);
        }

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showScriptDetail(meta);
            }
        });

        return row;
    }

    /** Detail view: metadata, body preview, run-once, toggle, delete. */
    private void showScriptDetail(final UserscriptMeta meta) {
        Context ctx = getContext();
        AlertDialog.Builder b = AcidTheme.dialogBuilder(ctx, mIsDarkMode);
        b.setTitle(meta.name);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = AcidTheme.dp(ctx, 20);
        int padV = AcidTheme.dp(ctx, 14);
        box.setPadding(padH, padV, padH, padV);

        if (!TextUtils.isEmpty(meta.description)) {
            TextView desc = new TextView(ctx);
            desc.setText(meta.description);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            desc.setTextColor(AcidTheme.text(mIsDarkMode));
            box.addView(desc);
        }

        TextView info = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(meta.enabled ? "已启用 (自动随网页加载运行)" : "已停用 (暂停自动运行)").append("\n");
        sb.append("生效范围: ").append(meta.describeScope()).append("\n");
        sb.append("执行时机: ").append(meta.runAt).append("\n");
        if (!TextUtils.isEmpty(meta.version)) sb.append("版本: ").append(meta.version).append("\n");
        sb.append("文件: ").append(meta.fileName);
        info.setText(sb.toString());
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        info.setTextColor(AcidTheme.muted(mIsDarkMode));
        info.setPadding(0, AcidTheme.dp(ctx, 8), 0, 0);
        box.addView(info);

        // Body preview, truncated so the dialog stays readable.
        TextView code = new TextView(ctx);
        String body = meta.body != null ? meta.body : "";
        if (body.length() > 400) body = body.substring(0, 400) + "\n…";
        code.setText(body);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        code.setTextColor(AcidTheme.DARK_CODE_TEXT);
        code.setTypeface(Typeface.MONOSPACE);
        code.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 8),
                        AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 8));
        code.setBackground(AcidTheme.pillBg(ctx, AcidTheme.codeBlockBg(mIsDarkMode), 0, 6));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = AcidTheme.dp(ctx, 10);
        box.addView(code, cLp);

        b.setView(box);
        b.setPositiveButton("在当前页运行", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (mActionListener != null) mActionListener.onRunScript(meta);
            }
        });
        b.setNeutralButton(meta.enabled ? "停用脚本" : "启用脚本", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                boolean nextState = !meta.enabled;
                if (mManager != null) {
                    mManager.setScriptEnabled(meta.fileName, nextState);
                    meta.enabled = nextState;
                    Toast.makeText(getContext(),
                        (nextState ? "已启用「" : "已停用「") + meta.name + "」",
                        Toast.LENGTH_SHORT).show();
                    renderScripts();
                    if (mActionListener != null) {
                        mActionListener.onScriptToggle(meta, nextState);
                    }
                }
            }
        });
        b.setNegativeButton("删除", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                confirmDelete(meta);
            }
        });
        b.show();
    }

    private void confirmDelete(final UserscriptMeta meta) {
        AlertDialog.Builder b = AcidTheme.dialogBuilder(getContext(), mIsDarkMode);
        b.setTitle("删除脚本");
        b.setMessage("确定删除「" + meta.name + "」？\n\n" + meta.fileName);
        b.setPositiveButton("删除", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                boolean ok = mManager != null && mManager.deleteUserscript(meta.fileName);
                Toast.makeText(getContext(),
                    ok ? "已删除「" + meta.name + "」" : "删除失败",
                    Toast.LENGTH_SHORT).show();
                renderScripts();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /**
     * Resting + pressed backgrounds, matching the bookmark/download rows so the whole
     * app reads as one system.
     */
    private void applyRowBackground(View row) {
        Context ctx = row.getContext();
        GradientDrawable normal = AcidTheme.pillBg(
            ctx, AcidTheme.menuItemBg(mIsDarkMode), 0, 8);
        GradientDrawable pressed = AcidTheme.pillBg(
            ctx, AcidTheme.rowPressed(mIsDarkMode), 0, 8);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        row.setBackground(sld);
    }
}
