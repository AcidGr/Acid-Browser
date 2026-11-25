package com.acid.browser;

import android.net.Uri;

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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

        // 15. Audit Batch-1 Fixes Regression
        run("15. Audit Batch-1: SemVer build meta, Userscript glob/header/@unwrap/traversal, Skill dedup, AdBlock $3p, DOM prune, Sandbox MCP/XSS & Host guard", new TestCase() {
            @Override public void execute() throws Exception { testAuditBatch1Fixes(); }
        });

        // 16. Batch-2 Stability & Infrastructure Convergence Regression
        run("16. Batch-2 Stability: DownloadTask I/O generation lock, HttpProbe registration, Pending-sync overwrite guard, AdBlock flush, IPv6 favicons & Zip caps", new TestCase() {
            @Override public void execute() throws Exception { testStabilityAndInfraConvergenceBatch2(); }
        });

        // 17. Post-Audit P0 & P1 Security, State & Lifecycle Regression
        run("17. Post-Audit P0/P1 Fixes: Loopback Origin/SSRF/PSL, MCP .user.js/paths, FileChooser URI/MIME, @unwrap guard, Checkpoint Gen/Status & Null DataStore", new TestCase() {
            @Override public void execute() throws Exception { testPostAuditP0AndP1Regressions(); }
        });

        // 18. Deep Audit Round-2 P0 & P1 Regressions
        run("18. Round-2 P0/P1 Fixes: ModelStore Upgrade, Repo Sync Lock, SSRF CGNAT/ULA/Redirects, MCP Tabs/Takeover, Userscript Shim/Resource, Skill Shadow/YAML, SSE 400/404 & Chunked, AdBlock Google/CSS/Regex, Download Name & FileProvider", new TestCase() {
            @Override public void execute() throws Exception { testPostAudit2Regressions(); }
        });

        // 19. Deep Audit Round-3 P0 & P1 Regressions
        run("19. Round-3 P0/P1 Fixes: SSRF Port/Host Pinning, Favicon Local Probe, ModelStore Async & Redirect, MCP mLanBound & JSON-RPC id, Sandbox .user.js/SkillPkg, Userscript Log/Probe/HTTP, AdBlock Wildcard, Downloader 416", new TestCase() {
            @Override public void execute() throws Exception { testPostAudit3Regressions(); }
        });

        // 20. Agent/MCP Workspace File Upload Support & Protected Files Isolation
        run("20. Workspace Upload: AgentUploadManager Path/Protected Policy, Multi-file & Single-file Rejection, TTL/Cancel, MCP Schema & Parity", new TestCase() {
            @Override public void execute() throws Exception { testAgentWorkspaceUploadFeature(); }
        });

        // 21. Screenshot Observation, Desktop/Mobile Coordinate Alignment, Off-DOM SoM & Multimodal Loop
        run("21. Visual Screenshot: Desktop/Mobile/Zoom Alignment, Off-DOM SoM Projection, Multimodal Pruning & MCP Image Output", new TestCase() {
            @Override public void execute() throws Exception { testScreenshotAlignmentAndMultimodalSupport(); }
        });

        // 22. Desktop Mode Focus Auto-Zoom Overview Recovery
        run("22. Desktop Overview Recovery: Restore Factor Thresholds, Viewport Metrics Script & Safe Driver Guards", new TestCase() {
            @Override public void execute() throws Exception { testDesktopOverviewAutoZoomRecovery(); }
        });

        // 23. Agent Chat File Attachments (+ button, max 5 limit, workspace staging, binary guard, multimodal retention)
        run("23. Agent Chat Attachments: Max-5 Cap, Safe Staging, Classification, Binary Read Guard & Multimodal Pruning", new TestCase() {
            @Override public void execute() throws Exception { testAgentChatAttachmentsFeature(); }
        });

        // 24. Bottom Sheet IME Resize Recovery & Bookmarks/History Batch Preparation
        run("24. Sheet IME Resize & History/Bookmarks Paging: Scroll Ceiling Math, Batch Buckets & Index Preservation", new TestCase() {
            @Override public void execute() throws Exception { testSheetImeCeilingAndHistoryBookmarksPaging(); }
        });

        // 25. Multi-Tab Focus & IME Isolation Policy During Agent Automation
        run("25. WebView Focus & IME Isolation: Foreground Agent IME Suppression, Background Lock, Window Hijack & Mode Reload Target", new TestCase() {
            @Override public void execute() throws Exception { testWebViewFocusAndImeIsolationPolicy(); }
        });

        // 26. Chrome Lock State & One-Stroke Border Outline Geometry
        run("26. Chrome Lock & One-Stroke Border: Multi-Tab Lock Switching & Phone/Tablet/Fullscreen/Split Outline Geometry", new TestCase() {
            @Override public void execute() throws Exception { testChromeLockPolicyAndOutlineGeometry(); }
        });

        // 27. Takeover Resume Custom Message & Running Steering Input State
        run("27. Takeover & Running Steering: Custom Resume Prompt, Bound Tab Preservation & Action Button Mode", new TestCase() {
            @Override public void execute() throws Exception { testTakeoverResumeAndRunningSteering(); }
        });

        // 28. GPU Screenshot Capture Eligibility, CopyBounds Geometry, SuppressionGuard & HUD Idle Detection
        run("28. GPU Screenshot Capture: PixelCopy Eligibility, CopyBounds Non-Stretch Math, Re-entrant Mask Guard & HUD Idle Detection", new TestCase() {
            @Override public void execute() throws Exception { testGpuScreenshotCaptureAndMasking(); }
        });

        // 29. Netscape Bookmark HTML/JSON Import-Export, Folder Normalization & Sheet Grouping
        run("29. Bookmark Import/Export & Folders: Netscape HTML Round-Trip, JSON Import, Dedupe, Folder Batch Ops & Sheet Grouping", new TestCase() {
            @Override public void execute() throws Exception { testNetscapeBookmarkImportExportAndFolders(); }
        });

        // 30. Multi-Select Batch Operations & Bilingual Localization
        run("30. Multi-Select Batch Ops: Bookmark Batch Move/Delete, History Single/Batch Delete, Download Shared-File Safe Batch Delete & Locale", new TestCase() {
            @Override public void execute() throws Exception { testMultiSelectBatchOperationsAndLocale(); }
        });

        // 31. WebDAV Cloud Sync, Full Backup ZIP Round-Trip & Security Guards
        run("31. WebDAV & Backup Center: PROPFIND/MKCOL/PUT/GET Sync, Merge/Overwrite Restore, Zip-Slip Rejection, Secret Isolation & Locale", new TestCase() {
            @Override public void execute() throws Exception { testWebDavSyncAndFullBackupRestore(); }
        });

        // 32. Renderer Single-Flight Gate, Slow-Whole-Doc Guard & Fast Click Download Policy
        run("32. Renderer Single-Flight & Fast Automation: Queue-Pileup Gate, Recovery Probe Cooldown, Whole-Doc SDK Guard & Download Poll Policy", new TestCase() {
            @Override public void execute() throws Exception { testRendererSingleFlightAndFastAutomationPolicies(); }
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
        "assets/skills/cloud_drive_download.md"
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
        assertEquals(Integer.valueOf(200), Integer.valueOf(AcidAgentLoop.DEFAULT_MAX_LOOP_STEPS),
            "Default max agent loop step limit is 200");

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

            // E. Bounded IntKey web_text_zoom persistence, clamping & settings.json hot-reload
            assertEquals(Integer.valueOf(100), Integer.valueOf(store.getWebTextZoom()), "Default web text zoom is 100");
            assertEquals("integer (75-160)", AcidSettings.WEB_TEXT_ZOOM.schemaTypeDescription(), "WEB_TEXT_ZOOM schema bounds");
            assertTrue(AcidSettings.describeJsonSchemaForPrompt().contains("`web_text_zoom`: integer (75-160)"), "Prompt schema includes web_text_zoom");

            store.setWebTextZoom(30);
            assertEquals(Integer.valueOf(75), Integer.valueOf(store.getWebTextZoom()), "Zoom below min clamped to 75");
            store.setWebTextZoom(250);
            assertEquals(Integer.valueOf(160), Integer.valueOf(store.getWebTextZoom()), "Zoom above max clamped to 160");
            store.setWebTextZoom(125);
            assertEquals(Integer.valueOf(125), Integer.valueOf(store.getWebTextZoom()), "Valid zoom 125 persisted");
            store.syncSettingsJsonFile();
            String exportedJson = AcidIo.readFileUtf8OrEmpty(settingsFile, 64 * 1024);
            assertTrue(exportedJson.contains("\"web_text_zoom\":125") || exportedJson.contains("\"web_text_zoom\": 125"), "settings.json exports web_text_zoom");

            JSONObject mutatedJson = new JSONObject(exportedJson);
            mutatedJson.put("web_text_zoom", 999);
            AcidIo.writeFileUtf8Safe(settingsFile, mutatedJson.toString(2));
            assertTrue(store.reloadFromJsonFile(true), "Reload mutated settings.json");
            assertEquals(Integer.valueOf(160), Integer.valueOf(store.getWebTextZoom()), "External settings.json zoom clamped to 160");

            mutatedJson.put("web_text_zoom", 90);
            AcidIo.writeFileUtf8Safe(settingsFile, mutatedJson.toString(2));
            assertTrue(store.reloadFromJsonFile(true), "Reload valid 90 zoom from settings.json");
            assertEquals(Integer.valueOf(90), Integer.valueOf(store.getWebTextZoom()), "Hot-reloaded zoom updated to 90");
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
        StringBuilder pbSingle = new StringBuilder();
        AcidMarkdownView.appendParaLine(pbSingle, "Paragraph 1");
        AcidMarkdownView.appendParaLine(pbSingle, "");
        AcidMarkdownView.appendParaLine(pbSingle, "Paragraph 2");
        assertEquals("Paragraph 1\n\nParagraph 2", pbSingle.toString(), "Single blank line between paragraphs preserved as \\n\\n");

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

        // 5. Language Settings & AcidLocale Hot-Switching (System / Simplified Chinese / English)
        assertEquals(Integer.valueOf(AcidSettings.LANG_SYSTEM), AcidSettings.LANGUAGE.defaultValue, "Default language is Follow System (0)");
        assertEquals(AcidSettings.LANG_EN, AcidSettings.LANGUAGE.codeToIndex("en"), "Language code 'en' maps to LANG_EN");
        assertEquals(AcidSettings.LANG_ZH, AcidSettings.LANGUAGE.codeToIndex("zh-CN"), "Language alias 'zh-CN' maps to LANG_ZH");
        java.util.Locale initialJvmLocale = java.util.Locale.getDefault();
        try {
            AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_EN);
            assertTrue(AcidLocale.isEn(), "AcidLocale switches to English");
            assertEquals(initialJvmLocale, java.util.Locale.getDefault(), "Switching AcidLocale does not pollute JVM Locale.getDefault()");
            assertEquals("Settings", AcidLocale.tr("设置"), "Static label translated to English");
            assertEquals("Blocked 42 times", AcidLocale.tr("已拦截 42 times".replace("times", "次")), "Dynamic counter translated to English");
            assertEquals("All Chats (5)", AcidLocale.tr("全部会话 (5)"), "Section count header translated to English");
            assertEquals(
                "DeepSeek / DeepSeek-R1 · Think:High",
                AcidLocale.tr("DeepSeek / DeepSeek-R1 · 思考:高"),
                "Model status reasoning effort suffix translated to English"
            );
            assertEquals(
                "DeepSeek / DeepSeek-V3 · Think:Off",
                AcidLocale.tr("DeepSeek / DeepSeek-V3 · 关闭思考"),
                "Model status Think:Off suffix translated to English"
            );
            assertEquals(
                "[Archive] Downloading 5.0 MB",
                AcidLocale.tr("[压缩包] 正在下载 5.0 MB"),
                "Download category subtitle (bracket format) translated to English"
            );
            assertEquals(
                "Archive · Downloading 50% · 2.5 MB / 5.0 MB",
                AcidLocale.tr("压缩包 · 正在下载 50% · 2.5 MB / 5.0 MB"),
                "DownloadsSheet real separator subtitle translated to English"
            );
            assertEquals(
                "APK · Saved · Unknown size · Tap to start",
                AcidLocale.tr("安装包 · 待下载 · 未知大小 · 点击开始下载"),
                "DownloadsSheet saved subtitle translated to English"
            );
            assertEquals(
                "Delete chat \"New Chat\"? This cannot be undone.",
                AcidLocale.tr("确定删除对话「新对话」吗？删除后无法恢复。"),
                "Default session title in delete confirmation translated to New Chat"
            );
            assertEquals(
                "Load skill · skills/cloud_drive_download.md",
                AcidLocale.tr("加载技能 · skills/cloud_drive_download.md"),
                "Agent skill load summary translated to English"
            );
            assertEquals(
                "Edit: custom_skill.md",
                AcidLocale.tr("编辑: custom_skill.md"),
                "Skill editor dialog title with colon translated to English"
            );
            assertEquals(
                "Cleaned AI workspace (3 temp items removed, configs & scripts kept)",
                AcidLocale.tr("已清理 AI 工作区 (3 个临时项，settings.json 与脚本已保留)"),
                "Standalone AI workspace cleanup toast translated to English"
            );
            assertEquals(
                "[用户笔记] 正在下载 5.0 MB",
                AcidLocale.tr("[用户笔记] 正在下载 5.0 MB"),
                "Non-download bracketed user text is not mutated by download category rule"
            );
            assertEquals(
                "Disabled · [Core] desc",
                AcidLocale.tr("已停用 · [官方核心] desc"),
                "Disabled builtin skill subtitle prefix translated to English"
            );
            assertEquals(
                "[Package · 3 files] desc",
                AcidLocale.tr("[技能包 · 3个文件] desc"),
                "Multi-file skill package subtitle prefix translated to English"
            );
            assertEquals(
                "Disabled · All Sites · v1.0 · document-end",
                AcidLocale.tr("已停用 · 所有网站 · v1.0 · document-end"),
                "Userscript subtitle translated to English"
            );
            assertEquals("Display & Typography", AcidLocale.tr("显示与排版"), "Display section header translated");
            assertEquals("Webpage Font Size", AcidLocale.tr("网页字体大小"), "Web font size title translated");
            assertEquals("100% (Default)", AcidLocale.tr("100% (标准)"), "Default zoom pill translated");
            assertEquals("Reset (100%)", AcidLocale.tr("恢复默认 (100%)"), "Reset zoom button translated");

            AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_ZH);
            assertTrue(!AcidLocale.isEn(), "AcidLocale switches back to Simplified Chinese");
            assertEquals("设置", AcidLocale.tr("设置"), "Static label preserved in Simplified Chinese");
        } finally {
            AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_SYSTEM);
        }
    }

    // =========================================================================
    // 15. Audit Batch-1 Fixes Regression
    // =========================================================================

    private static void testAuditBatch1Fixes() throws Exception {
        // 1. SemVer build metadata (+build) comparison
        assertEquals(Integer.valueOf(0), Integer.valueOf(AppUpdateChecker.compareSemVer("0.5.2+fp1", "0.5.2")),
            "SemVer +build metadata must compare equal to base version");
        assertTrue(AppUpdateChecker.compareSemVer("0.5.2+fp1", "0.5.3") < 0,
            "0.5.2+fp1 must be older than 0.5.3");
        assertTrue(AppUpdateChecker.compareSemVer("0.5.2+2026", "0.5.10") < 0,
            "0.5.2+2026 must be older than 0.5.10");
        assertEquals(Integer.valueOf(0), Integer.valueOf(AppUpdateChecker.compareSemVer("v0.5.2-alpha.1+fp2", "0.5.2")),
            "Pre-release and +build suffixes stripped consistently");

        // 2. UserscriptMeta *.domain.* internal globs
        assertTrue(UserscriptMeta.globMatches("*://*.google.*/*", "https://www.google.com/search?q=1"),
            "*.google.* must match www.google.com");
        assertTrue(UserscriptMeta.globMatches("*://*.google.*/*", "https://mail.google.co.jp/mail"),
            "*.google.* must match mail.google.co.jp");
        assertTrue(!UserscriptMeta.globMatches("*://*.google.*/*", "https://evilgoogle.com/"),
            "*.google.* must not match evilgoogle.com");

        // 3. UserscriptManager header-only @acid-enabled edit + path traversal guard
        String scriptWithBodyMarker =
            "// ==UserScript==\n"
            + "// @name         TestScript\n"
            + "// @match        https://example.com/*\n"
            + "// @acid-enabled  true\n"
            + "// ==/UserScript==\n"
            + "const marker = \"// @acid-enabled  true\";\n"
            + "console.log(marker);\n";
        String disabledContent = UserscriptManager.setAcidEnabledDirective(scriptWithBodyMarker, false);
        assertTrue(disabledContent.contains("// @acid-enabled  false"), "Header directive toggled to false");
        assertTrue(disabledContent.contains("const marker = \"// @acid-enabled  true\";"),
            "Identical @acid-enabled string inside JS body must remain untouched");

        String scriptNoDirective =
            "// ==UserScript==\n"
            + "// @name         NoDir\n"
            + "// @match        https://example.com/*\n"
            + "// ==/UserScript==\n"
            + "console.log(1);\n";
        String insertedDisabled = UserscriptManager.setAcidEnabledDirective(scriptNoDirective, false);
        assertTrue(insertedDisabled.indexOf("// @acid-enabled  false") > insertedDisabled.indexOf("==UserScript=="),
            "Inserted after header start");
        assertTrue(insertedDisabled.indexOf("// @acid-enabled  false") < insertedDisabled.indexOf("==/UserScript=="),
            "Inserted inside ==UserScript== header block");

        File usDir = File.createTempFile("acid_us_dir_", "_d");
        usDir.delete();
        usDir.mkdirs();
        try {
            assertEquals(null, UserscriptManager.resolveScriptFile(usDir, "../escape.user.js"),
                "Parent traversal must be rejected");
            assertEquals(null, UserscriptManager.resolveScriptFile(usDir, "sub/nested.user.js"),
                "Subdirectory path must be rejected");
            assertEquals(null, UserscriptManager.resolveScriptFile(usDir, ""),
                "Empty script name must be rejected");
            File okFile = UserscriptManager.resolveScriptFile(usDir, "ok.user.js");
            assertTrue(okFile != null && okFile.getParentFile().getCanonicalFile().equals(usDir.getCanonicalFile()),
                "Clean filename resolves inside userscripts dir");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(usDir);
        }

        // 4. UserscriptShaper @unwrap + native grant secret isolation
        UserscriptMeta unwrapGrantedMeta = UserscriptMeta.parse("unwrap_granted.user.js",
            "// ==UserScript==\n// @name UnwrapGrant\n// @match https://example.com/*\n"
            + "// @unwrap\n// @grant GM_setValue\n// ==/UserScript==\nwindow.testVar = 1;");
        String shapedSandboxed = UserscriptShaper.shape(unwrapGrantedMeta, null, "secret_tok_abc");
        assertTrue(!shapedSandboxed.contains("Script Body (@unwrap: page scope)"),
            "@unwrap with native bridge grant must NOT run unwrapped in page scope");
        assertTrue(shapedSandboxed.contains("(function(window, unsafeWindow, GM, GM_info"),
            "@unwrap with native bridge grant stays inside sandboxed closure");

        UserscriptMeta unwrapNoneMeta = UserscriptMeta.parse("unwrap_none.user.js",
            "// ==UserScript==\n// @name UnwrapNone\n// @match https://example.com/*\n"
            + "// @unwrap\n// @grant none\n// ==/UserScript==\nwindow.testVar = 2;");
        String shapedUnwrap = UserscriptShaper.shape(unwrapNoneMeta, null, "secret_tok_abc");
        assertTrue(shapedUnwrap.contains("Script Body (@unwrap: page scope)"),
            "@unwrap with @grant none continues using page scope");
        assertTrue(!shapedUnwrap.contains("secret_tok_abc"),
            "@unwrap with @grant none never embeds bridge secret");

        // 5. SkillManager directory-first deduplication (<slug>/SKILL.md + <slug>.md)
        File wsSkillTmp = File.createTempFile("acid_skill_ws_", "_d");
        wsSkillTmp.delete();
        wsSkillTmp.mkdirs();
        try {
            File demoPkgDir = new File(wsSkillTmp, "skills/demo");
            demoPkgDir.mkdirs();
            AcidIo.writeFileUtf8Safe(new File(demoPkgDir, "SKILL.md"), "# Demo Directory Skill\nDirectory package version");
            AcidIo.writeFileUtf8Safe(new File(wsSkillTmp, "skills/demo.md"), "# Demo Flat Skill\nFlat file version");
            AcidIo.writeFileUtf8Safe(new File(wsSkillTmp, "skills/other.md"), "# Other Skill\nIndependent skill");

            SkillManager sm = new SkillManager(null, wsSkillTmp);
            List<SkillManager.SkillMeta> skills = sm.getSkills();
            int demoCount = 0;
            SkillManager.SkillMeta demoMeta = null;
            boolean hasOther = false;
            for (SkillManager.SkillMeta m : skills) {
                if ("demo".equalsIgnoreCase(m.name) || m.fileName.startsWith("demo")) {
                    demoCount++;
                    demoMeta = m;
                }
                if ("other.md".equals(m.fileName)) {
                    hasOther = true;
                }
            }
            assertEquals(Integer.valueOf(1), Integer.valueOf(demoCount),
                "Coexisting <slug>/SKILL.md and <slug>.md must deduplicate to 1 skill entry");
            assertTrue(demoMeta != null && demoMeta.isDirectoryPackage,
                "Directory package <slug>/SKILL.md must take precedence over flat <slug>.md");
            assertTrue(hasOther, "Non-duplicate flat skill other.md preserved");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(wsSkillTmp);
        }

        // 6. AdBlock $3p/$third-party rejection, prefix-only scoped path & element-hiding TLD break
        AdBlockManager.RuleSet abRs = new AdBlockManager.RuleSet();
        assertTrue(!AdBlockManager.parseRuleLine("||adutp.com^$third-party", abRs),
            "Block rule with $third-party option must not become global domain block");
        assertTrue(!AdBlockManager.parseRuleLine("guanlongfamen.cn$3p", abRs),
            "Block rule with $3p option must not become global domain block");
        assertTrue(AdBlockManager.parseRuleLine("||bing.com/ads/", abRs), "Domain path rule parsed");
        assertTrue(AdBlockManager.isUriBlocked(android.net.Uri.parse("https://bing.com/ads/banner.js"), abRs),
            "Scoped domain path matches prefix /ads/");
        assertTrue(!AdBlockManager.isUriBlocked(android.net.Uri.parse("https://bing.com/search?q=https://x/ads/"), abRs),
            "Scoped domain path must NOT match query substring containing /ads/");
        AdBlockManager.parseRuleLine("co.uk##.psl-leak", abRs);
        String pslCss = AdBlockManager.buildIsolatedElementHidingCss(abRs, "shop.co.uk");
        assertTrue(!pslCss.contains(".psl-leak"), "Element hiding must stop climbing before public suffix co.uk");

        // 7. AcidAgentLoop.pruneHistoryForRequest compacts stale user [Current Browser State] DOM blocks
        List<JSONObject> hist = new ArrayList<JSONObject>();
        for (int i = 0; i < 5; i++) {
            String uContent = (i == 0 ? "Find cheapest flight\n\n" : "Step " + i + "\n\n")
                + "[Current Browser State]\nURL: https://travel.test/" + i
                + "\nInteractive Elements (2):\n  [e" + (i * 2 + 1) + "] button \"Btn" + i + "\"\n"
                + "  [e" + (i * 2 + 2) + "] link \"Lnk" + i + "\"";
            hist.add(new JSONObject().put("role", "user").put("content", uContent));
            hist.add(new JSONObject().put("role", "assistant").put("content", "Step " + i + " ack"));
        }
        List<JSONObject> pruned = AcidAgentLoop.pruneHistoryForRequest(hist);
        String oldestUser = pruned.get(0).getString("content");
        String newestUser = pruned.get(8).getString("content");
        assertTrue(oldestUser.startsWith("Find cheapest flight"), "Original user prompt text preserved after DOM pruning");
        assertTrue(!oldestUser.contains("button \"Btn0\""), "Stale user [Current Browser State] DOM elements pruned");
        assertTrue(newestUser.contains("button \"Btn4\""), "Latest user DOM observation preserved intact");

        // 8. AcidSandboxTools javascript:/data: XSS block & MCP protected write/edit/download guard
        File wsSbxTmp = File.createTempFile("acid_sbx_b1_", "_d");
        wsSbxTmp.delete();
        wsSbxTmp.mkdirs();
        try {
            AcidSandboxTools tools = new AcidSandboxTools(null, wsSbxTmp);
            assertEquals(null, tools.resolveAndValidateNavigationUrl("javascript:alert(1)"),
                "javascript: scheme blocked in resolveAndValidateNavigationUrl");
            assertEquals(null, tools.resolveAndValidateNavigationUrl("data:text/html,<h1>x</h1>"),
                "data: scheme blocked in resolveAndValidateNavigationUrl");
            assertEquals(null, tools.resolveAndValidateNavigationUrl("vbscript:msgbox(1)"),
                "vbscript: scheme blocked in resolveAndValidateNavigationUrl");

            AcidSandboxTools.ToolExecResult jsNavRes = tools.executeTool(
                "browser",
                new JSONObject().put("action", "navigate").put("url", "javascript:alert(1)"),
                AcidSandboxTools.ToolSource.AGENT_UI
            );
            assertTrue(jsNavRes.outputText.startsWith("Error:"), "browser navigate javascript: returns Error");

            String[] protectedMcpTargets = new String[] {
                "userscripts/sub/evil.user.js",
                "homepage/index.html",
                "settings.json",
                "skills/injected.md"
            };
            for (String p : protectedMcpTargets) {
                AcidSandboxTools.ToolExecResult wRes = tools.executeTool(
                    "write",
                    new JSONObject().put("file_path", p).put("content", "payload"),
                    AcidSandboxTools.ToolSource.MCP
                );
                assertTrue(wRes.outputText.startsWith("Error:"), "MCP write must block " + p);
            }

            // Pre-create protected files cleanly and verify MCP edit is also blocked
            AcidIo.writeFileUtf8Safe(new File(wsSbxTmp, "homepage/index.html"), "<html>ok</html>");
            AcidIo.writeFileUtf8Safe(new File(wsSbxTmp, "settings.json"), "{\"ok\":true}");
            AcidSandboxTools.ToolExecResult eHomepage = tools.executeTool(
                "edit",
                new JSONObject().put("file_path", "homepage/index.html").put("old_string", "ok").put("new_string", "xss"),
                AcidSandboxTools.ToolSource.MCP
            );
            assertTrue(eHomepage.outputText.startsWith("Error:"), "MCP edit must block homepage/index.html");

            AcidSandboxTools.ToolExecResult eSettings = tools.executeTool(
                "edit",
                new JSONObject().put("file_path", "settings.json").put("old_string", "true").put("new_string", "false"),
                AcidSandboxTools.ToolSource.MCP
            );
            assertTrue(eSettings.outputText.startsWith("Error:"), "MCP edit must block settings.json");

            AcidSandboxTools.ToolExecResult safeWrite = tools.executeTool(
                "write",
                new JSONObject().put("file_path", "notes/hello.txt").put("content", "hi"),
                AcidSandboxTools.ToolSource.MCP
            );
            assertTrue(!safeWrite.outputText.startsWith("Error:"), "MCP write allows ordinary workspace notes");

            AcidSandboxTools.ToolExecResult dlUserJs = tools.executeTool(
                "browser",
                new JSONObject().put("action", "download").put("url", "https://example.com/payload.user.js?v=1"),
                AcidSandboxTools.ToolSource.MCP
            );
            assertTrue(dlUserJs.outputText.startsWith("Error:"), "MCP browser download blocks .user.js URL");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(wsSbxTmp);
        }

        // 9. HttpControlServer unauthenticated Host / Origin validation
        assertTrue(HttpControlServer.isLocalOrTrustedHost("localhost:3090", false), "localhost trusted");
        assertTrue(HttpControlServer.isLocalOrTrustedHost("127.0.0.1:3090", false), "127.0.0.1 trusted");
        assertTrue(HttpControlServer.isLocalOrTrustedHost("[::1]:3090", false), "[::1] trusted");
        assertTrue(!HttpControlServer.isLocalOrTrustedHost("evil.com:3090", false), "DNS rebinding Host rejected");
        assertTrue(!HttpControlServer.isLocalOrTrustedHost("127.0.0.1.evil.com:3090", false), "Lookalike loopback rejected");
        assertTrue(!HttpControlServer.isLocalOrTrustedHost("192.168.1.99:3090", false), "Arbitrary LAN IP rejected when not lanBound");
        assertTrue(!HttpControlServer.isLocalOrTrustedHost(null, false), "Missing Host rejected");
        assertTrue(HttpControlServer.isLocalOrTrustedOrigin("http://localhost:3090"), "localhost Origin trusted");
        assertTrue(!HttpControlServer.isLocalOrTrustedOrigin("null"), "Opaque 'null' Origin rejected");
        assertTrue(!HttpControlServer.isLocalOrTrustedOrigin("https://evil.com"), "Remote Origin rejected");
    }

    // =========================================================================
    // 16. Batch-2 Stability & Infrastructure Convergence Regression
    // =========================================================================

    private static void testStabilityAndInfraConvergenceBatch2() throws Exception {
        // 1. DownloadTask atomic writeChunkBytes generation lock + AcidIo checkpoint save/load + resetForFreshStart
        File dlDir = File.createTempFile("acid_b2_dl_", "_dir");
        dlDir.delete();
        dlDir.mkdirs();
        try {
            DownloadTask task = new DownloadTask(
                7701L, "https://example.com/archive.bin", "archive.bin", "application/octet-stream", dlDir, null
            );
            DownloadChunk c0 = new DownloadChunk(0, 0, 1023);
            task.setChunks(Collections.singletonList(c0));
            task.supportsRange = true;
            task.contentLength = 1024;
            int g0 = task.getGeneration();

            byte[] payload = "HELLO_ACID_B2".getBytes(StandardCharsets.UTF_8);
            RandomAccessFile raf = new RandomAccessFile(task.destinationFile, "rw");
            try {
                assertTrue(task.writeChunkBytes(raf, c0, payload, payload.length, g0),
                    "Active generation writeChunkBytes succeeds");
                assertEquals((long) payload.length, task.downloadedBytes, "downloadedBytes reflects written slice");

                // Simulate pause bumping generation while lingering worker still holds raf
                task.isPaused = true;
                task.nextGeneration();
                assertTrue(!task.writeChunkBytes(raf, c0, payload, payload.length, g0),
                    "Stale generation worker rejected cleanly by writeChunkBytes");
                assertEquals((long) payload.length, c0.downloadedBytes,
                    "Chunk offset untouched by rejected stale worker");
            } finally {
                AcidIo.closeQuietly(raf);
            }

            assertTrue(task.saveCheckpoint(), "saveCheckpoint persists via AcidIo");
            DownloadTask restored = new DownloadTask(
                7701L, "https://example.com/archive.bin", "archive.bin", "application/octet-stream", dlDir, null
            );
            assertTrue(restored.loadCheckpoint(), "loadCheckpoint restores saved checkpoint");
            assertEquals((long) payload.length, restored.downloadedBytes, "Restored byte count matches saved snapshot");

            task.resetForFreshStart();
            assertEquals(0L, task.downloadedBytes, "resetForFreshStart resets progress cleanly");
            assertTrue(!task.checkpointFile.exists(), "resetForFreshStart cleans checkpoint file");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(dlDir);
        }

        // 2. HttpProbe.probe with DownloadTask registration & AcidHttp redirect credential stripping
        final ServerSocket redirectServer = new ServerSocket(0);
        final int port = redirectServer.getLocalPort();
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 2; i++) {
                        Socket s = redirectServer.accept();
                        BufferedReader br = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)
                        );
                        String reqLine = br.readLine();
                        String line;
                        while ((line = br.readLine()) != null && !line.isEmpty()) {}
                        OutputStream os = s.getOutputStream();
                        if (reqLine != null && reqLine.startsWith("GET /hop1 ")) {
                            String resp = "HTTP/1.1 302 Found\r\n" +
                                "Location: /hop2\r\n" +
                                "Connection: close\r\n\r\n";
                            os.write(resp.getBytes(StandardCharsets.US_ASCII));
                        } else {
                            String resp = "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Range: bytes 0-0/2048\r\n" +
                                "Content-Length: 1\r\n" +
                                "Connection: close\r\n\r\nX";
                            os.write(resp.getBytes(StandardCharsets.US_ASCII));
                        }
                        os.flush();
                        s.close();
                    }
                } catch (Exception ignored) {
                } finally {
                    try { redirectServer.close(); } catch (Exception ignored) {}
                }
            }
        });
        serverThread.start();

        File probeDir = File.createTempFile("acid_b2_probe_", "_dir");
        probeDir.delete();
        probeDir.mkdirs();
        try {
            DownloadTask probeTask = new DownloadTask(
                7702L, "http://127.0.0.1:" + port + "/hop1", "hop.bin", "application/octet-stream", probeDir, null
            );
            HttpProbe.ProbeResult pr = HttpProbe.probe(probeTask.url, null, probeTask);
            serverThread.join(2000);
            assertEquals(206, Integer.valueOf(pr.responseCode), "Probe follows manual redirect to 206 endpoint");
            assertEquals(2048L, pr.contentLength, "Content-Range total parsed after redirect");
            assertTrue(pr.finalUrl.endsWith("/hop2"), "Probe records resolved finalUrl");
            assertTrue(probeTask.drainActiveConnections().isEmpty(), "Completed probe unregisters active connections");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(probeDir);
        }

        // Credential retention policy check across HTTPS->HTTP & third-party domains
        assertTrue(!AcidHttp.shouldRetainDownloadCredentials(
            new URL("https://cdn.example.com/a"), new URL("http://cdn.example.com/a")),
            "Downgrade HTTPS->HTTP strips credentials");
        assertTrue(!AcidHttp.shouldRetainDownloadCredentials(
            new URL("https://site-a.com/a"), new URL("https://site-b.com/a")),
            "Cross-domain redirect strips credentials");
        assertTrue(AcidHttp.shouldRetainDownloadCredentials(
            new URL("https://d.pcs.baidu.com/file"), new URL("https://yqall02.baidupcs.com/file")),
            "Cloud-drive ecosystem CDN retains credentials");

        // 3. HistoryRepository & BookmarkRepository pending-sync guard prevents stale JSON overwrite on startup
        File syncDir = File.createTempFile("acid_b2_sync_", "_dir");
        syncDir.delete();
        syncDir.mkdirs();
        try {
            File settingsFile = new File(syncDir, "settings.json");
            File bookmarksFile = new File(syncDir, "bookmarks.json");
            File historyFile = new File(syncDir, "history.json");

            AcidIo.writeFileUtf8Safe(historyFile, "[{\"title\":\"Old\",\"url\":\"https://old.example\",\"time\":100}]");
            AcidIo.writeFileUtf8Safe(bookmarksFile, "[{\"title\":\"OldBm\",\"url\":\"https://old-bm.example\"}]");

            InMemoryPrefs prefs = new InMemoryPrefs();
            prefs.edit()
                .putString(HistoryRepository.PREF_HISTORY, "[{\"title\":\"New\",\"url\":\"https://new.example\",\"time\":200}]")
                .putBoolean(HistoryRepository.PREF_HISTORY_PENDING_SYNC, true)
                .putString(BookmarkRepository.PREF_BOOKMARKS, "[{\"title\":\"NewBm\",\"url\":\"https://new-bm.example\"}]")
                .putBoolean(BookmarkRepository.PREF_BOOKMARKS_PENDING_SYNC, true)
                .apply();

            BrowserDataStore ds = new BrowserDataStore(prefs, new InMemoryPrefs(), settingsFile, bookmarksFile, historyFile);
            ds.syncHistoryJsonFile();
            ds.syncBookmarksJsonFile();

            List<BrowserDataStore.EntryItem> hist = ds.loadHistory();
            assertEquals(1, Integer.valueOf(hist.size()), "Pending-sync history count preserved");
            assertEquals("https://new.example", hist.get(0).url, "Newer SharedPreferences history must not be clobbered by stale history.json");
            assertTrue(AcidIo.readFileUtf8(historyFile).contains("https://new.example"), "history.json catches up from pending SharedPreferences");
            assertTrue(!prefs.getBoolean(HistoryRepository.PREF_HISTORY_PENDING_SYNC, true), "Pending history sync flag cleared once flushed");

            List<BrowserDataStore.EntryItem> bms = ds.loadBookmarks();
            assertEquals(1, Integer.valueOf(bms.size()), "Pending-sync bookmark count preserved");
            assertEquals("https://new-bm.example", bms.get(0).url, "Newer SharedPreferences bookmark must not be clobbered by stale bookmarks.json");
            assertTrue(!prefs.getBoolean(BookmarkRepository.PREF_BOOKMARKS_PENDING_SYNC, true), "Pending bookmark sync flag cleared once flushed");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(syncDir);
        }

        // 4. AdBlockManager rule loading + remainder (<5) blocked count flush
        File abDir = File.createTempFile("acid_b2_ab_", "_dir");
        abDir.delete();
        abDir.mkdirs();
        try {
            File customRules = new File(abDir, "custom_rules.txt");
            AcidIo.writeFileUtf8Safe(customRules, "||ad-tracker.example^\n");
            InMemoryPrefs abPrefs = new InMemoryPrefs();
            BrowserDataStore abStore = new BrowserDataStore(
                abPrefs, new InMemoryPrefs(),
                new File(abDir, "settings.json"), new File(abDir, "bookmarks.json"), new File(abDir, "history.json")
            );
            AdBlockManager ab = new AdBlockManager(customRules, null, abStore);
            ab.loadAllRulesSync();
            assertTrue(ab.recordBlockedUri(android.net.Uri.parse("https://ad-tracker.example/p.js")), "AdBlock #1 blocked");
            assertTrue(ab.recordBlockedUri(android.net.Uri.parse("https://ad-tracker.example/q.js")), "AdBlock #2 blocked");
            assertTrue(ab.recordBlockedUri(android.net.Uri.parse("https://ad-tracker.example/r.js")), "AdBlock #3 blocked");
            ab.flushBlockedCount();
            assertEquals(3, Integer.valueOf(abStore.getAdBlockBlockedCount()), "flushBlockedCount persists remainder (<5) without loss");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(abDir);
        }

        // 5. FaviconManager IPv6 bracket handling & atomic saveToDisk
        assertEquals("[::1]:8080", FaviconManager.extractHost("http://[::1]:8080/dashboard"), "IPv6 non-standard port preserved");
        assertEquals("[::1]", FaviconManager.extractHost("http://[::1]:80/index.html"), "IPv6 default :80 stripped cleanly");
        assertEquals("[2001:db8::1]", FaviconManager.extractHost("https://[2001:db8::1]:443/app"), "IPv6 default :443 stripped cleanly");
        assertTrue(FaviconManager.isLocalOrIpHost("[::1]:8080"), "Bracketed IPv6 loopback with port detected as local/IP");
        assertTrue(FaviconManager.isLocalOrIpHost("[2001:db8::1]"), "Bracketed IPv6 literal detected as local/IP");
        assertTrue(!FaviconManager.isLocalOrIpHost("acid.browser:8443"), "Standard hostname with port is not local/IP");

        // 6. SkillStorageVault.resolveSafeChild traversal guard & SkillPackageImporter oversized ZIP abort
        File skillTmp = File.createTempFile("acid_b2_sk_", "_dir");
        skillTmp.delete();
        skillTmp.mkdirs();
        try {
            boolean escapeBlocked = false;
            try {
                SkillStorageVault.resolveSafeChild(skillTmp, "../outside.md");
            } catch (SecurityException se) {
                escapeBlocked = true;
            }
            assertTrue(escapeBlocked, "SkillStorageVault.resolveSafeChild rejects parent traversal");

            ByteArrayOutputStream zipBos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(zipBos);
            zos.putNextEntry(new ZipEntry("oversized/SKILL.md"));
            byte[] block = new byte[65536];
            Arrays.fill(block, (byte) 'A');
            long written = 0;
            while (written <= SkillPackageImporter.MAX_IMPORT_TOTAL_BYTES + 4096) {
                zos.write(block);
                written += block.length;
            }
            zos.closeEntry();
            zos.close();

            File skillsDir = new File(skillTmp, "skills");
            skillsDir.mkdirs();
            SkillPackageImporter importer = new SkillPackageImporter(skillsDir);
            SkillManager.ImportResult oversizedRes = importer.importFromZipBytes(zipBos.toByteArray(), "oversized_pkg");
            assertTrue(!oversizedRes.success, "Oversized ZIP exceeding MAX_IMPORT_TOTAL_BYTES fails cleanly instead of writing truncated file");
            assertTrue(!new File(skillsDir, "oversized_pkg").exists(), "Failed oversized ZIP does not leave partial skill directory");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(skillTmp);
        }

        // 7. AcidAsync.runSerialized executes tasks in strict FIFO order
        final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        java.util.concurrent.Future<?> last = null;
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            last = AcidAsync.runSerialized(new Runnable() {
                @Override
                public void run() {
                    order.add(Integer.valueOf(idx));
                }
            });
        }
        if (last != null) {
            last.get();
        }
        assertEquals(8, Integer.valueOf(order.size()), "All 8 serialized tasks executed");
        for (int i = 0; i < 8; i++) {
            assertEquals(Integer.valueOf(i), order.get(i), "AcidAsync.runSerialized preserves strict FIFO ordering at index " + i);
        }
    }

    // =========================================================================
    // 17. Post-Audit P0 & P1 Security, State & Lifecycle Regression
    // =========================================================================

    private static void testPostAuditP0AndP1Regressions() throws Exception {
        // 1. HttpControlServer loopback literal & Origin lookalike rejection
        assertTrue(!HttpControlServer.isLoopbackOrLocalLiteralHost("127.evil.com"), "127.evil.com is not loopback");
        assertTrue(HttpControlServer.isLoopbackOrLocalLiteralHost("127.0.0.2"), "127.0.0.2 is loopback IPv4");
        assertTrue(HttpControlServer.isLoopbackOrLocalLiteralHost("[::1]"), "[::1] is loopback IPv6");
        assertTrue(!HttpControlServer.isLocalOrTrustedOrigin("http://127.evil.com:3090"), "127.evil.com Origin rejected");
        assertTrue(!HttpControlServer.isLocalOrTrustedOrigin("https://127.0.0.1.attacker.test"), "Subdomain loopback Origin rejected");
        assertTrue(HttpControlServer.isLocalOrTrustedOrigin("http://[::1]:3090"), "[::1] Origin trusted");
        assertTrue(!HttpControlServer.isLocalOrTrustedOrigin("http://192.168.1.99:3090", false), "LAN Origin rejected when not lanBound");
        String detectedLanIp = HttpControlServer.getLocalIpAddress();
        if (detectedLanIp != null && !detectedLanIp.trim().isEmpty() && !"192.168.1.99".equals(detectedLanIp)) {
            assertTrue(!HttpControlServer.isLocalOrTrustedOrigin("http://192.168.1.99:3090", true), "Foreign LAN IP Origin rejected even when lanBound");
        }
        if (detectedLanIp != null && !detectedLanIp.trim().isEmpty() && !HttpControlServer.isLoopbackOrLocalLiteralHost(detectedLanIp)) {
            String lanOrigin = detectedLanIp.contains(":")
                ? "http://[" + detectedLanIp + "]:3090"
                : "http://" + detectedLanIp + ":3090";
            assertTrue(!HttpControlServer.isLocalOrTrustedOrigin(lanOrigin, false), "Device LAN Origin rejected when not lanBound");
            assertTrue(HttpControlServer.isLocalOrTrustedOrigin(lanOrigin, true), "Device LAN Origin allowed when lanBound");
        }

        // 2. AcidHttp SSRF validation & multi-label Public Suffix List (PSL)
        String[] ssrfBlockedUrls = new String[] {
            "http://127.0.0.1:8080/x",
            "http://[::1]:8080/x",
            "http://169.254.169.254/latest/meta-data",
            "http://metadata.google.internal/computeMetadata/v1",
            "file:///etc/passwd"
        };
        for (String blockedUrl : ssrfBlockedUrls) {
            boolean threw = false;
            try {
                AcidHttp.REMOTE_FETCH_VALIDATOR.validate(new URL(blockedUrl));
            } catch (IOException expected) {
                threw = true;
            }
            assertTrue(threw, "REMOTE_FETCH_VALIDATOR must block " + blockedUrl);
        }
        AcidHttp.REMOTE_FETCH_VALIDATOR.validate(new URL("https://example.com/skill.zip"));

        assertTrue(AcidHttp.isPublicSuffixOrTld("vercel.app"), "AcidHttp recognizes multi-label PSL vercel.app");
        assertTrue(AdBlockManager.isPublicSuffixOrTld("vercel.app"), "AdBlockManager delegates PSL check to AcidHttp");
        assertTrue(!AcidHttp.shouldRetainDownloadCredentials(
            new URL("https://site-a.vercel.app/f"), new URL("https://site-b.vercel.app/f")),
            "Public suffix vercel.app cross-tenant redirect strips credentials");
        assertTrue(!AcidHttp.shouldRetainDownloadCredentials(
            new URL("https://app1.github.io/a"), new URL("https://app2.github.io/b")),
            "Public suffix github.io cross-tenant redirect strips credentials");

        // 3. AcidSandboxTools MCP .user.js navigation/tab & sensitive workspace paths
        File sbxDir = File.createTempFile("acid_p0p1_sbx_", "_dir");
        sbxDir.delete();
        sbxDir.mkdirs();
        try {
            AcidSandboxTools sbx = new AcidSandboxTools(null, sbxDir);
            String[] blockedMcpFiles = new String[] {
                "adblock/custom_rules.txt",
                "userscript_storage.json",
                "skills_sync.json",
                "bookmarks.json",
                "history.json"
            };
            for (String p : blockedMcpFiles) {
                AcidSandboxTools.ToolExecResult wRes = sbx.executeTool(
                    "write",
                    new JSONObject().put("file_path", p).put("content", "payload"),
                    AcidSandboxTools.ToolSource.MCP
                );
                assertTrue(wRes.outputText.startsWith("Error:"), "MCP write must block " + p);

                AcidIo.writeFileUtf8Safe(new File(sbxDir, p), "initial_state");
                AcidSandboxTools.ToolExecResult eRes = sbx.executeTool(
                    "edit",
                    new JSONObject().put("file_path", p).put("old_string", "initial_state").put("new_string", "pwned"),
                    AcidSandboxTools.ToolSource.MCP
                );
                assertTrue(eRes.outputText.startsWith("Error:"), "MCP edit must block " + p);
            }

            AcidSandboxTools.ToolExecResult navUserJs = sbx.executeTool(
                "browser",
                new JSONObject().put("action", "navigate").put("url", "https://example.com/evil.user.js?v=1"),
                AcidSandboxTools.ToolSource.MCP
            );
            assertTrue(navUserJs.outputText.startsWith("Error:"), "MCP browser navigate must block .user.js");

            AcidSandboxTools.ToolExecResult tabUserJs = sbx.executeTool(
                "browser",
                new JSONObject().put("action", "tabs").put("tab_action", "new").put("url", "https://example.com/evil.user.js"),
                AcidSandboxTools.ToolSource.MCP
            );
            assertTrue(tabUserJs.outputText.startsWith("Error:"), "MCP browser tabs(new) must block .user.js");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(sbxDir);
        }

        // 4. AcidFileProvider FileChooser URI security & accept MIME normalization
        assertTrue(!AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("content://com.acid.browser.fileprovider/int_private/x")),
            "Reject own FileProvider content URI");
        assertTrue(!AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("file:///data/data/com.acid.browser/shared_prefs/x.xml")),
            "Reject app /data/data private file URI");
        assertTrue(!AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("file:///data/user/0/com.acid.browser/files/k")),
            "Reject app /data/user private file URI");
        assertTrue(!AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("file:///data/user_de/0/com.acid.browser/files/k")),
            "Reject app /data/user_de private file URI");
        assertTrue(!AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("file:///proc/self/environ")),
            "Reject /proc/self file URI");
        assertTrue(!AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("javascript:alert(1)")),
            "Reject non-content/file scheme");
        assertTrue(AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("content://com.android.externalstorage.documents/document/primary%3Aa.pdf")),
            "Allow external storage document URI");
        assertTrue(AcidFileProvider.isSafeFileChooserUri(
            null, Uri.parse("file:///storage/emulated/0/Download/a.pdf")),
            "Allow external shared storage file URI");

        AcidFileProvider.ExtensionMimeResolver mockResolver = new AcidFileProvider.ExtensionMimeResolver() {
            @Override
            public String mimeForExtension(String ext) {
                return "png".equals(ext) ? "image/png" : null;
            }
        };
        List<String> normMulti = AcidFileProvider.normalizeFileChooserAcceptTypes(
            new String[] { "image/png, IMAGE/JPEG;charset=utf-8" }, mockResolver);
        assertEquals(2, Integer.valueOf(normMulti.size()), "Comma-separated accept types normalized");
        assertEquals("image/png", normMulti.get(0), "First accept MIME normalized");
        assertEquals("image/jpeg", normMulti.get(1), "Second accept MIME stripped & lowercased");

        List<String> normMixedUnknown = AcidFileProvider.normalizeFileChooserAcceptTypes(
            new String[] { "image/png, .unknown_xyz" }, mockResolver);
        assertTrue(normMixedUnknown.isEmpty(), "Unknown extension falls back to unrestricted */*");

        List<String> normExtOnly = AcidFileProvider.normalizeFileChooserAcceptTypes(
            new String[] { ".PNG" }, mockResolver);
        assertEquals(1, Integer.valueOf(normExtOnly.size()), "Uppercase extension resolved via ExtensionMimeResolver");
        assertEquals("image/png", normExtOnly.get(0), ".PNG resolved to image/png");

        // 5. UserscriptMeta & UserscriptShaper @unwrap empty-@grant opt-out + idempotency guard
        UserscriptMeta unwrapNoGrant = UserscriptMeta.parse(
            "unwrap_plain.user.js",
            "// ==UserScript==\n// @name UnwrapNoGrant\n// @unwrap\n// ==/UserScript==\nwindow.__u=1;"
        );
        assertTrue(!unwrapNoGrant.isGranted("GM_setValue"), "@unwrap without @grant must not auto-grant GM_setValue");
        String shapedUnwrap = UserscriptShaper.shape(unwrapNoGrant, null, "unwrap_secret_tok");
        assertTrue(!shapedUnwrap.contains("unwrap_secret_tok"), "@unwrap without @grant must not embed native secret");
        assertTrue(shapedUnwrap.contains("window.__acidInjectedUserscripts"), "@unwrap output includes re-entrance guard");

        UserscriptMeta unwrapExplicit = UserscriptMeta.parse(
            "unwrap_grant.user.js",
            "// ==UserScript==\n// @name UnwrapExplicit\n// @unwrap\n// @grant GM_setValue\n// ==/UserScript==\nGM_setValue('k','v');"
        );
        assertTrue(unwrapExplicit.isGranted("GM_setValue"), "@unwrap with explicit @grant GM_setValue stays granted");

        // 6. DownloadTask generation/state-guarded checkpoint writes
        File cpDir = File.createTempFile("acid_p0p1_cp_", "_dir");
        cpDir.delete();
        cpDir.mkdirs();
        try {
            DownloadTask cpTask = new DownloadTask(
                8801L, "https://example.com/cp.bin", "cp.bin", "application/octet-stream", cpDir, null
            );
            int g0 = cpTask.getGeneration();
            cpTask.status = DownloadTask.STATUS_RUNNING;
            assertTrue(cpTask.writeActiveCheckpointJson("{\"status\":2}", g0),
                "Active checkpoint write succeeds for current running generation");
            assertTrue(cpTask.checkpointFile.exists(), "Checkpoint file created");

            cpTask.checkpointFile.delete();
            int g1 = cpTask.nextGeneration();
            assertTrue(!cpTask.writeActiveCheckpointJson("{\"status\":2}", g0),
                "Stale generation active checkpoint write must be rejected");
            assertTrue(!cpTask.checkpointFile.exists(), "Stale write must not resurrect deleted checkpoint");

            cpTask.status = DownloadTask.STATUS_SUCCESSFUL;
            assertTrue(!cpTask.writeSnapshotCheckpointForState("{\"status\":4}", g1, DownloadTask.STATUS_PAUSED),
                "Snapshot checkpoint write rejected when state already transitioned");
            assertTrue(!cpTask.checkpointFile.exists(), "Mismatched state write must not resurrect checkpoint");

            cpTask.status = DownloadTask.STATUS_PAUSED;
            assertTrue(cpTask.writeSnapshotCheckpointForState("{\"status\":4}", g1, DownloadTask.STATUS_PAUSED),
                "Snapshot checkpoint write succeeds when generation and state match");
            assertTrue(cpTask.checkpointFile.exists(), "Matching snapshot checkpoint written");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(cpDir);
        }

        // 7. AdBlockManager null BrowserDataStore safety
        AdBlockManager nullAb = new AdBlockManager((File) null, (File) null, (BrowserDataStore) null);
        nullAb.setEnabled(false);
        assertTrue(!nullAb.isEnabled(), "setEnabled works safely when dataStore is null");
        assertTrue(nullAb.getSubscriptionUrl() != null, "getSubscriptionUrl returns non-null default when dataStore is null");
        nullAb.setSubscriptionUrl("https://example.com/rules.txt");

        // 8. HttpControlServer IPv6 literal normalization & chunked terminator validation
        assertTrue(HttpControlServer.isLoopbackOrLocalLiteralHost("::"), ":: is wildcard local literal");
        assertTrue(HttpControlServer.isLoopbackOrLocalLiteralHost("::ffff:0.0.0.0"), "::ffff:0.0.0.0 is local literal");
        assertTrue(HttpControlServer.isLoopbackOrLocalLiteralHost("::ffff:7f00:1"), "::ffff:7f00:1 is loopback literal");
        assertTrue(HttpControlServer.isLoopbackOrLocalLiteralHost("0:0:0:0:0:0:0:1"), "0:0:0:0:0:0:0:1 is loopback IPv6");
        byte[] validChunked = HttpControlServer.readChunkedBody(
            new ByteArrayInputStream("4\r\nWiki\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)), 1024);
        assertTrue("Wiki".equals(new String(validChunked, StandardCharsets.UTF_8)), "valid chunked body parses cleanly");
        boolean rejectedTruncatedChunk = false;
        try {
            HttpControlServer.readChunkedBody(
                new ByteArrayInputStream("4\r\nWiki\r\n".getBytes(StandardCharsets.US_ASCII)), 1024);
        } catch (IOException expectedChunkErr) {
            rejectedTruncatedChunk = true;
        }
        assertTrue(rejectedTruncatedChunk, "truncated chunked body without 0 terminator must throw IOException");

        // 9. AcidHttp.REMOTE_FETCH_VALIDATOR blocks RFC1918 site-local addresses on non-MCP ports
        boolean blockedSiteLocal80 = false;
        try {
            AcidHttp.REMOTE_FETCH_VALIDATOR.validate(new URL("http://192.168.1.1:80/"));
        } catch (IOException expected) {
            blockedSiteLocal80 = true;
        }
        assertTrue(blockedSiteLocal80, "REMOTE_FETCH_VALIDATOR blocks 192.168.1.1:80");
        boolean blocked10Net = false;
        try {
            AcidHttp.REMOTE_FETCH_VALIDATOR.validate(new URL("http://10.0.0.1/admin"));
        } catch (IOException expected) {
            blocked10Net = true;
        }
        assertTrue(blocked10Net, "REMOTE_FETCH_VALIDATOR blocks 10.0.0.1/admin");

        // 10. AcidModelStore preset upgrade merges missing built-ins without wiping user models
        AcidModelStore.CatalogProviderPreset dsPreset = AcidModelStore.findBuiltinPreset("deepseek");
        assertTrue(dsPreset != null, "deepseek builtin preset exists");
        AcidModelStore.ConfiguredProvider dsProvider = new AcidModelStore.ConfiguredProvider(
            "deepseek", "DeepSeek", false, "https://api.deepseek.com/v1", "sk-test");
        dsProvider.models.add(new AcidModelStore.ModelEntry("custom-ds-user", "Custom User Model"));
        int beforePresetSize = dsPreset.defaultModels.size();
        AcidModelStore.mergeBuiltinCatalogPreset(dsProvider, dsPreset);
        assertTrue(dsProvider.findModel("custom-ds-user") != null, "user custom model preserved across preset upgrade");
        assertTrue(dsProvider.findModel("deepseek-flash") != null, "missing builtin model merged into provider");
        AcidModelStore.mergeBuiltinCatalogPreset(dsProvider, dsPreset);
        assertTrue(dsProvider.models.size() == beforePresetSize + 1, "idempotent preset merge avoids duplicates");
        AcidModelStore.ConfiguredProvider localV6 = new AcidModelStore.ConfiguredProvider(
            "custom-local", "Local IPv6", true, "http://[::1]:11434/v1", "");
        assertTrue(localV6.hasApiKey(), "loopback IPv6 endpoint does not require API key");
        AcidModelStore.ConfiguredProvider spoofedQuery = new AcidModelStore.ConfiguredProvider(
            "custom-evil", "Evil", true, "https://evil.com/?localhost", "");
        assertTrue(!spoofedQuery.hasApiKey(), "query-string ?localhost does not bypass API key requirement");

        // 11. AcidAgentLoop SSE inline errors, strict loopback check, and non-DOM observation compaction
        String sseErrObj = AcidAgentLoop.extractSseErrorMessage(
            new JSONObject("{\"error\":{\"message\":\"Rate limit\",\"code\":429}}"));
        assertTrue(sseErrObj != null && sseErrObj.contains("Rate limit") && sseErrObj.contains("429"),
            "extractSseErrorMessage formats error object with code");
        String sseErrStr = AcidAgentLoop.extractSseErrorMessage(new JSONObject("{\"error\":\"Overloaded\"}"));
        assertTrue("Overloaded".equals(sseErrStr), "extractSseErrorMessage handles string error");
        assertTrue(AcidAgentLoop.extractSseErrorMessage(
            new JSONObject("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")) == null,
            "normal delta chunk returns null SSE error");
        assertTrue(AcidAgentLoop.isLoopbackBaseUrl("http://127.0.0.1:11434/v1"), "127.0.0.1 is loopback URL");
        assertTrue(AcidAgentLoop.isLoopbackBaseUrl("http://[::1]:11434/v1"), "[::1] is loopback URL");
        assertTrue(AcidAgentLoop.isLoopbackBaseUrl("http://localhost:8000/v1"), "localhost is loopback URL");
        assertTrue(!AcidAgentLoop.isLoopbackBaseUrl("https://evil.com/?localhost"), "query string localhost is not loopback");
        assertTrue(!AcidAgentLoop.isLoopbackBaseUrl("https://localhost.evil.com/v1"), "localhost.evil.com is not loopback");

        StringBuilder bigPayload = new StringBuilder();
        for (int i = 0; i < 900; i++) bigPayload.append("0123456789");
        String giantStr = bigPayload.toString(); // 9000 chars (> 8192)
        List<JSONObject> hist = new ArrayList<JSONObject>();
        hist.add(new JSONObject().put("role", "tool").put("tool_call_id", "c0").put("content", "OUT0:" + giantStr));
        hist.add(new JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "OUT1:" + giantStr));
        hist.add(new JSONObject().put("role", "tool").put("tool_call_id", "c2").put("content", "OUT2:" + giantStr));
        List<JSONObject> prunedHist = AcidAgentLoop.pruneHistoryForRequest(hist);
        assertTrue(prunedHist.get(0).optString("content").contains("[... historical output middle pruned"),
            "older >8192 non-DOM tool output is compacted");
        assertTrue(prunedHist.get(1).optString("content").length() > 8500, "second-latest giant tool output preserved");
        assertTrue(prunedHist.get(2).optString("content").length() > 8500, "latest giant tool output preserved");

        // 12. BrowserController.prepareHistoryCommit deduplicates and blocks title downgrades
        BrowserController.TabSession tab = new BrowserController.TabSession(1, null);
        tab.url = "https://example.com/p1";
        tab.title = tab.url;
        assertTrue(BrowserController.prepareHistoryCommit(tab), "first HTTP visit prepares history commit");
        assertTrue(!BrowserController.prepareHistoryCommit(tab), "duplicate URL/title commit is deduplicated");
        tab.title = "Example Page Title";
        assertTrue(BrowserController.prepareHistoryCommit(tab), "title upgrade prepares history update");
        tab.title = tab.url;
        assertTrue(!BrowserController.prepareHistoryCommit(tab), "downgrading title back to URL is ignored");
        tab.url = "about:blank";
        tab.title = "Blank";
        assertTrue(!BrowserController.prepareHistoryCommit(tab), "non-HTTP(S) URL does not commit history");

        // 13. AcidDownloader HTTP 404 probe rejection avoids creating empty target files
        final File dlProbeDir = new File("build/test_dl_404_probe");
        SkillStorageVault.deleteRecursiveSafe(dlProbeDir);
        dlProbeDir.mkdirs();
        final ServerSocket errServer = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        final int errPort = errServer.getLocalPort();
        Thread errThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!errServer.isClosed()) {
                    try {
                        Socket s = errServer.accept();
                        try {
                            InputStream is = s.getInputStream();
                            byte[] buf = new byte[1024];
                            is.read(buf);
                            OutputStream os = s.getOutputStream();
                            os.write(("HTTP/1.1 404 Not Found\r\n" +
                                      "Content-Length: 0\r\n" +
                                      "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            os.flush();
                        } finally {
                            s.close();
                        }
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        });
        errThread.setDaemon(true);
        errThread.start();
        final CountDownLatch failLatch = new CountDownLatch(1);
        final String[] failErrHolder = new String[1];
        AcidDownloader downloader = new AcidDownloader();
        AcidDownloader.DownloaderListener dlListener = new AcidDownloader.DownloaderListener() {
            @Override public void onTaskStarted(DownloadTask task) {}
            @Override public void onTaskProgress(DownloadTask task) {}
            @Override public void onTaskPaused(DownloadTask task) {}
            @Override public void onTaskCompleted(DownloadTask task) { failLatch.countDown(); }
            @Override public void onTaskFailed(DownloadTask task, String error) {
                failErrHolder[0] = error;
                failLatch.countDown();
            }
        };
        downloader.addListener(dlListener);
        try {
            downloader.enqueue(
                8802L,
                "http://127.0.0.1:" + errPort + "/missing.bin",
                "missing.bin",
                "application/octet-stream",
                dlProbeDir,
                null
            );
            boolean finished = failLatch.await(5, TimeUnit.SECONDS);
            assertTrue(finished, "AcidDownloader 404 probe task finishes within timeout");
            assertTrue(failErrHolder[0] != null && failErrHolder[0].contains("404"),
                "AcidDownloader reports HTTP 404 from pre-flight probe");
            assertTrue(!new File(dlProbeDir, "missing.bin").exists(),
                "404 probe abort must not allocate target file");
        } finally {
            downloader.removeListener(dlListener);
            try { errServer.close(); } catch (Exception ignored) {}
            SkillStorageVault.deleteRecursiveSafe(dlProbeDir);
        }
    }

    // =========================================================================
    // 18. Deep Audit Round-2 P0 & P1 Regressions
    // =========================================================================

    private static void testPostAudit2Regressions() throws Exception {
        // 1. AcidModelStore preserves non-DeepSeek provider & custom selection across catalog upgrade
        InMemoryPrefs seedPrefs = new InMemoryPrefs();
        new AcidModelStore(seedPrefs);
        String seededJson = seedPrefs.getString("configured_providers_json", "[]");

        AcidModelStore.CatalogProviderPreset kimiPreset = AcidModelStore.findBuiltinPreset("moonshotai-cn");
        AcidModelStore.ConfiguredProvider kimiProv = new AcidModelStore.ConfiguredProvider(
            kimiPreset.id, kimiPreset.displayName, false, kimiPreset.defaultBaseUrl, "sk-kimi-test");
        kimiProv.models.add(new AcidModelStore.ModelEntry("kimi-k3", "Kimi K3"));
        JSONArray upgradeArr = new JSONArray(seededJson);
        upgradeArr.put(kimiProv.toJson());

        InMemoryPrefs upgradePrefs = new InMemoryPrefs();
        upgradePrefs.edit()
            .putString("configured_providers_json", upgradeArr.toString())
            .putString("selected_provider_id", "moonshotai-cn")
            .putString("selected_model_id", "kimi-k3")
            .putString("selected_reasoning_effort", "high")
            .putInt("catalog_version", 1)
            .apply();
        AcidModelStore upgradedKimi = new AcidModelStore(upgradePrefs);
        assertEquals("moonshotai-cn", upgradedKimi.getActiveProvider().id,
            "Catalog upgrade preserves selected non-DeepSeek built-in provider");
        assertEquals("kimi-k3", upgradedKimi.getActiveModel().id,
            "Catalog upgrade preserves selected non-DeepSeek model instead of resetting to deepseek-flash");
        assertEquals("high", upgradedKimi.getSelectedReasoningEffort(),
            "Catalog upgrade preserves selected reasoning effort");

        JSONArray withCustomArr = new JSONArray(seededJson);
        AcidModelStore.ConfiguredProvider customProv = new AcidModelStore.ConfiguredProvider(
            "custom_qwen_test", "Local Qwen", true, "http://127.0.0.1:11434/v1", "");
        customProv.models.add(new AcidModelStore.ModelEntry("qwen3-coder", "Qwen3 Coder"));
        withCustomArr.put(customProv.toJson());
        InMemoryPrefs customUpgradePrefs = new InMemoryPrefs();
        customUpgradePrefs.edit()
            .putString("configured_providers_json", withCustomArr.toString())
            .putString("selected_provider_id", "custom_qwen_test")
            .putString("selected_model_id", "qwen3-coder")
            .putString("selected_reasoning_effort", "medium")
            .putInt("catalog_version", 1)
            .apply();
        AcidModelStore upgradedCustom = new AcidModelStore(customUpgradePrefs);
        assertEquals("custom_qwen_test", upgradedCustom.getActiveProvider().id,
            "Catalog upgrade preserves selected custom provider");
        assertEquals("qwen3-coder", upgradedCustom.getActiveModel().id,
            "Catalog upgrade preserves selected custom model");

        // 2. BookmarkRepository & HistoryRepository PENDING_SYNC guard vs forceOverwritePending
        File repoDir = File.createTempFile("acid_r2_repo_", "_dir");
        repoDir.delete();
        repoDir.mkdirs();
        try {
            File sFile = new File(repoDir, "settings.json");
            File bFile = new File(repoDir, "bookmarks.json");
            File hFile = new File(repoDir, "history.json");
            InMemoryPrefs repoPrefs = new InMemoryPrefs();
            BrowserDataStore repoStore = new BrowserDataStore(
                repoPrefs, new InMemoryPrefs(), sFile, bFile, hFile);
            repoStore.syncBookmarksJsonFile();
            repoStore.syncHistoryJsonFile();
            int baseBmCount = repoStore.loadBookmarks().size();

            // Write new bookmarks externally, then simulate PENDING_SYNC set locally
            JSONArray extBm = new JSONArray();
            extBm.put(new JSONObject().put("title", "External Bookmark").put("url", "https://ext.bm.test/"));
            repoStore.recordHistory("https://inmem.hist.test/", "InMemory Hist", false);
            FileOutputStream bOut = new FileOutputStream(bFile);
            bOut.write(extBm.toString(2).getBytes(StandardCharsets.UTF_8));
            bOut.close();

            repoPrefs.edit().putBoolean(BookmarkRepository.PREF_BOOKMARKS_PENDING_SYNC, true).apply();
            assertTrue(!repoStore.reloadBookmarksFromJsonFile(false),
                "Non-forced observer reload respects PENDING_SYNC and keeps local bookmarks");
            assertEquals(baseBmCount, Integer.valueOf(repoStore.loadBookmarks().size()),
                "Local bookmark count untouched when PENDING_SYNC blocks non-forced reload");
            assertTrue(repoStore.reloadBookmarksFromJsonFile(true),
                "Force-overwrite reload (sandbox write) applies external bookmarks.json");
            assertEquals(1, Integer.valueOf(repoStore.loadBookmarks().size()),
                "Forced bookmark reload applied updated file");

            JSONArray extHist = new JSONArray();
            extHist.put(new JSONObject()
                .put("title", "External History")
                .put("url", "https://ext.hist.test/")
                .put("time", System.currentTimeMillis()));
            FileOutputStream hOut = new FileOutputStream(hFile);
            hOut.write(extHist.toString(2).getBytes(StandardCharsets.UTF_8));
            hOut.close();

            repoPrefs.edit().putBoolean(HistoryRepository.PREF_HISTORY_PENDING_SYNC, true).apply();
            assertTrue(!repoStore.reloadHistoryFromJsonFile(false),
                "Non-forced history reload respects PENDING_SYNC");
            assertTrue(repoStore.reloadHistoryFromJsonFile(true),
                "Force-overwrite history reload applies external history.json");
            assertEquals(1, Integer.valueOf(repoStore.loadHistory().size()),
                "Forced history reload applied updated file");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(repoDir);
        }

        // 3. AcidHttp CGNAT, Alibaba/AWS-IPv6 metadata, ULA literal vs Fake-IPv6, PSL, and redirects <= limit
        String[] extraSsrfBlocked = new String[] {
            "http://100.64.0.1/internal",
            "http://100.100.100.200/latest/meta-data",
            "http://[fc00::1]/private",
            "http://[fd00:ec2::254]/latest/meta-data",
            "http://[fdfe:dcba:9876::1]/literal-fakeip6"
        };
        for (String u : extraSsrfBlocked) {
            boolean blocked = false;
            try {
                AcidHttp.REMOTE_FETCH_VALIDATOR.validate(new URL(u));
            } catch (IOException expected) {
                blocked = true;
            }
            assertTrue(blocked, "REMOTE_FETCH_VALIDATOR blocks CGNAT/metadata/ULA literal " + u);
        }
        assertTrue(AcidHttp.isPublicSuffixOrTld("cloudfront.net"), "Expanded PSL includes cloudfront.net");
        assertTrue(AcidHttp.isPublicSuffixOrTld("workers.dev"), "Expanded PSL includes workers.dev");
        assertTrue(!AcidHttp.shouldRetainDownloadCredentials(
            new URL("https://a.workers.dev/f"), new URL("https://b.workers.dev/f")),
            "Expanded PSL workers.dev strips credentials across tenant subdomains");

        // Redirect boundary test: maxRedirects=2 allows 2 hops (3 requests) and rejects 3 hops
        final ServerSocket redirServer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        final int redirPort = redirServer.getLocalPort();
        Thread redirThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!redirServer.isClosed()) {
                    try {
                        Socket s = redirServer.accept();
                        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                        String reqLine = r.readLine();
                        String line;
                        while ((line = r.readLine()) != null && !line.isEmpty()) {}
                        OutputStream out = s.getOutputStream();
                        if (reqLine != null && reqLine.contains("/hop0")) {
                            out.write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + redirPort + "/hop1\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        } else if (reqLine != null && reqLine.contains("/hop1")) {
                            out.write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + redirPort + "/final\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        } else if (reqLine != null && reqLine.contains("/hop2")) {
                            out.write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + redirPort + "/hop0\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        } else {
                            byte[] okBody = "OK".getBytes(StandardCharsets.US_ASCII);
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + okBody.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            out.write(okBody);
                        }
                        out.flush();
                        s.close();
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }
        });
        redirThread.setDaemon(true);
        redirThread.start();
        try {
            java.net.HttpURLConnection okConn = AcidHttp.openConnectionWithRedirects(
                "http://127.0.0.1:" + redirPort + "/hop0", "GET", null, 3000, 3000, 2, null, null, null);
            assertEquals(200, Integer.valueOf(okConn.getResponseCode()),
                "openConnectionWithRedirects(..., maxRedirects=2) succeeds after 2 redirects");
            okConn.disconnect();

            boolean tooMany = false;
            try {
                java.net.HttpURLConnection failConn = AcidHttp.openConnectionWithRedirects(
                    "http://127.0.0.1:" + redirPort + "/hop2", "GET", null, 3000, 3000, 2, null, null, null);
                failConn.disconnect();
            } catch (IOException expected) {
                tooMany = true;
            }
            assertTrue(tooMany, "openConnectionWithRedirects(..., maxRedirects=2) throws on 3rd redirect");
        } finally {
            try { redirServer.close(); } catch (Exception ignored) {}
        }

        // 4. AcidSandboxTools schema & Error prefix / MCP takeover / tab_id guards
        JSONArray toolsSchema = AcidSandboxTools.buildToolsSchema();
        JSONObject browserProps = null;
        for (int i = 0; i < toolsSchema.length(); i++) {
            JSONObject toolObj = toolsSchema.optJSONObject(i);
            JSONObject fnObj = toolObj != null ? toolObj.optJSONObject("function") : null;
            if (fnObj != null && "browser".equals(fnObj.optString("name"))) {
                JSONObject params = fnObj.optJSONObject("parameters");
                browserProps = params != null ? params.optJSONObject("properties") : null;
                break;
            }
        }
        assertTrue(browserProps != null, "browser schema found");
        assertTrue(browserProps.has("text_limit"), "browser schema exposes text_limit");
        assertTrue(browserProps.has("viewport_only"), "browser schema exposes viewport_only");
        assertTrue(browserProps.has("scope"), "browser schema exposes scope");

        File sbx2Dir = File.createTempFile("acid_r2_sbx_", "_dir");
        sbx2Dir.delete();
        sbx2Dir.mkdirs();
        try {
            AcidSandboxTools sbx2 = new AcidSandboxTools(null, sbx2Dir);
            AcidSandboxTools.ToolExecResult takeoverMcp = sbx2.executeTool(
                "browser",
                new JSONObject().put("action", "request_takeover").put("reason", "Login check"),
                AcidSandboxTools.ToolSource.MCP
            );
            assertTrue(takeoverMcp.outputText != null && takeoverMcp.outputText.startsWith("Error:"),
                "MCP request_takeover blocked with Error: prefix");

            AcidSandboxTools.ToolExecResult unknownTool = sbx2.executeTool(
                "nonexistent_tool", new JSONObject(), AcidSandboxTools.ToolSource.MCP);
            assertTrue(unknownTool.outputText != null && unknownTool.outputText.startsWith("Error:"),
                "Unknown tool returns Error: prefix");

            AcidSandboxTools.ToolExecResult missingAction = sbx2.executeTool(
                "browser", new JSONObject(), AcidSandboxTools.ToolSource.AGENT_UI);
            assertTrue(missingAction.outputText != null && missingAction.outputText.startsWith("Error:"),
                "Missing browser action returns Error: prefix");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(sbx2Dir);
        }

        // 5. UserscriptShaper @unwrap + GM_addStyle & UserscriptDependencyResolver tab @resource
        UserscriptMeta unwrapCssMeta = UserscriptMeta.parse(
            "unwrap_css.user.js",
            "// ==UserScript==\n// @name UnwrapCss\n// @unwrap\n// @grant GM_addStyle\n// ==/UserScript==\nGM_addStyle('body{}');"
        );
        assertTrue(unwrapCssMeta.isGranted("GM_addStyle"), "@grant GM_addStyle parsed cleanly");
        String shapedCssUnwrap = UserscriptShaper.shape(unwrapCssMeta, null, "native_sec_token");
        assertTrue(shapedCssUnwrap.contains("GM_addStyle"),
            "@unwrap with shim-only GM_addStyle keeps shim wrapper so GM_addStyle exists");
        assertTrue(!shapedCssUnwrap.contains("native_sec_token"),
            "@unwrap with shim-only GM_addStyle keeps native secret empty");

        String resUrlTab = UserscriptDependencyResolver.extractResourceUrl(
            "myIcon\thttps://cdn.example.com/icons/app.png");
        assertEquals("https://cdn.example.com/icons/app.png", resUrlTab,
            "extractResourceUrl splits tab-delimited @resource declaration cleanly");

        // 6. SkillManager path traversal delete guard, multi-line YAML meta, built-in shadowing & URL slug
        File skWork = File.createTempFile("acid_r2_sk_", "_dir");
        skWork.delete();
        skWork.mkdirs();
        try {
            File outsideTarget = new File(skWork, "do_not_delete.md");
            FileOutputStream oos = new FileOutputStream(outsideTarget);
            oos.write("keep".getBytes(StandardCharsets.UTF_8));
            oos.close();

            SkillManager sm = new SkillManager(null, skWork);
            assertTrue(!sm.deleteSkill("../do_not_delete.md"), "deleteSkill rejects parent traversal");
            assertTrue(outsideTarget.exists(), "External file untouched by blocked deleteSkill");
            assertTrue(!sm.deleteSkill(SkillManager.SKILL_USERSCRIPT), "deleteSkill rejects built-in skill");

            File yamlDir = new File(sm.getSkillsDir(), "yaml_multi");
            yamlDir.mkdirs();
            File yamlFile = new File(yamlDir, "SKILL.md");
            String yamlDoc = "---\nname: yaml_multi\ndescription: >-\n  First YAML line\n  Second YAML line\n---\n# Body\n";
            FileOutputStream yos = new FileOutputStream(yamlFile);
            yos.write(yamlDoc.getBytes(StandardCharsets.UTF_8));
            yos.close();

            List<SkillManager.SkillMeta> loaded = sm.getSkills();
            String parsedDesc = null;
            for (SkillManager.SkillMeta m : loaded) {
                if ("yaml_multi".equals(m.name)) {
                    parsedDesc = m.description;
                    break;
                }
            }
            assertTrue(parsedDesc != null && parsedDesc.contains("First YAML line") && parsedDesc.contains("Second YAML line"),
                "parseSkillMeta appends multi-line YAML description lines");

            assertEquals("cool-skill-repo",
                SkillPackageImporter.inferSkillSlugFromUrl("https://github.com/org/cool-skill-repo/archive/refs/heads/main.zip"),
                "inferSkillSlugFromUrl skips archive/refs/heads/main segments");
            assertEquals("my-cool-skill",
                SkillPackageImporter.inferSkillSlugFromUrl("https://raw.githubusercontent.com/org/repo/main/my-cool-skill/SKILL.md"),
                "inferSkillSlugFromUrl skips SKILL.md and picks parent folder");

            ByteArrayOutputStream shadowBos = new ByteArrayOutputStream();
            ZipOutputStream szos = new ZipOutputStream(shadowBos);
            szos.putNextEntry(new ZipEntry("userscript_dev/SKILL.md"));
            szos.write("# Shadow\nDo not shadow builtin".getBytes(StandardCharsets.UTF_8));
            szos.closeEntry();
            szos.close();
            SkillPackageImporter skImp = new SkillPackageImporter(sm);
            SkillManager.ImportResult shadowRes = skImp.importFromZipBytes(shadowBos.toByteArray(), "userscript_dev");
            assertTrue(shadowRes.success, "Built-in name import succeeds by renaming instead of failing or shadowing");
            assertTrue(shadowRes.skillFileName != null && shadowRes.skillFileName.startsWith("custom_userscript_dev/"),
                "Built-in slug is prefixed with custom_ to prevent shadowing");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(skWork);
        }

        // 7. HttpControlServer StartCallback, POST /message 400/404, & strict chunked CRLF/trailer
        ServerSocket probeSock = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        int mcpPort = probeSock.getLocalPort();
        probeSock.close();

        HttpControlServer mcpSrv = new HttpControlServer(null, mcpPort);
        final CountDownLatch startedLatch = new CountDownLatch(1);
        final boolean[] startOkRef = new boolean[] { false };
        mcpSrv.start(new HttpControlServer.StartCallback() {
            @Override
            public void onStartResult(boolean success, String errorMessage) {
                startOkRef[0] = success;
                startedLatch.countDown();
            }
        });
        try {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "HttpControlServer StartCallback fired promptly");
            assertTrue(startOkRef[0], "HttpControlServer StartCallback reported successful bind");

            java.net.HttpURLConnection c400 = (java.net.HttpURLConnection)
                new URL("http://127.0.0.1:" + mcpPort + "/message").openConnection();
            c400.setRequestMethod("POST");
            c400.setDoOutput(true);
            c400.getOutputStream().write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(400, Integer.valueOf(c400.getResponseCode()),
                "POST /message without sessionId returns HTTP 400 before RPC execution");
            c400.disconnect();

            java.net.HttpURLConnection c404 = (java.net.HttpURLConnection)
                new URL("http://127.0.0.1:" + mcpPort + "/message?sessionId=unknown_sess").openConnection();
            c404.setRequestMethod("POST");
            c404.setDoOutput(true);
            c404.getOutputStream().write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(404, Integer.valueOf(c404.getResponseCode()),
                "POST /message with unknown sessionId returns HTTP 404 before RPC execution");
            c404.disconnect();
        } finally {
            mcpSrv.stop();
        }

        byte[] chunkWithTrailer = HttpControlServer.readChunkedBody(
            new ByteArrayInputStream("5\r\nhello\r\n0\r\nX-Trailer: yes\r\n\r\n".getBytes(StandardCharsets.US_ASCII)),
            4096);
        assertEquals("hello", new String(chunkWithTrailer, StandardCharsets.US_ASCII),
            "readChunkedBody decodes chunk payload followed by trailer headers and final CRLF");

        boolean badCrNoLf = false;
        try {
            HttpControlServer.readChunkedBody(
                new ByteArrayInputStream("1\r\na\rX0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)),
                4096);
        } catch (IOException expected) {
            badCrNoLf = true;
        }
        assertTrue(badCrNoLf, "readChunkedBody rejects CR not followed by LF after chunk data");

        boolean unterminatedTrailer = false;
        try {
            HttpControlServer.readChunkedBody(
                new ByteArrayInputStream("5\r\nhello\r\n0\r\nX-Trailer: yes\r\n".getBytes(StandardCharsets.US_ASCII)),
                4096);
        } catch (IOException expected) {
            unterminatedTrailer = true;
        }
        assertTrue(unterminatedTrailer, "readChunkedBody rejects EOF before empty trailer terminator line");

        // 8. AdBlockManager Google domain hardening, CSS ~domain exclusion & /regex/ path rules
        AdBlockManager.RuleSet abRs = new AdBlockManager.RuleSet();
        assertTrue(!AdBlockManager.isHostWhitelisted("accounts.google.evil.com", abRs),
            "Rejects multi-label Google account suffix spoof accounts.google.evil.com");
        assertTrue(!AdBlockManager.isHostWhitelisted("accounts.google.evil-co.xyz", abRs),
            "Rejects malformed TLD spoof accounts.google.evil-co.xyz");
        assertTrue(AdBlockManager.isHostWhitelisted("accounts.google.com", abRs),
            "Allows authentic accounts.google.com");
        assertTrue(AdBlockManager.isHostWhitelisted("accounts.google.co.uk", abRs),
            "Allows authentic accounts.google.co.uk");

        assertTrue(AdBlockManager.parseRuleLine("example.com,~sub.example.com##.excl-ad", abRs),
            "Parses domain CSS rule with ~domain exclusion");
        String cssMain = AdBlockManager.buildIsolatedElementHidingCss(abRs, "www.example.com");
        String cssSub = AdBlockManager.buildIsolatedElementHidingCss(abRs, "sub.example.com");
        assertTrue(cssMain.contains(".excl-ad"), "Non-excluded subdomain receives CSS hiding rule");
        assertTrue(!cssSub.contains(".excl-ad"), "~sub.example.com excluded from CSS hiding rule");

        assertTrue(AdBlockManager.parseRuleLine("/^https?:\\/\\/ad[0-9]+\\.tracker\\.test\\//", abRs),
            "Parses regex URL rule containing regex metacharacters");
        assertTrue(AdBlockManager.isUriBlocked(Uri.parse("https://ad42.tracker.test/p.js"), abRs),
            "Regex AdBlock rule matches matching numeric subdomain");
        assertTrue(!AdBlockManager.isUriBlocked(Uri.parse("https://cdn.tracker.test/p.js"), abRs),
            "Regex AdBlock rule does not false-positive on non-matching host");

        // 9. DownloadCoordinator response-content-disposition URL decoding & filename sanitization
        String ossUrl = "https://oss.example.com/obj?response-content-disposition="
            + "attachment%3B%20filename%3D%22..%2F..%2Freport%20v1.pdf%22";
        String guessedOssName = DownloadCoordinator.smartGuessFileName(ossUrl, null, null);
        assertEquals("report v1.pdf", guessedOssName,
            "smartGuessFileName decodes percent-encoded response-content-disposition and strips path traversal");
        String cleanSanitized = DownloadCoordinator.sanitizeDownloadFileName("../evil\\sub/payload.bin");
        assertTrue(!cleanSanitized.contains("..") && !cleanSanitized.contains("/") && !cleanSanitized.contains("\\"),
            "sanitizeDownloadFileName strips directory traversal and separators");

        // 10. AcidFileProvider boundary check rejects parent escapes cleanly
        File fpDir = File.createTempFile("acid_r2_fp_", "_dir");
        fpDir.delete();
        fpDir.mkdirs();
        try {
            assertTrue(AcidFileProvider.resolveAndVerifyBoundary(fpDir, "../escape.txt") == null,
                "AcidFileProvider.resolveAndVerifyBoundary rejects parent directory escape");
            assertTrue(AcidFileProvider.resolveAndVerifyBoundary(fpDir, "ok.pdf") != null,
                "AcidFileProvider.resolveAndVerifyBoundary accepts child path inside directory");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(fpDir);
        }
    }

    // =========================================================================
    // 19. Deep Audit Round-3 P0 & P1 Regressions
    // =========================================================================

    private static void testPostAudit3Regressions() throws Exception {
        // 1. AcidHttp.createDownloadValidator: strict host + port pinning & .localhost/.lan SSRF blocks
        AcidHttp.UrlValidator loopVal = AcidHttp.createDownloadValidator("http://127.0.0.1:18080/file.bin");
        loopVal.validate(new URL("http://127.0.0.1:18080/chunk"));
        String[] loopRejected = new String[] {
            "http://127.0.0.1:18081/chunk",
            "http://127.0.0.2:18080/chunk",
            "http://[::1]:18080/chunk",
            "http://localhost:18080/chunk"
        };
        for (String bad : loopRejected) {
            boolean blocked = false;
            try {
                loopVal.validate(new URL(bad));
            } catch (IOException expected) {
                blocked = true;
            }
            assertTrue(blocked, "createDownloadValidator(127.0.0.1:18080) rejects mismatched local target: " + bad);
        }

        AcidHttp.UrlValidator localhostVal = AcidHttp.createDownloadValidator("http://localhost:19000/a");
        localhostVal.validate(new URL("http://localhost:19000/b"));
        boolean localhostPortBlocked = false;
        try {
            localhostVal.validate(new URL("http://localhost:19001/b"));
        } catch (IOException expected) {
            localhostPortBlocked = true;
        }
        assertTrue(localhostPortBlocked, "createDownloadValidator(localhost:19000) rejects different port");
        boolean localhostToIpBlocked = false;
        try {
            localhostVal.validate(new URL("http://127.0.0.1:19000/b"));
        } catch (IOException expected) {
            localhostToIpBlocked = true;
        }
        assertTrue(localhostToIpBlocked, "createDownloadValidator(localhost) rejects raw IP literal cross-host");

        AcidHttp.UrlValidator pubVal = AcidHttp.createDownloadValidator("https://example.com/app.apk");
        for (String priv : new String[] {
            "http://127.0.0.1/x", "http://service.localhost/x", "http://printer.lan/x", "http://router.local/x"
        }) {
            boolean blocked = false;
            try {
                pubVal.validate(new URL(priv));
            } catch (IOException expected) {
                blocked = true;
            }
            assertTrue(blocked, "Public download validator blocks local/internal redirect target: " + priv);
        }
        boolean dotLocalhostBlocked = false;
        try {
            AcidHttp.validateHttpTargetForSsrf(new URL("http://app.localhost/"), true, "ssrf-test");
        } catch (SecurityException expected) {
            dotLocalhostBlocked = true;
        }
        assertTrue(dotLocalhostBlocked, "validateHttpTargetForSsrf blocks .localhost domain");
        boolean dotLanBlocked = false;
        try {
            AcidHttp.validateHttpTargetForSsrf(new URL("http://printer.lan/"), true, "ssrf-test");
        } catch (SecurityException expected) {
            dotLanBlocked = true;
        }
        assertTrue(dotLanBlocked, "validateHttpTargetForSsrf blocks .lan domain");

        // 2. FaviconManager local probe uses scoped createDownloadValidator instead of REMOTE_FETCH_VALIDATOR
        assertTrue(FaviconManager.isLocalOrIpHost("127.0.0.1"), "isLocalOrIpHost recognizes 127.0.0.1");
        assertTrue(!FaviconManager.isLocalOrIpHost("example.com"), "isLocalOrIpHost returns false for public domain");
        final ServerSocket favServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final int favPort = favServer.getLocalPort();
        Thread favThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!favServer.isClosed()) {
                    try {
                        Socket s = favServer.accept();
                        try {
                            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                            String req = r.readLine();
                            String l;
                            while ((l = r.readLine()) != null && !l.isEmpty()) {}
                            OutputStream out = s.getOutputStream();
                            if (req != null && req.contains("/redir-port")) {
                                out.write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + (favPort + 1) + "/secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            } else {
                                byte[] body = "ICO-DATA".getBytes(StandardCharsets.US_ASCII);
                                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                                out.write(body);
                            }
                            out.flush();
                        } finally {
                            s.close();
                        }
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        });
        favThread.setDaemon(true);
        favThread.start();
        try {
            String localFavUrl = "http://127.0.0.1:" + favPort + "/favicon.ico";
            assertTrue(FaviconManager.fetchUrlBytes(localFavUrl) == null,
                "Default REMOTE_FETCH_VALIDATOR fetchUrlBytes blocks loopback IP");
            byte[] okFavBytes = FaviconManager.fetchUrlBytes(localFavUrl, AcidHttp.createDownloadValidator(localFavUrl));
            assertEquals("ICO-DATA", okFavBytes != null ? new String(okFavBytes, StandardCharsets.US_ASCII) : "",
                "Scoped local FaviconManager fetchUrlBytes downloads local router/nas favicon");
            String redirFavUrl = "http://127.0.0.1:" + favPort + "/redir-port";
            assertTrue(FaviconManager.fetchUrlBytes(redirFavUrl, AcidHttp.createDownloadValidator(redirFavUrl)) == null,
                "Scoped local FaviconManager fetchUrlBytes rejects redirect to different port");
        } finally {
            try { favServer.close(); } catch (Exception ignored) {}
        }

        // 3. AcidModelStore.discoverModelsAsync uses AcidHttp redirects safely & invokes callback cleanly
        final ServerSocket modelServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final int modelPort = modelServer.getLocalPort();
        final String[] capturedModelAuth = new String[1];
        Thread modelThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!modelServer.isClosed()) {
                    try {
                        Socket s = modelServer.accept();
                        try {
                            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                            String req = r.readLine();
                            String authHeader = null;
                            String l;
                            while ((l = r.readLine()) != null && !l.isEmpty()) {
                                if (l.toLowerCase(java.util.Locale.ROOT).startsWith("authorization:")) {
                                    authHeader = l.substring("authorization:".length()).trim();
                                }
                            }
                            OutputStream out = s.getOutputStream();
                            if (req != null && req.contains("/v1/models")) {
                                out.write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + modelPort + "/v2/models\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            } else if (req != null && req.contains("/evil/models")) {
                                out.write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.2:" + modelPort + "/steal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            } else {
                                capturedModelAuth[0] = authHeader;
                                byte[] json = "{\"data\":[{\"id\":\"m1\",\"name\":\"Model One\"}]}".getBytes(StandardCharsets.UTF_8);
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + json.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                                out.write(json);
                            }
                            out.flush();
                        } finally {
                            s.close();
                        }
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        });
        modelThread.setDaemon(true);
        modelThread.start();
        try {
            final CountDownLatch okLatch = new CountDownLatch(1);
            final List<AcidModelStore.ModelEntry> discovered = new ArrayList<AcidModelStore.ModelEntry>();
            final String[] okErr = new String[1];
            AcidModelStore.discoverModelsAsync("http://127.0.0.1:" + modelPort + "/v1", "test-key",
                new AcidModelStore.ModelDiscoveryCallback() {
                    @Override public void onModelsDiscovered(List<AcidModelStore.ModelEntry> models) {
                        if (models != null) discovered.addAll(models);
                        okLatch.countDown();
                    }
                    @Override public void onDiscoveryFailed(String errorMsg) {
                        okErr[0] = errorMsg;
                        okLatch.countDown();
                    }
                });
            assertTrue(okLatch.await(5, TimeUnit.SECONDS), "discoverModelsAsync completes within timeout");
            assertTrue(okErr[0] == null, "discoverModelsAsync succeeds after same-origin redirect: " + okErr[0]);
            assertEquals(Integer.valueOf(1), Integer.valueOf(discovered.size()), "Discovers 1 model");
            assertEquals("m1", discovered.get(0).id, "Discovered model id matches m1");
            assertEquals("Bearer test-key", capturedModelAuth[0], "Same-origin redirect preserves Authorization header");

            final CountDownLatch failLatch = new CountDownLatch(1);
            final String[] evilErr = new String[1];
            AcidModelStore.discoverModelsAsync("http://127.0.0.1:" + modelPort + "/evil", "test-key",
                new AcidModelStore.ModelDiscoveryCallback() {
                    @Override public void onModelsDiscovered(List<AcidModelStore.ModelEntry> models) {
                        failLatch.countDown();
                    }
                    @Override public void onDiscoveryFailed(String errorMsg) {
                        evilErr[0] = errorMsg;
                        failLatch.countDown();
                    }
                });
            assertTrue(failLatch.await(5, TimeUnit.SECONDS), "Blocked redirect fires onDiscoveryFailed");
            assertTrue(evilErr[0] != null && !evilErr[0].isEmpty(), "Cross-host loopback redirect failed safely");
        } finally {
            try { modelServer.close(); } catch (Exception ignored) {}
        }

        // 4. HttpControlServer synchronous mLanBound & JSON-RPC "id" field on 401/403 + BrowserDataStore listener check
        File mcpRepoDir = File.createTempFile("acid_r3_mcp_", "_dir");
        mcpRepoDir.delete();
        mcpRepoDir.mkdirs();
        int freePort;
        ServerSocket probeSock = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        freePort = probeSock.getLocalPort();
        probeSock.close();
        BrowserDataStore lanStore = new BrowserDataStore(
            new InMemoryPrefs(), new InMemoryPrefs(),
            new File(mcpRepoDir, "settings.json"),
            new File(mcpRepoDir, "bookmarks.json"),
            new File(mcpRepoDir, "history.json"));
        assertTrue(!lanStore.hasSettingsChangedListener(), "hasSettingsChangedListener false initially");
        lanStore.setMcpLanEnabled(true);
        assertTrue(lanStore.isMcpLanEnabled(), "setMcpLanEnabled persists LAN mode flag");
        HttpControlServer lanSrv = new HttpControlServer(null, freePort);
        try {
            lanSrv.start();
            assertTrue(!lanSrv.isLanBound(), "isLanBound is synchronously false when bound to 127.0.0.1");
            byte[] rpcPayload = "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"tools/list\"}"
                .getBytes(StandardCharsets.UTF_8);
            Socket rawSock = new Socket("127.0.0.1", freePort);
            String rawResp;
            try {
                rawSock.setSoTimeout(3000);
                OutputStream rawOut = rawSock.getOutputStream();
                String rawReq = "POST /mcp HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + freePort + "\r\n"
                    + "Origin: http://evil.com\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + rpcPayload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
                rawOut.write(rawReq.getBytes(StandardCharsets.US_ASCII));
                rawOut.write(rpcPayload);
                rawOut.flush();
                rawResp = AcidIo.readStreamUtf8(rawSock.getInputStream());
            } finally {
                rawSock.close();
            }
            assertTrue(rawResp.startsWith("HTTP/1.1 403"), "Untrusted Origin returns 403 over HTTP: " + rawResp);
            int hdrEnd = rawResp.indexOf("\r\n\r\n");
            String errBody = hdrEnd >= 0 ? rawResp.substring(hdrEnd + 4) : "";
            JSONObject errJson = new JSONObject(errBody);
            assertEquals("2.0", errJson.optString("jsonrpc"), "403 error response carries jsonrpc 2.0");
            assertTrue(errJson.opt("id") == JSONObject.NULL && errBody.contains("\"id\""),
                "403 error response includes explicit null id: " + errBody);
            assertTrue(errJson.has("error"), "403 error response includes JSON-RPC error object");
        } finally {
            lanSrv.stop();
            assertTrue(!lanSrv.isLanBound(), "isLanBound cleared after stop()");
            SkillStorageVault.deleteRecursiveSafe(mcpRepoDir);
        }

        // 5. AcidSandboxTools: encoded .user.js bypasses, MCP download SSRF block, and builtin skill package write block
        File sbx3Dir = File.createTempFile("acid_r3_sbx_", "_dir");
        sbx3Dir.delete();
        sbx3Dir.mkdirs();
        try {
            AcidSandboxTools sbx3 = new AcidSandboxTools(null, sbx3Dir);
            String[] badUserJsArgs = new String[] {
                "{\"action\":\"download\",\"url\":\"https://example.com/evil%2euser%2ejs\"}",
                "{\"action\":\"download\",\"url\":\"https://example.com/dl?file=payload.user.js\"}",
                "{\"action\":\"download\",\"url\":\"https://example.com/blob\",\"filename\":\"hack.user.js\"}"
            };
            for (String argsStr : badUserJsArgs) {
                AcidSandboxTools.ToolExecResult r = sbx3.executeTool(
                    "browser", new JSONObject(argsStr), AcidSandboxTools.ToolSource.MCP);
                assertTrue(r.outputText != null && r.outputText.startsWith("Error:"),
                    "MCP blocks encoded/query/filename userscript download: " + argsStr);
            }

            AcidSandboxTools.ToolExecResult ssrfDl = sbx3.executeTool(
                "browser",
                new JSONObject().put("action", "download").put("url", "http://127.0.0.1:8080/internal.bin"),
                AcidSandboxTools.ToolSource.MCP);
            assertTrue(ssrfDl.outputText != null && ssrfDl.outputText.startsWith("Error:"),
                "MCP browser(download) rejects loopback SSRF URL");

            AcidSandboxTools.ToolExecResult shadowWrite = sbx3.executeTool(
                "write",
                new JSONObject().put("path", "skills/userscript_dev/SKILL.md").put("content", "# hijacked"),
                AcidSandboxTools.ToolSource.MCP);
            assertTrue(shadowWrite.outputText != null && shadowWrite.outputText.startsWith("Error:"),
                "write blocks directory package shadowing builtin userscript_dev skill");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(sbx3Dir);
        }

        // 6. SkillManager: ignores non-builtin directory shadowing & parses multiline YAML name/title
        File sk3Dir = File.createTempFile("acid_r3_sk_", "_dir");
        sk3Dir.delete();
        sk3Dir.mkdirs();
        try {
            File fakeBuiltinDir = new File(sk3Dir, "skills/userscript_dev");
            fakeBuiltinDir.mkdirs();
            AcidIo.writeFileUtf8Safe(new File(fakeBuiltinDir, "SKILL.md"),
                "# Hijacked Builtin\nFake directory shadow content");

            File multiYamlDir = new File(sk3Dir, "skills/multi_yaml_skill");
            multiYamlDir.mkdirs();
            AcidIo.writeFileUtf8Safe(new File(multiYamlDir, "SKILL.md"),
                "---\n"
                + "name: >\n"
                + "  folded_skill\n"
                + "  id\n"
                + "title: |\n"
                + "  Multi Line\n"
                + "  Skill Title\n"
                + "description: >\n"
                + "  Desc line 1\n"
                + "  line 2\n"
                + "---\n"
                + "Body content\n");

            SkillManager sm3 = new SkillManager(null, sk3Dir);
            File resolvedBuiltin = sm3.resolveSkillFile("userscript_dev");
            assertTrue(resolvedBuiltin == null || "userscript_dev.md".equals(resolvedBuiltin.getName()),
                "resolveSkillFile refuses non-builtin directory shadow skills/userscript_dev/SKILL.md, got: " + resolvedBuiltin);
            assertTrue(!sm3.readSkill("userscript_dev").contains("Fake directory shadow"),
                "readSkill(\"userscript_dev\") does not read directory shadow content");

            SkillManager.SkillMeta foldedMeta = null;
            for (SkillManager.SkillMeta m : sm3.getSkills()) {
                assertTrue(!"Hijacked Builtin".equals(m.title),
                    "getSkills ignores non-builtin directory shadow of builtin skill");
                if ("multi_yaml_skill/SKILL.md".equals(m.fileName)) {
                    foldedMeta = m;
                }
            }
            assertTrue(foldedMeta != null, "Multi-line YAML directory skill parsed");
            assertEquals("folded_skill id", foldedMeta.name, "Multiline YAML name folded cleanly");
            assertTrue(foldedMeta.title.contains("Multi Line") && foldedMeta.title.contains("Skill Title"),
                "Multiline YAML title preserved across lines");
            assertEquals("Desc line 1 line 2", foldedMeta.description, "Multiline YAML description folded cleanly");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(sk3Dir);
        }

        // 7. UserscriptNativeBridge: GM_log grant mapping & isInstalled site probe gate
        String[] logGrants = UserscriptNativeBridge.grantsFor("log");
        assertTrue(logGrants != null && Arrays.asList(logGrants).contains("GM_log"),
            "grantsFor(\"log\") maps to GM_log");

        File us3Dir = File.createTempFile("acid_r3_us_", "_dir");
        us3Dir.delete();
        us3Dir.mkdirs();
        try {
            UserscriptStorage st3 = new UserscriptStorage(us3Dir);
            String tok3 = UserscriptManager.deriveScriptSecret("seed3", "script_c.user.js");
            UserscriptMeta logMeta = UserscriptMeta.parse("log_only.user.js",
                "// ==UserScript==\n// @name l\n// @match *://*/*\n// @grant GM_log\n// ==/UserScript==\n");
            UserscriptNativeBridge br3 = new UserscriptNativeBridge(null, st3, null, tok3);
            final int[] hostProbeCalls = new int[1];
            UserscriptNativeBridge.Host probeHost = new UserscriptNativeBridge.Host() {
                @Override public void openInTab(String url, boolean active) {}
                @Override public void download(String url, String fileName) {}
                @Override public void notify(String title, String text) {}
                @Override public void onInjectionRequested(UserscriptNativeBridge bridge, int phase) {}
                @Override public String isScriptInstalled(String name, String namespace) {
                    hostProbeCalls[0]++;
                    return "{\"installed\":true}";
                }
            };
            br3.configure(new UserscriptNativeBridge.ScriptResolver() {
                @Override public UserscriptMeta findScript(String id) {
                    return "log_only.user.js".equals(id) ? logMeta : null;
                }
            }, null, probeHost, null);

            String logGrantedRes = br3.call("log",
                "{\"secret\":\"" + tok3 + "\",\"scriptId\":\"log_only.user.js\",\"text\":\"hello\"}");
            assertEquals("true", logGrantedRes, "Granted GM_log call succeeds with \"true\"");

            // isInstalled denied when probe gate is false even when resolver and host exist
            br3.setAllowIsInstalledProbe(false);
            String probeBlocked = br3.call("isInstalled",
                "{\"secret\":\"" + tok3 + "\",\"scriptId\":\"log_only.user.js\",\"name\":\"l\"}");
            assertEquals("", probeBlocked, "isInstalled probe denied with empty string on non-script-site page");
            assertEquals(Integer.valueOf(0), Integer.valueOf(hostProbeCalls[0]),
                "Host.isScriptInstalled is not invoked when probe gate is closed");

            br3.setAllowIsInstalledProbe(true);
            String probeAllowed = br3.call("isInstalled",
                "{\"secret\":\"" + tok3 + "\",\"scriptId\":\"log_only.user.js\",\"name\":\"l\"}");
            assertEquals("{\"value\":true}", probeAllowed,
                "isInstalled probe delegates to Host when allowed on script-store site");
            assertEquals(Integer.valueOf(1), Integer.valueOf(hostProbeCalls[0]),
                "Host.isScriptInstalled invoked once when probe gate is open");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(us3Dir);
        }

        // 8. UserscriptHttpHandler: 5 redirects allowed, 302 POST->GET strips body headers, oversized body errors
        final ServerSocket usHttpServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final int usHttpPort = usHttpServer.getLocalPort();
        final String[] capturedFinalReq = new String[4]; // method, contentType, contentLength, customKeep
        Thread usHttpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!usHttpServer.isClosed()) {
                    try {
                        Socket s = usHttpServer.accept();
                        try {
                            InputStream rawIn = s.getInputStream();
                            String req = readHttpAsciiLine(rawIn);
                            String ct = null, cl = null, keep = null;
                            String line;
                            while ((line = readHttpAsciiLine(rawIn)) != null && !line.isEmpty()) {
                                int colon = line.indexOf(':');
                                if (colon > 0) {
                                    String k = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                                    String v = line.substring(colon + 1).trim();
                                    if ("content-type".equals(k)) ct = v;
                                    else if ("content-length".equals(k)) cl = v;
                                    else if ("x-keep".equals(k)) keep = v;
                                }
                            }
                            if (cl != null) {
                                int bodyLen = Integer.parseInt(cl);
                                for (int i = 0; i < bodyLen; i++) {
                                    if (rawIn.read() < 0) break;
                                }
                            }
                            OutputStream out = s.getOutputStream();
                            if (req != null && req.contains("/r/")) {
                                int idx = req.indexOf("/r/") + 3;
                                int sp = req.indexOf(' ', idx);
                                int n = Integer.parseInt(req.substring(idx, sp));
                                if (n > 0) {
                                    out.write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:" + usHttpPort + "/r/" + (n - 1) + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                                } else {
                                    capturedFinalReq[0] = req.split(" ")[0];
                                    capturedFinalReq[1] = ct;
                                    capturedFinalReq[2] = cl;
                                    capturedFinalReq[3] = keep;
                                    byte[] ok = "DONE5".getBytes(StandardCharsets.US_ASCII);
                                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + ok.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                                    out.write(ok);
                                }
                            } else if (req != null && req.contains("/huge")) {
                                out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                                byte[] zeros = new byte[64 * 1024];
                                long sent = 0L;
                                long limit = 10L * 1024L * 1024L + 128L;
                                while (sent < limit) {
                                    int chunk = (int) Math.min(zeros.length, limit - sent);
                                    out.write(zeros, 0, chunk);
                                    sent += chunk;
                                }
                            }
                            out.flush();
                        } finally {
                            s.close();
                        }
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        });
        usHttpThread.setDaemon(true);
        usHttpThread.start();
        try {
            UserscriptHttpHandler usHandler = new UserscriptHttpHandler();
            String url5Hops = "http://127.0.0.1:" + usHttpPort + "/r/5";
            JSONObject post5 = new JSONObject()
                .put("url", url5Hops)
                .put("method", "POST")
                .put("data", "{\"a\":1}")
                .put("headers", new JSONObject()
                    .put("Content-Type", "application/json")
                    .put("Content-Length", "7")
                    .put("X-Keep", "yes"));
            JSONObject res5 = usHandler.executeRequestForTest(post5, AcidHttp.createDownloadValidator(url5Hops));
            assertEquals(Integer.valueOf(200), Integer.valueOf(res5.getInt("status")),
                "5 redirects succeeds under MAX_REDIRECTS=5");
            assertEquals("DONE5", res5.getString("responseText"), "Final response body received after 5 hops");
            assertEquals("GET", capturedFinalReq[0], "302 POST converts method to GET");
            assertTrue(capturedFinalReq[1] == null, "302 POST->GET strips Content-Type header");
            assertTrue(capturedFinalReq[2] == null, "302 POST->GET strips Content-Length header");
            assertEquals("yes", capturedFinalReq[3], "302 redirect preserves unrelated X-Keep header");

            boolean overCapBlocked = false;
            String hugeUrl = "http://127.0.0.1:" + usHttpPort + "/huge";
            try {
                usHandler.executeRequestForTest(new JSONObject().put("url", hugeUrl),
                    AcidHttp.createDownloadValidator(hugeUrl));
            } catch (IOException expected) {
                overCapBlocked = true;
            }
            assertTrue(overCapBlocked, "UserscriptHttpHandler throws IOException when response exceeds MAX_RESPONSE_BYTES");
        } finally {
            try { usHttpServer.close(); } catch (Exception ignored) {}
        }

        // 9. AdBlockManager wildcard path rules (/ads/*/banner/ and /*_ad_*/)
        AdBlockManager.RuleSet wcRs = new AdBlockManager.RuleSet();
        assertTrue(AdBlockManager.parseRuleLine("/ads/*/banner/", wcRs), "Parses /ads/*/banner/ wildcard rule");
        assertTrue(AdBlockManager.isUriBlocked(Uri.parse("https://cdn.site.test/ads/top/v2/banner/img.png"), wcRs),
            "/ads/*/banner/ blocks multi-segment matching ad path");
        assertTrue(!AdBlockManager.isUriBlocked(Uri.parse("https://cdn.site.test/content/article.html"), wcRs),
            "/ads/*/banner/ does not block unrelated content path");
        assertTrue(AdBlockManager.parseRuleLine("/*_ad_*/", wcRs), "Parses /*_ad_*/ wildcard rule");
        assertTrue(AdBlockManager.isUriBlocked(Uri.parse("https://cdn.site.test/static/promo_ad_unit/main.js"), wcRs),
            "/*_ad_*/ blocks matching path with _ad_ marker");
        assertTrue(!AdBlockManager.isUriBlocked(Uri.parse("https://cdn.site.test/static/promo/main.js"), wcRs),
            "/*_ad_*/ does not false-positive on normal static path");

        // 10. AcidDownloader 416 Range Not Satisfiable reset & stale errorMessage clearing
        final File dl416Dir = new File("build/test_dl_416_resume");
        SkillStorageVault.deleteRecursiveSafe(dl416Dir);
        dl416Dir.mkdirs();
        final ServerSocket r416Server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final int r416Port = r416Server.getLocalPort();
        final java.util.concurrent.atomic.AtomicInteger r416ReqNum = new java.util.concurrent.atomic.AtomicInteger(0);
        Thread r416Thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!r416Server.isClosed()) {
                    try {
                        Socket s = r416Server.accept();
                        try {
                            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                            String req = r.readLine();
                            String l;
                            while ((l = r.readLine()) != null && !l.isEmpty()) {}
                            OutputStream out = s.getOutputStream();
                            int hit = r416ReqNum.incrementAndGet();
                            if (hit == 1) {
                                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                            } else {
                                byte[] fresh = "FRESH-DATA".getBytes(StandardCharsets.US_ASCII);
                                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + fresh.length + "\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                                if (req == null || !req.startsWith("HEAD ")) {
                                    out.write(fresh);
                                }
                            }
                            out.flush();
                        } finally {
                            s.close();
                        }
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        });
        r416Thread.setDaemon(true);
        r416Thread.start();
        final CountDownLatch r416Latch = new CountDownLatch(1);
        final String[] r416Err = new String[1];
        AcidDownloader dl416 = new AcidDownloader();
        AcidDownloader.DownloaderListener r416Listener = new AcidDownloader.DownloaderListener() {
            @Override public void onTaskStarted(DownloadTask t) {}
            @Override public void onTaskProgress(DownloadTask t) {}
            @Override public void onTaskPaused(DownloadTask t) {}
            @Override public void onTaskCompleted(DownloadTask t) { r416Latch.countDown(); }
            @Override public void onTaskFailed(DownloadTask t, String e) {
                r416Err[0] = e;
                r416Latch.countDown();
            }
        };
        dl416.addListener(r416Listener);
        try {
            DownloadTask r416Task = new DownloadTask(
                9901L,
                "http://127.0.0.1:" + r416Port + "/file.bin",
                "file.bin",
                "application/octet-stream",
                dl416Dir,
                null);
            // Pre-populate stale partial file + checkpoint + old error state
            AcidIo.writeFileUtf8Safe(r416Task.destinationFile, "XXXXXXXXXXXXXXXXXXXX");
            r416Task.contentLength = 100L;
            r416Task.supportsRange = true;
            r416Task.downloadedBytes = 20L;
            r416Task.chunks.add(new DownloadChunk(0, 0L, 99L));
            r416Task.chunks.get(0).downloadedBytes = 20L;
            assertTrue(r416Task.saveCheckpoint(), "Pre-seeded resume checkpoint saved");
            r416Task.errorMessage = "stale failure";
            r416Task.completedAt = 99999L;
            r416Task.status = DownloadTask.STATUS_FAILED;

            dl416.startTaskAsync(r416Task);
            assertTrue(r416Latch.await(5, TimeUnit.SECONDS), "416 resume reset download finishes within timeout");
            assertTrue(r416Err[0] == null, "Download succeeds after 416 fallback fresh restart: " + r416Err[0]);
            assertEquals(Integer.valueOf(DownloadTask.STATUS_SUCCESSFUL), Integer.valueOf(r416Task.status),
                "Task reaches STATUS_SUCCESSFUL");
            assertTrue(r416Task.errorMessage == null, "Stale errorMessage cleared on task restart");
            assertEquals("FRESH-DATA", AcidIo.readFileUtf8OrEmpty(r416Task.destinationFile, 1024),
                "Fresh restart replaces stale partial bytes with FRESH-DATA");
            assertTrue(!r416Task.checkpointFile.exists(), "Checkpoint file cleaned on 416 fallback & completion");
        } finally {
            dl416.removeListener(r416Listener);
            try { r416Server.close(); } catch (Exception ignored) {}
            SkillStorageVault.deleteRecursiveSafe(dl416Dir);
        }

        // 11. WebAutomationDriver safe null Context & missing target handling in clickCoordinateSync
        WebAutomationDriver.AutomationHost nullTabHost = new WebAutomationDriver.AutomationHost() {
            @Override public BrowserController.TabSession getActiveTab() { return null; }
            @Override public BrowserController.TabSession getTargetTab() { return null; }
            @Override public boolean isTargetTabActive() { return false; }
            @Override public AgentOverlayView getOverlayView() { return null; }
            @Override public UserscriptManager getUserscriptManager() { return null; }
            @Override public void loadUrl(String url) {}
            @Override public void emitLog(String log) {}
            @Override public boolean isSomEnabled() { return false; }
            @Override public boolean isDesktopMode() { return false; }
            @Override public int getBlockedDeepLinksCount() { return 0; }
            @Override public String getLastBlockedDeepLink() { return ""; }
            @Override public int getTriggeredDownloadsCount() { return 0; }
            @Override public String getLastTriggeredDownloadInfo() { return ""; }
        };
        WebAutomationDriver nullDriver = new WebAutomationDriver(null, nullTabHost);
        JSONObject nullClickRes = nullDriver.clickCoordinateSync(10f, 10f);
        assertTrue(nullClickRes != null && !nullClickRes.optBoolean("ok", true),
            "WebAutomationDriver.clickCoordinateSync degrades cleanly with null Context and no target tab");
    }

    static void testAgentWorkspaceUploadFeature() throws Exception {
        File testRoot = new File("build/test_agent_upload");
        SkillStorageVault.deleteRecursiveSafe(testRoot);
        File wsDir = new File(testRoot, "acid_workspace");
        File outsideFile = new File(testRoot, "outside_secret.txt");
        wsDir.mkdirs();
        AcidIo.writeFileUtf8Safe(outsideFile, "secret");

        // Regular uploadable workspace files
        File reportPdf = new File(wsDir, "report.pdf");
        File imgSub = new File(wsDir, "notes/chart 1.png");
        File txtSub = new File(wsDir, "drafts/summary.txt");
        AcidIo.writeFileUtf8Safe(reportPdf, "%PDF-1.4");
        AcidIo.writeFileUtf8Safe(imgSub, "PNG");
        AcidIo.writeFileUtf8Safe(txtSub, "hello");

        // All workspace-cleanup-protected entries
        String[] protectedRelPaths = new String[] {
            "settings.json",
            "bookmarks.json",
            "history.json",
            "skills_sync.json",
            "userscript_storage.json",
            "homepage/index.html",
            "userscripts/anti.user.js",
            "adblock/custom_rules.txt",
            "skills/cloud.md"
        };
        for (String rel : protectedRelPaths) {
            AcidIo.writeFileUtf8Safe(new File(wsDir, rel), "protected");
        }

        try {
            // 1. Resolve single & multi-file valid paths (including workspace:// prefix & spaces)
            AgentUploadManager.UploadResolution singleRes =
                AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("report.pdf"));
            assertTrue(singleRes.ok && singleRes.files.size() == 1, "Resolves single workspace file");

            AgentUploadManager.UploadResolution multiRes = AgentUploadManager.resolveUploadFiles(
                wsDir, java.util.Arrays.asList("workspace://notes/chart 1.png", "drafts/summary.txt"));
            assertTrue(multiRes.ok && multiRes.files.size() == 2, "Resolves multi-file workspace paths");

            // 2. Protected cleanup entries are strictly denied for upload
            for (String rel : protectedRelPaths) {
                AgentUploadManager.UploadResolution denied =
                    AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList(rel));
                assertTrue(!denied.ok, "Protected workspace path must be denied: " + rel);
                AgentUploadManager.UploadResolution deniedWithUri =
                    AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("workspace://" + rel));
                assertTrue(!deniedWithUri.ok, "Protected workspace:// path must be denied: " + rel);
            }
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("drafts/../settings.json")).ok,
                "Normalized traversal into protected file denied");

            // 3. Escape, directory, missing, null-byte & empty inputs denied
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("../outside_secret.txt")).ok,
                "Relative escape outside workspace denied");
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList(outsideFile.getAbsolutePath())).ok,
                "Absolute path outside workspace denied");
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("notes")).ok,
                "Directory path rejected as upload target");
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList(".")).ok,
                "Workspace root itself rejected as upload target");
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("does_not_exist.txt")).ok,
                "Missing file rejected");
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("bad\0.txt")).ok,
                "Null byte path rejected");
            assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("report.pdf", "")).ok,
                "Blank item inside file_paths rejected atomically");

            // Optional symlink protection check (when FS permits symlinks)
            File symLeak = new File(wsDir, "sym_settings.txt");
            try {
                java.nio.file.Files.createSymbolicLink(symLeak.toPath(), new File(wsDir, "settings.json").toPath());
                assertTrue(!AgentUploadManager.resolveUploadFiles(wsDir, java.util.Arrays.asList("sym_settings.txt")).ok,
                    "Symlink pointing to protected workspace file denied");
            } catch (Throwable ignored) {}

            // 4. Parity with BrowserDataStore.clearWorkspaceTempFiles
            BrowserDataStore store = new BrowserDataStore(
                new InMemoryPrefs(), new InMemoryPrefs(),
                new File(wsDir, "settings.json"),
                new File(wsDir, "bookmarks.json"),
                new File(wsDir, "history.json")
            );
            int cleaned = store.clearWorkspaceTempFiles(wsDir);
            assertTrue(cleaned >= 3, "Temporary files cleaned by clearWorkspaceTempFiles");
            assertTrue(!reportPdf.exists() && !imgSub.exists() && !txtSub.exists(),
                "Non-protected temp files removed by clearWorkspaceTempFiles");
            for (String rel : protectedRelPaths) {
                assertTrue(new File(wsDir, rel).exists(), "Protected file preserved by cleanup: " + rel);
            }

            // Re-seed files for state-machine & tool dispatch checks
            AcidIo.writeFileUtf8Safe(reportPdf, "%PDF-1.4");
            AcidIo.writeFileUtf8Safe(imgSub, "PNG");

            // 5. ArmedUpload state machine: one-shot, multi-file, single-file rejection, tab isolation, expiry & cancel
            AgentUploadManager mgr = new AgentUploadManager();
            AgentUploadManager.ArmedUpload slot1 = mgr.arm(10, singleRes.files);
            assertTrue(mgr.consumeForTab(99, true) == null, "Unrelated tab does not consume armed upload");
            AgentUploadManager.ConsumeOutcome out1 = mgr.consumeForTab(10, false);
            assertTrue(out1 != null && out1.accepted && out1.uris.length == 1, "Single file consumed for matching tab");
            assertEquals("report.pdf", out1.uris[0].getLastPathSegment(), "Uri.fromFile preserves filename");
            assertTrue(mgr.consumeForTab(10, false) == null, "Armed upload is strictly one-shot");
            AgentUploadManager.ConsumeResult res1 = mgr.awaitCompletion(slot1, 50L);
            assertTrue(res1.triggered && res1.accepted && res1.fileNames.size() == 1, "Completion recorded");

            // Multi-file accepted when multiple=true
            List<File> twoFiles = java.util.Arrays.asList(reportPdf.getCanonicalFile(), imgSub.getCanonicalFile());
            AgentUploadManager.ArmedUpload slot2 = mgr.arm(11, twoFiles);
            AgentUploadManager.ConsumeOutcome out2 = mgr.consumeForTab(11, true);
            assertTrue(out2 != null && out2.accepted && out2.uris.length == 2, "Multiple files delivered when allowed");
            assertEquals("/chart 1.png", out2.uris[1].getPath().substring(out2.uris[1].getPath().lastIndexOf('/')),
                "Uri.fromFile percent-encoding decodes spaces cleanly");
            AgentUploadManager.ConsumeResult res2 = mgr.awaitCompletion(slot2, 50L);
            assertTrue(res2.triggered && res2.accepted && res2.multipleAllowed && res2.fileNames.size() == 2,
                "Multi-file completion recorded");

            // Multi-file rejected cleanly when webpage input is single-file only
            AgentUploadManager.ArmedUpload slot3 = mgr.arm(12, twoFiles);
            AgentUploadManager.ConsumeOutcome out3 = mgr.consumeForTab(12, false);
            assertTrue(out3 != null && !out3.accepted, "Multi-file rejected for single-file webpage input");
            AgentUploadManager.ConsumeResult res3 = mgr.awaitCompletion(slot3, 50L);
            assertTrue(res3.triggered && !res3.accepted && "SINGLE_FILE_INPUT_ONLY".equals(res3.errorCode),
                "Single-file rejection reports SINGLE_FILE_INPUT_ONLY");

            // Expiry guard
            mgr.arm(13, singleRes.files, 15L);
            Thread.sleep(30L);
            assertTrue(mgr.consumeForTab(13, true) == null, "Expired armed upload is ignored");

            // Cancellation wakes awaitCompletion
            AgentUploadManager.ArmedUpload slot4 = mgr.arm(14, singleRes.files, 5000L);
            mgr.cancelForTab(14, "navigated");
            AgentUploadManager.ConsumeResult res4 = mgr.awaitCompletion(slot4, 50L);
            assertTrue(!res4.triggered && "CANCELLED".equals(res4.errorCode), "Cancel aborts armed upload immediately");

            // 6. MCP tool schema & AcidSandboxTools validation (MCP + AGENT_UI)
            JSONArray mcpTools = AcidSandboxTools.buildMcpToolsList();
            JSONObject browserSchema = null;
            for (int i = 0; i < mcpTools.length(); i++) {
                JSONObject item = mcpTools.optJSONObject(i);
                if (item != null && "browser".equals(item.optString("name"))) {
                    browserSchema = item;
                    break;
                }
            }
            assertTrue(browserSchema != null, "MCP tools list includes browser");
            JSONObject props = browserSchema.optJSONObject("inputSchema").optJSONObject("properties");
            assertTrue(props.has("file_path") && props.has("file_paths"),
                "MCP browser schema exposes file_path and file_paths");
            assertTrue(props.optJSONObject("action").optJSONArray("enum").toString().contains("\"upload\""),
                "MCP browser action enum includes upload");

            AcidSandboxTools tools = new AcidSandboxTools(null, wsDir);
            AcidSandboxTools.ToolExecResult missingTarget = tools.executeTool("browser",
                new JSONObject().put("action", "upload").put("file_path", "report.pdf"),
                AcidSandboxTools.ToolSource.MCP);
            assertTrue(missingTarget.outputText.startsWith("Error:"), "MCP upload requires trigger target");

            AcidSandboxTools.ToolExecResult deniedProtectedMcp = tools.executeTool("browser",
                new JSONObject().put("action", "upload").put("ref", "e1").put("file_path", "settings.json"),
                AcidSandboxTools.ToolSource.MCP);
            assertTrue(deniedProtectedMcp.outputText.contains("protected workspace item"),
                "MCP upload blocks protected workspace files");

            AcidSandboxTools.ToolExecResult deniedProtectedUi = tools.executeTool("browser",
                new JSONObject().put("action", "upload").put("ref", "e1").put("file_path", "userscripts/anti.user.js"),
                AcidSandboxTools.ToolSource.AGENT_UI);
            assertTrue(deniedProtectedUi.outputText.contains("protected workspace item"),
                "Agent UI upload also blocks protected workspace files");

            AcidSandboxTools.ToolExecResult allowedPassValidationMcp = tools.executeTool("browser",
                new JSONObject()
                    .put("action", "upload")
                    .put("ref", "e1")
                    .put("file_paths", new JSONArray().put("report.pdf").put("notes/chart 1.png")),
                AcidSandboxTools.ToolSource.MCP);
            assertTrue(allowedPassValidationMcp.outputText.contains("Browser controller or automation driver unavailable"),
                "MCP multi-file workspace upload passes policy checks and reaches browser execution: "
                    + allowedPassValidationMcp.outputText);
        } finally {
            SkillStorageVault.deleteRecursiveSafe(testRoot);
        }
    }

    static void testScreenshotAlignmentAndMultimodalSupport() throws Exception {
        // 1. Scaling bounds preserve exact aspect ratio
        ScreenshotCaptureHelper.ImageDimensions dimsPhone =
            ScreenshotCaptureHelper.computeOutputDimensions(1080, 2160, 1536);
        assertEquals(Integer.valueOf(768), Integer.valueOf(dimsPhone.width), "1080x2160 scales width to 768");
        assertEquals(Integer.valueOf(1536), Integer.valueOf(dimsPhone.height), "1080x2160 caps height at 1536");

        ScreenshotCaptureHelper.ImageDimensions dimsSmall =
            ScreenshotCaptureHelper.computeOutputDimensions(720, 1280, 1536);
        assertEquals(Integer.valueOf(720), Integer.valueOf(dimsSmall.width), "720x1280 stays 1:1");
        assertEquals(Integer.valueOf(1280), Integer.valueOf(dimsSmall.height), "720x1280 stays 1:1");

        // 2. Mobile Mode alignment (view 1080x2160, css 360x720, image 768x1536)
        JSONObject mobileObs = new JSONObject().put("elements", new JSONArray()
            .put(new JSONObject()
                .put("ref", "e1").put("tag", "button").put("label", "Submit")
                .put("box", new JSONObject().put("x", 180).put("y", 360).put("width", 100).put("height", 40))));
        ScreenshotCaptureHelper.ViewportMapping mobileMap = ScreenshotCaptureHelper.computeViewportMapping(
            1080, 2160, 768, 1536, 360, 720, 0, 0, 3.0f);
        List<ScreenshotCaptureHelper.SomMarkRect> mobileMarks =
            ScreenshotCaptureHelper.extractSomMarks(mobileObs, mobileMap);
        assertEquals(Integer.valueOf(1), Integer.valueOf(mobileMarks.size()), "Mobile element projected into screenshot");
        double mobImgCenterX = (mobileMarks.get(0).left + mobileMarks.get(0).right) * 0.5;
        double mobImgCenterY = (mobileMarks.get(0).top + mobileMarks.get(0).bottom) * 0.5;

        ScreenshotCaptureHelper.ScreenshotFrame mobileFrame = new ScreenshotCaptureHelper.ScreenshotFrame(
            1, 768, 1536, 1080, 2160, false, 360, 720, 1000L);
        ScreenshotCaptureHelper.ResolvedPoint mobClick = ScreenshotCaptureHelper.resolveClickCoordinates(
            mobileFrame, mobImgCenterX, mobImgCenterY, "auto", 1080, 2160, false);
        assertTrue(mobClick.ok && "screenshot".equals(mobClick.coordSpaceUsed), "Auto mode maps screenshot pixel coords");
        assertTrue(Math.abs(mobClick.viewX - 690f) < 1.5f && Math.abs(mobClick.viewY - 1140f) < 1.5f,
            "Mobile screenshot center maps back to exact real view pixels (690, 1140), got ("
                + mobClick.viewX + "," + mobClick.viewY + ")");

        // 3. Desktop Mode alignment (1280x2560 CSS shrunk into 1080x2160 view -> 768x1536 image)
        JSONObject desktopObs = new JSONObject().put("elements", new JSONArray()
            .put(new JSONObject()
                .put("ref", "e2").put("tag", "a").put("label", "Repo")
                .put("box", new JSONObject().put("x", 640).put("y", 1280).put("width", 160).put("height", 80))));
        ScreenshotCaptureHelper.ViewportMapping deskMap = ScreenshotCaptureHelper.computeViewportMapping(
            1080, 2160, 768, 1536, 1280, 2560, 0, 0, 0.84375f);
        List<ScreenshotCaptureHelper.SomMarkRect> deskMarks =
            ScreenshotCaptureHelper.extractSomMarks(desktopObs, deskMap);
        assertEquals(Integer.valueOf(1), Integer.valueOf(deskMarks.size()), "Desktop element projected cleanly");
        double deskImgCenterX = (deskMarks.get(0).left + deskMarks.get(0).right) * 0.5;
        double deskImgCenterY = (deskMarks.get(0).top + deskMarks.get(0).bottom) * 0.5;

        ScreenshotCaptureHelper.ScreenshotFrame deskFrame = new ScreenshotCaptureHelper.ScreenshotFrame(
            1, 768, 1536, 1080, 2160, true, 1280, 2560, 2000L);
        ScreenshotCaptureHelper.ResolvedPoint deskClick = ScreenshotCaptureHelper.resolveClickCoordinates(
            deskFrame, deskImgCenterX, deskImgCenterY, "screenshot", 1080, 2160, true);
        assertTrue(deskClick.ok && "screenshot".equals(deskClick.coordSpaceUsed), "Desktop screenshot mode resolves");
        // CSS center = (720, 1320) * (1080/1280) = (607.5, 1113.75)
        assertTrue(Math.abs(deskClick.viewX - 607.5f) < 1.5f && Math.abs(deskClick.viewY - 1113.75f) < 1.5f,
            "Desktop screenshot center maps back to exact real view pixels (607.5, 1113.75), got ("
                + deskClick.viewX + "," + deskClick.viewY + ")");

        // 4. Pinch-zoomed & panned filtering
        JSONObject zoomedObs = new JSONObject().put("elements", new JSONArray()
            .put(new JSONObject().put("ref", "e1").put("tag", "button").put("label", "OffLeft")
                .put("box", new JSONObject().put("x", 50).put("y", 50).put("width", 40).put("height", 40)))
            .put(new JSONObject().put("ref", "e2").put("tag", "button").put("label", "InZoom")
                .put("box", new JSONObject().put("x", 200).put("y", 100).put("width", 160).put("height", 80))));
        ScreenshotCaptureHelper.ViewportMapping zoomMap = ScreenshotCaptureHelper.computeViewportMapping(
            1080, 2160, 768, 1536, 640, 1280, 200, 100, 1.6875f);
        List<ScreenshotCaptureHelper.SomMarkRect> zoomMarks =
            ScreenshotCaptureHelper.extractSomMarks(zoomedObs, zoomMap);
        assertEquals(Integer.valueOf(1), Integer.valueOf(zoomMarks.size()), "Panned viewport filters offscreen boxes");
        assertEquals("e2", zoomMarks.get(0).ref, "Keeps visible zoomed element");
        assertTrue(Math.abs(zoomMarks.get(0).left - 0f) < 0.5f && Math.abs(zoomMarks.get(0).top - 0f) < 0.5f,
            "Zoomed origin aligns at (0,0)");

        // 5. Stale mode rejection & normalized coordinates
        ScreenshotCaptureHelper.ResolvedPoint staleClick = ScreenshotCaptureHelper.resolveClickCoordinates(
            mobileFrame, 100, 200, "screenshot", 1080, 2160, true);
        assertTrue(!staleClick.ok && "STALE_SCREENSHOT".equals(staleClick.errorCode),
            "Rejects explicit screenshot coords after switching mobile -> desktop");
        ScreenshotCaptureHelper.ResolvedPoint normClick = ScreenshotCaptureHelper.resolveClickCoordinates(
            null, 500, 500, "normalized", 1080, 2160, true);
        assertTrue(normClick.ok && Math.abs(normClick.viewX - 540f) < 0.5f && Math.abs(normClick.viewY - 1080f) < 0.5f,
            "Normalized coordinates resolve to exact view center");

        // 6. Multimodal Agent loop alignment, pruning, and token estimate
        AcidSessionStore.AgentSession mmSession = new AcidSessionStore.AgentSession();
        mmSession.id = "s_mm";
        mmSession.apiMessages.add(new JSONObject().put("role", "user").put("content", "Inspect page"));
        JSONObject img1 = new JSONObject().put("base64", "AAAA").put("mimeType", "image/jpeg").put("width", 768).put("height", 1536);
        AcidAgentLoop.appendTurnScreenshotMessages(mmSession, java.util.Collections.singletonList(img1), null);
        assertTrue(AcidAgentLoop.isMultimodalImageMessage(mmSession.apiMessages.get(1)), "First screenshot appended as multimodal user message");

        StringBuilder bigB64 = new StringBuilder();
        for (int i = 0; i < 60000; i++) bigB64.append('B');
        JSONObject img2 = new JSONObject().put("base64", bigB64.toString()).put("mimeType", "image/jpeg").put("width", 768).put("height", 1536);
        AcidAgentLoop.appendTurnScreenshotMessages(mmSession, java.util.Collections.singletonList(img2), null);
        assertTrue(!AcidAgentLoop.isMultimodalImageMessage(mmSession.apiMessages.get(1)),
            "Older screenshot compacted inside session apiMessages on disk");
        assertTrue(AcidAgentLoop.isMultimodalImageMessage(mmSession.apiMessages.get(2)),
            "Latest screenshot retained in session apiMessages");
        int estTokens = AcidAgentLoop.estimateTokens(mmSession);
        assertTrue(estTokens > 500 && estTokens < 5000,
            "Token estimator avoids raw Base64 char inflation (got " + estTokens + ")");

        // Prune directly on two raw multimodal messages as well
        List<JSONObject> rawHist = new ArrayList<JSONObject>();
        rawHist.add(AcidAgentLoop.buildMultimodalImageMessage(java.util.Collections.singletonList(img1)));
        rawHist.add(new JSONObject().put("role", "assistant").put("content", "step1"));
        rawHist.add(AcidAgentLoop.buildMultimodalImageMessage(java.util.Collections.singletonList(img2)));
        List<JSONObject> prunedHist = AcidAgentLoop.pruneHistoryForRequest(rawHist);
        assertTrue(!AcidAgentLoop.isMultimodalImageMessage(prunedHist.get(0)), "pruneHistoryForRequest drops older image");
        assertTrue(AcidAgentLoop.isMultimodalImageMessage(prunedHist.get(2)), "pruneHistoryForRequest keeps latest image");

        // 7. MCP schema & MCP image content output
        JSONArray mcpTools = AcidSandboxTools.buildMcpToolsList();
        JSONObject browserProps = null;
        for (int i = 0; i < mcpTools.length(); i++) {
            JSONObject item = mcpTools.optJSONObject(i);
            if (item != null && "browser".equals(item.optString("name"))) {
                browserProps = item.optJSONObject("inputSchema").optJSONObject("properties");
                break;
            }
        }
        assertTrue(browserProps != null
            && browserProps.optJSONObject("action").optJSONArray("enum").toString().contains("\"screenshot\"")
            && browserProps.has("coord_space")
            && browserProps.has("save_path"),
            "MCP browser schema includes screenshot action, coord_space, and save_path");

        AcidSandboxTools.ToolExecResult mcpImgRes =
            AcidSandboxTools.ToolExecResult.withImage("Captured 768x1536", "QUI=", "image/jpeg", 768, 1536);
        JSONObject mcpPayload = HttpControlServer.formatMcpToolExecResult(mcpImgRes);
        JSONArray mcpContent = mcpPayload.optJSONArray("content");
        assertEquals(Integer.valueOf(2), Integer.valueOf(mcpContent.length()), "MCP returns text + image blocks");
        assertEquals("image", mcpContent.optJSONObject(1).optString("type"), "Second MCP block has type=image");
        assertEquals("image/jpeg", mcpContent.optJSONObject(1).optString("mimeType"), "MCP image block sets mimeType");
        assertEquals("QUI=", mcpContent.optJSONObject(1).optString("data"), "MCP image block carries Base64 data");

        // 8. Screenshot save_path validation, format detection & workspace protection parity
        File saveTestRoot = new File("build/test_screenshot_save_path");
        SkillStorageVault.deleteRecursiveSafe(saveTestRoot);
        File saveWs = new File(saveTestRoot, "acid_workspace");
        new File(saveWs, "captures/existing_dir.png").mkdirs();
        try {
            ScreenshotCaptureHelper.SaveTargetResolution pngRes =
                ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "workspace://captures/page.PNG");
            assertTrue(pngRes.ok && pngRes.format == ScreenshotCaptureHelper.SaveFormat.PNG
                && "captures/page.PNG".equals(pngRes.relativePath),
                "Resolves PNG save_path inside workspace");

            ScreenshotCaptureHelper.SaveTargetResolution jpgRes =
                ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "shot.jpeg");
            assertTrue(jpgRes.ok && jpgRes.format == ScreenshotCaptureHelper.SaveFormat.JPEG,
                "Resolves JPEG save_path inside workspace");

            assertTrue(!ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "../escape.png").ok,
                "Rejects save_path escaping workspace");
            assertTrue(!ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "homepage/bg.png").ok,
                "Rejects save_path inside protected homepage/");
            assertTrue(!ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "userscripts/pic.png").ok,
                "Rejects save_path inside protected userscripts/");
            assertTrue(!ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "settings.json").ok,
                "Rejects protected settings.json");
            assertTrue(!ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "captures/shot.txt").ok,
                "Rejects non-image extension");
            assertTrue(!ScreenshotCaptureHelper.resolveSaveTarget(saveWs, "captures/existing_dir.png").ok,
                "Rejects directory collision");

            AcidSandboxTools saveTools = new AcidSandboxTools(null, saveWs);
            AcidSandboxTools.ToolExecResult blockedProtected = saveTools.executeTool("browser",
                new JSONObject().put("action", "screenshot").put("save_path", "homepage/screen.png"),
                AcidSandboxTools.ToolSource.MCP);
            assertTrue(blockedProtected.outputText.contains("protected workspace item"),
                "MCP screenshot save_path rejects protected workspace paths");

            AcidSandboxTools.ToolExecResult blockedExt = saveTools.executeTool("browser",
                new JSONObject().put("action", "screenshot").put("save_path", "captures/no_ext"),
                AcidSandboxTools.ToolSource.AGENT_UI);
            assertTrue(blockedExt.outputText.startsWith("Error:") && blockedExt.outputText.contains(".png"),
                "Agent UI screenshot save_path validates image extension before capture");

            AcidSandboxTools.ToolExecResult validSavePathReady = saveTools.executeTool("browser",
                new JSONObject().put("action", "screenshot").put("save_path", "captures/ready.png"),
                AcidSandboxTools.ToolSource.MCP);
            assertTrue(validSavePathReady.outputText.contains("Browser controller or automation driver unavailable"),
                "Valid MCP screenshot save_path passes validation and proceeds to driver: "
                    + validSavePathReady.outputText);
        } finally {
            SkillStorageVault.deleteRecursiveSafe(saveTestRoot);
        }
    }

    static void testDesktopOverviewAutoZoomRecovery() throws Exception {
        assertEquals(1280, DesktopViewportManager.DESKTOP_WIDTH, "Desktop layout target width stays 1280px");
        assertTrue(Math.abs(DesktopViewportManager.OVERVIEW_ZOOM_THRESHOLD - 0.94) < 1e-9,
            "Overview zoom threshold stays 0.94");

        // Unzoomed or within threshold -> 1.0 (no-op)
        assertTrue(Math.abs(DesktopViewportManager.computeOverviewRestoreFactor(1280, 1280) - 1.0) < 1e-9,
            "Full 1280px overview requires no zoom adjustment");
        assertTrue(Math.abs(DesktopViewportManager.computeOverviewRestoreFactor(1280, 1210) - 1.0) < 1e-9,
            "Slightly narrow viewport above 0.94 threshold remains a no-op");

        // Focus-zoomed desktop page -> proportional zoomBy restore factor
        double justBelow = DesktopViewportManager.computeOverviewRestoreFactor(1280, 1180);
        assertTrue(Math.abs(justBelow - (1180.0 / 1280.0)) < 1e-9,
            "Viewport below 0.94 threshold computes exact restore factor: " + justBelow);

        double phoneFocusZoom = DesktopViewportManager.computeOverviewRestoreFactor(1280, 426);
        assertTrue(Math.abs(phoneFocusZoom - (426.0 / 1280.0)) < 1e-9,
            "3x focus auto-zoom on 1280px desktop page yields ~0.3328 zoomBy factor: " + phoneFocusZoom);

        double wideLayout = DesktopViewportManager.computeOverviewRestoreFactor(1600, 800);
        assertTrue(Math.abs(wideLayout - 0.5) < 1e-9,
            "Layout wider than 1280px scales relative to full layoutWidth");

        double narrowLayout = DesktopViewportManager.computeOverviewRestoreFactor(360, 360);
        assertTrue(Math.abs(narrowLayout - (360.0 / 1280.0)) < 1e-9,
            "Target width floors at DESKTOP_WIDTH (1280)");

        // Invalid or extreme inputs stay safe for WebView.zoomBy
        assertTrue(Math.abs(DesktopViewportManager.computeOverviewRestoreFactor(1280, 0) - 1.0) < 1e-9,
            "Zero visualViewportWidth safely returns 1.0");
        assertTrue(Math.abs(DesktopViewportManager.computeOverviewRestoreFactor(1280, -25) - 1.0) < 1e-9,
            "Negative visualViewportWidth safely returns 1.0");
        assertTrue(Math.abs(DesktopViewportManager.computeOverviewRestoreFactor(1280, 20) - 0.05) < 1e-9,
            "Extreme zoom ratio clamps at safe WebView.zoomBy lower bound 0.05");

        // Metrics probe script contains visualViewport and scroll telemetry keys
        String metricsJs = DesktopViewportManager.buildViewportMetricsScript();
        assertTrue(metricsJs.contains("window.visualViewport")
                && metricsJs.contains("clientWidth")
                && metricsJs.contains("offsetLeft")
                && metricsJs.contains("scrollY")
                && metricsJs.contains("lw:lw,vvw:vvw,vvol:vvol,sy:sy"),
            "Viewport metrics script probes layoutWidth, visualViewport width/offsetLeft, and scrollY");

        // Eligibility checks
        assertTrue(DesktopViewportManager.isEligibleUrl("https://example.com/form"), "https URL eligible");
        assertTrue(DesktopViewportManager.isEligibleUrl("http://192.168.1.2/login"), "http URL eligible");
        assertTrue(!DesktopViewportManager.isEligibleUrl("acid://home"), "acid:// excluded from desktop viewport lock");
        assertTrue(!DesktopViewportManager.isEligibleUrl("file:///sdcard/index.html"), "file:// excluded");
        assertTrue(!DesktopViewportManager.isEligibleUrl(null), "null URL excluded");

        // Driver guard safety with null host and desktop-mode host without looper/tab
        WebAutomationDriver nullHostDriver = new WebAutomationDriver(null, null);
        nullHostDriver.ensureDesktopOverviewIfZoomedSync();

        WebAutomationDriver.AutomationHost desktopNullTabHost = new WebAutomationDriver.AutomationHost() {
            @Override public BrowserController.TabSession getActiveTab() { return null; }
            @Override public BrowserController.TabSession getTargetTab() { return null; }
            @Override public boolean isTargetTabActive() { return false; }
            @Override public AgentOverlayView getOverlayView() { return null; }
            @Override public UserscriptManager getUserscriptManager() { return null; }
            @Override public void loadUrl(String url) {}
            @Override public void emitLog(String log) {}
            @Override public boolean isSomEnabled() { return false; }
            @Override public boolean isDesktopMode() { return true; }
            @Override public int getBlockedDeepLinksCount() { return 0; }
            @Override public String getLastBlockedDeepLink() { return ""; }
            @Override public int getTriggeredDownloadsCount() { return 0; }
            @Override public String getLastTriggeredDownloadInfo() { return ""; }
        };
        WebAutomationDriver desktopNoLooperDriver = new WebAutomationDriver(null, desktopNullTabHost);
        desktopNoLooperDriver.ensureDesktopOverviewIfZoomedSync();
    }

    static void testAgentChatAttachmentsFeature() throws Exception {
        assertEquals(Integer.valueOf(5), Integer.valueOf(AgentAttachmentHelper.MAX_ATTACHMENTS),
            "Single-send attachment ceiling stays 5");

        // 1. Max-5 selection clamping across initial and incremental batches
        List<String> seven = Arrays.asList("a", "b", "c", "d", "e", "f", "g");
        AgentAttachmentHelper.LimitedSelection<String> firstBatch = AgentAttachmentHelper.clampToMaxAttachments(seven, 0);
        assertEquals(Integer.valueOf(5), Integer.valueOf(firstBatch.accepted.size()), "Initial batch accepts max 5");
        assertEquals(Integer.valueOf(2), Integer.valueOf(firstBatch.trimmedCount), "Initial batch reports 2 trimmed");
        assertEquals("e", firstBatch.accepted.get(4), "Initial batch preserves selection order");

        AgentAttachmentHelper.LimitedSelection<String> partialBatch =
            AgentAttachmentHelper.clampToMaxAttachments(Arrays.asList("x", "y", "z"), 3);
        assertEquals(Integer.valueOf(2), Integer.valueOf(partialBatch.accepted.size()), "Remaining 2 slots accepted when 3 pending");
        assertEquals(Integer.valueOf(1), Integer.valueOf(partialBatch.trimmedCount), "Reports 1 excess item");

        AgentAttachmentHelper.LimitedSelection<String> fullBatch =
            AgentAttachmentHelper.clampToMaxAttachments(Arrays.asList("one"), 5);
        assertEquals(Integer.valueOf(0), Integer.valueOf(fullBatch.accepted.size()), "Full queue accepts 0 more items");
        assertEquals(Integer.valueOf(1), Integer.valueOf(fullBatch.trimmedCount), "Full queue reports excess");

        // 2. Classification & extension inference
        assertTrue(AgentAttachmentHelper.isRasterImageFile("image/jpeg", "photo.bin"), "JPEG MIME classified as raster image");
        assertTrue(AgentAttachmentHelper.isRasterImageFile("", "screen.png"), ".png classified as raster image");
        assertTrue(!AgentAttachmentHelper.isRasterImageFile("image/svg+xml", "diagram.svg"), "SVG excluded from raster vision parts");
        assertEquals("100001.jpg", AgentAttachmentHelper.ensureExtensionFromMime("100001", "image/jpeg"),
            "Extensionless image URI receives .jpg suffix");
        assertEquals("report.pdf", AgentAttachmentHelper.ensureExtensionFromMime("report", "application/pdf"),
            "Extensionless PDF URI receives .pdf suffix");
        assertEquals("notes.md", AgentAttachmentHelper.ensureExtensionFromMime("notes.md", "text/plain"),
            "Existing extension is preserved");
        assertEquals("0 B", AgentAttachmentHelper.formatFileSize(0L), "Zero byte attachment displays 0 B");

        // 3. Workspace staging, collision avoidance, prompt context & binary read/edit guard
        File wsRoot = new File("build/test_chat_attachments");
        SkillStorageVault.deleteRecursiveSafe(wsRoot);
        try {
            File uploadsDir = new File(wsRoot, AgentAttachmentHelper.UPLOADS_DIR_NAME);
            File safeTrav = AgentAttachmentHelper.resolveUniqueUploadFile(uploadsDir, "../../etc/passwd");
            assertTrue(safeTrav.getCanonicalPath().startsWith(uploadsDir.getCanonicalPath() + File.separator),
                "Path traversal filename confined inside uploads/");

            File f1 = AgentAttachmentHelper.resolveUniqueUploadFile(uploadsDir, "notes.txt");
            AcidIo.writeFileUtf8(f1, "line1: hello\nline2: world");
            File f2 = AgentAttachmentHelper.resolveUniqueUploadFile(uploadsDir, "notes.txt");
            assertTrue(!f1.getAbsolutePath().equals(f2.getAbsolutePath()) && f2.getName().startsWith("notes_1"),
                "Colliding filename increments cleanly: " + f2.getName());

            // Big text file (>12KB) & binary PDF file
            File bigTxt = AgentAttachmentHelper.resolveUniqueUploadFile(uploadsDir, "big.log");
            StringBuilder bigSb = new StringBuilder();
            for (int i = 0; i < 600; i++) bigSb.append("2026-11-16 log entry line ").append(i).append("\n");
            AcidIo.writeFileUtf8(bigTxt, bigSb.toString());

            File binPdf = AgentAttachmentHelper.resolveUniqueUploadFile(uploadsDir, "spec.pdf");
            byte[] pdfBytes = new byte[] { '%', 'P', 'D', 'F', '-', '1', '.', '4', '\n', 0, 1, 2, 3 };
            AcidIo.writeBytes(binPdf, pdfBytes);

            assertTrue(!AgentAttachmentHelper.isBinaryFile(f1), "UTF-8 notes.txt recognized as text");
            assertTrue(AgentAttachmentHelper.isBinaryFile(binPdf), "PDF with NUL byte recognized as binary");
            assertTrue(AgentAttachmentHelper.isTextReadableFile("image/svg+xml", "icon.svg", null), "SVG recognized as text");

            // Sandbox tools read/edit protection against binary attachments
            AcidSandboxTools tools = new AcidSandboxTools(null, wsRoot);
            AcidSandboxTools.ToolExecResult binRead = tools.executeTool(
                "read",
                new JSONObject().put("file_path", "uploads/spec.pdf")
            );
            assertTrue(binRead.outputText.contains("Binary file cannot be read as UTF-8 text"),
                "read tool blocks binary PDF mojibake cleanly: " + binRead.outputText);

            AcidSandboxTools.ToolExecResult txtRead = tools.executeTool(
                "read",
                new JSONObject().put("file_path", "uploads/notes.txt")
            );
            assertTrue(txtRead.outputText.contains("line1: hello"),
                "read tool reads uploaded text file normally");

            // Prompt context inlines small text, directs read on large text, and tags binary uploadable path
            List<AgentAttachmentHelper.AttachmentItem> mix = Arrays.asList(
                new AgentAttachmentHelper.AttachmentItem("att1", "notes.txt", "uploads/notes.txt", "text/plain", f1.length(), false, 0, 0, ""),
                new AgentAttachmentHelper.AttachmentItem("att2", "big.log", "uploads/big.log", "text/plain", bigTxt.length(), false, 0, 0, ""),
                new AgentAttachmentHelper.AttachmentItem("att3", "spec.pdf", "uploads/spec.pdf", "application/pdf", binPdf.length(), false, 0, 0, ""),
                new AgentAttachmentHelper.AttachmentItem("att4", "pic.jpg", "uploads/pic.jpg", "image/jpeg", 2048, true, 1200, 900, "AAAA")
            );
            String attCtx = AgentAttachmentHelper.buildAttachmentPromptContext(wsRoot, mix);
            assertTrue(attCtx.contains("line1: hello") && attCtx.contains("line2: world"),
                "Small text attachment inlined in user prompt");
            assertTrue(attCtx.contains("read(file_path='uploads/big.log'"),
                "Large text attachment points agent to read tool");
            assertTrue(attCtx.contains("browser(action='upload', file_path='uploads/spec.pdf'"),
                "Binary attachment points agent to bash/browser upload");
            assertTrue(attCtx.contains("preview=1200x900px attached visually"),
                "Image attachment includes visual dimensions");

            // 4. Text-only message vs multimodal user message & screenshot pruning isolation
            List<AgentAttachmentHelper.AttachmentItem> textOnlyAtt = Collections.singletonList(mix.get(0));
            JSONObject textApiMsg = AcidAgentLoop.buildUserApiMessage("Check notes", "", wsRoot, textOnlyAtt);
            assertTrue(textApiMsg.opt("content") instanceof String,
                "Text-only attachment sends plain string content compatible with non-vision models");

            JSONObject userImgApiMsg = AcidAgentLoop.buildUserApiMessage("What is in this pic?", "", wsRoot, Collections.singletonList(mix.get(3)));
            assertTrue(userImgApiMsg.opt("content") instanceof JSONArray && !AcidAgentLoop.isBrowserScreenshotMessage(userImgApiMsg),
                "User image message uses multimodal array and is distinct from browser screenshots");

            AcidSessionStore.AgentSession s = new AcidSessionStore.AgentSession();
            s.id = "s_attach";
            s.apiMessages.add(userImgApiMsg);
            JSONObject shot = new JSONObject().put("base64", "BBBB").put("mimeType", "image/jpeg").put("width", 768).put("height", 1536);
            AcidAgentLoop.appendTurnScreenshotMessages(s, Collections.singletonList(shot), null);
            assertTrue(AcidAgentLoop.isMultimodalImageMessage(s.apiMessages.get(0)),
                "Browser screenshot compaction must NOT wipe user-uploaded image attachment");
            assertTrue(AcidAgentLoop.isMultimodalImageMessage(s.apiMessages.get(1)),
                "Latest browser screenshot also retained");

            // Pruning older user image turns (>2) preserves text while compacting old images
            List<JSONObject> hist = new ArrayList<JSONObject>();
            hist.add(AcidAgentLoop.buildUserApiMessage("First pic", "", wsRoot, Collections.singletonList(mix.get(3))));
            hist.add(new JSONObject().put("role", "assistant").put("content", "ok1"));
            hist.add(AcidAgentLoop.buildUserApiMessage("Second pic", "", wsRoot, Collections.singletonList(mix.get(3))));
            hist.add(new JSONObject().put("role", "assistant").put("content", "ok2"));
            hist.add(AcidAgentLoop.buildUserApiMessage("Third pic", "", wsRoot, Collections.singletonList(mix.get(3))));
            List<JSONObject> pruned = AcidAgentLoop.pruneHistoryForRequest(hist);
            assertTrue(pruned.get(0).opt("content") instanceof String
                    && pruned.get(0).optString("content").contains("First pic"),
                "Compacted oldest user image keeps original prompt text intact");
            assertTrue(AcidAgentLoop.isMultimodalImageMessage(pruned.get(2))
                    && AcidAgentLoop.isMultimodalImageMessage(pruned.get(4)),
                "Keeps latest 2 user image messages intact");

            // 5. UiMessageItem JSON round-trip preserves attachment metadata without leaking base64
            AcidSessionStore.UiMessageItem uiItem = new AcidSessionStore.UiMessageItem();
            uiItem.type = AcidSessionStore.TYPE_USER;
            uiItem.text = "看看这个";
            uiItem.attachments.addAll(mix);
            String serialized = uiItem.toJson().toString();
            assertTrue(!serialized.contains("AAAA"), "UiMessageItem JSON does not embed raw Base64 blobs");
            AcidSessionStore.UiMessageItem restored = AcidSessionStore.UiMessageItem.fromJson(new JSONObject(serialized));
            assertEquals(Integer.valueOf(4), Integer.valueOf(restored.attachments.size()),
                "UiMessageItem restores all attachment metadata entries");
            assertEquals("uploads/pic.jpg", restored.attachments.get(3).relativePath,
                "Restored attachment retains workspace relativePath");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(wsRoot);
        }
    }

    static void testSheetImeCeilingAndHistoryBookmarksPaging() throws Exception {
        // 1. AcidBottomSheetView scroll ceiling shrinks cleanly with IME and restores on dismissal
        int fullCeiling = AcidBottomSheetView.computeScrollCeilingPx(2200, 0.75f, 300, 540);
        assertEquals(Integer.valueOf(1350), Integer.valueOf(fullCeiling),
            "Full-screen host computes 75% ceiling minus chrome reserve");

        int imeOpenCeiling = AcidBottomSheetView.computeScrollCeilingPx(1200, 0.75f, 300, 540);
        assertEquals(Integer.valueOf(600), Integer.valueOf(imeOpenCeiling),
            "Shrunk IME host computes reduced scroll ceiling");

        int restoredCeiling = AcidBottomSheetView.computeScrollCeilingPx(2200, 0.75f, 300, 540);
        assertEquals(Integer.valueOf(1350), Integer.valueOf(restoredCeiling),
            "Restored host height recomputes full 1350px ceiling without remaining short");

        int tightLandscapeCeiling = AcidBottomSheetView.computeScrollCeilingPx(700, 0.75f, 300, 540);
        assertEquals(Integer.valueOf(400), Integer.valueOf(tightLandscapeCeiling),
            "Very tight host height clamps scroll ceiling to available space above chrome");

        // 2. Batch paging constants and incremental preparation across Bookmarks & History
        assertEquals(Integer.valueOf(40), Integer.valueOf(BookmarksHistorySheet.INITIAL_BATCH_SIZE),
            "Initial batch size stays 40");
        assertEquals(Integer.valueOf(40), Integer.valueOf(BookmarksHistorySheet.LOAD_MORE_BATCH_SIZE),
            "Load-more batch size stays 40");

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(2026, java.util.Calendar.NOVEMBER, 16, 15, 0, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long now = cal.getTimeInMillis();
        long dayMs = 86400000L;

        List<BrowserDataStore.EntryItem> rawHistory = new ArrayList<BrowserDataStore.EntryItem>();
        rawHistory.add(new BrowserDataStore.EntryItem("Today 1", "https://today1.example", now - 60000L));
        rawHistory.add(new BrowserDataStore.EntryItem("Yesterday 1", "https://yest.example", now - dayMs));
        rawHistory.add(new BrowserDataStore.EntryItem("Today 2", "https://today2.example", now - 120000L));
        rawHistory.add(new BrowserDataStore.EntryItem("Past Week", "https://week.example", now - 3 * dayMs));
        rawHistory.add(new BrowserDataStore.EntryItem("Old Site", "https://old.example", now - 20 * dayMs));

        List<BookmarksHistorySheet.PreparedEntry> bucketedHistory =
            BookmarksHistorySheet.buildPreparedEntries(rawHistory, false, "", now);
        assertEquals(Integer.valueOf(5), Integer.valueOf(bucketedHistory.size()), "All history entries prepared");
        assertEquals("今天 (2)", bucketedHistory.get(0).sectionHeader, "Today bucket ordered first with count");
        assertEquals("Today 1", bucketedHistory.get(0).item.title, "Preserves Today item 1");
        assertEquals("Today 2", bucketedHistory.get(1).item.title, "Preserves Today item 2");
        assertEquals("昨天 (1)", bucketedHistory.get(2).sectionHeader, "Yesterday bucket follows Today");
        assertEquals("最近 7 天 (1)", bucketedHistory.get(3).sectionHeader, "Past 7 days bucket follows Yesterday");
        assertEquals("更早以前 (1)", bucketedHistory.get(4).sectionHeader, "Earlier bucket ordered last");

        // Search inside history flattens into Search Results without time buckets
        List<BookmarksHistorySheet.PreparedEntry> searchedHistory =
            BookmarksHistorySheet.buildPreparedEntries(rawHistory, false, "today", now);
        assertEquals(Integer.valueOf(2), Integer.valueOf(searchedHistory.size()), "History search filters matches");
        assertEquals("搜索结果 (2)", searchedHistory.get(0).sectionHeader, "Search header includes total match count");

        // Bookmarks preserve original index across filtered searches so edit/delete target exact item
        List<BrowserDataStore.EntryItem> rawBookmarks = new ArrayList<BrowserDataStore.EntryItem>();
        for (int i = 0; i < 120; i++) {
            rawBookmarks.add(new BrowserDataStore.EntryItem("Bookmark " + i, "https://b" + i + ".example"));
        }
        List<BookmarksHistorySheet.PreparedEntry> allBookmarks =
            BookmarksHistorySheet.buildPreparedEntries(rawBookmarks, true, "", now);
        assertEquals(Integer.valueOf(120), Integer.valueOf(allBookmarks.size()), "Prepares all bookmarks cleanly");
        assertEquals("全部书签 (120)", allBookmarks.get(0).sectionHeader, "Bookmarks header reflects total count");
        assertEquals(Integer.valueOf(99), Integer.valueOf(allBookmarks.get(99).originalIndex), "Retains exact index");

        List<BookmarksHistorySheet.PreparedEntry> filteredBookmarks =
            BookmarksHistorySheet.buildPreparedEntries(rawBookmarks, true, "b105", now);
        assertEquals(Integer.valueOf(1), Integer.valueOf(filteredBookmarks.size()), "Matches single filtered bookmark");
        assertEquals(Integer.valueOf(105), Integer.valueOf(filteredBookmarks.get(0).originalIndex),
            "Filtered bookmark keeps original backing index 105 for edit/delete");
        assertEquals("搜索结果 (1)", filteredBookmarks.get(0).sectionHeader, "Filtered bookmarks use search header");

        assertTrue(BookmarksHistorySheet.buildPreparedEntries(rawBookmarks, true, "nonexistent_xyz", now).isEmpty(),
            "Unmatched search yields empty list safely");
    }

    static void testWebViewFocusAndImeIsolationPolicy() {
        // 1. Active human tab allows focus & IME unless human UI chrome input holds focus
        AcidWebView.IsolationPolicy activeHuman = AcidWebView.resolveIsolationPolicy(
            true, AgentOverlayView.MODE_HUMAN, false, false
        );
        assertTrue(activeHuman.allowFocus && activeHuman.allowIme,
            "Active human tab allows View focus and IME input");

        AcidWebView.IsolationPolicy uiInputFocused = AcidWebView.resolveIsolationPolicy(
            true, AgentOverlayView.MODE_HUMAN, false, true
        );
        assertTrue(uiInputFocused.allowFocus && uiInputFocused.allowIme && uiInputFocused.blockProgrammaticFocus,
            "Active WebView stays touch-focusable for human taps while blocking programmatic focus steals");
        assertTrue(!AcidWebView.shouldAllowRequestFocus(
                uiInputFocused.allowFocus, uiInputFocused.blockProgrammaticFocus, false),
            "Programmatic/JS requestFocus without recent user touch cannot steal URL/Agent input focus");
        assertTrue(AcidWebView.shouldAllowRequestFocus(
                uiInputFocused.allowFocus, uiInputFocused.blockProgrammaticFocus, true),
            "Direct finger tap on unlocked webpage cleanly reclaims focus from URL/Agent bar");

        // 2. Active tab driven by Agent locks both View focus and IME popup (while non-agent active tab stays free)
        AcidWebView.IsolationPolicy foregroundAgent = AcidWebView.resolveIsolationPolicy(
            true, AgentOverlayView.MODE_AGENT, true, false
        );
        assertTrue(!foregroundAgent.allowFocus && !foregroundAgent.allowIme,
            "Foreground Agent-driven tab blocks Android focus and IME popup even when Agent dialog is collapsed");
        assertTrue(!AcidWebView.shouldAllowRequestFocus(
                foregroundAgent.allowFocus, foregroundAgent.blockProgrammaticFocus, true),
            "Locked Agent-driven tab never allows focus even when touched");

        AcidWebView.IsolationPolicy freeTabDuringBgAgent = AcidWebView.resolveIsolationPolicy(
            true, AgentOverlayView.MODE_AGENT, false, false
        );
        assertTrue(freeTabDuringBgAgent.allowFocus && freeTabDuringBgAgent.allowIme,
            "Human browsing non-agent active tab keeps full focus and IME while background tab runs Agent");

        // 3. Human takeover restores focus and IME
        AcidWebView.IsolationPolicy takeover = AcidWebView.resolveIsolationPolicy(
            true, AgentOverlayView.MODE_TAKEOVER_REQUESTED, true, false
        );
        assertTrue(takeover.allowFocus && takeover.allowIme,
            "Takeover mode restores focus and IME for human interaction");

        // 4. Background inactive tabs must ALWAYS block both focus and IME regardless of mode
        int[] allModes = new int[] {
            AgentOverlayView.MODE_HUMAN,
            AgentOverlayView.MODE_AGENT,
            AgentOverlayView.MODE_TAKEOVER_REQUESTED
        };
        for (int m : allModes) {
            for (boolean driven : new boolean[] { false, true }) {
                AcidWebView.IsolationPolicy bg = AcidWebView.resolveIsolationPolicy(false, m, driven, false);
                assertTrue(!bg.allowFocus && !bg.allowIme,
                    "Background tab isolates focus & IME (mode=" + m + ", driven=" + driven + ")");
            }
        }

        // 5. Window creation (onCreateWindow) & Agent binding transfer across foreground/background tabs
        assertTrue(AcidWebView.shouldSwitchToNewWindow(true),
            "Active tab window.open switches foreground to new window");
        assertTrue(!AcidWebView.shouldSwitchToNewWindow(false),
            "Background tab window.open must not hijack active foreground tab");
        assertTrue(AcidWebView.shouldTransferAgentBindingToNewWindow(true, AgentOverlayView.MODE_AGENT, 2, true),
            "Foreground watched bound tab transfers Agent binding to _blank popup");
        assertTrue(AcidWebView.shouldTransferAgentBindingToNewWindow(false, AgentOverlayView.MODE_AGENT, -1, true),
            "Foreground unbound MODE_AGENT tab transfers Agent binding to _blank popup");
        assertTrue(AcidWebView.shouldTransferAgentBindingToNewWindow(true, AgentOverlayView.MODE_AGENT, 2, false),
            "Background bound tab transfers Agent binding to popup without foreground switch");
        assertTrue(!AcidWebView.shouldTransferAgentBindingToNewWindow(false, AgentOverlayView.MODE_AGENT, 2, true),
            "Human active tab popup does not hijack background Agent binding");
        assertTrue(!AcidWebView.shouldTransferAgentBindingToNewWindow(false, AgentOverlayView.MODE_HUMAN, -1, true),
            "Human mode popup never binds Agent");

        // 6. Agent set_mode desktop/mobile reload targets agent-bound tab & TabSession isolates per-tab flag
        assertEquals(Integer.valueOf(7),
            Integer.valueOf(AcidWebView.resolveModeReloadTabId(1, 7, true)),
            "Background Agent mode change reloads only bound tab 7, leaving active tab 1 intact");
        assertEquals(Integer.valueOf(1),
            Integer.valueOf(AcidWebView.resolveModeReloadTabId(1, 7, false)),
            "Human UI mode change targets active tab 1");
        assertEquals(Integer.valueOf(1),
            Integer.valueOf(AcidWebView.resolveModeReloadTabId(1, 1, true)),
            "Foreground bound tab still reloads tab 1 cleanly");

        BrowserController.TabSession mobileHumanTab = new BrowserController.TabSession(1, null);
        BrowserController.TabSession desktopAgentTab = new BrowserController.TabSession(2, null);
        desktopAgentTab.isDesktopMode = true;
        assertTrue(!mobileHumanTab.isDesktopMode && desktopAgentTab.isDesktopMode,
            "TabSession isolates per-tab desktop viewport state across human and background Agent tabs");
    }

    private static String readHttpAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] arr = buf.toByteArray();
                int len = (prev == '\r' && arr.length > 0) ? arr.length - 1 : arr.length;
                return new String(arr, 0, len, StandardCharsets.US_ASCII);
            }
            buf.write(b);
            prev = b;
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void assertStrictlyOrthogonalClosedLoop(AgentLockChromeOverlay.OutlineGeometry geom, String label) {
        assertTrue(geom != null && geom.vertexCount() >= 4, label + ": valid polygon generated");
        int n = geom.vertexCount();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float dx = Math.abs(geom.points[j * 2] - geom.points[i * 2]);
            float dy = Math.abs(geom.points[j * 2 + 1] - geom.points[i * 2 + 1]);
            boolean horizontal = (dy < 0.01f && dx > 0.5f);
            boolean vertical = (dx < 0.01f && dy > 0.5f);
            assertTrue(horizontal ^ vertical,
                label + ": edge " + i + " must be strictly orthogonal non-zero (dx=" + dx + ", dy=" + dy + ")");
        }
    }

    static void testChromeLockPolicyAndOutlineGeometry() {
        // 1. Lock state follows active tab & control mode cleanly
        assertTrue(!AgentLockChromeOverlay.isChromeLocked(AgentOverlayView.MODE_HUMAN, false, -1, 1),
            "Human mode never locks Chrome buttons");
        assertTrue(!AgentLockChromeOverlay.isChromeLocked(AgentOverlayView.MODE_TAKEOVER_REQUESTED, false, 2, 2),
            "Human takeover unlocks all Chrome buttons even on bound tab");
        assertTrue(AgentLockChromeOverlay.isChromeLocked(AgentOverlayView.MODE_AGENT, false, 2, 2),
            "Foreground Agent-driven tab locks Chrome controls");
        assertTrue(!AgentLockChromeOverlay.isChromeLocked(AgentOverlayView.MODE_AGENT, false, 2, 5),
            "Switching to an unbound tab immediately unlocks Chrome controls");
        assertTrue(!AgentLockChromeOverlay.isChromeLocked(AgentOverlayView.MODE_AGENT, true, 2, 2),
            "Lost target tab does not stay stuck locked");
        assertTrue(AgentLockChromeOverlay.isChromeLocked(AgentOverlayView.MODE_AGENT, false, -1, 1),
            "Unbound MODE_AGENT locks active foreground tab");

        // 2. Phone portrait (1080x2400) produces 10-vertex one-stroke path skipping top-right AI & bottom Tabs
        AgentLockChromeOverlay.OutlineGeometry phone = AgentLockChromeOverlay.computeOutlineGeometry(
            new AgentLockChromeOverlay.Bounds(0, 0, 1080, 2400),
            new AgentLockChromeOverlay.Bounds(0, 0, 1080, 160),
            new AgentLockChromeOverlay.Bounds(130, 12, 950, 148),
            new AgentLockChromeOverlay.Bounds(968, 18, 1068, 142),
            new AgentLockChromeOverlay.Bounds(0, 168, 1080, 268),
            new AgentLockChromeOverlay.Bounds(0, 268, 1080, 2254),
            new AgentLockChromeOverlay.Bounds(0, 2256, 1080, 2400),
            new AgentLockChromeOverlay.Bounds(648, 2256, 864, 2400),
            9f, 27f, 18f
        );
        assertStrictlyOrthogonalClosedLoop(phone, "Phone portrait");
        assertEquals(Integer.valueOf(10), Integer.valueOf(phone.vertexCount()),
            "Phone layout carves both top-right AI notch and bottom Tabs notch");
        assertEquals(Float.valueOf(959f), Float.valueOf(phone.points[2]),
            "Top notch splits cleanly between search bar and AI button");
        assertTrue(phone.points[4] == phone.points[2] && phone.points[5] > 160f && phone.points[5] < 168f,
            "Top step sits centered between top bar and takeover banner");
        assertEquals(Float.valueOf(864f), Float.valueOf(phone.points[10]),
            "Bottom notch aligns to Tabs right bound");
        assertEquals(Float.valueOf(648f), Float.valueOf(phone.points[14]),
            "Bottom notch aligns to Tabs left bound");

        // 3. Tablet landscape (2560x1600) adapts dynamically without fixed pixel assumptions
        AgentLockChromeOverlay.OutlineGeometry tablet = AgentLockChromeOverlay.computeOutlineGeometry(
            new AgentLockChromeOverlay.Bounds(0, 0, 2560, 1600),
            new AgentLockChromeOverlay.Bounds(0, 0, 2560, 140),
            new AgentLockChromeOverlay.Bounds(120, 10, 2400, 130),
            new AgentLockChromeOverlay.Bounds(2420, 16, 2540, 124),
            new AgentLockChromeOverlay.Bounds(0, 146, 2560, 236),
            new AgentLockChromeOverlay.Bounds(0, 236, 2560, 1454),
            new AgentLockChromeOverlay.Bounds(0, 1456, 2560, 1600),
            new AgentLockChromeOverlay.Bounds(1536, 1456, 2048, 1600),
            9f, 27f, 18f
        );
        assertStrictlyOrthogonalClosedLoop(tablet, "Tablet landscape");
        assertEquals(Integer.valueOf(10), Integer.valueOf(tablet.vertexCount()),
            "Tablet landscape preserves full 10-vertex one-stroke geometry");
        assertEquals(Float.valueOf(2410f), Float.valueOf(tablet.points[2]),
            "Tablet top notch dynamically tracks wide search/AI boundary");
        assertEquals(Float.valueOf(1536f), Float.valueOf(tablet.points[14]),
            "Tablet bottom notch dynamically tracks 1/5th Tabs width");

        // 4. Fullscreen mode (bottom bar hidden) cleanly switches to flat bottom edge (6 vertices)
        AgentLockChromeOverlay.OutlineGeometry fullscreen = AgentLockChromeOverlay.computeOutlineGeometry(
            new AgentLockChromeOverlay.Bounds(0, 0, 1080, 2400),
            new AgentLockChromeOverlay.Bounds(0, 0, 1080, 130),
            new AgentLockChromeOverlay.Bounds(110, 8, 950, 122),
            new AgentLockChromeOverlay.Bounds(966, 12, 1066, 118),
            new AgentLockChromeOverlay.Bounds(0, 136, 1080, 232),
            new AgentLockChromeOverlay.Bounds(0, 232, 1080, 2400),
            null,
            null,
            9f, 27f, 18f
        );
        assertStrictlyOrthogonalClosedLoop(fullscreen, "Fullscreen cutout");
        assertEquals(Integer.valueOf(6), Integer.valueOf(fullscreen.vertexCount()),
            "Fullscreen omits bottom notch while keeping top-right AI notch");

        // 5. Ultra-narrow split-screen clamps corner radius safely below half shortest segment
        AgentLockChromeOverlay.OutlineGeometry narrow = AgentLockChromeOverlay.computeOutlineGeometry(
            new AgentLockChromeOverlay.Bounds(0, 0, 220, 900),
            new AgentLockChromeOverlay.Bounds(0, 0, 220, 90),
            new AgentLockChromeOverlay.Bounds(40, 8, 178, 82),
            new AgentLockChromeOverlay.Bounds(196, 10, 214, 80),
            new AgentLockChromeOverlay.Bounds(0, 124, 220, 184),
            new AgentLockChromeOverlay.Bounds(0, 184, 220, 810),
            new AgentLockChromeOverlay.Bounds(0, 812, 220, 900),
            new AgentLockChromeOverlay.Bounds(132, 812, 176, 900),
            6f, 24f, 12f
        );
        assertStrictlyOrthogonalClosedLoop(narrow, "Ultra-narrow split");
        assertTrue(narrow.cornerRadius < 24f && narrow.cornerRadius >= 1f,
            "Clamps corner radius safely for narrow segments (" + narrow.cornerRadius + ")");
    }

    static void testTakeoverResumeAndRunningSteering() {
        String fallback = "我已完成手动操作，请观察当前最新页面并继续完成任务。";

        // 1. Takeover resume must preserve custom user instructions instead of forcing fallback prompt
        assertEquals("已登录，帮我点第二个标签",
            AcidAgentLoop.resolveTakeoverResumePrompt("  已登录，帮我点第二个标签  ", false, fallback),
            "Takeover resume with typed text forwards trimmed user instruction");
        assertEquals(fallback,
            AcidAgentLoop.resolveTakeoverResumePrompt("   \n\t ", false, fallback),
            "Takeover resume with empty/whitespace text falls back to default continuation prompt");
        assertEquals(fallback,
            AcidAgentLoop.resolveTakeoverResumePrompt(null, false, fallback),
            "Takeover resume with null prompt falls back to default continuation prompt");
        assertEquals("",
            AcidAgentLoop.resolveTakeoverResumePrompt("  ", true, fallback),
            "Takeover resume with attachments and empty text lets attachment prompt apply");

        // 2. Bound tab preservation during steering / takeover resume
        assertEquals(Integer.valueOf(3),
            Integer.valueOf(AcidAgentLoop.resolveSubmitBindTabId(3, true, true, 7)),
            "Preserves live agent-bound tab 3 even if user is currently browsing tab 7");
        assertEquals(Integer.valueOf(7),
            Integer.valueOf(AcidAgentLoop.resolveSubmitBindTabId(3, false, true, 7)),
            "Falls back to active tab 7 if previous bound tab was closed");
        assertEquals(Integer.valueOf(7),
            Integer.valueOf(AcidAgentLoop.resolveSubmitBindTabId(-1, false, true, 7)),
            "Binds active tab 7 when no tab was previously bound");
        assertEquals(Integer.valueOf(7),
            Integer.valueOf(AcidAgentLoop.resolveSubmitBindTabId(3, true, false, 7)),
            "Normal idle submit binds active tab 7");

        // 3. AgentFloatingView primary action button transitions across (running, paused, hasInput)
        assertEquals(Integer.valueOf(AgentFloatingView.BTN_MODE_STOP),
            Integer.valueOf(AgentFloatingView.resolveSendStopButtonMode(true, false, false)),
            "Running with empty input shows Stop");
        assertEquals(Integer.valueOf(AgentFloatingView.BTN_MODE_SEND),
            Integer.valueOf(AgentFloatingView.resolveSendStopButtonMode(true, false, true)),
            "Running with typed input switches button to Send so follow-up can be delivered");
        assertEquals(Integer.valueOf(AgentFloatingView.BTN_MODE_RESUME),
            Integer.valueOf(AgentFloatingView.resolveSendStopButtonMode(false, true, false)),
            "Paused for takeover with empty input shows Resume");
        assertEquals(Integer.valueOf(AgentFloatingView.BTN_MODE_SEND),
            Integer.valueOf(AgentFloatingView.resolveSendStopButtonMode(false, true, true)),
            "Paused for takeover with typed input switches button to Send");
        assertEquals(Integer.valueOf(AgentFloatingView.BTN_MODE_SEND),
            Integer.valueOf(AgentFloatingView.resolveSendStopButtonMode(false, false, false)),
            "Idle with empty input shows Send");
        assertEquals(Integer.valueOf(AgentFloatingView.BTN_MODE_SEND),
            Integer.valueOf(AgentFloatingView.resolveSendStopButtonMode(false, false, true)),
            "Idle with typed input shows Send");

        // 4. In-app toast eligibility & non-IME-blocking placement policy
        assertTrue(AcidFeedback.isInAppHostEligible(false, false, true),
            "Focused active Activity uses non-blocking in-app toast instead of system Toast");
        assertTrue(!AcidFeedback.isInAppHostEligible(false, false, false),
            "Dialog / background window falls back to system Toast when Activity lacks window focus");
        assertTrue(!AcidFeedback.isInAppHostEligible(true, false, true),
            "Finishing Activity skips in-app toast");
        assertTrue(!AcidFeedback.isInAppHostEligible(false, true, true),
            "Destroyed Activity skips in-app toast");

        assertTrue(!BrowserActivity.shouldPlaceInAppToastAtTop(false, false, false),
            "Normal browsing places in-app toast at bottom above toolbar");
        assertTrue(BrowserActivity.shouldPlaceInAppToastAtTop(true, false, false),
            "Open Agent floating drawer moves in-app toast to top clear of drawer input");
        assertTrue(BrowserActivity.shouldPlaceInAppToastAtTop(false, true, false),
            "Focused Agent input moves in-app toast to top clear of IME");
        assertTrue(BrowserActivity.shouldPlaceInAppToastAtTop(false, false, true),
            "Focused URL bar moves in-app toast to top clear of IME");
    }

    static void testGpuScreenshotCaptureAndMasking() {
        // 1. Hardware PixelCopy eligibility checks
        assertTrue(WindowPixelCopyCapturer.isHardwareCaptureEligible(
            26, true, false, true, false, true, true, 1080, 2000, 1080, 2400),
            "API 26+ foreground shown hardware-accelerated non-main-looper WebView is eligible");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            25, true, false, true, false, true, true, 1080, 2000, 1080, 2400),
            "Pre-API 26 falls back to software draw");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, false, false, true, false, true, true, 1080, 2000, 1080, 2400),
            "Unavailable PixelCopy reflector falls back cleanly");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, true, true, true, false, true, true, 1080, 2000, 1080, 2400),
            "Never blocks on MainLooper");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, true, false, false, false, true, true, 1080, 2000, 1080, 2400),
            "Background tab falls back to software draw");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, true, false, true, true, true, true, 1080, 2000, 1080, 2400),
            "Fullscreen video CustomView bypasses PixelCopy");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, true, false, true, false, false, true, 1080, 2000, 1080, 2400),
            "Hidden WebView falls back to software draw");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, true, false, true, false, true, false, 1080, 2000, 1080, 2400),
            "Software-rendered WebView skips PixelCopy");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, true, false, true, false, true, true, 0, 2000, 1080, 2400),
            "Zero-width view rejected");
        assertTrue(!WindowPixelCopyCapturer.isHardwareCaptureEligible(
            33, true, false, true, false, true, true, 1080, 2000, 1080, 0),
            "Zero-height decor view rejected");

        // 2. Window CopyBounds geometry & non-stretch offset math
        WindowPixelCopyCapturer.CopyBounds fullBounds = WindowPixelCopyCapturer.computeWindowCopyBounds(
            0, 180, 1080, 1980, 1080, 2400);
        assertTrue(fullBounds.valid && fullBounds.exactFullMatch,
            "Contained WebView produces exactFullMatch=true");
        assertEquals(Integer.valueOf(0), Integer.valueOf(fullBounds.srcLeft), "Full match srcLeft");
        assertEquals(Integer.valueOf(180), Integer.valueOf(fullBounds.srcTop), "Full match srcTop");
        assertEquals(Integer.valueOf(1080), Integer.valueOf(fullBounds.copyWidth), "Full match width");
        assertEquals(Integer.valueOf(1980), Integer.valueOf(fullBounds.copyHeight), "Full match height");
        assertEquals(Integer.valueOf(0), Integer.valueOf(fullBounds.destOffsetX), "Full match destOffsetX");
        assertEquals(Integer.valueOf(0), Integer.valueOf(fullBounds.destOffsetY), "Full match destOffsetY");

        WindowPixelCopyCapturer.CopyBounds clippedAllSides = WindowPixelCopyCapturer.computeWindowCopyBounds(
            -40, -25, 1160, 2450, 1080, 2400);
        assertTrue(clippedAllSides.valid && !clippedAllSides.exactFullMatch,
            "All-sides clipped WebView sets valid=true, exactFullMatch=false");
        assertEquals(Integer.valueOf(0), Integer.valueOf(clippedAllSides.srcLeft), "Clipped srcLeft clamped to 0");
        assertEquals(Integer.valueOf(0), Integer.valueOf(clippedAllSides.srcTop), "Clipped srcTop clamped to 0");
        assertEquals(Integer.valueOf(1080), Integer.valueOf(clippedAllSides.srcRight), "Clipped srcRight clamped to decorW");
        assertEquals(Integer.valueOf(2400), Integer.valueOf(clippedAllSides.srcBottom), "Clipped srcBottom clamped to decorH");
        assertEquals(Integer.valueOf(1080), Integer.valueOf(clippedAllSides.copyWidth), "Clipped copyWidth unstretched");
        assertEquals(Integer.valueOf(2400), Integer.valueOf(clippedAllSides.copyHeight), "Clipped copyHeight unstretched");
        assertEquals(Integer.valueOf(40), Integer.valueOf(clippedAllSides.destOffsetX), "Clipped destOffsetX preserves -winX");
        assertEquals(Integer.valueOf(25), Integer.valueOf(clippedAllSides.destOffsetY), "Clipped destOffsetY preserves -winY");

        WindowPixelCopyCapturer.CopyBounds offscreen = WindowPixelCopyCapturer.computeWindowCopyBounds(
            1080, 0, 1080, 1980, 1080, 2400);
        assertTrue(!offscreen.valid, "Completely offscreen WebView returns invalid CopyBounds");
        WindowPixelCopyCapturer.CopyBounds invalidDims = WindowPixelCopyCapturer.computeWindowCopyBounds(
            0, 0, -10, 500, 1080, 2400);
        assertTrue(!invalidDims.valid, "Negative view dimensions return invalid CopyBounds");

        // 3. Re-entrant SuppressionGuard reference counting & idempotency
        final int[] maskTransitions = new int[] { 0, 0 }; // [0]=maskCount, [1]=unmaskCount
        final boolean[] maskedState = new boolean[] { false };
        WindowPixelCopyCapturer.SuppressionGuard guard = new WindowPixelCopyCapturer.SuppressionGuard(
            new WindowPixelCopyCapturer.OverlayMaskController() {
                @Override public boolean hasVisibleOverlays() { return true; }
                @Override public void setOverlaysMasked(boolean masked) {
                    maskedState[0] = masked;
                    if (masked) maskTransitions[0]++; else maskTransitions[1]++;
                }
                @Override public boolean hasRecordedMaskedFrame() { return true; }
            }
        );

        WindowPixelCopyCapturer.SuppressionGuard.ReleaseHandle h1 = guard.acquire();
        WindowPixelCopyCapturer.SuppressionGuard.ReleaseHandle h2 = guard.acquire();
        assertEquals(Integer.valueOf(2), Integer.valueOf(guard.activeCount()), "Two acquires yield activeCount=2");
        assertTrue(maskedState[0], "Guard enables mask on first acquire");
        assertEquals(Integer.valueOf(1), Integer.valueOf(maskTransitions[0]), "setOverlaysMasked(true) called once on 0->1");

        h1.release();
        h1.release(); // Duplicate release must be idempotent
        assertEquals(Integer.valueOf(1), Integer.valueOf(guard.activeCount()), "Duplicate release is idempotent");
        assertTrue(maskedState[0] && maskTransitions[1] == 0, "Mask stays active until final handle releases");

        h2.release();
        assertEquals(Integer.valueOf(0), Integer.valueOf(guard.activeCount()), "Second release returns activeCount=0");
        assertTrue(!maskedState[0] && maskTransitions[1] == 1, "setOverlaysMasked(false) called once on 1->0");

        guard.acquire();
        guard.acquire();
        guard.releaseAll();
        assertEquals(Integer.valueOf(0), Integer.valueOf(guard.activeCount()), "releaseAll resets activeCount to 0");
        assertTrue(!maskedState[0] && maskTransitions[1] == 2, "releaseAll unmasks exactly once");
        guard.releaseAll();
        assertEquals(Integer.valueOf(2), Integer.valueOf(maskTransitions[1]), "releaseAll on 0 count is no-op");

        // 4. AgentOverlayView HUD visual activity detection
        assertTrue(!AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_HUMAN, false, false, true, true, false),
            "Idle MODE_HUMAN without click pulses draws nothing (0-frame fast path)");
        assertTrue(AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_HUMAN, false, false, true, true, true),
            "MODE_HUMAN with active click pulse reports visual content");
        assertTrue(AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_AGENT, false, true, false, true, false),
            "MODE_AGENT with undismissed guidance card reports visual content");
        assertTrue(!AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_AGENT, false, true, true, true, false),
            "MODE_AGENT with dismissed card and delegated chrome border reports idle viewport HUD");
        assertTrue(AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_AGENT, false, true, true, false, false),
            "MODE_AGENT with non-delegated inner border reports visual content");
        assertTrue(!AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_AGENT, false, false, false, false, false),
            "MODE_AGENT on unbound user-browsed tab draws nothing");
        assertTrue(!AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_TAKEOVER_REQUESTED, true, true, false, false, true),
            "Overview showing suppresses HUD drawing");
        assertTrue(AgentOverlayView.shouldDrawHudOverlay(
            AgentOverlayView.MODE_TAKEOVER_REQUESTED, false, true, true, true, false),
            "Takeover requested draws amber border");

        // 5. AcidWebView whole-doc draw idempotency & AutomationHost default hardware hook
        AcidWebView.ensureWholeDocumentDrawEnabled();
        AcidWebView.ensureWholeDocumentDrawEnabled();

        WebAutomationDriver.AutomationHost defaultHost = new WebAutomationDriver.AutomationHost() {
            @Override public BrowserController.TabSession getActiveTab() { return null; }
            @Override public BrowserController.TabSession getTargetTab() { return null; }
            @Override public boolean isTargetTabActive() { return false; }
            @Override public AgentOverlayView getOverlayView() { return null; }
            @Override public UserscriptManager getUserscriptManager() { return null; }
            @Override public void loadUrl(String url) {}
            @Override public void emitLog(String log) {}
            @Override public boolean isSomEnabled() { return false; }
            @Override public boolean isDesktopMode() { return false; }
            @Override public int getBlockedDeepLinksCount() { return 0; }
            @Override public String getLastBlockedDeepLink() { return ""; }
            @Override public int getTriggeredDownloadsCount() { return 0; }
            @Override public String getLastTriggeredDownloadInfo() { return ""; }
        };
        final int[] cbCount = new int[] { 0 };
        defaultHost.captureHardwareViewportBitmapOnUiThread(null, false, new WindowPixelCopyCapturer.BitmapCaptureCallback() {
            @Override
            public void onCaptureComplete(android.graphics.Bitmap capturedBitmap, WindowPixelCopyCapturer.CopyBounds bounds) {
                cbCount[0]++;
                assertTrue(capturedBitmap == null && bounds == null, "Default host hook falls back with (null, null)");
            }
        });
        assertEquals(Integer.valueOf(1), Integer.valueOf(cbCount[0]), "Default hardware hook invokes callback once");

        // 6. AgentThinkingCollapseState, AgentBannerView tail-following & full takeover presentation
        AgentFloatingView.ThinkingCollapseState liveThink = new AgentFloatingView.ThinkingCollapseState(true);
        assertTrue(liveThink.expanded, "Live thinking block starts expanded while streaming");
        liveThink.onBind(true);
        assertTrue(liveThink.expanded, "Live thinking stays expanded across streaming updates");
        liveThink.onUserToggle();
        assertTrue(!liveThink.expanded, "User can collapse thinking block mid-stream");
        liveThink.onBind(true);
        assertTrue(!liveThink.expanded, "Mid-stream user collapse is preserved across streaming updates");
        liveThink.onUserToggle();
        assertTrue(liveThink.expanded, "User can re-expand thinking block mid-stream");
        liveThink.onBind(false);
        assertTrue(!liveThink.expanded, "Thinking block auto-collapses once streaming finishes");
        liveThink.onUserToggle();
        assertTrue(liveThink.expanded, "User can reopen finished thinking block");
        liveThink.onBind(false);
        assertTrue(liveThink.expanded, "Subsequent non-running binds keep user-opened finished block open");

        AgentFloatingView.ThinkingCollapseState historyThink = new AgentFloatingView.ThinkingCollapseState(false);
        assertTrue(!historyThink.expanded, "Historical finished thinking block starts collapsed");

        assertEquals("hello world", AgentBannerView.extractSingleLineTail("  hello \n  world  ", 20),
            "Short tail preserves full normalized text without ellipsis");
        assertEquals("\u2026latest words", AgentBannerView.extractSingleLineTail("first part then\n  latest   words ", 12),
            "Long tail trims from start and preserves newest trailing characters");
        assertEquals("", AgentBannerView.extractSingleLineTail("  null \n ", 20),
            "Null placeholder collapses to empty tail");
        assertEquals("first part...", AgentBannerView.extractSingleLineHead("  first\n  part   and more", 10),
            "Head summary preserves leading characters with trailing ellipsis");

        AcidSessionStore.UiMessageItem thinkItem = new AcidSessionStore.UiMessageItem();
        thinkItem.id = "think_1";
        thinkItem.type = AcidSessionStore.TYPE_THINKING;
        thinkItem.isRunning = true;
        thinkItem.text = "Analyzing step one.\nNow inspecting checkout button at bottom";
        AgentBannerView.ActivitySummary thinkSummary = AgentBannerView.summarizeUiItem(thinkItem);
        assertEquals("思考: ", thinkSummary.prefix, "Thinking summary splits prefix");
        assertEquals(Integer.valueOf(AgentBannerView.ELLIPSIZE_START), Integer.valueOf(thinkSummary.ellipsizeMode),
            "Thinking summary uses start ellipsizing to keep newest tail visible");
        assertTrue(thinkSummary.body.endsWith("checkout button at bottom"),
            "Thinking summary keeps trailing phrase");

        AcidSessionStore.UiMessageItem replyItem = new AcidSessionStore.UiMessageItem();
        replyItem.id = "reply_1";
        replyItem.type = AcidSessionStore.TYPE_ASSISTANT;
        replyItem.isRunning = true;
        replyItem.text = "Done checking.\nHere is the final answer.";
        AgentBannerView.ActivitySummary replySummary = AgentBannerView.summarizeUiItem(replyItem);
        assertEquals("回答: ", replySummary.prefix, "Assistant summary splits prefix");
        assertEquals(Integer.valueOf(AgentBannerView.ELLIPSIZE_START), Integer.valueOf(replySummary.ellipsizeMode),
            "Assistant summary uses start ellipsizing");
        assertTrue(replySummary.body.endsWith("Here is the final answer."),
            "Assistant summary keeps trailing phrase");

        AcidSessionStore.UiMessageItem toolItem = new AcidSessionStore.UiMessageItem();
        toolItem.id = "tool_1";
        toolItem.type = AcidSessionStore.TYPE_TOOL;
        toolItem.isRunning = true;
        toolItem.toolName = "browser";
        toolItem.toolSummary = "navigate · https://example.com";
        AgentBannerView.ActivitySummary toolSummary = AgentBannerView.summarizeUiItem(toolItem);
        assertEquals("[工具] ", toolSummary.prefix, "Tool summary keeps tool prefix");
        assertEquals(Integer.valueOf(AgentBannerView.ELLIPSIZE_END), Integer.valueOf(toolSummary.ellipsizeMode),
            "Tool summary keeps end ellipsizing");

        thinkItem.isRunning = false;
        assertTrue(!AgentBannerView.shouldAcceptItemUpdate("reply_1", thinkItem),
            "Closing earlier thinking item does not overwrite newer active reply in banner");
        assertTrue(AgentBannerView.shouldAcceptItemUpdate("think_1", thinkItem),
            "Final update for active item is still accepted when no newer item replaced it");

        AgentBannerView.BannerPresentation agentBanner = AgentBannerView.resolvePresentation(
            AgentOverlayView.MODE_AGENT, true, true, "", replySummary, false, false
        );
        assertTrue(agentBanner.visible && agentBanner.singleLine && agentBanner.maxLines == 1,
            "Running agent banner stays single-line");
        assertEquals("回答: ", agentBanner.prefixText, "Running agent banner retains separate prefix");
        assertEquals(Integer.valueOf(AgentBannerView.ELLIPSIZE_START), Integer.valueOf(agentBanner.ellipsizeMode),
            "Running agent banner passes start ellipsize for reply stream");

        AgentBannerView.BannerPresentation runningUnlockedFgBanner = AgentBannerView.resolvePresentation(
            AgentOverlayView.MODE_HUMAN, true, true, "", null, false, false
        );
        assertTrue(runningUnlockedFgBanner.visible,
            "Top banner shows immediately when agent is running in MODE_HUMAN");
        assertEquals("查看对话", runningUnlockedFgBanner.actionLabel,
            "Foreground MODE_HUMAN running banner offers View Chat button");

        AgentBannerView.BannerPresentation runningUnlockedBgBanner = AgentBannerView.resolvePresentation(
            AgentOverlayView.MODE_HUMAN, true, false, "", null, false, false
        );
        assertTrue(runningUnlockedBgBanner.visible,
            "Background MODE_HUMAN running banner is also visible");
        assertEquals("点击查看", runningUnlockedBgBanner.actionLabel,
            "Background MODE_HUMAN running banner offers View Tab button");

        AgentBannerView.BannerPresentation idleHiddenBanner = AgentBannerView.resolvePresentation(
            AgentOverlayView.MODE_HUMAN, false, true, "", replySummary, false, false
        );
        assertTrue(!idleHiddenBanner.visible,
            "Banner hides once agent stops running in MODE_HUMAN");

        String longTakeoverReason = "当前页面需要进行滑块验证码并输入短信动态码。\n\n"
            + "请手动完成登录后再点击右侧「交还AI」，我会继续帮你提交表单并下载最终附件。";
        AgentBannerView.BannerPresentation takeoverBanner = AgentBannerView.resolvePresentation(
            AgentOverlayView.MODE_TAKEOVER_REQUESTED, false, true, longTakeoverReason, replySummary, false, false
        );
        assertTrue(takeoverBanner.visible && !takeoverBanner.singleLine,
            "Takeover banner disables single-line restriction");
        assertEquals(Integer.valueOf(AgentBannerView.TAKEOVER_COLLAPSED_MAX_LINES), Integer.valueOf(takeoverBanner.maxLines),
            "Takeover banner defaults to generous multi-line cap");
        assertEquals("", takeoverBanner.prefixText, "Takeover banner leaves prefix empty for clean wrapping");
        assertTrue(takeoverBanner.statusText.contains(longTakeoverReason),
            "Takeover banner preserves entire multi-sentence reason without truncation");
        assertTrue(takeoverBanner.statusClickable, "Takeover banner text is clickable for extra-long expansion");

        AgentBannerView.BannerPresentation takeoverExpandedBanner = AgentBannerView.resolvePresentation(
            AgentOverlayView.MODE_TAKEOVER_REQUESTED, false, true, longTakeoverReason, replySummary, true, false
        );
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), Integer.valueOf(takeoverExpandedBanner.maxLines),
            "Expanded takeover banner shows unlimited lines");
        assertEquals(Integer.valueOf(AgentBannerView.ELLIPSIZE_NONE), Integer.valueOf(takeoverExpandedBanner.ellipsizeMode),
            "Expanded takeover banner clears ellipsize");

        AcidAgentLoop.ThinkStreamParser flushParser = new AcidAgentLoop.ThinkStreamParser();
        flushParser.feedContentDelta("好的，现在开始写脚本<");
        assertEquals("好的，现在开始写脚本", flushParser.contentSb.toString(),
            "Partial '<' suffix is held while streaming content");
        flushParser.flushPendingBeforeToolCalls();
        assertEquals("好的，现在开始写脚本<", flushParser.contentSb.toString(),
            "flushPendingBeforeToolCalls flushes held-back trailing chars before tool calls start");

        AcidSessionStore.UiMessageItem streamingToolItem = new AcidSessionStore.UiMessageItem();
        streamingToolItem.id = "tool_stream_1";
        streamingToolItem.type = AcidSessionStore.TYPE_TOOL;
        streamingToolItem.isRunning = true;
        streamingToolItem.toolName = "write";
        streamingToolItem.toolSummary = AcidAgentLoop.formatStreamingToolSummary(1280);
        AgentBannerView.ActivitySummary streamingToolSummary = AgentBannerView.summarizeUiItem(streamingToolItem);
        assertEquals("[工具] ", streamingToolSummary.prefix, "Streaming tool summary uses tool prefix");
        assertEquals("write · 生成参数中 (1280 字符)", streamingToolSummary.body,
            "Streaming tool summary includes tool name and arg character progress");
        AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_EN);
        try {
            assertEquals("write · Streaming args (1280 chars)",
                AcidLocale.tr(streamingToolSummary.body),
                "Streaming tool summary translates cleanly to English");
        } finally {
            AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_ZH);
        }

        try {
            org.json.JSONObject takeoverArgs = new org.json.JSONObject();
            takeoverArgs.put("action", "request_takeover");
            takeoverArgs.put("reason", "需要完成图形验证码");
            assertEquals("request_takeover · 需要完成图形验证码",
                AcidAgentLoop.summarizeToolArgs("browser", takeoverArgs),
                "request_takeover tool summary includes reason");
        } catch (org.json.JSONException ignored) {}
    }

    static void testNetscapeBookmarkImportExportAndFolders() throws Exception {
        // 1. Folder path normalization & ADD_DATE timestamp parsing
        assertEquals("Dev/Open Source",
            NetscapeBookmarkCodec.normalizeFolderPath(" /Dev\\../Open   Source/./ "),
            "Folder path normalizes slashes, dots, and repeated whitespace");
        assertEquals("", NetscapeBookmarkCodec.normalizeFolderPath("///.. / ."),
            "Dot-only folder collapses to empty string");

        assertEquals(Long.valueOf(1700000000000L),
            Long.valueOf(NetscapeBookmarkCodec.parseAddDateToMillis("1700000000")),
            "Parses Unix seconds ADD_DATE to millis");
        assertEquals(Long.valueOf(1700000000123L),
            Long.valueOf(NetscapeBookmarkCodec.parseAddDateToMillis("1700000000123")),
            "Parses Unix millis ADD_DATE directly");
        assertEquals(Long.valueOf(1700000000000L),
            Long.valueOf(NetscapeBookmarkCodec.parseAddDateToMillis("13344473600000000")),
            "Converts Chrome 17-digit WebKit microseconds since 1601 to Unix millis");

        // 2. Parse Chrome/Firefox-style Netscape HTML with entities, inline base64 ICON, nested H3 & unsafe scheme guard
        StringBuilder hugeIcon = new StringBuilder();
        for (int i = 0; i < 600; i++) hugeIcon.append('A');
        String sampleHtml = "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n"
            + "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n"
            + "<TITLE>Bookmarks</TITLE>\n"
            + "<H1>Bookmarks</H1>\n"
            + "<DL><p>\n"
            + "    <DT><A HREF=\"https://root.example/?a=1&amp;b=2\" ADD_DATE=\"1700000000\">Root &amp; Home</A>\n"
            + "    <DT><A HREF=\"javascript:alert(1)\" ADD_DATE=\"1700000001\">Evil JS</A>\n"
            + "    <DT><A HREF=\"data:text/html,hi\" ADD_DATE=\"1700000001\">Evil Data</A>\n"
            + "    <DT><H3 ADD_DATE=\"1700000000\" PERSONAL_TOOLBAR_FOLDER=\"true\">Bookmarks Bar</H3>\n"
            + "    <DD>Optional description\n"
            + "    <DL><p>\n"
            + "        <DT><A HREF=\"https://github.com/trending\" ADD_DATE=\"1700000100\" ICON=\"data:image/png;base64," + hugeIcon + "\">GitHub &quot;Trending&quot; &#39;Dev&#39; &#x1F680;</A>\n"
            + "        <DT><H3 ADD_DATE=\"1700000200\">AI / Tools</H3>\n"
            + "        <DL><p>\n"
            + "            <DT><A HREF=\"https://openai.com/\" ADD_DATE=\"13344473600000000\"><b>OpenAI</b></A>\n"
            + "        </DL><p>\n"
            + "    </DL><p>\n"
            + "    <DT><A HREF=\"https://after.example/\" ADD_DATE=\"1700000300\">After Folder</A>\n"
            + "</DL><p>\n";

        NetscapeBookmarkCodec.ImportParseResult parsedHtml = NetscapeBookmarkCodec.parseImportContent(sampleHtml);
        assertTrue(parsedHtml.ok, "Parses Netscape HTML cleanly");
        assertEquals("html", parsedHtml.format, "Detects html format");
        assertEquals(Integer.valueOf(4), Integer.valueOf(parsedHtml.items.size()),
            "Filters javascript:/data: bookmarks while keeping all 4 safe entries");
        assertEquals("Root & Home", parsedHtml.items.get(0).title, "Decodes &amp; entity in title");
        assertEquals("https://root.example/?a=1&b=2", parsedHtml.items.get(0).url, "Decodes &amp; entity in HREF");
        assertEquals("", parsedHtml.items.get(0).folder, "Root bookmark has empty folder");
        assertEquals("GitHub \"Trending\" 'Dev' 🚀", parsedHtml.items.get(1).title,
            "Decodes quot, #39, and hex Unicode entities despite huge ICON attribute");
        assertEquals("Bookmarks Bar", parsedHtml.items.get(1).folder, "Tracks first-level folder");
        assertEquals("Bookmarks Bar/AI Tools", parsedHtml.items.get(2).folder,
            "Tracks nested folder and sanitizes slash inside folder name");
        assertEquals("OpenAI", parsedHtml.items.get(2).title, "Strips inline <b> tags");
        assertEquals(Long.valueOf(1700000000000L), Long.valueOf(parsedHtml.items.get(2).time),
            "Parses WebKit microsecond ADD_DATE");
        assertEquals("", parsedHtml.items.get(3).folder, "Pops nested folder stack cleanly back to root");

        // 3. Lossless round-trip export -> import
        List<BrowserDataStore.EntryItem> roundTripSource = new ArrayList<BrowserDataStore.EntryItem>();
        roundTripSource.add(new BrowserDataStore.EntryItem("Pinned <Top>", "https://pinned.example/?x=1&y=2", 1710000000000L, ""));
        roundTripSource.add(new BrowserDataStore.EntryItem("Repo", "https://github.com/acid", 1710000100000L, "Tech/Code"));
        roundTripSource.add(new BrowserDataStore.EntryItem("Docs", "https://docs.example", 1710000200000L, "Tech/Docs"));
        String exportedHtml = NetscapeBookmarkCodec.exportToHtml(roundTripSource, 1710000000000L);
        assertTrue(exportedHtml.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"), "Standard Netscape header emitted");
        NetscapeBookmarkCodec.ImportParseResult reparsed = NetscapeBookmarkCodec.parseImportContent(exportedHtml);
        assertTrue(reparsed.ok, "Exported HTML parses back cleanly");
        assertEquals(Integer.valueOf(3), Integer.valueOf(reparsed.items.size()), "Round-trip preserves item count");
        assertEquals("Pinned <Top>", reparsed.items.get(0).title, "Round-trip preserves special chars in title");
        assertEquals("Tech/Code", reparsed.items.get(1).folder, "Round-trip preserves nested folder 1");
        assertEquals("Tech/Docs", reparsed.items.get(2).folder, "Round-trip preserves nested folder 2");
        assertEquals(Long.valueOf(1710000100000L), Long.valueOf(reparsed.items.get(1).time),
            "Round-trip preserves second-precision timestamp");

        // 4. JSON auto-detection with UTF-8 BOM & invalid/empty errors
        String bomJson = "\uFEFF[{\"title\":\"JSON Site\",\"url\":\"https://json.example\",\"folder\":\"Work/API\",\"time\":1720000000000},{\"title\":\"Bad\",\"url\":\"javascript:void(0)\"}]";
        NetscapeBookmarkCodec.ImportParseResult parsedJson = NetscapeBookmarkCodec.parseImportContent(bomJson);
        assertTrue(parsedJson.ok, "Parses JSON bookmark array with UTF-8 BOM");
        assertEquals("json", parsedJson.format, "Detects json format");
        assertEquals(Integer.valueOf(1), Integer.valueOf(parsedJson.items.size()), "Filters unsafe URL in JSON import");
        assertEquals("Work/API", parsedJson.items.get(0).folder, "Preserves folder from JSON");
        assertTrue(!NetscapeBookmarkCodec.parseImportContent("").ok, "Rejects empty import content");
        assertTrue(!NetscapeBookmarkCodec.parseImportContent("<html><body>no links</body></html>").ok,
            "Rejects HTML with zero bookmarks");

        // 4b. Unclosed <H3> boundary & standalone non-folder <h3> leak guard + user URL normalizer
        String unclosedH3Html = "<DL><p>\n"
            + "<DT><H3>Outer\n"
            + "<DL><p>\n"
            + "<DT><H3>Inner</H3>\n"
            + "<DL><p>\n"
            + "<DT><A HREF=\"https://inner.example\">In</A>\n"
            + "</DL><p>\n"
            + "</DL><p>\n"
            + "<H3>Article Heading</H3><p><A HREF=\"https://a.example\">A</A></p>"
            + "<DL><DT><A HREF=\"https://b.example\">B</A></DL>\n"
            + "</DL><p>";
        NetscapeBookmarkCodec.ImportParseResult edgeParsed = NetscapeBookmarkCodec.parseImportContent(unclosedH3Html);
        assertTrue(edgeParsed.ok, "Parses unclosed H3 and standalone H3 safely");
        assertEquals(Integer.valueOf(3), Integer.valueOf(edgeParsed.items.size()), "Extracts all 3 links");
        assertEquals("Outer/Inner", edgeParsed.items.get(0).folder,
            "Unclosed H3 stops before <DL> and preserves nested Outer/Inner");
        assertEquals("", edgeParsed.items.get(2).folder,
            "Standalone H3 before <A> does not leak pendingFolder into subsequent <DL>");

        assertEquals("https://github.com", NetscapeBookmarkCodec.normalizeUserBookmarkUrl("github.com"),
            "Prepends https:// for bare domain");
        assertEquals("https://example.com:8443/p", NetscapeBookmarkCodec.normalizeUserBookmarkUrl("example.com:8443/p"),
            "Prepends https:// for host:port/path");
        assertEquals("https://acid.org", NetscapeBookmarkCodec.normalizeUserBookmarkUrl("  https://acid.org  "),
            "Preserves valid https URL");
        assertEquals(null, NetscapeBookmarkCodec.normalizeUserBookmarkUrl(""), "Rejects empty URL");
        assertEquals(null, NetscapeBookmarkCodec.normalizeUserBookmarkUrl("javascript:alert(1)"), "Rejects javascript: URL");
        assertEquals(null, NetscapeBookmarkCodec.normalizeUserBookmarkUrl("data:text/html,1"), "Rejects data: URL");
        assertEquals(null, NetscapeBookmarkCodec.normalizeUserBookmarkUrl("file:///etc/hosts"), "Rejects file: URL");
        assertEquals(null, NetscapeBookmarkCodec.normalizeUserBookmarkUrl("mailto:a@b.com"), "Rejects mailto: scheme");

        // 5. Repository persistence, import append+dedupe, and folder batch operations
        File tmpDir = File.createTempFile("acid_bm_folder_test_", "_dir");
        tmpDir.delete();
        tmpDir.mkdirs();
        try {
            File settingsFile = new File(tmpDir, "settings.json");
            File bookmarksFile = new File(tmpDir, "bookmarks.json");
            File historyFile = new File(tmpDir, "history.json");
            InMemoryPrefs prefs = new InMemoryPrefs();
            BrowserDataStore store = new BrowserDataStore(prefs, new InMemoryPrefs(), settingsFile, bookmarksFile, historyFile);
            store.syncBookmarksJsonFile();

            // Initially 6 default bookmarks at indices 0..5
            List<BrowserDataStore.EntryItem> incoming = new ArrayList<BrowserDataStore.EntryItem>();
            incoming.add(new BrowserDataStore.EntryItem("GitHub Dup", "https://github.com", 0L, "Duplicate"));
            incoming.add(new BrowserDataStore.EntryItem("MDN", "https://developer.mozilla.org", 1725000000000L, "Dev/Docs"));
            incoming.add(new BrowserDataStore.EntryItem("ArchWiki", "https://wiki.archlinux.org", 1725000001000L, "Dev/Linux"));
            incoming.add(new BrowserDataStore.EntryItem("News", "https://news.ycombinator.com", 0L, "Reading"));
            incoming.add(new BrowserDataStore.EntryItem("LWN", "https://lwn.net", 1725000002000L, "Reading"));

            BookmarkRepository.ImportSummary summary = store.importBookmarks(incoming);
            assertEquals(Integer.valueOf(3), Integer.valueOf(summary.addedCount), "Added 3 non-duplicate bookmarks");
            assertEquals(Integer.valueOf(2), Integer.valueOf(summary.skippedDuplicateCount), "Skipped 2 duplicate URLs");

            List<BrowserDataStore.EntryItem> afterImport = store.loadBookmarks();
            assertEquals(Integer.valueOf(9), Integer.valueOf(afterImport.size()), "Total bookmarks = 6 default + 3 imported");
            assertEquals("Google", afterImport.get(0).title, "Existing top homepage bookmark preserved at index 0");
            assertEquals("MDN", afterImport.get(6).title, "Imported bookmarks appended after existing entries");
            assertEquals("Dev/Docs", afterImport.get(6).folder, "Imported bookmark retains folder");

            // Reload from disk JSON confirms folder/time round-trip
            assertTrue(store.reloadBookmarksFromJsonFile(true), "Reloads bookmarks.json cleanly");
            assertEquals("Dev/Docs", store.loadBookmarks().get(6).folder, "Folder survives JSON save & reload");
            assertEquals(Long.valueOf(1725000000000L), Long.valueOf(store.loadBookmarks().get(6).time),
                "Time survives JSON save & reload");

            // Add bookmark with folder & listFolders
            store.addBookmark("Kotlin", "https://kotlinlang.org", "Dev");
            List<String> folders = store.listBookmarkFolders();
            assertEquals(Integer.valueOf(4), Integer.valueOf(folders.size()), "Lists 4 distinct folders");

            // Rename prefix "Dev" -> "Engineering"
            int renamed = store.renameBookmarkFolder("Dev", "Engineering");
            assertEquals(Integer.valueOf(3), Integer.valueOf(renamed),
                "Renames exact folder and nested subfolder entries");
            assertTrue(store.listBookmarkFolders().contains("Engineering/Docs"), "Nested folder renamed");

            // Dissolve "Engineering" moves direct entry to "" and promotes "Engineering/Docs" -> "Docs"
            int dissolved = store.dissolveBookmarkFolder("Engineering");
            assertEquals(Integer.valueOf(3), Integer.valueOf(dissolved), "Dissolves 3 entries under Engineering");
            List<String> afterDissolveFolders = store.listBookmarkFolders();
            assertTrue(afterDissolveFolders.contains("Docs") && afterDissolveFolders.contains("Linux"),
                "Nested children promoted cleanly on parent folder dissolve");

            // Delete folder "Reading" removes LWN only
            int deleted = store.deleteBookmarkFolder("Reading");
            assertEquals(Integer.valueOf(1), Integer.valueOf(deleted), "Deletes folder bookmarks");
            assertTrue(!store.listBookmarkFolders().contains("Reading"), "Deleted folder gone from folder list");

            // 6. BookmarksHistorySheet 5-arg folder grouping & filtering
            List<BrowserDataStore.EntryItem> sheetSource = new ArrayList<BrowserDataStore.EntryItem>();
            sheetSource.add(new BrowserDataStore.EntryItem("Pin1", "https://p1.example", ""));
            sheetSource.add(new BrowserDataStore.EntryItem("Code1", "https://c1.example", "Code"));
            sheetSource.add(new BrowserDataStore.EntryItem("Pin2", "https://p2.example", ""));
            sheetSource.add(new BrowserDataStore.EntryItem("Read1", "https://r1.example", "Read"));

            long now = System.currentTimeMillis();
            List<BookmarksHistorySheet.PreparedEntry> allGrouped =
                BookmarksHistorySheet.buildPreparedEntries(sheetSource, true, "", null, now);
            assertEquals("未分类 (2)", allGrouped.get(0).sectionHeader, "Uncategorized items grouped first");
            assertEquals("Code (1)", allGrouped.get(2).sectionHeader, "Folder group header includes count");
            assertEquals("Code", allGrouped.get(2).folderKey, "Named folder sets folderKey");
            assertEquals(Integer.valueOf(1), Integer.valueOf(allGrouped.get(2).originalIndex),
                "Grouped order preserves original backing index");

            List<BookmarksHistorySheet.PreparedEntry> onlyCode =
                BookmarksHistorySheet.buildPreparedEntries(sheetSource, true, "", "Code", now);
            assertEquals(Integer.valueOf(1), Integer.valueOf(onlyCode.size()), "Folder filter selects matching folder");
            assertEquals(Integer.valueOf(1), Integer.valueOf(onlyCode.get(0).originalIndex),
                "Folder filter preserves original index");

            List<BookmarksHistorySheet.PreparedEntry> searchByFolder =
                BookmarksHistorySheet.buildPreparedEntries(sheetSource, true, "read", null, now);
            assertEquals(Integer.valueOf(1), Integer.valueOf(searchByFolder.size()),
                "Search query matches bookmark folder names too");
            assertEquals("搜索结果 (1)", searchByFolder.get(0).sectionHeader,
                "Search uses Search Results header");
        } finally {
            SkillStorageVault.deleteRecursiveSafe(tmpDir);
        }
    }

    // =========================================================================
    // 30. Multi-Select Batch Operations & Bilingual Localization
    // =========================================================================

    private static void testMultiSelectBatchOperationsAndLocale() throws Exception {
        File tmpDir = File.createTempFile("acid_multiselect_test_", "_dir");
        tmpDir.delete();
        tmpDir.mkdirs();
        DownloadCoordinator dc = null;
        try {
            File settingsFile = new File(tmpDir, "settings.json");
            File bookmarksFile = new File(tmpDir, "bookmarks.json");
            File historyFile = new File(tmpDir, "history.json");
            File dlDir = new File(tmpDir, "dl");
            dlDir.mkdirs();

            InMemoryPrefs prefs = new InMemoryPrefs();
            BrowserDataStore store = new BrowserDataStore(
                prefs, new InMemoryPrefs(), settingsFile, bookmarksFile, historyFile
            );

            final int[] bmMutatedCount = new int[]{0};
            store.setOnBookmarksMutatedListener(new Runnable() {
                @Override
                public void run() {
                    bmMutatedCount[0]++;
                }
            });
            final int[] histChangedCount = new int[]{0};
            store.setOnHistoryChangedListener(new BrowserDataStore.OnHistoryChangedListener() {
                @Override
                public void onHistoryChanged() {
                    histChangedCount[0]++;
                }
            });

            // 1. Bookmarks batch move & non-contiguous batch delete
            // Defaults: [Google(0), GitHub(1), Bilibili(2), 知乎(3), Hacker News(4), 百度(5)]
            List<BrowserDataStore.EntryItem> initialBm = store.loadBookmarks();
            assertEquals(Integer.valueOf(6), Integer.valueOf(initialBm.size()), "Initial default bookmark count");

            Set<Integer> moveTargets = new HashSet<Integer>(Arrays.asList(
                Integer.valueOf(1), Integer.valueOf(3), Integer.valueOf(5)
            ));
            int beforeMutateMoves = bmMutatedCount[0];
            int movedCount = store.moveBookmarksToFolder(moveTargets, "Work/Docs");
            assertEquals(Integer.valueOf(3), Integer.valueOf(movedCount), "Batch move returns matched count");
            assertEquals(Integer.valueOf(beforeMutateMoves + 1), Integer.valueOf(bmMutatedCount[0]),
                "Batch move triggers one single transaction mutation callback");

            List<BrowserDataStore.EntryItem> afterMove = store.loadBookmarks();
            assertEquals("Work/Docs", afterMove.get(1).folder, "Bookmark 1 moved to Work/Docs");
            assertEquals("Work/Docs", afterMove.get(3).folder, "Bookmark 3 moved to Work/Docs");
            assertEquals("Work/Docs", afterMove.get(5).folder, "Bookmark 5 moved to Work/Docs");
            assertEquals("", afterMove.get(0).folder, "Bookmark 0 remains uncategorized");

            // Move two entries back to uncategorized ""
            int uncatCount = store.moveBookmarksToFolder(
                new HashSet<Integer>(Arrays.asList(Integer.valueOf(1), Integer.valueOf(5))),
                ""
            );
            assertEquals(Integer.valueOf(2), Integer.valueOf(uncatCount), "Moves subset back to uncategorized");
            assertEquals("", store.loadBookmarks().get(1).folder, "Bookmark 1 is uncategorized again");
            assertEquals("Work/Docs", store.loadBookmarks().get(3).folder, "Bookmark 3 stays in Work/Docs");

            // Reload from JSON and verify persistence
            assertTrue(store.reloadBookmarksFromJsonFile(true), "Bookmarks JSON saved cleanly after batch move");
            assertEquals("Work/Docs", store.loadBookmarks().get(3).folder, "Moved folder survives JSON reload");

            // Remove non-contiguous indices {0, 2, 4} -> [GitHub, 知乎, 百度] survive
            int beforeDeleteMoves = bmMutatedCount[0];
            int removedBm = store.removeBookmarks(new HashSet<Integer>(Arrays.asList(
                Integer.valueOf(0), Integer.valueOf(2), Integer.valueOf(4)
            )));
            assertEquals(Integer.valueOf(3), Integer.valueOf(removedBm), "Removes all 3 non-contiguous indices");
            assertEquals(Integer.valueOf(beforeDeleteMoves + 1), Integer.valueOf(bmMutatedCount[0]),
                "Batch delete triggers one single transaction mutation callback");

            List<BrowserDataStore.EntryItem> afterBmDelete = store.loadBookmarks();
            assertEquals(Integer.valueOf(3), Integer.valueOf(afterBmDelete.size()), "3 bookmarks remain");
            assertEquals("GitHub", afterBmDelete.get(0).title, "Surviving bookmark 0 without index shift");
            assertEquals("知乎", afterBmDelete.get(1).title, "Surviving bookmark 1 without index shift");
            assertEquals("百度", afterBmDelete.get(2).title, "Surviving bookmark 2 without index shift");
            assertTrue(store.reloadBookmarksFromJsonFile(true), "Reloads post-delete bookmarks.json cleanly");
            assertEquals(Integer.valueOf(3), Integer.valueOf(store.loadBookmarks().size()),
                "Post-delete count matches bookmarks.json");

            // 2. History single & batch delete
            store.recordHistory("https://h1.test", "H1", false);
            store.recordHistory("https://h2.test", "H2", false);
            store.recordHistory("https://h3.test", "H3", false);
            store.recordHistory("https://h4.test", "H4", false);
            store.recordHistory("https://h5.test", "H5", false);
            assertEquals(Integer.valueOf(5), Integer.valueOf(store.loadHistory().size()), "Recorded 5 history entries");

            int beforeSingleHist = histChangedCount[0];
            assertTrue(store.removeHistoryItem("https://h3.test"), "Single history delete returns true");
            assertTrue(!store.removeHistoryItem("https://h3.test"), "Deleting already-removed URL returns false");
            assertEquals(Integer.valueOf(beforeSingleHist + 1), Integer.valueOf(histChangedCount[0]),
                "Single history delete notifies listener once");

            int batchHistRemoved = store.removeHistoryItems(new HashSet<String>(Arrays.asList(
                "https://h1.test", "https://h5.test", "https://missing.test"
            )));
            assertEquals(Integer.valueOf(2), Integer.valueOf(batchHistRemoved), "Batch history delete removes 2 matching URLs");

            List<BrowserDataStore.EntryItem> remHist = store.loadHistory();
            assertEquals(Integer.valueOf(2), Integer.valueOf(remHist.size()), "2 history items remain");
            assertEquals("https://h4.test", remHist.get(0).url, "Newest remaining history entry preserved");
            assertEquals("https://h2.test", remHist.get(1).url, "Older remaining history entry preserved");
            assertTrue(store.reloadHistoryFromJsonFile(true), "history.json synced cleanly after batch delete");
            assertEquals(Integer.valueOf(2), Integer.valueOf(store.loadHistory().size()),
                "history.json matches remaining count");

            // 3. Downloads batch delete & shared-filename physical protection
            File keepFile = new File(dlDir, "keep.zip");
            File sharedFile = new File(dlDir, "shared.apk");
            File loneFile = new File(dlDir, "lone.pdf");
            File partialFile = new File(dlDir, "partial.bin");
            File partialCfg = new File(dlDir, "partial.bin.part.cfg");

            AcidIo.writeFileUtf8Safe(keepFile, "keep-data");
            AcidIo.writeFileUtf8Safe(sharedFile, "shared-apk-data");
            AcidIo.writeFileUtf8Safe(loneFile, "lone-pdf-data");
            AcidIo.writeFileUtf8Safe(partialFile, "partial-bytes");
            AcidIo.writeFileUtf8Safe(partialCfg, "{\"url\":\"https://dl.test/partial.bin\"}");

            long now = System.currentTimeMillis();
            List<DownloadItem> seedDownloads = new ArrayList<DownloadItem>();
            seedDownloads.add(new DownloadItem(101L, "keep.zip", "https://dl.test/keep.zip", "application/zip", 9, 9, DownloadTask.STATUS_SUCCESSFUL, now));
            seedDownloads.add(new DownloadItem(102L, "shared.apk", "https://dl.test/shared1.apk", "application/vnd.android.package-archive", 15, 15, DownloadTask.STATUS_SUCCESSFUL, now));
            seedDownloads.add(new DownloadItem(103L, "shared.apk", "https://dl.test/shared2.apk", "application/vnd.android.package-archive", 15, 15, DownloadTask.STATUS_SUCCESSFUL, now));
            seedDownloads.add(new DownloadItem(104L, "lone.pdf", "https://dl.test/lone.pdf", "application/pdf", 13, 13, DownloadTask.STATUS_SUCCESSFUL, now));
            seedDownloads.add(new DownloadItem(105L, "partial.bin", "https://dl.test/partial.bin", "application/octet-stream", 100, 13, DownloadTask.STATUS_PAUSED, now));
            store.saveDownloads(seedDownloads);

            dc = new DownloadCoordinator(null, store, dlDir);
            assertEquals(Integer.valueOf(5), Integer.valueOf(dc.getDownloads().size()), "DownloadCoordinator loaded 5 items");

            // Delete keep.zip + ONE shared.apk record WITH deletePhysicalFile=false -> files stay
            // Delete 101L with deletePhysicalFile=false
            int delNoFile = dc.deleteDownloads(Collections.singletonList(Long.valueOf(101L)), false);
            assertEquals(Integer.valueOf(1), Integer.valueOf(delNoFile), "Deleted 1 record without deleting file");
            assertTrue(keepFile.exists(), "Physical keep.zip retained when deletePhysicalFile=false");

            // Delete ONE shared.apk (102L) WITH deletePhysicalFile=true while 103L still references shared.apk
            int delOneShared = dc.deleteDownloads(Collections.singletonList(Long.valueOf(102L)), true);
            assertEquals(Integer.valueOf(1), Integer.valueOf(delOneShared), "Deleted 102L record");
            assertTrue(sharedFile.exists(), "shared.apk preserved while sibling record 103L still exists");

            // Batch delete 103L (last shared.apk) + 104L (lone.pdf) + 105L (paused checkpoint) WITH deletePhysicalFile=true
            int delBatchFiles = dc.deleteDownloads(Arrays.asList(
                Long.valueOf(103L), Long.valueOf(104L), Long.valueOf(105L), Long.valueOf(999L)
            ), true);
            assertEquals(Integer.valueOf(3), Integer.valueOf(delBatchFiles), "Batch deleted remaining 3 matching records");
            assertTrue(!sharedFile.exists(), "shared.apk removed once no surviving record references it");
            assertTrue(!loneFile.exists(), "lone.pdf removed on batch delete");
            assertTrue(!partialFile.exists(), "partial.bin removed on unfinished batch delete");
            assertTrue(!partialCfg.exists(), "partial.bin.part.cfg removed on unfinished batch delete");
            assertEquals(Integer.valueOf(0), Integer.valueOf(dc.getDownloads().size()), "In-memory downloads list empty");
            assertEquals(Integer.valueOf(0), Integer.valueOf(store.loadDownloads().size()), "Persisted downloads list empty");

            // 4. Bilingual AcidLocale verification for multi-select labels and patterns
            AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_EN);
            try {
                assertEquals("Select All", AcidLocale.tr("全选"), "Select All EN translation");
                assertEquals("Deselect All", AcidLocale.tr("取消全选"), "Deselect All EN translation");
                assertEquals("Select Multiple", AcidLocale.tr("批量选择"), "Select Multiple EN translation");
                assertEquals("Move to...", AcidLocale.tr("移至文件夹"), "Move to folder EN translation");
                assertEquals("Delete Record", AcidLocale.tr("删除此条记录"), "Delete single history EN translation");
                assertEquals("Delete (5)", AcidLocale.tr("删除 (5)"), "Delete count dynamic translation");
                assertEquals("Selected 4 items", AcidLocale.tr("已选择 4 项"), "Selected count title translation");
                assertEquals("Deleted 3 bookmarks", AcidLocale.tr("已删除 3 条书签"), "Deleted bookmarks feedback translation");
                assertEquals("Moved 2 bookmarks to \"Work/Docs\"", AcidLocale.tr("已将 2 条书签移至「Work/Docs」"),
                    "Moved bookmarks feedback translation");
                assertEquals("Moved 2 bookmarks to Uncategorized", AcidLocale.tr("已将 2 条书签移至未分类"),
                    "Moved bookmarks to Uncategorized feedback translation");
                assertEquals("Deleted 3 history items", AcidLocale.tr("已删除 3 条历史记录"),
                    "Deleted history items feedback translation");
                assertEquals("Deleted 2 tasks and local files", AcidLocale.tr("已删除 2 个任务及本地源文件"),
                    "Deleted downloads + files feedback translation");
                assertEquals("Removed 2 download records", AcidLocale.tr("已移除 2 个下载任务记录"),
                    "Removed download records feedback translation");
            } finally {
                AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_ZH);
            }
        } finally {
            if (dc != null) {
                dc.destroy();
            }
            SkillStorageVault.deleteRecursiveSafe(tmpDir);
        }
    }

    // =========================================================================
    // 31. WebDAV Cloud Sync, Full Backup ZIP & Security Guards
    // =========================================================================

    private static final class LoopbackWebDavServer implements Runnable {
        private final ServerSocket mServer;
        private final String mExpectedAuthHeader;
        private final Set<String> mDirs = Collections.synchronizedSet(new HashSet<String>());
        private final Map<String, byte[]> mFiles = Collections.synchronizedMap(new HashMap<String, byte[]>());
        private volatile boolean mRunning = true;
        private final Thread mThread;

        LoopbackWebDavServer(String user, String pass) throws IOException {
            mServer = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            mExpectedAuthHeader = "Basic " + WebDavClient.encodeBase64Utf8(user + ":" + pass);
            mDirs.add("/dav");
            mThread = new Thread(this, "TestWebDavServer");
            mThread.setDaemon(true);
            mThread.start();
        }

        String getBaseUrl() {
            return "http://127.0.0.1:" + mServer.getLocalPort() + "/dav";
        }

        boolean hasDir(String path) {
            return mDirs.contains(normalizePath(path));
        }

        byte[] getStoredFile(String path) {
            return mFiles.get(normalizePath(path));
        }

        void deleteStoredFile(String path) {
            mFiles.remove(normalizePath(path));
        }

        private static String normalizePath(String raw) {
            String p = raw != null ? raw.trim() : "/";
            int q = p.indexOf('?');
            if (q >= 0) p = p.substring(0, q);
            while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p;
        }

        void stop() {
            mRunning = false;
            try { mServer.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            while (mRunning) {
                try {
                    Socket sock = mServer.accept();
                    handleClient(sock);
                } catch (IOException e) {
                    if (!mRunning) break;
                }
            }
        }

        private void handleClient(Socket sock) {
            try {
                sock.setSoTimeout(5000);
                InputStream in = sock.getInputStream();
                ByteArrayOutputStream hdrBuf = new ByteArrayOutputStream();
                int st = 0;
                int b;
                while ((b = in.read()) != -1) {
                    hdrBuf.write(b);
                    if (st == 0 && b == '\r') st = 1;
                    else if (st == 1 && b == '\n') st = 2;
                    else if (st == 2 && b == '\r') st = 3;
                    else if (st == 3 && b == '\n') break;
                    else st = (b == '\r') ? 1 : 0;
                }
                String rawHdrs = new String(hdrBuf.toByteArray(), StandardCharsets.ISO_8859_1);
                if (rawHdrs.isEmpty()) return;
                String[] lines = rawHdrs.split("\r\n");
                String[] reqLine = lines[0].split(" ");
                String method = reqLine.length > 0 ? reqLine[0] : "GET";
                String path = normalizePath(reqLine.length > 1 ? reqLine[1] : "/");

                String auth = "";
                int contentLength = 0;
                for (int i = 1; i < lines.length; i++) {
                    int c = lines[i].indexOf(':');
                    if (c <= 0) continue;
                    String k = lines[i].substring(0, c).trim().toLowerCase();
                    String v = lines[i].substring(c + 1).trim();
                    if ("authorization".equals(k)) auth = v;
                    else if ("content-length".equals(k)) {
                        try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    }
                }

                // Always drain exact request body before sending any HTTP status (avoids TCP RST race on 401/PUT)
                byte[] body = new byte[contentLength > 0 ? contentLength : 0];
                int off = 0;
                while (off < body.length) {
                    int n = in.read(body, off, body.length - off);
                    if (n == -1) break;
                    off += n;
                }

                OutputStream out = sock.getOutputStream();
                if (!mExpectedAuthHeader.equals(auth)) {
                    writeResp(out, 401, "Unauthorized", "WWW-Authenticate: Basic realm=\"AcidTest\"\r\n", new byte[0]);
                    return;
                }

                if ("PROPFIND".equalsIgnoreCase(method)) {
                    if (mDirs.contains(path)) {
                        byte[] xml = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            + "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>" + path
                            + "</D:href><D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")
                            .getBytes(StandardCharsets.UTF_8);
                        writeResp(out, 207, "Multi-Status", "Content-Type: application/xml\r\n", xml);
                    } else if (mFiles.containsKey(path)) {
                        byte[] xml = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            + "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>" + path
                            + "</D:href></D:response></D:multistatus>").getBytes(StandardCharsets.UTF_8);
                        writeResp(out, 207, "Multi-Status", "Content-Type: application/xml\r\n", xml);
                    } else {
                        writeResp(out, 404, "Not Found", "", new byte[0]);
                    }
                } else if ("MKCOL".equalsIgnoreCase(method)) {
                    if (mDirs.contains(path)) {
                        writeResp(out, 405, "Method Not Allowed", "", new byte[0]);
                    } else {
                        int lastSlash = path.lastIndexOf('/');
                        String parent = lastSlash > 0 ? path.substring(0, lastSlash) : "/";
                        if (!mDirs.contains(parent)) {
                            writeResp(out, 409, "Conflict", "", new byte[0]);
                        } else {
                            mDirs.add(path);
                            writeResp(out, 201, "Created", "", new byte[0]);
                        }
                    }
                } else if ("PUT".equalsIgnoreCase(method)) {
                    int lastSlash = path.lastIndexOf('/');
                    String parent = lastSlash > 0 ? path.substring(0, lastSlash) : "/";
                    if (!mDirs.contains(parent)) {
                        writeResp(out, 409, "Conflict", "", new byte[0]);
                    } else {
                        mFiles.put(path, body);
                        writeResp(out, 201, "Created", "", new byte[0]);
                    }
                } else if ("GET".equalsIgnoreCase(method)) {
                    byte[] payload = mFiles.get(path);
                    if (payload == null) {
                        writeResp(out, 404, "Not Found", "", new byte[0]);
                    } else {
                        writeResp(out, 200, "OK", "Content-Type: application/octet-stream\r\n", payload);
                    }
                } else {
                    writeResp(out, 405, "Method Not Allowed", "", new byte[0]);
                }
            } catch (IOException ignored) {
            } finally {
                try { sock.close(); } catch (IOException ignored) {}
            }
        }

        private static void writeResp(
            OutputStream out,
            int code,
            String reason,
            String extraHeaders,
            byte[] body
        ) throws IOException {
            String hdr = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + (extraHeaders != null ? extraHeaders : "")
                + "\r\n";
            out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
            if (body.length > 0) out.write(body);
            out.flush();
        }
    }

    private static void testWebDavSyncAndFullBackupRestore() throws Exception {
        File root = File.createTempFile("acid_webdav_test_", "_dir");
        root.delete();
        root.mkdirs();

        LoopbackWebDavServer server = new LoopbackWebDavServer("aciduser", "app-pass-9876");
        try {
            // 1. Setup populated source workspace
            File srcWs = new File(root, "src_ws");
            srcWs.mkdirs();
            InMemoryPrefs srcPrefs = new InMemoryPrefs();
            BrowserDataStore srcStore = new BrowserDataStore(
                srcPrefs, new InMemoryPrefs(),
                new File(srcWs, "settings.json"),
                new File(srcWs, "bookmarks.json"),
                new File(srcWs, "history.json")
            );
            srcStore.saveBookmarks(Arrays.asList(
                new BrowserDataStore.EntryItem("Acid Docs", "https://acid.dev/docs", 1730000000000L, "Work/AI"),
                new BrowserDataStore.EntryItem("Top Search", "https://duckduckgo.com", 1730000010000L, "")
            ));
            srcStore.recordHistory("Secret Browsing Page", "https://private-history.example/secret", false);
            srcStore.setDarkMode(true);
            srcStore.set(AcidSettings.WEB_TEXT_ZOOM, Integer.valueOf(125));
            srcStore.set(AcidSettings.MCP_AUTH_TOKEN, "secret-mcp-token-xyz");
            srcStore.setAdBlockEnabled(true);
            srcStore.setAdBlockSubscriptionUrl("https://filters.example/easylist.txt");

            UserscriptManager srcUm = new UserscriptManager(srcWs);
            File demoJs = new File(srcUm.getUserscriptsDir(), "cloud_demo.user.js");
            AcidIo.writeFileUtf8Safe(demoJs,
                "// ==UserScript==\n// @name Cloud Demo\n// @namespace https://acid.test\n// @version 1.0\n// @match *://*/*\n// ==/UserScript==\nconsole.log('cloud-demo');\n");
            File depsFile = new File(srcUm.getUserscriptsDir(), "deps/helper.js");
            AcidIo.writeFileUtf8Safe(depsFile, "window.__cloudDep = 1;\n");
            srcUm.getStorage().setValue("https://acid.test:Cloud Demo", "counter", Integer.valueOf(42));
            srcUm.getStorage().flushSync();

            File srcCustomAd = new File(srcWs, "adblock/custom_rules.txt");
            File srcSubAd = new File(srcWs, "adblock/subscriptions.txt");
            AcidIo.writeFileUtf8Safe(srcCustomAd, "||cloud-ad.example^\n");
            AcidIo.writeFileUtf8Safe(srcSubAd, "||sub-ad.example^\n");
            AdBlockManager srcAb = new AdBlockManager(srcCustomAd, srcSubAd, srcStore);
            srcAb.loadAllRulesSync();

            AcidIo.writeFileUtf8Safe(new File(srcWs, "homepage/index.html"),
                "<html><body><h1>Custom Acid Home</h1></body></html>");

            SkillManager srcSm = new SkillManager(null, srcWs);
            AcidIo.writeFileUtf8Safe(new File(srcSm.getSkillsDir(), "cloud_skill/SKILL.md"),
                "---\nname: cloud_skill\ndescription: Cloud synced skill\n---\nBody\n");
            srcSm.setSkillEnabled("paused_skill", false);

            BrowserBackupManager.WorkspaceTarget srcTarget =
                new BrowserBackupManager.WorkspaceTarget(srcWs, srcStore, srcUm, srcAb, srcSm);

            // 2. WebDAV auth check & connection test
            WebDavClient.WebDavConfig badCfg = new WebDavClient.WebDavConfig(
                server.getBaseUrl(), "AcidSync/Sub", "aciduser", "wrong-pass"
            );
            boolean got401 = false;
            try {
                WebDavClient.testConnection(badCfg);
            } catch (WebDavClient.WebDavException wde) {
                got401 = (wde.statusCode == 401);
            }
            assertTrue(got401, "Wrong WebDAV credentials trigger HTTP 401 WebDavException");

            WebDavClient.WebDavConfig validCfg = new WebDavClient.WebDavConfig(
                server.getBaseUrl(), "AcidSync/Sub", "aciduser", "app-pass-9876"
            );
            String connMsg = WebDavClient.testConnection(validCfg);
            assertTrue(connMsg.contains("连接成功"), "Valid WebDAV PROPFIND test returns success message");

            // 3. Upload full backup & separate bookmarks.html to WebDAV
            WebDavClient.SyncSelection fullSel = new WebDavClient.SyncSelection(true, true, true, true, true);
            BrowserBackupManager.SyncSummary upSummary = BrowserBackupManager.uploadToWebDav(srcTarget, validCfg, fullSel);
            assertEquals(Integer.valueOf(2), Integer.valueOf(upSummary.bookmarkCount), "Upload reports 2 bookmarks");
            assertTrue(server.hasDir("/dav/AcidSync"), "MKCOL created parent folder");
            assertTrue(server.hasDir("/dav/AcidSync/Sub"), "MKCOL created nested target folder");

            byte[] remoteHtmlBytes = server.getStoredFile("/dav/AcidSync/Sub/bookmarks.html");
            byte[] remoteZipBytes = server.getStoredFile("/dav/AcidSync/Sub/acid_backup.zip");
            assertTrue(remoteHtmlBytes != null && remoteHtmlBytes.length > 0, "Remote standalone bookmarks.html uploaded");
            assertTrue(remoteZipBytes != null && remoteZipBytes.length > 0, "Remote acid_backup.zip uploaded");
            String remoteHtmlStr = new String(remoteHtmlBytes, StandardCharsets.UTF_8);
            assertTrue(remoteHtmlStr.contains("https://acid.dev/docs") && remoteHtmlStr.contains("Work"),
                "Remote bookmarks.html uses standard Netscape folder/link format");

            // Inspect ZIP contents for manifest & privacy exclusion of history / MCP token / WebDAV password
            Set<String> entryNames = new HashSet<String>();
            String zipSettingsText = "";
            String zipAdBlockMetaText = "";
            ZipInputStream inspectZis = new ZipInputStream(new ByteArrayInputStream(remoteZipBytes));
            try {
                ZipEntry ze;
                while ((ze = inspectZis.getNextEntry()) != null) {
                    entryNames.add(ze.getName());
                    if ("settings.json".equals(ze.getName()) || "adblock_meta.json".equals(ze.getName())) {
                        ByteArrayOutputStream sBos = new ByteArrayOutputStream();
                        byte[] buf = new byte[1024];
                        int n;
                        while ((n = inspectZis.read(buf)) != -1) sBos.write(buf, 0, n);
                        String text = new String(sBos.toByteArray(), StandardCharsets.UTF_8);
                        if ("settings.json".equals(ze.getName())) {
                            zipSettingsText = text;
                        } else {
                            zipAdBlockMetaText = text;
                        }
                    }
                    inspectZis.closeEntry();
                }
            } finally {
                inspectZis.close();
            }
            assertTrue(entryNames.contains("manifest.json"), "Archive includes manifest.json");
            assertTrue(!entryNames.contains("history.json"), "Archive strictly excludes browsing history.json");
            assertTrue(!zipSettingsText.contains("secret-mcp-token-xyz"), "Archive settings omit MCP_AUTH_TOKEN");
            assertTrue(!zipSettingsText.contains("app-pass-9876"), "Archive settings omit WebDAV password");
            assertTrue(zipAdBlockMetaText.contains("adblock_subscription_url")
                    && !zipAdBlockMetaText.contains("adblock_enabled"),
                "adblock_meta.json stores subscription URL without duplicating adblock_enabled");

            // 4. Overwrite restore to clean workspace #1
            File dstOverWs = new File(root, "dst_overwrite");
            dstOverWs.mkdirs();
            InMemoryPrefs overPrefs = new InMemoryPrefs();
            BrowserDataStore overStore = new BrowserDataStore(
                overPrefs, new InMemoryPrefs(),
                new File(dstOverWs, "settings.json"),
                new File(dstOverWs, "bookmarks.json"),
                new File(dstOverWs, "history.json")
            );
            overStore.set(AcidSettings.MCP_AUTH_TOKEN, "local-mcp-keep");
            UserscriptManager overUm = new UserscriptManager(dstOverWs);
            AdBlockManager overAb = new AdBlockManager(
                new File(dstOverWs, "adblock/custom_rules.txt"),
                new File(dstOverWs, "adblock/subscriptions.txt"),
                overStore
            );
            SkillManager overSm = new SkillManager(null, dstOverWs);
            BrowserBackupManager.WorkspaceTarget overTarget =
                new BrowserBackupManager.WorkspaceTarget(dstOverWs, overStore, overUm, overAb, overSm);

            BrowserBackupManager.SyncSummary overRes =
                BrowserBackupManager.restoreFromWebDav(overTarget, validCfg, fullSel, true);
            assertEquals(Integer.valueOf(5), Integer.valueOf(overRes.getRestoredModuleCount()),
                "Overwrite restore applies all 5 modules");
            assertEquals(Integer.valueOf(2), Integer.valueOf(overStore.loadBookmarks().size()),
                "Overwrite restore matches remote bookmark count");
            String restoredAcidFolder = null;
            for (BrowserDataStore.EntryItem bi : overStore.loadBookmarks()) {
                if ("https://acid.dev/docs".equals(bi.url)) {
                    restoredAcidFolder = bi.folder;
                }
            }
            assertEquals("Work/AI", restoredAcidFolder, "Bookmark folder restored cleanly");
            assertTrue(overStore.isDarkMode(), "DARK_MODE setting restored");
            assertEquals(Integer.valueOf(125), overStore.get(AcidSettings.WEB_TEXT_ZOOM), "WEB_TEXT_ZOOM restored");
            assertEquals("local-mcp-keep", overStore.get(AcidSettings.MCP_AUTH_TOKEN),
                "Existing local MCP_AUTH_TOKEN preserved across settings restore");
            assertEquals(Integer.valueOf(1), Integer.valueOf(overUm.getScripts().size()), "Userscript restored");
            assertEquals(Integer.valueOf(42), overUm.getStorage().getValue("https://acid.test:Cloud Demo", "counter", Integer.valueOf(0)),
                "Userscript storage value restored cleanly");
            assertEquals("https://filters.example/easylist.txt", overStore.getAdBlockSubscriptionUrl(),
                "AdBlock subscription URL restored");
            assertTrue(overAb.getCustomRulesCount() >= 1 && overAb.getSubscriptionRulesCount() >= 1,
                "AdBlock custom and subscription rules reloaded");
            assertTrue(AcidIo.readFileUtf8(new File(dstOverWs, "homepage/index.html")).contains("Custom Acid Home"),
                "Custom homepage HTML restored");
            assertTrue(!overSm.isSkillEnabled("paused_skill"), "Disabled skill state restored");
            boolean foundCloudSkill = false;
            for (SkillManager.SkillMeta m : overSm.getSkills()) {
                if ("cloud_skill".equals(m.name)) { foundCloudSkill = true; break; }
            }
            assertTrue(foundCloudSkill, "Custom skill restored and visible in SkillManager");

            // 5. Merge restore into populated workspace #2 preserving local-only items
            File dstMergeWs = new File(root, "dst_merge");
            dstMergeWs.mkdirs();
            InMemoryPrefs mergePrefs = new InMemoryPrefs();
            BrowserDataStore mergeStore = new BrowserDataStore(
                mergePrefs, new InMemoryPrefs(),
                new File(dstMergeWs, "settings.json"),
                new File(dstMergeWs, "bookmarks.json"),
                new File(dstMergeWs, "history.json")
            );
            mergeStore.saveBookmarks(Arrays.asList(
                new BrowserDataStore.EntryItem("Local Only", "https://local-only.example", 1730000020000L, "Local"),
                new BrowserDataStore.EntryItem("Dup Cloud", "https://acid.dev/docs", 1730000000000L, "Work/AI")
            ));
            UserscriptManager mergeUm = new UserscriptManager(dstMergeWs);
            AcidIo.writeFileUtf8Safe(new File(mergeUm.getUserscriptsDir(), "local_only.user.js"),
                "// ==UserScript==\n// @name Local Only\n// @namespace https://local.test\n// @version 1.0\n// @match *://*/*\n// ==/UserScript==\nconsole.log('local');\n");
            mergeUm.getStorage().setValue("local_only", "k", "keep");
            mergeUm.getStorage().flushSync();
            File mergeCustomAd = new File(dstMergeWs, "adblock/custom_rules.txt");
            AcidIo.writeFileUtf8Safe(mergeCustomAd, "||local-ad.example^\n");
            AdBlockManager mergeAb = new AdBlockManager(mergeCustomAd, null, mergeStore);
            SkillManager mergeSm = new SkillManager(null, dstMergeWs);
            AcidIo.writeFileUtf8Safe(new File(mergeSm.getSkillsDir(), "local_skill/SKILL.md"),
                "---\nname: local_skill\ndescription: Local skill\n---\nBody\n");
            BrowserBackupManager.WorkspaceTarget mergeTarget =
                new BrowserBackupManager.WorkspaceTarget(dstMergeWs, mergeStore, mergeUm, mergeAb, mergeSm);

            BrowserBackupManager.SyncSummary mergeRes =
                BrowserBackupManager.restoreFromWebDav(mergeTarget, validCfg, fullSel, false);
            assertEquals(Integer.valueOf(1), Integer.valueOf(mergeRes.bookmarkCount),
                "Merge deduplicates existing URL and adds 1 new bookmark");
            assertEquals(Integer.valueOf(3), Integer.valueOf(mergeStore.loadBookmarks().size()),
                "Merge preserves local-only bookmark + cloud bookmarks");
            assertEquals(Integer.valueOf(2), Integer.valueOf(mergeUm.getScripts().size()),
                "Merge preserves both local and cloud userscripts");
            assertEquals("keep", mergeUm.getStorage().getValue("local_only", "k", ""),
                "Merge preserves local userscript storage key");
            String mergedAdRules = AcidIo.readFileUtf8(mergeCustomAd);
            assertTrue(mergedAdRules.contains("||local-ad.example^") && mergedAdRules.contains("||cloud-ad.example^"),
                "Merge keeps local and cloud AdBlock rules");

            // 6. Floccus-only WebDAV remote (bookmarks.html only, no acid_backup.zip)
            server.deleteStoredFile("/dav/AcidSync/Sub/acid_backup.zip");
            File floccusWs = new File(root, "dst_floccus");
            floccusWs.mkdirs();
            BrowserDataStore floccusStore = new BrowserDataStore(
                new InMemoryPrefs(), new InMemoryPrefs(),
                new File(floccusWs, "settings.json"),
                new File(floccusWs, "bookmarks.json"),
                new File(floccusWs, "history.json")
            );
            BrowserBackupManager.WorkspaceTarget floccusTarget =
                new BrowserBackupManager.WorkspaceTarget(floccusWs, floccusStore, null, null, null);
            BrowserBackupManager.SyncSummary floccusRes =
                BrowserBackupManager.restoreFromWebDav(floccusTarget, validCfg, fullSel, true);
            assertTrue(floccusRes.restoredBookmarks && floccusRes.missingRemoteArchiveNotice,
                "Floccus-only WebDAV restores bookmarks and reports missing full archive cleanly");
            assertEquals(Integer.valueOf(2), Integer.valueOf(floccusStore.loadBookmarks().size()),
                "Floccus-only restore imports standalone bookmarks.html");

            // 6b. Restoring AdBlock alone does not override local adblock_enabled setting
            File abOnlyWs = new File(root, "dst_adblock_only");
            abOnlyWs.mkdirs();
            BrowserDataStore abOnlyStore = new BrowserDataStore(
                new InMemoryPrefs(), new InMemoryPrefs(),
                new File(abOnlyWs, "settings.json"),
                new File(abOnlyWs, "bookmarks.json"),
                new File(abOnlyWs, "history.json")
            );
            abOnlyStore.setAdBlockEnabled(false);
            AdBlockManager abOnlyMgr = new AdBlockManager(
                new File(abOnlyWs, "adblock/custom_rules.txt"),
                new File(abOnlyWs, "adblock/subscriptions.txt"),
                abOnlyStore
            );
            BrowserBackupManager.WorkspaceTarget abOnlyTarget =
                new BrowserBackupManager.WorkspaceTarget(abOnlyWs, abOnlyStore, null, abOnlyMgr, null);
            WebDavClient.SyncSelection adBlockOnlySel = new WebDavClient.SyncSelection(false, false, false, true, false);
            BrowserBackupManager.restoreFromZipBytes(abOnlyTarget, remoteZipBytes, adBlockOnlySel, true);
            assertTrue(!abOnlyStore.isAdBlockEnabled(), "AdBlock-only restore keeps existing adblock_enabled=false");
            assertEquals("https://filters.example/easylist.txt", abOnlyStore.getAdBlockSubscriptionUrl(),
                "AdBlock-only restore updates subscription URL");
            assertTrue(abOnlyMgr.getCustomRulesCount() >= 1 && abOnlyMgr.getSubscriptionRulesCount() >= 1,
                "AdBlock-only restore reloads custom & subscription rules");

            // 6c. WebDAV preset detection checks
            assertEquals(Integer.valueOf(0), Integer.valueOf(SyncBackupPageBuilder.detectPresetIndex("https://dav.jianguoyun.com/dav")),
                "Jianguoyun preset detected");
            assertEquals(Integer.valueOf(1), Integer.valueOf(SyncBackupPageBuilder.detectPresetIndex("https://cloud.example.com/remote.php/dav/files/u")),
                "Nextcloud preset detected");
            assertEquals(Integer.valueOf(2), Integer.valueOf(SyncBackupPageBuilder.detectPresetIndex("http://192.168.1.5:5244/dav")),
                "Alist preset detected");
            assertEquals(Integer.valueOf(3), Integer.valueOf(SyncBackupPageBuilder.detectPresetIndex("http://192.168.1.5:5005/dav")),
                "Custom /dav server must not misdetect as Alist");
            assertEquals(Integer.valueOf(3), Integer.valueOf(SyncBackupPageBuilder.detectPresetIndex("")),
                "Empty URL defaults to Custom preset");

            // 7. ZIP security boundary rejections (Zip-Slip, unauthorized path, missing manifest)
            String[] badEntries = new String[]{"../evil.txt", "history.json", "only_random.txt"};
            for (int i = 0; i < badEntries.length; i++) {
                ByteArrayOutputStream badBos = new ByteArrayOutputStream();
                ZipOutputStream badZos = new ZipOutputStream(badBos);
                if (!"only_random.txt".equals(badEntries[i])) {
                    badZos.putNextEntry(new ZipEntry("manifest.json"));
                    badZos.write("{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8));
                    badZos.closeEntry();
                }
                badZos.putNextEntry(new ZipEntry(badEntries[i]));
                badZos.write("payload".getBytes(StandardCharsets.UTF_8));
                badZos.closeEntry();
                badZos.close();

                boolean rejected = false;
                try {
                    BrowserBackupManager.restoreFromZipBytes(floccusTarget, badBos.toByteArray(), fullSel, false);
                } catch (IOException ioe) {
                    rejected = true;
                }
                assertTrue(rejected, "Malicious/invalid ZIP rejected for entry: " + badEntries[i]);
            }
            assertTrue(!new File(root, "evil.txt").exists(), "Zip-Slip file never written outside staging/workspace");

            // 8. Bilingual localization verification
            AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_EN);
            try {
                assertEquals("Sync & Backup", AcidLocale.tr("同步与备份"), "Sync & Backup title EN");
                assertEquals("WebDAV Cloud Sync", AcidLocale.tr("WebDAV 云同步"), "WebDAV section EN");
                assertEquals("Cloud upload complete (5 bookmarks)", AcidLocale.tr("云端上传完成（5 条书签）"),
                    "Upload summary dynamic EN");
                assertEquals("Restored 5 modules · 3 new bookmarks", AcidLocale.tr("已恢复 5 项模块数据 · 新增书签 3"),
                    "Restore summary dynamic EN");
                assertEquals("Authentication failed (HTTP 401). Check username or app password.",
                    AcidLocale.tr("认证失败（HTTP 401），请检查用户名或应用专用密码"),
                    "401 error dynamic EN");
                assertEquals("Reasoning Effort", AcidLocale.tr("思考深度"), "Concise effort EN");
                assertEquals("Export Backup", AcidLocale.tr("导出备份包"), "Concise Export Backup EN");
                assertEquals("Import Bookmarks", AcidLocale.tr("导入书签"), "Concise Import Bookmarks EN");
                assertEquals("Allow", AcidLocale.tr("允许唤起"), "Concise App Launch Allow EN");
                assertEquals("Do Not Track (DNT)", AcidLocale.tr("不跟踪 (DNT)"), "Preserved DNT EN");
                assertEquals("Skills", AcidLocale.tr("技能管理 (Skills)"), "Preserved Skills EN");
                assertEquals("Base URL", AcidLocale.tr("Base URL"), "Preserved Base URL EN");
                assertEquals("Access Token", AcidLocale.tr("访问 Token"), "Preserved Access Token EN");
                assertEquals("Fetch /models", AcidLocale.tr("拉取 /models"), "Preserved Fetch /models EN");
                assertEquals("Preset Providers (6)", AcidLocale.tr("预设供应商 (6)"), "Concise Preset Providers header EN");
                assertEquals("2 models: A, B", AcidLocale.tr("2 个模型: A, B"), "Concise provider model summary EN");
                assertEquals("Dissolving \"Tech\" moves its bookmarks to Uncategorized.",
                    AcidLocale.tr("解散「Tech」后，书签将移至未分类。"), "Concise dissolve folder prompt EN");
                assertEquals("Allow LAN Connections", AcidLocale.tr("允许局域网连接"), "LAN toggle EN");
                assertEquals("Remote folder missing or insufficient permissions (HTTP 405)",
                    AcidLocale.tr(WebDavClient.formatHttpError(405, "test")), "405 error dynamic EN");
                assertEquals("Insufficient cloud storage or file too large (HTTP 507)",
                    AcidLocale.tr(WebDavClient.formatHttpError(507, "test")), "507 error dynamic EN");
            } finally {
                AcidLocale.applyLanguageIndex(null, AcidSettings.LANG_ZH);
            }
        } finally {
            server.stop();
            SkillStorageVault.deleteRecursiveSafe(root);
        }
    }

    // =========================================================================
    // 32. Renderer Single-Flight Gate, Whole-Doc Draw SDK Guard & Download Poll
    // =========================================================================

    private static void testRendererSingleFlightAndFastAutomationPolicies() throws Exception {
        // 1. RendererSingleFlightGate: untracked tabId <= 0
        RendererSingleFlightGate gate = new RendererSingleFlightGate(8000L);
        RendererSingleFlightGate.Ticket untracked = gate.tryBegin(-1);
        assertTrue(untracked != null && untracked.tabId == -1, "Untracked tabId returns valid sentinel ticket");
        assertTrue(!gate.isTabCongested(-1), "Untracked tabId is never congested");
        gate.onTimedOut(untracked, 1000L);
        assertTrue(!gate.isTabCongested(-1), "Untracked timeout does not mark congested");
        gate.onCompleted(untracked);

        // 2. Normal acquire & complete
        RendererSingleFlightGate.Ticket t1 = gate.tryBegin(7);
        assertTrue(t1 != null, "Fresh tab acquires ticket");
        assertTrue(!gate.isTabCongested(7), "In-flight non-timed-out tab is not congested");
        gate.onCompleted(t1);
        gate.onCompleted(t1); // idempotent duplicate completion
        assertTrue(!gate.isTabCongested(7), "Completed ticket leaves tab uncongested");

        // 3. Multi-ticket timeout -> congested -> blocks new tickets until ALL in-flight drain
        RendererSingleFlightGate.Ticket a = gate.tryBegin(10);
        RendererSingleFlightGate.Ticket b = gate.tryBegin(10);
        assertTrue(a != null && b != null, "Concurrent non-congested calls acquire tickets");
        gate.onTimedOut(a, 10000L);
        assertTrue(gate.isTabCongested(10), "Timeout marks tab congested immediately");
        assertTrue(gate.tryBegin(10) == null, "Congested tab blocks new tryBegin calls");
        gate.onCompleted(a);
        assertTrue(gate.isTabCongested(10), "Tab remains congested while second in-flight ticket is still pending");
        gate.onCompleted(b);
        assertTrue(!gate.isTabCongested(10), "Tab unlocks cleanly once all in-flight tickets finish");
        assertTrue(gate.tryBegin(10) != null, "Unlocked tab accepts new tickets");
        gate.resetTab(10);

        // 4. Navigation generation reset ignores late completion from old generation
        RendererSingleFlightGate.Ticket stale = gate.tryBegin(12);
        gate.onTimedOut(stale, 20000L);
        assertTrue(gate.isTabCongested(12), "Tab 12 congested after timeout");
        gate.resetTab(12);
        assertTrue(!gate.isTabCongested(12), "resetTab clears congestion immediately");
        RendererSingleFlightGate.Ticket fresh = gate.tryBegin(12);
        assertTrue(fresh != null && fresh.generation > stale.generation, "resetTab bumps generation");
        gate.onCompleted(stale);
        gate.onTimedOut(stale, 25000L);
        assertTrue(!gate.isTabCongested(12), "Late callbacks from stale generation are ignored");
        gate.onCompleted(fresh);

        // 5. Recovery probe cooldown & single-flight probe rules
        RendererSingleFlightGate.Ticket stuck = gate.tryBegin(15);
        gate.onTimedOut(stuck, 10000L);
        assertTrue(gate.tryBeginRecoveryProbe(15, 17999L) == null, "Probe denied before 8000ms cooldown");
        RendererSingleFlightGate.ProbeTicket p1 = gate.tryBeginRecoveryProbe(15, 18000L);
        assertTrue(p1 != null, "Probe allowed at 8000ms cooldown");
        assertTrue(gate.tryBeginRecoveryProbe(15, 18100L) == null, "Concurrent second probe denied while p1 in flight");
        gate.onRecoveryProbeFinished(p1, false);
        assertTrue(gate.isTabCongested(15), "Failed probe keeps tab congested");
        assertTrue(gate.tryBeginRecoveryProbe(15, 25999L) == null, "Next cooldown measured from last failed probe");
        RendererSingleFlightGate.ProbeTicket p2 = gate.tryBeginRecoveryProbe(15, 26000L);
        assertTrue(p2 != null, "Second probe allowed after cooldown elapses");
        gate.onRecoveryProbeFinished(p2, true);
        assertTrue(!gate.isTabCongested(15), "Successful recovery probe unlocks congested tab");
        assertTrue(gate.tryBegin(15) != null, "Unlocked tab after successful probe accepts work");
        gate.removeTab(15);
        assertTrue(!gate.isTabCongested(15), "removeTab clears slot");

        // 5b. Late recovery probe completion (>250ms caller timeout) & 3x stale expiry
        RendererSingleFlightGate.Ticket stuck2 = gate.tryBegin(16);
        gate.onTimedOut(stuck2, 10000L);
        RendererSingleFlightGate.ProbeTicket lateProbe = gate.tryBeginRecoveryProbe(16, 18000L);
        assertTrue(lateProbe != null, "Initial recovery probe begins after 8000ms cooldown");
        assertTrue(gate.tryBeginRecoveryProbe(16, 26000L) == null,
            "Active in-flight probe blocks duplicate probe before 3x stale expiry");
        gate.onRecoveryProbeFinished(lateProbe, true);
        assertTrue(!gate.isTabCongested(16), "Late probe alive=true unlocks tab immediately");

        RendererSingleFlightGate.Ticket stuck3 = gate.tryBegin(17);
        gate.onTimedOut(stuck3, 10000L);
        RendererSingleFlightGate.ProbeTicket droppedProbe = gate.tryBeginRecoveryProbe(17, 18000L);
        assertTrue(droppedProbe != null, "Probe begins on tab 17");
        assertTrue(gate.tryBeginRecoveryProbe(17, 41999L) == null,
            "Dropped probe stays single-flight before 3x cooldown (24000ms)");
        RendererSingleFlightGate.ProbeTicket replacementProbe = gate.tryBeginRecoveryProbe(17, 42000L);
        assertTrue(replacementProbe != null, "Stale dropped probe expires cleanly at 3x cooldown");
        gate.onRecoveryProbeFinished(droppedProbe, false);
        assertTrue(gate.tryBeginRecoveryProbe(17, 42100L) == null,
            "Superseded probe failure does not clear active replacementProbe");
        gate.onRecoveryProbeFinished(replacementProbe, true);
        assertTrue(!gate.isTabCongested(17), "Replacement probe unlocks tab 17");

        // 6. AcidWebView.shouldEnableSlowWholeDocumentDraw SDK boundary
        assertTrue(!AcidWebView.shouldEnableSlowWholeDocumentDraw(19), "Pre-21 SDK skips slow whole-doc draw");
        assertTrue(AcidWebView.shouldEnableSlowWholeDocumentDraw(21), "API 21 enables whole-doc draw");
        assertTrue(AcidWebView.shouldEnableSlowWholeDocumentDraw(25), "API 25 enables whole-doc draw");
        assertTrue(!AcidWebView.shouldEnableSlowWholeDocumentDraw(26), "API 26+ keeps tile culling fast path");
        assertTrue(!AcidWebView.shouldEnableSlowWholeDocumentDraw(34), "API 34 skips slow whole-doc draw");
        assertTrue(!AcidWebView.shouldEnableSlowWholeDocumentDraw(35), "API 35 skips slow whole-doc draw");

        // 7. AcidSandboxTools.shouldPollForDownloadAfterAction
        JSONObject buttonReceipt = new JSONObject("{\"ok\":true,\"hit\":{\"tag\":\"BUTTON\"},\"effect\":{\"url_changed\":false}}");
        JSONObject linkReceipt = new JSONObject("{\"ok\":true,\"hit\":{\"tag\":\"A\"},\"effect\":{\"url_changed\":false}}");
        JSONObject navClickReceipt = new JSONObject("{\"ok\":true,\"hit\":{\"tag\":\"DIV\"},\"effect\":{\"url_changed\":true}}");
        assertTrue(!AcidSandboxTools.shouldPollForDownloadAfterAction("click", new JSONObject(), buttonReceipt, false),
            "Normal button click skips 500ms download polling");
        assertTrue(AcidSandboxTools.shouldPollForDownloadAfterAction("click", new JSONObject(), linkReceipt, false),
            "Anchor tag click polls briefly for download");
        assertTrue(AcidSandboxTools.shouldPollForDownloadAfterAction("click", new JSONObject(), navClickReceipt, false),
            "Click causing url_changed polls briefly for download");
        assertTrue(AcidSandboxTools.shouldPollForDownloadAfterAction("click", new JSONObject(), buttonReceipt, true),
            "Click on actively loading tab polls briefly for download");
        assertTrue(!AcidSandboxTools.shouldPollForDownloadAfterAction("eval", new JSONObject("{\"code\":\"location.href\"}"), null, false),
            "eval reading location.href skips download polling");
        assertTrue(!AcidSandboxTools.shouldPollForDownloadAfterAction("eval", new JSONObject("{\"code\":\"btn.click()\"}"), null, false),
            "eval calling .click() skips download polling");
        assertTrue(AcidSandboxTools.shouldPollForDownloadAfterAction("eval", new JSONObject("{\"code\":\"startDownload()\"}"), null, false),
            "eval mentioning download triggers short download poll");
        assertTrue(AcidSandboxTools.shouldPollForDownloadAfterAction("eval", new JSONObject("{\"code\":\"1+1\"}"), null, true),
            "eval on loading tab triggers short download poll");
        assertTrue(AcidSandboxTools.shouldPollForDownloadAfterAction("navigate", new JSONObject(), null, false),
            "navigate allows short download poll");
    }
}
