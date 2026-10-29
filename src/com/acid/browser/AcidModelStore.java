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
    private static final int CURRENT_CATALOG_VERSION = 3;

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

        public ModelEntry(String id, String name, boolean supportsReasoning) {
            this.id = id != null ? id.trim() : "";
            this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : this.id;
            this.supportsReasoning = supportsReasoning;
        }

        public ModelEntry copy() {
            return new ModelEntry(id, name, supportsReasoning);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("supportsReasoning", supportsReasoning);
            } catch (Exception e) {
                Log.w(TAG, "Failed to encode model entry " + id, e);
            }
            return o;
        }

        public static ModelEntry fromJson(JSONObject o) {
            String id = o.optString("id", "").trim();
            String name = o.optString("name", id).trim();
            boolean reasoning = o.optBoolean("supportsReasoning", guessSupportsReasoning(id));
            return new ModelEntry(id, name, reasoning);
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

        public CatalogProviderPreset addModel(String id, String name, boolean supportsReasoning) {
            defaultModels.add(new ModelEntry(id, name, supportsReasoning));
            return this;
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

        list.add(new CatalogProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY",
            "DeepSeek 官方 API，包含 V4/V4.1 系列与 R1 深度思考模型"
        )
            .addModel("deepseek-flash", "DeepSeek-V4.1-Flash", true)
            .addModel("deepseek-v4-pro", "DeepSeek-V4-Pro", true)
            .addModel("deepseek-chat", "DeepSeek-V3 (Chat)", false)
            .addModel("deepseek-reasoner", "DeepSeek-R1 (Reasoner)", true));

        list.add(new CatalogProviderPreset(
            "openai", "OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY",
            "OpenAI 官方接口，支持 GPT-5.4、GPT-5.2 及 o3/o4 推理系列"
        )
            .addModel("gpt-5.4", "GPT-5.4", true)
            .addModel("gpt-5.4-mini", "GPT-5.4 mini", true)
            .addModel("gpt-5.2", "GPT-5.2", true)
            .addModel("o4-mini", "OpenAI o4-mini", true)
            .addModel("o3", "OpenAI o3", true)
            .addModel("gpt-4.1", "GPT-4.1", false)
            .addModel("gpt-4o", "GPT-4o", false));

        list.add(new CatalogProviderPreset(
            "anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", "ANTHROPIC_API_KEY",
            "Anthropic 官方或兼容网关，支持 Claude 4.5 / 5 Sonnet 与扩展思考"
        )
            .addModel("claude-sonnet-4-5", "Claude Sonnet 4.5", true)
            .addModel("claude-haiku-4-5", "Claude Haiku 4.5", true)
            .addModel("claude-opus-4-5", "Claude Opus 4.5", true)
            .addModel("claude-sonnet-4-6", "Claude Sonnet 4.6", true)
            .addModel("claude-sonnet-5", "Claude Sonnet 5", true)
            .addModel("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet", true));

        list.add(new CatalogProviderPreset(
            "google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "GEMINI_API_KEY",
            "Google AI Studio 兼容端点，支持 Gemini 3.8 / 3.5 Flash 与 Gemini 4 系列"
        )
            .addModel("gemini-3.8-flash", "Gemini 3.8 Flash", true)
            .addModel("gemini-3.5-flash", "Gemini 3.5 Flash", true)
            .addModel("gemini-4-argon", "Gemini 4 Argon", true)
            .addModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", true)
            .addModel("gemini-2.5-pro", "Gemini 2.5 Pro", true)
            .addModel("gemini-2.5-flash", "Gemini 2.5 Flash", true));

        list.add(new CatalogProviderPreset(
            "moonshotai-cn", "Kimi (月之暗面)", "https://api.moonshot.cn/v1", "MOONSHOT_API_KEY",
            "Moonshot AI 官方接口，支持 Kimi K3 / K2.7 Code 深度思考与长上下文"
        )
            .addModel("kimi-k3", "Kimi K3", true)
            .addModel("kimi-k2.7-code", "Kimi K2.7 Code", true)
            .addModel("kimi-k2.7-code-highspeed", "Kimi K2.7 Code HighSpeed", true)
            .addModel("kimi-k2.6", "Kimi K2.6", true));

        list.add(new CatalogProviderPreset(
            "zai-coding-cn", "智谱 GLM (BigModel)", "https://open.bigmodel.cn/api/paas/v4", "ZAI_CODING_CN_API_KEY",
            "智谱清言开放平台，支持 GLM-5.3、GLM-5.3 Flash 深度推理与视觉模型"
        )
            .addModel("glm-5.3", "GLM-5.3", true)
            .addModel("glm-5.3-flash", "GLM-5.3-Flash", true)
            .addModel("glm-5.3-highspeed", "GLM-5.3 Highspeed", true)
            .addModel("glm-4.6v", "GLM-4.6V (Vision)", true));

        list.add(new CatalogProviderPreset(
            "qwen-cn", "阿里云百炼 (Qwen)", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY",
            "阿里云通义千问兼容接口，支持 Qwen 3.8 Max/Flash、Qwen 3.7 Plus 与 QwQ"
        )
            .addModel("qwen3.8-max", "Qwen 3.8 Max", true)
            .addModel("qwen3.8-flash", "Qwen 3.8 Flash", true)
            .addModel("qwen3.7-plus", "Qwen 3.7 Plus", true)
            .addModel("qwen3.7-max", "Qwen 3.7 Max", true)
            .addModel("qwen-max", "Qwen-Max", false)
            .addModel("qwq-plus", "QwQ-Plus (深度思考)", true));

        list.add(new CatalogProviderPreset(
            "xai", "xAI (Grok)", "https://api.x.ai/v1", "XAI_API_KEY",
            "xAI 官方接口，支持 Grok 4.7、Grok 4.6 与 Grok 4.5 系列"
        )
            .addModel("grok-4.7", "Grok 4.7", true)
            .addModel("grok-4.6", "Grok 4.6", true)
            .addModel("grok-4.5", "Grok 4.5", true)
            .addModel("grok-4.3", "Grok 4.3", true)
            .addModel("grok-3", "Grok 3", false));

        list.add(new CatalogProviderPreset(
            "siliconflow", "硅基流动 (SiliconFlow)", "https://api.siliconflow.cn/v1", "SILICONFLOW_API_KEY",
            "国内高速聚合推理平台，内置满血 DeepSeek-V4.1 / V4-Pro 与 Qwen 系列"
        )
            .addModel("deepseek-ai/DeepSeek-V4.1-Flash", "DeepSeek-V4.1-Flash", true)
            .addModel("deepseek-ai/DeepSeek-V4-Pro", "DeepSeek-V4-Pro", true)
            .addModel("Qwen/Qwen3.8-Max", "Qwen3.8-Max", true)
            .addModel("deepseek-ai/DeepSeek-V3", "DeepSeek-V3", false)
            .addModel("deepseek-ai/DeepSeek-R1", "DeepSeek-R1", true));

        list.add(new CatalogProviderPreset(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
            "全球多模型聚合路由，统一接入 Claude 4.5 / GPT-5.4 / Gemini 3.8 / DeepSeek V4.1"
        )
            .addModel("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", true)
            .addModel("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5", true)
            .addModel("google/gemini-3.8-flash", "Gemini 3.8 Flash", true)
            .addModel("openai/gpt-5.4", "GPT-5.4", true)
            .addModel("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro", true)
            .addModel("x-ai/grok-4.7", "Grok 4.7", true));

        list.add(new CatalogProviderPreset(
            "xiaomi", "小米大模型 (MiMo)", "https://api.mimo.xiaomi.com/v1", "XIAOMI_API_KEY",
            "小米 MiMo 大模型平台，支持 MiMo-V2.6 Flash 与 Pro 推理系列"
        )
            .addModel("mimo-v2.6-flash", "MiMo-V2.6-Flash", true)
            .addModel("mimo-v2.6-pro", "MiMo-V2.6-Pro", true));

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
        if (modelId == null) return false;
        String lower = modelId.toLowerCase(Locale.US);
        return lower.contains("reasoner") ||
            lower.contains("r1") ||
            lower.contains("qwq") ||
            lower.contains("thinking") ||
            lower.contains("o1") ||
            lower.contains("o3") ||
            lower.contains("o4") ||
            lower.contains("z1") ||
            lower.contains("glm-5") ||
            lower.contains("kimi-k") ||
            lower.contains("deepseek-flash") ||
            lower.contains("deepseek-v4") ||
            lower.contains("gemini-2.5") ||
            lower.contains("gemini-3") ||
            lower.contains("gemini-4") ||
            lower.contains("claude-sonnet-4") ||
            lower.contains("claude-sonnet-5") ||
            lower.contains("claude-haiku-4") ||
            lower.contains("claude-haiku-5") ||
            lower.contains("claude-opus-4") ||
            lower.contains("claude-opus-5") ||
            lower.contains("claude-fable") ||
            lower.contains("claude-3-7") ||
            lower.contains("gpt-5") ||
            lower.contains("gpt-6") ||
            lower.contains("grok-4") ||
            lower.contains("mimo-v2.6");
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
                    List<ModelEntry> customModels = new ArrayList<ModelEntry>();
                    for (ModelEntry m : p.models) {
                        if (preset.findModel(m.id) == null) {
                            customModels.add(m);
                        }
                    }
                    p.models.clear();
                    for (ModelEntry m : preset.defaultModels) {
                        p.models.add(m.copy());
                    }
                    p.models.addAll(customModels);
                }
            }
        }
        if ("deepseek-chat".equals(mSelectedModelId) || TextUtils.isEmpty(mSelectedModelId)) {
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

    public synchronized String getSelectedProviderId() {
        return mSelectedProviderId;
    }

    public synchronized String getSelectedModelId() {
        return mSelectedModelId;
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
        if (m.supportsReasoning) {
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
