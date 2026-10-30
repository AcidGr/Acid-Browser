package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * AI Provider & Model Management Sheet built on {@link AcidBottomSheetView}'s multi-page stack.
 *
 * <p>Navigation stack:
 * <ol>
 *   <li>Root Page: Configured Providers List ({@code 模型配置}, top-right {@code [+ 添加供应商]})</li>
 *   <li>Sub Page 1: Add Provider ({@code ← 添加模型供应商}, segmented preset catalog / custom API)</li>
 *   <li>Sub Page 2: Provider Editor ({@code ← 编辑/配置 xxx}) — pressing Back returns accurately
 *       to whichever page launched it (Sub Page 1 or Root Page)!</li>
 * </ol>
 */
public class ModelProviderConfigSheet extends AcidBottomSheetView {

    public interface OnConfigChangeListener {
        void onProviderConfigChanged();
    }

    private final AcidModelStore mModelStore;
    private final OnConfigChangeListener mListener;
    private boolean mAddTabCustomMode = false;

    public ModelProviderConfigSheet(Context context, AcidModelStore modelStore, OnConfigChangeListener listener) {
        super(context);
        mModelStore = modelStore;
        mListener = listener;
    }

    public void showConfig() {
        if (mModelStore == null) return;
        setRootPage(buildProvidersListPage());
        show();
    }

    // =========================================================================
    // LEVEL 1: Configured Providers List Page
    // =========================================================================

