package com.acid.browser;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Hierarchical Settings Modal Sheet built declaratively on {@link AcidBottomSheetView}'s
 * {@link SheetPage} stack, {@link AcidSettings} registry, {@link AcidWidgets#groupCard},
 * and {@link AcidDialogs}.
 */
public class BrowserSettingsSheet extends AcidBottomSheetView {

    public interface OnSettingsActionListener {
        void onOpenModelConfig();
        void onOpenMcp();
        void onOpenAgentFloatingWindow();
        void onOpenAdBlock();
        void onOpenUserscripts();
        void onOpenSkills();
        void onOpenAbout();
        void onClearDataRequested(boolean clearCache, boolean clearCookies, boolean clearHistory, boolean clearStorage, boolean clearWorkspace);
        void onClearWorkspaceOnlyRequested();
        void onHomeUrlChanged(int type, String customUrl);
        void onWebDebuggingChanged(boolean enabled);
        void onDoNotTrackChanged(boolean enabled);
        void onBlockImagesChanged(boolean enabled);
        void onLanguageChanged(int langIndex);
        void onWebTextZoomChanged(int zoom);
        void onImportBookmarksRequested();
        void onExportBookmarksRequested();
        void onTestWebDavRequested(WebDavClient.WebDavConfig config, BrowserBackupManager.BackupResultCallback callback);
        void onWebDavUploadRequested(BrowserBackupManager.BackupResultCallback callback);
        void onWebDavRestoreRequested(boolean overwriteMode, BrowserBackupManager.BackupResultCallback callback);
        void onExportLocalBackupZipRequested(BrowserBackupManager.BackupResultCallback callback);
        void onImportLocalBackupZipRequested(boolean overwriteMode);
    }

    private final BrowserDataStore mDataStore;
    private final OnSettingsActionListener mListener;
    private boolean mIsMcpRunning = false;

    public BrowserSettingsSheet(Context context, BrowserDataStore dataStore, OnSettingsActionListener listener) {
        super(context);
        mDataStore = dataStore;
        mListener = listener;
        setRootPage(buildMainPage());
    }

    public boolean isDarkMode() {
        return mIsDarkMode;
    }

    public void setMcpRunning(boolean running) {
        mIsMcpRunning = running;
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildMainPage());
    }

    public void refresh() {
        if (isShowing()) {
            refreshActivePage();
        }
    }

    // =========================================================================
    // LEVEL 1: 设置总览 (Root Page)
    // =========================================================================

    private SheetPage buildMainPage() {
        return new SheetPage("设置", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                int blue = AcidTheme.COLOR_BLUE;
                int amber = AcidTheme.COLOR_AMBER;
                boolean adBlockOn = mDataStore.get(AcidSettings.ADBLOCK_ENABLED).booleanValue();

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "浏览与智能体"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SETTINGS, accent,
                        "通用与搜索", getSearchEngineLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildGeneralPage()); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, blue,
                        "AI 与自动化", mIsMcpRunning ? "MCP 运行中" : null,
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildAiPage()); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_ADBLOCK, accent,
                        "防护与扩展", adBlockOn ? "已开启" : "已暂停",
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildProtectionPage()); }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "系统与关于"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_RELOAD, accent,
                        "同步与备份", null,
                        new OnClickListener() {
                            @Override public void onClick(View v) {
                                pushPage(SyncBackupPageBuilder.buildSyncHomePage(BrowserSettingsSheet.this, mListener));
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, amber,
                        "隐私与高级", null,
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildAdvancedPage()); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_INFO, AcidTheme.muted(mIsDarkMode),
                        "关于 Acid Browser", "v" + AcidTheme.appVersionName(ctx),
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenAbout();
                            }
                        }
                    )
                ));
            }
        });
    }

    // =========================================================================
    // LEVEL 2: 二级设置详情页
    // =========================================================================

    private SheetPage buildGeneralPage() {
        return new SheetPage("通用与搜索", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "地址栏与启动"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_GLOBE, accent,
                        AcidSettings.LANGUAGE.title, getLanguageLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { showLanguageDialog(); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SEARCH, accent,
                        AcidSettings.SEARCH_ENGINE.title, getSearchEngineLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { showSearchEngineDialog(); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_HOME, accent,
                        AcidSettings.HOME_TYPE.title, getHomeTypeLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { showHomepageDialog(); }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_DOWNLOAD, accent,
                        AcidSettings.DOWNLOADER_ENGINE.title, getDownloaderEngineLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { showDownloaderEngineDialog(); }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "显示与排版"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TEXT_SIZE, accent,
                        AcidSettings.WEB_TEXT_ZOOM.title, getWebTextZoomLabel(),
                        new OnClickListener() {
                            @Override public void onClick(View v) { pushPage(buildWebTextZoomPage()); }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "浏览数据管理"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.dangerRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TRASH, "清除浏览数据",
                        new OnClickListener() {
                            @Override public void onClick(View v) { showClearDataDialog(); }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, accent,
                        AcidSettings.CLEAR_ON_EXIT, mDataStore, null, null
                    )
                ));
            }
        });
    }

    private SheetPage buildAiPage() {
        return new SheetPage("AI 与自动化", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                int blue = AcidTheme.COLOR_BLUE;

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "智能体与模型"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_MODEL, blue,
                        "模型配置", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenModelConfig();
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, accent,
                        "打开 Agent 对话窗", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                closeEntireModalFlow();
                                if (mListener != null) mListener.onOpenAgentFloatingWindow();
                            }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "远程网关与沙盒存储"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TERMINAL, accent,
                        "MCP 服务", null,
                        mIsMcpRunning ? "运行中" : "已停止", true,
                        new OnClickListener() {
                            @Override public void onClick(View v) {
                                if (mListener != null) mListener.onOpenMcp();
                            }
                        }
                    ),
                    AcidWidgets.dangerRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TRASH, "清理 AI 工作区文件",
                        new OnClickListener() {
                            @Override public void onClick(View v) { showClearWorkspaceConfirmDialog(); }
                        }
                    )
                ));
            }
        });
    }

    private SheetPage buildProtectionPage() {
        return new SheetPage("防护与扩展", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                boolean abEnabled = mDataStore.get(AcidSettings.ADBLOCK_ENABLED).booleanValue();
                int blockedCount = AdBlockManager.getInstance(ctx).getBlockedCount();

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "内容净化与脚本引擎"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_ADBLOCK, accent,
                        "广告拦截",
                        abEnabled ? ("已拦截 " + blockedCount + " 次") : "已暂停",
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenAdBlock();
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_FILE, AcidTheme.COLOR_BLUE,
                        "脚本管理", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenUserscripts();
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_AGENT, AcidTheme.accent(mIsDarkMode),
                        "技能管理 (Skills)", null,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mListener != null) mListener.onOpenSkills();
                            }
                        }
                    )
                ));
            }
        });
    }

    private SheetPage buildAdvancedPage() {
        return new SheetPage("隐私与高级", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                String filePath = mDataStore.getSettingsJsonFile() != null
                    ? mDataStore.getSettingsJsonFile().getName()
                    : "settings.json";

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "隐私与省流"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_INCOGNITO, accent,
                        AcidSettings.DO_NOT_TRACK.title, null,
                        mDataStore.isDoNotTrackEnabled(),
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                mDataStore.setDoNotTrackEnabled(isChecked);
                                if (mListener != null) mListener.onDoNotTrackChanged(isChecked);
                            }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_FILE_IMAGE, AcidTheme.COLOR_AMBER,
                        AcidSettings.BLOCK_IMAGES.title, null,
                        mDataStore.isBlockImagesEnabled(),
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                mDataStore.setBlockImagesEnabled(isChecked);
                                if (mListener != null) mListener.onBlockImagesChanged(isChecked);
                            }
                        }
                    )
                ));

                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "开发者与配置同步"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.switchRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_TERMINAL, AcidTheme.COLOR_BLUE,
                        AcidSettings.WEB_DEBUGGING.title, null,
                        mDataStore.isWebDebuggingEnabled(),
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                mDataStore.setWebDebuggingEnabled(isChecked);
                                if (mListener != null) mListener.onWebDebuggingChanged(isChecked);
                            }
                        }
                    ),
                    AcidWidgets.valueRow(
                        ctx, mIsDarkMode, AcidIcons.ICON_FILE, AcidTheme.COLOR_BLUE,
                        "配置文件", filePath,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                AcidFeedback.info(ctx, "配置文件: settings.json");
                            }
                        }
                    )
                ));
            }
        });
    }

    private String getSearchEngineLabel() {
        return AcidSettings.SEARCH_ENGINE.indexToLabel(mDataStore.getSearchEngineIndex());
    }

    private String getHomeTypeLabel() {
        return AcidSettings.HOME_TYPE.indexToLabel(mDataStore.getHomeType());
    }

    private String getDownloaderEngineLabel() {
        return AcidSettings.DOWNLOADER_ENGINE.indexToLabel(mDataStore.getDownloaderEngine());
    }

    private String getLanguageLabel() {
        return AcidSettings.LANGUAGE.indexToLabel(mDataStore.getLanguageIndex());
    }

    private String getWebTextZoomLabel() {
        return formatZoomLabel(mDataStore.getWebTextZoom());
    }

    private static String formatZoomLabel(int zoom) {
        return zoom == AcidSettings.WEB_TEXT_ZOOM_DEFAULT ? (zoom + "% (标准)") : (zoom + "%");
    }

    private static int clampZoom(int raw) {
        return AcidSettings.WEB_TEXT_ZOOM.sanitize(Integer.valueOf(raw)).intValue();
    }

    private static int snapZoomToStep(int raw) {
        int clamped = clampZoom(raw);
        int offset = clamped - AcidSettings.WEB_TEXT_ZOOM_MIN;
        int steps = Math.round(offset / (float) AcidSettings.WEB_TEXT_ZOOM_STEP);
        return clampZoom(AcidSettings.WEB_TEXT_ZOOM_MIN + steps * AcidSettings.WEB_TEXT_ZOOM_STEP);
    }

    private static void tintSeekBar(SeekBar sb, boolean dark) {
        int accent = AcidTheme.accent(dark);
        int track = AcidTheme.border(dark);
        if (Build.VERSION.SDK_INT >= 21) {
            sb.setSplitTrack(false);
        }
        Drawable progressDrawable = sb.getProgressDrawable();
        if (progressDrawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) progressDrawable;
            Drawable bg = layers.findDrawableByLayerId(android.R.id.background);
            if (bg != null) {
                bg.setColorFilter(track, PorterDuff.Mode.SRC_IN);
            }
            Drawable sec = layers.findDrawableByLayerId(android.R.id.secondaryProgress);
            if (sec != null) {
                sec.setColorFilter(track, PorterDuff.Mode.SRC_IN);
            }
            Drawable fg = layers.findDrawableByLayerId(android.R.id.progress);
            if (fg != null) {
                fg.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            }
        } else if (progressDrawable != null) {
            progressDrawable.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        }
        Drawable thumb = sb.getThumb();
        if (thumb != null) {
            thumb.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        }
    }

    private static void styleZoomPresetChip(Context ctx, boolean dark, TextView chip, boolean selected) {
        int accent = AcidTheme.accent(dark);
        if (selected) {
            chip.setBackground(AcidTheme.interactiveBg(
                ctx, accent, AcidTheme.adjustPressFill(accent, dark), 0, AcidTheme.RADIUS_PILL
            ));
            chip.setTextColor(AcidTheme.onColor(accent));
        } else {
            chip.setBackground(AcidTheme.interactiveBg(
                ctx, AcidTheme.menuItemBg(dark), AcidTheme.rowPressed(dark), AcidTheme.border(dark), AcidTheme.RADIUS_PILL
            ));
            chip.setTextColor(AcidTheme.muted(dark));
        }
    }

    // =========================================================================
    // LEVEL 3: 网页字体大小调节子页 (SeekBar + 实时预览)
    // =========================================================================

    private SheetPage buildWebTextZoomPage() {
        final int[] pendingZoom = new int[]{clampZoom(mDataStore.getWebTextZoom())};
        final int[] committedZoom = new int[]{pendingZoom[0]};

        final Runnable commitPending = new Runnable() {
            @Override
            public void run() {
                int target = clampZoom(pendingZoom[0]);
                if (target != committedZoom[0] || target != mDataStore.getWebTextZoom()) {
                    committedZoom[0] = target;
                    mDataStore.setWebTextZoom(target);
                    if (mListener != null) {
                        mListener.onWebTextZoomChanged(target);
                    }
                }
            }
        };

        return new SheetPage(AcidSettings.WEB_TEXT_ZOOM.title, new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinnedHeader, LinearLayout container, LinearLayout pinnedFooter) {
                final Context ctx = getContext();
                final boolean dark = mIsDarkMode;
                final int accent = AcidTheme.accent(dark);
                final int[] presets = new int[]{85, 100, 115, 130, 150};

                // 1. Live Preview Card
                LinearLayout previewCard = AcidWidgets.contentCard(ctx, dark);
                LinearLayout previewHeader = new LinearLayout(ctx);
                previewHeader.setOrientation(LinearLayout.HORIZONTAL);
                previewHeader.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                phLp.bottomMargin = AcidTheme.dp(ctx, 10);

                TextView tvPreviewLabel = new TextView(ctx);
                tvPreviewLabel.setText(AcidLocale.tr("实时效果预览"));
                tvPreviewLabel.setTextColor(AcidTheme.muted(dark));
                tvPreviewLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
                tvPreviewLabel.setTypeface(Typeface.DEFAULT_BOLD);
                previewHeader.addView(tvPreviewLabel, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ));

                final TextView tvPreviewTag = AcidWidgets.statusTag(ctx, dark, formatZoomLabel(pendingZoom[0]), accent);
                previewHeader.addView(tvPreviewTag);
                previewCard.addView(previewHeader, phLp);

                LinearLayout sampleBox = new LinearLayout(ctx);
                sampleBox.setOrientation(LinearLayout.VERTICAL);
                int boxPadH = AcidTheme.dp(ctx, 14);
                int boxPadV = AcidTheme.dp(ctx, 12);
                sampleBox.setPadding(boxPadH, boxPadV, boxPadH, boxPadV);
                sampleBox.setBackground(AcidTheme.cardBorderBg(
                    ctx, AcidTheme.formInputBg(dark), AcidTheme.border(dark), 1f, AcidTheme.RADIUS_CONTROL
                ));

                final TextView tvSampleTitle = new TextView(ctx);
                tvSampleTitle.setText(AcidLocale.tr("Acid Browser · 网页排版预览"));
                tvSampleTitle.setTextColor(AcidTheme.text(dark));
                tvSampleTitle.setTypeface(Typeface.DEFAULT_BOLD);
                sampleBox.addView(tvSampleTitle);

                final TextView tvSampleBody = new TextView(ctx);
                tvSampleBody.setText(AcidLocale.tr("拖动滑块实时调整全局网页字号。"));
                tvSampleBody.setTextColor(AcidTheme.icon(dark));
                LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                sbLp.topMargin = AcidTheme.dp(ctx, 6);
                sampleBox.addView(tvSampleBody, sbLp);

                final TextView tvSampleMeta = new TextView(ctx);
                tvSampleMeta.setText(AcidLocale.tr("发布于今天 · 阅读约 3 分钟 · 评论 12"));
                tvSampleMeta.setTextColor(AcidTheme.muted(dark));
                LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                smLp.topMargin = AcidTheme.dp(ctx, 8);
                sampleBox.addView(tvSampleMeta, smLp);

                previewCard.addView(sampleBox);
                container.addView(previewCard);

                // 2. Slider & Preset Controls Card
                container.addView(AcidWidgets.sectionHeader(ctx, dark, "字号调节"));
                LinearLayout controlCard = AcidWidgets.contentCard(ctx, dark);

                LinearLayout valRow = new LinearLayout(ctx);
                valRow.setOrientation(LinearLayout.HORIZONTAL);
                valRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams vrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                vrLp.bottomMargin = AcidTheme.dp(ctx, 10);

                TextView tvScaleTitle = new TextView(ctx);
                tvScaleTitle.setText(AcidLocale.tr("全局网页缩放"));
                tvScaleTitle.setTextColor(AcidTheme.text(dark));
                tvScaleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_ITEM);
                tvScaleTitle.setTypeface(Typeface.DEFAULT_BOLD);
                valRow.addView(tvScaleTitle, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ));

                final TextView tvScaleReadout = new TextView(ctx);
                tvScaleReadout.setTextColor(accent);
                tvScaleReadout.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
                tvScaleReadout.setTypeface(Typeface.DEFAULT_BOLD);
                valRow.addView(tvScaleReadout);
                controlCard.addView(valRow, vrLp);

                LinearLayout sliderRow = new LinearLayout(ctx);
                sliderRow.setOrientation(LinearLayout.HORIZONTAL);
                sliderRow.setGravity(Gravity.CENTER_VERTICAL);

                final TextView btnDec = AcidWidgets.miniButton(ctx, dark, "A-", 0, false, null);
                btnDec.setMinWidth(AcidTheme.dp(ctx, 38));
                sliderRow.addView(btnDec);

                final SeekBar seekBar = new SeekBar(ctx);
                final int maxSteps = (AcidSettings.WEB_TEXT_ZOOM_MAX - AcidSettings.WEB_TEXT_ZOOM_MIN) / AcidSettings.WEB_TEXT_ZOOM_STEP;
                seekBar.setMax(maxSteps);
                tintSeekBar(seekBar, dark);
                LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                );
                skLp.leftMargin = AcidTheme.dp(ctx, 6);
                skLp.rightMargin = AcidTheme.dp(ctx, 6);
                sliderRow.addView(seekBar, skLp);

                final TextView btnInc = AcidWidgets.miniButton(ctx, dark, "A+", 0, false, null);
                btnInc.setMinWidth(AcidTheme.dp(ctx, 38));
                sliderRow.addView(btnInc);
                controlCard.addView(sliderRow);

                // Scale ticks beneath slider
                LinearLayout tickRow = new LinearLayout(ctx);
                tickRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                trLp.topMargin = AcidTheme.dp(ctx, 4);
                trLp.bottomMargin = AcidTheme.dp(ctx, 12);

                String[] tickLabels = new String[]{
                    AcidSettings.WEB_TEXT_ZOOM_MIN + "%",
                    "100% 标准",
                    AcidSettings.WEB_TEXT_ZOOM_MAX + "%"
                };
                int[] tickGravities = new int[]{Gravity.START, Gravity.CENTER_HORIZONTAL, Gravity.END};
                for (int i = 0; i < tickLabels.length; i++) {
                    TextView tvTick = new TextView(ctx);
                    tvTick.setText(AcidLocale.tr(tickLabels[i]));
                    tvTick.setTextColor(AcidTheme.muted(dark));
                    tvTick.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
                    tvTick.setGravity(tickGravities[i]);
                    tickRow.addView(tvTick, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ));
                }
                controlCard.addView(tickRow, trLp);

                // Quick Preset Pills
                LinearLayout presetRow = new LinearLayout(ctx);
                presetRow.setOrientation(LinearLayout.HORIZONTAL);
                final TextView[] presetChips = new TextView[presets.length];
                for (int i = 0; i < presets.length; i++) {
                    TextView chip = new TextView(ctx);
                    chip.setText(presets[i] + "%");
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                    chip.setTypeface(Typeface.DEFAULT_BOLD);
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(0, AcidTheme.dp(ctx, 6), 0, AcidTheme.dp(ctx, 6));
                    chip.setClickable(true);
                    chip.setFocusable(true);
                    presetChips[i] = chip;
                    LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    );
                    if (i > 0) pcLp.leftMargin = AcidTheme.dp(ctx, 5);
                    presetRow.addView(chip, pcLp);
                }
                controlCard.addView(presetRow);
                container.addView(controlCard);

                final boolean[] suppressSeek = new boolean[]{false};
                final Runnable renderZoomVisuals = new Runnable() {
                    @Override
                    public void run() {
                        int z = pendingZoom[0];
                        float scale = z / 100f;
                        tvPreviewTag.setText(AcidLocale.tr(formatZoomLabel(z)));
                        tvScaleReadout.setText(z + "%");

                        tvSampleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f * scale);
                        tvSampleBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale);
                        tvSampleBody.setLineSpacing(AcidTheme.dp(ctx, 4f * scale), 1f);
                        tvSampleMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale);

                        boolean canDec = z > AcidSettings.WEB_TEXT_ZOOM_MIN;
                        boolean canInc = z < AcidSettings.WEB_TEXT_ZOOM_MAX;
                        btnDec.setEnabled(canDec);
                        btnDec.setAlpha(canDec ? 1f : 0.4f);
                        btnInc.setEnabled(canInc);
                        btnInc.setAlpha(canInc ? 1f : 0.4f);

                        for (int i = 0; i < presets.length; i++) {
                            styleZoomPresetChip(ctx, dark, presetChips[i], z == presets[i]);
                        }
                    }
                };

                final AcidSettings.KeyObserver<Integer> applyTargetZoom = new AcidSettings.KeyObserver<Integer>() {
                    @Override
                    public void onSettingChanged(Integer newZoom) {
                        int snapped = snapZoomToStep(newZoom.intValue());
                        pendingZoom[0] = snapped;
                        suppressSeek[0] = true;
                        seekBar.setProgress((snapped - AcidSettings.WEB_TEXT_ZOOM_MIN) / AcidSettings.WEB_TEXT_ZOOM_STEP);
                        suppressSeek[0] = false;
                        renderZoomVisuals.run();
                        commitPending.run();
                    }
                };

                // Initial bind without snapping away an arbitrary custom value from settings.json
                suppressSeek[0] = true;
                int initProgress = (snapZoomToStep(pendingZoom[0]) - AcidSettings.WEB_TEXT_ZOOM_MIN) / AcidSettings.WEB_TEXT_ZOOM_STEP;
                seekBar.setProgress(initProgress);
                suppressSeek[0] = false;
                renderZoomVisuals.run();

                seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                        if (suppressSeek[0] || !fromUser) return;
                        pendingZoom[0] = clampZoom(AcidSettings.WEB_TEXT_ZOOM_MIN + progress * AcidSettings.WEB_TEXT_ZOOM_STEP);
                        renderZoomVisuals.run();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                        commitPending.run();
                    }
                });

                btnDec.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyTargetZoom.onSettingChanged(Integer.valueOf(pendingZoom[0] - AcidSettings.WEB_TEXT_ZOOM_STEP));
                    }
                });
                btnInc.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyTargetZoom.onSettingChanged(Integer.valueOf(pendingZoom[0] + AcidSettings.WEB_TEXT_ZOOM_STEP));
                    }
                });
                for (int i = 0; i < presets.length; i++) {
                    final int presetVal = presets[i];
                    presetChips[i].setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            applyTargetZoom.onSettingChanged(Integer.valueOf(presetVal));
                        }
                    });
                }

                // 3. Pinned Footer CTA Row
                TextView btnReset = AcidWidgets.secondaryButton(ctx, dark, "恢复默认 (100%)", 0, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyTargetZoom.onSettingChanged(Integer.valueOf(AcidSettings.WEB_TEXT_ZOOM_DEFAULT));
                        AcidFeedback.ok(ctx, "已恢复默认网页字号 (100%)");
                    }
                });
                TextView btnDone = AcidWidgets.primaryButton(ctx, dark, "完成", 0, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        commitPending.run();
                        popPage();
                    }
                });
                pinnedFooter.addView(AcidWidgets.buttonRow(ctx, btnReset, btnDone));
            }
        }).withOnPause(commitPending);
    }

    private void showLanguageDialog() {
        final Context ctx = getContext();
        final String[] items = AcidSettings.LANGUAGE.displayLabels;
        AcidDialogs.chooseSingle(
            ctx, mIsDarkMode, "选择界面语言",
            items, mDataStore.getLanguageIndex(),
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int which) {
                    mDataStore.setLanguageIndex(which);
                    AcidLocale.applyLanguageIndex(ctx, which);
                    if (mListener != null) {
                        mListener.onLanguageChanged(which);
                    } else {
                        refreshActivePage();
                    }
                    AcidFeedback.ok(ctx, "界面语言已切换为: " + items[which]);
                }
            }
        );
    }

    private void showDownloaderEngineDialog() {
        final Context ctx = getContext();
        final String[] items = AcidSettings.DOWNLOADER_ENGINE.displayLabels;
        AcidDialogs.chooseSingle(
            ctx, mIsDarkMode, "选择默认下载引擎",
            items, mDataStore.getDownloaderEngine(),
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int which) {
                    mDataStore.setDownloaderEngine(which);
                    refreshActivePage();
                    AcidFeedback.ok(ctx, "下载引擎已切换为: " + items[which]);
                }
            }
        );
    }

    // =========================================================================
    // TIER 3: AcidDialogs Modals
    // =========================================================================

    private void showSearchEngineDialog() {
        final Context ctx = getContext();
        final String[] items = AcidSettings.SEARCH_ENGINE.displayLabels;
        AcidDialogs.chooseSingle(
            ctx, mIsDarkMode, "选择默认搜索引擎",
            items, mDataStore.getSearchEngineIndex(),
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int which) {
                    if (which == BrowserDataStore.SEARCH_CUSTOM) {
                        showCustomSearchEngineInputDialog();
                    } else {
                        mDataStore.setSearchEngineIndex(which);
                        refreshActivePage();
                        AcidFeedback.ok(ctx, "默认搜索引擎已切换为: " + items[which]);
                    }
                }
            }
        );
    }

    private void showCustomSearchEngineInputDialog() {
        final Context ctx = getContext();
        AcidDialogs.inputSingleLine(
            ctx, mIsDarkMode,
            "自定义搜索引擎",
            "搜索 URL（%s 代表搜索词）",
            mDataStore.getCustomSearchEngineUrl(),
            "https://example.com/s?q=%s",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
            "保存",
            "自定义搜索 URL 不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String input) {
                    mDataStore.setSearchEngineIndex(BrowserDataStore.SEARCH_CUSTOM);
                    mDataStore.setCustomSearchEngineUrl(input);
                    refreshActivePage();
                    AcidFeedback.ok(ctx, "已保存自定义搜索引擎");
                    return true;
                }
            }
        );
    }

    private void showHomepageDialog() {
        final Context ctx = getContext();
        final String[] items = AcidSettings.HOME_TYPE.displayLabels;
        AcidDialogs.chooseSingle(
            ctx, mIsDarkMode, "选择主页类型",
            items, mDataStore.getHomeType(),
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int which) {
                    if (which == BrowserDataStore.HOME_CUSTOM) {
                        showCustomHomepageInputDialog();
                    } else {
                        mDataStore.setHomeType(which);
                        refreshActivePage();
                        if (mListener != null) mListener.onHomeUrlChanged(which, "");
                        AcidFeedback.ok(ctx, "主页已切换为: " + items[which]);
                    }
                }
            }
        );
    }

    private void showCustomHomepageInputDialog() {
        final Context ctx = getContext();
        AcidDialogs.inputSingleLine(
            ctx, mIsDarkMode,
            "自定义主页 URL",
            "主页 URL",
            mDataStore.getCustomHomeUrl(),
            "https://www.bing.com",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
            "保存",
            "主页网址不能为空",
            new AcidDialogs.InputSubmitListener() {
                @Override
                public boolean onSubmit(String input) {
                    mDataStore.setHomeType(BrowserDataStore.HOME_CUSTOM);
                    mDataStore.setCustomHomeUrl(input);
                    refreshActivePage();
                    if (mListener != null) mListener.onHomeUrlChanged(BrowserDataStore.HOME_CUSTOM, input);
                    AcidFeedback.ok(ctx, "已保存自定义主页");
                    return true;
                }
            }
        );
    }

    private void showClearWorkspaceConfirmDialog() {
        AcidDialogs.confirmDanger(
            getContext(), mIsDarkMode,
            "清理 AI 工作区",
            "将清除 Agent 生成的临时文件，保留设置、书签、脚本与拦截规则。",
            "立即清理",
            new Runnable() {
                @Override
                public void run() {
                    if (mListener != null) mListener.onClearWorkspaceOnlyRequested();
                }
            }
        );
    }

    private void showClearDataDialog() {
        final Context ctx = getContext();
        final String[] options = new String[]{
            "网页缓存",
            "Cookie 与登录状态",
            "浏览历史",
            "网站本地存储",
            "AI 工作区临时文件"
        };
        final boolean[] checked = new boolean[]{true, false, true, false, false};

        AcidDialogs.chooseMulti(
            ctx, mIsDarkMode,
            "清除浏览数据",
            options, checked,
            "立即清除", true,
            new AcidDialogs.MultiChoiceCallback() {
                @Override
                public boolean onConfirmed(boolean[] sel) {
                    if (!sel[0] && !sel[1] && !sel[2] && !sel[3] && !sel[4]) {
                        AcidFeedback.warn(ctx, "请至少勾选一项要清除的数据");
                        return false;
                    }
                    if (mListener != null) {
                        mListener.onClearDataRequested(sel[0], sel[1], sel[2], sel[3], sel[4]);
                    }
                    return true;
                }
            }
        );
    }
}
