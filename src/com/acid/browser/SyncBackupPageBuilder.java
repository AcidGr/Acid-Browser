package com.acid.browser;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Declarative UI builder for Settings -&gt; Sync &amp; Backup ({@code 同步与备份}) and
 * WebDAV Account Configuration ({@code WebDAV 账号配置}) pages inside {@link BrowserSettingsSheet}.
 */
public final class SyncBackupPageBuilder {

    private SyncBackupPageBuilder() {}

    public static AcidBottomSheetView.SheetPage buildSyncHomePage(
        final BrowserSettingsSheet sheet,
        final BrowserSettingsSheet.OnSettingsActionListener listener
    ) {
        return new AcidBottomSheetView.SheetPage("同步与备份", new AcidBottomSheetView.PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = sheet.getContext();
                final boolean dark = sheet.isDarkMode();
                int accent = AcidTheme.accent(dark);
                int teal = AcidTheme.COLOR_GREEN;
                int amber = AcidTheme.COLOR_AMBER;
                int purple = AcidTheme.COLOR_PURPLE;

                final WebDavClient.WebDavConfig cfg = WebDavClient.loadConfig(ctx);
                final WebDavClient.SyncSelection sel = WebDavClient.loadSyncSelection(ctx);
                final WebDavClient.SyncStatus lastStatus = WebDavClient.loadLastSyncStatus(ctx);

                // 1. WebDAV Cloud Sync
                container.addView(AcidWidgets.sectionHeader(ctx, dark, "WebDAV 云同步"));
                container.addView(AcidWidgets.groupCard(
                    ctx, dark,
                    AcidWidgets.navRow(
                        ctx, dark, AcidIcons.ICON_RELOAD, cfg.isConfigured() ? teal : AcidTheme.muted(dark),
                        "WebDAV 服务器",
                        cfg.isConfigured() ? cfg.getMaskedDisplayUrl() : null,
                        cfg.isConfigured() ? "已配置" : "未配置",
                        true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                sheet.pushPage(buildWebDavConfigPage(sheet, listener));
                            }
                        }
                    )
                ));

                LinearLayout cloudActionCard = AcidWidgets.contentCard(ctx, dark);

