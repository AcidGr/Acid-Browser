package com.acid.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns arbitrary user-supplied JavaScript (expression, statement list, or function body)
 * into a list of candidate async-function bodies, ordered by preference.
 *
 * The caller injects each candidate in turn; a candidate that fails to parse never executes,
 * so trying several shapes is side-effect free. Order:
 *   1. expression      -> return (CODE\n);
 *   2. statements      -> PREFIX; return (LAST_STATEMENT\n);   (auto-return last statement)
 *   3. raw body        -> CODE                                  (user wrote their own `return`)
 *
 * Pure Java (no Android deps) so it can be unit-tested on the host JVM.
 */
public final class JsEvalShaper {

    public static final String MODE_EXPRESSION = "expression";
    public static final String MODE_STATEMENTS = "statements";
    public static final String MODE_BODY = "body";

    public static final class Candidate {
        public final String mode;
        public final String body;

        Candidate(String mode, String body) {
            this.mode = mode;
            this.body = body;
        }

        @Override
        public String toString() {
            return mode + ": " + body;
        }
    }

    private static final int MAX_STATEMENT_SPLITS = 3;

    private static final String[] NON_VALUE_KEYWORDS = {
        "return", "var", "let", "const", "if", "for", "while", "do", "switch", "try",
        "throw", "function", "class", "break", "continue", "import", "export", "debugger",
        "else", "catch", "finally", "case", "default"
    };

    private JsEvalShaper() {}

    public static List<Candidate> candidates(String code) {
        List<Candidate> out = new ArrayList<Candidate>();
        String src = code == null ? "" : stripTrailing(code);
        if (src.trim().isEmpty()) {
            out.add(new Candidate(MODE_EXPRESSION, "return undefined;"));
            return out;
        }

        // 1. Pure expression. Trailing newline keeps `// comment` endings harmless.
        String exprSrc = stripTrailingSemicolons(src);
        if (!exprSrc.trim().isEmpty()) {
            out.add(new Candidate(MODE_EXPRESSION, "return (" + exprSrc + "\n);"));
        }

        // 2. Statement list with the last value-producing statement auto-returned.
        List<Integer> splits = statementSplitPoints(src);
        int tried = 0;
        for (int i = splits.size() - 1; i >= 0 && tried < MAX_STATEMENT_SPLITS; i--) {
            int at = splits.get(i);
            String head = src.substring(0, at);
            String tail = stripTrailingSemicolons(src.substring(at)).trim();
            if (tail.isEmpty()) continue;
            char first = tail.charAt(0);
            if (".)]},:?=*/%&|^<>".indexOf(first) >= 0) continue; // split landed mid-expression; widen
            if (!looksLikeValueStatement(tail)) break; // last real statement is not a value; fall back to body
            tried++;
            out.add(new Candidate(MODE_STATEMENTS, head + "\nreturn (" + tail + "\n);"));
        }

        // 3. Raw function body.
        out.add(new Candidate(MODE_BODY, src));
        return out;
    }

    // ------------------------------------------------------------------------

    private static boolean looksLikeValueStatement(String stmt) {
        String s = stmt.trim();
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        // Continuation of a previous expression (bad split) or a bare block.
        if (".)]},;:?=*/%&|^<>".indexOf(c) >= 0) return false;
        if (c == '{') return false; // block statement / ambiguous object literal
        for (String kw : NON_VALUE_KEYWORDS) {
            if (s.startsWith(kw)) {
                if (s.length() == kw.length()) return false;
                char n = s.charAt(kw.length());
                if (!Character.isJavaIdentifierPart(n)) return false;
            }
        }
        return true;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static String stripTrailingSemicolons(String s) {
        int end = s.length();
        while (end > 0 && (Character.isWhitespace(s.charAt(end - 1)) || s.charAt(end - 1) == ';')) end--;
        return s.substring(0, end);
    }

    /**
     * Returns offsets where a new top-level statement may begin: right after a top-level `;`,
     * after a top-level `}` that closes a block, and after a top-level newline when the
     * neighbouring tokens don't indicate a continued expression. Strings, template literals
     * (including nested ${...}), comments and regex literals are skipped.
     */
    static List<Integer> statementSplitPoints(String src) {
        List<Integer> points = new ArrayList<Integer>();
        int n = src.length();
        int depth = 0;                  // combined (), [], {} depth in code context
        // Stack of depths at which a template literal's ${ was opened.
        List<Integer> templateStack = new ArrayList<Integer>();
        char prevSig = 0;               // previous significant (non-space, non-comment) char
        String prevWord = "";
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);

            // Comments
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int endIdx = src.indexOf("*/", i + 2);
                i = endIdx < 0 ? n : endIdx + 2;
                continue;
            }

