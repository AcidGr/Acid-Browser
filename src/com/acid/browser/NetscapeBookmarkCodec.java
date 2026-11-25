package com.acid.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java codec for importing and exporting bookmarks in standard Netscape Bookmark HTML
 * ({@code <!DOCTYPE NETSCAPE-Bookmark-file-1>}) as well as Acid {@code bookmarks.json} format.
 *
 * <p>Uses a linear {@code O(N)} scanner instead of complex regular expressions so large browser
 * export files containing inline base64 {@code ICON="data:..."} attributes parse fast and safely.
 */
public final class NetscapeBookmarkCodec {

    public static final int MAX_IMPORT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FOLDER_LENGTH = 160;

    private static final long WEBKIT_EPOCH_OFFSET_US = 11644473600000000L;

    public static final class ImportParseResult {
        public final boolean ok;
        public final List<BrowserDataStore.EntryItem> items;
        public final String format;
        public final String errorMessage;

        private ImportParseResult(
            boolean ok,
            List<BrowserDataStore.EntryItem> items,
            String format,
            String errorMessage
        ) {
            this.ok = ok;
            this.items = items != null ? items : Collections.<BrowserDataStore.EntryItem>emptyList();
            this.format = format != null ? format : "";
            this.errorMessage = errorMessage;
        }

        public static ImportParseResult success(List<BrowserDataStore.EntryItem> items, String format) {
            return new ImportParseResult(
                true,
                Collections.unmodifiableList(new ArrayList<BrowserDataStore.EntryItem>(items)),
                format,
                null
            );
        }

        public static ImportParseResult error(String message) {
            return new ImportParseResult(false, null, "", message);
        }
    }

    private static final class FolderNode {
        final String name;
        final List<BrowserDataStore.EntryItem> bookmarks = new ArrayList<BrowserDataStore.EntryItem>();
        final Map<String, FolderNode> children = new LinkedHashMap<String, FolderNode>();

        FolderNode(String name) {
            this.name = name;
        }
    }

    private NetscapeBookmarkCodec() {}

