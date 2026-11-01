package com.acid.browser;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Asynchronous Update Checker for Acid Browser backed by GitHub Releases
 * ({@code AcidGr/Acid-Browser}).
 *
 * <p>Decouples network probing and version metadata parsing from {@link AboutSheet}, using
 * {@link AcidAsync} and {@link AcidHttp}.
 */
public final class AppUpdateChecker {

    private static final String TAG = "AcidUpdateChecker";
    public static final String UPDATE_PROBE_URL = "https://api.github.com/repos/AcidGr/Acid-Browser/releases/latest";
    public static final String GITHUB_RELEASES_PAGE_URL = "https://github.com/AcidGr/Acid-Browser/releases";

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

    /**
     * Parses a GitHub Release JSON payload (or legacy version.json payload) and compares versions.
     */
    public static UpdateInfo parseReleaseJson(String rawJson, String currentVersionName, int currentVersionCode) throws Exception {
        JSONObject obj = new JSONObject(rawJson);

        // 1. Extract version name from GitHub "tag_name" (e.g. "v0.4.12") or "versionName"
        String rawTag = obj.optString("tag_name", obj.optString("versionName", "")).trim();
        if (rawTag.startsWith("v") || rawTag.startsWith("V")) {
            rawTag = rawTag.substring(1).trim();
        }
        String remoteName = rawTag;

        String cleanCurrent = currentVersionName != null ? currentVersionName.trim() : BuildInfo.VERSION_NAME;
        if (cleanCurrent.startsWith("v") || cleanCurrent.startsWith("V")) {
            cleanCurrent = cleanCurrent.substring(1).trim();
        }

        // 2. Extract changelog from GitHub "body" or "changelog"
        String changelog = obj.optString("body", obj.optString("changelog", "")).trim();

        // 3. Extract APK download URL and size from GitHub "assets" array
        String downloadUrl = obj.optString("downloadUrl", "");
        String apkSize = obj.optString("apkSize", "");
        JSONArray assets = obj.optJSONArray("assets");
        if (assets != null && assets.length() > 0) {
            JSONObject bestAsset = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "").toLowerCase(Locale.US);
                if (name.endsWith(".apk")) {
                    bestAsset = a;
                    if (name.contains(remoteName.toLowerCase(Locale.US))) {
                        break; // Prefer versioned APK asset
                    }
                }
            }
            if (bestAsset != null) {
                downloadUrl = bestAsset.optString("browser_download_url", downloadUrl);
                long sizeBytes = bestAsset.optLong("size", 0L);
                if (sizeBytes > 0) {
                    apkSize = formatBytes(sizeBytes);
                }
            }
        }
        if (downloadUrl.isEmpty()) {
            downloadUrl = obj.optString("html_url", GITHUB_RELEASES_PAGE_URL);
        }

        // 4. Determine whether remote release is newer than current version
        int explicitCode = obj.optInt("versionCode", 0);
        boolean hasUpdate;
        int remoteCode;
        if (explicitCode > 0) {
            remoteCode = explicitCode;
            hasUpdate = remoteCode > currentVersionCode;
        } else {
            remoteCode = semVerToSyntheticCode(remoteName);
            hasUpdate = compareSemVer(remoteName, cleanCurrent) > 0;
        }

        return new UpdateInfo(
            hasUpdate,
            remoteCode,
            remoteName,
            cleanCurrent,
            downloadUrl,
            apkSize,
            changelog
        );
    }

    /**
     * Compares two dotted version strings (e.g. {@code "0.4.13"} vs {@code "0.4.12"}).
     *
     * @return positive if {@code v1 > v2}, negative if {@code v1 < v2}, 0 if equal.
     */
    public static int compareSemVer(String v1, String v2) {
        int[] p1 = parseVersionParts(v1);
        int[] p2 = parseVersionParts(v2);
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int a = i < p1.length ? p1[i] : 0;
            int b = i < p2.length ? p2[i] : 0;
            if (a != b) return a - b;
        }
        return 0;
    }

    private static int[] parseVersionParts(String v) {
        if (v == null || v.trim().isEmpty()) return new int[] { 0 };
        String clean = v.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        int dash = clean.indexOf('-');
        if (dash > 0) clean = clean.substring(0, dash);
        String[] segs = clean.split("\\.");
        int[] res = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            try {
                res[i] = Integer.parseInt(segs[i].replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                res[i] = 0;
            }
        }
        return res;
    }

    private static int semVerToSyntheticCode(String v) {
        int[] p = parseVersionParts(v);
        int major = p.length > 0 ? p[0] : 0;
        int minor = p.length > 1 ? p[1] : 0;
        int patch = p.length > 2 ? p[2] : 0;
        return major * 10000 + minor * 100 + patch;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return ((bytes + 512L) / 1024L) + " KB";
        }
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public static AcidAsync.Handle checkAsync(final Context context, final Callback callback) {
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        return AcidAsync.run(new AcidAsync.Task<UpdateInfo>() {
            @Override
            public UpdateInfo execute() throws Exception {
                String raw = AcidHttp.fetchText(UPDATE_PROBE_URL, 8000, 10000, 256 * 1024);

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
                return parseReleaseJson(raw, currentName, currentCode);
            }
        }, new AcidAsync.Callback<UpdateInfo>() {
            @Override
            public void onSuccess(UpdateInfo result) {
                if (callback != null) callback.onResult(result);
            }

            @Override
            public void onError(Exception error) {
                Log.w(TAG, "Update probe failed", error);
                if (callback != null) callback.onError("检查更新失败，请访问 GitHub Releases 查看");
            }
        });
    }
}
