package com.acid.browser;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONObject;

/**
 * Asynchronous OTA Update Checker for Acid Browser.
 *
 * <p>Decouples network probing and version metadata parsing from {@link AboutSheet}, using
 * {@link AcidAsync} and {@link AcidHttp}.
 */
public final class AppUpdateChecker {

    private static final String TAG = "AcidUpdateChecker";
    public static final String UPDATE_PROBE_URL = "https://api.github.com/repos/AcidGr/Acid-Browser/releases/latest";

    public static final class UpdateInfo {
        public final boolean hasUpdate;
        public final int remoteVersionCode;
        public final String remoteVersionName;
        public final String currentVersionName;
        public final String downloadUrl;
        public final String apkSize;
        public final String changelog;

        UpdateInfo(
            boolean hasUpdate,
            int remoteVersionCode,
            String remoteVersionName,
            String currentVersionName,
            String downloadUrl,
            String apkSize,
            String changelog
        ) {
            this.hasUpdate = hasUpdate;
            this.remoteVersionCode = remoteVersionCode;
            this.remoteVersionName = remoteVersionName;
            this.currentVersionName = currentVersionName;
            this.downloadUrl = downloadUrl;
            this.apkSize = apkSize;
            this.changelog = changelog;
        }
    }

    public interface Callback {
        void onResult(UpdateInfo info);
        void onError(String message);
    }

    private AppUpdateChecker() {}

    public static AcidAsync.Handle checkAsync(final Context context, final Callback callback) {
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        return AcidAsync.run(new AcidAsync.Task<UpdateInfo>() {
            @Override
            public UpdateInfo execute() throws Exception {
                String raw = AcidHttp.fetchText(UPDATE_PROBE_URL, 5000, 5000, 64 * 1024);
                JSONObject obj = new JSONObject(raw);
                int remoteCode = obj.optInt("versionCode", 0);
                String remoteName = obj.optString("versionName", "");
                String downloadUrl = obj.optString("downloadUrl", "");
                String apkSize = obj.optString("apkSize", "");
                String changelog = obj.optString("changelog", "");

                int currentCode = BuildInfo.VERSION_CODE;
                String currentName = AcidTheme.appVersionName(appCtx);
                if (appCtx != null) {
                    try {
                        PackageInfo pi = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
                        if (pi != null) currentCode = pi.versionCode;
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read PackageInfo versionCode", e);
                    }
                }
                return new UpdateInfo(
                    remoteCode > currentCode,
                    remoteCode,
                    remoteName,
                    currentName,
                    downloadUrl,
                    apkSize,
                    changelog
                );
            }
        }, new AcidAsync.Callback<UpdateInfo>() {
            @Override
            public void onSuccess(UpdateInfo result) {
                if (callback != null) callback.onResult(result);
            }

            @Override
            public void onError(Exception error) {
                Log.w(TAG, "Update probe failed", error);
                if (callback != null) callback.onError("检查更新失败，请稍后重试");
            }
        });
    }
}
