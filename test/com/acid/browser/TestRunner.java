package com.acid.browser;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zero-dependency JVM Regression Test Suite for Acid Browser core engines & data stores.
 */
public final class TestRunner {

    private static int sPassed = 0;
    private static int sFailed = 0;

    public static void main(String[] args) throws Exception {
        run("BuildInfo single version source", new TestCase() {
            @Override public void execute() throws Exception { testBuildInfo(); }
        });
        run("JsEvalShaper expression & statement shaping", new TestCase() {
            @Override public void execute() throws Exception { testJsEvalShaper(); }
        });
        run("UserscriptMeta header parsing & URL glob scoping", new TestCase() {
            @Override public void execute() throws Exception { testUserscriptMeta(); }
        });
        run("AdBlockManager ABP rule parsing & domain matching", new TestCase() {
            @Override public void execute() throws Exception { testAdBlockRuleParsing(); }
        });
        run("BrowserDataStore settings.json round-trip & legacy migration", new TestCase() {
            @Override public void execute() throws Exception { testDataStoreSettingsAndSafety(); }
        });
        run("AcidSettings registry, BoolState reactivity & prompt schema", new TestCase() {
            @Override public void execute() throws Exception { testAcidSettingsReactivity(); }
        });
        run("AcidTheme tokens, onColor contrast & AcidAsync cancellation", new TestCase() {
            @Override public void execute() throws Exception { testThemeAndAsync(); }
        });
        run("AcidSandboxTools AgentTool registry & MCP schema generation", new TestCase() {
            @Override public void execute() throws Exception { testAgentToolRegistry(); }
        });

        System.out.println("[AcidTests] Passed: " + sPassed + ", Failed: " + sFailed);
        if (sFailed > 0) {
            System.exit(1);
        }
    }

    interface TestCase {
        void execute() throws Exception;
    }

