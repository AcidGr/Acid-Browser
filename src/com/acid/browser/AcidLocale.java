package com.acid.browser;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Unified Localization & Hot-Switch Engine for Acid Browser.
 *
 * <p>Supports three modes configured via {@link AcidSettings#LANGUAGE}:
 * <ul>
 *   <li>{@link AcidSettings#LANG_SYSTEM} (0): Follow Android OS language</li>
 *   <li>{@link AcidSettings#LANG_ZH} (1): Simplified Chinese (简体中文)</li>
 *   <li>{@link AcidSettings#LANG_EN} (2): English</li>
 * </ul>
 *
 * <p>Combines standard Android {@code res/values/strings.xml} & {@code res/values-en/strings.xml}
 * resource resolution (via {@code ConfigurationContext}) with automatic widget-level string and
 * dynamic pattern translation so the entire browser UI hot-switches in-place without destroying
 * active WebView tabs or interrupting running Agent/MCP sessions.
 */
public final class AcidLocale {

    private static volatile int sLanguageIndex = AcidSettings.LANG_SYSTEM;
    private static volatile boolean sEffectiveEnglish = false;
    private static volatile Resources sLocalizedResources = null;
    private static volatile Locale sObservedSystemLocale = null;

    private static final Map<String, String> EN_MAP = new HashMap<String, String>(512);

    static {
        initDictionary();
    }

    private AcidLocale() {}

    /**
     * Synchronizes active language from {@link BrowserDataStore} and updates the Android
     * {@link Configuration} resources context.
     *
     * @return {@code true} if the effective display language changed
     */
    public static synchronized boolean syncFromDataStore(Context context, BrowserDataStore store) {
        int idx = store != null ? store.getLanguageIndex() : AcidSettings.LANG_SYSTEM;
        return applyLanguageIndex(context, idx);
    }

    /**
     * Captures a live OS / per-app {@link Configuration} change delivered to {@code Activity.onConfigurationChanged}
     * and refreshes the effective language when {@link AcidSettings#LANG_SYSTEM} is selected.
     */
    public static synchronized boolean onSystemConfigurationChanged(
        Context context,
        Configuration newConfig,
        BrowserDataStore store
    ) {
        Locale updated = extractPrimaryLocale(newConfig);
        if (updated != null) {
            sObservedSystemLocale = updated;
        }
        if (store != null && store.getLanguageIndex() == AcidSettings.LANG_SYSTEM) {
            return applyLanguageIndex(context, AcidSettings.LANG_SYSTEM);
        }
        return false;
    }

    public static synchronized boolean applyLanguageIndex(Context context, int langIndex) {
        boolean prevEn = sEffectiveEnglish;
        int prevIdx = sLanguageIndex;
        sLanguageIndex = langIndex;

        if (context == null) {
            sObservedSystemLocale = null;
        }

        boolean nextEn;
        if (langIndex == AcidSettings.LANG_EN) {
            nextEn = true;
        } else if (langIndex == AcidSettings.LANG_ZH) {
            nextEn = false;
        } else {
            Locale sys = detectSystemLocale(context);
            String lang = sys != null && sys.getLanguage() != null ? sys.getLanguage().toLowerCase(Locale.US) : "";
            nextEn = lang.startsWith("en");
        }
        sEffectiveEnglish = nextEn;

        Locale targetLocale = nextEn ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        if (context != null) {
            try {
                Resources baseRes = context.getResources();
                if (baseRes != null) {
                    if (sObservedSystemLocale == null) {
                        Locale baseLoc = extractPrimaryLocale(baseRes.getConfiguration());
                        if (baseLoc != null) {
                            sObservedSystemLocale = baseLoc;
                        }
                    }
                    Configuration cfg = new Configuration(baseRes.getConfiguration());
                    if (Build.VERSION.SDK_INT >= 17) {
                        cfg.setLocale(targetLocale);
                        Context localizedCtx = context.createConfigurationContext(cfg);
                        sLocalizedResources = localizedCtx.getResources();
                    } else {
                        cfg.locale = targetLocale;
                        baseRes.updateConfiguration(cfg, baseRes.getDisplayMetrics());
                        sLocalizedResources = baseRes;
                    }
                }
            } catch (Throwable ignored) {
                // Safe no-op in JVM unit test stubs
            }
        }
        return (prevEn != nextEn) || (prevIdx != langIndex);
    }

    private static Locale extractPrimaryLocale(Configuration cfg) {
        if (cfg == null) return null;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                java.lang.reflect.Method getLocales = cfg.getClass().getMethod("getLocales");
                Object localeList = getLocales.invoke(cfg);
                if (localeList != null) {
                    java.lang.reflect.Method isEmpty = localeList.getClass().getMethod("isEmpty");
                    if (!Boolean.TRUE.equals(isEmpty.invoke(localeList))) {
                        java.lang.reflect.Method get = localeList.getClass().getMethod("get", int.class);
                        Object first = get.invoke(localeList, Integer.valueOf(0));
                        if (first instanceof Locale) {
                            return (Locale) first;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return cfg.locale;
    }

    private static Locale detectSystemLocale(Context context) {
        if (context != null) {
            if (sObservedSystemLocale != null) {
                return sObservedSystemLocale;
            }
            try {
                Resources ctxRes = context.getResources();
                if (ctxRes != null) {
                    Locale ctxLoc = extractPrimaryLocale(ctxRes.getConfiguration());
                    if (ctxLoc != null) {
                        sObservedSystemLocale = ctxLoc;
                        return ctxLoc;
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            Resources sysRes = Resources.getSystem();
            if (sysRes != null) {
                Locale sysLoc = extractPrimaryLocale(sysRes.getConfiguration());
                if (sysLoc != null) {
                    return sysLoc;
                }
            }
        } catch (Throwable ignored) {
            // Stub environment in JVM tests: default to Chinese so legacy unit test assertions stay deterministic
            if (context == null) {
                return Locale.SIMPLIFIED_CHINESE;
            }
        }
        Locale def = Locale.getDefault();
        return def != null ? def : Locale.SIMPLIFIED_CHINESE;
    }

    public static boolean isEn() {
        return sEffectiveEnglish;
    }

    public static int getLanguageIndex() {
        return sLanguageIndex;
    }

    /**
     * Resolves an Android {@code R.string.*} resource ID using the active localized {@link Resources},
     * with a fallback for headless JVM tests.
     */
    public static String getString(int resId, String fallbackZh, String fallbackEn) {
        Resources res = sLocalizedResources;
        if (res != null && resId != 0) {
            try {
                String val = res.getString(resId);
                if (val != null && !val.isEmpty()) return val;
            } catch (Throwable ignored) {}
        }
        return sEffectiveEnglish ? fallbackEn : fallbackZh;
    }

    public static String[] trArray(String[] items) {
        if (items == null || !sEffectiveEnglish) return items;
        String[] out = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = tr(items[i]);
        }
        return out;
    }

    /**
     * Translates a UI label, sentence, or dynamic status phrase into English when {@link #isEn()}
     * is active; returns {@code text} unchanged in Simplified Chinese mode.
     */
    public static String tr(String text) {
        if (text == null || text.isEmpty() || !sEffectiveEnglish) {
            return text;
        }
        String direct = EN_MAP.get(text);
        if (direct != null) {
            return direct;
        }
        return translateDynamicPattern(text);
    }

    private static String translateDynamicPattern(String s) {
        // 1. Section headers & counts: "xxx (N)"
        if (s.endsWith(")")) {
            int open = s.lastIndexOf(" (");
            if (open > 0) {
                String prefix = s.substring(0, open);
                String tail = s.substring(open); // " (N)"
                if ("全部会话".equals(prefix)) return "All Chats" + tail;
                if ("已装载供应商".equals(prefix)) return "Configured Providers" + tail;
                if ("默认供应商目录".equals(prefix)) return "Preset Providers" + tail;
                if ("全部书签".equals(prefix)) return "All Bookmarks" + tail;
                if ("搜索结果".equals(prefix)) return "Search Results" + tail;
                if ("今天".equals(prefix)) return "Today" + tail;
                if ("昨天".equals(prefix)) return "Yesterday" + tail;
                if ("最近 7 天".equals(prefix)) return "Last 7 Days" + tail;
                if ("更早以前".equals(prefix)) return "Earlier" + tail;
                if ("内置核心技能".equals(prefix)) return "Built-in Core Skills" + tail;
                if ("自定义与导入技能".equals(prefix)) return "Custom & Imported Skills" + tail;
                if ("附属参考文档与脚本".equals(prefix)) return "Companion Docs & Scripts" + tail;
                if ("已安装脚本".equals(prefix)) return "Installed Userscripts" + tail;
                if ("当前页面的脚本命令".equals(prefix)) return "Page Script Commands" + tail;
                if ("标签页".equals(prefix)) return "Tabs" + tail;
            }
        }

        // 2. Version & status prefixes
        if (s.startsWith("版本 v") || s.startsWith("版本 ")) {
            return "Version " + s.substring(3).trim();
        }
        if (s.startsWith("发现新版本 v")) {
            return "New Version " + s.substring(6).trim();
        }
        if (s.startsWith("当前已是最新版本 (v")) {
            return "Already up to date (" + s.substring(10);
        }
        if (s.startsWith("已是最新版本 v")) {
            return "Already up to date (v" + s.substring(8) + ")";
        }
        if (s.startsWith("已更新到 v")) {
            return "Updated to v" + s.substring(6);
        }
        if (s.startsWith("安装包大小: ")) {
            String v = s.substring(7);
            return "APK Size: " + ("未知".equals(v) ? "Unknown" : v);
        }
        if (s.startsWith("运行中 · Step ")) {
            return "Running · Step " + s.substring(11);
        }
        if (s.startsWith("思考中 · ")) {
            return "Thinking · " + s.substring(6);
        }
        if (s.startsWith("思考过程 · ")) {
            return "Thought Process · " + s.substring(7);
        }
        if (s.startsWith("[工具] ")) {
            return "[Tool] " + s.substring(5);
        }
        if (s.startsWith("思考: ")) {
            return "Thinking: " + s.substring(4);
        }
        if (s.startsWith("回答: ")) {
            return "Reply: " + s.substring(4);
        }
        if (s.startsWith("异常: ")) {
            return "Error: " + s.substring(4);
        }
        if (s.startsWith("请人工协助: ")) {
            String r = s.substring(7);
            return "Human Takeover: " + ("请完成页面操作".equals(r) ? "Please complete the action on page" : r);
        }
        if (s.startsWith("已拦截 ") && s.endsWith(" 次")) {
            return "Blocked " + s.substring(4, s.length() - 2).trim() + " times";
        }
        if (s.endsWith(" 次") && Character.isDigit(s.charAt(0))) {
            return s.substring(0, s.length() - 2).trim() + " times";
        }
        if (s.endsWith(" 条") && Character.isDigit(s.charAt(0))) {
            return s.substring(0, s.length() - 2).trim() + " rules";
        }
        if (s.startsWith("已拦截 App 唤端跳转 (共 ") && s.endsWith(" 次)")) {
            return "Blocked external app launch (" + s.substring(16, s.length() - 3).trim() + " total)";
        }
        if (s.startsWith("当前网页请求唤起外部应用「")) {
            return s.replace("当前网页请求唤起外部应用「", "This page wants to open external app \"")
                    .replace("」\n\n目标链接: ", "\"\n\nTarget URL: ");
        }
        if (s.startsWith("无法启动应用: ")) {
            return "Cannot launch app: " + s.substring(8);
        }
        if (s.contains(" · 缓存 ")) {
            return s.replace(" · 缓存 ", " · Cache ");
        }
        if (s.startsWith("上下文窗口: ")) {
            return s.replace("上下文窗口: ", "Context Window: ")
                    .replace("提示词缓存命中: ", "Prompt Cache Hit: ");
        }
        if (s.startsWith("已折叠 ") && s.endsWith(" 条更早历史记录 · 点击加载更多")) {
            String n = s.substring(4, s.length() - 17).trim();
            return n + " earlier messages folded · Tap to load more";
        }
        if (s.endsWith(" 条消息") && s.contains(" · ")) {
            int idx = s.lastIndexOf(" · ");
            String timePart = translateYesterdayPrefix(s.substring(0, idx));
            String countPart = s.substring(idx + 3, s.length() - 4).trim();
            return timePart + " · " + countPart + " msgs";
        }
        if (s.startsWith("昨天 ")) {
            return "Yesterday " + s.substring(3);
        }
        if (s.endsWith(" (当前)")) {
            String base = s.substring(0, s.length() - 5);
            if ("新对话".equals(base)) base = "New Chat";
            return base + " (Current)";
        }
        if (s.startsWith("确定删除对话「") && s.endsWith("」吗？删除后无法恢复。")) {
            String name = s.substring(7, s.length() - 11);
            return "Delete chat \"" + name + "\"? This cannot be undone.";
        }
        if (s.startsWith("默认搜索引擎已切换为: ")) {
            return "Default search engine switched to: " + tr(s.substring(12).trim());
        }
        if (s.startsWith("主页已切换为: ")) {
            return "Homepage switched to: " + tr(s.substring(8).trim());
        }
        if (s.startsWith("下载引擎已切换为: ")) {
            return "Download engine switched to: " + tr(s.substring(10).trim());
        }
        if (s.startsWith("界面语言已切换为: ")) {
            return "Language switched to: " + tr(s.substring(10).trim());
        }
        if (s.startsWith("已切换模型: ")) {
            return "Switched model: " + s.substring(7);
        }
        if (s.contains(" · 思考:") || s.endsWith(" · 关闭思考")) {
            return s.replace(" · 关闭思考", " · Think:Off")
                    .replace(" · 思考:低", " · Think:Low")
                    .replace(" · 思考:中", " · Think:Med")
                    .replace(" · 思考:高", " · Think:High")
                    .replace(" · 思考:极高", " · Think:Max")
                    .replace(" · 思考:默认", " · Think:Auto");
        }
        if (s.startsWith("接口: ")) {
            return "Endpoint: " + s.substring(4).replace("(默认地址)", "(Default)");
        }
        if (s.startsWith("已装载 ") && s.contains(" 个模型: ")) {
            return s.replace("已装载 ", "Loaded ").replace(" 个模型: ", " models: ").replace(" 等", " etc.");
        }
        if (s.startsWith("连接成功！服务端返回 ") && s.endsWith(" 个可用模型")) {
            String n = s.substring(11, s.length() - 6).trim();
            return "Connected! Server returned " + n + " available models";
        }
        if (s.startsWith("连接测试失败: ")) {
            return "Connection test failed: " + s.substring(8);
        }
        if (s.startsWith("获取模型列表失败: ")) {
            return "Failed to fetch models: " + s.substring(10);
        }
        if (s.startsWith("确定删除自定义供应商「")) {
            return s.replace("确定删除自定义供应商「", "Delete custom provider \"")
                    .replace("」及其已配置的模型列表吗？", "\" and its configured models?");
        }
        if (s.startsWith("已删除供应商: ")) {
            return "Deleted provider: " + s.substring(8);
        }
        if (s.startsWith("已保存供应商: ")) {
            return "Saved provider: " + s.substring(8);
        }
        if (s.startsWith("编辑 ")) {
            return "Edit " + s.substring(3);
        }
        if (s.startsWith("配置 ")) {
            return "Configure " + s.substring(3);
        }
        if (s.startsWith("输入 API Key (")) {
            return "Enter API Key (" + s.substring(12);
        }
        if (s.startsWith("选择要装载的模型 (共 ") && s.endsWith(" 个)")) {
            String n = s.substring(12, s.length() - 3).trim();
            return "Select models to load (" + n + " found)";
        }
        if (s.startsWith("已添加 ") && s.endsWith(" 个模型")) {
            String n = s.substring(4, s.length() - 4).trim();
            return "Added " + n + " models";
        }
        if (s.startsWith("未找到与 \"") && s.endsWith("\" 相关的记录")) {
            return "No records matching \"" + s.substring(6, s.length() - 7) + "\"";
        }
        if (s.startsWith("确定要删除书签「") && s.endsWith("」吗？")) {
            return "Delete bookmark \"" + s.substring(8, s.length() - 3) + "\"?";
        }
        if (s.startsWith("预估大小: ")) {
            return s.replace("预估大小: ", "Estimated Size: ").replace("未知大小", "Unknown size");
        }
        if (s.startsWith("下载引擎: ")) {
            return "Download Engine: " + tr(s.substring(6).trim());
        }
        if (s.startsWith("来源: ")) {
            return "Source: " + s.substring(4);
        }
        if (s.startsWith("确定移除下载记录「") && s.endsWith("」吗？")) {
            return "Remove download record \"" + s.substring(9, s.length() - 3) + "\"?";
        }
        if (s.startsWith("[") && s.contains("] ")) {
            int close = s.indexOf("] ");
            String rawCat = s.substring(1, close);
            if (isDownloadCategoryLabel(rawCat)) {
                String cat = tr(rawCat);
                String rest = s.substring(close + 2);
                rest = rest.replace("待下载 · ", "Saved · ")
                           .replace(" · 点击开始下载", " · Tap to start")
                           .replace("正在下载 ", "Downloading ")
                           .replace("已暂停 · ", "Paused · ")
                           .replace("排队等待下载...", "Queued...")
                           .replace("下载失败 · ", "Failed · ")
                           .replace("已完成 · ", "Completed · ")
                           .replace("未知大小", "Unknown size");
                return "[" + cat + "] " + rest;
            }
        }
        if (s.startsWith("已停用 · ") || s.startsWith("[官方核心] ") || s.startsWith("[技能包")
                || s.startsWith("所有网站 · ")) {
            String out = s;
            if (out.startsWith("已停用 · ")) {
                out = "Disabled · " + out.substring(6);
            }
            out = out.replace("[官方核心] ", "[Core] ")
                     .replace("[技能包] ", "[Package] ");
            if (out.contains("[技能包 · ") && out.contains("个文件] ")) {
                out = out.replace("[技能包 · ", "[Package · ").replace("个文件] ", " files] ");
            }
            out = out.replace("所有网站 · ", "All Sites · ");
            return out;
        }
        if (s.startsWith("安装失败: ")) {
            return "Install failed: " + s.substring(6);
        }
        if (s.startsWith("正在多线程下载: ")) {
            return s.replace("正在多线程下载: ", "Multi-thread downloading: ").replace(" · 剩余 ", " · ETA ");
        }
        if (s.startsWith("已恢复下载: ")) return "Resumed download: " + s.substring(7);
        if (s.startsWith("正在重试下载: ")) return "Retrying download: " + s.substring(8);
        if (s.startsWith("已暂停: ")) return "Paused: " + s.substring(5);
        if (s.startsWith("已暂存至下载管理: ")) {
            return s.replace("已暂存至下载管理: ", "Saved for later: ")
                    .replace("\n可在「下载管理」中随时点击下载", "\nTap to start anytime in Downloads");
        }
        if (s.startsWith("已开始内置下载: ")) {
            return s.replace("已开始内置下载: ", "Download started: ")
                    .replace("保存至手机 Download 目录", "Saved to system Download folder")
                    .replace("保存至应用专属 Download 目录（可在下载管理中直接打开或分享）", "Saved to app Download folder");
        }
        if (s.startsWith("已通过系统下载器开始下载: ")) {
            return "Started via System Downloader: " + s.substring(14);
        }
        if (s.startsWith("规则更新成功！当前活跃规则共 ") && s.endsWith(" 条")) {
            String n = s.substring(15, s.length() - 2).trim();
            return "Rules updated! " + n + " active rules loaded";
        }
        if (s.startsWith("已启用「") && s.endsWith("」")) {
            return "Enabled \"" + s.substring(4, s.length() - 1) + "\"";
        }
        if (s.startsWith("已停用「") && s.endsWith("」")) {
            return "Disabled \"" + s.substring(4, s.length() - 1) + "\"";
        }
        if (s.startsWith("已执行「") && s.endsWith("」")) {
            return "Executed \"" + s.substring(4, s.length() - 1) + "\"";
        }
        if (s.startsWith("已保存「") && s.endsWith("」")) {
            return "Saved \"" + s.substring(4, s.length() - 1) + "\"";
        }
        if (s.startsWith("已删除「") && s.endsWith("」")) {
            return "Deleted \"" + s.substring(4, s.length() - 1) + "\"";
        }
        if (s.startsWith("已成功安装脚本「") && s.endsWith("」")) {
            return "Installed userscript \"" + s.substring(8, s.length() - 1) + "\"";
        }
        if (s.startsWith("编辑「") && s.endsWith("」")) {
            return "Edit \"" + s.substring(3, s.length() - 1) + "\"";
        }
        if (s.startsWith("确定删除用户脚本「")) {
            return s.replace("确定删除用户脚本「", "Delete userscript \"").replace("」(", "\" (").replace(") 吗？", ")?");
        }
        if (s.startsWith("已启用技能「") && s.endsWith("」")) {
            return "Enabled skill \"" + s.substring(6, s.length() - 1) + "\"";
        }
        if (s.startsWith("已停用技能「") && s.endsWith("」")) {
            return "Disabled skill \"" + s.substring(6, s.length() - 1) + "\"";
        }
        if (s.startsWith("已删除技能「") && s.endsWith("」")) {
            return "Deleted skill \"" + s.substring(6, s.length() - 1) + "\"";
        }
        if (s.startsWith("确定删除技能")) {
            return s.replace("确定删除技能包「", "Delete skill package \"")
                    .replace("确定删除技能「", "Delete skill \"")
                    .replace(" 及其全部附属文件) 吗？", " and all companion files)?")
                    .replace("」(", "\" (")
                    .replace(") 吗？", ")?");
        }
        if (s.startsWith("确定将「") && s.endsWith("」恢复为初始官方版本吗？你所做的自定义修改将被覆盖。")) {
            return "Reset \"" + s.substring(4, s.length() - 26) + "\" to default? Custom edits will be overwritten.";
        }
        if (s.startsWith("已清除 ") && s.contains(" 类数据")) {
            return s.replace("已清除 ", "Cleared ")
                    .replace(" 类数据 (", " data types (")
                    .replace("清理 ", "removed ")
                    .replace(" 个工作区临时项", " temp workspace items")
                    .replace("工作区无临时残留", "no temp files")
                    .replace("，配置与脚本已保留)", ", configs & scripts kept)");
        }
        if (s.startsWith("已清除所选浏览数据 (") && s.endsWith(" 类)")) {
            return "Cleared selected browsing data (" + s.substring(11, s.length() - 3) + " types)";
        }
        if (s.startsWith("确定关闭除当前页")) {
            return s.replace("确定关闭除当前页及运行中的 AI 标签页以外的其余 ", "Close ")
                    .replace("确定关闭除当前页以外的其余 ", "Close ")
                    .replace(" 个标签页吗？", " other tabs?");
        }
        if (s.startsWith("MCP 服务已启动 (")) {
            return s.replace("MCP 服务已启动 (", "MCP Server started (")
                    .replace("局域网模式", "LAN mode")
                    .replace("本机模式", "Localhost mode");
        }
        if (s.startsWith("MCP 服务已重启 (")) {
            return s.replace("MCP 服务已重启 (", "MCP Server restarted (")
                    .replace("局域网模式", "LAN mode")
                    .replace("本机模式", "Localhost mode");
        }
        if (s.startsWith("已复制") && s.endsWith("到剪贴板")) {
            return "Copied to clipboard";
        }
        return s;
    }

    private static boolean isDownloadCategoryLabel(String cat) {
        return "全部".equals(cat) || "安装包".equals(cat) || "压缩包".equals(cat)
            || "文档".equals(cat) || "图片".equals(cat) || "视频".equals(cat)
            || "音频".equals(cat) || "其它".equals(cat);
    }

    private static String translateYesterdayPrefix(String t) {
        if (t != null && t.startsWith("昨天 ")) {
            return "Yesterday " + t.substring(3);
        }
        return t;
    }

    private static void initDictionary() {
        // Common actions & buttons
        EN_MAP.put("确定", "OK");
        EN_MAP.put("取消", "Cancel");
        EN_MAP.put("保存", "Save");
        EN_MAP.put("删除", "Delete");
        EN_MAP.put("关闭", "Close");
        EN_MAP.put("添加", "Add");
        EN_MAP.put("更新", "Update");
        EN_MAP.put("安装", "Install");
        EN_MAP.put("重置", "Reset");
        EN_MAP.put("选择", "Select");
        EN_MAP.put("下一步", "Next");
        EN_MAP.put("分享", "Share");
        EN_MAP.put("发送", "Send");
        EN_MAP.put("停止", "Stop");
        EN_MAP.put("继续", "Resume");
        EN_MAP.put("丢弃", "Discard");
        EN_MAP.put("先收起", "Save for Later");
        EN_MAP.put("立即下载", "Download Now");
        EN_MAP.put("立即更新", "Update Now");
        EN_MAP.put("稍后再说", "Later");
        EN_MAP.put("立即清理", "Clean Now");
        EN_MAP.put("立即清除", "Clear Now");
        EN_MAP.put("清空全部", "Clear All");
        EN_MAP.put("全部关闭", "Close All");
        EN_MAP.put("全部展开", "Expand All");

        // Language & Settings
        EN_MAP.put("界面语言", "Language");
        EN_MAP.put("选择界面语言", "Select Language");
        EN_MAP.put("跟随系统", "Follow System");
        EN_MAP.put("简体中文", "简体中文");
        EN_MAP.put("English", "English");
        EN_MAP.put("设置", "Settings");
        EN_MAP.put("功能", "Menu");
        EN_MAP.put("浏览与智能体", "Browsing & AI Agent");
        EN_MAP.put("通用与搜索", "General & Search");
        EN_MAP.put("AI 与自动化", "AI & Automation");
        EN_MAP.put("防护与扩展", "Protection & Extensions");
        EN_MAP.put("系统与关于", "System & About");
        EN_MAP.put("隐私与高级", "Privacy & Advanced");
        EN_MAP.put("关于 Acid Browser", "About Acid Browser");
        EN_MAP.put("关于", "About");
        EN_MAP.put("地址栏与启动", "Address Bar & Startup");
        EN_MAP.put("默认搜索引擎", "Search Engine");
        EN_MAP.put("选择默认搜索引擎", "Select Search Engine");
        EN_MAP.put("自定义搜索引擎", "Custom Search Engine");
        EN_MAP.put("自定义搜索引擎地址", "Custom Search URL");
        EN_MAP.put("搜索地址模板 (使用 %s 代表搜索词)", "Search URL template (use %s for query)");
        EN_MAP.put("自定义搜索 URL 不能为空", "Custom search URL cannot be empty");
        EN_MAP.put("已保存自定义搜索引擎", "Custom search engine saved");
        EN_MAP.put("百度 (Baidu)", "Baidu");
        EN_MAP.put("必应 (Bing)", "Bing");
        EN_MAP.put("自定义", "Custom");
        EN_MAP.put("主页设置", "Homepage");
        EN_MAP.put("选择主页类型", "Select Homepage");
        EN_MAP.put("Acid 默认首页", "Acid Default Home");
        EN_MAP.put("空白页 (about:blank)", "Blank Page (about:blank)");
        EN_MAP.put("自定义网址", "Custom URL");
        EN_MAP.put("自定义主页网址", "Custom Homepage URL");
        EN_MAP.put("主页网址", "Homepage URL");
        EN_MAP.put("主页网址不能为空", "Homepage URL cannot be empty");
        EN_MAP.put("已保存自定义主页", "Custom homepage saved");
        EN_MAP.put("下载引擎", "Download Engine");
        EN_MAP.put("选择默认下载引擎", "Select Download Engine");
        EN_MAP.put("内置下载器", "Built-in Downloader");
        EN_MAP.put("系统下载器", "System Downloader");
        EN_MAP.put("浏览数据管理", "Browsing Data");
        EN_MAP.put("清除浏览数据", "Clear Browsing Data");
        EN_MAP.put("退出时自动清理", "Clear Data on Exit");
        EN_MAP.put("网页缓存 (Cache)", "Web Cache");
        EN_MAP.put("Cookies 与网站登录状态", "Cookies & Site Data");
        EN_MAP.put("历史浏览记录", "Browsing History");
        EN_MAP.put("网页本地存储 (WebStorage)", "Web Storage (LocalStorage/IndexedDB)");
        EN_MAP.put("AI 工作区临时文件 (保留配置与脚本)", "AI Workspace Temp Files (Keep configs & scripts)");
        EN_MAP.put("请至少勾选一项要清除的数据", "Please select at least one item to clear");

        // AI & Automation settings
        EN_MAP.put("智能体与模型", "Agent & Models");
        EN_MAP.put("模型配置", "Model Configuration");
        EN_MAP.put("打开 Agent 对话窗", "Open Agent Chat");
        EN_MAP.put("远程网关与沙盒存储", "Remote Gateway & Sandbox");
        EN_MAP.put("MCP 服务与认证", "MCP Server & Auth");
        EN_MAP.put("查看本机/局域网地址、配置 Bearer 令牌", "View Local/LAN endpoints & Bearer token");
        EN_MAP.put("MCP 运行中", "MCP Running");
        EN_MAP.put("运行中", "Running");
        EN_MAP.put("已停止", "Stopped");
        EN_MAP.put("已开启", "Enabled");
        EN_MAP.put("已暂停", "Paused");
        EN_MAP.put("清理 AI 工作区文件", "Clean AI Workspace Temp Files");
        EN_MAP.put("清理 AI 工作区", "Clean AI Workspace");
        EN_MAP.put("将清除 Agent 在沙盒中生成的历史报告、抓取数据与临时文件。\n\n安全保护：不会删除 settings.json 配置文件、bookmarks.json 书签、history.json 浏览历史、自定义主页 (homepage)、用户脚本 (userscripts) 与广告拦截规则 (adblock)。",
            "Removes temporary reports and scraped files generated by the Agent.\n\nProtected: settings.json, bookmarks.json, history.json, custom homepage, userscripts, and adblock rules are preserved.");
        EN_MAP.put("AI 工作区当前无临时残留 (settings.json 与脚本已安全保留)", "No temporary files in AI workspace (configs & scripts preserved)");

        // Protection & Extensions
        EN_MAP.put("内容净化与脚本引擎", "Content Filtering & Scripts");
        EN_MAP.put("广告拦截", "AdBlock");
        EN_MAP.put("脚本管理", "Userscripts");
        EN_MAP.put("技能管理 (Skills)", "Skills");
        EN_MAP.put("技能管理", "Skills");

        // Privacy & Advanced
        EN_MAP.put("隐私与省流", "Privacy & Data Saving");
        EN_MAP.put("不跟踪 (DNT)", "Do Not Track (DNT)");
        EN_MAP.put("无图省流模式", "Block Images (Data Saver)");
        EN_MAP.put("开发者与配置同步", "Developer & Config Sync");
        EN_MAP.put("网页远程调试", "WebView Remote Debugging");
        EN_MAP.put("配置文件", "Config File");
        EN_MAP.put("工作区配置文件: settings.json (支持实时热重载)", "Workspace config: settings.json (Live hot-reload supported)");
        EN_MAP.put("已开启网页远程调试 (Chrome DevTools)", "Enabled WebView Remote Debugging (Chrome DevTools)");
        EN_MAP.put("已关闭网页远程调试", "Disabled WebView Remote Debugging");
        EN_MAP.put("已开启「不跟踪 (DNT)」请求", "Enabled Do Not Track (DNT)");
        EN_MAP.put("已关闭「不跟踪」请求", "Disabled Do Not Track (DNT)");
        EN_MAP.put("已开启无图省流模式", "Enabled Block Images mode");
        EN_MAP.put("已恢复正常图片加载", "Restored image loading");

        // Main Menu Tiles & Browser Mode Toasts
        EN_MAP.put("日间模式", "Light Mode");
        EN_MAP.put("夜间模式", "Dark Mode");
        EN_MAP.put("无痕模式", "Incognito");
        EN_MAP.put("书签收藏", "Bookmarks");
        EN_MAP.put("历史浏览", "History");
        EN_MAP.put("电脑模式", "Desktop Site");
        EN_MAP.put("手机模式", "Mobile Site");
        EN_MAP.put("下载管理", "Downloads");
        EN_MAP.put("全屏模式", "Fullscreen");
        EN_MAP.put("已切换为电脑端视口 (1280px)", "Switched to Desktop viewport (1280px)");
        EN_MAP.put("已切换为移动端视口", "Switched to Mobile viewport");
        EN_MAP.put("已开启无痕模式 (禁用历史记录、表单保存与磁盘缓存)", "Incognito enabled (history & cache disabled)");
        EN_MAP.put("已退出无痕模式 (已自动清理临时缓存与会话 Cookie)", "Incognito exited (session cache & cookies cleared)");
        EN_MAP.put("已开启全屏模式 (按返回键或点右下角退出)", "Fullscreen enabled (press Back or tap bottom-right to exit)");
        EN_MAP.put("已退出全屏模式", "Fullscreen exited");
        EN_MAP.put("MCP 服务已停止 (3090 端口已释放)", "MCP Server stopped (port 3090 released)");
        EN_MAP.put("搜索或输入网址...", "Search or enter URL...");
        EN_MAP.put("无痕模式 · 搜索或输入网址...", "Incognito · Search or enter URL...");
        EN_MAP.put("允许打开第三方应用？", "Open External App?");
        EN_MAP.put("Agent 仍在后台继续操作原标签页", "Agent continues running in the background tab");
        EN_MAP.put("该标签页正在被 Agent 使用，无法关闭。", "This tab is currently in use by Agent and cannot be closed.");

        // Takeover & Overlay
        EN_MAP.put("AI 请求接管", "AI Takeover Requested");
        EN_MAP.put("交还AI", "Return to AI");
        EN_MAP.put("点此接管", "Take Over");
        EN_MAP.put("点击查看", "View Tab");
        EN_MAP.put("自动化运行中 · 页面触控已锁定", "Agent running · Touch locked");
        EN_MAP.put("后台自动化进行中 · 当前页可自由浏览", "Agent running in background · Free to browse");
        EN_MAP.put("用户主动接管", "User took over manually");
        EN_MAP.put("思考中...", "Thinking...");
        EN_MAP.put("正在回答...", "Replying...");
        EN_MAP.put("[AI LOCK] AI 正在操作此网页，如需手动操作请点击顶部 [点此接管]", "[AI LOCK] Agent is controlling this page. Tap [Take Over] at top for manual control.");
        EN_MAP.put("Agent正在操作此页面", "Agent is controlling this page");
        EN_MAP.put("请保持浏览器在前台或小窗。", "Please keep the browser in foreground.");
        EN_MAP.put("切换标签页不影响Agent继续运行。", "Switching tabs will not interrupt the Agent.");
        EN_MAP.put("● 点击关闭遮罩提示 ●", "● Tap to dismiss overlay hint ●");

        // Agent Floating View & Sessions
        EN_MAP.put("新对话", "New Chat");
        EN_MAP.put("会话", "Chats");
        EN_MAP.put("历史会话", "Chat History");
        EN_MAP.put("新建对话", "New Chat");
        EN_MAP.put("暂无历史对话，点击右上角「+ 新建对话」开始", "No chat history yet. Tap \"+ New Chat\" at top-right to start.");
        EN_MAP.put("删除对话记录", "Delete Chat");
        EN_MAP.put("已删除对话", "Chat deleted");
        EN_MAP.put("向 Acid Agent 发送网页或沙盒任务...", "Ask Acid Agent to browse or run sandbox tasks...");
        EN_MAP.put("Acid Agent 自主智能体", "Acid Autonomous Agent");
        EN_MAP.put("内置 browser (网页感知与操控)、read、write、edit、bash 五大沙盒工具。您可以直接发送指令让它跨网页调研、填写表单、编写油猴脚本或生成本地数据报告。",
            "Equipped with 5 core sandbox tools: browser, read, write, edit, and bash. Ask it to research across pages, fill forms, write userscripts, or build local reports.");
        EN_MAP.put("💡 帮我下载网盘里的这个文件", "💡 Download this file from the cloud drive");
        EN_MAP.put("💡 帮我做一个贪吃蛇游戏并直接展示在页面上", "💡 Build a Snake game and display it on the page");
        EN_MAP.put("💡 帮我去掉当前页面上的广告", "💡 Remove ads on the current page");
        EN_MAP.put("💡 帮我下载当前页面上的媒体内容", "💡 Download media from the current page");
        EN_MAP.put("调用参数 (Arguments)", "Arguments");
        EN_MAP.put("返回结果 (Output)", "Output");
        EN_MAP.put("执行中", "Running");
        EN_MAP.put("失败", "Failed");
        EN_MAP.put("完成", "Done");
        EN_MAP.put("正在执行...", "Running...");
        EN_MAP.put("等待人工接管协助", "Waiting for human takeover");
        EN_MAP.put("已停止当前 Agent 任务", "Stopped current Agent task");
        EN_MAP.put("请输入要让 Agent 执行的任务指令", "Please enter a task for the Agent");
        EN_MAP.put("已复制回答内容到剪贴板", "Copied reply to clipboard");
        EN_MAP.put("已复制代码块到剪贴板", "Copied code block to clipboard");
        EN_MAP.put("已复制表格 Markdown 到剪贴板", "Copied Markdown table to clipboard");

        // Model Selector & Provider Config
        EN_MAP.put("选择模型", "Select Model");
        EN_MAP.put("当前使用模型", "Current Active Model");
        EN_MAP.put("暂无可用模型，请点击右上角「模型配置」添加", "No models loaded. Tap \"Model Configuration\" at top-right to add.");
        EN_MAP.put("思考深度 (Reasoning Effort)", "Reasoning Effort");
        EN_MAP.put("搜索已装载的供应商或模型名称...", "Search loaded providers or models...");
        EN_MAP.put("● 密钥已就绪", "● API Key Ready");
        EN_MAP.put("● 待填 API Key", "● API Key Required");
        EN_MAP.put("● 待填密钥", "● Key Required");
        EN_MAP.put("尚未装载任何模型，请点击右上角「模型配置」添加供应商", "No models loaded yet. Tap \"Model Configuration\" to add a provider.");
        EN_MAP.put("未找到匹配的已装载模型", "No matching loaded models found");
        EN_MAP.put("添加供应商", "Add Provider");
        EN_MAP.put("自定义", "Custom");
        EN_MAP.put("内置目录", "Preset");
        EN_MAP.put("测试连通性", "Test Connection");
        EN_MAP.put("测试中...", "Testing...");
        EN_MAP.put("配置与模型", "Configure & Models");
        EN_MAP.put("删除供应商", "Delete Provider");
        EN_MAP.put("添加模型供应商", "Add Model Provider");
        EN_MAP.put("自定义供应商 API", "Custom Provider API");
        EN_MAP.put("选择主流大模型厂商，填入 API 密钥即可一键装载默认推荐模型。", "Select a popular LLM provider and enter your API Key to load recommended models.");
        EN_MAP.put("接入第三方聚合网关、本地 Ollama / vLLM 或任意兼容 OpenAI Chat Completions 协议的服务。", "Connect any OpenAI-compatible gateway, local Ollama / vLLM, or custom endpoint.");
        EN_MAP.put("已装载 · 编辑", "Loaded · Edit");
        EN_MAP.put("+ 配置装载", "+ Configure");
        EN_MAP.put("创建自定义供应商", "Create Custom Provider");
        EN_MAP.put("供应商显示名称 (必填)", "Provider Display Name (Required)");
        EN_MAP.put("例如: 我的中转网关 / Ollama 本地服务", "e.g., My OpenAI Proxy / Local Ollama");
        EN_MAP.put("输入 sk-... (本地免鉴权接口可留空)", "Enter sk-... (leave blank for local auth-free endpoints)");
        EN_MAP.put("显示密钥", "Show Key");
        EN_MAP.put("隐藏密钥", "Hide Key");
        EN_MAP.put("API 密钥 (API Key)", "API Key");
        EN_MAP.put("API 接口地址 (OpenAI 兼容 Base URL)", "API Base URL (OpenAI Compatible)");
        EN_MAP.put("恢复默认模型", "Restore Defaults");
        EN_MAP.put("已恢复预设模型目录", "Restored preset models");
        EN_MAP.put("装载的模型列表", "Loaded Models");
        EN_MAP.put("创建并装载供应商", "Create & Load Provider");
        EN_MAP.put("保存供应商配置", "Save Provider Config");
        EN_MAP.put("供应商名称不能为空", "Provider name cannot be empty");
        EN_MAP.put("请至少为此供应商添加或保留一个可用模型", "Please keep or add at least one model");
        EN_MAP.put("+ 手动添加", "+ Add Manually");
        EN_MAP.put("同步接口模型 (/models)", "Sync Models (/models)");
        EN_MAP.put("获取中...", "Fetching...");
        EN_MAP.put("请先填入有效的 API 接口地址", "Please enter a valid API Base URL first");
        EN_MAP.put("接口未返回任何可用模型", "Endpoint returned no available models");
        EN_MAP.put("当前暂无已装载模型，请点击上方「+ 手动添加」或从接口同步", "No models loaded. Tap \"+ Add Manually\" or sync from endpoint.");
        EN_MAP.put("模型 ID (必填)", "Model ID (Required)");
        EN_MAP.put("如: deepseek-chat / gpt-4o / qwen-max", "e.g., deepseek-chat / gpt-4o / qwen-max");
        EN_MAP.put("显示名称 (选填，留空则同模型 ID)", "Display Name (Optional)");
        EN_MAP.put("如: DeepSeek V3", "e.g., DeepSeek V3");
        EN_MAP.put("手动添加模型", "Add Model Manually");
        EN_MAP.put("模型 ID 不能为空", "Model ID cannot be empty");
        EN_MAP.put("该模型 ID 已存在", "Model ID already exists");
        EN_MAP.put("添加所选", "Add Selected");
        EN_MAP.put("默认 (Default)", "Default");
        EN_MAP.put("关闭 (Off)", "Off");
        EN_MAP.put("低 (Low)", "Low");
        EN_MAP.put("中 (Medium)", "Medium");
        EN_MAP.put("高 (High)", "High");
        EN_MAP.put("极高 (Max)", "Max");
        EN_MAP.put("关闭思考", "Think:Off");
        EN_MAP.put("思考:低", "Think:Low");
        EN_MAP.put("思考:中", "Think:Med");
        EN_MAP.put("思考:高", "Think:High");
        EN_MAP.put("思考:极高", "Think:Max");
        EN_MAP.put("思考:默认", "Think:Auto");
        EN_MAP.put("未装载模型 · 请在功能-模型配置中添加", "No Model · Add in Menu -> Models");

        // Tabs Overview
        EN_MAP.put("其余标签页正在执行 AI 自动化任务，已受保护", "Other tabs are running AI tasks and are protected");
        EN_MAP.put("当前没有其他标签页可关闭", "No other tabs to close");
        EN_MAP.put("关闭其他标签页", "Close Other Tabs");
        EN_MAP.put("已关闭其余闲置标签页（AI 标签页已保留）", "Closed idle tabs (AI tab kept)");
        EN_MAP.put("已关闭其余标签页", "Closed other tabs");
        EN_MAP.put("● AI操作中", "● AI Active");
        EN_MAP.put("空白页面", "Blank Page");

        // Bookmarks & History
        EN_MAP.put("书签与历史", "Bookmarks & History");
        EN_MAP.put("搜索书签标题或网址...", "Search bookmarks by title or URL...");
        EN_MAP.put("搜索历史标题或网址...", "Search history by title or URL...");
        EN_MAP.put("收藏当前页", "Bookmark Page");
        EN_MAP.put("已收藏当前网页", "Page bookmarked");
        EN_MAP.put("清空历史", "Clear History");
        EN_MAP.put("暂无书签收藏，点击右上角可收藏当前页", "No bookmarks yet. Tap top-right to bookmark current page.");
        EN_MAP.put("暂无历史浏览记录", "No browsing history yet");
        EN_MAP.put("新标签页打开", "Open in New Tab");
        EN_MAP.put("后台打开", "Open in Background Tab");
        EN_MAP.put("已在后台打开新标签", "Opened in background tab");
        EN_MAP.put("编辑书签", "Edit Bookmark");
        EN_MAP.put("删除书签", "Delete Bookmark");
        EN_MAP.put("添加到书签", "Add to Bookmarks");
        EN_MAP.put("已添加至书签收藏", "Added to bookmarks");
        EN_MAP.put("网站标题", "Site Title");
        EN_MAP.put("网址 (URL)", "URL");
        EN_MAP.put("网址不能为空", "URL cannot be empty");
        EN_MAP.put("书签已更新", "Bookmark updated");
        EN_MAP.put("已删除书签", "Bookmark deleted");
        EN_MAP.put("清空历史记录", "Clear Browsing History");
        EN_MAP.put("确定要清空全部历史浏览记录吗？此操作无法撤销。", "Clear all browsing history? This cannot be undone.");
        EN_MAP.put("已清空历史浏览记录", "Browsing history cleared");

        // Downloads & Download Confirm
        EN_MAP.put("全部", "All");
        EN_MAP.put("安装包", "APK");
        EN_MAP.put("压缩包", "Archive");
        EN_MAP.put("文档", "Docs");
        EN_MAP.put("图片", "Image");
        EN_MAP.put("视频", "Video");
        EN_MAP.put("音频", "Audio");
        EN_MAP.put("其它", "Other");
        EN_MAP.put("搜索下载文件名...", "Search downloaded files...");
        EN_MAP.put("打开目录", "Open Folder");
        EN_MAP.put("暂无下载任务记录", "No download tasks yet");
        EN_MAP.put("未找到匹配的下载任务", "No matching downloads found");
        EN_MAP.put("开始下载", "Start Download");
        EN_MAP.put("暂停下载", "Pause Download");
        EN_MAP.put("继续下载", "Resume Download");
        EN_MAP.put("继续/重试下载", "Resume / Retry");
        EN_MAP.put("打开文件", "Open File");
        EN_MAP.put("其他应用打开", "Open With...");
        EN_MAP.put("打开所在目录", "Open Containing Folder");
        EN_MAP.put("复制下载链接", "Copy Download Link");
        EN_MAP.put("重新下载", "Redownload");
        EN_MAP.put("删除任务", "Delete Task");
        EN_MAP.put("同时删除本地已下载的源文件", "Also delete downloaded file from disk");
        EN_MAP.put("删除下载任务", "Delete Download Task");
        EN_MAP.put("已删除任务及本地源文件", "Deleted task and local file");
        EN_MAP.put("已移除下载任务记录", "Removed download record");
        EN_MAP.put("新建下载", "New Download");
        EN_MAP.put("保存文件名 (可修改)", "File Name (Editable)");
        EN_MAP.put("输入保存文件名", "Enter file name");
        EN_MAP.put("保存目录: /Download/", "Folder: /Download/");
        EN_MAP.put("浏览目录", "Browse");
        EN_MAP.put("复制链接", "Copy Link");
        EN_MAP.put("已复制到剪贴板", "Copied to clipboard");

        // AdBlock Sheet
        EN_MAP.put("防护已开启", "Protection Active");
        EN_MAP.put("防护已暂停", "Protection Paused");
        EN_MAP.put("自动拦截网页广告请求、弹窗贴片与恶意唤端", "Automatically blocks ads, popups, and unwanted app launches");
        EN_MAP.put("广告拦截已开启", "AdBlock enabled");
        EN_MAP.put("广告拦截已暂停", "AdBlock paused");
        EN_MAP.put("累计已拦截请求: ", "Total Blocked Requests: ");
        EN_MAP.put("内置规则", "Built-in");
        EN_MAP.put("订阅规则", "Subscribed");
        EN_MAP.put("自定义规则", "Custom");
        EN_MAP.put("阻止调用第三方 APP", "Block External App Launches");
        EN_MAP.put("外部应用唤起拦截等级", "App Launch Interception Policy");
        EN_MAP.put("完全拦截", "Block All");
        EN_MAP.put("弹窗询问", "Ask First");
        EN_MAP.put("完全不拦截", "Allow All");
        EN_MAP.put("已切换为「一级：完全拦截第三方 APP」", "Switched to Level 1: Block All External Apps");
        EN_MAP.put("已切换为「二级：唤起第三方 APP 时弹窗询问」", "Switched to Level 2: Ask Before Opening External Apps");
        EN_MAP.put("已切换为「三级：允许直接打开第三方 APP」", "Switched to Level 3: Allow External Apps");
        EN_MAP.put("在线规则订阅", "Filter Subscription");
        EN_MAP.put("ABP / EasyList 订阅地址", "ABP / EasyList Subscription URL");
        EN_MAP.put("拉取并更新订阅规则", "Fetch & Update Rules");
        EN_MAP.put("正在拉取规则...", "Fetching rules...");
        EN_MAP.put("请输入有效的订阅规则 URL", "Please enter a valid subscription URL");
        EN_MAP.put("自定义拦截规则", "Custom Filter Rules");
        EN_MAP.put("规则列表 (支持 ||domain^ 域名拦截与 ##selector 元素隐藏)", "Rules (||domain^ network block & ##selector element hiding)");
        EN_MAP.put("保存自定义规则", "Save Custom Rules");
        EN_MAP.put("自定义拦截规则已保存生效", "Custom rules saved and applied");

        // Userscripts Sheet
        EN_MAP.put("尚未安装任何用户脚本，点击右上角可导入", "No userscripts installed. Tap top-right to import.");
        EN_MAP.put("导入脚本", "Import");
        EN_MAP.put("所有网站", "All Sites");
        EN_MAP.put("在当前页运行一次", "Run Once on Current Page");
        EN_MAP.put("在当前页运行", "Run on Page");
        EN_MAP.put("编辑源码", "Edit Source");
        EN_MAP.put("检查更新", "Check Update");
        EN_MAP.put("检查中...", "Checking...");
        EN_MAP.put("查看脚本详情", "Script Details");
        EN_MAP.put("停用脚本", "Disable Script");
        EN_MAP.put("启用脚本", "Enable Script");
        EN_MAP.put("删除脚本", "Delete Script");
        EN_MAP.put("基本信息", "Script Info");
        EN_MAP.put("源码预览", "Source Preview");
        EN_MAP.put("无法读取脚本文件", "Unable to read script file");
        EN_MAP.put("脚本源码", "Script Source");
        EN_MAP.put("脚本内容不能为空", "Script content cannot be empty");
        EN_MAP.put("缺少 ==UserScript== 元数据块", "Missing ==UserScript== metadata header");
        EN_MAP.put("该脚本未声明 @updateURL / @downloadURL", "No @updateURL / @downloadURL declared in script");
        EN_MAP.put("正在检查更新...", "Checking for updates...");
        EN_MAP.put("检查更新失败，请检查网络或更新源", "Update check failed. Check network or update URL.");
        EN_MAP.put("检查更新失败", "Update check failed");
        EN_MAP.put("发现新版本", "Update Available");
        EN_MAP.put("导入用户脚本", "Import Userscript");
        EN_MAP.put("在线脚本 URL (.user.js) 或完整脚本源码", "Script URL (.user.js) or full script source");
        EN_MAP.put("解析并安装", "Parse & Install");
        EN_MAP.put("请输入脚本链接或源码", "Please enter a script URL or source code");
        EN_MAP.put("未检测到有效 URL 或 ==UserScript== 元数据头", "No valid URL or ==UserScript== header detected");
        EN_MAP.put("获取脚本失败，内容为空", "Failed to fetch script: empty content");
        EN_MAP.put("获取脚本失败，请检查网络连接", "Failed to fetch script: check network");
        EN_MAP.put("安装用户脚本", "Install Userscript");

        // Skills Sheet
        EN_MAP.put("暂无任何技能，点击右上角可添加或从 GitHub 导入", "No skills found. Tap top-right to add or import from GitHub.");
        EN_MAP.put("添加技能", "Add Skill");
        EN_MAP.put("查看技能详情", "Skill Details");
        EN_MAP.put("停用技能", "Disable Skill");
        EN_MAP.put("启用技能", "Enable Skill");
        EN_MAP.put("重置为官方默认", "Reset to Default");
        EN_MAP.put("重置默认", "Reset");
        EN_MAP.put("删除技能", "Delete Skill");
        EN_MAP.put("技能元数据", "Skill Metadata");
        EN_MAP.put("主入口文档 (SKILL.md)", "Entry Document (SKILL.md)");
        EN_MAP.put("Markdown 规则全文", "Markdown Document");
        EN_MAP.put("复制全文", "Copy Full Text");
        EN_MAP.put("已复制技能 Markdown 全文", "Copied skill Markdown to clipboard");
        EN_MAP.put("编辑修改", "Edit Skill");
        EN_MAP.put("附属文件路径", "Companion File Path");
        EN_MAP.put("文件内容", "File Content");
        EN_MAP.put("复制内容", "Copy Content");
        EN_MAP.put("已复制附属文件内容", "Copied companion file content");
        EN_MAP.put("编辑文件", "Edit File");
        EN_MAP.put("重置内置技能", "Reset Built-in Skill");
        EN_MAP.put("已重置为官方默认版本", "Reset to default version");
        EN_MAP.put("从 GitHub / 外部链接导入 (支持文件夹/单文件/ZIP)", "Import from GitHub / URL (Folder / File / ZIP)");
        EN_MAP.put("手动粘贴 / 编写 Markdown 技能", "Paste / Write Markdown Skill Manually");
        EN_MAP.put("添加或导入技能", "Add or Import Skill");
        EN_MAP.put("从 GitHub / 外部链接导入技能", "Import Skill from GitHub / URL");
        EN_MAP.put("支持 GitHub 技能目录链接 (tree/... 自動拉取整包附属文件)、单文件链接 (SKILL.md) 或 .zip 链接",
            "Supports GitHub directory URLs (tree/...), single SKILL.md URLs, or .zip archive URLs");
        EN_MAP.put("立即导入", "Import Now");
        EN_MAP.put("请输入有效的 GitHub 或技能文件链接", "Please enter a valid GitHub or skill URL");
        EN_MAP.put("正在从远程拉取技能及附属文件...", "Fetching skill and companion files...");
        EN_MAP.put("添加自定义技能 (Markdown)", "Add Custom Skill (Markdown)");
        EN_MAP.put("支持标准 YAML Frontmatter (--- name / description ---) 或 Markdown 标题格式...",
            "Supports YAML Frontmatter (--- name / description ---) or Markdown headings...");
        EN_MAP.put("内容不能为空", "Content cannot be empty");
        EN_MAP.put("输入技能路径或名称", "Enter Skill Path or Name");
        EN_MAP.put("支持单文件名 (如 my_skill.md) 或技能包路径 (如 my-skill/SKILL.md)",
            "Supports flat file (my_skill.md) or package path (my-skill/SKILL.md)");
        EN_MAP.put("技能名不能为空", "Skill name cannot be empty");
        EN_MAP.put("保存修改", "Save Changes");

        // MCP Sheet
        EN_MAP.put("MCP 服务", "MCP Server");
        EN_MAP.put("MCP 服务运行中", "MCP Server Running");
        EN_MAP.put("MCP 服务已停止", "MCP Server Stopped");
        EN_MAP.put("允许外部 AI 工具连接并协同遥控浏览器。请开启时确保你知道 MCP 是什么。",
            "Allows external AI tools (Cursor, Claude Desktop) to connect and automate the browser via MCP.");
        EN_MAP.put("连接地址", "Endpoints");
        EN_MAP.put("点击复制本机地址", "Tap to copy localhost URL");
        EN_MAP.put("服务未启动", "Server stopped");
        EN_MAP.put("局域网监听未开启 (仅本机可连)", "LAN access disabled (localhost only)");
        EN_MAP.put("点击复制局域网地址", "Tap to copy LAN URL");
        EN_MAP.put("未连接 Wi-Fi / 无局域网 IP", "No Wi-Fi / LAN IP detected");
        EN_MAP.put("本机地址", "Localhost Endpoint");
        EN_MAP.put("局域网地址", "LAN Endpoint");
        EN_MAP.put("未启动", "Stopped");
        EN_MAP.put("未就绪", "Not Ready");
        EN_MAP.put("服务端口", "Port");
        EN_MAP.put("MCP 服务未启动，请先在上方启动服务", "MCP Server is stopped. Please start it above first.");
        EN_MAP.put("请先在下方开启「允许局域网设备连接」", "Please enable \"Allow LAN Connections\" below first");
        EN_MAP.put("当前未检测到局域网 IP，请确认已连接 Wi-Fi", "No LAN IP detected. Please check your Wi-Fi connection.");
        EN_MAP.put("安全认证 (Bearer Token)", "Authentication (Bearer Token)");
        EN_MAP.put("启用 Bearer 令牌认证", "Require Bearer Token Auth");
        EN_MAP.put("外部客户端必须携带令牌才能访问，防止恶意网页与未授权调用", "Require token header or query parameter for all MCP requests");
        EN_MAP.put("已启用 MCP 令牌认证", "MCP token authentication enabled");
        EN_MAP.put("已停用令牌认证 (允许匿名访问)", "MCP token authentication disabled");
        EN_MAP.put("访问令牌 (Token)", "Access Token");
        EN_MAP.put("点击查看与复制完整令牌", "Tap to view and copy full token");
        EN_MAP.put("重新生成访问令牌", "Regenerate Access Token");
        EN_MAP.put("原有令牌将立即失效，需在客户端更新", "Invalidates current token immediately");
        EN_MAP.put("重置令牌确认", "Regenerate Token?");
        EN_MAP.put("重新生成后，已连接的外部客户端需要更新为新令牌才能继续调用。确认生成新令牌吗？",
            "Existing MCP clients will need the new token to connect. Generate a new token now?");
        EN_MAP.put("确认重置", "Regenerate");
        EN_MAP.put("新令牌已生成并复制到剪贴板", "New token generated and copied to clipboard");
        EN_MAP.put("网络访问控制", "Network Access");
        EN_MAP.put("允许局域网设备连接", "Allow LAN Connections");
        EN_MAP.put("监听 0.0.0.0，使同一 Wi-Fi 下的电脑等设备能访问 MCP", "Bind 0.0.0.0 so desktop clients on the same Wi-Fi can connect");
        EN_MAP.put("已允许局域网连接，建议开启上方令牌认证以保安全", "LAN access enabled. Keeping token auth enabled is recommended.");
        EN_MAP.put("已允许局域网连接", "LAN access enabled");
        EN_MAP.put("已限制仅本机回环访问", "Restricted to localhost (127.0.0.1) only");
        EN_MAP.put("连接与使用说明", "Connection Guide");
        EN_MAP.put("复制令牌", "Copy Token");
        EN_MAP.put(
            "1. 本机/跨机连接：\n"
            + "   • 同一手机上的 AI 工具使用「本机地址」。\n"
            + "   • 电脑上的 Cursor、Claude Desktop 等请使用「局域网地址」，两台设备需在同一 Wi-Fi 下。\n\n"
            + "2. 客户端认证配置：\n"
            + "   • 开启认证后，客户端需在请求头中配置：\n"
            + "     Authorization: Bearer <访问令牌>\n"
            + "   • 或在连接 URL 结尾追加参数：?token=<访问令牌>\n\n"
            + "3. 安全注意：\n"
            + "   • 认证通信不加密，请在可信任的家庭或公司局域网中使用，切勿在公共开放 Wi-Fi 下开启局域网连接。",
            "1. Local / LAN Connection:\n"
            + "   • On-device AI tools: use the Localhost Endpoint.\n"
            + "   • Desktop tools (Cursor, Claude Desktop): use the LAN Endpoint on the same Wi-Fi.\n\n"
            + "2. Client Authentication:\n"
            + "   • When auth is enabled, pass the header:\n"
            + "     Authorization: Bearer <token>\n"
            + "   • Or append query parameter: ?token=<token>\n\n"
            + "3. Security Notice:\n"
            + "   • Traffic is unencrypted HTTP. Use LAN mode only on trusted private Wi-Fi networks."
        );

        // About & Begging Sheet
        EN_MAP.put("乞讨", "Donate");
        EN_MAP.put("开发者与开源仓库", "Developer & Repository");
        EN_MAP.put("开发者", "Developer");
        EN_MAP.put("GitHub 开源仓库", "GitHub Repository");
        EN_MAP.put("内核简介", "Architecture");
        EN_MAP.put("Acid Browser 是一款零第三方依赖、原生内嵌自主 Agent Loop、本地 MCP 控制网关、油猴脚本引擎与 ABP 广告拦截器的超轻量现代 AI 浏览器。",
            "Acid Browser is an ultra-lightweight, zero-dependency AI browser with a native autonomous Agent Loop, local MCP gateway, Userscript engine, and ABP ad blocker.");
        EN_MAP.put("此为作者的乞讨页面，您可以付款但是您得不到任何好处！",
            "This is the author's begging page — you can pay, but you will get nothing in return!");
        EN_MAP.put("微信", "WeChat Pay");
        EN_MAP.put("支付宝", "Alipay");
        EN_MAP.put("推荐使用支付宝", "Alipay Recommended");
        EN_MAP.put("推荐使用微信支付", "WeChat Pay Recommended");
        EN_MAP.put("打开支付宝 [扫一扫]", "Scan with Alipay");
        EN_MAP.put("微信扫一扫向作者付款", "Scan with WeChat to pay author");
        EN_MAP.put("以后都隐藏乞讨按钮", "Hide This Button Forever");
        EN_MAP.put("已隐藏乞讨按钮", "Donate button hidden");
        EN_MAP.put("更新说明", "Release Notes");
        EN_MAP.put("修复已知问题，提升稳定性与浏览体验", "Bug fixes and stability improvements");
        EN_MAP.put("检查更新失败，请访问 GitHub Releases 查看", "Update check failed. Please check GitHub Releases.");

        // Element Context Menu & JS Dialogs
        EN_MAP.put("拨打电话", "Call Number");
        EN_MAP.put("无法拨打电话", "Cannot place call");
        EN_MAP.put("复制号码", "Copy Number");
        EN_MAP.put("号码已复制", "Phone number copied");
        EN_MAP.put("发送邮件", "Send Email");
        EN_MAP.put("无法发送邮件", "Cannot send email");
        EN_MAP.put("复制邮箱", "Copy Email");
        EN_MAP.put("邮箱已复制", "Email copied");
        EN_MAP.put("查看图片", "View Image");
        EN_MAP.put("下载图片", "Download Image");
        EN_MAP.put("复制链接", "Copy Link");
        EN_MAP.put("链接已复制", "Link copied");
        EN_MAP.put("复制图片链接", "Copy Image Link");
        EN_MAP.put("图片链接已复制", "Image link copied");
        EN_MAP.put("分享链接", "Share Link");
        EN_MAP.put("分享图片", "Share Image");
        EN_MAP.put("无法发起分享", "Cannot share");
        EN_MAP.put("网页提示", "Page Alert");
        EN_MAP.put("网页确认", "Page Confirm");
        EN_MAP.put("网页输入", "Page Prompt");
    }
}
