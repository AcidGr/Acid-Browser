package com.acid.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * DSH-Inspired Model & Provider Catalog Store for Acid Browser.
 *
 * Separates two core responsibilities:
 * 1. Global Provider & Model Configuration (Bottom-right Menu -> "模型配置"):
 *    - Built-in default provider catalog (DeepSeek, OpenAI, Anthropic, Kimi, Zhipu GLM, Qwen, Gemini, OpenRouter, SiliconFlow, xAI)
 *    - Custom OpenAI-compatible provider creation & editing
 *    - Live endpoint model discovery (`GET {baseURL}/models`)
 * 2. Session/Agent Model Selector (Agent Card top-right button next to 'X'):
 *    - Selects only from already loaded/configured providers & models
 *    - Displays `Provider + Model Name`
 *    - Configures optional Reasoning Effort (思考深度: Default / Off / Low / Medium / High / Max)
 */
public class AcidModelStore {

    private static final String TAG = "AcidModelStore";

    private static final String PREF_MODELS = "acid_models_store_v2";
    private static final String KEY_PROVIDERS_JSON = "configured_providers_json";
    private static final String KEY_SELECTED_PROVIDER = "selected_provider_id";
    private static final String KEY_SELECTED_MODEL = "selected_model_id";
    private static final String KEY_SELECTED_EFFORT = "selected_reasoning_effort";
    private static final String KEY_CATALOG_VERSION = "catalog_version";
    private static final int CURRENT_CATALOG_VERSION = 4;

    // Legacy preference keys from AcidAgentLoop v1 for seamless migration
    private static final String LEGACY_PREF_AGENT = "acid_agent_config";
    private static final String LEGACY_KEY_BASE_URL = "base_url";
    private static final String LEGACY_KEY_API_KEY = "api_key";
    private static final String LEGACY_KEY_MODEL = "model_name";

    // Reasoning Effort levels (aligned with DSH dsh-llm-pi-ai & dsh-client-ui-model-selection)
    public static final String EFFORT_DEFAULT = "default";
    public static final String EFFORT_OFF = "off";
    public static final String EFFORT_LOW = "low";
    public static final String EFFORT_MEDIUM = "medium";
    public static final String EFFORT_HIGH = "high";
    public static final String EFFORT_MAX = "max";

    public static final String[] EFFORT_IDS = new String[]{
        EFFORT_DEFAULT, EFFORT_OFF, EFFORT_LOW, EFFORT_MEDIUM, EFFORT_HIGH, EFFORT_MAX
    };

    public static String getEffortDisplayName(String effortId) {
        if (EFFORT_OFF.equals(effortId)) return "关闭 (Off)";
        if (EFFORT_LOW.equals(effortId)) return "低 (Low)";
        if (EFFORT_MEDIUM.equals(effortId)) return "中 (Medium)";
        if (EFFORT_HIGH.equals(effortId)) return "高 (High)";
        if (EFFORT_MAX.equals(effortId)) return "极高 (Max)";
        return "默认 (Default)";
    }

    public static String getEffortShortLabel(String effortId) {
        if (EFFORT_OFF.equals(effortId)) return "关闭思考";
        if (EFFORT_LOW.equals(effortId)) return "思考:低";
        if (EFFORT_MEDIUM.equals(effortId)) return "思考:中";
        if (EFFORT_HIGH.equals(effortId)) return "思考:高";
        if (EFFORT_MAX.equals(effortId)) return "思考:极高";
        return "思考:默认";
    }

    /**
     * Single model descriptor inside a provider.
     */
    public static class ModelEntry {
        public String id;
        public String name;
        public boolean supportsReasoning;

        public ModelEntry(String id, String name) {
            this(id, name, true);
        }

        public ModelEntry(String id, String name, boolean supportsReasoning) {
            this.id = id != null ? id.trim() : "";
            this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : this.id;
            this.supportsReasoning = true; // All modern models natively support reasoning units
        }

        public ModelEntry copy() {
            return new ModelEntry(id, name, true);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("supportsReasoning", true);
            } catch (Exception e) {
                Log.w(TAG, "Failed to encode model entry " + id, e);
            }
            return o;
        }

