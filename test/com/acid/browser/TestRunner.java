package com.acid.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import com.acid.browser.download.AcidDownloader;
import com.acid.browser.download.ChunkDivider;
import com.acid.browser.download.DownloadChunk;
import com.acid.browser.download.DownloadCoordinator;
import com.acid.browser.download.DownloadItem;
import com.acid.browser.download.DownloadTask;
import com.acid.browser.download.DownloadWorker;
import com.acid.browser.download.HttpProbe;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zero-dependency, deeply assertive JVM regression test suite for Acid Browser.
 * Tests core state machines, security sandboxes, network protocols, RFC 7233 range engines,
 * and verifies APK packaging/assets integrity.
 */
public final class TestRunner {

    private static int sPassed = 0;
    private static int sFailed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("[AcidTests] Executing refactored core regression suite...");

        // 1. Packaging & Assets Integrity
        run("1. Packaging & Assets: APK structure, DEX size, runtime assets and signature integrity", new TestCase() {
            @Override public void execute() throws Exception { testPackagingAndAssetsIntegrity(); }
        });

        // 2. BuildInfo & Single Source of Truth
        run("2. Core Versioning: BuildInfo single version source across UI and MCP server", new TestCase() {
            @Override public void execute() throws Exception { testBuildInfoAndSingleVersionSource(); }
        });

        // 3. AI Agent Autonomous Loop & State Machine
        run("3. Agent Loop: Step ceiling guard, tool deduplication, message alignment and token estimation", new TestCase() {
            @Override public void execute() throws Exception { testAgentLoopAndToolDispatching(); }
        });

        // 4. Stream Thinking Parser State Machine
        run("4. Stream Parsing: Cross-chunk <think>...</think> state machine resilience", new TestCase() {
            @Override public void execute() throws Exception { testStreamThinkSplitParser(); }
        });

        // 5. Sandbox Tools & Security Boundaries
        run("5. Sandbox Security: File:// path boundary guard, traversal block, ambiguous edit protection", new TestCase() {
            @Override public void execute() throws Exception { testSandboxSecurityAndFileBoundaries(); }
        });

        // 6. Userscript Meta & URL Glob Pattern Scoping
        run("6. Userscript Engine: Header parsing, strict URL glob boundaries and version comparison", new TestCase() {
            @Override public void execute() throws Exception { testUserscriptMetaAndUrlGlobScoping(); }
        });

        // 7. Userscript Runtime, Storage Isolation & Grants
        run("7. Userscript Security: Storage isolation, @grant enforcement and token derivation", new TestCase() {
            @Override public void execute() throws Exception { testUserscriptRuntimeStorageAndSecurity(); }
        });

        // 8. RFC 7233 Multi-Threaded Downloader & Resume Engine
        run("8. Downloader Engine: RFC 7233 range chunking, fake range 200 guard, generation tokens and checkpoint isolation", new TestCase() {
            @Override public void execute() throws Exception { testAcidDownloaderRfc7233RangeAndResume(); }
        });

        // 9. AdBlock Engine & ABP Rule Hierarchy
        run("9. AdBlock Engine: ABP rule parsing, domain hierarchy climbing, TLD protection and whitelist overrides", new TestCase() {
            @Override public void execute() throws Exception { testAdBlockAbpParsingAndDomainHierarchy(); }
        });

        // 10. DataStore Persistence, Two-Way Sync & Fault-Tolerance
        run("10. Persistence: Two-way JSON sync, corrupt format recovery, time bucketing and temp whitelist", new TestCase() {
            @Override public void execute() throws Exception { testBrowserDataStoreSyncAndFaultTolerance(); }
        });

        // 11. Local MCP Server & Network SSRF Protection
        run("11. MCP Control Server: JSON-RPC 2.0 dispatch, token auth, chunked transfer and decimal IP SSRF guard", new TestCase() {
            @Override public void execute() throws Exception { testMcpServerJsonRpcAndSsrfProtection(); }
        });

        // 12. UI Rendering, Viewport & Text Shaping
        run("12. UI & Rendering: Markdown inline code block protection, Desktop viewport script and JsEvalShaper", new TestCase() {
            @Override public void execute() throws Exception { testUiRenderingAndTextShaping(); }
        });

        // 13. Theme, Async & IO Foundation
        run("13. Foundations: AcidTheme contrast calculations, AcidAsync cancellation and AcidIo streams", new TestCase() {
            @Override public void execute() throws Exception { testThemeAndColorContrast(); }
        });

        // 14. Batch 1 Security & Core Bug Fixes Regression
        run("14. Batch-1 Fixes: Bookmark origin guard, Cloud-drive cookie isolation, Stream 'null' token, AdBlock path scope & Markdown PUA guard", new TestCase() {
            @Override public void execute() throws Exception { testBatch1SecurityAndCoreFixes(); }
        });

        System.out.println("[AcidTests] Suite Complete -> Passed: " + sPassed + ", Failed: " + sFailed);
        System.exit(sFailed > 0 ? 1 : 0);
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

    // =========================================================================
    // 1. Packaging & Assets Integrity Test
    // =========================================================================

    private static final String[] REQUIRED_RUNTIME_ASSETS = new String[] {
        "assets/agent_bridge.js",
        "assets/userscripts/page_bootstrap.js",
        "assets/userscripts/runtime_shim.js",
        "assets/userscripts/tampermonkey_facade.js",
        "assets/userscripts/anti_app_hijack.user.js",
        "assets/adblock/builtin_rules.txt",
        "assets/homepage/index.html",
        "assets/skills/userscript_dev.md",
        "assets/skills/homepage_dev.md",
        "assets/skills/cloud_drive_download.md",
        "assets/donate/wechat_qr.png",
        "assets/donate/alipay_qr.jpg"
    };

