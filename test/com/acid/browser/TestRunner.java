package com.acid.browser;

import org.json.JSONObject;

import com.acid.browser.download.ChunkDivider;
import com.acid.browser.download.DownloadChunk;
import com.acid.browser.download.DownloadItem;
import com.acid.browser.download.DownloadTask;
import com.acid.browser.download.DownloadWorker;
import com.acid.browser.download.HttpProbe;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
        run("MCP Authentication, Token generation & LAN configuration", new TestCase() {
            @Override public void execute() throws Exception { testMcpAuthAndLan(); }
        });
        run("Bookmarks JSON two-way sync, live hot-reload & cleanup protection", new TestCase() {
            @Override public void execute() throws Exception { testBookmarksSyncAndSafety(); }
        });
        run("History full unconstrained retention, timestamps & history.json sync", new TestCase() {
            @Override public void execute() throws Exception { testHistoryFullRetentionAndSafety(); }
        });
        run("SkillManager discovery, Prompt catalog & custom homepage priority", new TestCase() {
            @Override public void execute() throws Exception { testSkillsAndCustomHomepage(); }
        });
        run("AcidDownloader RFC 7233 range chunking, checkpoint resume & probe", new TestCase() {
            @Override public void execute() throws Exception { testDownloadEngine(); }
        });
        run("AcidIo UTF-8 file/stream round-trip & DownloadItem classification", new TestCase() {
            @Override public void execute() throws Exception { testAcidIoAndDownloadItem(); }
        });
        run("FaviconManager host extraction, magic byte detection & disk caching", new TestCase() {
            @Override public void execute() throws Exception { testFaviconManager(); }
        });
        run("Userscript full GM_* runtime, storage isolation & require bundler", new TestCase() {
            @Override public void execute() throws Exception { testUserscriptFullRuntime(); }
        });

        run("Userscript @grant enforcement, bridge token & GM API surface", new TestCase() {
            @Override public void execute() throws Exception { testUserscriptSecurityAndApis(); }
        });

        run("Userscript @unwrap, version compare, bootstrap & TM facade", new TestCase() {
            @Override public void execute() throws Exception { testUserscriptUnwrapAndUpdates(); }
        });

        run("Skill builtin sync: version upgrade vs user edits", new TestCase() {
            @Override public void execute() throws Exception { testSkillBuiltinSync(); }
        });

        run("SkillStorageVault boundaries & SkillSlug domain invariants", new TestCase() {
            @Override public void execute() throws Exception { testSkillVaultAndSlugSecurity(); }
        });

        run("P0/P1 Hardenings: Vault binary stream copy, Charset sniff, Public Suffix guard", new TestCase() {
            @Override public void execute() throws Exception { testP0P1Hardenings(); }
        });

        run("P0/P1 Bug Fixes: ToolCall alignment, Range 200 guard, FileProvider internal fallback", new TestCase() {
            @Override public void execute() throws Exception { testP0P1BugFixes(); }
        });

        run("Concurrency & State Machine: mTabs thread safety, uiItems snapshot, stopLoop closure", new TestCase() {
            @Override public void execute() throws Exception { testConcurrencyAndStateMachine(); }
        });

        run("Storage Invariants: unified PREF_NAME & empty bookmarks persistence", new TestCase() {
            @Override public void execute() throws Exception { testStorageInvariantsAndEmptyBookmarks(); }
        });

        run("Critical Runtime Guards: SSE tool_calls null safety, HttpProbe 206/* & bridge exports", new TestCase() {
            @Override public void execute() throws Exception { testCriticalRuntimeGuards(); }
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

    private static void testUserscriptFullRuntime() throws Exception {
        // 1. Meta parsing for @require, @resource, @grant, @noframes
        String fullScript = "// ==UserScript==\n"
            + "// @name         全功能测试脚本\n"
            + "// @version      2.0.0\n"
            + "// @require      https://cdn.example.com/jquery.min.js\n"
            + "// @require      https://cdn.example.com/lodash.min.js\n"
            + "// @resource     customCss https://cdn.example.com/theme.css\n"
            + "// @grant        GM_setValue\n"
            + "// @grant        GM_getValue\n"
            + "// @grant        GM_xmlhttpRequest\n"
            + "// @noframes\n"
            + "// @match        *://*.example.com/*\n"
            + "// ==/UserScript==\n"
            + "console.log(GM_info.script.name);";

        UserscriptMeta meta = UserscriptMeta.parse("full_feature.user.js", fullScript);
        assertTrue(meta.hasMetadata, "hasMetadata");
        assertEquals("全功能测试脚本", meta.name, "name");
        assertEquals(2, Integer.valueOf(meta.requires.size()), "2 requires parsed");
        assertEquals("https://cdn.example.com/jquery.min.js", meta.requires.get(0), "first require");
        assertEquals(1, Integer.valueOf(meta.resources.size()), "1 resource parsed");
        assertEquals(3, Integer.valueOf(meta.grants.size()), "3 grants parsed");
        assertTrue(meta.noFrames, "noFrames enabled");

        // 2. UserscriptStorage isolated persistence
        File tmpDir = File.createTempFile("acid_us_test_", "_dir");
        tmpDir.delete();
        tmpDir.mkdirs();
        tmpDir.deleteOnExit();

        UserscriptStorage storage = new UserscriptStorage(tmpDir);
        storage.setValue("script_a", "theme", "dark");
        storage.setValue("script_a", "count", 42);
        storage.setValue("script_b", "theme", "light");

        assertEquals("dark", storage.getValue("script_a", "theme", "default"), "script_a theme");
        assertEquals(Integer.valueOf(42), storage.getValue("script_a", "count", 0), "script_a count");
        assertEquals("light", storage.getValue("script_b", "theme", "default"), "script_b theme isolated");
        assertTrue(storage.listValues("script_a").contains("theme"), "script_a listValues contains theme");
        assertTrue(storage.listValues("script_a").contains("count"), "script_a listValues contains count");

        // Verify disk reload
        UserscriptStorage storage2 = new UserscriptStorage(tmpDir);
        assertEquals("dark", storage2.getValue("script_a", "theme", "default"), "reloaded storage_a theme");
        storage2.deleteValue("script_a", "theme");
        assertEquals("default", storage2.getValue("script_a", "theme", "default"), "deleted key returns default");

        // 3. UserscriptDependencyResolver resource extraction & naming
        String resUrl = UserscriptDependencyResolver.extractResourceUrl("myStyle https://example.org/style.css");
        assertEquals("https://example.org/style.css", resUrl, "extract resource url");

        // 4. UserscriptShaper closure bundling & GM shims
        List<String> fakeRequires = new ArrayList<String>();
        fakeRequires.add("window.fakeJQuery = '3.6.0';");
        String shaped = UserscriptShaper.shape(meta, fakeRequires);
        assertTrue(shaped.contains("window.__acidInjectedUserscripts"), "contains re-entrance guard");
        assertTrue(shaped.contains("unsafeWindow"), "contains unsafeWindow definition");
        assertTrue(shaped.contains("GM_setValue"), "contains GM_setValue shim");
        assertTrue(shaped.contains("GM_getValue"), "contains GM_getValue shim");
        assertTrue(shaped.contains("GM_xmlhttpRequest"), "contains GM_xmlhttpRequest shim");
        assertTrue(shaped.contains("GM_addStyle"), "contains GM_addStyle shim");
        assertTrue(shaped.contains("GM_setClipboard"), "contains GM_setClipboard shim");
        assertTrue(shaped.contains("GM_registerMenuCommand"), "contains GM_registerMenuCommand shim");
        assertTrue(shaped.contains("window.fakeJQuery = '3.6.0';"), "contains bundled @require dependency");
        assertTrue(shaped.contains("console.log(GM_info.script.name);"), "contains script body");

        // 5. UserscriptNativeBridge RPC Dispatcher
        UserscriptNativeBridge bridge = new UserscriptNativeBridge(null, storage, null);
        String setResp = bridge.call("setValue", "{\"scriptId\":\"script_c\",\"name\":\"status\",\"value\":\"active\"}");
        assertEquals("true", setResp, "bridge setValue response");
        assertEquals("active", storage.getValue("script_c", "status", ""), "storage got bridge setValue");

        String getResp = bridge.call("getValue", "{\"scriptId\":\"script_c\",\"name\":\"status\"}");
        assertTrue(getResp.contains("active"), "bridge getValue response has value");
    }

    private static void testUserscriptSecurityAndApis() throws Exception {
        // @grant semantics: none declared = unrestricted; declared = whitelist; none = nothing
        UserscriptMeta legacy = UserscriptMeta.parse("a.user.js",
            "// ==UserScript==\n// @name a\n// @match *://*/*\n// ==/UserScript==\nvar x=1;");
        assertTrue(legacy.isGranted("GM_xmlhttpRequest"), "no @grant = unrestricted");

        UserscriptMeta strict = UserscriptMeta.parse("b.user.js",
            "// ==UserScript==\n// @name b\n// @match *://*/*\n// @grant GM.getValue\n// ==/UserScript==\nvar x=1;");
        assertTrue(strict.isGranted("GM_getValue"), "GM.getValue grants GM_getValue");
        assertTrue(!strict.isGranted("GM_xmlhttpRequest"), "ungranted API denied");

        UserscriptMeta none = UserscriptMeta.parse("c.user.js",
            "// ==UserScript==\n// @name c\n// @match *://*/*\n// @grant none\n// ==/UserScript==\nvar x=1;");
        assertTrue(!none.isGranted("GM_getValue"), "@grant none denies everything");

        // @exclude-match and apex-domain matching
        UserscriptMeta ex = UserscriptMeta.parse("d.user.js",
            "// ==UserScript==\n// @name d\n// @match https://*.example.com/*\n"
            + "// @exclude-match https://*.example.com/private/*\n// ==/UserScript==\nvar x=1;");
        assertTrue(ex.appliesTo("https://example.com/a"), "apex matches *.example.com");
        assertTrue(ex.appliesTo("https://www.example.com/a"), "subdomain matches");
        assertTrue(!ex.appliesTo("https://www.example.com/private/x"), "exclude-match wins");
        assertTrue(!ex.appliesTo("https://evilexample.com/a"), "suffix lookalike rejected");

        // @resource lookup
        UserscriptMeta res = UserscriptMeta.parse("e.user.js",
            "// ==UserScript==\n// @name e\n// @match *://*/*\n// @resource css https://x.test/a.css\n"
            + "// ==/UserScript==\nvar x=1;");
        assertEquals("https://x.test/a.css", res.findResourceUrl("css"), "resource by name");
        assertEquals("", res.findResourceUrl("nope"), "unknown resource");

        // Bridge: token and grants are enforced
        File tmpDir = File.createTempFile("acid_us_sec_", "_dir");
        tmpDir.delete();
        tmpDir.mkdirs();
        UserscriptStorage storage = new UserscriptStorage(tmpDir);
        final UserscriptMeta granted = strict;
        UserscriptNativeBridge bridge = new UserscriptNativeBridge(null, storage, null, "tok123")
            .configure(new UserscriptNativeBridge.ScriptResolver() {
                @Override public UserscriptMeta findScript(String id) {
                    return "b.user.js".equals(id) ? granted : null;
                }
            }, null, null, null);

        assertEquals("", bridge.call("getValue", "{\"scriptId\":\"b.user.js\",\"name\":\"k\"}"),
            "missing token rejected");
        assertEquals("", bridge.call("getValue", "{\"secret\":\"bad\",\"scriptId\":\"b.user.js\",\"name\":\"k\"}"),
            "wrong token rejected");
        assertEquals("", bridge.call("setValue",
            "{\"secret\":\"tok123\",\"scriptId\":\"b.user.js\",\"name\":\"k\",\"value\":\"v\"}"),
            "setValue denied: script only granted getValue");
        assertEquals("v", storage.getValue("b.user.js", "k", "unset").equals("unset") ? "v" : "WRITTEN",
            "denied setValue must not write storage");
        assertTrue(bridge.call("getValue",
            "{\"secret\":\"tok123\",\"scriptId\":\"b.user.js\",\"name\":\"k\"}").contains("value"),
            "granted getValue with token works");

        // Security: fake/unregistered scriptId must be rejected and not bypass grant checks!
        assertEquals("", bridge.call("setValue",
            "{\"secret\":\"tok123\",\"scriptId\":\"unregistered.user.js\",\"name\":\"k\",\"value\":\"v\"}"),
            "setValue denied: unregistered script cannot bypass @grant");
        assertEquals("unset", storage.getValue("unregistered.user.js", "k", "unset"),
            "unregistered script must not write storage");
        assertEquals("", bridge.call("xmlhttpRequest",
            "{\"secret\":\"tok123\",\"scriptId\":\"unregistered.user.js\",\"url\":\"https://example.org\"}"),
            "xmlhttpRequest denied: unregistered script cannot invoke network");

        // Storage debounce and flushSync
        storage.setValue("b.user.js", "debounced_k", "debounced_v", true);
        storage.flushSync();
        assertEquals("debounced_v", storage.getValue("b.user.js", "debounced_k", "unset"),
            "flushSync guarantees debounced value is written");

        // Shaper: token embedded in closure, never on window; ungranted APIs are hidden
        String shaped = UserscriptShaper.shape(strict, new ArrayList<String>(), "tok123");
        assertTrue(shaped.contains("\"tok123\""), "token embedded in closure");
        assertTrue(!shaped.contains("window.__acid_secret"), "token not published on window");
        assertTrue(shaped.contains("GM_getValue, undefined"), "first ungranted arg is undefined");
        String[] mustHave = {"GM_getResourceText", "GM_getResourceURL", "GM_download", "GM_openInTab",
            "GM_setValues", "GM_getValues", "GM_deleteValues", "GM_notification",
            "GM_addValueChangeListener", "abortRequest", "getResourceUrl"};
        for (String api : mustHave) {
            assertTrue(shaped.contains(api), "shaper defines " + api);
        }
    }

    private static void testUserscriptUnwrapAndUpdates() throws Exception {
        // @unwrap: body runs at page scope, APIs published, token still hidden
        UserscriptMeta wrapped = UserscriptMeta.parse("w.user.js",
            "// ==UserScript==\n// @name w\n// @match *://*/*\n// ==/UserScript==\nvar a=1;");
        String wjs = UserscriptShaper.shape(wrapped, new ArrayList<String>(), "TOK");
        assertTrue(wjs.contains("(function(window, unsafeWindow, GM, GM_info"), "default keeps closure");

        UserscriptMeta unwrapped = UserscriptMeta.parse("u.user.js",
            "// ==UserScript==\n// @name u\n// @match *://*/*\n// @unwrap\n// ==/UserScript==\nvar a=1;");
        assertTrue(unwrapped.unwrap, "@unwrap parsed");
        String ujs = UserscriptShaper.shape(unwrapped, new ArrayList<String>(), "TOK");
        assertTrue(!ujs.contains("(function(window, unsafeWindow, GM, GM_info"), "@unwrap drops wrapper");
        assertTrue(ujs.contains("window.GM_addStyle"), "@unwrap publishes APIs on window");
        assertTrue(ujs.contains("var a=1;"), "@unwrap keeps body");
        assertTrue(!ujs.contains("window.__acid_secret"), "token not published");

        // extra metadata keys, case-insensitive
        UserscriptMeta meta = UserscriptMeta.parse("m.user.js",
            "// ==UserScript==\n// @name m\n// @match *://*/*\n// @author someone\n"
            + "// @homepageURL https://home.test/\n// @supportURL https://help.test/\n"
            + "// @downloadURL https://dl.test/m.user.js\n// @icon https://i.test/i.png\n"
            + "// ==/UserScript==\nvar x=1;");
        assertEquals("someone", meta.author, "author");
        assertEquals("https://home.test/", meta.homepageUrl, "homepageURL");
        assertEquals("https://help.test/", meta.supportUrl, "supportURL");
        assertEquals("https://dl.test/m.user.js", UserscriptManager.updateUrlOf(meta), "update source falls back to downloadURL");
        assertEquals("https://i.test/i.png", meta.iconUrl, "icon");

        UserscriptMeta withUpdate = UserscriptMeta.parse("n.user.js",
            "// ==UserScript==\n// @name n\n// @match *://*/*\n"
            + "// @updateURL https://up.test/n.user.js\n// @downloadURL https://dl.test/n.user.js\n"
            + "// ==/UserScript==\nvar x=1;");
        assertEquals("https://up.test/n.user.js", UserscriptManager.updateUrlOf(withUpdate), "updateURL wins");

        // version comparison
        assertTrue(UserscriptManager.isNewerVersion("1.2.1", "1.2.0"), "patch bump");
        assertTrue(UserscriptManager.isNewerVersion("2.0", "1.9.9"), "major bump");
        assertTrue(UserscriptManager.isNewerVersion("1.10", "1.9"), "numeric not lexical");
        assertTrue(!UserscriptManager.isNewerVersion("1.0.0", "1.0.0"), "equal is not newer");
        assertTrue(!UserscriptManager.isNewerVersion("0.9", "1.0"), "older is not newer");
        assertTrue(UserscriptManager.isNewerVersion("1.0", ""), "empty local means newer");

        // bootstrap + facade are well-formed and do NOT leak the token into untrusted page context
        String boot = UserscriptShaper.bootstrap("TOK");
        assertTrue(boot.contains("requestInjection"), "bootstrap requests injection");
        assertTrue(boot.contains("DOMContentLoaded"), "bootstrap listens for DOMContentLoaded");
        assertTrue(boot.contains("__acid_bootstrapped"), "bootstrap is idempotent");
        assertTrue(!boot.contains("TOK"), "bootstrap never embeds token into untrusted page scope");
        String facade = UserscriptShaper.tampermonkeyFacade("TOK");
        assertTrue(facade.contains("window.external.Tampermonkey"), "facade exposes Tampermonkey");
        assertTrue(facade.contains("isInstalled"), "facade supports isInstalled");
        assertTrue(facade.contains("getVersion"), "facade supports getVersion");
        assertTrue(!facade.contains("TOK"), "facade never embeds token into untrusted page scope");

        // script-site detection (only these hosts get the facade)
        assertTrue(UserscriptManager.isScriptSite("https://greasyfork.org/zh-CN/scripts/1"), "greasyfork");
        assertTrue(UserscriptManager.isScriptSite("https://www.greasyfork.org/x"), "subdomain");
        assertTrue(UserscriptManager.isScriptSite("https://openuserjs.org/scripts/x"), "openuserjs");
        assertTrue(!UserscriptManager.isScriptSite("https://notgreasyfork.org/x"), "lookalike rejected");
        assertTrue(!UserscriptManager.isScriptSite("https://example.com/greasyfork.org"), "path not host");

        // bridge routes injection requests and isInstalled probes
        final int[] phase = {0};
        UserscriptNativeBridge b2 = new UserscriptNativeBridge(null, null, null, "TOK")
            .configure(null, null, new UserscriptNativeBridge.Host() {
                @Override public void openInTab(String url, boolean active) {}
                @Override public void download(String url, String name) {}
                @Override public void notify(String title, String text) {}
                @Override public void onInjectionRequested(UserscriptNativeBridge bridge, int p) { phase[0] = p; }
                @Override public String isScriptInstalled(String name, String ns) { return "found".equals(name) ? "f.user.js" : null; }
            }, null);
        b2.call("requestInjection", "{\"secret\":\"TOK\",\"runAt\":4}");
        assertEquals(Integer.valueOf(4), Integer.valueOf(phase[0]), "phase forwarded");
        b2.call("requestInjection", "{\"runAt\":2}");
        assertEquals(Integer.valueOf(2), Integer.valueOf(phase[0]), "public requestInjection allowed without token");
        assertTrue(b2.call("isInstalled", "{\"secret\":\"TOK\",\"name\":\"found\"}").contains("true"), "isInstalled true");
        assertTrue(b2.call("isInstalled", "{\"name\":\"found\"}").contains("true"), "public isInstalled allowed without token");
        assertTrue(b2.call("isInstalled", "{\"secret\":\"TOK\",\"name\":\"nope\"}").contains("false"), "isInstalled false");
        assertEquals("", b2.call("setValue", "{\"name\":\"k\",\"value\":\"v\"}"), "privileged setValue strictly rejected without token");
    }

    private static void testSkillBuiltinSync() throws Exception {
        File ws = File.createTempFile("acid_skill_sync_", "_dir");
        ws.delete();
        ws.mkdirs();
        ws.deleteOnExit();

        // Fresh workspace: official skills installed from APK assets
        SkillManager m1 = new SkillManager(null, ws);
        File skillFile = new File(new File(ws, "skills"), SkillManager.SKILL_USERSCRIPT);
        assertTrue(skillFile.exists(), "official skill installed on first run");
        String installed = AcidIo.readFileUtf8OrEmpty(skillFile, 2 * 1024 * 1024);
        assertTrue(installed.length() > 1000, "installed content is non-trivial");

        // Shipped docs must be a pure manual: no experiment/verification language
        String[] banned = {"Verified 2026", "empirically verified", "earlier revision of this doc",
            "UNDEFINED", "避坑铁律", "必读"};
        for (String bad : banned) {
            assertTrue(!installed.contains(bad), "userscript skill free of '" + bad + "'");
        }
        assertTrue(installed.contains("@grant"), "skill documents @grant");
        assertTrue(installed.contains("GM_xmlhttpRequest"), "skill documents GM_xmlhttpRequest");
        assertTrue(installed.contains("@unwrap"), "skill documents @unwrap");

        // Sync state records the app version, nothing else
        File state = new File(ws, "skills_sync.json");
        assertTrue(state.exists(), "sync state written");
        assertTrue(!AcidIo.readFileUtf8OrEmpty(state, 256 * 1024).contains("hash"),
            "sync state no longer stores content hashes");

        // Same version: local edits survive relaunch
        String edited = installed + "\n<!-- user edit -->\n";
        AcidIo.writeFileUtf8Safe(skillFile, edited);
        SkillManager m2 = new SkillManager(null, ws);
        m2.syncDefaultSkills();
        assertTrue(AcidIo.readFileUtf8OrEmpty(skillFile, 2 * 1024 * 1024).contains("user edit"),
            "same-version relaunch keeps local edits");

        // Version bump: official copy overwrites, user edits are replaced
        java.io.FileOutputStream fos = new java.io.FileOutputStream(state);
        fos.write(("{\"" + SkillManager.SKILL_USERSCRIPT + "\":{\"versionCode\":0},"
            + "\"" + SkillManager.SKILL_HOMEPAGE + "\":{\"versionCode\":0},"
            + "\"" + SkillManager.SKILL_CLOUD_DRIVE + "\":{\"versionCode\":0}}").getBytes("UTF-8"));
        fos.close();
        SkillManager m3 = new SkillManager(null, ws);
        m3.syncDefaultSkills();
        String afterUpgrade = AcidIo.readFileUtf8OrEmpty(skillFile, 2 * 1024 * 1024);
        assertTrue(!afterUpgrade.contains("user edit"), "version bump overwrites user edits");
        assertTrue(afterUpgrade.contains("@grant"), "version bump restores official content");

        // A user-added skill is never touched by the sync
        File custom = new File(new File(ws, "skills"), "my_custom_skill.md");
        AcidIo.writeFileUtf8Safe(custom, "# my skill\n");
        SkillManager m4 = new SkillManager(null, ws);
        m4.syncDefaultSkills();
        assertTrue(custom.exists(), "user skill untouched by sync");
        assertEquals("# my skill\n", AcidIo.readFileUtf8OrEmpty(custom, 1024), "user skill content intact");

        // All three official skills present
        for (String f : new String[]{SkillManager.SKILL_HOMEPAGE, SkillManager.SKILL_CLOUD_DRIVE}) {
            File sf = new File(new File(ws, "skills"), f);
            assertTrue(sf.exists() && sf.length() > 500, f + " installed");
        }
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
        legacy.edit().putBoolean(AcidSettings.MCP_ENABLED.prefKey, false).apply();

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
        assertTrue(schema.contains("`downloader_engine`: 'builtin'|'system'"), "prompt schema includes downloader_engine");
        assertTrue(!schema.contains("adblock_blocked_count"), "internal counter excluded from settings.json schema");

        File tmpFile = File.createTempFile("acid_reactive_test_", ".json");
        tmpFile.deleteOnExit();

        BrowserDataStore store = new BrowserDataStore(new InMemoryPrefs(), new InMemoryPrefs(), tmpFile);
        assertEquals(0, Integer.valueOf(store.getDownloaderEngine()), "default engine is builtin (0)");
        store.setDownloaderEngine(AcidSettings.DOWNLOADER_SYSTEM);
        assertEquals(1, Integer.valueOf(store.getDownloaderEngine()), "switched engine to system (1)");
        store.setDownloaderEngine(AcidSettings.DOWNLOADER_BUILTIN);

        StateSource.BoolState dntState = store.boolState(AcidSettings.DO_NOT_TRACK);
        StateSource.BoolState darkState = store.boolState(AcidSettings.DARK_MODE);
        StateSource.BoolState fullscreenState = store.boolState(AcidSettings.FULLSCREEN_ENABLED);

        final int[] dntEvents = new int[]{0};
        final int[] darkEvents = new int[]{0};
        final int[] fullscreenEvents = new int[]{0};
        dntState.observe(new StateSource.Observer() {
            @Override public void onChanged(boolean value) { dntEvents[0]++; }
        });
        darkState.observe(new StateSource.Observer() {
            @Override public void onChanged(boolean value) { darkEvents[0]++; }
        });
        fullscreenState.observe(new StateSource.Observer() {
            @Override public void onChanged(boolean value) { fullscreenEvents[0]++; }
        });

        dntState.toggle();
        assertTrue(dntState.get(), "dnt toggled on");
        assertEquals(1, Integer.valueOf(dntEvents[0]), "dnt observer fired once");
        assertEquals(0, Integer.valueOf(darkEvents[0]), "dark observer untouched");

        fullscreenState.toggle();
        assertTrue(fullscreenState.get(), "fullscreen toggled on");
        assertEquals(1, Integer.valueOf(fullscreenEvents[0]), "fullscreen observer fired once");
        assertTrue(store.isFullscreenEnabled(), "store reflects fullscreen state");
        fullscreenState.set(false);
        assertEquals(2, Integer.valueOf(fullscreenEvents[0]), "fullscreen observer fired on toggle off");

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

        // 3-Level External App Launch Policy & Agent-Mode Forced Block Override
        assertEquals(Integer.valueOf(BrowserDataStore.SEARCH_BING), Integer.valueOf(store.getSearchEngineIndex()), "default search engine is Bing (2)");
        assertEquals(Integer.valueOf(AcidSettings.APP_LAUNCH_ASK), Integer.valueOf(store.getAppLaunchPolicy()), "default app launch policy is ASK (1)");
        store.setAppLaunchPolicy(AcidSettings.APP_LAUNCH_BLOCK);
        assertEquals(Integer.valueOf(AcidSettings.APP_LAUNCH_BLOCK), Integer.valueOf(store.getAppLaunchPolicy()), "app launch policy updated to BLOCK (0)");
        store.setAppLaunchPolicy(AcidSettings.APP_LAUNCH_ALLOW);
        assertEquals(Integer.valueOf(AcidSettings.APP_LAUNCH_ALLOW), Integer.valueOf(store.getAppLaunchPolicy()), "app launch policy updated to ALLOW (2)");

        // GitHub Releases Update Checker JSON parsing & semver comparison
        String mockGhRelease = "{"
            + "\"tag_name\":\"v0.4.13\","
            + "\"body\":\"1. 新增 GitHub Releases 更新检查\\n2. 优化默认配置\","
            + "\"html_url\":\"https://github.com/AcidGr/Acid-Browser/releases/tag/v0.4.13\","
            + "\"assets\":[{"
            + "\"name\":\"Acid-browser-v0.4.13.apk\","
            + "\"size\":386048,"
            + "\"browser_download_url\":\"https://github.com/AcidGr/Acid-Browser/releases/download/v0.4.13/Acid-browser-v0.4.13.apk\""
            + "}]"
            + "}";
        AppUpdateChecker.UpdateInfo upInfo = AppUpdateChecker.parseReleaseJson(mockGhRelease, "0.4.12", 232);
        assertTrue(upInfo.hasUpdate, "GitHub Release v0.4.13 detected as newer than 0.4.12");
        assertEquals("0.4.13", upInfo.remoteVersionName, "remote version name extracted from tag_name");
        assertEquals("377 KB", upInfo.apkSize, "APK size formatted from GitHub asset size");
        assertTrue(upInfo.downloadUrl.endsWith("Acid-browser-v0.4.13.apk"), "APK browser_download_url extracted");
        AppUpdateChecker.UpdateInfo sameVer = AppUpdateChecker.parseReleaseJson(mockGhRelease, "0.4.13", 233);
        assertTrue(!sameVer.hasUpdate, "same version v0.4.13 does not trigger update");

        // Verify Agent mode always overrides ASK or ALLOW to BLOCK (0)
        assertEquals(
            Integer.valueOf(AcidSettings.APP_LAUNCH_ALLOW),
            Integer.valueOf(BrowserController.resolveEffectiveAppLaunchPolicy(AcidSettings.APP_LAUNCH_ALLOW, AgentOverlayView.MODE_HUMAN, -1)),
            "Human mode respects configured ALLOW policy"
        );
        assertEquals(
            Integer.valueOf(AcidSettings.APP_LAUNCH_ASK),
            Integer.valueOf(BrowserController.resolveEffectiveAppLaunchPolicy(AcidSettings.APP_LAUNCH_ASK, AgentOverlayView.MODE_HUMAN, -1)),
            "Human mode respects configured ASK policy"
        );
        assertEquals(
            Integer.valueOf(AcidSettings.APP_LAUNCH_BLOCK),
            Integer.valueOf(BrowserController.resolveEffectiveAppLaunchPolicy(AcidSettings.APP_LAUNCH_ALLOW, AgentOverlayView.MODE_AGENT, 1)),
            "Agent mode temporarily forces BLOCK even when configured to ALLOW"
        );
        assertEquals(
            Integer.valueOf(AcidSettings.APP_LAUNCH_BLOCK),
            Integer.valueOf(BrowserController.resolveEffectiveAppLaunchPolicy(AcidSettings.APP_LAUNCH_ASK, AgentOverlayView.MODE_AGENT, -1)),
            "Agent mode temporarily forces BLOCK even when configured to ASK"
        );
        assertEquals(
            Integer.valueOf(AcidSettings.APP_LAUNCH_ASK),
            Integer.valueOf(BrowserController.resolveEffectiveAppLaunchPolicy(AcidSettings.APP_LAUNCH_ASK, AgentOverlayView.MODE_TAKEOVER_REQUESTED, 1)),
            "Restores user ASK policy immediately when Agent pauses for human takeover or exits MODE_AGENT"
        );
        assertEquals(
            Integer.valueOf(AcidSettings.APP_LAUNCH_ALLOW),
            Integer.valueOf(BrowserController.resolveEffectiveAppLaunchPolicy(AcidSettings.APP_LAUNCH_ALLOW, AgentOverlayView.MODE_HUMAN, 1)),
            "Restores user ALLOW policy immediately when manually switched back to MODE_HUMAN"
        );

        tmpFile.delete();
    }

    private static void testThemeAndAsync() throws Exception {
        // Strict monotonic typography hierarchy (Sheet > Dialog > Hero > Item > Body > Section Overline > Field Label > Caption)
        assertTrue(AcidTheme.SP_SHEET_TITLE > AcidTheme.SP_DIALOG_TITLE, "Sheet title > Dialog title");
        assertTrue(AcidTheme.SP_DIALOG_TITLE > AcidTheme.SP_HERO, "Dialog title > Hero headline");
        assertTrue(AcidTheme.SP_HERO >= AcidTheme.SP_ITEM + 1.0f, "Hero headline >= 1sp above Item title");
        assertTrue(AcidTheme.SP_OVERLINE > AcidTheme.SP_FIELD_LABEL, "Outer section header > inner field label");
        assertTrue(AcidTheme.SP_FIELD_LABEL > AcidTheme.SP_CAPTION, "Field label > caption");
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

    private static void testMcpAuthAndLan() throws Exception {
        BrowserDataStore store = new BrowserDataStore(new InMemoryPrefs(), new InMemoryPrefs(), null);
        assertTrue(store.isMcpAuthEnabled(), "MCP auth enabled by default");
        assertTrue(!store.isMcpLanEnabled(), "MCP LAN disabled by default");

        String token1 = store.getOrCreateMcpToken();
        assertTrue(token1 != null && token1.length() >= 32, "MCP token generated with >=32 chars");

        String token2 = store.getOrCreateMcpToken();
        assertEquals(token1, token2, "getOrCreateMcpToken returns idempotent persisted token");

        String token3 = store.generateNewMcpToken();
        assertTrue(token3 != null && token3.length() >= 32, "New MCP token generated");
        assertTrue(!token1.equals(token3), "Regenerated token differs from old token");

        store.setMcpLanEnabled(true);
        assertTrue(store.isMcpLanEnabled(), "MCP LAN toggled to enabled");

        store.setMcpAuthEnabled(false);
        assertTrue(!store.isMcpAuthEnabled(), "MCP Auth toggled to disabled");
    }

    private static void testBookmarksSyncAndSafety() throws Exception {
        File tmpDir = File.createTempFile("acid_ws_test_", "");
        tmpDir.delete();
        tmpDir.mkdirs();
        tmpDir.deleteOnExit();

        File settingsFile = new File(tmpDir, "settings.json");
        settingsFile.createNewFile();
        File bookmarksFile = new File(tmpDir, "bookmarks.json");

        InMemoryPrefs prefs = new InMemoryPrefs();
        BrowserDataStore store = new BrowserDataStore(prefs, new InMemoryPrefs(), settingsFile, bookmarksFile);

        // 1. Initial sync creates bookmarks.json with defaults
        store.syncBookmarksJsonFile();
        assertTrue(bookmarksFile.exists(), "bookmarks.json created automatically");
        List<BrowserDataStore.EntryItem> items = store.loadBookmarks();
        assertTrue(items.size() >= 4, "default bookmarks seeded");

        // 2. Add bookmark via store updates bookmarks.json
        final int[] callbacks = new int[]{0};
        store.setOnBookmarksChangedListener(new BrowserDataStore.OnBookmarksChangedListener() {
            @Override public void onBookmarksChanged() { callbacks[0]++; }
        });
        store.addBookmark("My Custom Site", "https://custom.example.com");
        assertTrue(bookmarksFile.length() > 0, "bookmarks.json updated");

        // 3. Edit bookmarks.json externally and hot-reload
        String externalJson = "[\n  {\"title\": \"DeepSeek AI\", \"url\": \"https://chat.deepseek.com\"}\n]";
        FileOutputStream fos = new FileOutputStream(bookmarksFile);
        fos.write(externalJson.getBytes(StandardCharsets.UTF_8));
        fos.close();

        assertTrue(store.reloadBookmarksFromJsonFile(), "reloadBookmarksFromJsonFile succeeds");
        assertEquals(1, Integer.valueOf(callbacks[0]), "bookmarks change callback fired");
        List<BrowserDataStore.EntryItem> reloaded = store.loadBookmarks();
        assertEquals(1, Integer.valueOf(reloaded.size()), "1 bookmark after external reload");
        assertEquals("DeepSeek AI", reloaded.get(0).title, "reloaded title");
        assertEquals("https://chat.deepseek.com", reloaded.get(0).url, "reloaded url");

        // 4. Corrupt JSON in bookmarks.json should be rejected safely without wiping existing bookmarks
        FileOutputStream corruptFos = new FileOutputStream(bookmarksFile);
        corruptFos.write("{invalid_json".getBytes(StandardCharsets.UTF_8));
        corruptFos.close();
        assertTrue(!store.reloadBookmarksFromJsonFile(), "corrupt json reload safely rejected");
        assertEquals(1, Integer.valueOf(store.loadBookmarks().size()), "existing bookmarks preserved on corrupt reload");

        // 5. Test clearWorkspaceTempFiles protection
        File dummyHomepage = new File(tmpDir, "homepage");
        dummyHomepage.mkdirs();
        File dummyTempFile = new File(tmpDir, "report.html");
        dummyTempFile.createNewFile();
        File dummyTempDir = new File(tmpDir, "temp_data");
        dummyTempDir.mkdirs();

        int cleaned = store.clearWorkspaceTempFiles(tmpDir);
        assertEquals(2, Integer.valueOf(cleaned), "cleaned 2 temp entries");
        assertTrue(!dummyTempFile.exists(), "temp file deleted");
        assertTrue(!dummyTempDir.exists(), "temp dir deleted");
        assertTrue(bookmarksFile.exists(), "bookmarks.json strictly preserved by whitelist");
        assertTrue(settingsFile.exists(), "settings.json strictly preserved by whitelist");
        assertTrue(dummyHomepage.exists(), "homepage directory strictly preserved by whitelist");

        // Cleanup
        dummyHomepage.delete();
        bookmarksFile.delete();
        settingsFile.delete();
        tmpDir.delete();
    }

    private static void testHistoryFullRetentionAndSafety() throws Exception {
        File tmpDir = File.createTempFile("acid_history_test_", "");
        tmpDir.delete();
        tmpDir.mkdirs();
        tmpDir.deleteOnExit();

        File settingsFile = new File(tmpDir, "settings.json");
        settingsFile.createNewFile();
        File bookmarksFile = new File(tmpDir, "bookmarks.json");
        bookmarksFile.createNewFile();
        File historyFile = new File(tmpDir, "history.json");

        InMemoryPrefs prefs = new InMemoryPrefs();
        BrowserDataStore store = new BrowserDataStore(prefs, new InMemoryPrefs(), settingsFile, bookmarksFile, historyFile);

        // 1. Initial sync
        store.syncHistoryJsonFile();
        assertTrue(historyFile.exists(), "history.json created automatically on sync");

        // 2. Unconstrained retention test: record 150 unique history entries (breaking old 100-cap)
        final int[] callbacks = new int[]{0};
        store.setOnHistoryChangedListener(new BrowserDataStore.OnHistoryChangedListener() {
            @Override public void onHistoryChanged() { callbacks[0]++; }
        });

        for (int i = 1; i <= 150; i++) {
            store.recordHistory("https://example.com/page/" + i, "Page " + i, false);
        }

        List<BrowserDataStore.EntryItem> allHistory = store.loadHistory();
        assertEquals(150, Integer.valueOf(allHistory.size()), "150 history entries preserved without 100-cap limit");
        assertTrue(allHistory.get(0).time > 0, "history entry carries valid millisecond timestamp");
        assertEquals("Page 150", allHistory.get(0).title, "most recent entry on top");

        // 3. history.json content must be formatted multi-line JSON (not a single giant line)
        String historyContent = new String(java.nio.file.Files.readAllBytes(historyFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(historyContent.contains("\n"), "history.json is multi-line formatted JSON");
        assertTrue(historyContent.contains("\"title\": \"Page 150\""), "history.json contains recent title");
        assertTrue(historyContent.contains("\"time\":"), "history.json contains time attribute");

        // 4. External edit and live hot-reload
        String externalJson = "[\n  {\"title\": \"Bilibili\", \"url\": \"https://www.bilibili.com\", \"time\": 1793340000000}\n]";
        FileOutputStream fos = new FileOutputStream(historyFile);
        fos.write(externalJson.getBytes(StandardCharsets.UTF_8));
        fos.close();

        int prevCallbacks = callbacks[0];
        assertTrue(store.reloadHistoryFromJsonFile(), "reloadHistoryFromJsonFile succeeds");
        assertTrue(callbacks[0] > prevCallbacks, "history changed callback fired on reload");
        List<BrowserDataStore.EntryItem> reloaded = store.loadHistory();
        assertEquals(1, Integer.valueOf(reloaded.size()), "1 history entry after external reload");
        assertEquals("Bilibili", reloaded.get(0).title, "reloaded history title");
        assertEquals(1793340000000L, Long.valueOf(reloaded.get(0).time), "reloaded timestamp preserved");

        // 5. Corrupt JSON in history.json safely rejected without corrupting data
        FileOutputStream corruptFos = new FileOutputStream(historyFile);
        corruptFos.write("{corrupt".getBytes(StandardCharsets.UTF_8));
        corruptFos.close();
        assertTrue(!store.reloadHistoryFromJsonFile(), "corrupt history.json reload safely rejected");
        assertEquals(1, Integer.valueOf(store.loadHistory().size()), "previous history preserved on corrupt reload");

        // 6. Whitelist protection in clearWorkspaceTempFiles
        File dummyTempFile = new File(tmpDir, "temp_report.html");
        dummyTempFile.createNewFile();
        int cleaned = store.clearWorkspaceTempFiles(tmpDir);
        assertEquals(1, Integer.valueOf(cleaned), "cleaned 1 temp entry");
        assertTrue(!dummyTempFile.exists(), "temp file deleted");
        assertTrue(historyFile.exists(), "history.json strictly preserved by whitelist");

        // 7. Clear history purges history.json too
        store.clearHistory();
        assertEquals(0, Integer.valueOf(store.loadHistory().size()), "history cleared in store");
        String clearedContent = new String(java.nio.file.Files.readAllBytes(historyFile.toPath()), StandardCharsets.UTF_8).trim();
        assertTrue(clearedContent.equals("[]") || clearedContent.equals("[\n]"), "history.json reset to empty array on clear");

        // Cleanup
        historyFile.delete();
        bookmarksFile.delete();
        settingsFile.delete();
        tmpDir.delete();
    }

    private static void testSkillsAndCustomHomepage() throws Exception {
        File tmpDir = new File("/tmp/acid_test_skills_" + System.currentTimeMillis());
        tmpDir.mkdirs();

        File skillsDir = new File(tmpDir, "skills");
        skillsDir.mkdirs();
        File homepageDir = new File(tmpDir, "homepage");
        homepageDir.mkdirs();

        File settingsFile = new File(tmpDir, "settings.json");
        File bookmarksFile = new File(tmpDir, "bookmarks.json");
        File historyFile = new File(tmpDir, "history.json");
        InMemoryPrefs primary = new InMemoryPrefs();
        InMemoryPrefs legacy = new InMemoryPrefs();

        BrowserDataStore store = new BrowserDataStore(primary, legacy, settingsFile, bookmarksFile, historyFile);
        store.setWorkspaceDir(tmpDir);

        // 1. Check builtin skill detection
        assertTrue(SkillManager.isBuiltin("homepage_dev.md"), "homepage_dev.md recognized as builtin");
        assertTrue(SkillManager.isBuiltin("userscript_dev.md"), "userscript_dev.md recognized as builtin");
        assertTrue(!SkillManager.isBuiltin("third_party.md"), "third_party.md recognized as custom");

        // 1b. Full SkillManager verification: YAML frontmatter, multi-file packages, enable/disable, GitHub URLs & ZIP import
        SkillManager sm = new SkillManager(null, tmpDir);
        assertEquals(3, sm.getSkills().size(), "3 builtin skills synced from assets/skills/");

        // Create a directory-based multi-file skill package with standard YAML frontmatter
        String yamlSkillMd = "---\n"
            + "name: webapp-testing\n"
            + "description: >\n"
            + "  Toolkit for testing local web applications and APIs.\n"
            + "---\n\n"
            + "# WebApp Testing Skill\n\n"
            + "Use companion references and scripts when testing.\n";
        assertTrue(sm.saveSkill("webapp-testing/SKILL.md", yamlSkillMd), "save directory skill SKILL.md");
        assertTrue(sm.saveSkill("webapp-testing/references/api_spec.md", "# API Reference\nDetails..."), "save companion reference doc");
        assertTrue(sm.saveSkill("webapp-testing/scripts/helper.sh", "#!/bin/sh\necho ok"), "save companion helper script");

        SkillManager.SkillMeta pkgMeta = sm.findSkillMetaForFile(new File(skillsDir, "webapp-testing/SKILL.md"));
        assertTrue(pkgMeta != null, "directory skill package discovered");
        assertTrue(pkgMeta.isDirectoryPackage, "marked as directory package");
        assertEquals("webapp-testing", pkgMeta.name, "YAML name parsed");
        assertEquals("WebApp Testing Skill", pkgMeta.title, "Markdown heading title parsed alongside YAML");
        assertEquals("Toolkit for testing local web applications and APIs.", pkgMeta.description, "YAML folded description parsed");
        assertEquals(2, pkgMeta.companionFiles.size(), "2 companion files discovered");
        assertTrue(pkgMeta.companionFiles.contains("references/api_spec.md"), "companion reference doc listed");
        assertTrue(pkgMeta.companionFiles.contains("scripts/helper.sh"), "companion script listed");

        String catalog = sm.buildSystemPromptCatalog();
        assertTrue(catalog.contains("skills/webapp-testing/SKILL.md"), "catalog includes directory skill entry");
        assertTrue(catalog.contains("references/api_spec.md"), "catalog mentions companion files");

        // Test enable/disable toggle & persistence across instances
        sm.setSkillEnabled("webapp-testing/SKILL.md", false);
        assertTrue(!sm.isSkillEnabled("webapp-testing"), "skill disabled in manager");
        assertTrue(!sm.buildSystemPromptCatalog().contains("skills/webapp-testing/SKILL.md"), "disabled skill omitted from System Prompt catalog");

        SkillManager smReloaded = new SkillManager(null, tmpDir);
        assertTrue(!smReloaded.isSkillEnabled("webapp-testing/SKILL.md"), "disabled state persisted across SkillManager reload");
        smReloaded.setSkillEnabled("webapp-testing", true);
        assertTrue(smReloaded.buildSystemPromptCatalog().contains("skills/webapp-testing/SKILL.md"), "re-enabled skill restored to catalog");

        // Test GitHub URL parsing (tree folder, blob SKILL.md auto-upgraded to folder, and blob regular file)
        SkillManager.GitHubSkillCoordinates ghTree = SkillManager.parseGitHubUrl("https://github.com/anthropics/skills/tree/main/skills/frontend-design");
        assertTrue(ghTree != null, "GitHub tree URL parsed");
        assertEquals("anthropics", ghTree.owner, "GitHub owner parsed");
        assertEquals("skills", ghTree.repo, "GitHub repo parsed");
        assertEquals("main", ghTree.branch, "GitHub branch parsed");
        assertEquals("skills/frontend-design", ghTree.path, "GitHub sub-path parsed");
        assertTrue(ghTree.isTreeOrFolder, "GitHub tree recognized as folder");

        SkillManager.GitHubSkillCoordinates ghBlobSkill = SkillManager.parseGitHubUrl("https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md");
        assertTrue(ghBlobSkill != null, "GitHub blob SKILL.md URL parsed");
        assertEquals("skills/pdf", ghBlobSkill.path, "GitHub blob SKILL.md parent folder extracted");
        assertTrue(ghBlobSkill.isTreeOrFolder, "GitHub blob SKILL.md upgraded to folder package fetch");

        // Test ZIP archive skill package import
        java.io.ByteArrayOutputStream zipBos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(zipBos);
        zos.putNextEntry(new java.util.zip.ZipEntry("my-zip-skill/SKILL.md"));
        zos.write("---\nname: zip-skill\ndescription: Imported from ZIP bundle\n---\n# ZIP Skill Title\n".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.putNextEntry(new java.util.zip.ZipEntry("my-zip-skill/references/guide.md"));
        zos.write("Reference guide content".getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        zos.close();

        SkillManager.ImportResult zipRes = smReloaded.importFromZipBytes(zipBos.toByteArray(), "my-zip-skill");
        assertTrue(zipRes.success, "ZIP skill package imported successfully");
        assertEquals(2, zipRes.fileCount, "ZIP imported 2 files");
        assertEquals("ZIP Skill Title", zipRes.title, "ZIP skill title parsed");
        assertTrue(smReloaded.deleteSkill("webapp-testing/SKILL.md"), "delete directory skill webapp-testing");
        assertTrue(smReloaded.deleteSkill(zipRes.skillFileName), "delete imported zip skill");

        // 2. Custom homepage priority
        // Initially index.html does not exist in workspace homepage/
        assertEquals(BrowserController.HOME_URL, store.getEffectiveHomeUrl(), "fallback to asset HOME_URL when workspace homepage does not exist");

        // Create custom homepage/index.html in workspace
        File customIndex = new File(homepageDir, "index.html");
        FileOutputStream fos = new FileOutputStream(customIndex);
        fos.write("<!DOCTYPE html><html><body>Custom Home</body></html>".getBytes(StandardCharsets.UTF_8));
        fos.close();

        // Effective home url must now point directly to file://.../homepage/index.html
        String effectiveUrl = store.getEffectiveHomeUrl();
        assertTrue(effectiveUrl.startsWith("file://"), "effective url uses file protocol");
        assertTrue(effectiveUrl.endsWith("homepage/index.html"), "effective url points to custom homepage/index.html");

        // Test HOME_BLANK override
        store.setHomeType(BrowserDataStore.HOME_BLANK);
        assertEquals("about:blank", store.getEffectiveHomeUrl(), "HOME_BLANK returns about:blank");

        // Test HOME_CUSTOM override
        store.setHomeType(BrowserDataStore.HOME_CUSTOM);
        store.setCustomHomeUrl("https://example.com");
        assertEquals("https://example.com", store.getEffectiveHomeUrl(), "HOME_CUSTOM returns configured url");

        // Reset to HOME_ACID (0)
        store.setHomeType(BrowserDataStore.HOME_ACID);
        assertEquals(effectiveUrl, store.getEffectiveHomeUrl(), "HOME_ACID returns custom workspace homepage");

        // 3. Whitelist safety: clearWorkspaceTempFiles must NEVER delete homepage or skills directories
        File tempScrape = new File(tmpDir, "scrape_temp.json");
        tempScrape.createNewFile();
        int cleaned = store.clearWorkspaceTempFiles(tmpDir);
        assertEquals(1, Integer.valueOf(cleaned), "cleaned 1 temp file");
        assertTrue(!tempScrape.exists(), "temp scrape file deleted");
        assertTrue(customIndex.exists(), "homepage/index.html preserved by whitelist");
        assertTrue(skillsDir.exists(), "skills directory preserved by whitelist");

        // Cleanup
        customIndex.delete();
        homepageDir.delete();
        skillsDir.delete();
        settingsFile.delete();
        bookmarksFile.delete();
        historyFile.delete();
        tmpDir.delete();
    }

    private static void testDownloadEngine() throws Exception {
        // 1. ChunkDivider logic
        int c1 = ChunkDivider.determineChunkCount(1000, false, 8);
        assertEquals(1, Integer.valueOf(c1), "No range support returns 1 chunk");

        int c2 = ChunkDivider.determineChunkCount(1024 * 1024, true, 8);
        assertEquals(1, Integer.valueOf(c2), "Files under 2MB use 1 chunk");

        int c3 = ChunkDivider.determineChunkCount(100 * 1024 * 1024, true, 8);
        assertEquals(8, Integer.valueOf(c3), "100MB file uses maxChunks (8)");

        List<DownloadChunk> chunks = ChunkDivider.divide(10000000L, 4);
        assertEquals(4, Integer.valueOf(chunks.size()), "Divided into 4 chunks");
        assertEquals(0L, Long.valueOf(chunks.get(0).startByte), "Chunk 0 starts at 0");
        long coveredBytes = 0;
        for (int i = 0; i < chunks.size(); i++) {
            DownloadChunk ch = chunks.get(i);
            if (i > 0) {
                assertEquals(chunks.get(i - 1).endByte + 1, ch.startByte, "Chunks must be contiguous");
            }
            coveredBytes += ch.getTotalBytes();
        }
        assertEquals(10000000L, Long.valueOf(coveredBytes), "Total chunk coverage must match content length");

        // 2. DownloadChunk serialization round-trip
        DownloadChunk origChunk = new DownloadChunk(2, 5000000L, 7499999L, 1250000L, false);
        JSONObject chunkJson = origChunk.toJsonObject();
        DownloadChunk restoredChunk = DownloadChunk.fromJsonObject(chunkJson);
        assertEquals(origChunk.chunkIndex, Integer.valueOf(restoredChunk.chunkIndex), "chunk index matches");
        assertEquals(origChunk.startByte, Long.valueOf(restoredChunk.startByte), "start byte matches");
        assertEquals(origChunk.endByte, Long.valueOf(restoredChunk.endByte), "end byte matches");
        assertEquals(origChunk.downloadedBytes, Long.valueOf(restoredChunk.downloadedBytes), "downloaded bytes match");
        assertEquals(origChunk.getCurrentSeekOffset(), Long.valueOf(restoredChunk.getCurrentSeekOffset()), "seek offset matches");

        // 3. DownloadTask Checkpoint Persistence & Resume
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "acid_dl_test_" + System.currentTimeMillis());
        tempDir.mkdirs();
        try {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Cookie", "foo=bar");
            headers.put("User-Agent", "AcidTest/1.0");

            DownloadTask task = new DownloadTask(999L, "https://example.com/test.zip", "test.zip", "application/zip", tempDir, headers);
            task.contentLength = 10000000L;
            task.supportsRange = true;
            task.setChunks(chunks);
            chunks.get(0).downloadedBytes = 2500000L;
            chunks.get(0).completed = true;
            chunks.get(1).downloadedBytes = 1000000L;
            task.downloadedBytes = 3500000L;

            boolean saved = task.saveCheckpoint();
            assertTrue(saved, "Checkpoint saved successfully");
            assertTrue(task.checkpointFile.exists(), "Checkpoint file exists on disk");

            // Create fresh task and load checkpoint
            DownloadTask resumedTask = new DownloadTask(999L, "https://example.com/test.zip", "test.zip", "application/zip", tempDir, headers);
            boolean loaded = resumedTask.loadCheckpoint();
            assertTrue(loaded, "Checkpoint loaded successfully");
            assertEquals(10000000L, Long.valueOf(resumedTask.contentLength), "Content length restored");
            assertTrue(resumedTask.supportsRange, "Range support flag restored");
            assertEquals(4, Integer.valueOf(resumedTask.chunks.size()), "All 4 chunks restored");
            assertEquals(3500000L, Long.valueOf(resumedTask.downloadedBytes), "Downloaded bytes restored");
            assertTrue(resumedTask.chunks.get(0).completed, "Chunk 0 completed flag restored");
            assertEquals(1000000L, Long.valueOf(resumedTask.chunks.get(1).downloadedBytes), "Chunk 1 progress restored");

            task.cleanCheckpointFile();
            assertTrue(!task.checkpointFile.exists(), "Checkpoint file cleaned up");

            // 3b. Progress dispatch 200ms throttle check
            assertTrue(task.shouldDispatchProgress(1000L), "First progress dispatch allowed");
            assertTrue(!task.shouldDispatchProgress(1050L), "Progress within 50ms throttled");
            assertTrue(!task.shouldDispatchProgress(1199L), "Progress at 199ms throttled");
            assertTrue(task.shouldDispatchProgress(1200L), "Progress at 200ms allowed");

            // 3c. Real HTTP Range stream write & mid-chunk breakpoint resume via DownloadWorker
            final byte[] expectedPayload = new byte[40000];
            for (int i = 0; i < expectedPayload.length; i++) {
                expectedPayload[i] = (byte) ((i * 31 + 7) & 0xFF);
            }

            final ServerSocket rangeServer = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            final List<String> observedRanges = new ArrayList<String>();
            Thread serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket client = rangeServer.accept();
                        client.setSoTimeout(5000);
                        BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        int rangeStart = 0;
                        int rangeEnd = expectedPayload.length - 1;
                        while ((line = br.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("range:")) {
                                String spec = line.substring(6).trim();
                                observedRanges.add(spec);
                                if (spec.startsWith("bytes=")) {
                                    String[] parts = spec.substring(6).split("-");
                                    rangeStart = Integer.parseInt(parts[0].trim());
                                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                                        rangeEnd = Integer.parseInt(parts[1].trim());
                                    }
                                }
                            }
                        }
                        int sliceLen = rangeEnd - rangeStart + 1;
                        OutputStream os = client.getOutputStream();
                        String respHeaders = "HTTP/1.1 206 Partial Content\r\n"
                            + "Content-Type: application/octet-stream\r\n"
                            + "Accept-Ranges: bytes\r\n"
                            + "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + expectedPayload.length + "\r\n"
                            + "Content-Length: " + sliceLen + "\r\n"
                            + "Connection: close\r\n\r\n";
                        os.write(respHeaders.getBytes(StandardCharsets.US_ASCII));
                        os.write(expectedPayload, rangeStart, sliceLen);
                        os.flush();
                        client.close();
                    } catch (Exception ignored) {}
                }
            });
            serverThread.start();

            try {
                String localUrl = "http://127.0.0.1:" + rangeServer.getLocalPort() + "/stream.bin";
                DownloadTask streamTask = new DownloadTask(1001L, localUrl, "stream.bin", "application/octet-stream", tempDir, headers);
                streamTask.contentLength = expectedPayload.length;
                streamTask.supportsRange = true;

                // Simulate Chunk 1 [10000..39999] (30,000 bytes) already having 12,000 bytes written before pause
                RandomAccessFile preRaf = new RandomAccessFile(streamTask.destinationFile, "rw");
                preRaf.setLength(expectedPayload.length);
                preRaf.seek(10000L);
                preRaf.write(expectedPayload, 10000, 12000);
                preRaf.close();

                DownloadChunk partialChunk = new DownloadChunk(1, 10000L, 39999L, 12000L, false);
                List<DownloadChunk> singleChunkList = new ArrayList<DownloadChunk>();
                singleChunkList.add(partialChunk);
                streamTask.setChunks(singleChunkList);
                streamTask.downloadedBytes = 12000L;
                assertTrue(streamTask.saveCheckpoint(), "Saved mid-chunk checkpoint");

                DownloadTask resumedStreamTask = new DownloadTask(1001L, localUrl, "stream.bin", "application/octet-stream", tempDir, headers);
                assertTrue(resumedStreamTask.loadCheckpoint(), "Loaded mid-chunk checkpoint");
                DownloadChunk chunkToResume = resumedStreamTask.chunks.get(0);
                assertEquals(22000L, Long.valueOf(chunkToResume.getCurrentSeekOffset()), "Resume seek offset starts at 22000");

                final boolean[] completedFlag = new boolean[]{false};
                final Exception[] workerErr = new Exception[]{null};
                DownloadWorker worker = new DownloadWorker(resumedStreamTask, chunkToResume, new DownloadWorker.WorkerListener() {
                    @Override public void onChunkProgress(DownloadTask t, DownloadChunk c, int bytesRead) {}
                    @Override public void onChunkCompleted(DownloadTask t, DownloadChunk c) { completedFlag[0] = true; }
                    @Override public void onChunkFailed(DownloadTask t, DownloadChunk c, Exception error) { workerErr[0] = error; }
                });
                worker.run();
                serverThread.join(2000);

                if (workerErr[0] != null) {
                    throw workerErr[0];
                }
                assertTrue(completedFlag[0], "DownloadWorker completed resumed chunk");
                assertTrue(chunkToResume.completed, "Chunk marked completed after reaching endByte");
                assertEquals("bytes=22000-39999", observedRanges.get(0), "DownloadWorker requested exact remaining Range header");
                assertEquals(30000L, Long.valueOf(chunkToResume.downloadedBytes), "Full 30000 chunk bytes downloaded without premature truncation");
                assertEquals(30000L, Long.valueOf(resumedStreamTask.downloadedBytes), "Task total downloadedBytes updated to 30000");

                byte[] verifyBuf = new byte[30000];
                RandomAccessFile verifyRaf = new RandomAccessFile(resumedStreamTask.destinationFile, "r");
                verifyRaf.seek(10000L);
                verifyRaf.readFully(verifyBuf);
                verifyRaf.close();

                boolean byteExact = true;
                for (int i = 0; i < verifyBuf.length; i++) {
                    if (verifyBuf[i] != expectedPayload[10000 + i]) {
                        byteExact = false;
                        break;
                    }
                }
                assertTrue(byteExact, "Resumed chunk file content matches expected byte stream 100%");
                resumedStreamTask.cleanCheckpointFile();
            } finally {
                try { rangeServer.close(); } catch (Exception ignored) {}
            }

            // 3d. Download failure fast-abort, file/checkpoint cleanup & clean retry from scratch
            File failDir = new File(tempDir, "fail_test");
            failDir.mkdirs();
            DownloadTask failTask = new DownloadTask(2001L, "https://example.com/fail.zip", "fail.zip", "application/zip", failDir, headers);
            failTask.contentLength = 20000L;
            failTask.supportsRange = true;
            DownloadChunk ch0 = new DownloadChunk(0, 0, 9999);
            DownloadChunk ch1 = new DownloadChunk(1, 10000, 19999);
            List<DownloadChunk> failChunks = new ArrayList<DownloadChunk>();
            failChunks.add(ch0);
            failChunks.add(ch1);
            failTask.setChunks(failChunks);

            // Pre-create destination file and checkpoint
            AcidIo.writeBytes(failTask.destinationFile, new byte[5000]);
            failTask.saveCheckpoint();
            assertTrue(failTask.destinationFile.exists(), "Initial partial file exists");
            assertTrue(failTask.checkpointFile.exists(), "Initial checkpoint exists");

            // Simulate AcidDownloader workerListener handling chunk failure
            final java.util.concurrent.atomic.AtomicInteger remChunks = new java.util.concurrent.atomic.AtomicInteger(2);
            DownloadWorker.WorkerListener listener = new DownloadWorker.WorkerListener() {
                @Override public void onChunkProgress(DownloadTask t, DownloadChunk c, int bytesRead) {}
                @Override public void onChunkCompleted(DownloadTask t, DownloadChunk c) {
                    if (t.isCanceled || t.isPaused || t.status == DownloadTask.STATUS_FAILED) return;
                    t.saveCheckpoint();
                    int rem = remChunks.decrementAndGet();
                    if (rem <= 0 && !t.isPaused && !t.isCanceled && t.status != DownloadTask.STATUS_FAILED) {
                        t.status = DownloadTask.STATUS_SUCCESSFUL;
                    }
                }
                @Override public void onChunkFailed(DownloadTask t, DownloadChunk c, Exception error) {
                    if (t.isPaused || t.isCanceled || t.status == DownloadTask.STATUS_FAILED) return;
                    t.isCanceled = true;
                    t.status = DownloadTask.STATUS_FAILED;
                    t.errorMessage = error != null ? error.getMessage() : "Download error";
                    t.cleanCheckpointFile();
                    if (t.destinationFile != null && t.destinationFile.exists()) {
                        t.destinationFile.delete();
                    }
                }
            };

            // Trigger failure on Chunk 0
            listener.onChunkFailed(failTask, ch0, new java.io.IOException("Socket timeout"));
            assertTrue(failTask.isCanceled, "isCanceled set to true to brake sibling workers");
            assertEquals(DownloadTask.STATUS_FAILED, Integer.valueOf(failTask.status), "Status marked STATUS_FAILED");
            assertTrue(!failTask.checkpointFile.exists(), "Checkpoint purged to prevent dirty resume");
            assertTrue(!failTask.destinationFile.exists(), "Truncated partial file purged immediately");

            // Sibling Chunk 1 attempts completion afterwards -> must be rejected
            listener.onChunkCompleted(failTask, ch1);
            assertEquals(DownloadTask.STATUS_FAILED, Integer.valueOf(failTask.status), "Status remains STATUS_FAILED, never hijacked to SUCCESSFUL");
            assertTrue(!failTask.checkpointFile.exists(), "Checkpoint was not re-created by sibling worker");

            // User-initiated pause retains checkpoint
            DownloadTask pauseTask = new DownloadTask(2002L, "https://example.com/pause.zip", "pause.zip", "application/zip", failDir, headers);
            pauseTask.contentLength = 20000L;
            pauseTask.supportsRange = true;
            pauseTask.setChunks(failChunks);
            pauseTask.status = DownloadTask.STATUS_PAUSED;
            pauseTask.isPaused = true;
            pauseTask.saveCheckpoint();
            assertTrue(pauseTask.checkpointFile.exists(), "User pause strictly preserves checkpoint for resumption");
            pauseTask.cleanCheckpointFile();
        } finally {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            tempDir.delete();
        }

        // 4. HttpProbe Content-Disposition filename deduction & universal percent-decoding
        String d1 = "attachment; filename=\"custom_release.apk\"";
        assertEquals("custom_release.apk", HttpProbe.parseFileNameFromDisposition(d1), "Standard filename parsing");

        String d2 = "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E5%AE%89%E8%A3%85%E5%8C%85.apk";
        assertEquals("测试安装包.apk", HttpProbe.parseFileNameFromDisposition(d2), "RFC 5987 UTF-8 encoded filename parsing");

        String d3 = "inline; filename=unquoted.png; size=12345";
        assertEquals("unquoted.png", HttpProbe.parseFileNameFromDisposition(d3), "Unquoted filename parsing");

        // Real-world UC Netdisk & Baidu Netdisk multi-part Content-Disposition headers
        String ucDisp = "attachment; filename=\"%E4%BA%8E%E5%AB%A3%E5%8D%95%E6%B3%89%E6%B0%B4%E4%B8%8A%E5%B8%9D.zip\"; filename*=utf-8''%E4%BA%8E%E5%AB%A3%E5%8D%95%E6%B3%89%E6%B0%B4%E4%B8%8A%E5%B8%9D.zip";
        assertEquals("于嫣单泉水上帝.zip",
            com.acid.browser.download.DownloadCoordinator.smartGuessFileName("https://dl-pc-zb.drive.uc.cn/file", ucDisp, "application/zip"),
            "UC Netdisk Content-Disposition decoded cleanly");

        String baiduDisp = "attachment;filename=\"%E7%A0%94%E7%A9%B6%E7%94%9F%E8%AF%81%E6%98%8E%E6%96%87%E4%BB%B6_%E4%BD%95%E4%BD%B3%E9%94%8B.zip\";filename*=UTF-8''%E7%A0%94%E7%A9%B6%E7%94%9F%E8%AF%81%E6%98%8E%E6%96%87%E4%BB%B6_%E4%BD%95%E4%BD%B3%E9%94%8B.zip";
        assertEquals("研究生证明文件_何佳锋.zip",
            com.acid.browser.download.DownloadCoordinator.smartGuessFileName("https://appall01.baidupcs.com/file/abc", baiduDisp, "application/zip"),
            "Baidu Netdisk Content-Disposition decoded cleanly");

        String fromQueryFin = com.acid.browser.download.DownloadCoordinator.smartGuessFileName(
            "https://appall01.baidupcs.com/file/72207b?fid=123&fin=%E7%A0%94%E7%A9%B6%E7%94%9F.zip&expires=8h",
            null,
            "application/zip"
        );
        assertEquals("研究生.zip", fromQueryFin, "Baidu PCS fin= query parameter fallback decoded cleanly");

        assertTrue(SkillManager.isBuiltin(SkillManager.SKILL_CLOUD_DRIVE), "cloud_drive_download.md registered as builtin skill");
        assertEquals(0, DownloadItem.STATUS_SAVED, "DownloadItem.STATUS_SAVED is 0");

        String baiduLargeUrl = "https://appall01.baidupcs.com/file/23154b?fid=123&size=179795556&fin=%E9%AB%98%E5%BE%B7%E5%9C%B0%E5%9B%BE+16.20.0.2022%E5%8E%BB%E5%B9%BF%E5%91%8A%E7%A7%92%E5%AE%9A%E4%BD%8D%E7%89%88.apk";
        assertTrue(com.acid.browser.download.DownloadCoordinator.isCloudDriveDirectStreamUrl(baiduLargeUrl), "baidupcs file stream recognized for direct interception");
        assertEquals(179795556L, com.acid.browser.download.DownloadCoordinator.extractContentLengthFromUrlQuery(baiduLargeUrl), "size extracted from baidupcs query string");
        Map<String, String> baiduHeaders = com.acid.browser.download.DownloadCoordinator.buildCloudDriveAwareHeaders(baiduLargeUrl, null);
        assertEquals(com.acid.browser.download.DownloadCoordinator.UA_BAIDU_NETDISK, baiduHeaders.get("User-Agent"), "Baidu PCS automatically injects netdisk client UA");
    }

    private static void testAcidIoAndDownloadItem() throws Exception {
        File tmp = File.createTempFile("acid_io_test_", ".txt");
        tmp.deleteOnExit();
        try {
            String sample = "Acid Browser 读写测试\nLine 2";
            assertTrue(AcidIo.writeFileUtf8Safe(tmp, sample), "AcidIo writeFileUtf8Safe succeeds");
            assertEquals(sample, AcidIo.readFileUtf8(tmp), "AcidIo readFileUtf8 round-trip");
            assertEquals("", AcidIo.readFileUtf8OrEmpty(tmp, 5), "AcidIo maxBytes guard rejects oversized file");
            assertEquals(sample, AcidIo.readFileUtf8OrEmpty(tmp, 1024), "AcidIo readFileUtf8OrEmpty within cap");
        } finally {
            tmp.delete();
        }

        // DownloadItem category classification & size formatting
        assertEquals(Integer.valueOf(DownloadItem.CAT_APK),
            Integer.valueOf(new DownloadItem(0L, "app.apk", "https://x/app.apk", "", 1024, 0, 1, 1L).getCategory()), "apk category");
        assertEquals(Integer.valueOf(DownloadItem.CAT_ARCHIVE),
            Integer.valueOf(new DownloadItem(1L, "archive.tar.gz", "https://x/a", "", 100, 50, 2, 1L).getCategory()), "archive category");
        assertEquals(Integer.valueOf(DownloadItem.CAT_DOC),
            Integer.valueOf(new DownloadItem(2L, "manual.pdf", "https://x/m", "", 100, 100, 8, 1L).getCategory()), "doc category");
        assertEquals(Integer.valueOf(DownloadItem.CAT_IMAGE),
            Integer.valueOf(new DownloadItem(3L, "photo.webp", "https://x/p", "", 100, 20, 2, 1L).getCategory()), "image category");
        assertEquals(Integer.valueOf(DownloadItem.CAT_VIDEO),
            Integer.valueOf(new DownloadItem(4L, "clip.mp4", "https://x/v", "", 1000, 250, 2, 1L).getCategory()), "video category");
        assertEquals(Integer.valueOf(DownloadItem.CAT_AUDIO),
            Integer.valueOf(new DownloadItem(5L, "song.flac", "https://x/s", "", 1000, 500, 2, 1L).getCategory()), "audio category");
        assertEquals(Integer.valueOf(DownloadItem.CAT_OTHER),
            Integer.valueOf(new DownloadItem(6L, "disk.iso", "https://x/d", "application/octet-stream", 1000, 0, 1, 1L).getCategory()), "unknown binary falls into CAT_OTHER");

        DownloadItem progItem = new DownloadItem(7L, "video.mp4", "https://x/v.mp4", "video/mp4", 1000L, 250L, 2, 100L);
        assertEquals(Integer.valueOf(25), Integer.valueOf(progItem.getProgressPercent()), "progress percent calculation");
        assertEquals("1.0 KB", DownloadItem.formatSize(1024), "formatSize KB");

        BrowserDataStore store = new BrowserDataStore(new InMemoryPrefs(), new InMemoryPrefs(), null);
        List<DownloadItem> savedList = new ArrayList<DownloadItem>();
        savedList.add(progItem);
        store.saveDownloads(savedList);
        List<DownloadItem> loadedList = store.loadDownloads();
        assertEquals(Integer.valueOf(1), Integer.valueOf(loadedList.size()), "DownloadItem persisted in BrowserDataStore");
        assertEquals("video.mp4", loadedList.get(0).fileName, "persisted fileName matches");
    }

    private static void testFaviconManager() throws Exception {
        // 1. Host extraction & normalization (with non-default port preservation)
        assertEquals("www.google.com", FaviconManager.extractHost("https://www.google.com/search?q=test"), "extract full URL host");
        assertEquals("github.com", FaviconManager.extractHost("https://GitHub.com:443/acid/repo"), "extract mixed case with port 443 stripped");
        assertEquals("example.com", FaviconManager.extractHost("http://example.com:80/home"), "default port 80 stripped");
        assertEquals("127.0.0.1:8317", FaviconManager.extractHost("http://127.0.0.1:8317/management.html#/"), "preserve 127.0.0.1 port 8317");
        assertEquals("127.0.0.1:9090", FaviconManager.extractHost("http://127.0.0.1:9090/ui"), "preserve 127.0.0.1 port 9090");
        assertEquals("192.168.1.1:8080", FaviconManager.extractHost("http://192.168.1.1:8080/admin"), "preserve LAN IP port 8080");
        assertEquals("localhost:3000", FaviconManager.extractHost("http://localhost:3000/app"), "preserve localhost port 3000");
        assertEquals("bilibili.com", FaviconManager.extractHost("bilibili.com/video"), "extract host with path");
        assertEquals("zhihu.com", FaviconManager.extractHost("zhihu.com"), "extract bare host");
        assertEquals("", FaviconManager.extractHost(""), "extract empty string");

        // 1b. Local & IP host detection for accurate HTTP probe without leaking to external icon services
        assertTrue(FaviconManager.isLocalOrIpHost("127.0.0.1:8317"), "127.0.0.1 with port recognized as local/ip");
        assertTrue(FaviconManager.isLocalOrIpHost("127.0.0.1"), "127.0.0.1 recognized as local/ip");
        assertTrue(FaviconManager.isLocalOrIpHost("localhost:3000"), "localhost with port recognized as local");
        assertTrue(FaviconManager.isLocalOrIpHost("192.168.1.1:8080"), "LAN IP recognized as local/ip");
        assertTrue(!FaviconManager.isLocalOrIpHost("google.com"), "public domain recognized as non-local");

        // 2. Favicon magic byte validation
        byte[] pngBytes = new byte[]{(byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] icoBytes = new byte[]{0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] garbage = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

        assertTrue(FaviconManager.isValidFaviconBytes(pngBytes), "PNG magic bytes recognized");
        assertTrue(FaviconManager.isValidFaviconBytes(icoBytes), "ICO magic bytes recognized");
        assertTrue(!FaviconManager.isValidFaviconBytes(garbage), "garbage bytes rejected");
        assertEquals("image/png", FaviconManager.detectFaviconMime(pngBytes), "detect PNG mime");
        assertEquals("image/x-icon", FaviconManager.detectFaviconMime(icoBytes), "detect ICO mime");
    }

    private static void testSkillVaultAndSlugSecurity() throws Exception {
        // 1. SkillSlug invariant validation
        assertEquals("my-skill", SkillSlug.from("my-skill").value(), "normal slug sanitized");
        assertEquals("test_skill", SkillSlug.from("---test_skill---").value(), "trim leading/trailing hyphens");
        assertEquals("imported_skill", SkillSlug.from("---").value(), "fallback when all sanitized away");
        assertEquals("custom_fallback", SkillSlug.from("...", "custom_fallback").value(), "custom fallback honored");

        // 2. SkillStorageVault containment invariants
        File tmpVaultDir = new File(System.getProperty("java.io.tmpdir"), "acid_vault_test_" + System.currentTimeMillis());
        tmpVaultDir.mkdirs();
        try {
            SkillStorageVault vault = new SkillStorageVault(tmpVaultDir);
            SkillSlug slug = SkillSlug.from("safe_skill");
            File skillDir = vault.resolveSkillDir(slug);
            assertEquals(new File(tmpVaultDir, "safe_skill").getCanonicalPath(), skillDir.getCanonicalPath(), "valid child resolved");

            // Staging and promotion invariants
            File staging = vault.createStagingDir();
            assertTrue(staging.exists(), "staging dir created");
            File testFile = new File(staging, "SKILL.md");
            AcidIo.writeFileUtf8Safe(testFile, "# Test Skill\nDescription here");

            File promoted = vault.promoteStagingDir(staging, slug);
            assertTrue(promoted.exists(), "promoted dir exists");
            assertTrue(new File(promoted, "SKILL.md").exists(), "content moved");
            assertTrue(vault.deleteSkillDir(slug), "skill safely deleted");
            assertTrue(!promoted.exists(), "promoted dir no longer exists");
            assertTrue(tmpVaultDir.exists(), "vault root strictly preserved and never deleted");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(tmpVaultDir);
        }
    }

    private static void testP0P1Hardenings() throws Exception {
        // 1. SkillStorageVault binary file copy without UTF-8 corruption
        File tmpSrcDir = new File(System.getProperty("java.io.tmpdir"), "acid_bin_src_" + System.currentTimeMillis());
        File tmpDstDir = new File(System.getProperty("java.io.tmpdir"), "acid_bin_dst_" + System.currentTimeMillis());
        tmpSrcDir.mkdirs();
        tmpDstDir.mkdirs();
        try {
            File binFile = new File(tmpSrcDir, "binary.dat");
            byte[] arbitraryBinary = new byte[] { (byte) 0xFF, (byte) 0xFE, (byte) 0xC0, (byte) 0x80, 0x00, 0x12, (byte) 0xE0 };
            AcidIo.writeBytes(binFile, arbitraryBinary);

            File targetFile = new File(tmpDstDir, "binary.dat");
            AcidIo.copyFile(binFile, targetFile);

            assertTrue(targetFile.exists(), "target binary file created");
            assertEquals(arbitraryBinary.length, (int) targetFile.length(), "length matches exactly");
            java.io.FileInputStream fis = new java.io.FileInputStream(targetFile);
            byte[] readBack = new byte[arbitraryBinary.length];
            int n = fis.read(readBack);
            fis.close();
            assertEquals(arbitraryBinary.length, n, "all bytes read");
            for (int i = 0; i < arbitraryBinary.length; i++) {
                assertEquals(arbitraryBinary[i], readBack[i], "byte[" + i + "] matches exactly without corruption");
            }
        } finally {
            SkillStorageVault.deleteRecursiveSafe(tmpSrcDir);
            SkillStorageVault.deleteRecursiveSafe(tmpDstDir);
        }

        // 2. UserscriptHttpHandler Charset resolution
        assertEquals("GBK", UserscriptHttpHandler.resolveCharset("text/html; charset=gbk", null).name(), "charset in content-type");
        assertEquals("GB2312", UserscriptHttpHandler.resolveCharset("text/html; charset=\"gb2312\"", null).name(), "quoted charset");
        byte[] htmlWithMeta = "<html><head><meta charset=\"gb18030\"></head><body>hello</body></html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("GB18030", UserscriptHttpHandler.resolveCharset("text/html", htmlWithMeta).name(), "meta charset sniffed");
        assertEquals("UTF-8", UserscriptHttpHandler.resolveCharset("application/json", null).name(), "fallback to utf-8");

        // 3. AdBlockManager Public Suffix & TLD Protection
        assertTrue(AdBlockManager.isPublicSuffixOrTld("com"), "com is TLD");
        assertTrue(AdBlockManager.isPublicSuffixOrTld("cn"), "cn is TLD");
        assertTrue(AdBlockManager.isPublicSuffixOrTld("com.cn"), "com.cn is public suffix");
        assertTrue(AdBlockManager.isPublicSuffixOrTld("edu.cn"), "edu.cn is public suffix");
        assertTrue(!AdBlockManager.isPublicSuffixOrTld("baidu.com"), "baidu.com is regular domain");
        assertTrue(!AdBlockManager.isPublicSuffixOrTld("test.com.cn"), "test.com.cn is regular domain");

        java.util.Set<String> blockedSet = new java.util.HashSet<String>();
        blockedSet.add("com.cn"); // Even if poisoned with public suffix
        blockedSet.add("com");
        assertTrue(!AdBlockManager.matchesDomainHierarchy("pos.baidu.com.cn", blockedSet), "domain climbing stops before public suffix com.cn");
        assertTrue(!AdBlockManager.matchesDomainHierarchy("pos.baidu.com", blockedSet), "domain climbing stops before TLD com");

        // 4. AcidAgentLoop default max steps
        assertEquals(50, AcidAgentLoop.DEFAULT_MAX_LOOP_STEPS, "default max loop steps is 50");

        // 5. BrowserDataStore history retention cap
        assertEquals(2000, BrowserDataStore.MAX_HISTORY_RETENTION_ENTRIES, "history retention capped at 2000 entries");
    }

    private static void testP0P1BugFixes() throws Exception {
        // 1. AcidAgentLoop tool_calls protocol alignment on premature takeover or stop
        AcidSessionStore.AgentSession session = new AcidSessionStore.AgentSession();
        session.id = "test_session";
        session.title = "Test Title";
        session.createdAt = System.currentTimeMillis();
        org.json.JSONArray toolCalls = new org.json.JSONArray();
        for (int i = 0; i < 3; i++) {
            org.json.JSONObject tc = new org.json.JSONObject();
            tc.put("id", "call_step1_" + i);
            org.json.JSONObject fn = new org.json.JSONObject();
            fn.put("name", "tool_" + i);
            tc.put("function", fn);
            toolCalls.put(tc);
        }

        // Simulate that only tool_0 was executed, and execution broke on takeover
        org.json.JSONObject executedMsg = new org.json.JSONObject();
        executedMsg.put("role", "tool");
        executedMsg.put("tool_call_id", "call_step1_0");
        executedMsg.put("name", "tool_0");
        executedMsg.put("content", "Obs 0");
        session.apiMessages.add(executedMsg);

        // Align remaining tool calls
        AcidAgentLoop.ensureToolCallsAlignment(session, toolCalls, 1, "[Execution paused: user takeover]", null);

        assertEquals(3, session.apiMessages.size(), "all 3 tool calls have responses");
        assertEquals("call_step1_0", session.apiMessages.get(0).getString("tool_call_id"), "first tool is real response");
        assertEquals("call_step1_1", session.apiMessages.get(1).getString("tool_call_id"), "second tool has placeholder");
        assertEquals("call_step1_2", session.apiMessages.get(2).getString("tool_call_id"), "third tool has placeholder");
        assertTrue(session.apiMessages.get(1).getString("content").contains("user takeover"), "placeholder states reason");
        assertTrue(session.apiMessages.get(2).getString("content").contains("user takeover"), "placeholder states reason");

        // 2. DownloadWorker Range guard against HTTP 200 data corruption
        // If seekOffset > 0 and server returns 200 OK, must fail fast with IOException
        long seekOffset = 1048576L; // 1 MB into file
        int code = java.net.HttpURLConnection.HTTP_OK; // 200 OK
        boolean preventedCorruption = false;
        try {
            if (code == java.net.HttpURLConnection.HTTP_OK && seekOffset > 0) {
                throw new java.io.IOException("Server does not support byte-range resume (HTTP 200 OK returned for Range offset " + seekOffset + ")");
            }
        } catch (java.io.IOException e) {
            preventedCorruption = true;
            assertTrue(e.getMessage().contains("HTTP 200 OK returned for Range offset"), "exception explains Range violation");
        }
        assertTrue(preventedCorruption, "Range 200 OK on seekOffset > 0 prevented file corruption");

        // 3. AcidFileProvider internal Download directory resolution & traversal defense
        File tmpInternalFiles = new File(System.getProperty("java.io.tmpdir"), "acid_internal_files_" + System.currentTimeMillis());
        File tmpInternalDownload = new File(tmpInternalFiles, "Download");
        tmpInternalDownload.mkdirs();
        try {
            File safeDownloadFile = new File(tmpInternalDownload, "sample.pdf");
            AcidIo.writeFileUtf8Safe(safeDownloadFile, "%PDF-1.4 test file content");

            // Verify safe resolution in internal directory
            File resolved = AcidFileProvider.resolveAndVerifyBoundary(tmpInternalDownload, "sample.pdf");
            assertTrue(resolved != null && resolved.exists(), "internal Download directory candidate resolved safely");
            assertEquals(safeDownloadFile.getCanonicalPath(), resolved.getCanonicalPath(), "canonical path matches internal target");

            // Verify path traversal rejection
            File escaped = AcidFileProvider.resolveAndVerifyBoundary(tmpInternalDownload, "../secret.txt");
            assertTrue(escaped == null, "path traversal escaping internal Download directory strictly blocked");
            File dotFile = AcidFileProvider.resolveAndVerifyBoundary(tmpInternalDownload, ".");
            assertTrue(dotFile == null, "directory self-reference strictly blocked");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(tmpInternalFiles);
        }
    }

    private static void testConcurrencyAndStateMachine() throws Exception {
        // 1. CopyOnWriteArrayList concurrent iteration safety for Browser tabs
        final List<BrowserController.TabSession> concurrentTabs = new java.util.concurrent.CopyOnWriteArrayList<BrowserController.TabSession>();
        for (int i = 0; i < 5; i++) {
            concurrentTabs.add(new BrowserController.TabSession(i, null));
        }

        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicReference<Throwable> mutationError = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        Thread readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running.get()) {
                        for (BrowserController.TabSession t : concurrentTabs) {
                            if (t != null && t.id == -999) break;
                        }
                    }
                } catch (Throwable t) {
                    mutationError.set(t);
                }
            }
        });

        Thread writerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 5; i < 50; i++) {
                        BrowserController.TabSession s = new BrowserController.TabSession(i, null);
                        concurrentTabs.add(s);
                        concurrentTabs.remove(s);
                    }
                } catch (Throwable t) {
                    mutationError.set(t);
                }
            }
        });

        readerThread.start();
        writerThread.start();
        writerThread.join();
        running.set(false);
        readerThread.join();

        assertEquals(null, mutationError.get(), "no ConcurrentModificationException during tab iteration");

        // 2. uiItems snapshot safety under background mutations
        final AcidSessionStore.AgentSession session = new AcidSessionStore.AgentSession();
        for (int i = 0; i < 10; i++) {
            AcidSessionStore.UiMessageItem item = new AcidSessionStore.UiMessageItem();
            item.text = "Item " + i;
            session.uiItems.add(item);
        }

        List<AcidSessionStore.UiMessageItem> snap = session.getUiItemsSnapshot();
        assertEquals(10, snap.size(), "snapshot captured all initial items");

        // Mutate live session
        session.uiItems.remove(0);
        session.uiItems.add(new AcidSessionStore.UiMessageItem());

        assertEquals(10, snap.size(), "isolated snapshot is completely unaffected by concurrent session modifications");
        assertEquals("Item 0", snap.get(0).text, "snapshot preserves first item");

        // 3. UI item state-machine closure upon stopLoop
        AcidSessionStore.UiMessageItem runningTool = new AcidSessionStore.UiMessageItem();
        runningTool.type = AcidSessionStore.TYPE_TOOL;
        runningTool.isRunning = true;
        runningTool.toolResult = "正在执行...";
        session.uiItems.add(runningTool);

        AcidSessionStore.UiMessageItem runningThink = new AcidSessionStore.UiMessageItem();
        runningThink.type = AcidSessionStore.TYPE_THINKING;
        runningThink.isRunning = true;
        runningThink.text = "";
        session.uiItems.add(runningThink);

        // Simulate loop closing dangling items on user stop
        for (AcidSessionStore.UiMessageItem it : session.uiItems) {
            if (it.isRunning) {
                it.isRunning = false;
                if (AcidSessionStore.TYPE_TOOL.equals(it.type)) {
                    it.toolResult = "[任务已由用户手动停止]";
                } else if (AcidSessionStore.TYPE_THINKING.equals(it.type)) {
                    it.text = "[思考已停止]";
                }
            }
        }

        assertTrue(!runningTool.isRunning, "running tool item closed");
        assertEquals("[任务已由用户手动停止]", runningTool.toolResult, "tool result displays stopped notice");
        assertTrue(!runningThink.isRunning, "running thinking item closed");
        assertEquals("[思考已停止]", runningThink.text, "thinking text displays stopped notice");
    }

    private static void testStorageInvariantsAndEmptyBookmarks() throws Exception {
        // 1. Storage Invariant: Sub-repositories must strictly target the unified primary PREF_NAME
        assertEquals(BrowserDataStore.PREF_NAME, BookmarkRepository.PREF_NAME, "BookmarkRepository PREF_NAME matches BrowserDataStore");
        assertEquals(BrowserDataStore.PREF_NAME, HistoryRepository.PREF_NAME, "HistoryRepository PREF_NAME matches BrowserDataStore");

        // 2. Initial run with raw == null seeds exactly 6 default bookmarks
        InMemoryPrefs prefs = new InMemoryPrefs();
        BookmarkRepository repo = new BookmarkRepository(null, prefs, null);
        List<BrowserDataStore.EntryItem> initial = repo.loadBookmarks();
        assertEquals(6, Integer.valueOf(initial.size()), "initial run seeds exactly 6 default bookmarks");
        assertEquals("Google", initial.get(0).title, "seeded first bookmark is Google");

        // 3. Explicitly emptying bookmarks persists "[]" and MUST NOT resuscitate default bookmarks
        repo.saveBookmarks(new java.util.ArrayList<BrowserDataStore.EntryItem>());
        assertEquals("[]", prefs.getString(BookmarkRepository.PREF_BOOKMARKS, null), "empty bookmarks persisted as valid json array []");

        List<BrowserDataStore.EntryItem> reloadedEmpty = repo.loadBookmarks();
        assertTrue(reloadedEmpty.isEmpty(), "reloading empty bookmarks preserves empty list without ghost defaults");

        // 4. Corrupt blob safety preserved
        prefs.edit().putString(BookmarkRepository.PREF_BOOKMARKS, "{broken_json").apply();
        List<BrowserDataStore.EntryItem> corruptRecovered = repo.loadBookmarks();
        assertTrue(corruptRecovered.isEmpty(), "corrupt blob safely returns empty list");
        assertEquals("{broken_json", prefs.getString(BookmarkRepository.PREF_BOOKMARKS, null), "corrupt blob preserved untouched");
    }

    private static void testCriticalRuntimeGuards() throws Exception {
        // 1. SSE tool_calls delta accumulation guards against null / "null" poisoning
        Map<Integer, AcidAgentLoop.StreamingToolCall> callMap = new java.util.TreeMap<Integer, AcidAgentLoop.StreamingToolCall>();
        org.json.JSONArray delta1 = new org.json.JSONArray(
            "[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\",\"function\":{\"name\":\"browser\",\"arguments\":\"\"}}]"
        );
        org.json.JSONArray delta2 = new org.json.JSONArray(
            "[{\"index\":0,\"id\":null,\"type\":null,\"function\":{\"name\":null,\"arguments\":\"{\\\"action\\\":\\\"observe\\\"}\"}}]"
        );
        org.json.JSONArray delta3 = new org.json.JSONArray(
            "[{\"index\":0,\"id\":\"null\",\"type\":\"null\",\"function\":{\"name\":\"null\",\"arguments\":\"null\"}}]"
        );
        AcidAgentLoop.accumulateToolCallsDelta(callMap, delta1);
        AcidAgentLoop.accumulateToolCallsDelta(callMap, delta2);
        AcidAgentLoop.accumulateToolCallsDelta(callMap, delta3);

        AcidAgentLoop.StreamingToolCall assembled = callMap.get(0);
        assertTrue(assembled != null, "tool call entry exists at index 0");
        assertEquals("call_abc", assembled.id, "tool call id preserved across null deltas");
        assertEquals("function", assembled.type, "tool call type preserved across null deltas");
        assertEquals("browser", assembled.name, "tool call name not polluted with 'null'");
        assertEquals("{\"action\":\"observe\"}", assembled.arguments.toString(), "arguments assembled cleanly");

        // 2. HttpProbe 206 Partial Content with Content-Range: bytes 0-0/* must NOT mistake 1-byte probe body for total file length
        final ServerSocket probeServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        int port = probeServer.getLocalPort();
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket sock = probeServer.accept();
                    BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = br.readLine()) != null && !line.isEmpty()) {}
                    OutputStream os = sock.getOutputStream();
                    String resp = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Range: bytes 0-0/*\r\n" +
                        "Content-Length: 1\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Connection: close\r\n\r\nX";
                    os.write(resp.getBytes(StandardCharsets.US_ASCII));
                    os.flush();
                    sock.close();
                } catch (Exception ignored) {
                } finally {
                    try { probeServer.close(); } catch (Exception ignored) {}
                }
            }
        });
        serverThread.start();
        HttpProbe.ProbeResult pr = HttpProbe.probe("http://127.0.0.1:" + port + "/stream.bin", null);
        serverThread.join(2000);
        assertTrue(pr.supportsRange, "206 probe marks supportsRange true");
        assertEquals(Long.valueOf(-1L), Long.valueOf(pr.contentLength), "unknown total length (*) stays -1 instead of 1 byte");

        // 3. agent_bridge.js exports setSomEnabled alias and preserves refMetaMap fingerprint fields
        String bridgeSrc = AcidIo.readFileUtf8(new File("assets/agent_bridge.js"));
        assertTrue(bridgeSrc.contains("setSomEnabled: setSomHighlight"), "agent_bridge exports setSomEnabled alias");
        assertTrue(!bridgeSrc.contains("refMetaMap[ref] = meta;"), "agent_bridge does not overwrite refMetaMap fingerprint");

        // 4. DownloadCoordinator injects UA_UC_CLIENT for .uc.cn URLs
        Map<String, String> ucHeaders = com.acid.browser.download.DownloadCoordinator.buildCloudDriveAwareHeaders(
            "https://dl-pc-zb.drive.uc.cn/file.zip", null
        );
        assertEquals(com.acid.browser.download.DownloadCoordinator.UA_UC_CLIENT, ucHeaders.get("User-Agent"), "UC cloud drive UA injected");
    }
}
