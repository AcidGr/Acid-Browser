package com.acid.browser;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative Single-Source-of-Truth Registry for all persisted browser settings.
 *
 * <p>Each setting is declared once here with its {@code SharedPreferences} key, optional
 * {@code settings.json} key, default value, UI display title, and JSON serialization codec.
 * {@link BrowserDataStore} iterates over {@link #ALL_KEYS} for export, live hot-reload import,
 * observer dispatch, and {@link AcidAgentLoop} prompt schema generation — eliminating manual
 * per-setting getters/setters and prompt drift.
 */
public final class AcidSettings {

    public interface KeyObserver<T> {
        void onSettingChanged(T newValue);
    }

    public abstract static class SettingKey<T> {
        public final String prefKey;
        public final String jsonKey;
        public final T defaultValue;
        public final String title;
        public final boolean exportToJson;

        SettingKey(String prefKey, String jsonKey, T defaultValue, String title, boolean exportToJson) {
            this.prefKey = prefKey;
            this.jsonKey = jsonKey;
            this.defaultValue = defaultValue;
            this.title = title != null ? title : "";
            this.exportToJson = exportToJson && jsonKey != null && !jsonKey.isEmpty();
        }

        public abstract T read(SharedPreferences primary, SharedPreferences legacy);
        public abstract void write(SharedPreferences.Editor editor, T value);
        public abstract void writeToJson(JSONObject out, T value) throws JSONException;
        public abstract T parseFromJson(Object rawJsonVal);
        public abstract String schemaTypeDescription();

        public T sanitize(T candidate) {
            return candidate != null ? candidate : defaultValue;
        }
    }

    public static final class BoolKey extends SettingKey<Boolean> {
        private final boolean mMigrateFromLegacy;

        BoolKey(String prefKey, String jsonKey, boolean defaultValue, String title, boolean exportToJson, boolean migrateFromLegacy) {
            super(prefKey, jsonKey, Boolean.valueOf(defaultValue), title, exportToJson);
            mMigrateFromLegacy = migrateFromLegacy;
        }

        @Override
        public Boolean read(SharedPreferences primary, SharedPreferences legacy) {
            if (primary.contains(prefKey)) {
                return Boolean.valueOf(primary.getBoolean(prefKey, defaultValue.booleanValue()));
            }
            if (mMigrateFromLegacy && legacy != null && legacy.contains(prefKey)) {
                boolean migrated = legacy.getBoolean(prefKey, defaultValue.booleanValue());
                primary.edit().putBoolean(prefKey, migrated).apply();
                return Boolean.valueOf(migrated);
            }
            return defaultValue;
        }

        @Override
        public void write(SharedPreferences.Editor editor, Boolean value) {
            editor.putBoolean(prefKey, sanitize(value).booleanValue());
        }

        @Override
        public void writeToJson(JSONObject out, Boolean value) throws JSONException {
            out.put(jsonKey, sanitize(value).booleanValue());
        }

        @Override
        public Boolean parseFromJson(Object raw) {
            if (raw instanceof Boolean) return (Boolean) raw;
            if (raw != null) {
                String s = String.valueOf(raw).trim();
                if ("true".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(s) || "0".equals(s)) return Boolean.FALSE;
            }
            return defaultValue;
        }

        @Override
        public String schemaTypeDescription() {
            return "boolean";
        }
    }

    public static final class IntKey extends SettingKey<Integer> {
        IntKey(String prefKey, String jsonKey, int defaultValue, String title, boolean exportToJson) {
            super(prefKey, jsonKey, Integer.valueOf(defaultValue), title, exportToJson);
        }

        @Override
        public Integer read(SharedPreferences primary, SharedPreferences legacy) {
            return Integer.valueOf(primary.getInt(prefKey, defaultValue.intValue()));
        }

        @Override
        public void write(SharedPreferences.Editor editor, Integer value) {
            editor.putInt(prefKey, sanitize(value).intValue());
        }

        @Override
        public void writeToJson(JSONObject out, Integer value) throws JSONException {
            out.put(jsonKey, sanitize(value).intValue());
        }

        @Override
        public Integer parseFromJson(Object raw) {
            if (raw instanceof Number) return Integer.valueOf(((Number) raw).intValue());
            if (raw != null) {
                try { return Integer.valueOf(Integer.parseInt(String.valueOf(raw).trim())); }
                catch (NumberFormatException ignored) {}
            }
            return defaultValue;
        }

        @Override
        public String schemaTypeDescription() {
            return "integer";
        }
    }

    public static final class StringKey extends SettingKey<String> {
        private final boolean mTrimOnWrite;

        StringKey(String prefKey, String jsonKey, String defaultValue, String title, boolean exportToJson, boolean trimOnWrite) {
            super(prefKey, jsonKey, defaultValue != null ? defaultValue : "", title, exportToJson);
            mTrimOnWrite = trimOnWrite;
        }

        @Override
        public String sanitize(String candidate) {
            if (candidate == null) return "";
            return mTrimOnWrite ? candidate.trim() : candidate;
        }

        @Override
        public String read(SharedPreferences primary, SharedPreferences legacy) {
            String val = primary.getString(prefKey, defaultValue);
            return val != null ? val : defaultValue;
        }

        @Override
        public void write(SharedPreferences.Editor editor, String value) {
            editor.putString(prefKey, sanitize(value));
        }

        @Override
        public void writeToJson(JSONObject out, String value) throws JSONException {
            out.put(jsonKey, sanitize(value));
        }

        @Override
        public String parseFromJson(Object raw) {
            if (raw == null || raw == JSONObject.NULL) return "";
            return sanitize(String.valueOf(raw));
        }

        @Override
        public String schemaTypeDescription() {
            return "string";
        }
    }

    /**
     * Integer-indexed enum setting stored as an {@code int} in {@code SharedPreferences}
     * (preserving backward compatibility) and exported as a readable code string in {@code settings.json}.
     */
    public static final class EnumIndexKey extends SettingKey<Integer> {
        public final String[] codes;
        public final String[] displayLabels;
        private final String[] mAliasPairs;

        EnumIndexKey(
            String prefKey,
            String jsonKey,
            int defaultIndex,
            String title,
            String[] codes,
            String[] displayLabels,
            String[] aliasPairs
        ) {
            super(prefKey, jsonKey, Integer.valueOf(defaultIndex), title, true);
            this.codes = codes;
            this.displayLabels = displayLabels;
            this.mAliasPairs = aliasPairs != null ? aliasPairs : new String[0];
        }

        @Override
        public Integer sanitize(Integer candidate) {
            if (candidate == null) return defaultValue;
            int idx = candidate.intValue();
            if (idx < 0 || idx >= codes.length) return defaultValue;
            return candidate;
        }

        public String indexToCode(int idx) {
            return (idx >= 0 && idx < codes.length) ? codes[idx] : codes[defaultValue.intValue()];
        }

        public String indexToLabel(int idx) {
            return (idx >= 0 && idx < displayLabels.length) ? displayLabels[idx] : displayLabels[defaultValue.intValue()];
        }

        public int codeToIndex(String rawCode) {
            if (rawCode == null) return defaultValue.intValue();
            String norm = rawCode.trim().toLowerCase();
            for (int i = 0; i < codes.length; i++) {
                if (codes[i].equalsIgnoreCase(norm)) return i;
            }
            for (int i = 0; i + 1 < mAliasPairs.length; i += 2) {
                if (mAliasPairs[i].equalsIgnoreCase(norm)) {
                    return codeToIndex(mAliasPairs[i + 1]);
                }
            }
            return defaultValue.intValue();
        }

        @Override
        public Integer read(SharedPreferences primary, SharedPreferences legacy) {
            return sanitize(Integer.valueOf(primary.getInt(prefKey, defaultValue.intValue())));
        }

        @Override
        public void write(SharedPreferences.Editor editor, Integer value) {
            editor.putInt(prefKey, sanitize(value).intValue());
        }

        @Override
        public void writeToJson(JSONObject out, Integer value) throws JSONException {
            out.put(jsonKey, indexToCode(sanitize(value).intValue()));
        }

        @Override
        public Integer parseFromJson(Object raw) {
            if (raw instanceof Number) {
                return sanitize(Integer.valueOf(((Number) raw).intValue()));
            }
            return Integer.valueOf(codeToIndex(raw != null ? String.valueOf(raw) : null));
        }

        @Override
        public String schemaTypeDescription() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < codes.length; i++) {
                if (i > 0) sb.append('|');
                sb.append('\'').append(codes[i]).append('\'');
            }
            return sb.toString();
        }
    }

    // =====================================================================
    // Registered Browser Settings (Single Source of Truth)
    // =====================================================================

    private static final List<SettingKey<?>> MUTABLE_REGISTRY = new ArrayList<SettingKey<?>>();

    private static <K extends SettingKey<?>> K register(K key) {
        MUTABLE_REGISTRY.add(key);
        return key;
    }

    public static final EnumIndexKey SEARCH_ENGINE = register(new EnumIndexKey(
        "search_engine",
        "search_engine",
        0,
        "默认搜索引擎",
        new String[]{"google", "baidu", "bing", "duckduckgo", "custom"},
        new String[]{"Google", "百度 (Baidu)", "必应 (Bing)", "DuckDuckGo", "自定义"},
        new String[]{"ddg", "duckduckgo"}
    ));

    public static final StringKey SEARCH_ENGINE_CUSTOM_URL = register(new StringKey(
        "search_engine_custom",
        "search_engine_custom_url",
        "",
        "自定义搜索引擎地址",
        true,
        true
    ));

    public static final EnumIndexKey HOME_TYPE = register(new EnumIndexKey(
        "home_type",
        "home_type",
        0,
        "主页设置",
        new String[]{"acid", "blank", "custom"},
        new String[]{"Acid 默认首页", "空白页 (about:blank)", "自定义网址"},
        new String[]{"about:blank", "blank"}
    ));

    public static final StringKey HOME_CUSTOM_URL = register(new StringKey(
        "home_custom_url",
        "home_custom_url",
        "",
        "自定义主页网址",
        true,
        true
    ));

    public static final int DOWNLOADER_BUILTIN = 0;
    public static final int DOWNLOADER_SYSTEM = 1;

    public static final EnumIndexKey DOWNLOADER_ENGINE = register(new EnumIndexKey(
        "downloader_engine",
        "downloader_engine",
        0,
        "下载引擎",
        new String[]{"builtin", "system"},
        new String[]{"内置下载器", "系统下载器"},
        new String[]{"internal", "builtin", "native", "system"}
    ));

    public static final BoolKey BLOCK_IMAGES = register(new BoolKey(
        "block_images", "block_images", false, "无图省流模式", true, false
    ));

    public static final BoolKey DO_NOT_TRACK = register(new BoolKey(
        "do_not_track", "do_not_track", false, "不跟踪 (DNT)", true, false
    ));

    public static final BoolKey WEB_DEBUGGING = register(new BoolKey(
        "web_debugging", "web_debugging", false, "网页远程调试", true, false
    ));

    public static final BoolKey CLEAR_ON_EXIT = register(new BoolKey(
        "clear_on_exit", "clear_on_exit", false, "退出时自动清理", true, false
    ));

    public static final BoolKey DARK_MODE = register(new BoolKey(
        "dark_mode", "dark_mode", false, "夜间模式", true, false
    ));

    public static final BoolKey ADBLOCK_ENABLED = register(new BoolKey(
        "adblock_enabled", "adblock_enabled", true, "广告拦截", true, false
    ));

    public static final BoolKey MCP_ENABLED = register(new BoolKey(
        "mcp_server_enabled", "mcp_server_enabled", false, "本地 MCP 服务", true, true
    ));

    public static final BoolKey MCP_AUTH_ENABLED = register(new BoolKey(
        "mcp_auth_enabled", "mcp_auth_enabled", true, "MCP 令牌认证", true, false
    ));

    public static final BoolKey MCP_LAN_ENABLED = register(new BoolKey(
        "mcp_lan_enabled", "mcp_lan_enabled", false, "MCP 允许局域网连接", true, false
    ));

    // Internal persisted state (not exported to settings.json)
    public static final StringKey MCP_AUTH_TOKEN = register(new StringKey(
        "mcp_auth_token", null, "", "MCP 访问令牌", false, true
    ));

    // Internal persisted state (not exported to settings.json)
    public static final IntKey ADBLOCK_BLOCKED_COUNT = register(new IntKey(
        "adblock_blocked_count", null, 0, "累计拦截次数", false
    ));

    public static final StringKey ADBLOCK_SUBSCRIPTION_URL = register(new StringKey(
        "adblock_sub_url",
        null,
        "https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-easylist.txt",
        "订阅规则地址",
        false,
        true
    ));

    public static final List<SettingKey<?>> ALL_KEYS = Collections.unmodifiableList(MUTABLE_REGISTRY);

    /**
     * Generates the authoritative {@code settings.json} key/type specification directly from
     * registered keys so the Agent system prompt can never drift from actual storage.
     */
    public static String describeJsonSchemaForPrompt() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < ALL_KEYS.size(); i++) {
            SettingKey<?> k = ALL_KEYS.get(i);
            if (!k.exportToJson) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append('`').append(k.jsonKey).append("`: ").append(k.schemaTypeDescription());
        }
        return sb.toString();
    }

    private AcidSettings() {}
}
