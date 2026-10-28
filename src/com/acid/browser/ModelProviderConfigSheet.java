package com.acid.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Provider & Model Management Sheet.
 * Encapsulates the 3-level navigation:
 * 1. Configured Providers List (Test Connection, Edit, Delete)
 * 2. Add Provider (Catalog presets vs. Custom OpenAI-compatible endpoint)
 * 3. Provider Editor (Base URL, API Key with toggle, Model discovery via GET /models, Manual model addition)
 */
public class ModelProviderConfigSheet extends AcidBottomSheetView {

    public interface OnConfigChangeListener {
        void onProviderConfigChanged();
    }

    private static final int STATE_LIST = 0;
    private static final int STATE_ADD = 1;
    private static final int STATE_EDIT = 2;

    private int mCurrentState = STATE_LIST;
    private final AcidModelStore mModelStore;
    private final OnConfigChangeListener mListener;
    private boolean mIsDarkMode = false;

    public ModelProviderConfigSheet(Context context, AcidModelStore modelStore, OnConfigChangeListener listener) {
        super(context);
        mModelStore = modelStore;
        mListener = listener;
    }

    @Override
    public void applyTheme(boolean isDark) {
        super.applyTheme(isDark);
        mIsDarkMode = isDark;
    }

    public boolean handleBackPressed() {
        if (!isShowing()) return false;
        if (mCurrentState == STATE_EDIT) {
            showConfig();
            return true;
        } else if (mCurrentState == STATE_ADD) {
            showConfig();
            return true;
        } else {
            dismiss();
            return true;
        }
    }

    // =========================================================================
    // 1. Configured Providers List View (State: STATE_LIST)
    // =========================================================================

