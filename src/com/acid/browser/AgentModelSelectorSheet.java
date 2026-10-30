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
 * Model & Reasoning Effort Selector Sheet built on {@link AcidBottomSheetView} and {@link AcidWidgets}.
 *
 * <p>Uses the pinned header slot for the live search input so filtering never steals focus,
 * and delegates to {@code OverlayStackManager.pushModal} when opening provider settings so
 * closing provider settings returns right back to this selector.
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
    }

    public void showSelector() {
        if (mModelStore == null) return;
        mFilterQuery = "";
        rebuildAll();
        show();
    }

    @Override
    public void show() {
        rebuildAll();
        super.show();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            rebuildAll();
        }
    }

    private void rebuildAll() {
        if (mModelStore == null) return;
        setTitle("选择模型与思考深度");
        setAction(AcidIcons.ICON_SETTINGS, "模型配置", AcidTheme.accent(mIsDarkMode), new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onOpenModelProviderConfig();
                }
            }
        });

        clearPinnedHeader();
        LinearLayout container = getContentContainer();
        container.removeAllViews();

        final Context ctx = getContext();
        final List<AcidModelStore.ConfiguredProvider> providers = mModelStore.getProviders();
        final AcidModelStore.ConfiguredProvider activeProvider = mModelStore.getActiveProvider();
        final AcidModelStore.ModelEntry activeModel = mModelStore.getActiveModel();
        final String activeEffort = mModelStore.getSelectedReasoningEffort();

        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // 1. Active Model & Reasoning Effort Card (H3 Card Lead)
        LinearLayout topCard = AcidWidgets.cardGroup(ctx, mIsDarkMode, blue, 1f);
        int pad = AcidTheme.dp(ctx, 14);
        topCard.setPadding(pad, AcidTheme.dp(ctx, 12), pad, AcidTheme.dp(ctx, 12));

        TextView lblCur = new TextView(ctx);
        lblCur.setText("当前使用模型");
        lblCur.setTextColor(mutedCol);
        lblCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        lblCur.setTypeface(Typeface.DEFAULT_BOLD);
        topCard.addView(lblCur);

        TextView tvCurVal = new TextView(ctx);
        if (activeProvider != null && activeModel != null) {
            tvCurVal.setText(activeProvider.displayName + " / " + activeModel.name);
            tvCurVal.setTextColor(textCol);
        } else {
            tvCurVal.setText("暂无可用模型，请点击右上角「模型配置」添加");
            tvCurVal.setTextColor(AcidTheme.COLOR_AMBER);
        }
        tvCurVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H3_CARD);
        tvCurVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvCurVal.setPadding(0, AcidTheme.dp(ctx, 3), 0, AcidTheme.dp(ctx, 8));
        topCard.addView(tvCurVal);

        if (activeModel != null) {
            LinearLayout effortHead = new LinearLayout(ctx);
            effortHead.setOrientation(LinearLayout.HORIZONTAL);
            effortHead.setGravity(Gravity.CENTER_VERTICAL);

            TextView lblEffort = AcidWidgets.fieldLabel(ctx, mIsDarkMode, "思考深度 (Reasoning Effort)");
            effortHead.addView(lblEffort, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView curEffortBadge = new TextView(ctx);
            curEffortBadge.setText(AcidModelStore.getEffortDisplayName(activeEffort));
            curEffortBadge.setTextColor(blue);
            curEffortBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            curEffortBadge.setTypeface(Typeface.DEFAULT_BOLD);
            effortHead.addView(curEffortBadge);
            topCard.addView(effortHead);

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
                        pill.setTextColor(Color.WHITE);
                        pill.setBackground(AcidTheme.interactiveBg(
                            ctx, blue, AcidTheme.adjustPressFill(blue, mIsDarkMode), 0, AcidTheme.RADIUS_CONTROL
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
                            rebuildAll();
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
            setMinContentHeightDp(300);
            final EditText etSearch = new EditText(ctx);
            etSearch.setText(mFilterQuery);
            LinearLayout searchBox = AcidWidgets.searchBox(ctx, mIsDarkMode, etSearch, "搜索已装载的供应商或模型名称...");
            getPinnedHeaderContainer().addView(searchBox);

            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    mFilterQuery = s != null ? s.toString() : "";
                    populateGroups(groupsContainer, providers, activeProvider, activeModel);
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        } else {
            setWrapContentHeight();
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
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accent = AcidTheme.accent(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

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

                LinearLayout row = AcidWidgets.listItemCard(ctx, mIsDarkMode, isSelected, blue);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
                    ctx, AcidIcons.ICON_MODEL, isSelected ? blue : accent, 18
                );
                row.addView(icon);

                LinearLayout textColView = new LinearLayout(ctx);
                textColView.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                );
                tcLp.leftMargin = AcidTheme.dp(ctx, 10);
                tcLp.rightMargin = AcidTheme.dp(ctx, 8);

                TextView tvTitle = new TextView(ctx);
                tvTitle.setText(m.name);
                tvTitle.setTextColor(textCol);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
                tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
                tvTitle.setSingleLine(true);
                tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                textColView.addView(tvTitle);

                TextView tvSub = new TextView(ctx);
                tvSub.setText(p.displayName + " · " + m.id);
                tvSub.setTextColor(mutedCol);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvSub.setSingleLine(true);
                tvSub.setEllipsize(TextUtils.TruncateAt.END);
                tvSub.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
                textColView.addView(tvSub);

                row.addView(textColView, tcLp);

                if (isSelected) {
                    AcidIcons.SvgIconView check = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CHECK, blue, 18);
                    row.addView(check);
                } else {
                    TextView tvPick = new TextView(ctx);
                    tvPick.setText("选择");
                    tvPick.setTextColor(mutedCol);
                    tvPick.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                    row.addView(tvPick);
                }

                row.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mModelStore.selectModel(p.id, m.id, null);
                        if (mListener != null) {
                            mListener.onModelConfigChanged();
                        }
                        rebuildAll();
                        Toast.makeText(ctx, "已切换模型: " + p.displayName + " / " + m.name, Toast.LENGTH_SHORT).show();
                    }
                });

                container.addView(row);
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
