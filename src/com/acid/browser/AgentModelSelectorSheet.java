package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Model & Reasoning Effort Selector sheet for Agent conversations.
 * Supports active model indication, 6-level reasoning effort toggle, live search, and provider grouping.
 */
public class AgentModelSelectorSheet extends AcidBottomSheetView {

    public interface ModelSelectorListener {
        void onOpenModelProviderConfig();
        void onModelConfigChanged();
    }

    private final AcidModelStore mModelStore;
    private final ModelSelectorListener mListener;
    private String mFilterQuery = "";
    private boolean mIsDarkMode = false;

    public AgentModelSelectorSheet(Context context, AcidModelStore modelStore, ModelSelectorListener listener) {
        super(context);
        mModelStore = modelStore;
        mListener = listener;
    }

    @Override
    public void applyTheme(boolean isDark) {
        super.applyTheme(isDark);
        mIsDarkMode = isDark;
    }

    public void showSelector() {
        if (mModelStore == null) return;
        mFilterQuery = "";
        setTitle("选择模型与思考深度");

        int accent = AcidTheme.accent(mIsDarkMode);
        setAction(AcidIcons.ICON_SETTINGS, "模型配置", accent, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mListener != null) {
                    mListener.onOpenModelProviderConfig();
                }
            }
        });

        renderContent();
        show();
    }

    private void renderContent() {
        LinearLayout container = getContentContainer();
        container.removeAllViews();
        if (mModelStore == null) return;

        final Context ctx = getContext();
        final List<AcidModelStore.ConfiguredProvider> providers = mModelStore.getProviders();
        final AcidModelStore.ConfiguredProvider activeProvider = mModelStore.getActiveProvider();
        final AcidModelStore.ModelEntry activeModel = mModelStore.getActiveModel();
        final String activeEffort = mModelStore.getSelectedReasoningEffort();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // --- Section A: Active Selection & Reasoning Effort Card ---
        LinearLayout topCard = new LinearLayout(ctx);
        topCard.setOrientation(LinearLayout.VERTICAL);
        topCard.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, blue, 1, 12));
        int padH = AcidTheme.dp(ctx, 12);
        int padV = AcidTheme.dp(ctx, 10);
        topCard.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topLp.bottomMargin = AcidTheme.dp(ctx, 10);
        topCard.setLayoutParams(topLp);

        TextView lblCur = new TextView(ctx);
        lblCur.setText("当前使用模型 (供应商 + 模型名称)");
        lblCur.setTextColor(mutedCol);
        lblCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        topCard.addView(lblCur);

        TextView tvCurVal = new TextView(ctx);
        if (activeProvider != null && activeModel != null) {
            tvCurVal.setText(activeProvider.displayName + "  /  " + activeModel.name + " (" + activeModel.id + ")");
            tvCurVal.setTextColor(textCol);
        } else {
            tvCurVal.setText("暂无已装载模型，请点击右上角 [模型配置] 添加");
            tvCurVal.setTextColor(AcidTheme.COLOR_AMBER);
        }
        tvCurVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tvCurVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvCurVal.setPadding(0, AcidTheme.dp(ctx, 3), 0, AcidTheme.dp(ctx, 8));
        topCard.addView(tvCurVal);

        // Reasoning Effort Selector Row
        if (activeModel != null) {
            LinearLayout effortHead = new LinearLayout(ctx);
            effortHead.setOrientation(LinearLayout.HORIZONTAL);
            effortHead.setGravity(Gravity.CENTER_VERTICAL);

            TextView lblEffort = new TextView(ctx);
            lblEffort.setText("思考深度 (Reasoning Effort):");
            lblEffort.setTextColor(textCol);
            lblEffort.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            lblEffort.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams leLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            effortHead.addView(lblEffort, leLp);

            if (activeModel.supportsReasoning) {
                TextView curEffortBadge = new TextView(ctx);
                curEffortBadge.setText("当前: " + AcidModelStore.getEffortDisplayName(activeEffort));
                curEffortBadge.setTextColor(blue);
                curEffortBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                curEffortBadge.setTypeface(Typeface.DEFAULT_BOLD);
                effortHead.addView(curEffortBadge);
                topCard.addView(effortHead);

                // 2 rows of 3 effort pills
                String[] effortIds = AcidModelStore.EFFORT_IDS;
                for (int r = 0; r < 2; r++) {
                    LinearLayout eRow = new LinearLayout(ctx);
                    eRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams erLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    erLp.topMargin = AcidTheme.dp(ctx, 6);
                    eRow.setLayoutParams(erLp);

                    for (int c = 0; c < 3; c++) {
                        int idx = r * 3 + c;
                        if (idx >= effortIds.length) break;
                        final String efId = effortIds[idx];
                        boolean isSelectedEffort = efId.equals(activeEffort);

                        TextView pill = new TextView(ctx);
                        pill.setText(AcidModelStore.getEffortDisplayName(efId));
                        pill.setGravity(Gravity.CENTER);
                        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                        pill.setTypeface(isSelectedEffort ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                        int pPad = AcidTheme.dp(ctx, 6);
                        pill.setPadding(pPad, pPad, pPad, pPad);
                        if (isSelectedEffort) {
                            pill.setTextColor(Color.WHITE);
                            pill.setBackground(AcidTheme.pillBg(ctx, blue, blue, 8));
                        } else {
                            pill.setTextColor(textCol);
                            pill.setBackground(AcidTheme.pillBg(ctx, mIsDarkMode ? AcidTheme.DARK_BG : Color.WHITE, borderCol, 8));
                        }
                        pill.setOnClickListener(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                mModelStore.setReasoningEffort(efId);
                                if (mListener != null) {
                                    mListener.onModelConfigChanged();
                                }
                                renderContent();
                            }
                        });

                        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        if (c > 0) pLp.leftMargin = AcidTheme.dp(ctx, 6);
                        eRow.addView(pill, pLp);
                    }
                    topCard.addView(eRow);
                }
            } else {
                TextView btnEnableThink = new TextView(ctx);
                btnEnableThink.setText("开启思考深度调节");
                btnEnableThink.setTextColor(blue);
                btnEnableThink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                int padTb = AcidTheme.dp(ctx, 3);
                int padLr = AcidTheme.dp(ctx, 8);
                btnEnableThink.setPadding(padLr, padTb, padLr, padTb);
                btnEnableThink.setBackground(AcidTheme.pillBg(ctx, Color.argb(24, 2, 132, 199), blue, 6));
                btnEnableThink.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activeModel.supportsReasoning = true;
                        mModelStore.saveToPrefs();
                        if (mListener != null) {
                            mListener.onModelConfigChanged();
                        }
                        renderContent();
                    }
                });
                effortHead.addView(btnEnableThink);
                topCard.addView(effortHead);

                TextView tvNoReason = new TextView(ctx);
                tvNoReason.setText("当前模型未开启思考深度选项（标准对话模式）。如该模型支持推理思考，可点击右侧按钮开启。");
                tvNoReason.setTextColor(mutedCol);
                tvNoReason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                tvNoReason.setPadding(0, AcidTheme.dp(ctx, 4), 0, 0);
                topCard.addView(tvNoReason);
            }
        }
        container.addView(topCard);

        // --- Section B: Search Box (Shown when > 4 models loaded) ---
        int totalModels = mModelStore.getTotalLoadedModelsCount();
        final LinearLayout groupsContainer = new LinearLayout(ctx);
        groupsContainer.setOrientation(LinearLayout.VERTICAL);

        if (totalModels > 4) {
            final EditText etSearch = new EditText(ctx);
            etSearch.setSingleLine(true);
            etSearch.setHint("搜索已装载的供应商或模型名称...");
            etSearch.setHintTextColor(mutedCol);
            etSearch.setTextColor(textCol);
            etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            etSearch.setPadding(AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 7), AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 7));
            etSearch.setBackground(AcidTheme.pillBg(ctx, cardBg, borderCol, 10));
            etSearch.setText(mFilterQuery);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            sLp.bottomMargin = AcidTheme.dp(ctx, 8);
            container.addView(etSearch, sLp);

            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    mFilterQuery = s != null ? s.toString() : "";
                    populateGroups(groupsContainer, providers, activeProvider, activeModel);
                }
                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        container.addView(groupsContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        populateGroups(groupsContainer, providers, activeProvider, activeModel);
    }

    private void populateGroups(
        LinearLayout container,
        List<AcidModelStore.ConfiguredProvider> providers,
        AcidModelStore.ConfiguredProvider activeProvider,
        AcidModelStore.ModelEntry activeModel
    ) {
        container.removeAllViews();
        Context ctx = getContext();
        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accent = AcidTheme.accent(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        String query = mFilterQuery != null ? mFilterQuery.trim().toLowerCase(Locale.US) : "";
        int matchedCount = 0;

        for (int i = 0; i < providers.size(); i++) {
            final AcidModelStore.ConfiguredProvider p = providers.get(i);
            if (p.models.isEmpty()) continue;

            List<AcidModelStore.ModelEntry> matchedModels = new ArrayList<AcidModelStore.ModelEntry>();
            for (AcidModelStore.ModelEntry m : p.models) {
                if (query.isEmpty() ||
                    p.displayName.toLowerCase(Locale.US).contains(query) ||
                    m.name.toLowerCase(Locale.US).contains(query) ||
                    m.id.toLowerCase(Locale.US).contains(query)) {
                    matchedModels.add(m);
                }
            }
            if (matchedModels.isEmpty()) continue;

            // Provider Group Header
            LinearLayout groupHead = new LinearLayout(ctx);
            groupHead.setOrientation(LinearLayout.HORIZONTAL);
            groupHead.setGravity(Gravity.CENTER_VERTICAL);
            groupHead.setPadding(AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4));

            TextView tvGroupName = new TextView(ctx);
            tvGroupName.setText("供应商: " + p.displayName);
            tvGroupName.setTextColor(mutedCol);
            tvGroupName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvGroupName.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams gnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            groupHead.addView(tvGroupName, gnLp);

            boolean hasKey = p.hasApiKey();
            TextView tvKeyBadge = new TextView(ctx);
            tvKeyBadge.setText(hasKey ? "● 密钥已就绪" : "● 待填 API Key");
            tvKeyBadge.setTextColor(hasKey ? accent : AcidTheme.COLOR_AMBER);
            tvKeyBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            groupHead.addView(tvKeyBadge);

            container.addView(groupHead);

            // Models inside this provider
            for (int j = 0; j < matchedModels.size(); j++) {
                final AcidModelStore.ModelEntry m = matchedModels.get(j);
                matchedCount++;
                final boolean isSelected = (activeProvider != null && activeModel != null &&
                    p.id.equals(activeProvider.id) && m.id.equals(activeModel.id));

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padR = AcidTheme.dp(ctx, 10);
                row.setPadding(padR, padR, padR, padR);
                int rBg = isSelected ? (mIsDarkMode ? Color.parseColor("#0C4A6E") : Color.parseColor("#E0F2FE")) : cardBg;
                int rStroke = isSelected ? blue : borderCol;
                row.setBackground(AcidTheme.pillBg(ctx, rBg, rStroke, 10));
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rLp.bottomMargin = AcidTheme.dp(ctx, 6);

                AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
                    ctx, AcidIcons.ICON_MODEL, isSelected ? blue : accent, 17
                );
                row.addView(icon);

                LinearLayout textColView = new LinearLayout(ctx);
                textColView.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tcLp.leftMargin = AcidTheme.dp(ctx, 8);
                tcLp.rightMargin = AcidTheme.dp(ctx, 8);

                TextView tvTitle = new TextView(ctx);
                tvTitle.setText(p.displayName + "  ·  " + m.name);
                tvTitle.setTextColor(textCol);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
                tvTitle.setSingleLine(true);
                tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                textColView.addView(tvTitle);

                TextView tvSub = new TextView(ctx);
                String subInfo = "ID: " + m.id + (m.supportsReasoning ? "  ·  支持思考深度" : "  ·  标准对话");
                tvSub.setText(subInfo);
                tvSub.setTextColor(m.supportsReasoning ? blue : mutedCol);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                tvSub.setSingleLine(true);
                tvSub.setEllipsize(TextUtils.TruncateAt.END);
                textColView.addView(tvSub);

                row.addView(textColView, tcLp);

                if (isSelected) {
                    AcidIcons.SvgIconView check = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CHECK, blue, 18);
                    row.addView(check);
                } else {
                    TextView tvPick = new TextView(ctx);
                    tvPick.setText("选择");
                    tvPick.setTextColor(mutedCol);
                    tvPick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    row.addView(tvPick);
                }

                row.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mModelStore.selectModel(p.id, m.id, null);
                        if (mListener != null) {
                            mListener.onModelConfigChanged();
                        }
                        renderContent();
                        Toast.makeText(
                            ctx,
                            "已切换模型: " + p.displayName + " / " + m.name,
                            Toast.LENGTH_SHORT
                        ).show();
                    }
                });

                container.addView(row, rLp);
            }
        }

        if (matchedCount == 0) {
            TextView tvEmpty = new TextView(ctx);
            tvEmpty.setText(query.isEmpty()
                ? "尚未装载任何模型。请点击右上角 [模型配置] 或浏览器右下角 [功能 -> 模型配置] 添加供应商。"
                : "没有匹配的已装载模型。");
            tvEmpty.setTextColor(mutedCol);
            tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 24), AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 24));
            container.addView(tvEmpty);
        }
    }
}