    /**
     * Normalizes a bookmark folder path (e.g. {@code " /Dev\\Open Source / "} -> {@code "Dev/Open Source"}),
     * removing empty segments, dot traversals, control whitespace, and capping length.
     */
    public static String normalizeFolderPath(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace('\\', '/')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim();
        if (cleaned.isEmpty()) return "";

        String[] parts = cleaned.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String seg = collapseSpaces(parts[i]);
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(seg);
        }
        String result = sb.toString();
        if (result.length() > MAX_FOLDER_LENGTH) {
            int end = MAX_FOLDER_LENGTH;
            if (end > 0 && Character.isHighSurrogate(result.charAt(end - 1))) {
                end--;
            }
            result = result.substring(0, end).trim();
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1).trim();
            }
        }
        return result;
    }

    private static String collapseSpaces(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return "";
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean prevSpace = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c <= ' ') {
                if (!prevSpace) {
                    out.append(' ');
                    prevSpace = true;
                }
            } else {
                out.append(c);
                prevSpace = false;
            }
        }
        return out.toString().trim();
    }

    /**
     * Validates that a bookmark URL uses an allowed navigation scheme and contains no control characters.
     */
    public static boolean isSafeBookmarkUrl(String rawUrl) {
        if (rawUrl == null) return false;
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        String lower = trimmed.toLowerCase(Locale.US);
        return lower.startsWith("http://")
            || lower.startsWith("https://")
            || lower.startsWith("ftp://")
            || lower.startsWith("about:")
            || lower.startsWith("acid://");
    }

    /**
     * Normalizes user-entered bookmark URLs (e.g. prepending {@code https://} to bare hosts
     * like {@code github.com}), while rejecting blank values, whitespace, control characters,
     * and unsafe or unsupported schemes ({@code javascript:}, {@code data:}, {@code file:}, etc.).
     */
    public static String normalizeUserBookmarkUrl(String rawUrl) {
        if (rawUrl == null) return null;
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return null;
            }
        }
        if (isSafeBookmarkUrl(trimmed)) {
            return trimmed;
        }
        if (trimmed.indexOf(' ') >= 0 || trimmed.contains("://")) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.US);
        if (lower.startsWith("javascript:")
            || lower.startsWith("vbscript:")
            || lower.startsWith("data:")
            || lower.startsWith("blob:")
            || lower.startsWith("content:")
            || lower.startsWith("intent:")
            || lower.startsWith("file:")) {
            return null;
        }
        int slashIdx = trimmed.indexOf('/');
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx >= 0 && (slashIdx < 0 || colonIdx < slashIdx)) {
            int portEnd = slashIdx >= 0 ? slashIdx : trimmed.length();
            if (colonIdx == 0 || colonIdx + 1 >= portEnd) {
                return null;
            }
            for (int i = colonIdx + 1; i < portEnd; i++) {
                char c = trimmed.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
        }
        String candidate = "https://" + trimmed;
        return isSafeBookmarkUrl(candidate) ? candidate : null;
    }

    public static String escapeHtmlText(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String escapeHtmlAttr(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String decodeHtmlEntities(String s) {
        if (s == null || s.isEmpty() || s.indexOf('&') < 0) {
            return s != null ? s : "";
        }
        int len = s.length();
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i <= 12) {
                    String entity = s.substring(i + 1, semi);
                    String decoded = decodeSingleEntity(entity);
                    if (decoded != null) {
                        sb.append(decoded);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String decodeSingleEntity(String entity) {
        if ("amp".equalsIgnoreCase(entity)) return "&";
        if ("lt".equalsIgnoreCase(entity)) return "<";
        if ("gt".equalsIgnoreCase(entity)) return ">";
        if ("quot".equalsIgnoreCase(entity)) return "\"";
        if ("apos".equalsIgnoreCase(entity) || "#39".equals(entity)) return "'";
        if ("nbsp".equalsIgnoreCase(entity)) return " ";
        if (entity.startsWith("#")) {
            try {
                int cp;
                if (entity.length() > 2 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                    cp = Integer.parseInt(entity.substring(2), 16);
                } else {
                    cp = Integer.parseInt(entity.substring(1), 10);
                }
                if (cp > 0 && cp <= 0x10FFFF && Character.isValidCodePoint(cp)) {
                    return new String(Character.toChars(cp));
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Converts Netscape {@code ADD_DATE} timestamps (Unix seconds, Unix milliseconds,
     * Unix microseconds, or 17-digit Chrome WebKit microseconds since 1601-01-01) to epoch millis.
     */
    public static long parseAddDateToMillis(String raw) {
        if (raw == null) return 0L;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return 0L;
        try {
            long val = Long.parseLong(trimmed);
            if (val <= 0L) return 0L;
            if (val >= WEBKIT_EPOCH_OFFSET_US) {
                long ms = (val - WEBKIT_EPOCH_OFFSET_US) / 1000L;
                return ms > 0L ? ms : 0L;
            }
            if (val >= 100000000000000L) {
                return val / 1000L;
            }
            if (val >= 100000000000L) {
                return val;
            }
            return val * 1000L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public static String exportToHtml(List<BrowserDataStore.EntryItem> items) {
        return exportToHtml(items, System.currentTimeMillis());
    }

    public static String exportToHtml(List<BrowserDataStore.EntryItem> items, long fallbackTimeMillis) {
        long fallbackMs = fallbackTimeMillis > 0L ? fallbackTimeMillis : System.currentTimeMillis();
        long fallbackSec = fallbackMs / 1000L;

        FolderNode root = new FolderNode("");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                BrowserDataStore.EntryItem item = items.get(i);
                if (item == null || item.url == null) continue;
                String url = item.url.trim();
                if (url.isEmpty()) continue;

                String folder = normalizeFolderPath(item.folder);
                FolderNode target = root;
                if (!folder.isEmpty()) {
                    String[] segs = folder.split("/");
                    for (int s = 0; s < segs.length; s++) {
                        String seg = segs[s];
                        if (seg.isEmpty()) continue;
                        FolderNode next = target.children.get(seg);
                        if (next == null) {
                            next = new FolderNode(seg);
                            target.children.put(seg, next);
                        }
                        target = next;
                    }
                }
                target.bookmarks.add(item);
            }
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n");
        sb.append("<!-- This is an automatically generated file.\n");
        sb.append("     It will be read and overwritten.\n");
        sb.append("     DO NOT EDIT! -->\n");
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n");
        sb.append("<TITLE>Bookmarks</TITLE>\n");
        sb.append("<H1>Bookmarks</H1>\n");
        sb.append("<DL><p>\n");
        emitFolderChildren(sb, root, 1, fallbackSec);
        sb.append("</DL><p>\n");
        return sb.toString();
    }

    private static void emitFolderChildren(
        StringBuilder sb,
        FolderNode node,
        int indentLevel,
        long fallbackSec
    ) {
        String indent = repeatIndent(indentLevel);
        for (int i = 0; i < node.bookmarks.size(); i++) {
            BrowserDataStore.EntryItem item = node.bookmarks.get(i);
            long addDateSec = (item.time > 0L ? item.time / 1000L : fallbackSec);
            String title = (item.title != null && !item.title.trim().isEmpty())
                ? item.title.trim()
                : item.url.trim();
            sb.append(indent)
              .append("<DT><A HREF=\"")
              .append(escapeHtmlAttr(item.url.trim()))
              .append("\" ADD_DATE=\"")
              .append(addDateSec)
              .append("\">")
              .append(escapeHtmlText(title))
              .append("</A>\n");
        }
        for (FolderNode child : node.children.values()) {
            sb.append(indent)
              .append("<DT><H3 ADD_DATE=\"")
              .append(fallbackSec)
              .append("\">")
              .append(escapeHtmlText(child.name))
              .append("</H3>\n");
            sb.append(indent).append("<DL><p>\n");
            emitFolderChildren(sb, child, indentLevel + 1, fallbackSec);
            sb.append(indent).append("</DL><p>\n");
        }
    }

    private static String repeatIndent(int level) {
        StringBuilder sb = new StringBuilder(level * 4);
        for (int i = 0; i < level; i++) {
            sb.append("    ");
        }
        return sb.toString();
    }

    /**
     * Auto-detects and parses either Acid JSON bookmark arrays ({@code [...]}) or standard
     * Netscape Bookmark HTML exports.
     */
    public static ImportParseResult parseImportContent(String rawContent) {
        if (rawContent == null) {
            return ImportParseResult.error("文件内容为空");
        }
        String text = rawContent;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return ImportParseResult.error("文件内容为空");
        }
        if (trimmed.startsWith("[")) {
            return parseJsonBookmarks(trimmed);
        }
        return parseNetscapeHtml(text);
    }

    private static ImportParseResult parseJsonBookmarks(String jsonText) {
        try {
            JSONArray arr = new JSONArray(jsonText);
            List<BrowserDataStore.EntryItem> list = new ArrayList<BrowserDataStore.EntryItem>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String rawUrl = o.optString("url", "");
                String url = rawUrl != null ? rawUrl.trim() : "";
                if (!isSafeBookmarkUrl(url)) continue;
                String title = o.optString("title", "").trim();
                if (title.isEmpty()) title = url;
                String folder = normalizeFolderPath(o.optString("folder", ""));
                long time = o.optLong("time", 0L);
                if (time < 0L) time = 0L;
                list.add(new BrowserDataStore.EntryItem(title, url, time, folder));
            }
            if (list.isEmpty()) {
                return ImportParseResult.error("未在 JSON 文件中找到有效书签");
            }
            return ImportParseResult.success(list, "json");
        } catch (Exception e) {
            return ImportParseResult.error("JSON 书签文件格式无效: " + e.getMessage());
        }
    }

    private static ImportParseResult parseNetscapeHtml(String html) {
        List<BrowserDataStore.EntryItem> results = new ArrayList<BrowserDataStore.EntryItem>();
        List<Boolean> dlPushedFolder = new ArrayList<Boolean>();
        List<String> folderStack = new ArrayList<String>();
        String pendingFolder = null;

        int len = html.length();
        int pos = 0;
        while (pos < len) {
            int lt = html.indexOf('<', pos);
            if (lt < 0) break;

            // Skip HTML comments <!-- ... -->
            if (lt + 3 < len && html.charAt(lt + 1) == '!' && html.charAt(lt + 2) == '-' && html.charAt(lt + 3) == '-') {
                int commentEnd = html.indexOf("-->", lt + 4);
                pos = commentEnd >= 0 ? commentEnd + 3 : len;
                continue;
            }

            if (matchesTagPrefix(html, lt, "h3")) {
                int tagClose = findTagEndQuoteAware(html, lt + 3);
                if (tagClose < 0) break;
                int closeTag = findClosingH3OrBoundary(html, tagClose + 1);
                int textEnd;
                int nextPos;
                if (closeTag >= 0 && matchesTagPrefix(html, closeTag, "/h3")) {
                    textEnd = closeTag;
                    int endBracket = html.indexOf('>', closeTag + 4);
                    nextPos = endBracket >= 0 ? endBracket + 1 : closeTag + 4;
                } else if (closeTag >= 0) {
                    textEnd = closeTag;
                    nextPos = closeTag;
                } else {
                    textEnd = len;
                    nextPos = len;
                }
                String rawName = html.substring(tagClose + 1, textEnd);
                String folderSeg = normalizeFolderPath(decodeHtmlEntities(stripInlineTags(rawName))).replace('/', ' ');
                folderSeg = collapseSpaces(folderSeg);
                if (!folderSeg.isEmpty()) {
                    pendingFolder = folderSeg;
                }
                pos = nextPos;
                continue;
            }

            if (matchesTagPrefix(html, lt, "dl")) {
                int tagClose = findTagEndQuoteAware(html, lt + 3);
                if (tagClose < 0) break;
                if (pendingFolder != null && !pendingFolder.isEmpty()) {
                    folderStack.add(pendingFolder);
                    dlPushedFolder.add(Boolean.TRUE);
                    pendingFolder = null;
                } else {
                    dlPushedFolder.add(Boolean.FALSE);
                }
                pos = tagClose + 1;
                continue;
            }

            if (matchesTagPrefix(html, lt, "/dl")) {
                int tagClose = findTagEndQuoteAware(html, lt + 4);
                pendingFolder = null;
                if (!dlPushedFolder.isEmpty()) {
                    boolean pushed = dlPushedFolder.remove(dlPushedFolder.size() - 1).booleanValue();
                    if (pushed && !folderStack.isEmpty()) {
                        folderStack.remove(folderStack.size() - 1);
                    }
                }
                pos = tagClose >= 0 ? tagClose + 1 : lt + 4;
                continue;
            }

            if (matchesTagPrefix(html, lt, "a")) {
                pendingFolder = null;
                int attrStart = lt + 2;
                int tagClose = findTagEndQuoteAware(html, attrStart);
                if (tagClose < 0) break;

                Map<String, String> attrs = parseAttributes(html, attrStart, tagClose);
                int closeTag = findClosingAnchorOrBoundary(html, tagClose + 1);
                int textEnd;
                int nextPos;
                if (closeTag >= 0 && matchesTagPrefix(html, closeTag, "/a")) {
                    textEnd = closeTag;
                    int endBracket = html.indexOf('>', closeTag + 3);
                    nextPos = endBracket >= 0 ? endBracket + 1 : closeTag + 3;
                } else if (closeTag >= 0) {
                    textEnd = closeTag;
                    nextPos = closeTag;
                } else {
                    textEnd = len;
                    nextPos = len;
                }

                String rawHref = attrs.get("href");
                if (rawHref != null) {
                    String href = decodeHtmlEntities(rawHref).trim();
                    if (isSafeBookmarkUrl(href)) {
                        String rawTitle = html.substring(tagClose + 1, textEnd);
                        String title = collapseSpaces(decodeHtmlEntities(stripInlineTags(rawTitle)));
                        if (title.isEmpty()) title = href;
                        long timeMs = parseAddDateToMillis(attrs.get("add_date"));
                        String folder = joinFolderStack(folderStack);
                        results.add(new BrowserDataStore.EntryItem(title, href, timeMs, folder));
                    }
                }
                pos = nextPos;
                continue;
            }

            pos = lt + 1;
        }

        if (results.isEmpty()) {
            return ImportParseResult.error("未在文件中识别到有效的书签记录");
        }
        return ImportParseResult.success(results, "html");
    }

    private static boolean matchesTagPrefix(String html, int ltIndex, String tagLower) {
        int nameStart = ltIndex + 1;
        int tLen = tagLower.length();
        if (nameStart + tLen > html.length()) return false;
        for (int i = 0; i < tLen; i++) {
            char c = html.charAt(nameStart + i);
            char target = tagLower.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if (c != target) return false;
        }
        if (nameStart + tLen == html.length()) return true;
        char next = html.charAt(nameStart + tLen);
        return next <= ' ' || next == '>' || next == '/';
    }

    private static int findTagEndQuoteAware(String html, int from) {
        int len = html.length();
        char quote = 0;
        for (int i = from; i < len; i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else {
                if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findClosingH3OrBoundary(String html, int from) {
        int len = html.length();
        for (int i = from; i < len; i++) {
            char c = html.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
            if (c == '<') {
                if (matchesTagPrefix(html, i, "/h3")
                    || matchesTagPrefix(html, i, "dl")
                    || matchesTagPrefix(html, i, "/dl")
                    || matchesTagPrefix(html, i, "dt")
                    || matchesTagPrefix(html, i, "a")
                    || matchesTagPrefix(html, i, "h3")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findClosingAnchorOrBoundary(String html, int from) {
        int len = html.length();
        for (int i = from; i < len; i++) {
            char c = html.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
            if (c == '<') {
                if (matchesTagPrefix(html, i, "/a")
                    || matchesTagPrefix(html, i, "dt")
                    || matchesTagPrefix(html, i, "dl")
                    || matchesTagPrefix(html, i, "/dl")
                    || matchesTagPrefix(html, i, "h3")
                    || matchesTagPrefix(html, i, "a")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Map<String, String> parseAttributes(String html, int start, int end) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        int i = start;
        while (i < end) {
            while (i < end && html.charAt(i) <= ' ') i++;
            if (i >= end) break;
            if (html.charAt(i) == '/') {
                i++;
                continue;
            }
            int nameStart = i;
            while (i < end) {
                char c = html.charAt(i);
                if (c <= ' ' || c == '=' || c == '/') break;
                i++;
            }
            String attrName = html.substring(nameStart, i).toLowerCase(Locale.US);
            while (i < end && html.charAt(i) <= ' ') i++;
            String attrValue = "";
            if (i < end && html.charAt(i) == '=') {
                i++;
                while (i < end && html.charAt(i) <= ' ') i++;
                if (i < end) {
                    char q = html.charAt(i);
                    if (q == '"' || q == '\'') {
                        i++;
                        int valStart = i;
                        while (i < end && html.charAt(i) != q) i++;
                        attrValue = html.substring(valStart, i);
                        if (i < end) i++;
                    } else {
                        int valStart = i;
                        while (i < end && html.charAt(i) > ' ') i++;
                        attrValue = html.substring(valStart, i);
                    }
                }
            }
            if (!attrName.isEmpty() && !map.containsKey(attrName)) {
                map.put(attrName, attrValue);
            }
        }
        return map;
    }

    private static String stripInlineTags(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('<') < 0) {
            return raw != null ? raw : "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean inTag = false;
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!inTag) {
                if (c == '<') {
                    inTag = true;
                    quote = 0;
                } else {
                    sb.append(c);
                }
            } else {
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else {
                    if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (c == '>') {
                        inTag = false;
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String joinFolderStack(List<String> stack) {
        if (stack == null || stack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(stack.get(i));
        }
        return normalizeFolderPath(sb.toString());
    }
}
