package com.acid.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Model & Reasoning Effort Selector Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage}
 * template and {@link AcidWidgets}.
 */
public class AgentModelSelectorSheet extends AcidBottomSheetView {

    public interface ModelSelectorListener {
        void onOpenModelProviderConfig();
        void onModelConfigChanged();
    }

    private final AcidModelStore mModelStore;
    private final ModelSelectorListener mListener;
    private String mFilterQuery = "";

    public AgentModelSelectorSheet(Context context, AcidModelStore modelStore, ModelSelectorListener listener) {
        super(context);
        mModelStore = modelStore;
        mListener = listener;
        setRootPage(buildSelectorPage());
    }

    @Override
    protected void onResetToRootPage() {
        mFilterQuery = "";
        setRootPage(buildSelectorPage());
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildSelectorPage());
        }
        super.show();
    }

    private SheetPage buildSelectorPage() {
        boolean manyModels = mModelStore != null && mModelStore.getTotalLoadedModelsCount() > 4;
        return new SheetPage("选择模型", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                renderSelectorContent(pinned, container);
            }
        })
            .withAction(AcidIcons.ICON_SETTINGS, "模型配置", new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) {
                        mListener.onOpenModelProviderConfig();
                    }
                }
            })
            .withMinContentHeightDp(manyModels ? 300f : 0f);
    }

    private void renderSelectorContent(LinearLayout pinned, LinearLayout container) {
        if (mModelStore == null) return;
        final Context ctx = getContext();
        final List<AcidModelStore.ConfiguredProvider> providers = mModelStore.getProviders();
        final AcidModelStore.ConfiguredProvider activeProvider = mModelStore.getActiveProvider();
        final AcidModelStore.ModelEntry activeModel = mModelStore.getActiveModel();
        final String activeEffort = mModelStore.getSelectedReasoningEffort();

        int textCol = AcidTheme.text(mIsDarkMode);
        int accent = AcidTheme.accent(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // 1. Active Model & Reasoning Effort Hero Card
        LinearLayout topCard = AcidWidgets.heroCard(ctx, mIsDarkMode, accent);
        topCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "当前使用模型"));

        TextView tvCurVal = new TextView(ctx);
        if (activeProvider != null && activeModel != null) {
            tvCurVal.setText(activeProvider.displayName + " / " + activeModel.name);
            tvCurVal.setTextColor(textCol);
        } else {
            tvCurVal.setText("暂无可用模型，请点击右上角「模型配置」添加");
            tvCurVal.setTextColor(AcidTheme.COLOR_AMBER);
        }
        tvCurVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
        tvCurVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvCurVal.setPadding(0, AcidTheme.dp(ctx, 2), 0, AcidTheme.dp(ctx, 8));
        topCard.addView(tvCurVal);

        if (activeModel != null) {
            topCard.addView(AcidWidgets.fieldLabelWithAction(
                ctx,
                mIsDarkMode,
                "思考深度 (Reasoning Effort)",
                AcidWidgets.statusTag(ctx, mIsDarkMode, AcidModelStore.getEffortDisplayName(activeEffort), blue)
            ));

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
                    boolean isSel = efId.equals(activeEffort);

                    TextView pill = new TextView(ctx);
                    pill.setText(AcidModelStore.getEffortDisplayName(efId));
                    pill.setGravity(Gravity.CENTER);
                    pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                    pill.setTypeface(isSel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    int pPad = AcidTheme.dp(ctx, 7);
                    pill.setPadding(pPad, pPad, pPad, pPad);
                    if (isSel) {
                        pill.setTextColor(AcidTheme.onColor(accent));
                        pill.setBackground(AcidTheme.interactiveBg(
                            ctx, accent, AcidTheme.adjustPressFill(accent, mIsDarkMode), 0, AcidTheme.RADIUS_CONTROL
                        ));
                    } else {
                        pill.setTextColor(textCol);
                        pill.setBackground(AcidTheme.interactiveBg(
                            ctx,
                            AcidTheme.formInputBg(mIsDarkMode),
                            AcidTheme.rowPressed(mIsDarkMode),
                            AcidTheme.border(mIsDarkMode),
                            AcidTheme.RADIUS_CONTROL
                        ));
                    }
                    pill.setClickable(true);
                    pill.setFocusable(true);
                    pill.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mModelStore.setReasoningEffort(efId);
                            if (mListener != null) {
                                mListener.onModelConfigChanged();
                            }
                            refreshActivePage();
                        }
                    });

                    LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c > 0) pLp.leftMargin = AcidTheme.dp(ctx, 6);
                    eRow.addView(pill, pLp);
                }
                topCard.addView(eRow);
            }
        }
        container.addView(topCard);

        // 2. Pinned Search Bar (when > 4 models loaded)
        final LinearLayout groupsContainer = new LinearLayout(ctx);
        groupsContainer.setOrientation(LinearLayout.VERTICAL);

        if (mModelStore.getTotalLoadedModelsCount() > 4) {
            final EditText etSearch = new EditText(ctx);
            etSearch.setText(mFilterQuery);
            pinned.addView(AcidWidgets.searchBox(ctx, mIsDarkMode, etSearch, "搜索已装载的供应商或模型名称..."));

            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    mFilterQuery = s != null ? s.toString() : "";
                    populateGroups(groupsContainer, providers, activeProvider, activeModel);
                }
                @Override public void afterTextChanged(Editable s) {}
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
        final Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);

        String query = mFilterQuery != null ? mFilterQuery.trim().toLowerCase(Locale.US) : "";
        int matchedCount = 0;

        for (int i = 0; i < providers.size(); i++) {
            final AcidModelStore.ConfiguredProvider p = providers.get(i);
            if (p.models.isEmpty()) continue;

            List<AcidModelStore.ModelEntry> matched = new ArrayList<AcidModelStore.ModelEntry>();
            for (AcidModelStore.ModelEntry m : p.models) {
                if (query.isEmpty() ||
                    p.displayName.toLowerCase(Locale.US).contains(query) ||
                    m.name.toLowerCase(Locale.US).contains(query) ||
                    m.id.toLowerCase(Locale.US).contains(query)) {
                    matched.add(m);
                }
            }
            if (matched.isEmpty()) continue;

            boolean hasKey = p.hasApiKey();
            container.addView(AcidWidgets.sectionHeaderRow(
                ctx,
                mIsDarkMode,
                p.displayName,
                hasKey ? "● 密钥已就绪" : "● 待填 API Key",
                hasKey ? accent : AcidTheme.COLOR_AMBER
            ));

            for (int j = 0; j < matched.size(); j++) {
                final AcidModelStore.ModelEntry m = matched.get(j);
                matchedCount++;
                final boolean isSelected = (activeProvider != null && activeModel != null &&
                    p.id.equals(activeProvider.id) && m.id.equals(activeModel.id));

                AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
                    .leadingIcon(AcidIcons.ICON_MODEL, accent, 18)
                    .title(m.name)
                    .subtitle(p.displayName + " · " + m.id)
                    .selected(isSelected, accent)
                    .onClick(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mModelStore.selectModel(p.id, m.id, null);
                            if (mListener != null) {
                                mListener.onModelConfigChanged();
                            }
                            refreshActivePage();
                            AcidFeedback.ok(ctx, "已切换模型: " + p.displayName + " / " + m.name);
                        }
                    });

                if (isSelected) {
                    rowBuilder.trailingCheck(ctx, accent);
                } else {
                    rowBuilder.trailingText(ctx, mIsDarkMode, "选择");
                }

                container.addView(rowBuilder.build(ctx, mIsDarkMode));
            }
        }

        if (matchedCount == 0) {
            container.addView(AcidWidgets.emptyState(
                ctx, mIsDarkMode, AcidIcons.ICON_MODEL,
                query.isEmpty()
                    ? "尚未装载任何模型，请点击右上角「模型配置」添加供应商"
                    : "未找到匹配的已装载模型"
            ));
        }
    }
}
