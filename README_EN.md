# Acid Browser

**Acid Browser** is an Android browser built directly on top of the system `WebView` without AndroidX or external SDK dependencies. It integrates an in-app AI Agent loop, a local sandbox workspace, a userscript engine, an ABP-compatible ad blocker, and a local MCP server.

[English](README_EN.md) | [中文说明](README.md)

---

## Design Philosophy

### 1. Clicking and Form Filling Do Not Rely on Screenshot Vision Models

Unlike screenshot-only GUI agents that guess pixel coordinates from images on every step, Acid Browser drives web pages through in-page DOM semantic distillation (`agent_bridge.js`):

- **Semantic Handles (`[e1..eN]`)**: `observe` inspects visible, unoccluded interactive elements in the live DOM and returns compact `[e1..eN]` handles plus a paginated page text excerpt. Text-only LLMs can read the page structure and pick targets directly.
- **Native Touch & Verified DOM Input**: `click` resolves the target element geometry and dispatches native Android `MotionEvent` taps on foreground tabs (or DOM clicks on background tabs), while `fill` updates controlled inputs (React/Vue safe) with readback verification.
- **Closed-Loop Feedback**: Every state-changing `browser` action automatically returns the updated page observation and `[e1..eN]` handle list.

### 2. `screenshot` Was Added Later as an On-Demand Fallback

`screenshot` was added later out of necessity for cases that pure DOM extraction cannot represent (canvas elements, charts, image/CAPTCHA inspection, unlabeled icon-only controls, or layout checks). It automatically aligns coordinates for both mobile and desktop viewports, optionally draws `[e1..eN]` boxes strictly onto the captured image (`som=true`) without modifying the webpage DOM, and can save the image to the sandbox workspace (`save_path`).

### 3. Foreground Anti-Touch Lock & Human Takeover

- While the Agent operates the foreground tab, touches on that WebView and chrome editing controls are locked to avoid accidental interference, while users can still switch to other tabs freely.
- For logins, CAPTCHAs, SMS codes, or payments, the Agent pauses via `request_takeover` (or the user taps **Take Over**). Users can resume with an empty tap or send typed follow-up instructions directly from the Agent drawer.

---

## Browser Features

- **Browsing & Tabs**: Address bar search/navigation (customizable search engines), 2-column thumbnail tab overview (protecting active Agent tabs from accidental closure), per-tab Desktop (`1280px` + Desktop UA) / Mobile toggle.
- **Appearance & Privacy**: Light / OLED Dark theme, fullscreen mode with display-cutout layout support, global webpage text zoom with live preview, Incognito mode, Do Not Track (DNT), image-blocking mode, long-press context menu, and 3-level third-party app launch interception (enforced as block during Agent automation).
- **Ad Blocker**: Built-in rules, online ABP/EasyList subscriptions, and live-reloaded custom rules (`||domain^`, `@@||whitelist^`, `domain##selector`).
- **Userscripts**: Native `==UserScript==` support (`@name`, `@match`, `@exclude`, `@run-at`, `@grant`, `@require`), URL/code import, `.user.js` link interception, per-script toggles, menu commands, and built-in anti-app-hijack script.
- **Download Manager**: Built-in multi-threaded chunked downloader (or system `DownloadManager`), cloud-drive header/cookie handling, file-type filtering, system share, APK install, and update check.
- **Agent Chat, Skills & MCP**: Multi-session chat history, configurable OpenAI-compatible providers (including unauthenticated local endpoints such as Ollama/vLLM), per-session model & reasoning effort selector, up to 5 chat attachments (`uploads/`), Markdown skills (`homepage_dev.md`, `userscript_dev.md`, `cloud_drive_download.md`), and a local JSON-RPC 2.0 MCP server (`GET /sse`, `POST /mcp`, bound to `127.0.0.1:3090` with Bearer auth by default, plus `scripts/acid-mcp-stdio-bridge.py`).

---

## Agent Tools & Actions

Both the in-app Agent and the MCP server share the same tool registry (`AcidSandboxTools`):

- **`browser`**:
  - `observe` (DOM `[e1..eN]` handles & text excerpt; supports `selector`, `offset`/`limit`, `text_offset`/`text_limit`, `viewport_only`)
  - `navigate` (`url`)
  - `click` (`ref` / `text` with optional `exact` / `selector` / `x`, `y`, `coord_space`: `auto` | `screenshot` | `normalized` | `view`)
  - `fill` (`ref` / `selector`, `text`, `clear`, `submit`)
  - `select` (`ref`, `text`)
  - `scroll` (`direction`, `amount`, optional `ref`)
  - `wait` (`condition`: `stable` | `selector` | `text` | `gone` | `networkidle` | `ms`)
  - `eval` (`code`, awaits Promises / `async/await`)
  - `upload` (click target + `file_path` or `file_paths` inside `acid_workspace/`, protected config/state files blocked)
  - `download` (`url`, optional `file_name`, `user_agent`)
  - `set_mode` (`mode`: `desktop` | `mobile`, `som`)
  - `tabs` (`tab_action`: `list` | `new` | `switch` | `close`, `tab_id`, `url`)
  - `back`, `reload`
  - `screenshot` (on-demand visual fallback; optional `som`, `save_path`)
  - `request_takeover` (`reason`; in-app Agent only, disabled over MCP)
- **`read`**: Read line-numbered UTF-8 text files inside `acid_workspace/` (`file_path`, `offset`, `limit`).
- **`write`**: Create or overwrite files in `acid_workspace/` (files under `userscripts/*.user.js` are validated and installed automatically).
- **`edit`**: Exact literal string replacement in existing workspace files (`old_string`, `new_string`, `replace_all`).
- **`bash`**: Run `/system/bin/sh` commands inside `acid_workspace/` (`command`, `timeout_ms`; in-app Agent only, disabled over MCP).

---

## Build & Test

### Prerequisites
- Linux / macOS
- JDK 8+ (`javac`, `java`)
- Node.js (for JS bridge regression tests)
- Android SDK Build Tools (`aapt`, `dx`, `zipalign`, `apksigner`) and `android.jar` (`minSdkVersion` 24)

### Commands
```bash
# Run all regression tests and build signed APK (build/Acid-browser.apk)
./build.sh

# Run JVM and JS bridge regression tests only
./test.sh
```
