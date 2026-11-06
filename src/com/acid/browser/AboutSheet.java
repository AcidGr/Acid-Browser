package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * About & OTA Update Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage} template,
 * {@link AcidWidgets}, and {@link AppUpdateChecker}.
 */
public class AboutSheet extends AcidBottomSheetView {

    public static final String GITHUB_REPO_URL = "https://github.com/AcidGr/Acid-Browser";
    public static final String PREF_HIDE_BEGGING = "hide_begging_button";
    private static final String ASSET_WECHAT_QR = "donate/wechat_qr.png";
    private static final String ASSET_ALIPAY_QR = "donate/alipay_qr.jpg";

    public interface OnAboutActionListener {
        void onNavigateToUrl(String url);
        void onStartApkDownload(String url, String fileName);
    }

    private final OnAboutActionListener mListener;
    private boolean mShowingAlipay = false;

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

                if (!isBeggingHidden(ctx)) {
                    TextView btnBeg = AcidWidgets.miniButton(
                        ctx, mIsDarkMode, "乞讨", AcidTheme.COLOR_ORANGE, false,
                        new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                mShowingAlipay = false;
                                pushPage(buildBeggingPage());
                            }
                        }
                    );
                    LinearLayout.LayoutParams begLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    begLp.leftMargin = AcidTheme.dp(ctx, 8);
                    versionRow.addView(btnBeg, begLp);
                }

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

    private static boolean isBeggingHidden(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getSharedPreferences(BrowserDataStore.PREF_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(PREF_HIDE_BEGGING, false);
    }

    private static void setBeggingHidden(Context ctx, boolean hidden) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(BrowserDataStore.PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(PREF_HIDE_BEGGING, hidden).apply();
    }

    private SheetPage buildBeggingPage() {
        return new SheetPage("乞讨", new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinnedHeader, LinearLayout container, LinearLayout pinnedFooter) {
                final Context ctx = getContext();
                int text = AcidTheme.text(mIsDarkMode);

                // 1. Top Remark Note
                LinearLayout noteCard = AcidWidgets.contentCard(ctx, mIsDarkMode, AcidTheme.COLOR_ORANGE);
                TextView tvNote = new TextView(ctx);
                tvNote.setText("此为作者的乞讨页面，您可以付款当时您得不到任何好处！");
                tvNote.setTextColor(text);
                tvNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
                tvNote.setTypeface(Typeface.DEFAULT_BOLD);
                tvNote.setGravity(Gravity.CENTER);
                tvNote.setLineSpacing(AcidTheme.dp(ctx, 3f), 1f);
                noteCard.addView(tvNote);
                container.addView(noteCard);

                // 2. Two Buttons: 微信 & 支付宝
                final ImageView qrImageView = new ImageView(ctx);
                qrImageView.setAdjustViewBounds(true);
                qrImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                qrImageView.setMaxHeight(AcidTheme.dp(ctx, 360));

                OnClickListener selectWeChat = new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mShowingAlipay) {
                            mShowingAlipay = false;
                            refreshActivePage();
                        }
                    }
                };
                OnClickListener selectAlipay = new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!mShowingAlipay) {
                            mShowingAlipay = true;
                            refreshActivePage();
                        }
                    }
                };

                TextView btnWeChat = !mShowingAlipay
                    ? AcidWidgets.primaryButton(ctx, mIsDarkMode, "微信", AcidTheme.COLOR_GREEN, selectWeChat)
                    : AcidWidgets.secondaryButton(ctx, mIsDarkMode, "微信", text, selectWeChat);

                TextView btnAlipay = mShowingAlipay
                    ? AcidWidgets.primaryButton(ctx, mIsDarkMode, "支付宝", AcidTheme.COLOR_BLUE, selectAlipay)
                    : AcidWidgets.secondaryButton(ctx, mIsDarkMode, "支付宝", text, selectAlipay);

                LinearLayout switchRow = AcidWidgets.buttonRow(ctx, btnWeChat, btnAlipay);
                LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                switchLp.bottomMargin = AcidTheme.dp(ctx, 10);
                container.addView(switchRow, switchLp);

                // 3. QR Code Image Card
                LinearLayout qrCard = AcidWidgets.contentCard(ctx, mIsDarkMode);
                qrCard.setGravity(Gravity.CENTER);
                Bitmap qrBitmap = loadQrBitmapFromAsset(ctx, mShowingAlipay ? ASSET_ALIPAY_QR : ASSET_WECHAT_QR);
                if (qrBitmap != null) {
                    qrImageView.setImageBitmap(qrBitmap);
                }
                LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                qrCard.addView(qrImageView, qrLp);
                container.addView(qrCard);

                // 4. Bottom Action Button to permanently hide the begging button
                TextView btnHideForever = AcidWidgets.secondaryButton(
                    ctx, mIsDarkMode, "以后都隐藏乞讨按钮", AcidTheme.muted(mIsDarkMode),
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            setBeggingHidden(ctx, true);
                            AcidFeedback.info(ctx, "已隐藏乞讨按钮");
                            popPage();
                        }
                    }
                );
                pinnedFooter.addView(btnHideForever, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        });
    }

    private static Bitmap loadQrBitmapFromAsset(Context ctx, String assetPath) {
        if (ctx == null || assetPath == null) return null;
        InputStream is = null;
        try {
            is = ctx.getAssets().open(assetPath);
            return BitmapFactory.decodeStream(is);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
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
