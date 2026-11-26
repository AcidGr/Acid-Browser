package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Repository managing persisted open-tab snapshots for startup restoration.
 *
 * <p>Stores tab metadata ({@code url}, {@code title}, {@code isDesktopMode}, and {@code activeIndex})
 * in {@link SharedPreferences} only — isolated from {@code settings.json} and backup archives.
 * Deduplicates unchanged writes and performs per-entry sanitization and corrupt-record recovery.
 */
public class TabSessionRepository {

    private static final String TAG = "AcidTabSessionRepo";
    public static final String PREF_SAVED_TABS = "saved_tabs_session";
    public static final String PREF_NAME = BrowserDataStore.PREF_NAME;

    public static final class SavedTabEntry {
        public final String url;
        public final String title;
        public final boolean isDesktopMode;

        public SavedTabEntry(String url, String title, boolean isDesktopMode) {
            this.url = url != null ? url : "";
            this.title = title != null ? title : "";
            this.isDesktopMode = isDesktopMode;
        }
    }

    public static final class SavedTabSession {
        public final List<SavedTabEntry> tabs;
        public final int activeIndex;

        public SavedTabSession(List<SavedTabEntry> tabs, int activeIndex) {
            List<SavedTabEntry> copy = tabs != null
                ? new ArrayList<SavedTabEntry>(tabs)
                : new ArrayList<SavedTabEntry>();
            this.tabs = Collections.unmodifiableList(copy);
            if (this.tabs.isEmpty()) {
                this.activeIndex = 0;
            } else {
                this.activeIndex = Math.max(0, Math.min(activeIndex, this.tabs.size() - 1));
            }
        }
    }

    private final Context mContext;
    private final SharedPreferences mPrefsOverride;
    private final Object mLock = new Object();

    public TabSessionRepository(Context context, SharedPreferences prefsOverride) {
        mContext = context != null ? context.getApplicationContext() : null;
        mPrefsOverride = prefsOverride;
    }

    private SharedPreferences prefs() {
        if (mPrefsOverride != null) return mPrefsOverride;
        if (mContext != null) return mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        throw new IllegalStateException("Neither SharedPreferences override nor Context provided to TabSessionRepository");
    }

