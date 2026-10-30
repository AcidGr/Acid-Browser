package com.acid.browser;

import android.content.Context;
import android.graphics.Typeface;
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
 * AdBlock Configuration, Statistics, Online Subscriptions & Custom Rules Sheet
 * built on {@link AcidBottomSheetView} and {@link AcidWidgets}.
 */
public class AdBlockSheet extends AcidBottomSheetView {

    public interface OnAdBlockStateChangeListener {
        void onAdBlockStateChanged(boolean enabled);
    }

    private final AdBlockManager mManager;
    private final OnAdBlockStateChangeListener mStateListener;

    public AdBlockSheet(Context context, AdBlockManager manager) {
        this(context, manager, null);
    }

    public AdBlockSheet(Context context, AdBlockManager manager, OnAdBlockStateChangeListener listener) {
        super(context);
        mManager = manager;
        mStateListener = listener;
    }

    public void showAdBlock() {
        renderContent();
        show();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            renderContent();
        }
    }

    public void renderContent() {
        if (mManager == null) return;
        setTitle("广告拦截");
        hideAction();
        clearPinnedHeader();

        LinearLayout container = getContentContainer();
        container.removeAllViews();
        final Context ctx = getContext();

        int textColor = AcidTheme.text(mIsDarkMode);
        int mutedColor = AcidTheme.muted(mIsDarkMode);
        int accentColor = AcidTheme.accent(mIsDarkMode);
        final boolean enabled = mManager.isEnabled();

        // 1. Hero Status Card (H3 Lead = 14.5sp Bold)
        LinearLayout statusCard = AcidWidgets.cardGroup(
            ctx, mIsDarkMode,
            enabled ? accentColor : AcidTheme.border(mIsDarkMode),
            1f
        );
        int pad = AcidTheme.dp(ctx, 14);
        statusCard.setPadding(pad, AcidTheme.dp(ctx, 12), pad, AcidTheme.dp(ctx, 12));

        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        AcidIcons.SvgIconView iconView = new AcidIcons.SvgIconView(
            ctx, AcidIcons.ICON_ADBLOCK, enabled ? accentColor : mutedColor, 26
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
        tvTitle.setText(enabled ? "广告与追踪防护已开启" : "广告拦截防护已暂停");
        tvTitle.setTextColor(textColor);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H3_CARD);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText("自动拦截网页广告请求、弹窗贴片与恶意唤端");
        tvSub.setTextColor(mutedColor);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        tvSub.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
        titleCol.addView(tvSub);

        topRow.addView(titleCol, tLp);

        final Switch sw = new Switch(ctx);
        sw.setChecked(enabled);
        AcidWidgets.styleSwitch(sw, mIsDarkMode, enabled);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mManager.isEnabled() == isChecked) return;
                mManager.setEnabled(isChecked);
                Toast.makeText(ctx, isChecked ? "广告拦截已开启" : "广告拦截已暂停", Toast.LENGTH_SHORT).show();
                if (mStateListener != null) {
                    mStateListener.onAdBlockStateChanged(isChecked);
                }
                renderContent();
            }
        });
        topRow.addView(sw);
        statusCard.addView(topRow);

        // Counter + 3-Column Stats Pills inside Status Card
        LinearLayout counterRow = new LinearLayout(ctx);
        counterRow.setOrientation(LinearLayout.HORIZONTAL);
        counterRow.setGravity(Gravity.CENTER_VERTICAL);
        counterRow.setPadding(0, AcidTheme.dp(ctx, 10), 0, AcidTheme.dp(ctx, 8));

        TextView tvCountLabel = new TextView(ctx);
        tvCountLabel.setText("累计已拦截请求: ");
        tvCountLabel.setTextColor(mutedColor);
        tvCountLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        counterRow.addView(tvCountLabel);

        TextView tvCountNum = new TextView(ctx);
        tvCountNum.setText(mManager.getBlockedCount() + " 次");
        tvCountNum.setTextColor(accentColor);
        tvCountNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H3_CARD);
        tvCountNum.setTypeface(Typeface.DEFAULT_BOLD);
        counterRow.addView(tvCountNum);
        statusCard.addView(counterRow);

        LinearLayout pillRow = new LinearLayout(ctx);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        addStatPill(pillRow, "内置规则", mManager.getBuiltinRulesCount() + " 条");
        addStatPill(pillRow, "订阅规则", mManager.getSubscriptionRulesCount() + " 条");
        addStatPill(pillRow, "自定义规则", mManager.getCustomRulesCount() + " 条");
        statusCard.addView(pillRow);

        container.addView(statusCard);

        // 2. Online Subscription Section (H2 Section Header + Card)
        container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "规则库在线订阅 (ABP / EasyList)"));
        LinearLayout subCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);
        subCard.setPadding(pad, AcidTheme.dp(ctx, 12), pad, AcidTheme.dp(ctx, 12));

        subCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "订阅规则 Raw URL"));
        final EditText etSubUrl = AcidWidgets.inputField(
            ctx, mIsDarkMode,
            mManager.getSubscriptionUrl(),
            "https://raw.githubusercontent.com/.../easylist.txt",
            true
        );
        subCard.addView(etSubUrl);

        final TextView[] dlBtnRef = new TextView[1];
        TextView btnDownload = AcidWidgets.primaryButton(ctx, mIsDarkMode, "拉取并更新订阅规则", accentColor, new OnClickListener() {
            @Override
            public void onClick(View v) {
                final String url = etSubUrl.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(ctx, "请输入有效的订阅规则 URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                final TextView btn = dlBtnRef[0];
                if (btn != null) {
                    btn.setText("正在拉取规则...");
                    btn.setEnabled(false);
                }
                mManager.downloadSubscription(url, new AdBlockManager.AdBlockCallback() {
                    @Override
                    public void onSuccess(int totalRules) {
                        if (btn != null) {
                            btn.setText("拉取并更新订阅规则");
                            btn.setEnabled(true);
                        }
                        Toast.makeText(ctx, "规则更新成功！当前活跃规则共 " + totalRules + " 条", Toast.LENGTH_LONG).show();
                        renderContent();
                    }

                    @Override
                    public void onError(String message) {
                        if (btn != null) {
                            btn.setText("拉取并更新订阅规则");
                            btn.setEnabled(true);
                        }
                        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        dlBtnRef[0] = btnDownload;
        subCard.addView(btnDownload, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        container.addView(subCard);

        // 3. Custom Rules Section (H2 Section Header + Card)
        container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "自定义拦截规则 (||domain^ 或 ##selector)"));
        LinearLayout customCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);
        customCard.setPadding(pad, AcidTheme.dp(ctx, 12), pad, AcidTheme.dp(ctx, 12));

        final EditText etCustom = AcidWidgets.inputField(
            ctx, mIsDarkMode,
            mManager.getCustomRulesText(),
            "例如:\n||ads.example.com^\nexample.com##.floating-banner",
            false
        );
        etCustom.setTypeface(Typeface.MONOSPACE);
        etCustom.setMinLines(4);
        etCustom.setMaxLines(8);
        etCustom.setGravity(Gravity.TOP | Gravity.START);
        customCard.addView(etCustom);

        TextView btnSaveCustom = AcidWidgets.primaryButton(ctx, mIsDarkMode, "保存自定义规则", AcidTheme.COLOR_BLUE, new OnClickListener() {
            @Override
            public void onClick(View v) {
                AcidTheme.hideKeyboard(AdBlockSheet.this);
                mManager.saveCustomRulesText(etCustom.getText().toString());
                Toast.makeText(ctx, "自定义拦截规则已保存生效", Toast.LENGTH_SHORT).show();
                renderContent();
            }
        });
        customCard.addView(btnSaveCustom, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        container.addView(customCard);
    }

    private void addStatPill(LinearLayout parent, String label, String value) {
        Context ctx = parent.getContext();
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(AcidTheme.pillBg(
            ctx, AcidTheme.subtle(mIsDarkMode), AcidTheme.border(mIsDarkMode), AcidTheme.RADIUS_CONTROL
        ));
        int pad = AcidTheme.dp(ctx, 7);
        pill.setPadding(pad, pad, pad, pad);

        TextView valTv = new TextView(ctx);
        valTv.setText(value);
        valTv.setTextColor(AcidTheme.text(mIsDarkMode));
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        valTv.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(valTv);

        TextView labTv = new TextView(ctx);
        labTv.setText(label);
        labTv.setTextColor(AcidTheme.muted(mIsDarkMode));
        labTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
        pill.addView(labTv);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        if (parent.getChildCount() > 0) {
            lp.leftMargin = AcidTheme.dp(ctx, 6);
        }
        parent.addView(pill, lp);
    }
}