    public void showConfig() {
        if (mModelStore == null) return;
        mCurrentState = STATE_LIST;
        hideKeyboard();
        setTitle("模型配置");

        int accent = AcidTheme.accent(mIsDarkMode);
        setAction(AcidIcons.ICON_PLUS, "添加供应商", accent, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddProviderView(false);
            }
        });

        renderConfiguredProvidersList();
        show();
    }

    private void renderConfiguredProvidersList() {
        LinearLayout container = getContentContainer();
        container.removeAllViews();
        if (mModelStore == null) return;

        final Context ctx = getContext();
        final List<AcidModelStore.ConfiguredProvider> providers = mModelStore.getProviders();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accent = AcidTheme.accent(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // Intro description banner
        TextView intro = new TextView(ctx);
        intro.setText("填入各供应商的 API 密钥即可在 Agent 模型选择器中使用其模型。点击右上角 [+ 添加供应商] 可选择内置默认供应商或添加自定义 OpenAI 兼容接口。");
        intro.setTextColor(mutedCol);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        intro.setPadding(AcidTheme.dp(ctx, 2), 0, AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 10));
        container.addView(intro);

        for (int i = 0; i < providers.size(); i++) {
            final AcidModelStore.ConfiguredProvider p = providers.get(i);

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, borderCol, 1, 12));
            int padH = AcidTheme.dp(ctx, 12);
            int padV = AcidTheme.dp(ctx, 10);
            card.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cLp.bottomMargin = AcidTheme.dp(ctx, 10);
            card.setLayoutParams(cLp);

            // Row 1: Name + Tag + Status Dot
            LinearLayout headRow = new LinearLayout(ctx);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(ctx);
            tvName.setText(p.displayName);
            tvName.setTextColor(textCol);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(tvName);

            TextView tvTag = new TextView(ctx);
            tvTag.setText(p.isCustom ? "自定义" : "内置目录");
            tvTag.setTextColor(p.isCustom ? AcidTheme.COLOR_PURPLE : blue);
            tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            int padTagH = AcidTheme.dp(ctx, 6);
            int padTagV = AcidTheme.dp(ctx, 2);
            tvTag.setPadding(padTagH, padTagV, padTagH, padTagV);
            tvTag.setBackground(AcidTheme.pillBg(
                ctx,
                p.isCustom ? Color.argb(26, 139, 92, 246) : Color.argb(26, 2, 132, 199),
                0, 4
            ));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tagLp.leftMargin = AcidTheme.dp(ctx, 6);
            headRow.addView(tvTag, tagLp);

            View spacer = new View(ctx);
            headRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            boolean hasKey = p.hasApiKey();
            TextView tvKeyState = new TextView(ctx);
            tvKeyState.setText(hasKey ? "● API 密钥已配置" : "● API 密钥缺失");
            tvKeyState.setTextColor(hasKey ? accent : AcidTheme.COLOR_RED);
            tvKeyState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvKeyState.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(tvKeyState);
            card.addView(headRow);

            // Row 2: Base URL
            TextView tvUrl = new TextView(ctx);
            tvUrl.setText("API: " + (p.baseUrl.isEmpty() ? "(使用默认地址)" : p.baseUrl));
            tvUrl.setTextColor(mutedCol);
            tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvUrl.setSingleLine(true);
            tvUrl.setEllipsize(TextUtils.TruncateAt.END);
            tvUrl.setPadding(0, AcidTheme.dp(ctx, 4), 0, AcidTheme.dp(ctx, 4));
            card.addView(tvUrl);

            // Row 3: Models Badges Summary
            StringBuilder sb = new StringBuilder("已装载 " + p.models.size() + " 个模型: ");
            for (int j = 0; j < p.models.size() && j < 4; j++) {
                if (j > 0) sb.append(", ");
                sb.append(p.models.get(j).name);
            }
            if (p.models.size() > 4) sb.append(" 等");
            TextView tvModelsSummary = new TextView(ctx);
            tvModelsSummary.setText(sb.toString());
            tvModelsSummary.setTextColor(blue);
            tvModelsSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvModelsSummary.setSingleLine(true);
            tvModelsSummary.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(tvModelsSummary);

            // Row 4: Action Buttons Bar: [测试连通性] [编辑] [删除]
            LinearLayout actionsBar = new LinearLayout(ctx);
            actionsBar.setOrientation(LinearLayout.HORIZONTAL);
            actionsBar.setGravity(Gravity.CENTER_VERTICAL);
            actionsBar.setPadding(0, AcidTheme.dp(ctx, 8), 0, 0);

            // Button: Test Connection
            final TextView btnTest = new TextView(ctx);
            btnTest.setText("测试连通性");
            btnTest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            btnTest.setTextColor(textCol);
            int padBtnH = AcidTheme.dp(ctx, 10);
            int padBtnV = AcidTheme.dp(ctx, 5);
            btnTest.setPadding(padBtnH, padBtnV, padBtnH, padBtnV);
            btnTest.setBackground(AcidTheme.pillBg(ctx, mIsDarkMode ? AcidTheme.DARK_BG : Color.WHITE, borderCol, 6));
            btnTest.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    btnTest.setText("测试中...");
                    btnTest.setEnabled(false);
                    AcidModelStore.discoverModelsAsync(p.baseUrl, p.apiKey, new AcidModelStore.ModelDiscoveryCallback() {
                        @Override
                        public void onModelsDiscovered(final List<AcidModelStore.ModelEntry> models) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    btnTest.setText("测试连通性");
                                    btnTest.setEnabled(true);
                                    Toast.makeText(ctx, "连接成功! 服务端返回 " + models.size() + " 个可用模型", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        @Override
                        public void onDiscoveryFailed(final String error) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    btnTest.setText("测试连通性");
                                    btnTest.setEnabled(true);
                                    Toast.makeText(ctx, "连接测试失败: " + error, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                }
            });
            actionsBar.addView(btnTest);

            // Button: Edit
            TextView btnEdit = new TextView(ctx);
            btnEdit.setText("配置与模型");
            btnEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            btnEdit.setTextColor(Color.WHITE);
            btnEdit.setPadding(padBtnH, padBtnV, padBtnH, padBtnV);
            btnEdit.setBackground(AcidTheme.pillBg(ctx, blue, blue, 6));
            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            editLp.leftMargin = AcidTheme.dp(ctx, 8);
            btnEdit.setLayoutParams(editLp);
            btnEdit.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditProviderView(p, null);
                }
            });
            actionsBar.addView(btnEdit);

            // Button: Delete (if custom or non-default)
            if (p.isCustom) {
                View space2 = new View(ctx);
                actionsBar.addView(space2, new LinearLayout.LayoutParams(0, 1, 1f));

                TextView btnDel = new TextView(ctx);
                btnDel.setText("删除");
                btnDel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                btnDel.setTextColor(AcidTheme.COLOR_RED);
                btnDel.setPadding(padBtnH, padBtnV, padBtnH, padBtnV);
                btnDel.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mModelStore.deleteProvider(p.id);
                        if (mListener != null) mListener.onProviderConfigChanged();
                        renderConfiguredProvidersList();
                        Toast.makeText(ctx, "已删除供应商: " + p.displayName, Toast.LENGTH_SHORT).show();
                    }
                });
                actionsBar.addView(btnDel);
            }

            card.addView(actionsBar);
            container.addView(card);
        }

        // Add Bottom Quick Trigger Button
        TextView btnAddBottom = new TextView(ctx);
        btnAddBottom.setText("+ 添加供应商 (内置目录 / 自定义)");
        btnAddBottom.setTextColor(blue);
        btnAddBottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btnAddBottom.setTypeface(Typeface.DEFAULT_BOLD);
        btnAddBottom.setGravity(Gravity.CENTER);
        int padAb = AcidTheme.dp(ctx, 10);
        btnAddBottom.setPadding(padAb, padAb, padAb, padAb);
        btnAddBottom.setBackground(AcidTheme.pillBg(ctx, cardBg, borderCol, 8));
        btnAddBottom.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddProviderView(false);
            }
        });
        container.addView(btnAddBottom, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    // =========================================================================
    // 2. Add Provider View (State: STATE_ADD)
    // =========================================================================

    public void showAddProviderView(final boolean customMode) {
        if (mModelStore == null) return;
        mCurrentState = STATE_ADD;
        hideKeyboard();
        setTitle("添加模型供应商");

        int mutedCol = AcidTheme.muted(mIsDarkMode);
        setAction(AcidIcons.ICON_BACK, "返回列表", mutedCol, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfig();
            }
        });

        LinearLayout container = getContentContainer();
        container.removeAllViews();
        Context ctx = getContext();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        int textCol = AcidTheme.text(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // Segmented Switch
        LinearLayout segBar = new LinearLayout(ctx);
        segBar.setOrientation(LinearLayout.HORIZONTAL);
        int segPad = AcidTheme.dp(ctx, 3);
        segBar.setPadding(segPad, segPad, segPad, segPad);
        segBar.setBackground(AcidTheme.pillBg(ctx, cardBg, borderCol, 10));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        segLp.bottomMargin = AcidTheme.dp(ctx, 8);
        segBar.setLayoutParams(segLp);

        TextView tabCatalog = new TextView(ctx);
        tabCatalog.setText("默认供应商目录 (10)");
        tabCatalog.setGravity(Gravity.CENTER);
        tabCatalog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tabCatalog.setTypeface(!customMode ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        int tPad = AcidTheme.dp(ctx, 8);
        tabCatalog.setPadding(tPad, tPad, tPad, tPad);
        tabCatalog.setTextColor(!customMode ? Color.WHITE : textCol);
        tabCatalog.setBackground(!customMode ? AcidTheme.pillBg(ctx, blue, 0, 8) : null);
        tabCatalog.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (customMode) showAddProviderView(false);
            }
        });
        segBar.addView(tabCatalog, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tabCustom = new TextView(ctx);
        tabCustom.setText("自定义供应商 API");
        tabCustom.setGravity(Gravity.CENTER);
        tabCustom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tabCustom.setTypeface(customMode ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabCustom.setPadding(tPad, tPad, tPad, tPad);
        tabCustom.setTextColor(customMode ? Color.WHITE : textCol);
        tabCustom.setBackground(customMode ? AcidTheme.pillBg(ctx, blue, 0, 8) : null);
        tabCustom.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!customMode) showAddProviderView(true);
            }
        });
        segBar.addView(tabCustom, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        container.addView(segBar);

        TextView hint = new TextView(ctx);
        hint.setText(!customMode
            ? "从内置目录中选择 DeepSeek、OpenAI、Kimi、智谱 GLM、通义千问等主流提供商，填入 API 密钥即可装载使用。"
            : "连接中转站、自部署服务 (Ollama / vLLM) 或其他兼容 OpenAI 协议的接口，需填写供应商名称、API 地址和模型。");
        hint.setTextColor(mutedCol);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hint.setPadding(AcidTheme.dp(ctx, 2), 0, AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 10));
        container.addView(hint);

        if (!customMode) {
            renderBuiltinCatalogPresetsList();
        } else {
            AcidModelStore.ConfiguredProvider draftCustom = new AcidModelStore.ConfiguredProvider(
                "", "", true, "", ""
            );
            renderProviderEditorForm(draftCustom, null, true);
        }
    }

    private void renderBuiltinCatalogPresetsList() {
        LinearLayout container = getContentContainer();
        Context ctx = getContext();
        List<AcidModelStore.CatalogProviderPreset> presets = AcidModelStore.getBuiltinCatalogPresets();

        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accent = AcidTheme.accent(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        for (int i = 0; i < presets.size(); i++) {
            final AcidModelStore.CatalogProviderPreset preset = presets.get(i);
            final AcidModelStore.ConfiguredProvider existing = mModelStore.getProvider(preset.id);
            boolean isLoaded = (existing != null);

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, isLoaded ? accent : borderCol, 1, 10));
            int padH = AcidTheme.dp(ctx, 12);
            int padV = AcidTheme.dp(ctx, 10);
            card.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cLp.bottomMargin = AcidTheme.dp(ctx, 8);
            card.setLayoutParams(cLp);

            LinearLayout rowTop = new LinearLayout(ctx);
            rowTop.setOrientation(LinearLayout.HORIZONTAL);
            rowTop.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(preset.displayName);
            tvTitle.setTextColor(textCol);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rowTop.addView(tvTitle, ttLp);

            TextView tvAction = new TextView(ctx);
            tvAction.setText(isLoaded ? "已装载 · 点击编辑" : "+ 选择并配置");
            tvAction.setTextColor(isLoaded ? accent : blue);
            tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvAction.setTypeface(Typeface.DEFAULT_BOLD);
            int padAcH = AcidTheme.dp(ctx, 8);
            int padAcV = AcidTheme.dp(ctx, 3);
            tvAction.setPadding(padAcH, padAcV, padAcH, padAcV);
            tvAction.setBackground(AcidTheme.pillBg(
                ctx,
                isLoaded ? Color.argb(26, 0, 186, 118) : Color.argb(26, 2, 132, 199),
                0, 6
            ));
            rowTop.addView(tvAction);
            card.addView(rowTop);

            TextView tvDesc = new TextView(ctx);
            tvDesc.setText(preset.description);
            tvDesc.setTextColor(mutedCol);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tvDesc.setPadding(0, AcidTheme.dp(ctx, 3), 0, AcidTheme.dp(ctx, 2));
            card.addView(tvDesc);

            StringBuilder mListStr = new StringBuilder("预设模型: ");
            for (int j = 0; j < preset.defaultModels.size(); j++) {
                if (j > 0) mListStr.append(", ");
                mListStr.append(preset.defaultModels.get(j).name);
            }
            TextView tvModels = new TextView(ctx);
            tvModels.setText(mListStr.toString());
            tvModels.setTextColor(blue);
            tvModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f);
            card.addView(tvModels);

            card.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditProviderView(existing, preset);
                }
            });

            container.addView(card);
        }
    }

    // =========================================================================
    // 3. Provider Editor Form View (State: STATE_EDIT)
    // =========================================================================

    public void showEditProviderView(
        final AcidModelStore.ConfiguredProvider existing,
        final AcidModelStore.CatalogProviderPreset preset
    ) {
        if (mModelStore == null) return;
        mCurrentState = STATE_EDIT;
        hideKeyboard();

        String title = existing != null
            ? ("编辑 " + existing.displayName)
            : (preset != null ? ("配置 " + preset.displayName) : "创建自定义供应商");
        setTitle(title);

        int mutedCol = AcidTheme.muted(mIsDarkMode);
        setAction(AcidIcons.ICON_BACK, "返回", mutedCol, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfig();
            }
        });

        getContentContainer().removeAllViews();

        AcidModelStore.ConfiguredProvider workingDraft;
        if (existing != null) {
            workingDraft = new AcidModelStore.ConfiguredProvider(
                existing.id, existing.displayName, existing.isCustom, existing.baseUrl, existing.apiKey
            );
            for (AcidModelStore.ModelEntry m : existing.models) {
                workingDraft.models.add(m.copy());
            }
        } else if (preset != null) {
            workingDraft = new AcidModelStore.ConfiguredProvider(
                preset.id, preset.displayName, false, preset.defaultBaseUrl, ""
            );
            for (AcidModelStore.ModelEntry m : preset.defaultModels) {
                workingDraft.models.add(m.copy());
            }
        } else {
            workingDraft = new AcidModelStore.ConfiguredProvider("", "", true, "", "");
        }

        renderProviderEditorForm(workingDraft, preset, false);
    }

    private void renderProviderEditorForm(
        final AcidModelStore.ConfiguredProvider draft,
        final AcidModelStore.CatalogProviderPreset preset,
        final boolean embeddedInAddTab
    ) {
        final Context ctx = getContext();
        int cardBg = mIsDarkMode ? Color.parseColor("#1E293B") : Color.parseColor("#F8FAFC");
        int inputBg = mIsDarkMode ? AcidTheme.DARK_BG : Color.WHITE;
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        final int textCol = AcidTheme.text(mIsDarkMode);
        final int mutedCol = AcidTheme.muted(mIsDarkMode);
        final int blue = AcidTheme.COLOR_BLUE;

        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, borderCol, 1, 12));
        int padF = AcidTheme.dp(ctx, 12);
        form.setPadding(padF, padF, padF, padF);

        // 1. Display Name
        TextView lblName = new TextView(ctx);
        lblName.setText(draft.isCustom ? "供应商显示名称 (必填):" : ("供应商: " + draft.displayName + " (ID: " + draft.id + ")"));
        lblName.setTextColor(textCol);
        lblName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblName.setTypeface(Typeface.DEFAULT_BOLD);
        form.addView(lblName);

        final EditText etDisplayName = new EditText(ctx);
        etDisplayName.setSingleLine(true);
        etDisplayName.setText(draft.displayName);
        etDisplayName.setHint("例如: 我的中转网关 / Ollama 本地服务");
        etDisplayName.setTextColor(textCol);
        etDisplayName.setHintTextColor(mutedCol);
        etDisplayName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        etDisplayName.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 7), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 7));
        etDisplayName.setBackground(AcidTheme.pillBg(ctx, inputBg, borderCol, 8));
        if (draft.isCustom) {
            LinearLayout.LayoutParams dnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            dnLp.topMargin = AcidTheme.dp(ctx, 4);
            dnLp.bottomMargin = AcidTheme.dp(ctx, 8);
            form.addView(etDisplayName, dnLp);
        } else {
            lblName.setPadding(0, 0, 0, AcidTheme.dp(ctx, 8));
        }

        // 2. API Key with Show/Hide Toggle
        LinearLayout keyLabelRow = new LinearLayout(ctx);
        keyLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        keyLabelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblKey = new TextView(ctx);
        lblKey.setText("API 密钥 (API Key):");
        lblKey.setTextColor(textCol);
        lblKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblKey.setTypeface(Typeface.DEFAULT_BOLD);
        keyLabelRow.addView(lblKey, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView btnToggleKeyVis = new TextView(ctx);
        btnToggleKeyVis.setText("显示密钥");
        btnToggleKeyVis.setTextColor(blue);
        btnToggleKeyVis.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btnToggleKeyVis.setPadding(AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2));
        keyLabelRow.addView(btnToggleKeyVis);
        form.addView(keyLabelRow);

        final EditText etApiKey = new EditText(ctx);
        etApiKey.setSingleLine(true);
        etApiKey.setText(draft.apiKey);
        etApiKey.setHint(preset != null ? ("输入 API Key (" + preset.envKeyHint + ")") : "输入 sk-... (本地免鉴权接口可留空)");
        etApiKey.setTextColor(textCol);
        etApiKey.setHintTextColor(mutedCol);
        etApiKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        etApiKey.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 7), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 7));
        etApiKey.setBackground(AcidTheme.pillBg(ctx, inputBg, borderCol, 8));
        etApiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        keyLp.topMargin = AcidTheme.dp(ctx, 4);
        keyLp.bottomMargin = AcidTheme.dp(ctx, 8);
        form.addView(etApiKey, keyLp);

        btnToggleKeyVis.setOnClickListener(new OnClickListener() {
            private boolean revealed = false;
            @Override
            public void onClick(View v) {
                revealed = !revealed;
                etApiKey.setTransformationMethod(revealed
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
                btnToggleKeyVis.setText(revealed ? "隐藏密钥" : "显示密钥");
                etApiKey.setSelection(etApiKey.getText().length());
            }
        });

        // 3. Base URL
        TextView lblUrl = new TextView(ctx);
        lblUrl.setText("API 地址 (Base URL - OpenAI 兼容接口):");
        lblUrl.setTextColor(textCol);
        lblUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblUrl.setTypeface(Typeface.DEFAULT_BOLD);
        form.addView(lblUrl);

        final EditText etBaseUrl = new EditText(ctx);
        etBaseUrl.setSingleLine(true);
        etBaseUrl.setText(draft.baseUrl);
        etBaseUrl.setHint(preset != null ? preset.defaultBaseUrl : "https://gateway.example/v1");
        etBaseUrl.setTextColor(textCol);
        etBaseUrl.setHintTextColor(mutedCol);
        etBaseUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        etBaseUrl.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 7), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 7));
        etBaseUrl.setBackground(AcidTheme.pillBg(ctx, inputBg, borderCol, 8));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        urlLp.topMargin = AcidTheme.dp(ctx, 4);
        urlLp.bottomMargin = AcidTheme.dp(ctx, 10);
        form.addView(etBaseUrl, urlLp);

        // 4. Models Catalog Section
        LinearLayout modelHeaderRow = new LinearLayout(ctx);
        modelHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        modelHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblModels = new TextView(ctx);
        lblModels.setText("装载的模型列表");
        lblModels.setTextColor(textCol);
        lblModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lblModels.setTypeface(Typeface.DEFAULT_BOLD);
        modelHeaderRow.addView(lblModels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final LinearLayout modelsListBoxRef = new LinearLayout(ctx);
        modelsListBoxRef.setOrientation(LinearLayout.VERTICAL);

        if (preset != null) {
            TextView btnResetModels = new TextView(ctx);
            btnResetModels.setText("恢复默认模型");
            btnResetModels.setTextColor(blue);
            btnResetModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            btnResetModels.setPadding(AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2));
            modelHeaderRow.addView(btnResetModels);
            btnResetModels.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.models.clear();
                    for (AcidModelStore.ModelEntry m : preset.defaultModels) {
                        draft.models.add(m.copy());
                    }
                    renderDraftModelsList(modelsListBoxRef, draft);
                    Toast.makeText(ctx, "已恢复预设模型目录", Toast.LENGTH_SHORT).show();
                }
            });
        }

        form.addView(modelHeaderRow);
        form.addView(buildModelDiscoveryActionBar(draft, etBaseUrl, etApiKey, modelsListBoxRef));
        form.addView(modelsListBoxRef);
        renderDraftModelsList(modelsListBoxRef, draft);

        // 5. Save Button
        TextView btnSave = new TextView(ctx);
        btnSave.setText(draft.isCustom && draft.id.isEmpty() ? "创建并装载供应商" : "保存并装载供应商");
        btnSave.setTextColor(Color.WHITE);
        btnSave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btnSave.setTypeface(Typeface.DEFAULT_BOLD);
        btnSave.setGravity(Gravity.CENTER);
        int padSave = AcidTheme.dp(ctx, 11);
        btnSave.setPadding(padSave, padSave, padSave, padSave);
        btnSave.setBackground(AcidTheme.pillBg(ctx, blue, blue, 10));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        saveLp.topMargin = AcidTheme.dp(ctx, 14);
        btnSave.setLayoutParams(saveLp);

        btnSave.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                String displayName = etDisplayName.getText().toString().trim();
                String apiKey = etApiKey.getText().toString().trim();
                String baseUrl = etBaseUrl.getText().toString().trim();

                if (draft.isCustom) {
                    if (displayName.isEmpty()) {
                        Toast.makeText(ctx, "供应商名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (draft.id.isEmpty()) {
                        draft.id = "custom_" + System.currentTimeMillis();
                    }
                    draft.displayName = displayName;
                }
                draft.apiKey = apiKey;
                if (!baseUrl.isEmpty()) {
                    draft.baseUrl = baseUrl;
                } else if (draft.baseUrl.isEmpty() && preset != null) {
                    draft.baseUrl = preset.defaultBaseUrl;
                }

                if (draft.models.isEmpty()) {
                    Toast.makeText(ctx, "请至少为此供应商添加或保留一个可用模型", Toast.LENGTH_SHORT).show();
                    return;
                }

                mModelStore.upsertProvider(draft);
                if (mListener != null) {
                    mListener.onProviderConfigChanged();
                }
                Toast.makeText(ctx, "供应商 " + draft.displayName + " 配置已保存！", Toast.LENGTH_SHORT).show();
                showConfig();
            }
        });
        form.addView(btnSave);

        getContentContainer().addView(form);
    }

    private View buildModelDiscoveryActionBar(
        final AcidModelStore.ConfiguredProvider draft,
        final EditText etBaseUrl,
        final EditText etApiKey,
        final LinearLayout modelsListBox
    ) {
        Context ctx = getContext();
        int inputBg = mIsDarkMode ? AcidTheme.DARK_BG : Color.WHITE;
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        int textCol = AcidTheme.text(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, AcidTheme.dp(ctx, 6), 0, AcidTheme.dp(ctx, 6));

        // Button: Add Manually
        TextView btnAdd = new TextView(ctx);
        btnAdd.setText("+ 手动添加");
        btnAdd.setTextColor(textCol);
        btnAdd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        int padH = AcidTheme.dp(ctx, 8);
        int padV = AcidTheme.dp(ctx, 4);
        btnAdd.setPadding(padH, padV, padH, padV);
        btnAdd.setBackground(AcidTheme.pillBg(ctx, inputBg, borderCol, 6));
        btnAdd.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualAddModelDialog(draft, modelsListBox);
            }
        });
        bar.addView(btnAdd);

        // Button: Sync Online Models
        final TextView btnSync = new TextView(ctx);
        btnSync.setText("从接口同步模型列表 (/models)");
        btnSync.setTextColor(blue);
        btnSync.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnSync.setPadding(padH, padV, padH, padV);
        btnSync.setBackground(AcidTheme.pillBg(ctx, inputBg, borderCol, 6));
        LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        syncLp.leftMargin = AcidTheme.dp(ctx, 6);
        btnSync.setLayoutParams(syncLp);

        btnSync.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                String targetUrl = etBaseUrl.getText().toString().trim();
                if (targetUrl.isEmpty()) targetUrl = draft.baseUrl;
                String apiKey = etApiKey.getText().toString().trim();
                if (targetUrl.isEmpty()) {
                    Toast.makeText(getContext(), "请先填入有效的 API Base URL", Toast.LENGTH_SHORT).show();
                    return;
                }

                btnSync.setText("获取中...");
                btnSync.setEnabled(false);
                AcidModelStore.discoverModelsAsync(targetUrl, apiKey, new AcidModelStore.ModelDiscoveryCallback() {
                    @Override
                    public void onModelsDiscovered(final List<AcidModelStore.ModelEntry> models) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                btnSync.setText("从接口同步模型列表 (/models)");
                                btnSync.setEnabled(true);
                                if (models.isEmpty()) {
                                    Toast.makeText(getContext(), "接口未返回任何可用模型", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                showDiscoveredModelsPickerDialog(draft, models, modelsListBox);
                            }
                        });
                    }
                    @Override
                    public void onDiscoveryFailed(final String error) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                btnSync.setText("从接口同步模型列表 (/models)");
                                btnSync.setEnabled(true);
                                Toast.makeText(getContext(), "获取模型列表失败: " + error, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });
        bar.addView(btnSync);

        return bar;
    }

    private void renderDraftModelsList(final LinearLayout modelsListBox, final AcidModelStore.ConfiguredProvider draft) {
        modelsListBox.removeAllViews();
        Context ctx = getContext();
        int cardBg = mIsDarkMode ? AcidTheme.DARK_BG : Color.WHITE;
        int borderCol = mIsDarkMode ? AcidTheme.DARK_BORDER : AcidTheme.LIGHT_BORDER;
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        if (draft.models.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("当前暂无已装载模型，请点击上方 [+ 手动添加] 或从接口同步");
            empty.setTextColor(mutedCol);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            empty.setPadding(0, AcidTheme.dp(ctx, 6), 0, AcidTheme.dp(ctx, 6));
            modelsListBox.addView(empty);
            return;
        }

        for (int i = 0; i < draft.models.size(); i++) {
            final int index = i;
            final AcidModelStore.ModelEntry m = draft.models.get(i);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = AcidTheme.dp(ctx, 8);
            row.setPadding(pad, pad, pad, pad);
            row.setBackground(AcidTheme.pillBg(ctx, cardBg, borderCol, 6));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = AcidTheme.dp(ctx, 4);

            LinearLayout tBox = new LinearLayout(ctx);
            tBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

            TextView tvName = new TextView(ctx);
            tvName.setText(m.name);
            tvName.setTextColor(textCol);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            tBox.addView(tvName);

            TextView tvId = new TextView(ctx);
            tvId.setText("ID: " + m.id + (m.supportsReasoning ? "  ·  支持思考深度调节" : ""));
            tvId.setTextColor(m.supportsReasoning ? blue : mutedCol);
            tvId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            tBox.addView(tvId);
            row.addView(tBox, tLp);

            // Delete Model 'X'
            LinearLayout delBox = new LinearLayout(ctx);
            delBox.setGravity(Gravity.CENTER);
            int padD = AcidTheme.dp(ctx, 4);
            delBox.setPadding(padD, padD, padD, padD);
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CLOSE, mutedCol, 12);
            delBox.addView(delIcon);
            delBox.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.models.remove(index);
                    renderDraftModelsList(modelsListBox, draft);
                }
            });
            row.addView(delBox);

            modelsListBox.addView(row, rLp);
        }
    }

    private void showManualAddModelDialog(
        final AcidModelStore.ConfiguredProvider draft,
        final LinearLayout modelsListBox
    ) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AcidTheme.dp(ctx, 16);
        box.setPadding(pad, pad, pad, pad);

        TextView tvId = new TextView(ctx);
        tvId.setText("模型 ID (例如: deepseek-flash, gpt-5.4, claude-sonnet-4-5, gemini-3.8-flash):");
        tvId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        box.addView(tvId);

        final EditText etId = new EditText(ctx);
        etId.setSingleLine(true);
        etId.setHint("输入 exact model ID");
        etId.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        box.addView(etId);

        TextView tvName = new TextView(ctx);
        tvName.setText("显示名称 (选填，留空则使用模型 ID):");
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvName.setPadding(0, AcidTheme.dp(ctx, 8), 0, 0);
        box.addView(tvName);

        final EditText etName = new EditText(ctx);
        etName.setSingleLine(true);
        etName.setHint("例如: DeepSeek R1 思考模型");
        etName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        box.addView(etName);

        final android.widget.CheckBox cbReasoning = new android.widget.CheckBox(ctx);
        cbReasoning.setText("支持调节思考深度 (Reasoning Effort)");
        cbReasoning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        cbReasoning.setChecked(true);
        box.addView(cbReasoning);

        AcidTheme.dialogBuilder(ctx, mIsDarkMode)
            .setTitle("手动添加模型")
            .setView(box)
            .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String id = etId.getText().toString().trim();
                    if (id.isEmpty()) {
                        Toast.makeText(getContext(), "模型 ID 不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = id;
                    if (draft.findModel(id) != null) {
                        Toast.makeText(getContext(), "该模型 ID 已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    draft.models.add(new AcidModelStore.ModelEntry(id, name, cbReasoning.isChecked()));
                    renderDraftModelsList(modelsListBox, draft);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showDiscoveredModelsPickerDialog(
        final AcidModelStore.ConfiguredProvider draft,
        final List<AcidModelStore.ModelEntry> discovered,
        final LinearLayout modelsListBox
    ) {
        Context ctx = getContext();
        final String[] labels = new String[discovered.size()];
        final boolean[] checked = new boolean[discovered.size()];
        for (int i = 0; i < discovered.size(); i++) {
            AcidModelStore.ModelEntry m = discovered.get(i);
            labels[i] = m.id + (m.supportsReasoning ? " [支持思考]" : "");
            checked[i] = (draft.findModel(m.id) != null);
        }

        AcidTheme.dialogBuilder(ctx, mIsDarkMode)
            .setTitle("选择要装载的模型 (共发现 " + discovered.size() + " 个)")
            .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                    checked[which] = isChecked;
                }
            })
            .setPositiveButton("添加所选", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    int added = 0;
                    for (int i = 0; i < discovered.size(); i++) {
                        if (checked[i]) {
                            AcidModelStore.ModelEntry m = discovered.get(i);
                            if (draft.findModel(m.id) == null) {
                                draft.models.add(m.copy());
                                added++;
                            }
                        }
                    }
                    renderDraftModelsList(modelsListBox, draft);
                    Toast.makeText(getContext(), "已添加 " + added + " 个模型", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && findFocus() != null) {
                imm.hideSoftInputFromWindow(findFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }
}
