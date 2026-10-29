package com.acid.browser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Bottom Sheet for AdBlock configuration, statistics, online subscriptions, and custom rules.
 */
public class AdBlockSheet extends AcidBottomSheetView {

    private final AdBlockManager mManager;
    private boolean mIsDarkMode = false;

    public AdBlockSheet(Context context, AdBlockManager manager) {
        super(context);
        mManager = manager;
    }

    @Override
    public void applyTheme(boolean isDark) {
        super.applyTheme(isDark);
        mIsDarkMode = isDark;
    }

    public void showAdBlock() {
        setTitle("广告拦截");
        renderContent();
        show();
    }

    public void renderContent() {
        if (mManager == null) return;
        LinearLayout container = getContentContainer();
        container.removeAllViews();
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        int textColor = AcidTheme.text(mIsDarkMode);
        int mutedColor = AcidTheme.muted(mIsDarkMode);
        int accentColor = AcidTheme.accent(mIsDarkMode);
        int cardBg = AcidTheme.cardSurface(mIsDarkMode);
        int borderColor = AcidTheme.subtleBorder(mIsDarkMode);
        final boolean enabled = mManager.isEnabled();

        // 1. Top Status & Intercept Count Card
        LinearLayout statusCard = new LinearLayout(ctx);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, enabled ? accentColor : borderColor, 1.2f, 12));
        int padH = AcidTheme.dp(ctx, 14);
        int padV = AcidTheme.dp(ctx, 12);
        statusCard.setPadding(padH, padV, padH, padV);

        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        AcidIcons.SvgIconView iconView = new AcidIcons.SvgIconView(
            ctx, AcidIcons.ICON_ADBLOCK, enabled ? accentColor : mutedColor, 26
        );
        topRow.addView(iconView);

        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 10);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(enabled ? "广告与隐私追踪防护已开启" : "广告拦截防护已暂停");
        tvTitle.setTextColor(textColor);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText("自动拦截网页广告脚本、弹窗贴片与开屏唤醒");
        tvSub.setTextColor(mutedColor);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        titleCol.addView(tvSub);

        topRow.addView(titleCol, tLp);

        final Switch sw = new Switch(ctx);
        sw.setChecked(enabled);
        if (Build.VERSION.SDK_INT >= 23) {
            sw.setThumbTintList(ColorStateList.valueOf(enabled ? accentColor : mutedColor));
        }
        sw.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean isChecked = sw.isChecked();
                mManager.setEnabled(isChecked);
                Toast.makeText(getContext(), isChecked ? "广告拦截已开启" : "广告拦截已暂停", Toast.LENGTH_SHORT).show();
                renderContent();
            }
        });
        topRow.addView(sw);
        statusCard.addView(topRow);

        // Counter Row
        LinearLayout counterBox = new LinearLayout(ctx);
        counterBox.setOrientation(LinearLayout.HORIZONTAL);
        counterBox.setGravity(Gravity.CENTER_VERTICAL);
        counterBox.setPadding(0, AcidTheme.dp(ctx, 10), 0, 0);

        TextView tvCountLabel = new TextView(ctx);
        tvCountLabel.setText("已累计成功拦截：");
        tvCountLabel.setTextColor(mutedColor);
        tvCountLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        counterBox.addView(tvCountLabel);

        TextView tvCountNum = new TextView(ctx);
        tvCountNum.setText(mManager.getBlockedCount() + " 次");
        tvCountNum.setTextColor(accentColor);
        tvCountNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvCountNum.setTypeface(Typeface.DEFAULT_BOLD);
        counterBox.addView(tvCountNum);

        statusCard.addView(counterBox);

        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scLp.bottomMargin = AcidTheme.dp(ctx, 12);
        container.addView(statusCard, scLp);

        // 2. Rules Stats Pills Card
        LinearLayout pillRow = new LinearLayout(ctx);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.CENTER_VERTICAL);

        int pillBg = AcidTheme.subtle(mIsDarkMode);
        addStatPill(pillRow, "内置移动规则", mManager.getBuiltinRulesCount() + " 条", pillBg, mutedColor, textColor, d);
        addStatPill(pillRow, "在线订阅规则", mManager.getSubscriptionRulesCount() + " 条", pillBg, mutedColor, textColor, d);
        addStatPill(pillRow, "自定义规则", mManager.getCustomRulesCount() + " 条", pillBg, mutedColor, textColor, d);

        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        prLp.bottomMargin = AcidTheme.dp(ctx, 14);
        container.addView(pillRow, prLp);

        // 3. Online Subscription Card
        LinearLayout subCard = new LinearLayout(ctx);
        subCard.setOrientation(LinearLayout.VERTICAL);
        subCard.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, borderColor, 1, 10));
        subCard.setPadding(padH, padV, padH, padV);

        TextView subTitle = new TextView(ctx);
        subTitle.setText("规则在线订阅 (GitHub / ABP)");
        subTitle.setTextColor(textColor);
        subTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        subTitle.setTypeface(Typeface.DEFAULT_BOLD);
        subCard.addView(subTitle);

        TextView subDesc = new TextView(ctx);
        subDesc.setText("支持填入 anti-AD、EasyList 等开源规则的 Raw 链接更新本地规则库：");
        subDesc.setTextColor(mutedColor);
        subDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sdLp.topMargin = AcidTheme.dp(ctx, 2);
        sdLp.bottomMargin = AcidTheme.dp(ctx, 8);
        subCard.addView(subDesc, sdLp);

        final EditText etSubUrl = new EditText(ctx);
        etSubUrl.setText(mManager.getSubscriptionUrl());
        etSubUrl.setTextColor(textColor);
        etSubUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        etSubUrl.setHint("https://raw.githubusercontent.com/.../easylist.txt");
        etSubUrl.setHintTextColor(mutedColor);
        etSubUrl.setBackground(AcidTheme.pillBg(ctx, AcidTheme.subtle(mIsDarkMode), borderColor, 6));
        etSubUrl.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 8));
        subCard.addView(etSubUrl);

        LinearLayout subBtnRow = new LinearLayout(ctx);
        subBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        subBtnRow.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams sbrLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbrLp.topMargin = AcidTheme.dp(ctx, 8);

        final AcidIcons.BadgeButton btnDownload = new AcidIcons.BadgeButton(
            ctx, AcidIcons.ICON_DOWNLOAD, "下载并更新规则", accentColor, null
        );
        btnDownload.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                final String url = etSubUrl.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(getContext(), "请输入有效的订阅规则 URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                btnDownload.setEnabled(false);
                Toast.makeText(getContext(), "正在连接并拉取规则...", Toast.LENGTH_SHORT).show();
                mManager.downloadSubscription(url, new AdBlockManager.AdBlockCallback() {
                    @Override
                    public void onSuccess(int totalRules) {
                        btnDownload.setEnabled(true);
                        Toast.makeText(getContext(), "规则更新成功！当前活跃规则共 " + totalRules + " 条", Toast.LENGTH_LONG).show();
                        renderContent();
                    }

                    @Override
                    public void onError(String message) {
                        btnDownload.setEnabled(true);
                        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        subBtnRow.addView(btnDownload);
        subCard.addView(subBtnRow, sbrLp);

        LinearLayout.LayoutParams sc2Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sc2Lp.bottomMargin = AcidTheme.dp(ctx, 12);
        container.addView(subCard, sc2Lp);

        // 4. Custom Rules Card
        LinearLayout customCard = new LinearLayout(ctx);
        customCard.setOrientation(LinearLayout.VERTICAL);
        customCard.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, borderColor, 1, 10));
        customCard.setPadding(padH, padV, padH, padV);

        TextView customTitle = new TextView(ctx);
        customTitle.setText("自定义拦截规则 (用户与 Agent 统一)");
        customTitle.setTextColor(textColor);
        customTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        customTitle.setTypeface(Typeface.DEFAULT_BOLD);
        customCard.addView(customTitle);

        TextView customDesc = new TextView(ctx);
        customDesc.setText("每行一条标准 ABP 规则（||domain^ 拦截请求、##selector 元素隐藏）。Agent 发现漏网广告亦会自动保存到此：");
        customDesc.setTextColor(mutedColor);
        customDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cdLp.topMargin = AcidTheme.dp(ctx, 2);
        cdLp.bottomMargin = AcidTheme.dp(ctx, 8);
        customCard.addView(customDesc, cdLp);

        final EditText etCustom = new EditText(ctx);
        etCustom.setText(mManager.getCustomRulesText());
        etCustom.setTextColor(textColor);
        etCustom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f);
        etCustom.setTypeface(Typeface.MONOSPACE);
        etCustom.setBackground(AcidTheme.pillBg(ctx, AcidTheme.subtle(mIsDarkMode), borderColor, 6));
        etCustom.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 8));
        etCustom.setMinLines(4);
        etCustom.setMaxLines(8);
        etCustom.setGravity(Gravity.TOP);
        customCard.addView(etCustom);

        LinearLayout customBtnRow = new LinearLayout(ctx);
        customBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        customBtnRow.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams cbrLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbrLp.topMargin = AcidTheme.dp(ctx, 8);

        AcidIcons.BadgeButton btnSaveCustom = new AcidIcons.BadgeButton(
            ctx, AcidIcons.ICON_CHECK, "保存自定义规则", accentColor, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    String text = etCustom.getText().toString();
                    mManager.saveCustomRulesText(text);
                    Toast.makeText(getContext(), "自定义规则已保存生效", Toast.LENGTH_SHORT).show();
                    renderContent();
                }
            }
        );
        customBtnRow.addView(btnSaveCustom);
        customCard.addView(customBtnRow, cbrLp);

        container.addView(customCard);
    }

    private void addStatPill(LinearLayout parent, String label, String value, int bg, int mutedColor, int textColor, float d) {
        Context ctx = parent.getContext();
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(AcidTheme.pillBg(ctx, bg, 0, 8));
        int padH = Math.round(6 * d);
        int padV = Math.round(6 * d);
        pill.setPadding(padH, padV, padH, padV);

        TextView valTv = new TextView(ctx);
        valTv.setText(value);
        valTv.setTextColor(textColor);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        valTv.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(valTv);

        TextView labTv = new TextView(ctx);
        labTv.setText(label);
        labTv.setTextColor(mutedColor);
        labTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        pill.addView(labTv);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Math.round(2 * d);
        lp.rightMargin = Math.round(2 * d);
        parent.addView(pill, lp);
    }
}
