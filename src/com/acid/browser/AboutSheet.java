package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * About & OTA Update Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage} template,
 * {@link AcidWidgets}, and {@link AppUpdateChecker}.
 */
public class AboutSheet extends AcidBottomSheetView {

    public static final String GITHUB_REPO_URL = "https://github.com/AcidGr/Acid-Browser";

    public interface OnAboutActionListener {
        void onNavigateToUrl(String url);
        void onStartApkDownload(String url, String fileName);
    }

    private final OnAboutActionListener mListener;

    public AboutSheet(Context context, OnAboutActionListener listener) {
        super(context);
        mListener = listener;
        setRootPage(buildAboutPage());
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildAboutPage());
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildAboutPage());
        } else {
            refreshActivePage();
        }
        super.show();
    }

    private SheetPage buildAboutPage() {
        return new SheetPage("关于 Acid Browser", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                int text = AcidTheme.text(mIsDarkMode);
                int muted = AcidTheme.muted(mIsDarkMode);
                int blue = AcidTheme.COLOR_BLUE;

                // 1. Hero Brand Card
                LinearLayout hero = AcidWidgets.heroCard(ctx, mIsDarkMode, AcidTheme.border(mIsDarkMode));
                hero.setGravity(Gravity.CENTER);

                AcidIcons.SvgIconView logo = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_LOGO, accent, 42);
                hero.addView(logo);

                TextView tvName = new TextView(ctx);
                tvName.setText("Acid Browser");
                tvName.setTextColor(text);
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                tvName.setPadding(0, AcidTheme.dp(ctx, 8), 0, AcidTheme.dp(ctx, 4));
                hero.addView(tvName);

                LinearLayout versionRow = new LinearLayout(ctx);
                versionRow.setOrientation(LinearLayout.HORIZONTAL);
                versionRow.setGravity(Gravity.CENTER_VERTICAL);
                versionRow.setPadding(0, AcidTheme.dp(ctx, 4), 0, 0);

                String curVer = "v" + AcidTheme.appVersionName(ctx);
                versionRow.addView(AcidWidgets.statusTag(ctx, mIsDarkMode, "版本 " + curVer, accent));

                AcidWidgets.LoadingButton btnCheckUpdate = AcidWidgets.LoadingButton.mini(
                    ctx, mIsDarkMode, "检查更新", "检查中...", blue, false,
                    new AcidWidgets.LoadingButton.OnAsyncActionListener() {
                        @Override
                        public void onTrigger(final AcidWidgets.LoadingButton button) {
                            button.setLoading();
                            AppUpdateChecker.checkAsync(ctx, new AppUpdateChecker.Callback() {
                                @Override
                                public void onResult(AppUpdateChecker.UpdateInfo info) {
                                    button.reset();
                                    if (info.hasUpdate) {
                                        showUpdateDialog(info);
                                    } else {
                                        AcidFeedback.ok(ctx, "当前已是最新版本 (v" + info.currentVersionName + ")");
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    button.reset();
                                    AcidFeedback.warn(ctx, message);
                                }
                            });
                        }
                    }
                );
                LinearLayout.LayoutParams cuLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                cuLp.leftMargin = AcidTheme.dp(ctx, 8);
                versionRow.addView(btnCheckUpdate.view, cuLp);
                hero.addView(versionRow);
                container.addView(hero);

                // 2. Developer & Open Source Section
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "开发者与开源仓库"));
                container.addView(AcidWidgets.groupCard(
                    ctx, mIsDarkMode,
                    AcidWidgets.valueRow(ctx, mIsDarkMode, AcidIcons.ICON_AGENT, accent, "开发者", "酸小明", null),
                    AcidWidgets.navRow(ctx, mIsDarkMode, AcidIcons.ICON_GLOBE, blue, "GitHub 开源仓库", "AcidGr/Acid-Browser", new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (mListener != null) {
                                mListener.onNavigateToUrl(GITHUB_REPO_URL);
                            }
                        }
                    })
                ));

                // 3. Kernel Architecture Summary
                container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "内核简介"));
                LinearLayout descCard = AcidWidgets.contentCard(ctx, mIsDarkMode);
                TextView tvDesc = new TextView(ctx);
                tvDesc.setText("Acid Browser 是一款零第三方依赖、原生内嵌自主 Agent Loop、本地 MCP 控制网关、油猴脚本引擎与 ABP 广告拦截器的超轻量现代 AI 浏览器。");
                tvDesc.setTextColor(muted);
                tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvDesc.setLineSpacing(AcidTheme.dp(ctx, 3f), 1f);
                descCard.addView(tvDesc);
                container.addView(descCard);
            }
        });
    }

    private void showUpdateDialog(final AppUpdateChecker.UpdateInfo info) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView tvSize = new TextView(ctx);
        tvSize.setText("安装包大小: " + (info.apkSize.isEmpty() ? "未知" : info.apkSize));
        tvSize.setTextColor(AcidTheme.text(mIsDarkMode));
        tvSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tvSize.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(tvSize);

        box.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "更新说明"));

        TextView tvLog = new TextView(ctx);
        tvLog.setText(info.changelog.isEmpty() ? "修复已知问题，提升稳定性与浏览体验" : info.changelog);
        tvLog.setTextColor(AcidTheme.muted(mIsDarkMode));
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tvLog.setLineSpacing(AcidTheme.dp(ctx, 3f), 1f);
        box.addView(tvLog);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("发现新版本 v" + info.remoteVersionName)
            .setCustomView(box)
            .setPositiveButton("立即更新", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (mListener != null) {
                        String lowerUrl = info.downloadUrl != null ? info.downloadUrl.toLowerCase() : "";
                        int qIdx = lowerUrl.indexOf('?');
                        String pathPart = (qIdx >= 0) ? lowerUrl.substring(0, qIdx) : lowerUrl;
                        if (pathPart.endsWith(".apk")) {
                            mListener.onStartApkDownload(
                                info.downloadUrl,
                                "Acid-browser-v" + info.remoteVersionName + ".apk"
                            );
                        } else {
                            mListener.onNavigateToUrl(info.downloadUrl);
                        }
                    }
                    return true;
                }
            })
            .setNegativeButton("稍后再说", null)
            .show();
    }
}