        public static ModelEntry fromJson(JSONObject o) {
            String id = o.optString("id", "").trim();
            String name = o.optString("name", id).trim();
            return new ModelEntry(id, name, true);
        }
    }

    /**
     * Built-in catalog provider template (Reference: DSH dsh-llm-pi-ai PROVIDER_DATA).
     */
    public static class CatalogProviderPreset {
        public final String id;
        public final String displayName;
        public final String defaultBaseUrl;
        public final String envKeyHint;
        public final String description;
        public final List<ModelEntry> defaultModels = new ArrayList<ModelEntry>();

        public CatalogProviderPreset(String id, String displayName, String defaultBaseUrl, String envKeyHint, String description) {
            this.id = id;
            this.displayName = displayName;
            this.defaultBaseUrl = defaultBaseUrl;
            this.envKeyHint = envKeyHint;
            this.description = description;
        }

        public CatalogProviderPreset addModel(String id, String name) {
            defaultModels.add(new ModelEntry(id, name, true));
            return this;
        }

        public CatalogProviderPreset addModel(String id, String name, boolean supportsReasoning) {
            return addModel(id, name);
        }

        public ModelEntry findModel(String modelId) {
            if (modelId == null) return null;
            for (ModelEntry m : defaultModels) {
                if (modelId.equals(m.id)) return m;
            }
            return null;
        }
    }

    /**
     * A user-loaded/configured provider instance stored on device.
     */
    public static class ConfiguredProvider {
        public String id;
        public String displayName;
        public boolean isCustom;
        public String baseUrl;
        public String apiKey;
        public final List<ModelEntry> models = new ArrayList<ModelEntry>();

        public ConfiguredProvider(String id, String displayName, boolean isCustom, String baseUrl, String apiKey) {
            this.id = id != null ? id.trim() : "";
            this.displayName = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : this.id;
            this.isCustom = isCustom;
            this.baseUrl = baseUrl != null ? baseUrl.trim() : "";
            this.apiKey = apiKey != null ? apiKey.trim() : "";
        }

        public boolean hasApiKey() {
            if (!TextUtils.isEmpty(apiKey)) return true;
            return baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost");
        }

        public ModelEntry findModel(String modelId) {
            if (modelId == null) return null;
            for (ModelEntry m : models) {
                if (modelId.equals(m.id)) return m;
            }
            return null;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("displayName", displayName);
                o.put("isCustom", isCustom);
                o.put("baseUrl", baseUrl);
                o.put("apiKey", apiKey);
                JSONArray arr = new JSONArray();
                for (ModelEntry m : models) {
                    arr.put(m.toJson());
                }
                o.put("models", arr);
            } catch (Exception e) {
                Log.w(TAG, "Failed to encode provider " + id, e);
            }
            return o;
        }

        public static ConfiguredProvider fromJson(JSONObject o) {
            ConfiguredProvider p = new ConfiguredProvider(
                o.optString("id", ""),
                o.optString("displayName", ""),
                o.optBoolean("isCustom", false),
                o.optString("baseUrl", ""),
                o.optString("apiKey", "")
            );
            JSONArray arr = o.optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject mo = arr.optJSONObject(i);
                    if (mo != null) {
                        ModelEntry me = ModelEntry.fromJson(mo);
                        if (!me.id.isEmpty()) {
                            p.models.add(me);
                        }
                    }
                }
            }
            return p;
        }
    }

    private final Context mContext;
    private final List<ConfiguredProvider> mProviders = new ArrayList<ConfiguredProvider>();
    private String mSelectedProviderId = "deepseek";
    private String mSelectedModelId = "deepseek-flash";
    private String mSelectedEffort = EFFORT_DEFAULT;
    /**
     * Set when the persisted provider blob could not be decoded. Writes are refused while
     * this is true so a parse failure cannot overwrite recoverable API keys with defaults.
     */
    private boolean mStorageCorrupted = false;

    public AcidModelStore(Context context) {
        mContext = context;
        loadFromPrefs();
    }

    /**
     * Built-in provider catalog modeled after DSH's `dsh-llm-pi-ai` and `dsh-llm-deepseek`.
     */
    public static List<CatalogProviderPreset> getBuiltinCatalogPresets() {
        List<CatalogProviderPreset> list = new ArrayList<CatalogProviderPreset>();

        // 1. DeepSeek
        list.add(new CatalogProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY", ""
        )
            .addModel("deepseek-flash", "DeepSeek V4.1 Flash")
            .addModel("deepseek-v4-pro", "DeepSeek V4 Pro"));

        // 2. Kimi
        list.add(new CatalogProviderPreset(
            "moonshotai-cn", "Kimi", "https://api.moonshot.cn/v1", "MOONSHOT_API_KEY", ""
        )
            .addModel("kimi-k3", "Kimi K3")
            .addModel("kimi-k2.7-code", "Kimi K2.7 Code")
            .addModel("kimi-k2.7-code-highspeed", "Kimi K2.7 Code HighSpeed")
            .addModel("kimi-k2.6", "Kimi K2.6"));

        // 3. 阿里云百炼
        list.add(new CatalogProviderPreset(
            "qwen-cn", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", ""
        )
            .addModel("qwen3.8-max", "Qwen 3.8 Max")
            .addModel("qwen3.8-flash", "Qwen 3.8 Flash")
            .addModel("qwen3.7-max", "Qwen 3.7 Max")
            .addModel("qwen3.7-plus", "Qwen 3.7 Plus")
            .addModel("qwen3.6-plus", "Qwen 3.6 Plus"));

        // 4. 小米
        list.add(new CatalogProviderPreset(
            "xiaomi", "小米", "https://api.xiaomimimo.com/v1", "XIAOMI_API_KEY", ""
        )
            .addModel("mimo-v2.6-pro-ultraspeed", "MiMo-V2.6-Pro-UltraSpeed")
            .addModel("mimo-v2.6-pro", "MiMo-V2.6-Pro")
            .addModel("mimo-v2.6-flash", "MiMo-V2.6-Flash"));

        // 5. MiniMax
        list.add(new CatalogProviderPreset(
            "minimax-cn", "MiniMax", "https://api.minimaxi.com/v1", "MINIMAX_API_KEY", ""
        )
            .addModel("MiniMax-M3.1-Flash", "MiniMax-M3.1-Flash")
            .addModel("MiniMax-M3", "MiniMax-M3")
            .addModel("MiniMax-M2.7-highspeed", "MiniMax-M2.7-highspeed")
            .addModel("MiniMax-M2.7", "MiniMax-M2.7"));

        // 6. 硅基流动
        list.add(new CatalogProviderPreset(
            "siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "SILICONFLOW_API_KEY", ""
        )
            .addModel("deepseek-ai/DeepSeek-V4.1-Flash", "DeepSeek-V4.1-Flash")
            .addModel("deepseek-ai/DeepSeek-V4-Pro", "DeepSeek-V4-Pro")
            .addModel("Qwen/Qwen3.8-Max", "Qwen3.8-Max")
            .addModel("Qwen/Qwen3.8-Flash", "Qwen3.8-Flash")
            .addModel("THUDM/glm-5.3", "GLM-5.3")
            .addModel("MiniMax/MiniMax-M3.1-Flash", "MiniMax-M3.1-Flash"));

        return list;
    }

    public static CatalogProviderPreset findBuiltinPreset(String id) {
        if (id == null) return null;
        for (CatalogProviderPreset p : getBuiltinCatalogPresets()) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public static boolean guessSupportsReasoning(String modelId) {
        return true;
    }

    // =========================================================================
    // Persistence & Migration
    // =========================================================================

    private synchronized void loadFromPrefs() {
        SharedPreferences sp = mContext.getSharedPreferences(PREF_MODELS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_PROVIDERS_JSON, null);
        mProviders.clear();

        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        Log.w(TAG, "provider[" + i + "] is not a JSON object; skipped");
                        continue;
                    }
                    ConfiguredProvider cp = ConfiguredProvider.fromJson(o);
                    if (cp.id.isEmpty()) {
                        Log.w(TAG, "provider[" + i + "] has no id; skipped");
                        continue;
                    }
                    mProviders.add(cp);
                }
            } catch (Exception e) {
                // A corrupt provider blob must not be mistaken for "no providers configured":
                // the seeding branch below would overwrite the user's API keys.
                Log.e(TAG, "Provider config is unreadable; keeping stored data untouched", e);
                mStorageCorrupted = true;
                return;
            }
        }

        int savedVer = sp.getInt(KEY_CATALOG_VERSION, 0);

        if (mProviders.isEmpty()) {
            migrateOrSeedDefaults();
            sp.edit().putInt(KEY_CATALOG_VERSION, CURRENT_CATALOG_VERSION).apply();
        } else {
            if (savedVer < CURRENT_CATALOG_VERSION) {
                upgradeBuiltinCatalog(sp);
            }
            mSelectedProviderId = sp.getString(KEY_SELECTED_PROVIDER, mProviders.get(0).id);
            mSelectedModelId = sp.getString(KEY_SELECTED_MODEL, "");
            if ("deepseek-chat".equals(mSelectedModelId) || TextUtils.isEmpty(mSelectedModelId)) {
                mSelectedModelId = "deepseek-flash";
            }
            mSelectedEffort = sp.getString(KEY_SELECTED_EFFORT, EFFORT_DEFAULT);
            ensureValidSelection();
        }
    }

    private void upgradeBuiltinCatalog(SharedPreferences sp) {
        for (ConfiguredProvider p : mProviders) {
            if (!p.isCustom) {
                CatalogProviderPreset preset = findBuiltinPreset(p.id);
                if (preset != null) {
                    p.models.clear();
                    for (ModelEntry m : preset.defaultModels) {
                        p.models.add(m.copy());
                    }
                }
            }
        }
        if ("deepseek-chat".equals(mSelectedModelId) || "deepseek-reasoner".equals(mSelectedModelId) || TextUtils.isEmpty(mSelectedModelId)) {
            mSelectedModelId = "deepseek-flash";
        }
        sp.edit()
            .putInt(KEY_CATALOG_VERSION, CURRENT_CATALOG_VERSION)
            .putString(KEY_SELECTED_MODEL, mSelectedModelId)
            .apply();
        saveToPrefs();
    }

    private void migrateOrSeedDefaults() {
        SharedPreferences legacy = mContext.getSharedPreferences(LEGACY_PREF_AGENT, Context.MODE_PRIVATE);
        String legacyUrl = legacy.getString(LEGACY_KEY_BASE_URL, AcidAgentLoop.DEFAULT_BASE_URL);
        String legacyKey = legacy.getString(LEGACY_KEY_API_KEY, "");
        String legacyModel = legacy.getString(LEGACY_KEY_MODEL, AcidAgentLoop.DEFAULT_MODEL);

        CatalogProviderPreset dsPreset = findBuiltinPreset("deepseek");
        boolean isLegacyDeepSeek = legacyUrl == null || legacyUrl.contains("deepseek.com");

        ConfiguredProvider dsProvider = new ConfiguredProvider(
            "deepseek",
            "DeepSeek",
            false,
            isLegacyDeepSeek && !TextUtils.isEmpty(legacyUrl) ? legacyUrl : dsPreset.defaultBaseUrl,
            isLegacyDeepSeek ? legacyKey : ""
        );
        for (ModelEntry m : dsPreset.defaultModels) {
            dsProvider.models.add(m.copy());
        }
        if (isLegacyDeepSeek && !TextUtils.isEmpty(legacyModel) && dsProvider.findModel(legacyModel) == null) {
            dsProvider.models.add(0, new ModelEntry(legacyModel, legacyModel, guessSupportsReasoning(legacyModel)));
        }
        mProviders.add(dsProvider);

        if (!isLegacyDeepSeek && !TextUtils.isEmpty(legacyUrl)) {
            ConfiguredProvider customLegacy = new ConfiguredProvider(
                "custom-migrated",
                "已迁移自定义接口",
                true,
                legacyUrl,
                legacyKey
            );
            String mId = !TextUtils.isEmpty(legacyModel) ? legacyModel : "gpt-5.4";
            customLegacy.models.add(new ModelEntry(mId, mId, guessSupportsReasoning(mId)));
            mProviders.add(0, customLegacy);
            mSelectedProviderId = customLegacy.id;
            mSelectedModelId = mId;
        } else {
            mSelectedProviderId = "deepseek";
            mSelectedModelId = !TextUtils.isEmpty(legacyModel) && !"deepseek-chat".equals(legacyModel) ? legacyModel : "deepseek-flash";
        }

        mSelectedEffort = EFFORT_DEFAULT;
        ensureValidSelection();
        saveToPrefs();
    }

    private void ensureValidSelection() {
        if (mProviders.isEmpty()) return;
        ConfiguredProvider p = getProvider(mSelectedProviderId);
        if (p == null || p.models.isEmpty()) {
            for (ConfiguredProvider candidate : mProviders) {
                if (!candidate.models.isEmpty()) {
                    p = candidate;
                    mSelectedProviderId = candidate.id;
                    break;
                }
            }
        }
        if (p != null && !p.models.isEmpty()) {
            if (p.findModel(mSelectedModelId) == null) {
                mSelectedModelId = p.models.get(0).id;
            }
        }
        if (TextUtils.isEmpty(mSelectedEffort)) {
            mSelectedEffort = EFFORT_DEFAULT;
        }
    }

    public synchronized void saveToPrefs() {
        if (mStorageCorrupted) {
            // Refuse to write: the in-memory list is empty because parsing failed, not because
            // the user has no providers. Persisting it would destroy the recoverable original.
            Log.e(TAG, "Refusing to save provider config while stored data is unreadable");
            return;
        }
        JSONArray arr = new JSONArray();
        for (ConfiguredProvider p : mProviders) {
            arr.put(p.toJson());
        }
        mContext.getSharedPreferences(PREF_MODELS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDERS_JSON, arr.toString())
            .putString(KEY_SELECTED_PROVIDER, mSelectedProviderId)
            .putString(KEY_SELECTED_MODEL, mSelectedModelId)
            .putString(KEY_SELECTED_EFFORT, mSelectedEffort)
            .apply();

        // Keep legacy preferences synced for any external caller
        ConfiguredProvider activeP = getActiveProvider();
        ModelEntry activeM = getActiveModel();
        if (activeP != null && activeM != null) {
            mContext.getSharedPreferences(LEGACY_PREF_AGENT, Context.MODE_PRIVATE)
                .edit()
                .putString(LEGACY_KEY_BASE_URL, activeP.baseUrl)
                .putString(LEGACY_KEY_API_KEY, activeP.apiKey)
                .putString(LEGACY_KEY_MODEL, activeM.id)
                .apply();
        }
    }

    // =========================================================================
    // Public Query & Mutation API
    // =========================================================================

    public synchronized List<ConfiguredProvider> getProviders() {
        return new ArrayList<ConfiguredProvider>(mProviders);
    }

    public synchronized ConfiguredProvider getProvider(String providerId) {
        if (providerId == null) return null;
        for (ConfiguredProvider p : mProviders) {
            if (providerId.equals(p.id)) return p;
        }
        return null;
    }

    public synchronized int getTotalLoadedModelsCount() {
        int count = 0;
        for (ConfiguredProvider p : mProviders) {
            count += p.models.size();
        }
        return count;
    }

    public synchronized ConfiguredProvider getActiveProvider() {
        ensureValidSelection();
        ConfiguredProvider p = getProvider(mSelectedProviderId);
        if (p == null && !mProviders.isEmpty()) {
            p = mProviders.get(0);
        }
        return p;
    }

    public synchronized ModelEntry getActiveModel() {
        ConfiguredProvider p = getActiveProvider();
        if (p == null || p.models.isEmpty()) return null;
        ModelEntry m = p.findModel(mSelectedModelId);
        if (m == null) {
            m = p.models.get(0);
        }
        return m;
    }

    public synchronized String getSelectedReasoningEffort() {
        return mSelectedEffort != null ? mSelectedEffort : EFFORT_DEFAULT;
    }

    public synchronized void selectModel(String providerId, String modelId, String reasoningEffort) {
        ConfiguredProvider p = getProvider(providerId);
        if (p != null && p.findModel(modelId) != null) {
            mSelectedProviderId = providerId;
            mSelectedModelId = modelId;
            if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
                mSelectedEffort = reasoningEffort;
            }
            saveToPrefs();
        }
    }

    public synchronized void setReasoningEffort(String reasoningEffort) {
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            mSelectedEffort = reasoningEffort;
            saveToPrefs();
        }
    }

    public synchronized void upsertProvider(ConfiguredProvider provider) {
        if (provider == null || TextUtils.isEmpty(provider.id)) return;
        int idx = -1;
        for (int i = 0; i < mProviders.size(); i++) {
            if (mProviders.get(i).id.equals(provider.id)) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            mProviders.set(idx, provider);
        } else {
            // Put DeepSeek first, otherwise append
            if ("deepseek".equals(provider.id)) {
                mProviders.add(0, provider);
            } else {
                mProviders.add(provider);
            }
        }
        ensureValidSelection();
        saveToPrefs();
    }

    public synchronized void deleteProvider(String providerId) {
        if (providerId == null) return;
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            if (providerId.equals(mProviders.get(i).id)) {
                mProviders.remove(i);
            }
        }
        ensureValidSelection();
        saveToPrefs();
    }

    /**
     * Formatted string for Agent Card subtitle & Selector header:
     * Shows "供应商 + 模型名称" + optional "思考深度"
     */
    public synchronized String getStatusDisplayText() {
        ConfiguredProvider p = getActiveProvider();
        ModelEntry m = getActiveModel();
        if (p == null || m == null) {
            return "未装载模型 · 请在功能-模型配置中添加";
        }
        String base = p.displayName + " / " + m.name;
        if (!EFFORT_DEFAULT.equals(mSelectedEffort)) {
            return base + " · " + getEffortShortLabel(mSelectedEffort);
        }
        return base;
    }

    // =========================================================================
    // Live Endpoint Model Discovery (`GET {baseURL}/models` per DSH discovery.ts)
    // =========================================================================

    public interface ModelDiscoveryCallback {
        void onModelsDiscovered(List<ModelEntry> models);
        void onDiscoveryFailed(String errorMsg);
    }

    public static void discoverModelsAsync(final String baseUrl, final String apiKey, final ModelDiscoveryCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<ModelEntry> list = fetchModelsSync(baseUrl, apiKey);
                    if (callback != null) {
                        callback.onModelsDiscovered(list);
                    }
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onDiscoveryFailed(e.getMessage() != null ? e.getMessage() : e.toString());
                    }
                }
            }
        }, "AcidModelDiscoveryThread").start();
    }

    private static List<ModelEntry> fetchModelsSync(String rawBaseUrl, String apiKey) throws Exception {
        if (rawBaseUrl == null || rawBaseUrl.trim().isEmpty()) {
            throw new Exception("请先填写 API 地址 (Base URL)");
        }
        String base = rawBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        String url = base.endsWith("/models") ? base : (base + "/models");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String respStr = readStreamUtf8(is);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + (code == 401 || code == 403 ? " (请检查 API Key)" : "") + ": " +
                (respStr.length() > 160 ? respStr.substring(0, 160) : respStr));
        }

        JSONObject root = new JSONObject(respStr);
        List<ModelEntry> result = new ArrayList<ModelEntry>();

        JSONArray dataArr = root.optJSONArray("data");
        if (dataArr != null) {
            for (int i = 0; i < dataArr.length(); i++) {
                JSONObject item = dataArr.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                if (id.isEmpty()) continue;
                String name = item.optString("name", item.optString("display_name", id)).trim();
                if (name.isEmpty()) name = id;
                result.add(new ModelEntry(id, name, guessSupportsReasoning(id)));
            }
        } else {
            JSONObject modelsObj = root.optJSONObject("models");
            if (modelsObj != null) {
                Iterator<String> keys = modelsObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject item = modelsObj.optJSONObject(key);
                    String id = (key != null && !key.isEmpty()) ? key : (item != null ? item.optString("id", "") : "");
                    if (id.isEmpty()) continue;
                    String name = item != null ? item.optString("name", item.optString("displayName", id)) : id;
                    result.add(new ModelEntry(id, name, guessSupportsReasoning(id)));
                }
            }
        }

        if (result.isEmpty()) {
            throw new Exception("该端点未返回任何可用模型列表，请手动添加模型 ID");
        }
        return result;
    }

    private static String readStreamUtf8(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        is.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
