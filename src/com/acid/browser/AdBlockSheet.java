package com.acid.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * AdBlock Configuration, Statistics, Online Subscriptions & Custom Rules Sheet
 * built on {@link AcidBottomSheetView}'s {@link SheetPage} template and {@link AcidWidgets}.
 *
 * <p>Preserves unsaved custom rules and subscription URL drafts across toggle switches,
 * subscription downloads, and Day/Night theme transitions.
 */
public class AdBlockSheet extends AcidBottomSheetView {

    public interface OnAdBlockStateChangeListener {
        void onAdBlockStateChanged(boolean enabled);
    }

    private final AdBlockManager mManager;
    private final OnAdBlockStateChangeListener mStateListener;

    private String mDraftSubUrl = null;
    private String mDraftCustomRules = null;

    public AdBlockSheet(Context context, AdBlockManager manager) {
        this(context, manager, null);
    }

    public AdBlockSheet(Context context, AdBlockManager manager, OnAdBlockStateChangeListener listener) {
        super(context);
        mManager = manager;
        mStateListener = listener;
        setRootPage(buildAdBlockPage());
    }

    @Override
    protected void onResetToRootPage() {
        mDraftSubUrl = null;
        mDraftCustomRules = null;
        setRootPage(buildAdBlockPage());
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildAdBlockPage());
        } else {
            refreshActivePage();
        }
        super.show();
    }

    public void renderContent() {
        mDraftSubUrl = null;
        mDraftCustomRules = null;
        setRootPage(buildAdBlockPage());
    }

    private SheetPage buildAdBlockPage() {
        return new SheetPage("广告拦截", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                if (mManager == null) return;
                final Context ctx = getContext();
                int textColor = AcidTheme.text(mIsDarkMode);
                int mutedColor = AcidTheme.muted(mIsDarkMode);
                int accentColor = AcidTheme.accent(mIsDarkMode);
                final boolean enabled = mManager.isEnabled();

                // 1. Hero Status Card (SP_HERO = 15sp Bold)
                LinearLayout statusCard = AcidWidgets.heroCard(
                    ctx, mIsDarkMode,
                    enabled ? accentColor : AcidTheme.border(mIsDarkMode)
                );

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
                tvTitle.setText(AcidLocale.tr(enabled ? "防护已开启" : "防护已暂停"));
                tvTitle.setTextColor(textColor);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
                tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
                titleCol.addView(tvTitle);

                topRow.addView(titleCol, tLp);

                final Switch sw = new Switch(ctx);
                sw.setChecked(enabled);
                AcidWidgets.styleSwitch(sw, mIsDarkMode, enabled);
                sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (mManager.isEnabled() == isChecked) return;
                        mManager.setEnabled(isChecked);
                        AcidFeedback.info(ctx, isChecked ? "广告拦截已开启" : "广告拦截已暂停");
                        if (mStateListener != null) {
                            mStateListener.onAdBlockStateChanged(isChecked);
                        }
                        refreshActivePage();
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
                tvCountLabel.setText(AcidLocale.tr("累计已拦截请求: "));
                tvCountLabel.setTextColor(mutedColor);
                tvCountLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
                counterRow.addView(tvCountLabel);

                TextView tvCountNum = new TextView(ctx);
                tvCountNum.setText(AcidLocale.tr(mManager.getBlockedCount() + " 次"));
                tvCountNum.setTextColor(accentColor);
                tvCountNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
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

                // 1b. 3-Level External App Launch Interception Section (阻止调用第三方 APP)
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "阻止调用第三方 APP"));
                LinearLayout appLaunchCard = AcidWidgets.contentCard(ctx, mIsDarkMode);

                final int currentPolicy = mManager.getAppLaunchPolicy();
                String[] policyLabels = AcidSettings.APP_LAUNCH_POLICY.displayLabels;
                appLaunchCard.addView(AcidWidgets.segmentedTabs(
                    ctx,
                    mIsDarkMode,
                    policyLabels,
                    currentPolicy,
                    accentColor,
                    new AcidWidgets.OnTabSelectedListener() {
                        @Override
                        public void onTabSelected(int index) {
                            if (mManager.getAppLaunchPolicy() == index) return;
                            mManager.setAppLaunchPolicy(index);
                            String toastText;
                            if (index == AcidSettings.APP_LAUNCH_BLOCK) {
                                toastText = "已设为完全拦截";
                            } else if (index == AcidSettings.APP_LAUNCH_ASK) {
                                toastText = "已设为弹窗询问";
                            } else {
                                toastText = "已设为允许唤起";
                            }
                            AcidFeedback.info(ctx, toastText);
                            refreshActivePage();
                        }
                    }
                ));

                TextView tvPolicyDesc = AcidWidgets.descriptionText(ctx, mIsDarkMode, "Agent 运行期间自动锁定为完全拦截");
                tvPolicyDesc.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
                appLaunchCard.addView(tvPolicyDesc);
                container.addView(appLaunchCard);

                // 2. Online Subscription Section
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "在线规则订阅"));
                LinearLayout subCard = AcidWidgets.contentCard(ctx, mIsDarkMode);

                subCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "订阅 URL"));
                String initialSubUrl = mDraftSubUrl != null ? mDraftSubUrl : mManager.getSubscriptionUrl();
                final EditText etSubUrl = AcidWidgets.inputField(
                    ctx, mIsDarkMode,
                    initialSubUrl,
                    "https://...",
                    true
                );
                etSubUrl.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override public void afterTextChanged(Editable s) {
                        mDraftSubUrl = s != null ? s.toString() : "";
                    }
                });
                subCard.addView(etSubUrl);

                AcidWidgets.LoadingButton btnDownload = AcidWidgets.LoadingButton.primary(
                    ctx, mIsDarkMode,
                    "更新订阅", "更新中...",
                    accentColor,
                    new AcidWidgets.LoadingButton.OnAsyncActionListener() {
                        @Override
                        public void onTrigger(final AcidWidgets.LoadingButton button) {
                            final String url = etSubUrl.getText().toString().trim();
                            if (url.isEmpty()) {
                                AcidFeedback.warn(ctx, "请输入有效的订阅规则 URL");
                                return;
                            }
                            button.setLoading();
                            mManager.downloadSubscription(url, new AdBlockManager.AdBlockCallback() {
                                @Override
                                public void onSuccess(int totalRules) {
                                    button.reset();
                                    AcidFeedback.ok(ctx, "规则更新成功！当前活跃规则共 " + totalRules + " 条");
                                    refreshActivePage();
                                }

                                @Override
                                public void onError(String message) {
                                    button.reset();
                                    AcidFeedback.error(ctx, message);
                                }
                            });
                        }
                    }
                );
                subCard.addView(btnDownload.view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                container.addView(subCard);

                // 3. Custom Rules Section (preserves draft across toggles & theme switches!)
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "自定义拦截规则"));
                LinearLayout customCard = AcidWidgets.contentCard(ctx, mIsDarkMode);
                customCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "规则列表（支持 ||domain^ 与 ##selector）"));

                String initialCustom = mDraftCustomRules != null ? mDraftCustomRules : mManager.getCustomRulesText();
                final EditText etCustom = AcidWidgets.inputField(
                    ctx, mIsDarkMode,
                    initialCustom,
                    "||ads.example.com^\nexample.com##.banner",
                    false
                );
                etCustom.setTypeface(Typeface.MONOSPACE);
                etCustom.setMinLines(4);
                etCustom.setMaxLines(8);
                etCustom.setGravity(Gravity.TOP | Gravity.START);
                etCustom.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override public void afterTextChanged(Editable s) {
                        mDraftCustomRules = s != null ? s.toString() : "";
                    }
                });
                customCard.addView(etCustom);

                TextView btnSaveCustom = AcidWidgets.primaryButton(ctx, mIsDarkMode, "保存规则", accentColor, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AcidTheme.hideKeyboard(AdBlockSheet.this);
                        String text = etCustom.getText() != null ? etCustom.getText().toString() : "";
                        mDraftCustomRules = text;
                        mManager.saveCustomRulesText(text);
                        AcidFeedback.ok(ctx, "自定义拦截规则已保存生效");
                        refreshActivePage();
                    }
                });
                customCard.addView(btnSaveCustom, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                container.addView(customCard);
            }
        });
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
        valTv.setText(AcidLocale.tr(value));
        valTv.setTextColor(AcidTheme.text(mIsDarkMode));
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        valTv.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(valTv);

        TextView labTv = new TextView(ctx);
        labTv.setText(AcidLocale.tr(label));
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