            // Strings
            if (c == '"' || c == '\'') {
                i = skipQuoted(src, i, c);
                prevSig = c;
                prevWord = "";
                continue;
            }
            if (c == '`') {
                int r = skipTemplate(src, i + 1);
                if (r < 0) {
                    // Hit ${ : enter code context inside the template.
                    i = -r;
                    templateStack.add(depth);
                    depth++;
                    prevSig = '{';
                    prevWord = "";
                } else {
                    i = r;
                    prevSig = '`';
                    prevWord = "";
                }
                continue;
            }

            // Regex literal heuristic
            if (c == '/' && regexAllowedAfter(prevSig, prevWord)) {
                i = skipRegex(src, i);
                prevSig = '/';
                prevWord = "";
                continue;
            }

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']') {
                if (depth > 0) depth--;
            } else if (c == '}') {
                if (!templateStack.isEmpty() && templateStack.get(templateStack.size() - 1) == depth - 1) {
                    // Closing a ${...} inside a template literal: resume template scanning.
                    templateStack.remove(templateStack.size() - 1);
                    depth--;
                    int r = skipTemplate(src, i + 1);
                    if (r < 0) {
                        i = -r;
                        templateStack.add(depth);
                        depth++;
                        prevSig = '{';
                    } else {
                        i = r;
                        prevSig = '`';
                    }
                    prevWord = "";
                    continue;
                }
                if (depth > 0) depth--;
                if (depth == 0) points.add(i + 1);
            } else if (c == ';' && depth == 0) {
                points.add(i + 1);
            } else if (c == '\n' && depth == 0) {
                if (newlineEndsStatement(src, i, prevSig)) points.add(i + 1);
            }

            if (Character.isJavaIdentifierPart(c)) {
                int s = i;
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) i++;
                prevWord = src.substring(s, i);
                prevSig = src.charAt(i - 1);
                continue;
            }
            if (!Character.isWhitespace(c)) {
                prevSig = c;
                prevWord = "";
            }
            i++;
        }
        return points;
    }

    private static boolean newlineEndsStatement(String src, int nlIndex, char prevSig) {
        if (prevSig == 0) return false;
        if ("([{,.=+-*/%&|^!~?:<>".indexOf(prevSig) >= 0) return false;
        int j = nlIndex + 1;
        int n = src.length();
        while (j < n && Character.isWhitespace(src.charAt(j))) j++;
        if (j >= n) return false;
        char next = src.charAt(j);
        if ("([.,=+-*/%&|^?:<>)]}`".indexOf(next) >= 0) {
            // `//` comment on the next line is fine; anything else continues the expression.
            return next == '/' && j + 1 < n && (src.charAt(j + 1) == '/' || src.charAt(j + 1) == '*');
        }
        return true;
    }

    private static boolean regexAllowedAfter(char prevSig, String prevWord) {
        if (prevSig == 0) return true;
        if (!prevWord.isEmpty()) {
            return "return".equals(prevWord) || "typeof".equals(prevWord) || "case".equals(prevWord)
                || "in".equals(prevWord) || "of".equals(prevWord) || "void".equals(prevWord)
                || "delete".equals(prevWord) || "throw".equals(prevWord) || "new".equals(prevWord)
                || "await".equals(prevWord) || "yield".equals(prevWord);
        }
        return "(,=:[!&|?{};+-*%<>~^".indexOf(prevSig) >= 0;
    }

    private static int skipQuoted(String src, int start, char quote) {
        int i = start + 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == quote || c == '\n') return i + 1;
            i++;
        }
        return n;
    }

    /** Scans template text from `from`. Returns index after closing backtick, or -(index after "${"). */
    private static int skipTemplate(String src, int from) {
        int i = from;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '`') return i + 1;
            if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') return -(i + 2);
            i++;
        }
        return n;
    }

    private static int skipRegex(String src, int start) {
        int i = start + 1;
        int n = src.length();
        boolean inClass = false;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '\n') return i;
            if (inClass) {
                if (c == ']') inClass = false;
            } else if (c == '[') {
                inClass = true;
            } else if (c == '/') {
                i++;
                while (i < n && Character.isLetter(src.charAt(i))) i++;
                return i;
            }
            i++;
        }
        return n;
    }
}
