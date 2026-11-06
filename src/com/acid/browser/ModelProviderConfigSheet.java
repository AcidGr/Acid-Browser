package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * AI Provider & Model Management Sheet built on {@link AcidBottomSheetView}'s multi-page stack,
 * {@link AcidWidgets}, and {@link AcidDialogs}.
 *
 * <p>Fixes:
 * <ul>
 *   <li>Live {@code TextWatcher} synchronization onto {@code workingDraft} so switching Day/Night
 *       mode or syncing {@code /models} never discards unsaved API Key / Base URL / Display Name inputs.</li>
 *   <li>Dynamic accent color ({@code 0}) for the top-right {@code [+ 添加供应商]} action pill.</li>
 *   <li>Primary save action anchored in the pinned bottom footer slot.</li>
 * </ul>
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
        setRootPage(buildProvidersListPage());
    }

    @Override
    protected void onResetToRootPage() {
        mAddTabCustomMode = false;
        setRootPage(buildProvidersListPage());
    }

    // =========================================================================
    // LEVEL 1: Configured Providers List Page
    // =========================================================================

    private SheetPage buildProvidersListPage() {
        return new SheetPage("模型配置", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                renderConfiguredProvidersList(container);
            }
        }).withAction(AcidIcons.ICON_PLUS, "添加供应商", new OnClickListener() {
            @Override
            public void onClick(View v) {
                mAddTabCustomMode = false;
                pushPage(buildAddProviderPage());
            }
        });
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

            LinearLayout card = AcidWidgets.contentCard(ctx, mIsDarkMode);

            // Row 1: Display Name (SP_ITEM = 14sp Bold) + Source Tag + API Key Status
            LinearLayout headRow = new LinearLayout(ctx);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(ctx);
            tvName.setText(p.displayName);
            tvName.setTextColor(textCol);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_ITEM);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(tvName);

            int tagColor = p.isCustom ? AcidTheme.COLOR_PURPLE : blue;
            TextView tvTag = AcidWidgets.statusTag(ctx, mIsDarkMode, p.isCustom ? "自定义" : "内置目录", tagColor);
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tagLp.leftMargin = AcidTheme.dp(ctx, 6);
            headRow.addView(tvTag, tagLp);

            headRow.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

            boolean hasKey = p.hasApiKey();
            headRow.addView(AcidWidgets.statusTag(
                ctx, mIsDarkMode,
                hasKey ? "● 密钥已就绪" : "● 待填密钥",
                hasKey ? accent : AcidTheme.COLOR_AMBER
            ));
            card.addView(headRow);

            // Row 2: Base URL
            TextView tvUrl = new TextView(ctx);
            tvUrl.setText(AcidLocale.tr("接口: " + (p.baseUrl.isEmpty() ? "(默认地址)" : p.baseUrl)));
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
            tvModels.setText(AcidLocale.tr(sb.toString()));
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

            AcidWidgets.LoadingButton btnTest = AcidWidgets.LoadingButton.mini(
                ctx, mIsDarkMode, "测试连通性", "测试中...", textCol, false,
                new AcidWidgets.LoadingButton.OnAsyncActionListener() {
                    @Override
                    public void onTrigger(final AcidWidgets.LoadingButton button) {
                        button.setLoading();
                        AcidModelStore.discoverModelsAsync(p.baseUrl, p.apiKey, new AcidModelStore.ModelDiscoveryCallback() {
                            @Override
                            public void onModelsDiscovered(final List<AcidModelStore.ModelEntry> models) {
                                post(new Runnable() {
                                    @Override
                                    public void run() {
                                        button.reset();
                                        AcidFeedback.ok(ctx, "连接成功！服务端返回 " + models.size() + " 个可用模型");
                                    }
                                });
                            }

                            @Override
                            public void onDiscoveryFailed(final String error) {
                                post(new Runnable() {
                                    @Override
                                    public void run() {
                                        button.reset();
                                        AcidFeedback.error(ctx, "连接测试失败: " + error);
                                    }
                                });
                            }
                        });
                    }
                }
            );
            actionsBar.addView(btnTest.view);

            TextView btnEdit = AcidWidgets.miniButton(ctx, mIsDarkMode, "配置与模型", accent, true, new OnClickListener() {
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
    }

    private void confirmDeleteProvider(final AcidModelStore.ConfiguredProvider p) {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "删除供应商",
            "确定删除自定义供应商「" + p.displayName + "」及其已配置的模型列表吗？",
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    mModelStore.deleteProvider(p.id);
                    if (mListener != null) mListener.onProviderConfigChanged();
                    refreshActivePage();
                    AcidFeedback.info(ctx, "已删除供应商: " + p.displayName);
                }
            }
        );
    }

    // =========================================================================
    // LEVEL 2: Add Provider Page (Catalog vs. Custom API)
    // =========================================================================

    private SheetPage buildAddProviderPage() {
        final AcidModelStore.ConfiguredProvider customDraft = new AcidModelStore.ConfiguredProvider(
            "", "", true, "", ""
        );
        return new SheetPage("添加模型供应商", new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container, LinearLayout footer) {
                Context ctx = getContext();
                List<AcidModelStore.CatalogProviderPreset> presets = AcidModelStore.getBuiltinCatalogPresets();

                pinned.addView(AcidWidgets.segmentedTabs(
                    ctx,
                    mIsDarkMode,
                    new String[]{"默认供应商目录 (" + presets.size() + ")", "自定义供应商 API"},
                    mAddTabCustomMode ? 1 : 0,
                    AcidTheme.accent(mIsDarkMode),
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
                    renderProviderEditorForm(container, footer, customDraft, null);
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

            LinearLayout row = new AcidWidgets.ListRow()
                .title(preset.displayName)
                .subtitle(preset.description)
                .selected(isLoaded, accent)
                .trailingBadge(ctx, mIsDarkMode, isLoaded ? "已装载 · 编辑" : "+ 配置装载", isLoaded ? accent : blue)
                .onClick(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pushPage(buildEditProviderPage(existing, preset));
                    }
                })
                .build(ctx, mIsDarkMode);

            container.addView(row);
        }
    }

    // =========================================================================
    // LEVEL 3: Provider Editor Page (Live Draft Sync + Pinned Footer Save)
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

        return new SheetPage(pageTitle, new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container, LinearLayout footer) {
                renderProviderEditorForm(container, footer, workingDraft, preset);
            }
        });
    }

    private void renderProviderEditorForm(
        LinearLayout container,
        LinearLayout footer,
        final AcidModelStore.ConfiguredProvider draft,
        final AcidModelStore.CatalogProviderPreset preset
    ) {
        final Context ctx = getContext();
        final int textCol = AcidTheme.text(mIsDarkMode);
        final int accent = AcidTheme.accent(mIsDarkMode);
        final int blue = AcidTheme.COLOR_BLUE;

        LinearLayout form = AcidWidgets.contentCard(ctx, mIsDarkMode);

        // 1. Display Name (live-synced to draft)
        if (draft.isCustom) {
            form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "供应商显示名称 (必填)"));
            final EditText etDisplayName = AcidWidgets.inputField(
                ctx, mIsDarkMode, draft.displayName, "例如: 我的中转网关 / Ollama 本地服务", true
            );
            etDisplayName.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    draft.displayName = s != null ? s.toString().trim() : "";
                }
            });
            form.addView(etDisplayName);
        } else {
            TextView tvFixed = new TextView(ctx);
            tvFixed.setText(draft.displayName + " (" + draft.id + ")");
            tvFixed.setTextColor(textCol);
            tvFixed.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
            tvFixed.setTypeface(Typeface.DEFAULT_BOLD);
            tvFixed.setPadding(0, 0, 0, AcidTheme.dp(ctx, 10));
            form.addView(tvFixed);
        }

        // 2. API Key with Show/Hide interactive textAction (live-synced to draft)
        final EditText etApiKey = AcidWidgets.inputField(
            ctx, mIsDarkMode, draft.apiKey,
            preset != null ? ("输入 API Key (" + preset.envKeyHint + ")") : "输入 sk-... (本地免鉴权接口可留空)",
            true
        );
        etApiKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
        etApiKey.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                draft.apiKey = s != null ? s.toString().trim() : "";
            }
        });

        final TextView[] toggleBtnRef = new TextView[1];
        TextView btnToggleKeyVis = AcidWidgets.textAction(ctx, mIsDarkMode, "显示密钥", blue, new OnClickListener() {
            private boolean revealed = false;
            @Override
            public void onClick(View v) {
                revealed = !revealed;
                etApiKey.setTransformationMethod(revealed
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
                if (toggleBtnRef[0] != null) {
                    toggleBtnRef[0].setText(AcidLocale.tr(revealed ? "隐藏密钥" : "显示密钥"));
                }
                etApiKey.setSelection(etApiKey.getText().length());
            }
        });
        toggleBtnRef[0] = btnToggleKeyVis;

        form.addView(AcidWidgets.fieldLabelWithAction(ctx, mIsDarkMode, "API 密钥 (API Key)", btnToggleKeyVis));
        form.addView(etApiKey);

        // 3. Base URL (live-synced to draft)
        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "API 接口地址 (OpenAI 兼容 Base URL)"));
        final EditText etBaseUrl = AcidWidgets.inputField(
            ctx, mIsDarkMode, draft.baseUrl,
            preset != null ? preset.defaultBaseUrl : "https://gateway.example.com/v1",
            true
        );
        etBaseUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                draft.baseUrl = s != null ? s.toString().trim() : "";
            }
        });
        form.addView(etBaseUrl);

        // 4. Models Section Header + Reset Action
        final LinearLayout modelsListBox = new LinearLayout(ctx);
        modelsListBox.setOrientation(LinearLayout.VERTICAL);

        TextView btnReset = null;
        if (preset != null) {
            btnReset = AcidWidgets.textAction(ctx, mIsDarkMode, "恢复默认模型", blue, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.models.clear();
                    for (AcidModelStore.ModelEntry m : preset.defaultModels) {
                        draft.models.add(m.copy());
                    }
                    renderDraftModelsList(modelsListBox, draft);
                    AcidFeedback.info(ctx, "已恢复预设模型目录");
                }
            });
        }
        form.addView(AcidWidgets.fieldLabelWithAction(ctx, mIsDarkMode, "装载的模型列表", btnReset));

        form.addView(buildModelDiscoveryActionBar(draft, modelsListBox));
        form.addView(modelsListBox);
        renderDraftModelsList(modelsListBox, draft);

        container.addView(form);

        // 5. Pinned Footer Save CTA
        TextView btnSave = AcidWidgets.primaryButton(
            ctx, mIsDarkMode,
            draft.isCustom && draft.id.isEmpty() ? "创建并装载供应商" : "保存供应商配置",
            accent,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    AcidTheme.hideKeyboard(ModelProviderConfigSheet.this);
                    if (draft.isCustom) {
                        if (draft.displayName.isEmpty()) {
                            AcidFeedback.warn(ctx, "供应商名称不能为空");
                            return;
                        }
                        if (draft.id.isEmpty()) {
                            draft.id = "custom_" + System.currentTimeMillis();
                        }
                    }
                    if (draft.baseUrl.isEmpty() && preset != null) {
                        draft.baseUrl = preset.defaultBaseUrl;
                    }
                    if (draft.models.isEmpty()) {
                        AcidFeedback.warn(ctx, "请至少为此供应商添加或保留一个可用模型");
                        return;
                    }

                    mModelStore.upsertProvider(draft);
                    if (mListener != null) {
                        mListener.onProviderConfigChanged();
                    }
                    AcidFeedback.ok(ctx, "已保存供应商: " + draft.displayName);
                    setRootPage(buildProvidersListPage());
                }
            }
        );
        footer.addView(btnSave, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private View buildModelDiscoveryActionBar(
        final AcidModelStore.ConfiguredProvider draft,
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

        AcidWidgets.LoadingButton btnSync = AcidWidgets.LoadingButton.mini(
            ctx, mIsDarkMode, "同步接口模型 (/models)", "获取中...", AcidTheme.COLOR_BLUE, false,
            new AcidWidgets.LoadingButton.OnAsyncActionListener() {
                @Override
                public void onTrigger(final AcidWidgets.LoadingButton button) {
                    if (draft.baseUrl.isEmpty()) {
                        AcidFeedback.warn(ctx, "请先填入有效的 API 接口地址");
                        return;
                    }
                    button.setLoading();
                    AcidModelStore.discoverModelsAsync(draft.baseUrl, draft.apiKey, new AcidModelStore.ModelDiscoveryCallback() {
                        @Override
                        public void onModelsDiscovered(final List<AcidModelStore.ModelEntry> models) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    button.reset();
                                    if (models.isEmpty()) {
                                        AcidFeedback.warn(ctx, "接口未返回任何可用模型");
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
                                    button.reset();
                                    AcidFeedback.error(ctx, "获取模型列表失败: " + error);
                                }
                            });
                        }
                    });
                }
            }
        );
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sLp.leftMargin = AcidTheme.dp(ctx, 8);
        bar.addView(btnSync.view, sLp);

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
            empty.setText(AcidLocale.tr("当前暂无已装载模型，请点击上方「+ 手动添加」或从接口同步"));
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
            int padV = AcidTheme.dp(ctx, 7);
            row.setPadding(padH, padV, AcidTheme.dp(ctx, 6), padV);
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

            row.addView(AcidWidgets.iconAction(ctx, mIsDarkMode, AcidIcons.ICON_CLOSE, mutedCol, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    draft.models.remove(index);
                    renderDraftModelsList(modelsListBox, draft);
                }
            }));

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
                        AcidFeedback.warn(ctx, "模型 ID 不能为空");
                        return false;
                    }
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) name = id;
                    if (draft.findModel(id) != null) {
                        AcidFeedback.warn(ctx, "该模型 ID 已存在");
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

        AcidDialogs.chooseMulti(
            ctx, mIsDarkMode,
            "选择要装载的模型 (共 " + discovered.size() + " 个)",
            labels, checked,
            "添加所选", false,
            new AcidDialogs.MultiChoiceCallback() {
                @Override
                public boolean onConfirmed(boolean[] sel) {
                    int added = 0;
                    for (int i = 0; i < discovered.size(); i++) {
                        if (sel[i]) {
                            AcidModelStore.ModelEntry m = discovered.get(i);
                            if (draft.findModel(m.id) == null) {
                                draft.models.add(m.copy());
                                added++;
                            }
                        }
                    }
                    renderDraftModelsList(modelsListBox, draft);
                    AcidFeedback.ok(ctx, "已添加 " + added + " 个模型");
                    return true;
                }
            }
        );
    }
}
