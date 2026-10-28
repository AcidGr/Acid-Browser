package com.acid.browser;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * In-Process Tool Catalog & Sandbox Executor for Acid Agent Loop.
 * Provides the 5 core tools modeled after DSH standard/minimal + mobile-use:
 *  1. browser (unified closed-loop web perception & interaction tool)
 *  2. read    (line-numbered UTF-8 file reader inside sandbox)
 *  3. write   (create or overwrite file inside sandbox / userscripts)
 *  4. edit    (exact string replacement inside sandbox files)
 *  5. bash    (execute shell commands with working directory in sandbox)
 */
public class AcidSandboxTools {

    private final BrowserController mController;
    private final File mWorkspaceDir;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public AcidSandboxTools(BrowserController controller) {
        mController = controller;
        mWorkspaceDir = controller.getWorkspaceDir();
        if (!mWorkspaceDir.exists()) {
            mWorkspaceDir.mkdirs();
        }
    }

    public File getWorkspaceDir() {
        return mWorkspaceDir;
    }

    /**
     * Builds the OpenAI-compatible `tools` JSON array for the 5 core tools.
     */
    public static JSONArray buildToolsSchema() {
        JSONArray tools = new JSONArray();
        try {
            // 1. browser (Unified Closed-Loop Web Automation Tool)
            JSONObject browserProps = new JSONObject();
            browserProps.put("action", propEnum(
                "string",
                "Action to perform on the browser:\n" +
                "- 'observe': Inspect current webpage semantic tree ([e1..eN] handles) and text excerpt.\n" +
                "- 'navigate': Load URL in current tab (supports http/https or file:/// inside workspace).\n" +
                "- 'click': Tap element by 'ref' (e.g. 'e3'), visible text ('text', e.g. '消息'/'发送'), CSS selector ('selector'), or pixel coordinates ('x', 'y'). Includes auto-healing if the DOM re-rendered.\n" +
                "- 'fill': Fill text into input/textarea/contenteditable ('ref' or 'selector'). React/Vue safe. Optional 'submit'=true.\n" +
                "- 'select': Choose option in <select> dropdown 'ref' by value or visible label.\n" +
                "- 'scroll': Scroll viewport ('direction': 'down'|'up'|'top'|'bottom', 'amount': 0.8).\n" +
                "- 'wait': Wait for a specific condition ('condition': 'networkidle'|'selector'|'stable'|'text'|'gone'|'ms', optional 'selector', 'text', 'quiet_ms', 'ms', 'timeout_ms'). Returns a receipt {satisfied, timed_out, elapsed_ms, error_code?} followed by the latest page observation. Always check 'satisfied' before proceeding.\n" +
                "- 'eval': Evaluate JavaScript ('code') on current page and return {ok, result, result_type}. Accepts an expression, multiple statements (value of the last statement is returned automatically), or a function body with explicit 'return'. Supports async/await; Promises are awaited. Syntax errors return SYNTAX_ERROR without executing.\n" +
                "- 'set_mode': Switch browser identity ('mode': 'desktop' for 1280px PC view or 'mobile'), or toggle 'som' (true/false).\n" +
                "- 'tabs': Manage tabs ('tab_action': 'list'|'new'|'switch'|'close', optional 'tab_id', 'url').\n" +
                "- 'back': Navigate back in current tab history.\n" +
                "- 'reload': Reload current webpage.\n" +
                "- 'request_takeover': Pause automation loop and ask human user to manually handle login/CAPTCHA ('reason').",
                new String[]{
                    "observe", "navigate", "click", "fill", "select",
                    "scroll", "wait", "eval", "set_mode", "tabs", "back", "reload", "request_takeover"
                }
            ));
            browserProps.put("url", prop("string", "Target URL for 'navigate' or 'tabs' (new)."));
            browserProps.put("ref", prop("string", "Target element handle from observe snapshot (e.g. 'e1', 'e7') for 'click', 'fill', 'select', or container 'scroll'. Supports auto-healing if the DOM refreshed."));
            browserProps.put("text", prop("string", "Text value for 'fill', option value/label for 'select', visible element text for semantic 'click' (e.g. '消息'/'发送'), or expected text for 'wait'."));
            browserProps.put("selector", prop("string", "CSS selector for 'click', 'fill', or 'wait' (e.g. 'a[href*=\"/user/self\"]', 'input[name=\"q\"]')."));
            browserProps.put("exact", prop("boolean", "Whether semantic text matching requires exact full equality (default false)."));
            browserProps.put("condition", prop("string", "Wait condition for 'wait': 'stable' (DOM quiet), 'selector' (element exists), 'text' (text present), 'gone' (element/text disappeared), 'networkidle' (page loaded), 'ms' (fixed sleep)."));
            browserProps.put("quiet_ms", prop("integer", "Quiet threshold in ms without DOM mutations for 'stable' wait (default 300)."));
            browserProps.put("ms", prop("integer", "Sleep duration in milliseconds for 'wait' (condition='ms')."));
            browserProps.put("timeout_ms", prop("integer", "Maximum wait timeout in milliseconds for 'wait' (default 5000)."));
            browserProps.put("clear", prop("boolean", "Whether to clear existing input value before 'fill' (default true)."));
            browserProps.put("submit", prop("boolean", "Whether to press Enter / submit form after 'fill' (default false)."));
            browserProps.put("direction", prop("string", "Scroll direction for 'scroll': 'down', 'up', 'left', 'right', 'top', 'bottom'."));
            browserProps.put("amount", prop("number", "Scroll viewport multiplier for 'scroll' (default 0.75)."));
            browserProps.put("code", prop("string", "JavaScript code expression to evaluate for 'eval'. Async functions and Promises are automatically awaited and resolved."));
            browserProps.put("mode", prop("string", "Browser identity mode for 'set_mode': 'desktop' (1280px PC) or 'mobile'."));
            browserProps.put("som", prop("boolean", "Enable or disable Set-of-Marks visual boxes on webpage."));
            browserProps.put("tab_action", prop("string", "Sub-action for 'tabs': 'list', 'new', 'switch', 'close'."));
            browserProps.put("tab_id", prop("integer", "Target tab ID for 'tabs' switch/close."));
            browserProps.put("x", prop("number", "Raw X coordinate in view pixels for coordinate 'click'."));
            browserProps.put("y", prop("number", "Raw Y coordinate in view pixels for coordinate 'click'."));
            browserProps.put("reason", prop("string", "Explanation shown to the human user when calling 'request_takeover'."));

            tools.put(makeTool(
                "browser",
                "Control the built-in Acid WebView browser. Every state-changing action (navigate, click, fill, select, scroll, set_mode, back, reload) automatically returns the updated page observation snapshot so you can immediately decide the next step.",
                browserProps,
                new String[]{"action"}
            ));

            // 2. read
            JSONObject readProps = new JSONObject();
            readProps.put("file_path", prop("string", "Path to read (relative paths resolve inside the app sandbox workspace, e.g. 'notes.md' or 'userscripts/custom.user.js')."));
            readProps.put("offset", prop("integer", "1-based starting line number (default 1)."));
            readProps.put("limit", prop("integer", "Maximum number of lines to read (default 200)."));
            tools.put(makeTool(
                "read",
                "Read a UTF-8 text file inside the sandbox workspace and return line-numbered content.",
                readProps,
                new String[]{"file_path"}
            ));

            // 3. write
            JSONObject writeProps = new JSONObject();
            writeProps.put("file_path", prop("string", "Path to write (relative to sandbox workspace). Writing to 'userscripts/<name>.user.js' automatically installs a browser userscript plugin."));
            writeProps.put("content", prop("string", "Complete UTF-8 text content to write."));
            tools.put(makeTool(
                "write",
                "Create or overwrite a UTF-8 text file in the sandbox workspace (e.g. HTML reports, JSON data, scripts, or 'userscripts/*.user.js' browser plugins).",
                writeProps,
                new String[]{"file_path", "content"}
            ));

            // 4. edit
            JSONObject editProps = new JSONObject();
            editProps.put("file_path", prop("string", "Path to the existing text file inside the sandbox workspace."));
            editProps.put("old_string", prop("string", "Exact literal text to search for and replace."));
            editProps.put("new_string", prop("string", "Replacement text."));
            editProps.put("replace_all", prop("boolean", "Whether to replace all occurrences (default false)."));
            tools.put(makeTool(
                "edit",
                "Edit an existing UTF-8 text file by replacing literal text.",
                editProps,
                new String[]{"file_path", "old_string", "new_string"}
            ));

            // 5. bash
            JSONObject bashProps = new JSONObject();
            bashProps.put("command", prop("string", "Shell command to execute via /system/bin/sh inside the sandbox workspace directory."));
            bashProps.put("timeout_ms", prop("integer", "Timeout in milliseconds (default 20000)."));
            tools.put(makeTool(
                "bash",
                "Execute a shell command (`sh -c`) with working directory set to the Acid sandbox workspace (`" +
                "/data/user/0/com.acid.browser/files/workspace`). Returns stdout, stderr, and exit code.",
                bashProps,
                new String[]{"command"}
            ));
        } catch (Exception ignored) {}
        return tools;
    }

