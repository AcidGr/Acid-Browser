package com.acid.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight Native Markdown Renderer for Acid Agent Assistant Replies.
 * Renders:
 *  - Fenced code blocks (```lang ... ```) inside rounded syntax cards with horizontal scroll
 *  - Headings (#, ##, ###)
 *  - Bullet & numbered lists (-, *, 1.)
 *  - Blockquotes (>)
 *  - Inline bold (**text**) and inline code (`code`)
 */
public class AcidMarkdownView extends LinearLayout {

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");

    public static void renderInto(LinearLayout container, String markdown, boolean darkTheme) {
        container.removeAllViews();
        if (markdown == null || markdown.isEmpty()) return;

        Context ctx = container.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        int textColor = AcidTheme.markdownText(darkTheme);
        int mutedColor = AcidTheme.markdownMuted(darkTheme);
        int codeBlockBg = AcidTheme.codeBlockBg(darkTheme);
        int codeBlockBorder = AcidTheme.codeBlockBorder(darkTheme);
        int inlineCodeBg = AcidTheme.inlineCodeBg(darkTheme);
        int inlineCodeFg = AcidTheme.inlineCodeFg(darkTheme);

        String[] rawLines = markdown.replace("\r\n", "\n").split("\n");
        StringBuilder paraBuf = new StringBuilder();
        StringBuilder codeBuf = new StringBuilder();
        boolean inCodeBlock = false;
        String codeLang = "";

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    flushParagraph(ctx, container, paraBuf, textColor, inlineCodeBg, inlineCodeFg);
                    inCodeBlock = true;
                    codeLang = trimmed.substring(3).trim();
                    codeBuf.setLength(0);
                } else {
                    addCodeBlockView(ctx, container, codeLang, codeBuf.toString(), codeBlockBg, codeBlockBorder, d);
                    inCodeBlock = false;
                    codeLang = "";
                    codeBuf.setLength(0);
                }
                continue;
            }

            if (inCodeBlock) {
                if (codeBuf.length() > 0) codeBuf.append("\n");
                codeBuf.append(line);
            } else {
                if (trimmed.isEmpty()) {
                    flushParagraph(ctx, container, paraBuf, textColor, inlineCodeBg, inlineCodeFg);
                } else if (trimmed.startsWith("> ")) {
                    flushParagraph(ctx, container, paraBuf, textColor, inlineCodeBg, inlineCodeFg);
                    addQuoteView(ctx, container, trimmed.substring(2), darkTheme, mutedColor, inlineCodeBg, inlineCodeFg, d);
                } else if (isTableRow(trimmed) && i + 1 < rawLines.length && isTableDelimiterRow(rawLines[i + 1])) {
                    flushParagraph(ctx, container, paraBuf, textColor, inlineCodeBg, inlineCodeFg);
                    List<String> tableLines = new ArrayList<>();
                    tableLines.add(line);
                    tableLines.add(rawLines[++i]);
                    while (i + 1 < rawLines.length && isTableRow(rawLines[i + 1])) {
                        tableLines.add(rawLines[++i]);
                    }
                    addTableView(ctx, container, tableLines, darkTheme, textColor, inlineCodeBg, inlineCodeFg, d);
                } else {
                    if (paraBuf.length() > 0) paraBuf.append("\n");
                    paraBuf.append(line);
                }
            }
        }

        if (inCodeBlock && codeBuf.length() > 0) {
            addCodeBlockView(ctx, container, codeLang, codeBuf.toString(), codeBlockBg, codeBlockBorder, d);
        }
        flushParagraph(ctx, container, paraBuf, textColor, inlineCodeBg, inlineCodeFg);
    }

    private static void flushParagraph(
        Context ctx,
        LinearLayout container,
        StringBuilder buf,
        int textColor,
        int inlineCodeBg,
        int inlineCodeFg
    ) {
        if (buf.length() == 0) return;
        String block = buf.toString();
        buf.setLength(0);

        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tv.setLineSpacing(Math.round(3 * d), 1.0f);
        tv.setTextIsSelectable(true);
        tv.setText(formatRichLines(block, inlineCodeBg, inlineCodeFg));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = Math.round(6 * d);
        container.addView(tv, lp);
    }

    private static void addQuoteView(
        Context ctx,
        LinearLayout container,
        String quoteText,
        boolean darkTheme,
        int mutedColor,
        int inlineCodeBg,
        int inlineCodeFg,
        float d
    ) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(HORIZONTAL);
        box.setPadding(Math.round(8 * d), Math.round(4 * d), Math.round(8 * d), Math.round(4 * d));

        GradientDrawable bar = new GradientDrawable();
        bar.setColor(AcidTheme.accent(darkTheme));
        bar.setCornerRadius(2 * d);

        TextView leftLine = new TextView(ctx);
        leftLine.setBackground(bar);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(Math.round(3 * d), ViewGroup.LayoutParams.MATCH_PARENT);
        barLp.rightMargin = Math.round(8 * d);
        box.addView(leftLine, barLp);

        TextView tv = new TextView(ctx);
        tv.setTextColor(mutedColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        tv.setText(parseInlineMarkdown(quoteText, inlineCodeBg, inlineCodeFg));
        box.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = Math.round(6 * d);
        container.addView(box, lp);
    }

    private static void addCodeBlockView(
        Context ctx,
        LinearLayout container,
        String lang,
        String code,
        int bgColor,
        int borderColor,
        float d
    ) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(8 * d);
        gd.setStroke(Math.max(1, Math.round(1 * d)), borderColor);
        card.setBackground(gd);
        card.setPadding(Math.round(10 * d), Math.round(7 * d), Math.round(10 * d), Math.round(8 * d));

        if (lang != null && !lang.isEmpty()) {
            TextView langTv = new TextView(ctx);
            langTv.setText(lang.toUpperCase());
            langTv.setTextColor(AcidTheme.DARK_INLINE_CODE_FG);
            langTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_MICRO);
            langTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lLp.bottomMargin = Math.round(4 * d);
            card.addView(langTv, lLp);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);

        TextView codeTv = new TextView(ctx);
        codeTv.setText(code);
        codeTv.setTextColor(AcidTheme.DARK_CODE_TEXT);
        codeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        codeTv.setTypeface(Typeface.MONOSPACE);
        codeTv.setTextIsSelectable(true);
        hsv.addView(codeTv);

        card.addView(hsv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.topMargin = Math.round(2 * d);
        cardLp.bottomMargin = Math.round(8 * d);
        container.addView(card, cardLp);
    }

    private static boolean isTableRow(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("```") || trimmed.startsWith("> ") || trimmed.startsWith("#")) return false;
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) return false;
        return trimmed.contains("|");
    }

    private static boolean isTableDelimiterRow(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.contains("-") || !trimmed.contains("|")) return false;
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        if (parts.length == 0) return false;
        for (String part : parts) {
            String p = part.trim();
            if (!p.matches("^:?-+:?$")) {
                return false;
            }
        }
        return true;
    }

    private static String[] splitTableRow(String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String placeholder = "\u0000PIPE\u0000";
        s = s.replace("\\|", placeholder);
        String[] rawParts = s.split("\\|", -1);
        String[] parts = new String[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            parts[i] = rawParts[i].replace(placeholder, "|").trim();
        }
        return parts;
    }

    private static void addTableView(
        Context ctx,
        LinearLayout container,
        List<String> tableLines,
        boolean darkTheme,
        int textColor,
        int inlineCodeBg,
        int inlineCodeFg,
        float d
    ) {
        if (tableLines == null || tableLines.size() < 2) return;

        String headerLine = tableLines.get(0);
        String delimLine = tableLines.get(1);

        String[] headerParts = splitTableRow(headerLine);
        String[] delimParts = splitTableRow(delimLine);

        int colCount = Math.max(headerParts.length, delimParts.length);
        for (int r = 2; r < tableLines.size(); r++) {
            String[] rowParts = splitTableRow(tableLines.get(r));
            if (rowParts.length > colCount) colCount = rowParts.length;
        }
        if (colCount == 0) return;

        int[] alignments = new int[colCount];
        for (int c = 0; c < colCount; c++) {
            if (c < delimParts.length) {
                String dPart = delimParts[c].trim();
                boolean left = dPart.startsWith(":");
                boolean right = dPart.endsWith(":");
                if (left && right) {
                    alignments[c] = Gravity.CENTER;
                } else if (right) {
                    alignments[c] = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                } else {
                    alignments[c] = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                }
            } else {
                alignments[c] = Gravity.LEFT | Gravity.CENTER_VERTICAL;
            }
        }

        int tableBg = AcidTheme.markdownTableBg(darkTheme);
        int borderColor = AcidTheme.markdownTableBorder(darkTheme);
        int headerBg = AcidTheme.markdownTableHeaderBg(darkTheme);
        int headerDivider = AcidTheme.markdownTableBorder(darkTheme);
        int rowDividerColor = AcidTheme.markdownTableRowDivider(darkTheme);
        int altRowBg = AcidTheme.markdownTableAltRowBg(darkTheme);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        GradientDrawable cardGd = new GradientDrawable();
        cardGd.setColor(tableBg);
        cardGd.setCornerRadius(8 * d);
        cardGd.setStroke(Math.max(1, Math.round(1 * d)), borderColor);
        card.setBackground(cardGd);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setClipToOutline(true);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setFillViewport(true);
        hsv.setHorizontalScrollBarEnabled(true);
        hsv.setScrollbarFadingEnabled(true);

        TableLayout table = new TableLayout(ctx);
        table.setStretchAllColumns(true);

        int padH = Math.round(12 * d);
        int padVHead = Math.round(8 * d);
        int padVCell = Math.round(7 * d);
        int minCellWidth = Math.round(60 * d);
        int maxCellWidth = Math.round(280 * d);

        // Header row
        TableRow headerRow = new TableRow(ctx);
        headerRow.setBackgroundColor(headerBg);
        for (int c = 0; c < colCount; c++) {
            String cellText = c < headerParts.length ? headerParts[c] : "";
            TextView tv = new TextView(ctx);
            tv.setPadding(padH, padVHead, padH, padVHead);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setGravity(alignments[c]);
            tv.setMinWidth(minCellWidth);
            tv.setMaxWidth(maxCellWidth);
            tv.setText(parseInlineMarkdown(cellText, inlineCodeBg, inlineCodeFg));
            headerRow.addView(tv);
        }
        table.addView(headerRow);

        View headDiv = new View(ctx);
        headDiv.setBackgroundColor(headerDivider);
        table.addView(headDiv, new TableLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1 * d))
        ));

        // Data rows
        for (int r = 2; r < tableLines.size(); r++) {
            String rowLine = tableLines.get(r);
            if (isTableDelimiterRow(rowLine)) {
                View subDiv = new View(ctx);
                subDiv.setBackgroundColor(headerDivider);
                table.addView(subDiv, new TableLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1 * d))
                ));
                continue;
            }

            String[] rowParts = splitTableRow(rowLine);
            TableRow row = new TableRow(ctx);
            if ((r % 2) == 1) {
                row.setBackgroundColor(altRowBg);
            }

            for (int c = 0; c < colCount; c++) {
                String cellText = c < rowParts.length ? rowParts[c] : "";
                TextView tv = new TextView(ctx);
                tv.setPadding(padH, padVCell, padH, padVCell);
                tv.setTextColor(textColor);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
                tv.setLineSpacing(Math.round(2 * d), 1.0f);
                tv.setGravity(alignments[c]);
                tv.setMinWidth(minCellWidth);
                tv.setMaxWidth(maxCellWidth);
                tv.setText(parseInlineMarkdown(cellText, inlineCodeBg, inlineCodeFg));
                row.addView(tv);
            }
            table.addView(row);

            if (r < tableLines.size() - 1) {
                View rowDiv = new View(ctx);
                rowDiv.setBackgroundColor(rowDividerColor);
                table.addView(rowDiv, new TableLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.8f * d))
                ));
            }
        }

        hsv.addView(table, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.addView(hsv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final Context finalCtx = ctx;
        final List<String> finalTableLines = tableLines;
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                StringBuilder sb = new StringBuilder();
                for (String tLine : finalTableLines) {
                    sb.append(tLine).append("\n");
                }
                AcidIo.copyToClipboard(finalCtx, "table", sb.toString().trim(), "已复制表格 Markdown 到剪贴板");
                return true;
            }
        });

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.topMargin = Math.round(4 * d);
        cardLp.bottomMargin = Math.round(8 * d);
        container.addView(card, cardLp);
    }

    private static CharSequence formatRichLines(String text, int inlineCodeBg, int inlineCodeFg) {
        String[] lines = text.split("\n");
        SpannableStringBuilder ssb = new SpannableStringBuilder();

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) ssb.append("\n");
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("### ")) {
                int start = ssb.length();
                ssb.append(parseInlineMarkdown(trimmed.substring(4), inlineCodeBg, inlineCodeFg));
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new RelativeSizeSpan(1.06f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("## ")) {
                int start = ssb.length();
                ssb.append(parseInlineMarkdown(trimmed.substring(3), inlineCodeBg, inlineCodeFg));
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new RelativeSizeSpan(1.12f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("# ")) {
                int start = ssb.length();
                ssb.append(parseInlineMarkdown(trimmed.substring(2), inlineCodeBg, inlineCodeFg));
                int end = ssb.length();
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new RelativeSizeSpan(1.18f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                ssb.append(" • ");
                ssb.append(parseInlineMarkdown(trimmed.substring(2), inlineCodeBg, inlineCodeFg));
            } else {
                ssb.append(parseInlineMarkdown(line, inlineCodeBg, inlineCodeFg));
            }
        }
        return ssb;
    }

    private static SpannableStringBuilder parseInlineMarkdown(String line, int inlineCodeBg, int inlineCodeFg) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(line);

        // 1. Inline code `...`
        Matcher cm = CODE_PATTERN.matcher(ssb.toString());
        int offset = 0;
        while (cm.find()) {
            int s = cm.start() - offset;
            int e = cm.end() - offset;
            String inner = cm.group(1);
            ssb.replace(s, e, " " + inner + " ");
            int newEnd = s + inner.length() + 2;
            ssb.setSpan(new TypefaceSpan("monospace"), s, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new BackgroundColorSpan(inlineCodeBg), s, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new ForegroundColorSpan(inlineCodeFg), s, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            offset += (e - s) - (newEnd - s);
        }

        // 2. Bold **...**
        Matcher bm = BOLD_PATTERN.matcher(ssb.toString());
        int bOffset = 0;
        while (bm.find()) {
            int s = bm.start() - bOffset;
            int e = bm.end() - bOffset;
            String inner = bm.group(1);
            ssb.replace(s, e, inner);
            int newEnd = s + inner.length();
            ssb.setSpan(new StyleSpan(Typeface.BOLD), s, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bOffset += 4;
        }

        return ssb;
    }

    public AcidMarkdownView(Context context) {
        super(context);
    }
}