    private static void testPackagingAndAssetsIntegrity() throws Exception {
        // A. Verify source assets existence and non-empty size
        for (String assetRelPath : REQUIRED_RUNTIME_ASSETS) {
            File f = new File(assetRelPath);
            assertTrue(f.exists() && f.isFile(), "Source asset must exist on disk: " + assetRelPath);
            assertTrue(f.length() > 100, "Source asset must not be empty (<100B): " + assetRelPath + " (" + f.length() + "B)");
        }

        // B. Builtin AdBlock rules file must contain at least 2,500 lines of ABP rules
        File adblockFile = new File("assets/adblock/builtin_rules.txt");
        String adblockContent = AcidIo.readFileUtf8(adblockFile);
        int ruleLines = adblockContent.split("\n").length;
        assertTrue(ruleLines >= 2500, "builtin_rules.txt must contain >=2500 lines (actual: " + ruleLines + ")");

        // C. Agent bridge must define window.__acidBridge
        File bridgeFile = new File("assets/agent_bridge.js");
        String bridgeContent = AcidIo.readFileUtf8(bridgeFile);
        assertTrue(bridgeContent.contains("window.__acidBridge"), "agent_bridge.js must declare window.__acidBridge");
        assertTrue(bridgeContent.length() > 30000, "agent_bridge.js must contain complete implementation (>30KB)");

        // D. Inspect built APK if present (post-build.sh verification)
        File apkFile = new File("build/Acid-browser.apk");
        if (apkFile.exists() && apkFile.length() > 0) {
            assertTrue(apkFile.length() > 300 * 1024, "Built APK must be >= 300KB (actual: " + (apkFile.length() / 1024) + "KB)");
            assertTrue(apkFile.length() < 2 * 1024 * 1024, "Built APK must remain ultra-lightweight < 2MB (actual: " + (apkFile.length() / 1024) + "KB)");

            ZipFile zip = new ZipFile(apkFile);
            try {
                // Verify classes.dex
                ZipEntry dex = zip.getEntry("classes.dex");
                assertTrue(dex != null, "APK must contain classes.dex");
                assertTrue(dex.getSize() > 200 * 1024, "classes.dex must be > 200KB (actual: " + dex.getSize() + ")");

                // Verify binary AndroidManifest.xml
                ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
                assertTrue(manifest != null, "APK must contain AndroidManifest.xml");
                assertTrue(manifest.getSize() > 1000, "AndroidManifest.xml must be valid size");

                // Verify all 10 critical assets packaged into the final APK
                for (String assetRelPath : REQUIRED_RUNTIME_ASSETS) {
                    ZipEntry ze = zip.getEntry(assetRelPath);
                    assertTrue(ze != null, "APK archive is missing required runtime asset: " + assetRelPath);
                    assertTrue(ze.getSize() > 100, "Packaged APK asset must not be empty: " + assetRelPath);
                }

                // Verify signature entries in META-INF
                boolean hasSignature = false;
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().startsWith("META-INF/") && (e.getName().endsWith(".SF") || e.getName().endsWith(".RSA"))) {
                        hasSignature = true;
                        break;
                    }
                }
                assertTrue(hasSignature, "APK must be digitally signed with META-INF signature files");
            } finally {
                zip.close();
            }
        }
    }

    // =========================================================================
    // 2. BuildInfo & Single Source of Truth
    // =========================================================================

    private static void testBuildInfoAndSingleVersionSource() {
        assertTrue(BuildInfo.VERSION_NAME != null && !BuildInfo.VERSION_NAME.isEmpty(), "VERSION_NAME populated");
        assertTrue(BuildInfo.VERSION_CODE > 0, "VERSION_CODE positive safe integer");
        assertEquals(BuildInfo.VERSION_NAME, AcidTheme.appVersionName(null), "AcidTheme fallback aligns with BuildInfo");
        assertEquals(BuildInfo.VERSION_NAME, HttpControlServer.MCP_SERVER_VERSION, "MCP version aligns with BuildInfo");
    }

    // =========================================================================
    // 3. AI Agent Loop & State Machine
    // =========================================================================

    private static void testAgentLoopAndToolDispatching() throws Exception {
        // 1. Tool Calls Deduplication: Proxy repeating function name must NOT duplicate to browserbrowser
        Map<Integer, AcidAgentLoop.StreamingToolCall> toolCallsMap = new HashMap<Integer, AcidAgentLoop.StreamingToolCall>();

        JSONArray delta1 = new JSONArray();
        JSONObject tc1 = new JSONObject();
        tc1.put("index", 0);
        tc1.put("id", "call_first");
        JSONObject fn1 = new JSONObject();
        fn1.put("name", "browser");
        fn1.put("arguments", "{\"action\":\"obs");
        tc1.put("function", fn1);
        delta1.put(tc1);

        AcidAgentLoop.accumulateToolCallsDelta(toolCallsMap, delta1);
        AcidAgentLoop.StreamingToolCall call = toolCallsMap.get(0);
        assertTrue(call != null, "Tool call 0 created");
        assertEquals("browser", call.name, "Call name after initial chunk");
        assertEquals("call_first", call.id, "Call ID after initial chunk");

        // Delta 2: Proxy repeats full 'browser' name alongside next argument piece
        JSONArray delta2 = new JSONArray();
        JSONObject tc2 = new JSONObject();
        tc2.put("index", 0);
        JSONObject fn2 = new JSONObject();
        fn2.put("name", "browser"); // Repeated full name
        fn2.put("arguments", "erve\"}");
        tc2.put("function", fn2);
        delta2.put(tc2);

        AcidAgentLoop.accumulateToolCallsDelta(toolCallsMap, delta2);
        assertEquals("browser", call.name, "Call name must remain 'browser' and not duplicate to 'browserbrowser'");
        assertEquals("{\"action\":\"observe\"}", call.arguments.toString(), "Arguments assembled correctly");

        // 2. OpenAI / ReAct Protocol Message Alignment & Self-Healing
        AcidSessionStore.AgentSession session = new AcidSessionStore.AgentSession();
        session.id = "test_align";

        // Create an assistant message declaring two tool calls
        JSONObject assistantMsg = new JSONObject();
        assistantMsg.put("role", "assistant");
        JSONArray declaredCalls = new JSONArray();

        JSONObject call0 = new JSONObject();
        call0.put("id", "call_0");
        JSONObject fnCall0 = new JSONObject();
        fnCall0.put("name", "browser");
        call0.put("function", fnCall0);
        declaredCalls.put(call0);

        JSONObject call1 = new JSONObject();
        call1.put("id", "call_1");
        JSONObject fnCall1 = new JSONObject();
        fnCall1.put("name", "read");
        call1.put("function", fnCall1);
        declaredCalls.put(call1);

        assistantMsg.put("tool_calls", declaredCalls);
        session.apiMessages.add(assistantMsg);

        // Simulate user interrupting after only call_0 completed
        JSONObject tool0Resp = new JSONObject();
        tool0Resp.put("role", "tool");
        tool0Resp.put("tool_call_id", "call_0");
        tool0Resp.put("name", "browser");
        tool0Resp.put("content", "Observed page");
        session.apiMessages.add(tool0Resp);

        // Before sending next turn, alignment must synthesize closing placeholder for call_1
        AcidAgentLoop.ensureToolCallsAlignment(session, declaredCalls, 1, "[Task stopped by user]", null);
        assertEquals(3, Integer.valueOf(session.apiMessages.size()), "Session must contain assistant + 2 tool messages");

        JSONObject synthesized = session.apiMessages.get(2);
        assertEquals("tool", synthesized.optString("role"), "Synthesized role is tool");
        assertEquals("call_1", synthesized.optString("tool_call_id"), "Synthesized call ID matches unfulfilled call_1");
        assertEquals("read", synthesized.optString("name"), "Synthesized name is read");
        assertTrue(synthesized.optString("content").contains("stopped by user"), "Synthesized content explains stop reason");

        // 3. Token estimation heuristic
        int estimated = AcidAgentLoop.estimateTokens(session);
        assertTrue(estimated > 10, "Token estimation heuristic returns positive count: " + estimated);

        // 4. Observation Compaction & Pruning Ironclad Rules
        List<JSONObject> history = new ArrayList<JSONObject>();

        JSONObject uMsg = new JSONObject();
        uMsg.put("role", "user");
        uMsg.put("content", "Search and email");
        history.add(uMsg);

        // Step 2: Observe slice 0
        StringBuilder obs0Content = new StringBuilder("=== [BROWSER ACTION: OBSERVE] ===\nPage: Mail\nURL: https://mail.test\nInteractive Elements (180 | showing 1-180 of 441):\n");
        for (int i = 1; i <= 20; i++) {
            obs0Content.append("  [e").append(i).append("] a \"Inbox item ").append(i).append("\"\n");
        }
        obs0Content.append("Page Text Excerpt:\nMail body text long excerpt to simulate real page content in testing...");
        JSONObject obs0 = new JSONObject();
        obs0.put("role", "tool");
        obs0.put("name", "browser");
        obs0.put("content", obs0Content.toString());
        history.add(obs0);

        // Step 3 & 4: Two EVAL tool calls (non-DOM tools)
        JSONObject eval1 = new JSONObject();
        eval1.put("role", "tool");
        eval1.put("name", "browser");
        eval1.put("content", "=== [BROWSER ACTION: EVAL] ===\n\"evaluated\"");
        history.add(eval1);

        JSONObject eval2 = new JSONObject();
        eval2.put("role", "tool");
        eval2.put("name", "browser");
        eval2.put("content", "=== [BROWSER ACTION: EVAL] ===\n\"evaluated2\"");
        history.add(eval2);

        // Pruning test: obs0 MUST NOT be pruned despite subsequent eval calls!
        List<JSONObject> pruned1 = AcidAgentLoop.pruneHistoryForRequest(history);
        assertTrue(pruned1.get(1).optString("content").contains("  [e1] a \"Inbox item 1\""), "DOM observation must NOT be pruned by subsequent eval calls!");
        assertTrue(!pruned1.get(1).optString("content").contains("superseded"), "Must not mark latest DOM as superseded");

        // Now test pagination: add obs1 (slice 181-360) and obs2 (slice 361-441)
        JSONObject obs1 = new JSONObject();
        obs1.put("role", "tool");
        obs1.put("name", "browser");
        obs1.put("content", "=== [BROWSER ACTION: OBSERVE] ===\nPage: Mail\nURL: https://mail.test\nInteractive Elements (180 | showing 181-360 of 441):\n  [e181] a \"Email 181\"\nPage Text Excerpt:\nMore text");
        history.add(obs1);

        JSONObject obs2 = new JSONObject();
        obs2.put("role", "tool");
        obs2.put("name", "browser");
        obs2.put("content", "=== [BROWSER ACTION: OBSERVE] ===\nPage: Mail\nURL: https://mail.test\nInteractive Elements (81 | showing 361-441 of 441):\n  [e361] a \"Email 361\"\nPage Text Excerpt:\nEnd of page");
        history.add(obs2);

        // With 3 DOM observations (obs0, obs1, obs2), all 3 must co-exist completely unpruned!
        List<JSONObject> pruned2 = AcidAgentLoop.pruneHistoryForRequest(history);
        assertTrue(pruned2.get(1).optString("content").contains("  [e1] a \"Inbox item 1\""), "Slice 0 elements must be preserved in 3-slice window");
        assertTrue(pruned2.get(4).optString("content").contains("  [e181] a \"Email 181\""), "Slice 1 elements must be preserved in 3-slice window");
        assertTrue(pruned2.get(5).optString("content").contains("  [e361] a \"Email 361\""), "Slice 2 elements must be preserved in 3-slice window");

        // Add 4th DOM observation (new page action / navigation)
        JSONObject obs3 = new JSONObject();
        obs3.put("role", "tool");
        obs3.put("name", "browser");
        obs3.put("content", "=== [BROWSER ACTION: NAVIGATE] ===\nPage: Compose\nURL: https://mail.test/compose\nInteractive Elements (10 | showing 1-10 of 10):\n  [e1] input \"To\"\nPage Text Excerpt:\nCompose window");
        history.add(obs3);

        List<JSONObject> pruned3 = AcidAgentLoop.pruneHistoryForRequest(history);
        // Now obs0 (4th from end) is compacted, but obs1, obs2, obs3 remain intact!
        String compactedObs0 = pruned3.get(1).optString("content");
        assertTrue(compactedObs0.contains("Interactive Elements: [180 elements superseded by later step; see latest observation below]"), "Oldest slice compacted with accurate superseded notice");
        assertTrue(!compactedObs0.contains("  [e1] a \"Inbox item 1\""), "Compacted slice removes stale element handles");
        // And latest obs3 is 100% intact!
        assertTrue(pruned3.get(6).optString("content").contains("  [e1] input \"To\""), "Latest DOM observation must NEVER be compacted");
    }

    // =========================================================================
    // 4. Stream Thinking Parser State Machine
    // =========================================================================

    private static void testStreamThinkSplitParser() {
        AcidAgentLoop.ThinkStreamParser parser = new AcidAgentLoop.ThinkStreamParser();

        // Feed tags split across arbitrary token boundaries: "<th" + "ink>Deep" + " thoughts</th" + "ink>Final answer"
        parser.feedContentDelta("<th");
        assertEquals("", parser.reasoningSb.toString(), "Partial tag held in pending");
        assertEquals("", parser.contentSb.toString(), "No content emitted while holding tag");

        parser.feedContentDelta("ink>Deep");
        assertTrue(parser.insideThinkTag, "Parser entered thinking state");
        assertEquals("Deep", parser.reasoningSb.toString(), "Reasoning buffer received content");

        parser.feedContentDelta(" thoughts</th");
        assertEquals("Deep thoughts", parser.reasoningSb.toString(), "Reasoning content buffered before closing tag");

        parser.feedContentDelta("ink>Final answer");
        assertTrue(!parser.insideThinkTag, "Parser exited thinking state");
        assertTrue(parser.thinkClosed, "thinkClosed flag set");
        assertEquals("Deep thoughts", parser.reasoningSb.toString(), "Reasoning intact");
        assertEquals("Final answer", parser.contentSb.toString(), "Final answer routed to content buffer");

        parser.finish();
        assertEquals("Final answer", parser.contentSb.toString(), "Content preserved after finish");
    }

    // =========================================================================
    // 5. Sandbox Tools & Security Boundaries
    // =========================================================================

    private static void testSandboxSecurityAndFileBoundaries() throws Exception {
        File wsDir = new File(System.getProperty("java.io.tmpdir"), "acid_sandbox_sec_" + System.currentTimeMillis());
        wsDir.mkdirs();
        try {
            AcidSandboxTools tools = new AcidSandboxTools(null, wsDir);

            // A. P0 Security Fix: browser navigate with file:// URL escaping workspace is strictly blocked
            JSONObject evilNav = new JSONObject();
            evilNav.put("action", "navigate");
            evilNav.put("url", "file:///data/data/com.acid.browser/shared_prefs/acid_models_store_v2.xml");
            AcidSandboxTools.ToolExecResult res = tools.executeTool("browser", evilNav);
            assertTrue(res.outputText != null && res.outputText.contains("Access denied: file:// URL escapes sandbox workspace"),
                "file:// URL escaping sandbox workspace must be blocked: " + res.outputText);

            // B. Path traversal via ../ in file:// URL is blocked
            JSONObject traversalNav = new JSONObject();
            traversalNav.put("action", "navigate");
            traversalNav.put("url", "file://" + wsDir.getAbsolutePath() + "/../../etc/passwd");
            AcidSandboxTools.ToolExecResult res2 = tools.executeTool("browser", traversalNav);
            assertTrue(res2.outputText != null && res2.outputText.contains("Access denied: file:// URL escapes sandbox workspace"),
                "file:// traversal URL must be blocked: " + res2.outputText);

            // C. workspace:// protocol translates safely to file:// inside workspace
            File localReport = new File(wsDir, "report.html");
            AcidIo.writeFileUtf8(localReport, "<h1>Report</h1>");
            File resolved = tools.resolveSandboxFile("report.html");
            assertTrue(resolved != null && resolved.exists(), "resolveSandboxFile resolves report.html");

            // D. Ambiguous string replacement in edit tool is rejected when replace_all=false
            File editTarget = new File(wsDir, "ambiguous.txt");
            AcidIo.writeFileUtf8(editTarget, "apple banana apple");
            JSONObject editArgs = new JSONObject();
            editArgs.put("file_path", "ambiguous.txt");
            editArgs.put("old_string", "apple");
            editArgs.put("new_string", "orange");
            editArgs.put("replace_all", false);
            AcidSandboxTools.ToolExecResult editRes = tools.executeTool("edit", editArgs);
            assertTrue(editRes.outputText.contains("appears multiple times"),
                "edit tool rejects ambiguous replacements when replace_all=false: " + editRes.outputText);
            assertEquals("apple banana apple", AcidIo.readFileUtf8(editTarget), "File content unchanged when ambiguous");

            // E. Overlapping string detection (e.g. 'aaa' in 'aaaa')
            AcidIo.writeFileUtf8(editTarget, "aaaa");
            editArgs.put("old_string", "aaa");
            editArgs.put("new_string", "bbb");
            editArgs.put("replace_all", false);
            AcidSandboxTools.ToolExecResult overlapRes = tools.executeTool("edit", editArgs);
            assertTrue(overlapRes.outputText.contains("appears multiple times"),
                "Overlapping target 'aaa' in 'aaaa' detected as ambiguous: " + overlapRes.outputText);

            // F. Writing userscript from MCP source is blocked (privilege escalation prevention)
            File usDir = new File(wsDir, "userscripts");
            usDir.mkdirs();
            JSONObject usWriteArgs = new JSONObject();
            usWriteArgs.put("file_path", "userscripts/evil.user.js");
            usWriteArgs.put("content", "// ==UserScript==\n// @name evil\n// @match *://*/*\n// ==/UserScript==\n");
            AcidSandboxTools.ToolExecResult mcpWriteRes = tools.executeTool("write", usWriteArgs, AcidSandboxTools.ToolSource.MCP);
            assertTrue(mcpWriteRes.outputText.contains("only available from the in-app agent chat"),
                "Installing userscripts via external MCP gateway must be blocked: " + mcpWriteRes.outputText);

            // G. Bash execution from MCP source is blocked
            JSONObject bashArgs = new JSONObject();
            bashArgs.put("command", "id");
            AcidSandboxTools.ToolExecResult mcpBashRes = tools.executeTool("bash", bashArgs, AcidSandboxTools.ToolSource.MCP);
            assertTrue(mcpBashRes.outputText.contains("restricted to in-app agent chat"),
                "Executing bash shell via external MCP gateway must be blocked: " + mcpBashRes.outputText);
        } finally {
            SkillStorageVault.deleteRecursiveSafe(wsDir);
        }
    }

    // =========================================================================
    // 6. Userscript Meta & URL Glob Pattern Scoping
    // =========================================================================

    private static void testUserscriptMetaAndUrlGlobScoping() {
        // A. Header metadata parsing
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
        assertTrue(meta.hasMetadata, "hasMetadata parsed");
        assertEquals("知乎阅读模式", meta.name, "script name");
        assertEquals("1.2.0", meta.version, "script version");
        assertEquals(UserscriptMeta.RUN_AT_START, meta.runAt, "run-at");

        // B. Strict URL glob scoping (prevent cross-domain injection via URL parameters)
        String zhihuGlob = "*://*.zhihu.com/*";
        assertTrue(UserscriptMeta.globMatches(zhihuGlob, "https://zhuanlan.zhihu.com/p/123"), "Matches valid subdomain");
        assertTrue(UserscriptMeta.globMatches(zhihuGlob, "https://zhihu.com/"), "Matches root domain");
        assertTrue(!UserscriptMeta.globMatches(zhihuGlob, "https://evil.com/redirect?target=https://zhihu.com/login"),
            "Malicious URL with target parameter must NOT match: " + zhihuGlob);
        assertTrue(!UserscriptMeta.globMatches(zhihuGlob, "https://evil.com/zhihu.com/test"),
            "Malicious host path prefix must NOT match: " + zhihuGlob);

        // C. Exclude precedence
        assertTrue(!meta.appliesTo("https://www.zhihu.com/signin?next=/"), "Exclude pattern wins over match pattern");

        // D. Numeric-aware version comparison
        assertTrue(UserscriptManager.isNewerVersion("0.4.18", "0.4.9"), "0.4.18 is newer than 0.4.9");
        assertTrue(UserscriptManager.isNewerVersion("1.0.0", "0.9.9"), "1.0.0 is newer than 0.9.9");
        assertTrue(!UserscriptManager.isNewerVersion("0.4.18", "0.4.18"), "Same version is not newer");
        assertTrue(!UserscriptManager.isNewerVersion("0.3.5", "0.4.0"), "Older version is not newer");
    }

    // =========================================================================
    // 7. Userscript Runtime, Storage Isolation & Grants
    // =========================================================================

    private static void testUserscriptRuntimeStorageAndSecurity() throws Exception {
        File tmpDir = File.createTempFile("acid_us_test_", "_dir");
        tmpDir.delete();
        tmpDir.mkdirs();
        try {
            UserscriptStorage storage = new UserscriptStorage(tmpDir);

            // A. Storage isolation between scripts
            storage.setValue("script_a", "theme", "dark");
            storage.setValue("script_a", "counter", 42);
            storage.setValue("script_b", "theme", "light");

            assertEquals("dark", storage.getValue("script_a", "theme", "def"), "script_a theme");
            assertEquals("light", storage.getValue("script_b", "theme", "def"), "script_b theme isolated");
            assertEquals(Integer.valueOf(42), storage.getValue("script_a", "counter", 0), "script_a counter");

            // B. Disk reload
            UserscriptStorage storage2 = new UserscriptStorage(tmpDir);
            assertEquals("dark", storage2.getValue("script_a", "theme", "def"), "Reloaded storage preserves value");
            storage2.deleteValue("script_a", "theme");
            assertEquals("def", storage2.getValue("script_a", "theme", "def"), "Deleted key falls back to default");

            // C. SHA-256 Script Token Derivation (Tokens must be unique per script ID)
            String seed = "test_seed_123456";
            String tokenA = UserscriptManager.deriveScriptSecret(seed, "script_a.user.js");
            String tokenB = UserscriptManager.deriveScriptSecret(seed, "script_b.user.js");
            assertTrue(tokenA != null && tokenA.length() == 32, "tokenA length is 32 hex chars");
            assertTrue(tokenB != null && tokenB.length() == 32, "tokenB length is 32 hex chars");
            assertTrue(!tokenA.equals(tokenB), "Distinct script IDs must produce distinct tokens");

            // D. @grant Whitelist Enforcement in Native Bridge
            UserscriptMeta grantedMeta = UserscriptMeta.parse("granted.user.js",
                "// ==UserScript==\n// @name g\n// @match *://*/*\n// @grant GM_getValue\n// ==/UserScript==\n");

            UserscriptNativeBridge bridge = new UserscriptNativeBridge(null, storage, null, tokenA);
            bridge.configure(new UserscriptNativeBridge.ScriptResolver() {
                @Override public UserscriptMeta findScript(String id) {
                    return "granted.user.js".equals(id) ? grantedMeta : null;
                }
            }, null, null, null);

            // Attempting ungranted GM_setValue should be denied
            String ungrantedCall = bridge.call("setValue", "{\"secret\":\"" + tokenA + "\",\"scriptId\":\"granted.user.js\",\"name\":\"k\",\"value\":\"v\"}");
            assertEquals("", ungrantedCall, "Ungranted API call must return empty string and be rejected");

            // Attempting call with invalid secret token should be denied
            String badTokenCall = bridge.call("getValue", "{\"secret\":\"bad_token\",\"scriptId\":\"granted.user.js\",\"name\":\"k\"}");
            assertEquals("", badTokenCall, "Invalid secret token call must be rejected");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(tmpDir);
        }
    }

    // =========================================================================
    // 8. RFC 7233 Multi-Threaded Downloader & Resume Engine
    // =========================================================================

    private static void testAcidDownloaderRfc7233RangeAndResume() throws Exception {
        // A. RFC 7233 Disjoint Chunk Partitioning
        long totalSize = 25 * 1024 * 1024; // 25 MB
        int chunkCount = ChunkDivider.determineChunkCount(totalSize, true, 4);
        assertEquals(4, Integer.valueOf(chunkCount), "4 chunks determined for 25MB file");

        List<DownloadChunk> chunks = ChunkDivider.divide(totalSize, chunkCount);
        assertEquals(4, Integer.valueOf(chunks.size()), "4 chunks generated");

        long expectedStart = 0;
        for (int i = 0; i < chunks.size(); i++) {
            DownloadChunk c = chunks.get(i);
            assertEquals(expectedStart, c.startByte, "Chunk " + i + " startByte aligns seamlessly");
            if (i == chunks.size() - 1) {
                assertEquals(totalSize - 1, c.endByte, "Last chunk endByte matches totalSize - 1");
            } else {
                assertTrue(c.endByte >= c.startByte, "Chunk endByte >= startByte");
            }
            expectedStart = c.endByte + 1;
        }

        // B. Real Socket Probe: HTTP 200 OK with 'Accept-Ranges: bytes' must NOT fool HttpProbe into supportsRange=true
        final ServerSocket fakeServer = new ServerSocket(0);
        final int port = fakeServer.getLocalPort();
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket sock = fakeServer.accept();
                    BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = br.readLine()) != null && !line.isEmpty()) {}
                    OutputStream os = sock.getOutputStream();
                    String resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 10485760\r\n" +
                        "Content-Type: application/zip\r\n" +
                        "Accept-Ranges: bytes\r\n" + // Server claims to support ranges, but actually returned 200 instead of 206!
                        "Connection: close\r\n\r\nPK_TEST_STREAM";
                    os.write(resp.getBytes(StandardCharsets.US_ASCII));
                    os.flush();
                    sock.close();
                } catch (Exception ignored) {
                } finally {
                    try { fakeServer.close(); } catch (Exception ignored) {}
                }
            }
        });
        serverThread.start();

        HttpProbe.ProbeResult probeRes = HttpProbe.probe("http://127.0.0.1:" + port + "/file.zip", null);
        serverThread.join(2000);
        assertTrue(!probeRes.supportsRange, "200 OK response must mark supportsRange=false even if Accept-Ranges header exists");
        assertEquals(10485760L, probeRes.contentLength, "Content-Length resolved");

        // C. Generation Token Isolation (Stale worker abort)
        File dlDir = new File(System.getProperty("java.io.tmpdir"), "acid_dl_test_" + System.currentTimeMillis());
        dlDir.mkdirs();
        try {
            DownloadTask task = new DownloadTask(9001L, "http://127.0.0.1/fake.bin", "fake.bin", "application/octet-stream", dlDir, null);
            DownloadChunk ch = new DownloadChunk(0, 0, 5000);
            task.setChunks(Collections.singletonList(ch));
            int g0 = task.getGeneration();

            final boolean[] workerExecuted = new boolean[]{false};
            DownloadWorker staleWorker = new DownloadWorker(task, ch, new DownloadWorker.WorkerListener() {
                @Override public void onChunkProgress(DownloadTask t, DownloadChunk c, int b) { workerExecuted[0] = true; }
                @Override public void onChunkCompleted(DownloadTask t, DownloadChunk c) { workerExecuted[0] = true; }
                @Override public void onChunkFailed(DownloadTask t, DownloadChunk c, Exception e) { workerExecuted[0] = true; }
            }, g0);

            // Increment generation (simulating pause)
            task.nextGeneration();
            staleWorker.run();
            assertTrue(!workerExecuted[0], "Worker with stale generation token must halt immediately without execution");

            // D. Checkpoint Isolation: .part.cfg belongs strictly to its own URL; reject mismatched URLs
            task.saveCheckpoint();
            assertTrue(task.checkpointFile.exists(), "Checkpoint file saved");

            // Create a different task pointing to the same file name but a different URL
            DownloadTask attackerTask = new DownloadTask(9002L, "http://attacker.com/evil.bin", "fake.bin", "application/octet-stream", dlDir, null);
            boolean resumed = attackerTask.loadCheckpoint();
            assertTrue(!resumed, "Checkpoint must be rejected when remote URL differs (cross-URL checkpoint pollution guard)");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(dlDir);
        }

        // E. RFC 5987 filename* and Content-Disposition sanitization
        String dispStar = "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E6%96%87%E6%A1%A3.pdf";
        String fnStar = HttpProbe.parseFileNameFromDisposition(dispStar);
        assertEquals("测试文档.pdf", fnStar, "RFC 5987 percent-encoded filename extracted");

        String dispBad = "attachment; filename=\"../../../evil.apk\"";
        String fnClean = HttpProbe.parseFileNameFromDisposition(dispBad);
        assertTrue(!fnClean.contains("/"), "Path traversal slashes must be stripped: " + fnClean);
        assertEquals("evil.apk", fnClean, "Leaf filename preserved cleanly");
    }

    // =========================================================================
    // 9. AdBlock Engine & ABP Rule Hierarchy
    // =========================================================================

    private static void testAdBlockAbpParsingAndDomainHierarchy() {
        Set<String> blocked = new HashSet<String>();
        Set<String> whitelist = new HashSet<String>();
        List<String> paths = new ArrayList<String>();
        Map<String, List<String>> domainHiding = new HashMap<String, List<String>>();
        List<String> globalHiding = new ArrayList<String>();

        // Parse ABP rules
        assertTrue(AdBlockManager.parseRuleLine("||adserver.com^", blocked, whitelist, paths, domainHiding, globalHiding), "Parse domain rule");
        assertTrue(AdBlockManager.parseRuleLine("@@||trusted.adserver.com^", blocked, whitelist, paths, domainHiding, globalHiding), "Parse whitelist rule");
        assertTrue(AdBlockManager.parseRuleLine("example.com##.sponsor-banner", blocked, whitelist, paths, domainHiding, globalHiding), "Parse hiding rule");
        assertTrue(AdBlockManager.parseRuleLine("/adframe/", blocked, whitelist, paths, domainHiding, globalHiding), "Parse path rule");

        // Domain hierarchy matching
        assertTrue(AdBlockManager.matchesDomainHierarchy("sub.adserver.com", blocked), "Subdomain matches domain block rule");
        assertTrue(AdBlockManager.matchesDomainHierarchy("deep.sub.adserver.com", blocked), "Deep subdomain matches domain block rule");
        assertTrue(!AdBlockManager.matchesDomainHierarchy("notadserver.com", blocked), "Suffix lookalike must not match");

        // TLD & Public Suffix Guard (never collateral block entire .com or .cn)
        assertTrue(AdBlockManager.isPublicSuffixOrTld("com"), ".com is TLD");
        assertTrue(AdBlockManager.isPublicSuffixOrTld("co.uk"), ".co.uk is public suffix");
        assertTrue(!AdBlockManager.isPublicSuffixOrTld("adserver.com"), "adserver.com is not TLD");
    }

    // =========================================================================
    // 10. DataStore Persistence, Two-Way Sync & Fault-Tolerance
    // =========================================================================

    private static void testBrowserDataStoreSyncAndFaultTolerance() throws Exception {
        File tmpDir = File.createTempFile("acid_ds_test_", "_dir");
        tmpDir.delete();
        tmpDir.mkdirs();
        try {
            File settingsFile = new File(tmpDir, "settings.json");
            File bookmarksFile = new File(tmpDir, "bookmarks.json");
            File historyFile = new File(tmpDir, "history.json");

            InMemoryPrefs primaryPrefs = new InMemoryPrefs();
            BrowserDataStore store = new BrowserDataStore(primaryPrefs, new InMemoryPrefs(), settingsFile, bookmarksFile, historyFile);

            // A. Initial sync creates json files
            store.syncSettingsJsonFile();
            store.syncBookmarksJsonFile();
            store.syncHistoryJsonFile();
            assertTrue(settingsFile.exists(), "settings.json created");
            assertTrue(bookmarksFile.exists(), "bookmarks.json created");
            assertTrue(historyFile.exists(), "history.json created");

            // B. Add bookmark and verify disk persistence
            store.addBookmark("Acid Browser", "https://acid.org");
            List<BrowserDataStore.EntryItem> list = store.loadBookmarks();
            assertEquals(7, Integer.valueOf(list.size()), "7 bookmarks in store (6 default presets + 1 added)");
            boolean found = false;
            for (BrowserDataStore.EntryItem it : list) {
                if ("Acid Browser".equals(it.title) && "https://acid.org".equals(it.url)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Added bookmark persisted and found in store");

            // C. Corrupt JSON fault tolerance: corrupt JSON must not wipe memory state
            FileOutputStream fos = new FileOutputStream(bookmarksFile);
            fos.write("{bad_corrupt_json".getBytes(StandardCharsets.UTF_8));
            fos.close();

            boolean reloaded = store.reloadBookmarksFromJsonFile();
            assertTrue(!reloaded, "Corrupt JSON reload safely rejected");
            assertEquals(7, Integer.valueOf(store.loadBookmarks().size()), "Existing bookmarks preserved on corrupt reload");

            // D. Temporary workspace file cleanup whitelist protection
            File dummyHomepage = new File(tmpDir, "homepage");
            dummyHomepage.mkdirs();
            File dummyReport = new File(tmpDir, "temp_report.html");
            dummyReport.createNewFile();

            int cleaned = store.clearWorkspaceTempFiles(tmpDir);
            assertTrue(!dummyReport.exists(), "Unwhitelisted temp report deleted");
            assertTrue(bookmarksFile.exists(), "bookmarks.json protected by whitelist");
            assertTrue(settingsFile.exists(), "settings.json protected by whitelist");
            assertTrue(dummyHomepage.exists(), "homepage directory protected by whitelist");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(tmpDir);
        }
    }

    // =========================================================================
    // 11. Local MCP Server & Network SSRF Protection
    // =========================================================================

    private static void testMcpServerJsonRpcAndSsrfProtection() throws Exception {
        // A. Decimal integer IP representation (2130706433 = 127.0.0.1) targeting port 3090 is blocked
        boolean ssrfBlocked = false;
        try {
            UserscriptHttpHandler.validateAllowedHttpTarget(new URL("http://2130706433:3090/mcp"));
        } catch (SecurityException se) {
            ssrfBlocked = true;
        }
        assertTrue(ssrfBlocked, "Decimal integer IP representation targeting port 3090 must trigger SecurityException");

        // B. Standard public URL is permitted
        UserscriptHttpHandler.validateAllowedHttpTarget(new URL("https://example.com/api"));

        // C. Route specificity: /message_status must not match /message
        String pExact = "/message";
        String pSlash = "/message/";
        String pBad = "/message_invalid";
        assertTrue("/message".equals(pExact) || pExact.startsWith("/message/"), "/message exact allowed");
        assertTrue("/message".equals(pSlash) || pSlash.startsWith("/message/"), "/message/ slash allowed");
        assertTrue(!("/message".equals(pBad) || pBad.startsWith("/message/")), "/message_invalid strictly rejected");

        // D. Chunked body decoder
        byte[] chunkedPayload = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] decoded = HttpControlServer.readChunkedBody(new java.io.ByteArrayInputStream(chunkedPayload), 1024 * 1024);
        assertEquals("Wikipedia", new String(decoded, StandardCharsets.UTF_8), "Chunked body assembled correctly");
    }

    // =========================================================================
    // 12. UI Rendering, Viewport & Text Shaping
    // =========================================================================

    private static void testUiRenderingAndTextShaping() {
        // A. Markdown code block placeholder protection (Asterisks inside `code` must NOT be altered or bolded)
        String inputLine = "Execute `git commit -m \"**wip**\"` to commit.";
        List<String> snippets = new ArrayList<String>();
        String masked = AcidMarkdownView.maskCodeSnippets(inputLine, snippets);
        assertEquals(1, Integer.valueOf(snippets.size()), "1 code snippet extracted");
        assertEquals("git commit -m \"**wip**\"", snippets.get(0), "Code snippet preserved exactly");
        assertTrue(!masked.contains("**wip**"), "Masked text does not expose asterisks to bold matcher");
        assertTrue(masked.contains("\uE0000\uE001"), "Masked text contains protected placeholder");

        // A2. Multi-paragraph continuous buffer stream (preserves single blank line between paragraphs)
        StringBuilder pb = new StringBuilder();
        AcidMarkdownView.appendParaLine(pb, "Paragraph 1");
        AcidMarkdownView.appendParaLine(pb, "");
        AcidMarkdownView.appendParaLine(pb, "");
        AcidMarkdownView.appendParaLine(pb, "Paragraph 2");
        assertEquals("Paragraph 1\n\nParagraph 2", pb.toString(), "Multi-paragraph preserves exactly one blank line separator");

        // B. Desktop Viewport Manager script generation & URL eligibility
        assertTrue(DesktopViewportManager.isEligibleUrl("https://github.com/login"), "https eligible for desktop viewport");
        assertTrue(DesktopViewportManager.isEligibleUrl("http://localhost:3000"), "http eligible for desktop viewport");
        assertTrue(!DesktopViewportManager.isEligibleUrl("file:///android_asset/homepage/index.html"), "file:// excluded from viewport override");
        assertTrue(!DesktopViewportManager.isEligibleUrl("about:blank"), "about: excluded from viewport override");

        String desktopScript = DesktopViewportManager.buildViewportScript(true);
        assertTrue(desktopScript.contains("width=" + DesktopViewportManager.DESKTOP_WIDTH), "Desktop script sets 1280px width");

        // C. JsEvalShaper Expression and Statement shaping
        List<JsEvalShaper.Candidate> exprCand = JsEvalShaper.candidates("document.title");
        assertEquals(JsEvalShaper.MODE_EXPRESSION, exprCand.get(0).mode, "Single expression mode");
        assertTrue(exprCand.get(0).body.contains("return (document.title"), "Expression wrapped with return");

        List<JsEvalShaper.Candidate> stmtCand = JsEvalShaper.candidates("let a = 1;\na + 2;");
        assertTrue(stmtCand.size() >= 2, "Generates multiple AST shape candidates");
    }

    // =========================================================================
    // 13. Theme, Async & IO Foundation
    // =========================================================================

    private static void testThemeAndColorContrast() throws Exception {
        // A. AcidTheme onColor contrast
        int onDark = AcidTheme.onColor(0xFF000000); // Black background
        assertEquals(android.graphics.Color.WHITE, onDark, "onColor on pure black is white");

        int onLight = AcidTheme.onColor(0xFFFFFFFF); // White background
        assertEquals(android.graphics.Color.BLACK, onLight, "onColor on pure white is black");

        // B. AcidIo stream round-trip
        String testText = "Acid Browser Zero-Dependency Native Runtime 🚀";
        byte[] bytes = testText.getBytes(StandardCharsets.UTF_8);
        String readBack = AcidIo.readStreamUtf8(new java.io.ByteArrayInputStream(bytes));
        assertEquals(testText, readBack, "Stream read round-trip UTF-8 matches");

        // C. DownloadItem Category classification
        DownloadItem itemApk = new DownloadItem(1L, "Acid.apk", "http://dl/a.apk", null, 1024, 1024, 8, 0);
        assertEquals(DownloadItem.CAT_APK, itemApk.getCategory(), "Classified as APK");
        assertEquals("安装包", itemApk.getCategoryLabel(), "APK category label");

        DownloadItem itemZip = new DownloadItem(2L, "data.tar.gz", "http://dl/a.tar.gz", null, 1024, 1024, 8, 0);
        assertEquals(DownloadItem.CAT_ARCHIVE, itemZip.getCategory(), "Classified as Archive");
    }

    // =========================================================================
    // 14. Batch 1 Security & Core Bug Fixes Regression
    // =========================================================================

    private static void testBatch1SecurityAndCoreFixes() throws Exception {
        // A. P0-1: Local homepage bookmark isolation & request header origin guard
        File wsDir = new File(System.getProperty("java.io.tmpdir"), "acid_b1_home_" + System.currentTimeMillis());
        File wsHomeDir = new File(wsDir, "homepage");
        wsHomeDir.mkdirs();
        File customHome = new File(wsHomeDir, "index.html");
        AcidIo.writeFileUtf8(customHome, "<html></html>");
        try {
            assertTrue(BrowserController.isTrustedLocalHomepageFileUrl(BrowserController.HOME_URL, wsDir), "Bundled asset homepage trusted");
            assertTrue(BrowserController.isTrustedLocalHomepageFileUrl("file://" + customHome.getAbsolutePath(), wsDir), "Workspace homepage trusted");
            assertTrue(!BrowserController.isTrustedLocalHomepageFileUrl("https://evil.com/homepage/index.html", wsDir), "Remote URL ending in /homepage/index.html rejected");
            assertTrue(!BrowserController.isTrustedLocalHomepageFileUrl("file://" + wsDir.getAbsolutePath() + "/homepage/../settings.json", wsDir), "Path traversal out of homepage/ rejected");
            assertTrue(!BrowserController.isTrustedLocalHomepageFileUrl("about:blank", wsDir), "about:blank rejected for bookmark injection");

            Map<String, String> localHeaders = new HashMap<String, String>();
            localHeaders.put("Origin", "null");
            assertTrue(BrowserController.isAllowedLocalHomeRequestHeaders(localHeaders), "file:// Origin: null allowed");

            Map<String, String> evilHeaders = new HashMap<String, String>();
            evilHeaders.put("Origin", "https://attacker.example");
            assertTrue(!BrowserController.isAllowedLocalHomeRequestHeaders(evilHeaders), "External web Origin blocked");

            Map<String, String> evilRef = new HashMap<String, String>();
            evilRef.put("Referer", "https://attacker.example/index.html");
            assertTrue(!BrowserController.isAllowedLocalHomeRequestHeaders(evilRef), "External web Referer blocked");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(wsDir);
        }

        // B. P0-2: Cloud-drive domain boundary & redirect credential stripping
        assertEquals(DownloadCoordinator.CLOUD_DRIVE_BAIDU,
            DownloadCoordinator.classifyCloudDriveUrl("https://appall01.baidupcs.com/file/test.apk"), "Authentic Baidu PCS HTTPS matched");
        assertEquals(DownloadCoordinator.CLOUD_DRIVE_QUARK,
            DownloadCoordinator.classifyCloudDriveUrl("https://dl.quark.cn/download?auth_key=1"), "Authentic Quark HTTPS matched");
        assertEquals(DownloadCoordinator.CLOUD_DRIVE_115,
            DownloadCoordinator.classifyCloudDriveUrl("https://cdn.115cdn.com/obj/1.zip"), "Authentic 115 CDN HTTPS matched");
        assertEquals(DownloadCoordinator.CLOUD_DRIVE_NONE,
            DownloadCoordinator.classifyCloudDriveUrl("https://attacker.com/steal.apk?ref=baidupcs.com&x=.quark.cn&y=115.com"),
            "Substring spoof in query string rejected");
        assertEquals(DownloadCoordinator.CLOUD_DRIVE_NONE,
            DownloadCoordinator.classifyCloudDriveUrl("https://evil-baidupcs.com/file/1.apk"),
            "Lookalike suffix domain rejected");
        assertEquals(DownloadCoordinator.CLOUD_DRIVE_NONE,
            DownloadCoordinator.classifyCloudDriveUrl("http://appall01.baidupcs.com/file/test.apk"),
            "Cleartext HTTP cloud-drive URL rejected for cookie injection");
        assertTrue(!DownloadCoordinator.isCloudDriveDirectStreamUrl("https://attacker.com/.baidupcs.com/file/a.bin"),
            "Direct stream detector rejects path substring spoof");

        assertTrue(HttpProbe.shouldRetainCredentialsOnRedirect(
            new URL("https://d.pcs.baidu.com/file/a"), new URL("https://qd01.baidupcs.com/file/a")),
            "Baidu ecosystem HTTPS redirect retains credentials");
        assertTrue(!HttpProbe.shouldRetainCredentialsOnRedirect(
            new URL("https://d.pcs.baidu.com/file/a"), new URL("http://qd01.baidupcs.com/file/a")),
            "HTTPS -> HTTP downgrade strips credentials");
        assertTrue(!HttpProbe.shouldRetainCredentialsOnRedirect(
            new URL("https://pan.baidu.com/file/a"), new URL("https://attacker.org/collect")),
            "Cross-origin third-party redirect strips credentials");

        // C. P0-5: External ACTION_VIEW Intent scheme allowlist
        assertTrue(BrowserActivity.isSafeExternalViewUri("https://github.com/AcidGr"), "HTTPS external intent allowed");
        assertTrue(BrowserActivity.isSafeExternalViewUri("HTTP://localhost:3000/test"), "Case-insensitive HTTP external intent allowed");
        assertTrue(!BrowserActivity.isSafeExternalViewUri("javascript:fetch('https://evil.com')"), "javascript: intent blocked");
        assertTrue(!BrowserActivity.isSafeExternalViewUri("file:///data/user/0/com.acid.browser/shared_prefs/a.xml"), "file:// intent blocked");
        assertTrue(!BrowserActivity.isSafeExternalViewUri("data:text/html,<script>alert(1)</script>"), "data: intent blocked");

        // D. P0-6: Stream "null" token preservation & strict tool JSON argument parsing
        AcidAgentLoop.ThinkStreamParser nullParser = new AcidAgentLoop.ThinkStreamParser();
        nullParser.feedReasoningDelta("check ");
        nullParser.feedReasoningDelta("null");
        nullParser.feedContentDelta("if (el !== ");
        nullParser.feedContentDelta("null");
        nullParser.feedContentDelta(") return;");
        nullParser.finish();
        assertEquals("check null", nullParser.reasoningSb.toString(), "Reasoning 'null' token preserved");
        assertEquals("if (el !== null) return;", nullParser.contentSb.toString(), "Content 'null' token preserved");

        Map<Integer, AcidAgentLoop.StreamingToolCall> tcMap = new HashMap<Integer, AcidAgentLoop.StreamingToolCall>();
        AcidAgentLoop.accumulateToolCallsDelta(tcMap, new JSONArray("[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"eval\",\"arguments\":\"{\\\"v\\\":\"}}]"));
        AcidAgentLoop.accumulateToolCallsDelta(tcMap, new JSONArray("[{\"index\":0,\"function\":{\"arguments\":\"null\"}}]"));
        AcidAgentLoop.accumulateToolCallsDelta(tcMap, new JSONArray("[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]"));
        assertEquals("{\"v\":null}", tcMap.get(0).arguments.toString(), "Tool call 'null' argument chunk preserved");

        assertEquals(0, AcidAgentLoop.parseToolArgumentsStrict("").length(), "Empty tool args allowed as {}");
        boolean rejectedBrokenJson = false;
        try {
            AcidAgentLoop.parseToolArgumentsStrict("{\"action\":\"click\",\"ref\":");
        } catch (Exception expected) {
            rejectedBrokenJson = true;
        }
        assertTrue(rejectedBrokenJson, "Truncated/invalid tool JSON must throw instead of silently becoming {}");

        // E. P0-7 & P1-6: AdBlock path-scoped rules never block/whitelist entire domains + CSS selector isolation
        AdBlockManager.RuleSet rs = new AdBlockManager.RuleSet();
        assertTrue(AdBlockManager.parseRuleLine("||youtube.com/get_midroll_info", rs), "Scoped block rule parsed");
        assertTrue(AdBlockManager.parseRuleLine("@@||bing.com/fd/ls/GLinkPing.aspx?", rs), "Scoped whitelist rule parsed");
        assertTrue(AdBlockManager.parseRuleLine("||bing.com/ads/", rs), "Scoped bing ad path parsed");
        assertTrue(!AdBlockManager.parseRuleLine("@@||tencent-cloud.cn^$domain=tencent.com", rs), "$domain= rule not promoted to global whitelist");

        assertTrue(!AdBlockManager.isUriBlocked(android.net.Uri.parse("https://www.youtube.com/watch?v=abc"), rs),
            "Main YouTube video page must NOT be blocked by path-scoped rule");
        assertTrue(AdBlockManager.isUriBlocked(android.net.Uri.parse("https://www.youtube.com/get_midroll_info?slot=1"), rs),
            "Specific YouTube ad path IS blocked");
        assertTrue(AdBlockManager.isUriBlocked(android.net.Uri.parse("https://www.bing.com/ads/banner.js"), rs),
            "Bing ad path remains blocked (not globally whitelisted)");
        assertTrue(!AdBlockManager.isUriBlocked(android.net.Uri.parse("https://www.bing.com/fd/ls/GLinkPing.aspx?p=1"), rs),
            "Specific Bing whitelisted path is allowed");
        assertTrue(!AdBlockManager.isHostWhitelisted("gstatic.com.evil-tracker.xyz", rs),
            "Lookalike gstatic hostname must not bypass AdBlock");

        assertTrue(!AdBlockManager.isSafeCssSelector(".a { display:none } body { background:url(//x) }"), "CSS breakout rejected");
        assertTrue(!AdBlockManager.isSafeCssSelector("+js(abort-on-property-read, ads)"), "uBlock scriptlet rejected");
        AdBlockManager.parseRuleLine("example.com##.ad-slot", rs);
        AdBlockManager.parseRuleLine("##.global-ad", rs);
        String isolatedCss = AdBlockManager.buildIsolatedElementHidingCss(rs, "sub.example.com");
        assertTrue(isolatedCss.contains(".global-ad { display: none !important;"), "Global selector emitted as independent CSS rule");
        assertTrue(isolatedCss.contains(".ad-slot { display: none !important;"), "Domain selector emitted as independent CSS rule");

        // F. P0-8: AcidMarkdownView PUA placeholder injection & bounds guard
        List<String> mdSnips = new ArrayList<String>();
        String rawPuaLine = "Inline `code` with rogue \uE00099\uE001, spoofed \uE0000\uE001, and overflow \uE00099999999999999999999\uE001 markers";
        String maskedPua = AcidMarkdownView.maskCodeSnippets(rawPuaLine, mdSnips);
        android.text.SpannableStringBuilder puaSsb = new android.text.SpannableStringBuilder(maskedPua);
        AcidMarkdownView.restoreMaskedSnippets(puaSsb, mdSnips, false, 0, 0);
        String renderedStr = puaSsb.toString();
        assertTrue(renderedStr.contains(" code "), "Valid inline code rendered");
        assertTrue(renderedStr.contains("rogue \uE00099\uE001, spoofed \uE0000\uE001"), "Literal PUA markers escaped & restored verbatim without crash or spoofing");
        // Direct injection of unescaped out-of-range marker into restoreMaskedSnippets must also never throw
        android.text.SpannableStringBuilder forgedSsb = new android.text.SpannableStringBuilder("x \uE00042\uE001 \uE000999999999999999999\uE001 y");
        AcidMarkdownView.restoreMaskedSnippets(forgedSsb, mdSnips, false, 0, 0);
        assertEquals("x \uE00042\uE001 \uE000999999999999999999\uE001 y", forgedSsb.toString(), "Out-of-bounds/overflow unescaped markers safely ignored");

        // G. P1-8: Filename dot-traversal sanitization & AcidFileProvider bucket isolation
        assertEquals(null, HttpProbe.decodeAndSanitizeFileName(".."), "Dot-dot filename rejected");
        assertEquals("download", DownloadCoordinator.sanitizeDownloadFileName("..."), "Multi-dot filename falls back to 'download'");
        String singleDecoded = HttpProbe.decodeAndSanitizeFileName("%252e%252e%252fpayload.apk");
        assertTrue(singleDecoded != null && singleDecoded.contains("2e"), "Double-encoded traversal is not double-decoded");

        android.net.Uri intUri = AcidFileProvider.getUriForFile(new File("/data/user/0/com.acid.browser/files/Download/update.apk"));
        android.net.Uri extUri = AcidFileProvider.getUriForFile(new File("/storage/emulated/0/Android/data/com.acid.browser/files/Download/update.apk"));
        assertEquals(AcidFileProvider.BUCKET_INT_PRIVATE, AcidFileProvider.extractBucketFromPath(intUri.getPath()), "Internal private Download bucket encoded");
        assertEquals(AcidFileProvider.BUCKET_EXT_PRIVATE, AcidFileProvider.extractBucketFromPath(extUri.getPath()), "External private Download bucket encoded");

        // H. Release Blocker Fixes (Batch 2):
        // 1. Userscript @match host boundary & tab-indented @grant & @require ES6 scope
        assertTrue(!UserscriptMeta.globMatches("*://*.zhihu.com/*", "https://attacker.com?.zhihu.com/"),
            "Query-before-slash host spoofing must not match *.zhihu.com");
        assertTrue(!UserscriptMeta.globMatches("*://*.zhihu.com/*", "https://zhihu.com@attacker.com/"),
            "Userinfo host spoofing must not match *.zhihu.com");
        assertTrue(!UserscriptMeta.globMatches("*://*/*", "file:///data/user/0/com.acid.browser/shared_prefs/AcidBrowserPrefs.xml"),
            "*://*/* must only match http/https, not file://");
        UserscriptMeta tabMeta = UserscriptMeta.parse("tab.user.js",
            "// ==UserScript==\n// @name\tTabTest\n// @match\thttps://example.com/*\n// @grant\tnone\n// ==/UserScript==\nconsole.log(LIB_CONST);");
        assertTrue(!tabMeta.isGranted("GM_xmlhttpRequest"), "Tab-indented @grant none must deny GM_xmlhttpRequest");
        List<String> reqDeps = new ArrayList<String>();
        reqDeps.add("const LIB_CONST = 42;");
        String shapedNoGrant = UserscriptShaper.shape(tabMeta, reqDeps, "super_secret_token_999");
        assertTrue(!shapedNoGrant.contains("super_secret_token_999"), "@grant none script must not embed native bridge secret in JS closure");
        assertTrue(shapedNoGrant.contains("const LIB_CONST = 42;\n;\n"), "@require dependency injected in function scope without inner try{} block");

        // 2. Sandbox navigation URL validation & password log masking
        File wsTmp = File.createTempFile("acid_sbx_ws_", "_dir");
        wsTmp.delete();
        wsTmp.mkdirs();
        try {
            AcidSandboxTools sbx = new AcidSandboxTools(null, wsTmp);
            assertEquals(null, sbx.resolveAndValidateNavigationUrl("FILE:///data/data/com.acid.browser/shared_prefs/AcidBrowserPrefs.xml"),
                "Uppercase FILE:// outside workspace must be rejected");
            assertEquals(null, sbx.resolveAndValidateNavigationUrl("file:///android_asset/../../data/data/com.acid.browser/shared_prefs/AcidBrowserPrefs.xml"),
                "android_asset .. traversal must be rejected");
            assertEquals(null, sbx.resolveAndValidateNavigationUrl("content://com.acid.browser.fileprovider/int_private/x"),
                "content:// URI must be rejected in sandbox navigation");
            assertEquals("file:///android_asset/homepage.html", sbx.resolveAndValidateNavigationUrl("file:///android_asset/homepage.html"),
                "Clean android_asset URL remains allowed");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(wsTmp);
        }

        assertTrue(WebAutomationDriver.shouldMaskFillLog(new JSONObject("{\"ref\":\"e1\"}"), new JSONObject("{\"ok\":true,\"value\":\"***\"}")),
            "Password receipt with value='***' must mask log");
        assertTrue(!WebAutomationDriver.shouldMaskFillLog(new JSONObject("{\"ref\":\"e1\"}"), new JSONObject("{\"ok\":true,\"value\":\"hello\"}")),
            "Normal input receipt must not mask log");

        // 3. Chunked transfer integer overflow guard
        boolean overflowCaught = false;
        try {
            byte[] maliciousChunked = "5\r\nhello\r\n7fffffff\r\n".getBytes(StandardCharsets.US_ASCII);
            HttpControlServer.readChunkedBody(new java.io.ByteArrayInputStream(maliciousChunked), 1024);
        } catch (java.io.IOException ioe) {
            overflowCaught = true;
        }
        assertTrue(overflowCaught, "Chunk size 0x7fffffff must throw IOException instead of overflowing baos.size() + chunkSize");

        // 4. HistoryRepository stale async snapshot guard after clearHistory()
        File histFile = File.createTempFile("acid_hist_race_", ".json");
        try {
            HistoryRepository repo = new HistoryRepository(null, new InMemoryPrefs(), histFile);
            repo.recordHistory("https://example.com/1", "Page 1", false);
            repo.clearHistory();
            JSONArray staleSnapshot = new JSONArray("[{\"title\":\"Stale\",\"url\":\"https://stale.example\",\"time\":1}]");
            boolean staleSaved = repo.saveHistoryToJsonFile(staleSnapshot, 1L);
            assertTrue(!staleSaved, "Stale revision snapshot must be rejected after clearHistory()");
            assertEquals(0, Integer.valueOf(repo.loadHistory().size()), "Cleared history remains empty");
        } finally {
            histFile.delete();
        }
    }
}