    static void run(String name, TestCase tc) {
        try {
            tc.execute();
            sPassed++;
            System.out.println("  [PASS] " + name);
        } catch (Throwable t) {
            sFailed++;
            System.err.println("  [FAIL] " + name + ": " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    static void assertEquals(Object expected, Object actual, String msg) {
        boolean eq = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!eq) {
            throw new AssertionError(msg + " — expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void testBuildInfo() {
        assertTrue(BuildInfo.VERSION_NAME != null && !BuildInfo.VERSION_NAME.isEmpty(), "VERSION_NAME populated");
        assertTrue(BuildInfo.VERSION_CODE > 0, "VERSION_CODE positive");
        assertEquals(BuildInfo.VERSION_NAME, AcidTheme.appVersionName(null), "AcidTheme fallback uses BuildInfo");
        assertEquals(BuildInfo.VERSION_NAME, HttpControlServer.MCP_SERVER_VERSION, "MCP version uses BuildInfo");
    }

    private static void testJsEvalShaper() {
        List<JsEvalShaper.Candidate> empty = JsEvalShaper.candidates("   ");
        assertEquals(1, Integer.valueOf(empty.size()), "empty candidate count");
        assertEquals(JsEvalShaper.MODE_EXPRESSION, empty.get(0).mode, "empty mode");

        List<JsEvalShaper.Candidate> expr = JsEvalShaper.candidates("document.title");
        assertEquals(JsEvalShaper.MODE_EXPRESSION, expr.get(0).mode, "expr first");
        assertTrue(expr.get(0).body.contains("return (document.title"), "expr return wrapper");

        List<JsEvalShaper.Candidate> stmts = JsEvalShaper.candidates("const x = 2;\nx * 21;");
        boolean hasStmtAutoReturn = false;
        for (JsEvalShaper.Candidate c : stmts) {
            if (JsEvalShaper.MODE_STATEMENTS.equals(c.mode) && c.body.contains("return (x * 21")) {
                hasStmtAutoReturn = true;
            }
        }
        assertTrue(hasStmtAutoReturn, "auto-returns trailing value statement");
    }

    private static void testUserscriptMeta() {
        String script = "// ==UserScript==\n"
            + "// @name         知乎阅读模式\n"
            + "// @version      1.2.0\n"
            + "// @description  净化正文\n"
            + "// @match        *://*.zhihu.com/*\n"
            + "// @exclude      *://www.zhihu.com/signin*\n"
            + "// @run-at       document-start\n"
            + "// ==/UserScript==\n"
            + "(function(){ console.log(1); })();";
        UserscriptMeta meta = UserscriptMeta.parse("reader.user.js", script);
        assertTrue(meta.hasMetadata, "hasMetadata");
        assertEquals("知乎阅读模式", meta.name, "script name");
        assertEquals("1.2.0", meta.version, "script version");
        assertEquals(UserscriptMeta.RUN_AT_START, meta.runAt, "run-at");
        assertTrue(meta.appliesTo("https://zhuanlan.zhihu.com/p/12345"), "matches subdomain");
        assertTrue(!meta.appliesTo("https://www.zhihu.com/signin?next=/"), "exclude wins");
        assertTrue(!meta.appliesTo("https://news.ycombinator.com/"), "non-matching host rejected");

        UserscriptMeta fallback = UserscriptMeta.parse("raw_tool.user.js", "window.__x = 1;");
        assertTrue(!fallback.hasMetadata, "fallback no header");
        assertEquals("raw_tool", fallback.name, "fallback strips .user.js");
        assertEquals(UserscriptMeta.RUN_AT_IDLE, fallback.runAt, "fallback runAt");
        assertTrue(fallback.appliesTo("https://any.example.org/a"), "unrestricted applies everywhere");
    }

    private static void testAdBlockRuleParsing() {
        Set<String> blocked = new HashSet<String>();
        Set<String> white = new HashSet<String>();
        List<String> paths = new ArrayList<String>();
        Map<String, List<String>> domHide = new HashMap<String, List<String>>();
        List<String> globHide = new ArrayList<String>();

        assertTrue(!AdBlockManager.parseRuleLine("! comment", blocked, white, paths, domHide, globHide), "comment ignored");
        assertTrue(AdBlockManager.parseRuleLine("||ads.example.com^", blocked, white, paths, domHide, globHide), "domain block");
        assertTrue(AdBlockManager.parseRuleLine("@@||safe.ads.example.com^", blocked, white, paths, domHide, globHide), "whitelist");
        assertTrue(AdBlockManager.parseRuleLine("zhihu.com,juejin.cn##.AppBanner", blocked, white, paths, domHide, globHide), "domain hide");
        assertTrue(AdBlockManager.parseRuleLine("##.global-popup", blocked, white, paths, domHide, globHide), "global hide");
        assertTrue(AdBlockManager.parseRuleLine("/banner_tracker/", blocked, white, paths, domHide, globHide), "path rule");

        assertTrue(AdBlockManager.matchesDomainHierarchy("sub.ads.example.com", blocked), "subdomain matches parent rule");
        assertTrue(AdBlockManager.matchesDomainHierarchy("safe.ads.example.com", white), "whitelist matches");
        assertTrue(!AdBlockManager.matchesDomainHierarchy("clean.example.com", blocked), "sibling domain not blocked");
        assertEquals(1, Integer.valueOf(domHide.get("zhihu.com").size()), "zhihu selector parsed");
        assertEquals(".global-popup", globHide.get(0), "global selector parsed");
    }

    private static void testDataStoreSettingsAndSafety() throws Exception {
        File tmpFile = File.createTempFile("acid_settings_test_", ".json");
        tmpFile.deleteOnExit();
        tmpFile.delete();

        InMemoryPrefs primary = new InMemoryPrefs();
        InMemoryPrefs legacy = new InMemoryPrefs();
        legacy.edit().putBoolean(BrowserDataStore.PREF_MCP_ENABLED, false).apply();

        BrowserDataStore store = new BrowserDataStore(primary, legacy, tmpFile);
        assertEquals(Boolean.FALSE, Boolean.valueOf(store.isMcpServerEnabled()), "legacy mcp_server_enabled migrated");

        store.setDarkMode(true);
        store.setBlockImagesEnabled(true);
        store.setDoNotTrackEnabled(true);
        store.setWebDebuggingEnabled(true);
        store.setClearOnExit(true);
        store.setSearchEngineIndex(BrowserDataStore.SEARCH_DUCKDUCKGO);
        store.setHomeType(BrowserDataStore.HOME_CUSTOM);
        store.setCustomHomeUrl("example.org");

        assertTrue(tmpFile.exists(), "settings.json written");
        assertEquals("https://example.org", store.getEffectiveHomeUrl(), "custom home normalizes https");
        assertEquals("https://duckduckgo.com/?q=acid+test", store.buildSearchUrl("acid test"), "ddg search url");

        // Reload into fresh prefs from settings.json
        InMemoryPrefs freshPrimary = new InMemoryPrefs();
        final int[] reloadCallbacks = new int[]{0};
        BrowserDataStore store2 = new BrowserDataStore(freshPrimary, new InMemoryPrefs(), tmpFile);
        store2.setOnSettingsChangedListener(new BrowserDataStore.OnSettingsChangedListener() {
            @Override public void onSettingsChanged() { reloadCallbacks[0]++; }
        });
        assertTrue(store2.reloadFromJsonFile(), "reloadFromJsonFile succeeds");
        assertEquals(1, Integer.valueOf(reloadCallbacks[0]), "listener notified");
        assertTrue(store2.isDarkMode(), "dark_mode round-trip");
        assertTrue(store2.isBlockImagesEnabled(), "block_images round-trip");
        assertTrue(store2.isDoNotTrackEnabled(), "do_not_track round-trip");
        assertTrue(store2.isWebDebuggingEnabled(), "web_debugging round-trip");
        assertTrue(store2.isClearOnExit(), "clear_on_exit round-trip");
        assertEquals(Integer.valueOf(BrowserDataStore.SEARCH_DUCKDUCKGO), Integer.valueOf(store2.getSearchEngineIndex()), "search_engine round-trip");
        assertEquals(Integer.valueOf(BrowserDataStore.HOME_CUSTOM), Integer.valueOf(store2.getHomeType()), "home_type round-trip");

        // Corrupt bookmark blob must NOT be overwritten by default seed bookmarks
        InMemoryPrefs corruptPrefs = new InMemoryPrefs();
        corruptPrefs.edit().putString(BrowserDataStore.PREF_BOOKMARKS, "{corrupt_json").apply();
        BrowserDataStore store3 = new BrowserDataStore(corruptPrefs, new InMemoryPrefs(), null);
        List<BrowserDataStore.EntryItem> recovered = store3.loadBookmarks();
        assertTrue(recovered.isEmpty(), "corrupt bookmarks returns empty without crash");
        assertEquals("{corrupt_json", corruptPrefs.getString(BrowserDataStore.PREF_BOOKMARKS, ""), "corrupt blob preserved untouched");

        // Write external JSON edit and reload
        String externalJson = "{\"search_engine\":\"bing\",\"dark_mode\":false,\"adblock_enabled\":false}";
        FileOutputStream fos = new FileOutputStream(tmpFile);
        fos.write(externalJson.getBytes(StandardCharsets.UTF_8));
        fos.close();
        assertTrue(store2.reloadFromJsonFile(), "external json reload");
        assertEquals(Integer.valueOf(BrowserDataStore.SEARCH_BING), Integer.valueOf(store2.getSearchEngineIndex()), "bing loaded");
        assertTrue(!store2.isDarkMode(), "dark_mode false loaded");
        assertTrue(!store2.isAdBlockEnabled(), "adblock_enabled false loaded");

        JSONObject exported = store2.exportToJson();
        assertEquals("bing", exported.getString("search_engine"), "exported search_engine");
        tmpFile.delete();
    }

    private static void testAcidSettingsReactivity() throws Exception {
        String schema = AcidSettings.describeJsonSchemaForPrompt();
        assertTrue(schema.contains("`mcp_server_enabled`: boolean"), "prompt schema includes mcp_server_enabled");
        assertTrue(schema.contains("`search_engine`: 'google'|'baidu'|'bing'|'duckduckgo'|'custom'"), "prompt schema includes enum codes");
        assertTrue(!schema.contains("adblock_blocked_count"), "internal counter excluded from settings.json schema");

        File tmpFile = File.createTempFile("acid_reactive_test_", ".json");
        tmpFile.deleteOnExit();

        BrowserDataStore store = new BrowserDataStore(new InMemoryPrefs(), new InMemoryPrefs(), tmpFile);
        StateSource.BoolState dntState = store.boolState(AcidSettings.DO_NOT_TRACK);
        StateSource.BoolState darkState = store.boolState(AcidSettings.DARK_MODE);

        final int[] dntEvents = new int[]{0};
        final int[] darkEvents = new int[]{0};
        dntState.observe(new StateSource.Observer() {
            @Override public void onChanged(boolean value) { dntEvents[0]++; }
        });
        darkState.observe(new StateSource.Observer() {
            @Override public void onChanged(boolean value) { darkEvents[0]++; }
        });

        dntState.toggle();
        assertTrue(dntState.get(), "dnt toggled on");
        assertEquals(1, Integer.valueOf(dntEvents[0]), "dnt observer fired once");
        assertEquals(0, Integer.valueOf(darkEvents[0]), "dark observer untouched");

        // Setting same value does not re-fire
        dntState.set(true);
        assertEquals(1, Integer.valueOf(dntEvents[0]), "idempotent set does not spam observer");

        // Hot-reload where only dark_mode changes: only dark observer should fire
        String json = "{\"do_not_track\": true, \"dark_mode\": true}";
        FileOutputStream fos = new FileOutputStream(tmpFile);
        fos.write(json.getBytes(StandardCharsets.UTF_8));
        fos.close();
        assertTrue(store.reloadFromJsonFile(), "hot reload succeeds");
        assertEquals(1, Integer.valueOf(dntEvents[0]), "unchanged key observer not spammed on reload");
        assertEquals(1, Integer.valueOf(darkEvents[0]), "changed key observer fired on reload");
        assertTrue(darkState.get(), "darkState reflects reload");

        // MutableBoolState with SetterHook
        final int[] hookCalls = new int[]{0};
        StateSource.MutableBoolState runtime = new StateSource.MutableBoolState(false, new StateSource.SetterHook() {
            @Override public boolean apply(boolean requestedValue) {
                hookCalls[0]++;
                return requestedValue;
            }
        });
        runtime.toggle();
        assertTrue(runtime.get(), "runtime toggled");
        assertEquals(1, Integer.valueOf(hookCalls[0]), "hook called on set");
        runtime.syncExternal(false);
        assertTrue(!runtime.get(), "syncExternal updates state");
        assertEquals(1, Integer.valueOf(hookCalls[0]), "syncExternal bypasses hook");
        tmpFile.delete();
    }

    private static void testThemeAndAsync() throws Exception {
        // Distinct typography hierarchy (no fake 0.5sp H3 vs H4 gap, Dialog Title < Sheet Title)
        assertTrue(AcidTheme.SP_SHEET_TITLE > AcidTheme.SP_DIALOG_TITLE, "Sheet title > Dialog title");
        assertTrue(AcidTheme.SP_HERO >= AcidTheme.SP_ITEM + 1.0f, "Hero headline >= 1sp above Item title");
        assertTrue(AcidTheme.SP_FIELD_LABEL > AcidTheme.SP_OVERLINE, "Field label distinct from section overline");
        assertEquals(Float.valueOf(58f), Float.valueOf(AcidTheme.ROW_DIVIDER_INDENT_DP), "Derived row divider indent");

        // Contrast helper onColor
        assertEquals(Integer.valueOf(android.graphics.Color.BLACK), Integer.valueOf(AcidTheme.onColor(AcidTheme.DARK_ACCENT)), "Black text on neon green");
        assertEquals(Integer.valueOf(android.graphics.Color.WHITE), Integer.valueOf(AcidTheme.onColor(AcidTheme.LIGHT_ACCENT)), "White text on light emerald");
        assertEquals(Integer.valueOf(android.graphics.Color.WHITE), Integer.valueOf(AcidTheme.onColor(AcidTheme.COLOR_BLUE)), "White text on sky blue");
        assertEquals(Integer.valueOf(android.graphics.Color.WHITE), Integer.valueOf(AcidTheme.onColor(AcidTheme.COLOR_RED)), "White text on danger red");

        // AcidAsync cancellation prevents callback execution
        AcidAsync.Handle cancelled = AcidAsync.run(new AcidAsync.Task<String>() {
            @Override public String execute() throws Exception {
                Thread.sleep(80);
                return "done";
            }
        }, new AcidAsync.Callback<String>() {
            @Override public void onSuccess(String result) {
                throw new AssertionError("Cancelled task must not invoke onSuccess");
            }
            @Override public void onError(Exception error) {}
        });
        cancelled.cancel();
        assertTrue(cancelled.isCancelled(), "handle marked cancelled");
        Thread.sleep(110);
    }

    private static void testAgentToolRegistry() throws Exception {
        List<String> names = AcidSandboxTools.getRegisteredToolNames();
        assertEquals(5, Integer.valueOf(names.size()), "5 core tools registered");
        assertTrue(names.contains("browser") && names.contains("read") && names.contains("write")
            && names.contains("edit") && names.contains("bash"), "all 5 tool names present");

        org.json.JSONArray openaiSchema = AcidSandboxTools.buildToolsSchema();
        org.json.JSONArray mcpSchema = AcidSandboxTools.buildMcpToolsList();
        assertEquals(5, Integer.valueOf(openaiSchema.length()), "5 OpenAI tool definitions");
        assertEquals(5, Integer.valueOf(mcpSchema.length()), "5 MCP tool definitions");
        assertEquals("browser", mcpSchema.getJSONObject(0).getString("name"), "first MCP tool is browser");
        assertTrue(mcpSchema.getJSONObject(0).optJSONObject("inputSchema") != null, "MCP inputSchema populated");
    }
}