                if (lastStatus.timestampMs > 0L && !lastStatus.summary.isEmpty()) {
                    String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(lastStatus.timestampMs));
                    TextView tvStatus = AcidWidgets.descriptionText(
                        ctx, dark, "最近记录：" + timeStr + " · " + lastStatus.summary
                    );
                    tvStatus.setPadding(0, 0, 0, AcidTheme.dp(ctx, 10));
                    cloudActionCard.addView(tvStatus);
                }

                final TextView uploadBtnView = AcidWidgets.primaryButton(
                    ctx, dark, "立即上传", accent, null
                );
                final AcidWidgets.LoadingButton uploadBtn = new AcidWidgets.LoadingButton(
                    uploadBtnView, "立即上传", "上传中..."
                );

                final TextView restoreBtnView = AcidWidgets.secondaryButton(
                    ctx, dark, "从云端恢复", accent, null
                );
                final AcidWidgets.LoadingButton restoreBtn = new AcidWidgets.LoadingButton(
                    restoreBtnView, "从云端恢复", "恢复中..."
                );

                uploadBtnView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        WebDavClient.WebDavConfig latest = WebDavClient.loadConfig(ctx);
                        if (!latest.isConfigured()) {
                            AcidFeedback.info(ctx, "请先配置 WebDAV 服务器账号");
                            sheet.pushPage(buildWebDavConfigPage(sheet, listener));
                            return;
                        }
                        WebDavClient.SyncSelection currentSel = WebDavClient.loadSyncSelection(ctx);
                        if (!currentSel.hasAny()) {
                            AcidFeedback.warn(ctx, "请至少勾选一项要同步的内容");
                            return;
                        }
                        if (listener == null) return;
                        uploadBtn.setLoading();
                        restoreBtnView.setEnabled(false);
                        restoreBtnView.setAlpha(0.55f);
                        listener.onWebDavUploadRequested(new BrowserBackupManager.BackupResultCallback() {
                            @Override
                            public void onResult(boolean ok, BrowserBackupManager.SyncSummary summary, String message) {
                                uploadBtn.reset();
                                restoreBtn.reset();
                                if (ok) {
                                    AcidFeedback.ok(ctx, message);
                                    sheet.refresh();
                                } else {
                                    AcidFeedback.error(ctx, message);
                                }
                            }
                        });
                    }
                });

                restoreBtnView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        WebDavClient.WebDavConfig latest = WebDavClient.loadConfig(ctx);
                        if (!latest.isConfigured()) {
                            AcidFeedback.info(ctx, "请先配置 WebDAV 服务器账号");
                            sheet.pushPage(buildWebDavConfigPage(sheet, listener));
                            return;
                        }
                        WebDavClient.SyncSelection currentSel = WebDavClient.loadSyncSelection(ctx);
                        if (!currentSel.hasAny()) {
                            AcidFeedback.warn(ctx, "请至少勾选一项要恢复的内容");
                            return;
                        }
                        showRestoreModeDialog(ctx, dark, "选择云端恢复方式", new RestoreModeCallback() {
                            @Override
                            public void onModeChosen(boolean overwriteMode) {
                                if (listener == null) return;
                                restoreBtn.setLoading();
                                uploadBtnView.setEnabled(false);
                                uploadBtnView.setAlpha(0.55f);
                                listener.onWebDavRestoreRequested(overwriteMode, new BrowserBackupManager.BackupResultCallback() {
                                    @Override
                                    public void onResult(boolean ok, BrowserBackupManager.SyncSummary summary, String message) {
                                        uploadBtn.reset();
                                        restoreBtn.reset();
                                        if (ok) {
                                            AcidFeedback.ok(ctx, message);
                                        } else {
                                            AcidFeedback.error(ctx, message);
                                        }
                                    }
                                });
                            }
                        });
                    }
                });

                cloudActionCard.addView(AcidWidgets.buttonRow(ctx, uploadBtnView, restoreBtnView));
                container.addView(cloudActionCard);

                // 2. Sync & Backup Scope
                container.addView(AcidWidgets.sectionHeader(ctx, dark, "同步内容"));
                container.addView(AcidWidgets.groupCard(
                    ctx, dark,
                    AcidWidgets.switchRow(
                        ctx, dark, AcidIcons.ICON_BOOKMARK, amber,
                        "书签",
                        null,
                        sel.bookmarks,
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                WebDavClient.SyncSelection s = WebDavClient.loadSyncSelection(ctx);
                                s.bookmarks = isChecked;
                                WebDavClient.saveSyncSelection(ctx, s);
                            }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, dark, AcidIcons.ICON_SETTINGS, accent,
                        "浏览器设置",
                        null,
                        sel.settings,
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                WebDavClient.SyncSelection s = WebDavClient.loadSyncSelection(ctx);
                                s.settings = isChecked;
                                WebDavClient.saveSyncSelection(ctx, s);
                            }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, dark, AcidIcons.ICON_TERMINAL, purple,
                        "油猴脚本",
                        null,
                        sel.userscripts,
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                WebDavClient.SyncSelection s = WebDavClient.loadSyncSelection(ctx);
                                s.userscripts = isChecked;
                                WebDavClient.saveSyncSelection(ctx, s);
                            }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, dark, AcidIcons.ICON_SHIELD, teal,
                        "广告拦截",
                        null,
                        sel.adblock,
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                WebDavClient.SyncSelection s = WebDavClient.loadSyncSelection(ctx);
                                s.adblock = isChecked;
                                WebDavClient.saveSyncSelection(ctx, s);
                            }
                        }
                    ),
                    AcidWidgets.switchRow(
                        ctx, dark, AcidIcons.ICON_AGENT, accent,
                        "Skills 与自定义主页",
                        null,
                        sel.skillsAndHomepage,
                        new AcidWidgets.OnToggleListener() {
                            @Override
                            public void onToggle(boolean isChecked) {
                                WebDavClient.SyncSelection s = WebDavClient.loadSyncSelection(ctx);
                                s.skillsAndHomepage = isChecked;
                                WebDavClient.saveSyncSelection(ctx, s);
                            }
                        }
                    )
                ));

                // 3. Local Archive Export / Import
                container.addView(AcidWidgets.sectionHeader(ctx, dark, "本地备份"));
                container.addView(AcidWidgets.groupCard(
                    ctx, dark,
                    AcidWidgets.navRow(
                        ctx, dark, AcidIcons.ICON_FILE_ARCHIVE, accent,
                        "导出备份包",
                        null,
                        null, true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                WebDavClient.SyncSelection currentSel = WebDavClient.loadSyncSelection(ctx);
                                if (!currentSel.hasAny()) {
                                    AcidFeedback.warn(ctx, "请至少勾选一项要导出的内容");
                                    return;
                                }
                                if (listener != null) {
                                    listener.onExportLocalBackupZipRequested(new BrowserBackupManager.BackupResultCallback() {
                                        @Override
                                        public void onResult(boolean ok, BrowserBackupManager.SyncSummary summary, String message) {
                                            if (ok) {
                                                sheet.refresh();
                                            } else {
                                                AcidFeedback.error(ctx, message);
                                            }
                                        }
                                    });
                                }
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, dark, AcidIcons.ICON_DOWNLOAD, teal,
                        "导入备份包",
                        null,
                        null, true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                WebDavClient.SyncSelection currentSel = WebDavClient.loadSyncSelection(ctx);
                                if (!currentSel.hasAny()) {
                                    AcidFeedback.warn(ctx, "请至少勾选一项要恢复的内容");
                                    return;
                                }
                                showRestoreModeDialog(ctx, dark, "选择本地备份恢复方式", new RestoreModeCallback() {
                                    @Override
                                    public void onModeChosen(boolean overwriteMode) {
                                        if (listener != null) {
                                            listener.onImportLocalBackupZipRequested(overwriteMode);
                                        }
                                    }
                                });
                            }
                        }
                    )
                ));

                // 4. Standalone Bookmark HTML Import / Export
                container.addView(AcidWidgets.sectionHeader(ctx, dark, "书签文件"));
                container.addView(AcidWidgets.groupCard(
                    ctx, dark,
                    AcidWidgets.navRow(
                        ctx, dark, AcidIcons.ICON_BOOKMARK, amber,
                        "导入书签",
                        null,
                        null, true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (listener != null) {
                                    listener.onImportBookmarksRequested();
                                }
                            }
                        }
                    ),
                    AcidWidgets.navRow(
                        ctx, dark, AcidIcons.ICON_FILE, accent,
                        "导出书签",
                        null,
                        null, true,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (listener != null) {
                                    listener.onExportBookmarksRequested();
                                }
                            }
                        }
                    )
                ));

                TextView privacyNote = AcidWidgets.descriptionText(
                    ctx, dark,
                    "不包含浏览历史、Cookie、API Key 与 Token。"
                );
                container.addView(privacyNote);
            }
        });
    }

    static int detectPresetIndex(String serverUrl) {
        if (serverUrl == null || serverUrl.isEmpty()) {
            return 3;
        }
        String lower = serverUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("jianguoyun.com")) {
            return 0;
        }
        if (lower.contains("/remote.php/dav")) {
            return 1;
        }
        if ("https://example.com/dav".equals(lower)
                || lower.contains(":5244/dav")
                || lower.contains("alist")) {
            return 2;
        }
        return 3;
    }

    private static void bindDraftWatcher(EditText et, final String[] draft, final int slot) {
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                draft[slot] = s != null ? s.toString() : "";
            }
        });
    }

    public static AcidBottomSheetView.SheetPage buildWebDavConfigPage(
        final BrowserSettingsSheet sheet,
        final BrowserSettingsSheet.OnSettingsActionListener listener
    ) {
        final Context ctx = sheet.getContext();
        final WebDavClient.WebDavConfig existing = WebDavClient.loadConfig(ctx);
        final String[] draft = new String[]{
            existing.serverUrl,
            existing.remoteFolder,
            existing.username,
            existing.password
        };
        final int[] presetIdx = new int[]{ detectPresetIndex(existing.serverUrl) };
        final boolean[] passVisible = new boolean[]{ false };

        AcidBottomSheetView.SheetPage page = new AcidBottomSheetView.SheetPage(
            "WebDAV 配置",
            new AcidBottomSheetView.FullPageRenderer() {
                @Override
                public void render(LinearLayout pinnedHeader, LinearLayout container, LinearLayout pinnedFooter) {
                    final boolean dark = sheet.isDarkMode();
                    int accent = AcidTheme.accent(dark);

                    container.addView(AcidWidgets.sectionHeader(ctx, dark, "预设"));
                    final EditText[] urlHolder = new EditText[1];
                    final EditText[] folderHolder = new EditText[1];
                    final TextView[] presetTipHolder = new TextView[1];

                    String[] presets = new String[]{"坚果云", "Nextcloud", "Alist", "自定义"};
                    container.addView(AcidWidgets.filterChipBar(
                        ctx, dark, presets, presetIdx[0],
                        new AcidWidgets.OnTabSelectedListener() {
                            @Override
                            public void onTabSelected(int index) {
                                presetIdx[0] = index;
                                EditText etUrl = urlHolder[0];
                                EditText etFolder = folderHolder[0];
                                TextView tip = presetTipHolder[0];
                                if (etUrl == null || tip == null) return;
                                if (index == 0) {
                                    etUrl.setText("https://dav.jianguoyun.com/dav");
                                    if (etFolder != null && etFolder.getText().toString().trim().isEmpty()) {
                                        etFolder.setText(WebDavClient.DEFAULT_REMOTE_FOLDER);
                                    }
                                    tip.setText(AcidLocale.tr("坚果云需使用第三方应用专用密码"));
                                    tip.setVisibility(View.VISIBLE);
                                } else if (index == 1) {
                                    etUrl.setText("https://example.com/remote.php/dav/files/user");
                                    tip.setVisibility(View.GONE);
                                } else if (index == 2) {
                                    etUrl.setText("https://example.com/dav");
                                    tip.setVisibility(View.GONE);
                                } else {
                                    tip.setVisibility(View.GONE);
                                }
                            }
                        }
                    ));

                    TextView presetTip = AcidWidgets.descriptionText(
                        ctx, dark,
                        "坚果云需使用第三方应用专用密码"
                    );
                    presetTip.setVisibility(presetIdx[0] == 0 ? View.VISIBLE : View.GONE);
                    presetTipHolder[0] = presetTip;
                    container.addView(presetTip);

                    LinearLayout formCard = AcidWidgets.contentCard(ctx, dark);
                    formCard.addView(AcidWidgets.fieldLabel(ctx, dark, "WebDAV URL"));
                    final EditText etUrl = AcidWidgets.inputField(
                        ctx, dark, draft[0], "https://...", true
                    );
                    etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                    bindDraftWatcher(etUrl, draft, 0);
                    urlHolder[0] = etUrl;
                    formCard.addView(etUrl);

                    formCard.addView(AcidWidgets.fieldLabel(ctx, dark, "同步目录"));
                    final EditText etFolder = AcidWidgets.inputField(
                        ctx, dark, draft[1], "留空为根目录", true
                    );
                    bindDraftWatcher(etFolder, draft, 1);
                    folderHolder[0] = etFolder;
                    formCard.addView(etFolder);

                    formCard.addView(AcidWidgets.fieldLabel(ctx, dark, "用户名"));
                    final EditText etUser = AcidWidgets.inputField(
                        ctx, dark, draft[2], "选填", true
                    );
                    bindDraftWatcher(etUser, draft, 2);
                    formCard.addView(etUser);

                    final EditText etPass = AcidWidgets.inputField(
                        ctx, dark, draft[3], "选填", true
                    );
                    etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    etPass.setTransformationMethod(
                        passVisible[0]
                            ? HideReturnsTransformationMethod.getInstance()
                            : PasswordTransformationMethod.getInstance()
                    );
                    bindDraftWatcher(etPass, draft, 3);

                    final TextView[] toggleViewRef = new TextView[1];
                    TextView toggleBtn = AcidWidgets.textAction(
                        ctx, dark, passVisible[0] ? "隐藏密码" : "显示密码", accent,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                passVisible[0] = !passVisible[0];
                                int selPos = etPass.getSelectionEnd();
                                etPass.setTransformationMethod(
                                    passVisible[0]
                                        ? HideReturnsTransformationMethod.getInstance()
                                        : PasswordTransformationMethod.getInstance()
                                );
                                if (selPos >= 0) {
                                    etPass.setSelection(Math.min(selPos, etPass.getText().length()));
                                }
                                if (toggleViewRef[0] != null) {
                                    toggleViewRef[0].setText(AcidLocale.tr(passVisible[0] ? "隐藏密码" : "显示密码"));
                                }
                            }
                        }
                    );
                    toggleViewRef[0] = toggleBtn;
                    ((LinearLayout.LayoutParams) etPass.getLayoutParams()).bottomMargin = 0;
                    formCard.addView(AcidWidgets.fieldLabelWithAction(ctx, dark, "密码", toggleBtn));
                    formCard.addView(etPass);
                    container.addView(formCard);

                    // Pinned Footer: Test Connection + Save Config
                    final TextView testBtnView = AcidWidgets.secondaryButton(
                        ctx, dark, "测试连接", accent, null
                    );
                    final AcidWidgets.LoadingButton testBtn = new AcidWidgets.LoadingButton(
                        testBtnView, "测试连接", "测试中..."
                    );

                    TextView saveBtnView = AcidWidgets.primaryButton(
                        ctx, dark, "保存配置", accent,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                WebDavClient.WebDavConfig next = readFormConfig(etUrl, etFolder, etUser, etPass);
                                if (!next.isConfigured()) {
                                    AcidFeedback.warn(ctx, "请输入有效的 http:// 或 https:// URL");
                                    return;
                                }
                                try {
                                    WebDavClient.validateConfigOrThrow(next);
                                } catch (WebDavClient.WebDavException wde) {
                                    AcidFeedback.warn(ctx, wde.getMessage());
                                    return;
                                }
                                WebDavClient.saveConfig(ctx, next);
                                AcidFeedback.ok(ctx, "WebDAV 配置已保存");
                                sheet.popPage();
                            }
                        }
                    );

                    testBtnView.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            WebDavClient.WebDavConfig candidate = readFormConfig(etUrl, etFolder, etUser, etPass);
                            if (!candidate.isConfigured()) {
                                AcidFeedback.warn(ctx, "请输入有效的 http:// 或 https:// URL");
                                return;
                            }
                            if (listener == null) return;
                            testBtn.setLoading();
                            listener.onTestWebDavRequested(candidate, new BrowserBackupManager.BackupResultCallback() {
                                @Override
                                public void onResult(boolean ok, BrowserBackupManager.SyncSummary summary, String message) {
                                    testBtn.reset();
                                    if (ok) {
                                        AcidFeedback.ok(ctx, message);
                                    } else {
                                        AcidFeedback.error(ctx, message);
                                    }
                                }
                            });
                        }
                    });

                    pinnedFooter.addView(AcidWidgets.buttonRow(ctx, testBtnView, saveBtnView));
                }
            }
        );

        if (existing.isConfigured()) {
            page.withAction(AcidIcons.ICON_TRASH, "清空", AcidTheme.COLOR_RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    WebDavClient.clearConfig(ctx);
                    AcidFeedback.info(ctx, "已清除 WebDAV 服务器配置");
                    sheet.popPage();
                }
            });
        }
        return page;
    }

    private static WebDavClient.WebDavConfig readFormConfig(
        EditText etUrl,
        EditText etFolder,
        EditText etUser,
        EditText etPass
    ) {
        return new WebDavClient.WebDavConfig(
            etUrl.getText().toString(),
            etFolder.getText().toString(),
            etUser.getText().toString(),
            etPass.getText().toString()
        );
    }

    private interface RestoreModeCallback {
        void onModeChosen(boolean overwriteMode);
    }

    private static void showRestoreModeDialog(
        Context ctx,
        boolean dark,
        String title,
        final RestoreModeCallback callback
    ) {
        String[] options = new String[]{
            "合并恢复（保留本地数据）",
            "完全覆盖"
        };
        AcidDialogs.chooseSingle(
            ctx, dark, title, options, -1,
            new AcidDialogs.SingleChoiceCallback() {
                @Override
                public void onSelected(int index) {
                    if (callback != null) {
                        callback.onModeChosen(index == 1);
                    }
                }
            }
        );
    }
}
