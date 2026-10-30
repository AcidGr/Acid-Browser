package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Dedicated About & OTA Update Sheet extracted from {@code BrowserActivity}.
 *
 * <p>Follows the standard {@link AcidBottomSheetView} + {@link AcidWidgets} + {@link AcidDialog}
 * template and rebuilds cleanly when Day/Night mode toggles.
 */
public class AboutSheet extends AcidBottomSheetView {

    private static final String TAG = "AcidAboutSheet";
    public static final String UPDATE_PROBE_URL = "https://api.github.com/repos/AcidGr/Acid-Browser/releases/latest";

    public interface OnAboutActionListener {
        void onNavigateToUrl(String url);
        void onStartApkDownload(String url, String fileName);
    }

    private final OnAboutActionListener mListener;

    public AboutSheet(Context context, OnAboutActionListener listener) {
        super(context);
        mListener = listener;
    }

    public void showAbout() {
        rebuildContent();
        show();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            rebuildContent();
        }
    }

    private void rebuildContent() {
        setTitle("关于 Acid Browser");
        hideAction();
        clearPinnedHeader();

        LinearLayout container = getContentContainer();
        container.removeAllViews();

        final Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);
        int text = AcidTheme.text(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // 1. Hero Brand Card
        LinearLayout hero = AcidWidgets.cardGroup(ctx, mIsDarkMode);
        hero.setGravity(Gravity.CENTER);
        int padHero = AcidTheme.dp(ctx, 18);
        hero.setPadding(padHero, padHero, padHero, padHero);

        AcidIcons.SvgIconView logo = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_LOGO, accent, 42);
        hero.addView(logo);

        TextView tvName = new TextView(ctx);
        tvName.setText("Acid Browser");
        tvName.setTextColor(text);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H1_SHEET);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setPadding(0, AcidTheme.dp(ctx, 8), 0, AcidTheme.dp(ctx, 4));
        hero.addView(tvName);

        LinearLayout versionRow = new LinearLayout(ctx);
        versionRow.setOrientation(LinearLayout.HORIZONTAL);
        versionRow.setGravity(Gravity.CENTER_VERTICAL);
        versionRow.setPadding(0, AcidTheme.dp(ctx, 4), 0, 0);

        String curVer = "v" + AcidTheme.appVersionName(ctx);
        TextView tvVersion = new TextView(ctx);
        tvVersion.setText("版本 " + curVer);
        tvVersion.setTextColor(accent);
        tvVersion.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        tvVersion.setTypeface(Typeface.DEFAULT_BOLD);
        tvVersion.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 4));
        tvVersion.setBackground(AcidTheme.pillBg(ctx, AcidTheme.softTint(accent, 28), 0, AcidTheme.RADIUS_BADGE));
        versionRow.addView(tvVersion);

        final TextView[] btnRef = new TextView[1];
        TextView btnCheckUpdate = AcidWidgets.miniButton(ctx, mIsDarkMode, "检查更新", blue, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAppUpdate(btnRef[0]);
            }
        });
        btnRef[0] = btnCheckUpdate;
        LinearLayout.LayoutParams cuLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cuLp.leftMargin = AcidTheme.dp(ctx, 8);
        versionRow.addView(btnCheckUpdate, cuLp);
        hero.addView(versionRow);

        container.addView(hero);

        // 2. Developer & Repository Section
        container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "开发者与开源主页"));
        LinearLayout infoCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);
        int padCard = AcidTheme.dp(ctx, 14);
        infoCard.setPadding(padCard, AcidTheme.dp(ctx, 12), padCard, AcidTheme.dp(ctx, 12));

        infoCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "作者 / 开发者"));
        TextView tvAuthor = new TextView(ctx);
        tvAuthor.setText("酸小明");
        tvAuthor.setTextColor(text);
        tvAuthor.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
        tvAuthor.setTypeface(Typeface.DEFAULT_BOLD);
        tvAuthor.setPadding(0, 0, 0, AcidTheme.dp(ctx, 8));
        infoCard.addView(tvAuthor);

        infoCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "GitHub 主页"));
        TextView tvGh = new TextView(ctx);
        tvGh.setText("https://github.com/AcidGr");
        tvGh.setTextColor(blue);
        tvGh.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tvGh.setPadding(0, 0, 0, AcidTheme.dp(ctx, 10));
        infoCard.addView(tvGh);

        TextView btnVisit = AcidWidgets.primaryButton(ctx, mIsDarkMode, "访问作者 GitHub 主页", blue, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onNavigateToUrl("https://github.com/AcidGr");
                }
            }
        });
        infoCard.addView(btnVisit, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        container.addView(infoCard);

        // 3. Kernel Architecture Summary
        container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "内核简介"));
        LinearLayout descCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);
        descCard.setPadding(padCard, AcidTheme.dp(ctx, 12), padCard, AcidTheme.dp(ctx, 12));

        TextView tvDesc = new TextView(ctx);
        tvDesc.setText("Acid Browser 是一款零第三方依赖、原生内嵌自主 Agent Loop、本地 MCP 控制网关、油猴脚本引擎与 ABP 广告拦截器的超轻量现代 AI 浏览器。");
        tvDesc.setTextColor(muted);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        tvDesc.setLineSpacing(AcidTheme.dp(ctx, 3f), 1f);
        descCard.addView(tvDesc);
        container.addView(descCard);
    }

    private void checkAppUpdate(final TextView triggerBtn) {
        final Context appCtx = getContext().getApplicationContext();
        if (triggerBtn != null) {
            triggerBtn.setText("检查中...");
            triggerBtn.setEnabled(false);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String jsonStr = null;
                try {
                    URL u = new URL(UPDATE_PROBE_URL);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[1024];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            bos.write(buf, 0, len);
                        }
                        in.close();
                        jsonStr = new String(bos.toByteArray(), "UTF-8");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Update probe failed: " + UPDATE_PROBE_URL, e);
                }

                final String resultJson = jsonStr;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (triggerBtn != null) {
                            triggerBtn.setText("检查更新");
                            triggerBtn.setEnabled(true);
                        }
                        if (resultJson == null) {
                            Toast.makeText(appCtx, "检查更新失败，请稍后重试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            JSONObject obj = new JSONObject(resultJson);
                            int remoteCode = obj.optInt("versionCode", 0);
                            String remoteName = obj.optString("versionName", "");
                            String downloadUrl = obj.optString("downloadUrl", "");
                            String apkSize = obj.optString("apkSize", "");
                            String changelog = obj.optString("changelog", "");

                            int currentCode = 0;
                            String currentName = AcidTheme.appVersionName(appCtx);
                            try {
                                PackageInfo pi = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
                                currentCode = pi.versionCode;
                            } catch (Exception e) {
                                Log.w(TAG, "Could not read versionCode", e);
                            }

                            if (remoteCode > currentCode) {
                                showUpdateDialog(remoteName, apkSize, changelog, downloadUrl);
                            } else {
                                Toast.makeText(appCtx, "当前已是最新版本 (v" + currentName + ")", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(appCtx, "解析更新信息失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void showUpdateDialog(
        final String remoteName,
        String apkSize,
        String changelog,
        final String downloadUrl
    ) {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView tvSize = new TextView(ctx);
        tvSize.setText("安装包大小: " + (apkSize.isEmpty() ? "未知" : apkSize));
        tvSize.setTextColor(AcidTheme.text(mIsDarkMode));
        tvSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tvSize.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(tvSize);

        box.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "更新日志"));

        TextView tvLog = new TextView(ctx);
        tvLog.setText(changelog.isEmpty() ? "修复已知问题，提升稳定性与浏览体验" : changelog);
        tvLog.setTextColor(AcidTheme.muted(mIsDarkMode));
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tvLog.setLineSpacing(AcidTheme.dp(ctx, 3f), 1f);
        box.addView(tvLog);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("发现新版本 v" + remoteName)
            .setCustomView(box)
            .setPositiveButton("立即更新", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (mListener != null) {
                        mListener.onStartApkDownload(downloadUrl, "Acid-browser-v" + remoteName + ".apk");
                    }
                    return true;
                }
            })
            .setNegativeButton("稍后再说", null)
            .show();
    }
}