    /**
     * Validates and normalizes a tab URL for persistence/restoration across restarts.
     * Filters out transient or unsafe schemes, blocks {@code file://} escapes outside the
     * workspace, drops deleted local workspace files, and normalizes local homepage aliases.
     */
    public static String sanitizeRestorableTabUrl(
        String rawUrl,
        File workspaceDir,
        boolean allowAboutBlank,
        String effectiveHomeUrl
    ) {
        if (rawUrl == null) return null;
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('\0') >= 0) return null;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")
            || lower.startsWith("data:")
            || lower.startsWith("blob:")
            || lower.startsWith("intent:")
            || lower.startsWith("content:")
            || lower.startsWith("vbscript:")) {
            return null;
        }

        if ("about:blank".equals(lower)) {
            return allowAboutBlank ? "about:blank" : null;
        }

        String fallbackHome = (effectiveHomeUrl != null && !effectiveHomeUrl.isEmpty())
            ? effectiveHomeUrl
            : BrowserController.HOME_URL;

        if ("acid://home".equals(lower)
            || "about:home".equals(lower)
            || BrowserController.HOME_URL.equals(trimmed)) {
            return fallbackHome;
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return trimmed;
        }

        if (lower.startsWith("file://")) {
            String rawPath = trimmed.substring("file://".length());
            int qIdx = rawPath.indexOf('?');
            if (qIdx >= 0) rawPath = rawPath.substring(0, qIdx);
            int hIdx = rawPath.indexOf('#');
            if (hIdx >= 0) rawPath = rawPath.substring(0, hIdx);
            String decodedPath;
            try {
                // Preserve literal '+' in file:// paths instead of treating it as form-encoded space
                decodedPath = java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                return null;
            }
            if (decodedPath.indexOf('\0') >= 0 || decodedPath.indexOf('\\') >= 0) {
                return null;
            }
            String lowerPath = decodedPath.toLowerCase(Locale.ROOT);
            if (lowerPath.startsWith("/android_asset/") || lowerPath.startsWith("/android_res/")) {
                String[] segs = decodedPath.split("/");
                for (int i = 0; i < segs.length; i++) {
                    if (".".equals(segs[i]) || "..".equals(segs[i])) {
                        return null;
                    }
                }
                return trimmed;
            }
            if (workspaceDir == null) {
                return null;
            }
            try {
                File wsCanon = workspaceDir.getCanonicalFile();
                File targetCanon = new File(decodedPath).getCanonicalFile();
                String wsPrefix = wsCanon.getPath();
                if (!wsPrefix.endsWith(File.separator)) {
                    wsPrefix = wsPrefix + File.separator;
                }
                if (!targetCanon.getPath().startsWith(wsPrefix)) {
                    return null;
                }
                File wsHomeCanon = new File(new File(wsCanon, "homepage"), "index.html").getCanonicalFile();
                if (targetCanon.equals(wsHomeCanon)) {
                    return fallbackHome;
                }
                if (targetCanon.exists() && targetCanon.isFile()) {
                    return trimmed;
                }
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }

        return null;
    }

    /**
     * Filters invalid entries, remaps {@code activeIndex}, and caps to {@link BrowserController#MAX_TABS}
     * while preserving the window containing the active tab.
     */
    public static SavedTabSession normalizeSession(
        List<SavedTabEntry> rawEntries,
        int rawActiveIndex,
        File workspaceDir,
        boolean allowAboutBlank,
        String effectiveHomeUrl
    ) {
        if (rawEntries == null || rawEntries.isEmpty()) {
            return null;
        }
        int clampedRawActive = Math.max(0, Math.min(rawActiveIndex, rawEntries.size() - 1));
        int mappedActive = -1;
        List<SavedTabEntry> validList = new ArrayList<SavedTabEntry>();

        for (int i = 0; i < rawEntries.size(); i++) {
            SavedTabEntry entry = rawEntries.get(i);
            if (entry == null) continue;
            try {
                String safeUrl = sanitizeRestorableTabUrl(
                    entry.url, workspaceDir, allowAboutBlank, effectiveHomeUrl
                );
                if (safeUrl == null) continue;
                validList.add(new SavedTabEntry(safeUrl, entry.title, entry.isDesktopMode));
                if (i <= clampedRawActive) {
                    mappedActive = validList.size() - 1;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to normalize saved tab[" + i + "]; skipped", e);
            }
        }

        if (validList.isEmpty()) {
            return null;
        }
        int active = Math.max(0, mappedActive);
        List<SavedTabEntry> capped = validList;
        if (validList.size() > BrowserController.MAX_TABS) {
            if (active < BrowserController.MAX_TABS) {
                capped = new ArrayList<SavedTabEntry>(validList.subList(0, BrowserController.MAX_TABS));
            } else {
                int start = active - BrowserController.MAX_TABS + 1;
                capped = new ArrayList<SavedTabEntry>(validList.subList(start, active + 1));
                active = BrowserController.MAX_TABS - 1;
            }
        }
        return new SavedTabSession(capped, active);
    }

    public void saveTabSession(
        List<SavedTabEntry> entries,
        int activeIndex,
        File workspaceDir,
        boolean allowAboutBlank,
        String effectiveHomeUrl
    ) {
        SavedTabSession normalized = normalizeSession(
            entries, activeIndex, workspaceDir, allowAboutBlank, effectiveHomeUrl
        );
        if (normalized == null) {
            clearTabSession();
            return;
        }

        JSONArray arr = new JSONArray();
        for (int i = 0; i < normalized.tabs.size(); i++) {
            SavedTabEntry entry = normalized.tabs.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("url", entry.url);
                o.put("title", entry.title);
                o.put("desktop", entry.isDesktopMode);
                arr.put(o);
            } catch (Exception e) {
                Log.w(TAG, "Failed to encode saved tab[" + i + "]; skipped", e);
            }
        }

        if (arr.length() == 0) {
            clearTabSession();
            return;
        }

        JSONObject root = new JSONObject();
        try {
            root.put("activeIndex", Math.max(0, Math.min(normalized.activeIndex, arr.length() - 1)));
            root.put("tabs", arr);
        } catch (Exception e) {
            Log.w(TAG, "Failed to encode tab session root", e);
            return;
        }

        String nextJson = root.toString();
        synchronized (mLock) {
            SharedPreferences p = prefs();
            String existing = p.getString(PREF_SAVED_TABS, null);
            if (nextJson.equals(existing)) {
                return;
            }
            p.edit().putString(PREF_SAVED_TABS, nextJson).apply();
        }
    }

    public SavedTabSession loadTabSession(
        File workspaceDir,
        boolean allowAboutBlank,
        String effectiveHomeUrl
    ) {
        final String raw;
        synchronized (mLock) {
            raw = prefs().getString(PREF_SAVED_TABS, null);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("tabs");
            if (arr == null || arr.length() == 0) {
                return null;
            }
            int rawActiveIndex = root.optInt("activeIndex", 0);
            List<SavedTabEntry> rawEntries = new ArrayList<SavedTabEntry>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                SavedTabEntry parsed = null;
                try {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        Log.w(TAG, "savedTab[" + i + "] is not a JSON object; skipped");
                    } else {
                        parsed = new SavedTabEntry(
                            o.optString("url", ""),
                            o.optString("title", ""),
                            o.optBoolean("desktop", false)
                        );
                    }
                } catch (Exception e) {
                    Log.w(TAG, "savedTab[" + i + "] unreadable; skipped", e);
                }
                rawEntries.add(parsed);
            }
            return normalizeSession(
                rawEntries, rawActiveIndex, workspaceDir, allowAboutBlank, effectiveHomeUrl
            );
        } catch (Exception e) {
            Log.w(TAG, "Saved tab session unreadable; returning null", e);
            return null;
        }
    }

    public void clearTabSession() {
        synchronized (mLock) {
            SharedPreferences p = prefs();
            if (p.contains(PREF_SAVED_TABS)) {
                p.edit().remove(PREF_SAVED_TABS).apply();
            }
        }
    }
}