    private SheetPage buildProvidersListPage() {
        return new SheetPage(
            "模型配置",
            AcidIcons.ICON_PLUS,
            "添加供应商",
            AcidTheme.accent(mIsDarkMode),
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mAddTabCustomMode = false;
                    pushPage(buildAddProviderPage());
                }
            },
            new PageRenderer() {
                @Override
                public void render(LinearLayout pinned, LinearLayout container) {
                    renderConfiguredProvidersList(container);
                }
            }
        );
    }

    private void renderConfiguredProvidersList(LinearLayout container) {
        if (mModelStore == null) return;
        final Context ctx = getContext();
        final List<AcidModelStore.ConfiguredProvider> providers = mModelStore.getProviders();

        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accent = AcidTheme.accent(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "已装载供应商 (" + providers.size() + ")"));

        for (int i = 0; i < providers.size(); i++) {
            final AcidModelStore.ConfiguredProvider p = providers.get(i);

            LinearLayout card = AcidWidgets.cardGroup(ctx, mIsDarkMode);
            int pad = AcidTheme.dp(ctx, 14);
            card.setPadding(pad, AcidTheme.dp(ctx, 12), pad, AcidTheme.dp(ctx, 12));

            // Row 1: Display Name (H4 = 14sp Bold) + Source Tag + API Key Status
            LinearLayout headRow = new LinearLayout(ctx);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(ctx);
            tvName.setText(p.displayName);
            tvName.setTextColor(textCol);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(tvName);

            int tagColor = p.isCustom ? AcidTheme.COLOR_PURPLE : blue;
            TextView tvTag = new TextView(ctx);
            tvTag.setText(p.isCustom ? "自定义" : "内置目录");
            tvTag.setTextColor(tagColor);
            tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            tvTag.setTypeface(Typeface.DEFAULT_BOLD);
            tvTag.setPadding(AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2));
            tvTag.setBackground(AcidTheme.pillBg(ctx, AcidTheme.softTint(tagColor, 28), 0, AcidTheme.RADIUS_BADGE));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tagLp.leftMargin = AcidTheme.dp(ctx, 6);
            headRow.addView(tvTag, tagLp);

            headRow.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

            boolean hasKey = p.hasApiKey();
            TextView tvKeyState = new TextView(ctx);
            tvKeyState.setText(hasKey ? "● 密钥已就绪" : "● 待填密钥");
            tvKeyState.setTextColor(hasKey ? accent : AcidTheme.COLOR_AMBER);
            tvKeyState.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            tvKeyState.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(tvKeyState);
            card.addView(headRow);

            // Row 2: Base URL
            TextView tvUrl = new TextView(ctx);
            tvUrl.setText("接口: " + (p.baseUrl.isEmpty() ? "(默认地址)" : p.baseUrl));
            tvUrl.setTextColor(mutedCol);
            tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvUrl.setSingleLine(true);
            tvUrl.setEllipsize(TextUtils.TruncateAt.END);
            tvUrl.setPadding(0, AcidTheme.dp(ctx, 4), 0, AcidTheme.dp(ctx, 2));
            card.addView(tvUrl);

            // Row 3: Loaded Models Summary
            StringBuilder sb = new StringBuilder("已装载 " + p.models.size() + " 个模型: ");
            for (int j = 0; j < p.models.size() && j < 4; j++) {
                if (j > 0) sb.append(", ");
                sb.append(p.models.get(j).name);
            }
            if (p.models.size() > 4) sb.append(" 等");
            TextView tvModels = new TextView(ctx);
            tvModels.setText(sb.toString());
            tvModels.setTextColor(blue);
            tvModels.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvModels.setSingleLine(true);
            tvModels.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(tvModels);

            // Row 4: Action Bar [测试连通性] [配置与模型] ... [删除]
            LinearLayout actionsBar = new LinearLayout(ctx);
            actionsBar.setOrientation(LinearLayout.HORIZONTAL);
            actionsBar.setGravity(Gravity.CENTER_VERTICAL);
            actionsBar.setPadding(0, AcidTheme.dp(ctx, 10), 0, 0);

            final TextView[] testRef = new TextView[1];
            TextView btnTest = AcidWidgets.miniButton(ctx, mIsDarkMode, "测试连通性", textCol, false, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    final TextView target = testRef[0];
                    if (target != null) {
                        target.setText("测试中...");
                        target.setEnabled(false);
                    }
                    AcidModelStore.discoverModelsAsync(p.baseUrl, p.apiKey, new AcidModelStore.ModelDiscoveryCallback() {
                        @Override
                        public void onModelsDiscovered(final List<AcidModelStore.ModelEntry> models) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    if (target != null) {
                                        target.setText("测试连通性");
                                        target.setEnabled(true);
                                    }
                                    Toast.makeText(ctx, "连接成功！服务端返回 " + models.size() + " 个可用模型", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }

                        @Override
                        public void onDiscoveryFailed(final String error) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    if (target != null) {
                                        target.setText("测试连通性");
                                        target.setEnabled(true);
                                    }
                                    Toast.makeText(ctx, "连接测试失败: " + error, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                }
            });
            testRef[0] = btnTest;
            actionsBar.addView(btnTest);

            TextView btnEdit = AcidWidgets.miniButton(ctx, mIsDarkMode, "配置与模型", blue, true, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pushPage(buildEditProviderPage(p, null));
                }
            });
            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            editLp.leftMargin = AcidTheme.dp(ctx, 8);
            actionsBar.addView(btnEdit, editLp);

            if (p.isCustom) {
                actionsBar.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
                TextView btnDel = AcidWidgets.miniButton(ctx, mIsDarkMode, "删除", AcidTheme.COLOR_RED, false, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmDeleteProvider(p);
                    }
                });
                actionsBar.addView(btnDel);
            }

            card.addView(actionsBar);
            container.addView(card);
        }

        TextView btnAddBottom = AcidWidgets.secondaryButton(
            ctx, mIsDarkMode, "+ 添加模型供应商 (内置目录 / 自定义 API)", blue,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mAddTabCustomMode = false;
                    pushPage(buildAddProviderPage());
                }
            }
        );
        container.addView(btnAddBottom, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void confirmDeleteProvider(final AcidModelStore.ConfiguredProvider p) {
        final Context ctx = getContext();
        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("删除供应商")
            .setMessage("确定删除自定义供应商「" + p.displayName + "」及其已配置的模型列表吗？")
            .setDangerButton("删除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    mModelStore.deleteProvider(p.id);
                    if (mListener != null) mListener.onProviderConfigChanged();
                    refreshActivePage();
                    Toast.makeText(ctx, "已删除供应商: " + p.displayName, Toast.LENGTH_SHORT).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // =========================================================================
    // LEVEL 2: Add Provider Page (Catalog vs. Custom API)
    // =========================================================================

    private SheetPage buildAddProviderPage() {
        return new SheetPage("添加模型供应商", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                List<AcidModelStore.CatalogProviderPreset> presets = AcidModelStore.getBuiltinCatalogPresets();

                pinned.addView(AcidWidgets.segmentedTabs(
                    ctx,
                    mIsDarkMode,
                    new String[]{"默认供应商目录 (" + presets.size() + ")", "自定义供应商 API"},
                    mAddTabCustomMode ? 1 : 0,
                    AcidTheme.COLOR_BLUE,
                    new AcidWidgets.OnTabSelectedListener() {
                        @Override
                        public void onTabSelected(int index) {
                            boolean nextCustom = (index == 1);
                            if (mAddTabCustomMode != nextCustom) {
                                mAddTabCustomMode = nextCustom;
                                refreshActivePage();
                            }
                        }
                    }
                ));

                container.addView(AcidWidgets.descriptionText(
                    ctx, mIsDarkMode,
                    !mAddTabCustomMode
                        ? "选择主流大模型厂商，填入 API 密钥即可一键装载默认推荐模型。"
                        : "接入第三方聚合网关、本地 Ollama / vLLM 或任意兼容 OpenAI Chat Completions 协议的服务。"
                ));

                if (!mAddTabCustomMode) {
                    renderBuiltinCatalogPresetsList(container, presets);
                } else {
                    AcidModelStore.ConfiguredProvider draftCustom = new AcidModelStore.ConfiguredProvider(
                        "", "", true, "", ""
                    );
                    renderProviderEditorForm(container, draftCustom, null);
                }
            }
        });
    }

    private void renderBuiltinCatalogPresetsList(
        LinearLayout container,
        List<AcidModelStore.CatalogProviderPreset> presets
    ) {
        Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        for (int i = 0; i < presets.size(); i++) {
            final AcidModelStore.CatalogProviderPreset preset = presets.get(i);
            final AcidModelStore.ConfiguredProvider existing = mModelStore.getProvider(preset.id);
            boolean isLoaded = (existing != null);

            LinearLayout row = AcidWidgets.listItemCard(ctx, mIsDarkMode, isLoaded, accent);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout textBox = new LinearLayout(ctx);
            textBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            tLp.rightMargin = AcidTheme.dp(ctx, 8);

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(preset.displayName);
            tvTitle.setTextColor(AcidTheme.text(mIsDarkMode));
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            textBox.addView(tvTitle);

            if (!TextUtils.isEmpty(preset.description)) {
                TextView tvSub = new TextView(ctx);
                tvSub.setText(preset.description);
                tvSub.setTextColor(AcidTheme.muted(mIsDarkMode));
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvSub.setSingleLine(true);
                tvSub.setEllipsize(TextUtils.TruncateAt.END);
                tvSub.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
                textBox.addView(tvSub);
            }
            row.addView(textBox, tLp);

            int badgeColor = isLoaded ? accent : blue;
            TextView tvAction = new TextView(ctx);
            tvAction.setText(isLoaded ? "已装载 · 编辑" : "+ 配置装载");
            tvAction.setTextColor(badgeColor);
            tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvAction.setTypeface(Typeface.DEFAULT_BOLD);
            tvAction.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 5), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 5));
            tvAction.setBackground(AcidTheme.pillBg(ctx, AcidTheme.softTint(badgeColor, 26), 0, AcidTheme.RADIUS_BADGE));
            row.addView(tvAction);

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pushPage(buildEditProviderPage(existing, preset));
                }
            });

            container.addView(row);
        }
    }

    // =========================================================================
    // LEVEL 3: Provider Editor Page
    // =========================================================================

    private SheetPage buildEditProviderPage(
        final AcidModelStore.ConfiguredProvider existing,
        final AcidModelStore.CatalogProviderPreset preset
    ) {
        String pageTitle = existing != null
            ? ("编辑 " + existing.displayName)
            : (preset != null ? ("配置 " + preset.displayName) : "创建自定义供应商");

        final AcidModelStore.ConfiguredProvider workingDraft;
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

        return new SheetPage(pageTitle, new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                renderProviderEditorForm(container, workingDraft, preset);
            }
        });
    }

    private void renderProviderEditorForm(
        LinearLayout container,
        final AcidModelStore.ConfiguredProvider draft,
        final AcidModelStore.CatalogProviderPreset preset
    ) {
        final Context ctx = getContext();
        final int textCol = AcidTheme.text(mIsDarkMode);
        final int mutedCol = AcidTheme.muted(mIsDarkMode);
        final int blue = AcidTheme.COLOR_BLUE;

        LinearLayout form = AcidWidgets.cardGroup(ctx, mIsDarkMode);
        int pad = AcidTheme.dp(ctx, 14);
        form.setPadding(pad, pad, pad, pad);

        // 1. Display Name
        final EditText etDisplayName;
        if (draft.isCustom) {
            form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "供应商显示名称 (必填)"));
            etDisplayName = AcidWidgets.inputField(
                ctx, mIsDarkMode, draft.displayName, "例如: 我的中转网关 / Ollama 本地服务", true
            );
            form.addView(etDisplayName);
        } else {
            etDisplayName = new EditText(ctx);
            etDisplayName.setText(draft.displayName);

            TextView tvFixed = new TextView(ctx);
            tvFixed.setText(draft.displayName + " (" + draft.id + ")");
            tvFixed.setTextColor(textCol);
            tvFixed.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H3_CARD);
            tvFixed.setTypeface(Typeface.DEFAULT_BOLD);
            tvFixed.setPadding(0, 0, 0, AcidTheme.dp(ctx, 10));
            form.addView(tvFixed);
        }

        // 2. API Key with Show/Hide toggle
        LinearLayout keyHead = new LinearLayout(ctx);
        keyHead.setOrientation(LinearLayout.HORIZONTAL);
        keyHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblKey = AcidWidgets.fieldLabel(ctx, mIsDarkMode, "API 密钥 (API Key)");
        keyHead.addView(lblKey, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView btnToggleKeyVis = new TextView(ctx);
        btnToggleKeyVis.setText("显示密钥");
        btnToggleKeyVis.setTextColor(blue);
        btnToggleKeyVis.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        btnToggleKeyVis.setTypeface(Typeface.DEFAULT_BOLD);
        btnToggleKeyVis.setPadding(AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2));
        keyHead.addView(btnToggleKeyVis);
        form.addView(keyHead);

        final EditText etApiKey = AcidWidgets.inputField(
            ctx, mIsDarkMode, draft.apiKey,
            preset != null ? ("输入 API Key (" + preset.envKeyHint + ")") : "输入 sk-... (本地免鉴权接口可留空)",
            true
        );
        etApiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        form.addView(etApiKey);

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
        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "API 接口地址 (OpenAI 兼容 Base URL)"));
        final EditText etBaseUrl = AcidWidgets.inputField(
            ctx, mIsDarkMode, draft.baseUrl,
            preset != null ? preset.defaultBaseUrl : "https://gateway.example.com/v1",
            true
        );
        form.addView(etBaseUrl);

        // 4. Models Section Header + Reset Action
        LinearLayout modelHead = new LinearLayout(ctx);
        modelHead.setOrientation(LinearLayout.HORIZONTAL);
        modelHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblModels = AcidWidgets.fieldLabel(ctx, mIsDarkMode, "装载的模型列表");
        modelHead.addView(lblModels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final LinearLayout modelsListBox = new LinearLayout(ctx);
        modelsListBox.setOrientation(LinearLayout.VERTICAL);

        if (preset != null) {
            TextView btnReset = new TextView(ctx);
            btnReset.setText("恢复默认模型");
            btnReset.setTextColor(blue);
            btnReset.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            btnReset.setTypeface(Typeface.DEFAULT_BOLD);
            btnReset.setPadding(AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 2));
            btnReset.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.models.clear();
                    for (AcidModelStore.ModelEntry m : preset.defaultModels) {
                        draft.models.add(m.copy());
                    }
                    renderDraftModelsList(modelsListBox, draft);
                    Toast.makeText(ctx, "已恢复预设模型目录", Toast.LENGTH_SHORT).show();
                }
            });
            modelHead.addView(btnReset);
        }
        form.addView(modelHead);

        // Discovery & Manual Add Toolbar
        form.addView(buildModelDiscoveryActionBar(draft, etBaseUrl, etApiKey, modelsListBox));
        form.addView(modelsListBox);
        renderDraftModelsList(modelsListBox, draft);

        // 5. Primary Save Button
        TextView btnSave = AcidWidgets.primaryButton(
            ctx, mIsDarkMode,
            draft.isCustom && draft.id.isEmpty() ? "创建并装载供应商" : "保存供应商配置",
            blue,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    AcidTheme.hideKeyboard(ModelProviderConfigSheet.this);
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
                    Toast.makeText(ctx, "已保存供应商: " + draft.displayName, Toast.LENGTH_SHORT).show();
                    setRootPage(buildProvidersListPage());
                }
            }
        );
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        saveLp.topMargin = AcidTheme.dp(ctx, 12);
        form.addView(btnSave, saveLp);

        container.addView(form);
    }

    private View buildModelDiscoveryActionBar(
        final AcidModelStore.ConfiguredProvider draft,
        final EditText etBaseUrl,
        final EditText etApiKey,
        final LinearLayout modelsListBox
    ) {
        final Context ctx = getContext();
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, AcidTheme.dp(ctx, 4), 0, AcidTheme.dp(ctx, 8));

        TextView btnAdd = AcidWidgets.miniButton(
            ctx, mIsDarkMode, "+ 手动添加", AcidTheme.text(mIsDarkMode), false,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showManualAddModelDialog(draft, modelsListBox);
                }
            }
        );
        bar.addView(btnAdd);

        final TextView[] syncRef = new TextView[1];
        TextView btnSync = AcidWidgets.miniButton(
            ctx, mIsDarkMode, "同步接口模型 (/models)", AcidTheme.COLOR_BLUE, false,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    String targetUrl = etBaseUrl.getText().toString().trim();
                    if (targetUrl.isEmpty()) targetUrl = draft.baseUrl;
                    String apiKey = etApiKey.getText().toString().trim();
                    if (targetUrl.isEmpty()) {
                        Toast.makeText(ctx, "请先填入有效的 API 接口地址", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    final TextView target = syncRef[0];
                    if (target != null) {
                        target.setText("获取中...");
                        target.setEnabled(false);
                    }
                    AcidModelStore.discoverModelsAsync(targetUrl, apiKey, new AcidModelStore.ModelDiscoveryCallback() {
                        @Override
                        public void onModelsDiscovered(final List<AcidModelStore.ModelEntry> models) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    if (target != null) {
                                        target.setText("同步接口模型 (/models)");
                                        target.setEnabled(true);
                                    }
                                    if (models.isEmpty()) {
                                        Toast.makeText(ctx, "接口未返回任何可用模型", Toast.LENGTH_SHORT).show();
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
                                    if (target != null) {
                                        target.setText("同步接口模型 (/models)");
                                        target.setEnabled(true);
                                    }
                                    Toast.makeText(ctx, "获取模型列表失败: " + error, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                }
            }
        );
        syncRef[0] = btnSync;
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sLp.leftMargin = AcidTheme.dp(ctx, 8);
        bar.addView(btnSync, sLp);

        return bar;
    }

    private void renderDraftModelsList(
        final LinearLayout modelsListBox,
        final AcidModelStore.ConfiguredProvider draft
    ) {
        modelsListBox.removeAllViews();
        Context ctx = getContext();
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        if (draft.models.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("当前暂无已装载模型，请点击上方「+ 手动添加」或从接口同步");
            empty.setTextColor(mutedCol);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
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
            int padH = AcidTheme.dp(ctx, 10);
            int padV = AcidTheme.dp(ctx, 8);
            row.setPadding(padH, padV, padH, padV);
            row.setBackground(AcidTheme.pillBg(
                ctx,
                AcidTheme.formInputBg(mIsDarkMode),
                AcidTheme.border(mIsDarkMode),
                AcidTheme.RADIUS_CONTROL
            ));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = AcidTheme.dp(ctx, 5);

            LinearLayout tBox = new LinearLayout(ctx);
            tBox.setOrientation(LinearLayout.VERTICAL);

            TextView tvName = new TextView(ctx);
            tvName.setText(m.name);
            tvName.setTextColor(textCol);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            tBox.addView(tvName);

            TextView tvId = new TextView(ctx);
            tvId.setText("ID: " + m.id);
            tvId.setTextColor(mutedCol);
            tvId.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            tBox.addView(tvId);
            row.addView(tBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout delBox = new LinearLayout(ctx);
            delBox.setGravity(Gravity.CENTER);
            int delSize = AcidTheme.dp(ctx, 28);
            delBox.setLayoutParams(new LinearLayout.LayoutParams(delSize, delSize));
            delBox.setBackground(AcidTheme.interactiveBg(
                ctx, Color.TRANSPARENT, AcidTheme.rowPressed(mIsDarkMode), 0, AcidTheme.RADIUS_BADGE
            ));
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CLOSE, mutedCol, 14);
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

    // =========================================================================
    // TIER 3: AcidDialog Modals
    // =========================================================================

    private void showManualAddModelDialog(
        final AcidModelStore.ConfiguredProvider draft,
        final LinearLayout modelsListBox
    ) {
        final Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "模型 ID (必填)"));
        final EditText etId = AcidWidgets.inputField(
            ctx, mIsDarkMode, "", "如: deepseek-chat / gpt-4o / qwen-max", true
        );
        box.addView(etId);

        box.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "显示名称 (选填，留空则同模型 ID)"));
        final EditText etName = AcidWidgets.inputField(
            ctx, mIsDarkMode, "", "如: DeepSeek V3", true
        );
        box.addView(etName);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("手动添加模型")
            .setCustomView(box)
            .setPositiveButton("添加", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String id = etId.getText().toString().trim();
                    if (id.isEmpty()) {
                        Toast.makeText(ctx, "模型 ID 不能为空", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = id;
                    if (draft.findModel(id) != null) {
                        Toast.makeText(ctx, "该模型 ID 已存在", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    draft.models.add(new AcidModelStore.ModelEntry(id, name, true));
                    renderDraftModelsList(modelsListBox, draft);
                    return true;
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
        final Context ctx = getContext();
        final String[] labels = new String[discovered.size()];
        final boolean[] checked = new boolean[discovered.size()];
        for (int i = 0; i < discovered.size(); i++) {
            AcidModelStore.ModelEntry m = discovered.get(i);
            labels[i] = (m.name != null && !m.name.equals(m.id)) ? (m.name + " (" + m.id + ")") : m.id;
            checked[i] = (draft.findModel(m.id) != null);
        }

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("选择要装载的模型 (共 " + discovered.size() + " 个)")
            .setMultiChoiceItems(labels, checked)
            .setPositiveButton("添加所选", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
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
                    Toast.makeText(ctx, "已添加 " + added + " 个模型", Toast.LENGTH_SHORT).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
