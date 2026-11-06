package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * About & OTA Update Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage} template,
 * {@link AcidWidgets}, and {@link AppUpdateChecker}.
 */
public class AboutSheet extends AcidBottomSheetView {

    public static final String GITHUB_REPO_URL = "https://github.com/AcidGr/Acid-Browser";
    public static final String PREF_HIDE_BEGGING = "hide_begging_button";

    /** 37x37 WeChat QR matrix (171 bytes packed hex). */
    private static final String WECHAT_QR_HEX =
        "fe14e5c3fc1586b2506e9b5a2abb75d6b445dbabc7faaec1337a5d07faaaaaafe019cb8a005efec676d32e8d1"
        + "96dcccc6632aefaaec232022100016786d8003908cf0000dc6d3800063eeb20001b8e0a000143d2bc00109a"
        + "90800099467c00031460a0000b388c00008dae8e00176e0ee0017b4c082d8f767890b566a123bf5e2b18b32"
        + "ddffc804b980c77f9247f6a105c056f18baa6d69f9dd4060549ee91cfe17f05a5fff68fe1549eaa8";

    /** 41x41 Alipay QR matrix (210 bytes packed hex). */
    private static final String ALIPAY_QR_HEX =
        "fe08a8a43fc1112d01906eaf2de62bb75cd8f275dba1e51652ec132a627107faaaaaaafe00246b80001b49e2a"
        + "d864461bc4aac75e4674492d825648cbd956b58b60f0b4994d80afcea8c24d1c3438ed0d29c53c0eab9c42"
        + "e124019e52ce2a01c47a8842018cd4fa0780d12fda42406d8a29ddc024940198a01cfcf52482e1ae6525810"
        + "cf7dd289016ce5698c4ea963f5b7d71b33478ff4a5015b4e81f2ad4fba933ab36cea58a665ff8066446947b"
        + "fb981fdeb10460f6ab1bbaf562d7fd5d4f0d0a8b6e8b375d567040f205495fe02df708e0";

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

                // 1. Top Remark Note (borderless plain text)
                TextView tvNote = new TextView(ctx);
                tvNote.setText("此为作者的乞讨页面，您可以付款但是您得不到任何好处！");
                tvNote.setTextColor(text);
                tvNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
                tvNote.setTypeface(Typeface.DEFAULT_BOLD);
                tvNote.setGravity(Gravity.CENTER);
                tvNote.setLineSpacing(AcidTheme.dp(ctx, 3f), 1f);
                tvNote.setPadding(
                    AcidTheme.dp(ctx, 4),
                    AcidTheme.dp(ctx, 2),
                    AcidTheme.dp(ctx, 4),
                    AcidTheme.dp(ctx, 12)
                );
                container.addView(tvNote);

                // 2. Two Buttons: 微信 & 支付宝
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
                switchLp.bottomMargin = AcidTheme.dp(ctx, 12);
                container.addView(switchRow, switchLp);

                // 3. Vector-rendered QR Code Card
                ImageView qrImageView = new ImageView(ctx);
                qrImageView.setAdjustViewBounds(true);
                qrImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                qrImageView.setMaxHeight(AcidTheme.dp(ctx, 380));
                qrImageView.setImageBitmap(renderVectorQrCard(mShowingAlipay));
                LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                qrLp.bottomMargin = AcidTheme.dp(ctx, 6);
                container.addView(qrImageView, qrLp);

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

    static Bitmap renderVectorQrCard(boolean isAlipay) {
        final int cardW = 720;
        final int cardH = 860;
        final int n = isAlipay ? 41 : 37;
        final int c0 = isAlipay ? 17 : 13;
        final int c1 = 24;
        final String hex = isAlipay ? ALIPAY_QR_HEX : WECHAT_QR_HEX;
        final int brandColor = isAlipay ? Color.rgb(22, 119, 255) : Color.rgb(7, 193, 96);
        final String badgeChar = isAlipay ? "支" : "微";
        final String titleText = isAlipay ? "推荐使用支付宝" : "推荐使用微信支付";
        final String subText = isAlipay ? "打开支付宝 [扫一扫]" : "微信扫一扫向作者付款";

        Bitmap bmp = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Outer brand card
        p.setStyle(Paint.Style.FILL);
        p.setColor(brandColor);
        canvas.drawRoundRect(new RectF(0, 0, cardW, cardH), 32f, 32f, p);

        // Inner white card
        final int padX = 64;
        final int topY = 130;
        final int botY = 770;
        p.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(padX, topY, cardW - padX, botY), 28f, 28f, p);

        // QR modules
        final int cell = isAlipay ? 11 : 12;
        final int qrPx = n * cell;
        final int qrX0 = (cardW - qrPx) / 2;
        final int qrY0 = topY + 52;
        p.setColor(Color.BLACK);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int bitIdx = r * n + c;
                int hexIdx = bitIdx >> 2;
                int shift = 3 - (bitIdx & 3);
                if (hexIdx < hex.length()) {
                    int nibble = Character.digit(hex.charAt(hexIdx), 16);
                    if (nibble >= 0 && ((nibble >> shift) & 1) == 1) {
                        int x = qrX0 + c * cell;
                        int y = qrY0 + r * cell;
                        canvas.drawRect(x, y, x + cell, y + cell, p);
                    }
                }
            }
        }

        // Center badge inside cleared region
        float bx0 = qrX0 + c0 * cell + 2f;
        float by0 = qrY0 + c0 * cell + 2f;
        float bx1 = qrX0 + c1 * cell - 2f;
        float by1 = qrY0 + c1 * cell - 2f;
        p.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(bx0, by0, bx1, by1), 14f, 14f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(Color.rgb(230, 230, 230));
        canvas.drawRoundRect(new RectF(bx0, by0, bx1, by1), 14f, 14f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(brandColor);
        canvas.drawRoundRect(new RectF(bx0 + 6f, by0 + 6f, bx1 - 6f, by1 - 6f), 10f, 10f, p);

        // Typography
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);

        p.setColor(Color.WHITE);
        p.setTextSize(42f);
        drawCenteredText(canvas, p, titleText, cardW / 2f, 68f);

        p.setTextSize(38f);
        drawCenteredText(canvas, p, badgeChar, (bx0 + bx1) / 2f, (by0 + by1) / 2f);

        p.setColor(Color.rgb(30, 41, 59));
        p.setTextSize(30f);
        drawCenteredText(canvas, p, "酸小明(**勋)", cardW / 2f, qrY0 + qrPx + 54f);

        p.setColor(Color.WHITE);
        p.setTextSize(26f);
        drawCenteredText(canvas, p, subText, cardW / 2f, 815f);

        return bmp;
    }

    private static void drawCenteredText(Canvas canvas, Paint p, String text, float cx, float cy) {
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, cx, baseline, p);
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