    /**
     * Builds the standard MCP (Model Context Protocol) tools array.
     * Each tool has {name, description, inputSchema}.
     */
    public static JSONArray buildMcpToolsList() {
        JSONArray openaiTools = buildToolsSchema();
        JSONArray mcpTools = new JSONArray();
        for (int i = 0; i < openaiTools.length(); i++) {
            JSONObject t = openaiTools.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            try {
                JSONObject mcpTool = new JSONObject();
                mcpTool.put("name", fn.optString("name"));
                mcpTool.put("description", fn.optString("description"));
                mcpTool.put("inputSchema", fn.optJSONObject("parameters"));
                mcpTools.put(mcpTool);
            } catch (Exception ignored) {}
        }
        return mcpTools;
    }

    private static JSONObject prop(String type, String description) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("description", description);
        return o;
    }

    private static JSONObject propEnum(String type, String description, String[] values) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("description", description);
        JSONArray arr = new JSONArray();
        for (String v : values) arr.put(v);
        o.put("enum", arr);
        return o;
    }

    private static JSONObject makeTool(String name, String description, JSONObject properties, String[] required) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);

        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", properties);
        if (required != null && required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            params.put("required", req);
        }
        fn.put("parameters", params);

        JSONObject wrapper = new JSONObject();
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        return wrapper;
    }

    // =========================================================================
    // Unified Tool Dispatcher
    // =========================================================================

    public static class ToolExecResult {
        public final String outputText;
        public final boolean requestedTakeover;
        public final String takeoverReason;

        public ToolExecResult(String outputText, boolean requestedTakeover, String takeoverReason) {
            this.outputText = outputText;
            this.requestedTakeover = requestedTakeover;
            this.takeoverReason = takeoverReason;
        }
    }

    public ToolExecResult executeTool(String name, JSONObject args) {
        if (args == null) args = new JSONObject();
        try {
            if ("browser".equals(name)) {
                return executeBrowserTool(args);
            } else if ("read".equals(name)) {
                return new ToolExecResult(executeReadTool(args), false, null);
            } else if ("write".equals(name)) {
                return new ToolExecResult(executeWriteTool(args), false, null);
            } else if ("edit".equals(name)) {
                return new ToolExecResult(executeEditTool(args), false, null);
            } else if ("bash".equals(name)) {
                return new ToolExecResult(executeBashTool(args), false, null);
            } else {
                return new ToolExecResult("Error: Unknown tool '" + name + "'", false, null);
            }
        } catch (Exception e) {
            return new ToolExecResult("Tool execution exception (" + name + "): " + e.getMessage(), false, null);
        }
    }

    // =========================================================================
    // 1. Browser Tool Implementation (Closed-Loop Observation)
    // =========================================================================

    private ToolExecResult executeBrowserTool(final JSONObject args) throws Exception {
        String action = args.optString("action", "observe").toLowerCase().trim();

        if ("observe".equals(action)) {
            boolean som = args.has("som") ? args.optBoolean("som", mController.isSomEnabled()) : mController.isSomEnabled();
            JSONObject obs = mController.observeSync(som, true, false);
            return new ToolExecResult(formatObservationWithActionHeader("OBSERVE", null, obs), false, null);
        }

        if ("navigate".equals(action)) {
            String url = args.optString("url", "");
            if (url.startsWith("workspace://")) {
                File f = resolveSandboxFile(url.substring("workspace://".length()));
                url = "file://" + f.getAbsolutePath();
            } else if (!url.contains("://") && !url.startsWith("about:") && !url.startsWith("javascript:")) {
                // Check if it refers to a local HTML file inside workspace
                File possibleLocal = resolveSandboxFile(url);
                if (possibleLocal.exists() && possibleLocal.isFile()) {
                    url = "file://" + possibleLocal.getAbsolutePath();
                }
            }
            JSONObject obs = mController.navigateSync(url, 3800);
            return new ToolExecResult(formatObservationWithActionHeader("NAVIGATE -> " + url, null, obs), false, null);
        }

        if ("click".equals(action)) {
            JSONObject clickRes;
            if (args.has("x") && args.has("y")) {
                clickRes = mController.clickCoordinateSync((float) args.optDouble("x", 0), (float) args.optDouble("y", 0));
            } else if (args.has("ref") || args.has("text") || args.has("selector")) {
                JSONObject targetOpts = new JSONObject();
                if (args.has("ref")) targetOpts.put("ref", args.optString("ref").trim());
                if (args.has("text")) targetOpts.put("text", args.optString("text").trim());
                if (args.has("selector")) targetOpts.put("selector", args.optString("selector").trim());
                if (args.has("exact")) targetOpts.put("exact", args.optBoolean("exact", false));
                clickRes = mController.clickTargetSync(targetOpts, true);
            } else {
                return new ToolExecResult("Error: browser click requires 'ref' (e.g. 'e1'), semantic 'text', 'selector', or ('x', 'y') coordinates.", false, null);
            }
            Thread.sleep(380); // Let page animations/DOM settle after click
            JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("CLICK", clickRes, obs), false, null);
        }

        if ("fill".equals(action)) {
            String text = args.optString("text", "");
            boolean clear = args.optBoolean("clear", true);
            boolean submit = args.optBoolean("submit", false);
            JSONObject targetOpts = new JSONObject();
            if (args.has("ref")) targetOpts.put("ref", args.optString("ref").trim());
            if (args.has("selector")) targetOpts.put("selector", args.optString("selector").trim());
            JSONObject fillRes = mController.fillTargetSync(targetOpts, text, clear, submit);
            if (submit) {
                Thread.sleep(650);
            } else {
                Thread.sleep(150);
            }
            JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("FILL", fillRes, obs), false, null);
        }

        if ("select".equals(action)) {
            String ref = args.optString("ref", "").trim();
            String text = args.optString("text", "");
            JSONObject selRes = mController.selectOptionSync(ref, text);
            Thread.sleep(200);
            JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SELECT", selRes, obs), false, null);
        }

        if ("scroll".equals(action)) {
            String dir = args.optString("direction", "down");
            double amount = args.optDouble("amount", 0.75);
            String ref = args.optString("ref", null);
            if (ref != null && ref.isEmpty()) ref = null;
            JSONObject scrRes = mController.scrollSync(dir, amount, ref);
            Thread.sleep(250);
            JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SCROLL " + dir, scrRes, obs), false, null);
        }

        if ("wait".equals(action)) {
            String cond = args.optString("condition", "stable");
            String target = args.has("selector") ? args.optString("selector") : (args.has("text") ? args.optString("text") : null);
            long quietMs = args.optLong("quiet_ms", 300);
            long timeoutMs = args.optLong("timeout_ms", 5000);
            if ("ms".equalsIgnoreCase(cond) && args.has("ms")) {
                quietMs = args.optLong("ms", 1000);
            }
            JSONObject waitRes = mController.waitSync(cond, target, quietMs, timeoutMs);
            boolean sat = waitRes.optBoolean("satisfied", false);
            String status = sat ? "✓ SATISFIED" : ("✗ " + waitRes.optString("error_code", "NOT_SATISFIED"));
            String title = "WAIT " + waitRes.optString("condition", cond).toUpperCase()
                + (target != null ? " (\"" + target + "\")" : "")
                + " " + status + " [" + waitRes.optLong("elapsed_ms", 0) + "ms]";
            JSONObject snapshot = waitRes.optJSONObject("snapshot");
            JSONObject receipt = new JSONObject(waitRes.toString());
            receipt.remove("snapshot");
            return new ToolExecResult(formatObservationWithActionHeader(title, receipt, snapshot), false, null);
        }

        if ("eval".equals(action)) {
            String code = args.optString("code", "");
            long timeout = args.optLong("timeout_ms", 10000);
            String raw = mController.evaluateJsAsync(code, timeout);
            return new ToolExecResult("=== [BROWSER ACTION: EVAL] ===\n" + raw, false, null);
        }

        if ("set_mode".equals(action)) {
            final CountDownLatch latch = new CountDownLatch(1);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (args.has("mode")) {
                        String m = args.optString("mode", "mobile").toLowerCase();
                        boolean desktop = "desktop".equals(m) || "pc".equals(m);
                        mController.setDesktopMode(desktop, true);
                    }
                    if (args.has("som")) {
                        mController.setSomEnabled(args.optBoolean("som", false));
                    }
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(600);
            JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader("SET_MODE", null, obs), false, null);
        }

        if ("tabs".equals(action)) {
            final String sub = args.optString("tab_action", "list").toLowerCase();
            final CountDownLatch latch = new CountDownLatch(1);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if ("new".equals(sub)) {
                        mController.createNewTab(args.optString("url", BrowserController.HOME_URL), true);
                    } else if ("switch".equals(sub)) {
                        mController.switchToTab(args.optInt("tab_id", 1));
                    } else if ("close".equals(sub)) {
                        mController.closeTab(args.optInt("tab_id", 1));
                    }
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(400);

            StringBuilder sb = new StringBuilder("=== [BROWSER ACTION: TABS (" + sub.toUpperCase() + ")] ===\n");
            List<BrowserController.TabSession> tabs = mController.getTabs();
            BrowserController.TabSession active = mController.getActiveTab();
            for (BrowserController.TabSession t : tabs) {
                sb.append(active != null && active.id == t.id ? "* " : "  ")
                  .append("[tab_id=").append(t.id).append("] ")
                  .append(t.title).append(" (").append(t.url).append(")\n");
            }
            JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
            sb.append("\n").append(obs.optString("compactText", ""));
            return new ToolExecResult(sb.toString(), false, null);
        }

        if ("back".equals(action) || "reload".equals(action)) {
            final boolean isBack = "back".equals(action);
            final CountDownLatch latch = new CountDownLatch(1);
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    BrowserController.TabSession tab = mController.getActiveTab();
                    if (tab != null) {
                        if (isBack && tab.webView.canGoBack()) {
                            tab.webView.goBack();
                        } else if (!isBack) {
                            tab.webView.reload();
                        }
                    }
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            Thread.sleep(500);
            JSONObject obs = mController.observeSync(mController.isSomEnabled(), true, false);
            return new ToolExecResult(formatObservationWithActionHeader(action.toUpperCase(), null, obs), false, null);
        }

        if ("request_takeover".equals(action)) {
            final String reason = args.optString("reason", "需要人工协助完成当前页面操作（如登录或验证码）");
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    mController.setControlMode(AgentOverlayView.MODE_TAKEOVER_REQUESTED, reason);
                }
            });
            return new ToolExecResult(
                "[HUMAN TAKEOVER REQUESTED] Automation paused. Reason: " + reason,
                true,
                reason
            );
        }

        return new ToolExecResult("Unknown browser action: " + action, false, null);
    }

    private JSONObject cleanReceipt(JSONObject r) {
        if (r == null) return null;
        try {
            JSONObject c = new JSONObject(r.toString());
            c.remove("strategy");
            c.remove("viewX");
            c.remove("viewY");
            c.remove("revisionBefore");
            c.remove("revisionAfter");
            c.remove("wait_satisfied");
            c.remove("checks");
            c.remove("target");
            if (c.optBoolean("satisfied", false)) {
                c.remove("last_check");
                c.remove("quiet_ms");
                c.remove("timeout_ms");
            }
            if (c.has("value")) {
                String v = c.optString("value", "");
                if (v.length() > 80) {
                    c.put("value", v.substring(0, 60) + "... (" + v.length() + " chars)");
                }
            }
            if (c.has("hit") && c.optJSONObject("hit") != null) {
                JSONObject hit = c.optJSONObject("hit");
                hit.remove("rect");
            }
            return c;
        } catch (Exception e) {
            return r;
        }
    }

    private String formatObservationWithActionHeader(String actionTitle, JSONObject receipt, JSONObject obs) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== [BROWSER ACTION: ").append(actionTitle).append("] ===\n");
        if (receipt != null) {
            JSONObject cleaned = cleanReceipt(receipt);
            sb.append("Receipt: ").append(cleaned.toString()).append("\n");
        }
        if (obs != null) {
            String compact = obs.optString("compactText", "");
            if (!compact.isEmpty()) {
                sb.append(compact);
            } else {
                sb.append(obs.toString());
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // 2. Sandbox File Tools: read, write, edit
    // =========================================================================

    private File resolveSandboxFile(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return mWorkspaceDir;
        }
        String p = rawPath.trim();
        File f = p.startsWith("/") ? new File(p) : new File(mWorkspaceDir, p);
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f.getAbsoluteFile();
        }
    }

    private String executeReadTool(JSONObject args) {
        String pathStr = args.optString("file_path", "");
        int offset = Math.max(1, args.optInt("offset", 1));
        int limit = Math.max(1, Math.min(2000, args.optInt("limit", 200)));

        File f = resolveSandboxFile(pathStr);
        if (!f.exists() || !f.isFile()) {
            return "Error: File not found: " + f.getAbsolutePath();
        }
        try {
            String content = readFileUtf8(f);
            String[] lines = content.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(f.getAbsolutePath())
              .append(" (Total lines: ").append(lines.length).append(")\n");
            int startIdx = Math.min(offset - 1, lines.length);
            int endIdx = Math.min(startIdx + limit, lines.length);
            for (int i = startIdx; i < endIdx; i++) {
                sb.append(i + 1).append(": ").append(lines[i]).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private String executeWriteTool(JSONObject args) {
        String pathStr = args.optString("file_path", "");
        String content = args.optString("content", "");
        if (pathStr.trim().isEmpty()) {
            return "Error: file_path is required.";
        }
        File f = resolveSandboxFile(pathStr);
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writeFileUtf8(f, content);
            mController.emitLog("[WRITE] Saved " + f.getName() + " (" + content.length() + " chars)");
            return "Successfully wrote " + content.length() + " characters to " + f.getAbsolutePath() +
                " (Accessible in browser via: file://" + f.getAbsolutePath() + ")";
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    private String executeEditTool(JSONObject args) {
        String pathStr = args.optString("file_path", "");
        String oldStr = args.optString("old_string", "");
        String newStr = args.optString("new_string", "");
        boolean replaceAll = args.optBoolean("replace_all", false);

        File f = resolveSandboxFile(pathStr);
        if (!f.exists() || !f.isFile()) {
            return "Error: File not found: " + f.getAbsolutePath();
        }
        if (oldStr.isEmpty()) {
            return "Error: old_string cannot be empty.";
        }
        try {
            String content = readFileUtf8(f);
            int firstIdx = content.indexOf(oldStr);
            if (firstIdx < 0) {
                return "Error: old_string not found in " + f.getAbsolutePath();
            }
            String updated;
            if (replaceAll) {
                updated = content.replace(oldStr, newStr);
            } else {
                int secondIdx = content.indexOf(oldStr, firstIdx + oldStr.length());
                if (secondIdx >= 0) {
                    return "Error: old_string appears multiple times in " + f.getName() + "; set replace_all=true or provide more context.";
                }
                updated = content.substring(0, firstIdx) + newStr + content.substring(firstIdx + oldStr.length());
            }
            writeFileUtf8(f, updated);
            mController.emitLog("[EDIT] Updated " + f.getName());
            return "Successfully edited " + f.getAbsolutePath();
        } catch (Exception e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    // =========================================================================
    // 3. Sandbox Shell Tool: bash
    // =========================================================================

    private String executeBashTool(JSONObject args) {
        String cmd = args.optString("command", "");
        long timeoutMs = Math.max(1000, Math.min(120000, args.optLong("timeout_ms", 20000)));
        if (cmd.trim().isEmpty()) {
            return "Error: command is empty.";
        }
        mController.emitLog("[BASH] $ " + (cmd.length() > 60 ? cmd.substring(0, 60) + "..." : cmd));

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(mWorkspaceDir);
            pb.redirectErrorStream(true);
            proc = pb.start();

            final InputStream is = proc.getInputStream();
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thread readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            if (baos.size() < 64 * 1024) {
                                baos.write(buf, 0, n);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });
            readerThread.start();

            boolean finished = waitForProcess(proc, timeoutMs);
            if (!finished) {
                proc.destroy();
                return "[timeout after " + timeoutMs + "ms]\n" + new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
            readerThread.join(1000);
            int code = proc.exitValue();
            String out = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            if (out.length() > 16000) {
                out = out.substring(0, 16000) + "\n...[output truncated]...";
            }
            return out + "\n[exit code: " + code + "]";
        } catch (Exception e) {
            return "Error executing bash command: " + e.getMessage() + "\n[exit code: -1]";
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean waitForProcess(Process p, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(50);
            }
        }
        return false;
    }

    private String readFileUtf8(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = fis.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        fis.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void writeFileUtf8(File f, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(content.getBytes(StandardCharsets.UTF_8));
        fos.close();
    }
}
