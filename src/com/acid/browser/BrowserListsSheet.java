package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * BottomSheet sheet managing Bookmarks, Browsing History, and Download tasks.
 */
public class BrowserListsSheet extends AcidBottomSheetView {

    public interface OnListActionListener {
        void onNavigateUrl(String url);
    }

    private final BrowserDataStore mDataStore;
    private final BrowserController mController;
    private final OnListActionListener mActionListener;
    private boolean mIsDarkMode = false;

    public BrowserListsSheet(
        Context context,
        BrowserDataStore dataStore,
        BrowserController controller,
        OnListActionListener actionListener
    ) {
        super(context);
        mDataStore = dataStore;
        mController = controller;
        mActionListener = actionListener;
    }

    @Override
    public void applyTheme(boolean isDark) {
        super.applyTheme(isDark);
        mIsDarkMode = isDark;
    }

    // =========================================================================
    // 1. Bookmarks Sheet
    // =========================================================================

    public void showBookmarks(final String currentTitle, final String currentUrl) {
        setTitle("书签收藏");
        int accent = AcidTheme.accent(mIsDarkMode);
        setAction(AcidIcons.ICON_PLUS, "添加当前页", accent, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!TextUtils.isEmpty(currentUrl)) {
                    mDataStore.addBookmark(currentTitle, currentUrl);
                    renderBookmarkItems();
                    Toast.makeText(getContext(), "已添加当前页到书签！", Toast.LENGTH_SHORT).show();
                }
            }
        });

        renderBookmarkItems();
        show();
    }

    private void renderBookmarkItems() {
        LinearLayout container = getContentContainer();
        container.removeAllViews();
        final List<BrowserDataStore.EntryItem> list = mDataStore.loadBookmarks();

        Context ctx = getContext();
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accentCol = AcidTheme.accent(mIsDarkMode);

        if (list.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无书签收藏");
            empty.setTextColor(mutedCol);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, AcidTheme.dp(ctx, 30), 0, AcidTheme.dp(ctx, 30));
            container.addView(empty);
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            final BrowserDataStore.EntryItem item = list.get(i);
            final int index = i;

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = AcidTheme.dp(ctx, 8);
            int padV = AcidTheme.dp(ctx, 10);
            row.setPadding(padH, padV, padH, padV);
            row.setBackground(AcidTheme.pillBg(ctx, itemBg, 0, 8));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = AcidTheme.dp(ctx, 6);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_BOOKMARK, accentCol, 17);
            row.addView(icon);

            LinearLayout textColBox = new LinearLayout(ctx);
            textColBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = AcidTheme.dp(ctx, 8);
            tLp.rightMargin = AcidTheme.dp(ctx, 8);

            TextView title = new TextView(ctx);
            title.setText(item.title);
            title.setTextColor(textCol);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textColBox.addView(title);

            TextView url = new TextView(ctx);
            url.setText(item.url);
            url.setTextColor(mutedCol);
            url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            textColBox.addView(url);
            row.addView(textColBox, tLp);

            // Delete item button
            LinearLayout delBox = new LinearLayout(ctx);
            delBox.setGravity(Gravity.CENTER);
            delBox.setPadding(AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4));
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CLOSE, mutedCol, 13);
            delBox.addView(delIcon);
            delBox.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mDataStore.removeBookmark(index);
                    renderBookmarkItems();
                }
            });
            row.addView(delBox);

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mActionListener != null) {
                        mActionListener.onNavigateUrl(item.url);
                    }
                }
            });

            container.addView(row, rLp);
        }
    }

    // =========================================================================
    // 2. History Sheet
    // =========================================================================

    public void showHistory() {
        setTitle("历史浏览");
        setAction(AcidIcons.ICON_TRASH, "清空历史", AcidTheme.COLOR_RED, new OnClickListener() {
            @Override
            public void onClick(View v) {
                mDataStore.clearHistory();
                renderHistoryItems();
                Toast.makeText(getContext(), "历史记录已清空", Toast.LENGTH_SHORT).show();
            }
        });

        renderHistoryItems();
        show();
    }

    private void renderHistoryItems() {
        LinearLayout container = getContentContainer();
        container.removeAllViews();
        List<BrowserDataStore.EntryItem> list = mDataStore.loadHistory();

        Context ctx = getContext();
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        if (list.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无历史浏览记录");
            empty.setTextColor(mutedCol);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, AcidTheme.dp(ctx, 30), 0, AcidTheme.dp(ctx, 30));
            container.addView(empty);
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            final BrowserDataStore.EntryItem item = list.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = AcidTheme.dp(ctx, 8);
            int padV = AcidTheme.dp(ctx, 10);
            row.setPadding(padH, padV, padH, padV);
            row.setBackground(AcidTheme.pillBg(ctx, itemBg, 0, 8));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = AcidTheme.dp(ctx, 6);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_HISTORY, AcidTheme.COLOR_AMBER, 17);
            row.addView(icon);

            LinearLayout textColBox = new LinearLayout(ctx);
            textColBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = AcidTheme.dp(ctx, 8);
            tLp.rightMargin = AcidTheme.dp(ctx, 8);

            TextView title = new TextView(ctx);
            title.setText(item.title);
            title.setTextColor(textCol);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textColBox.addView(title);

            TextView url = new TextView(ctx);
            url.setText(item.url);
            url.setTextColor(mutedCol);
            url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            textColBox.addView(url);
            row.addView(textColBox, tLp);

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mActionListener != null) {
                        mActionListener.onNavigateUrl(item.url);
                    }
                }
            });

            container.addView(row, rLp);
        }
    }

    // =========================================================================
    // 3. Downloads Sheet
    // =========================================================================

    public void showDownloads() {
        setTitle("下载管理");
        hideAction();

        LinearLayout container = getContentContainer();
        container.removeAllViews();

        Context ctx = getContext();
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accentCol = AcidTheme.accent(mIsDarkMode);

        List<BrowserController.DownloadItem> list = mController != null ? mController.getDownloads() : null;
        if (list == null || list.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无下载任务记录");
            empty.setTextColor(mutedCol);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, AcidTheme.dp(ctx, 30), 0, AcidTheme.dp(ctx, 30));
            container.addView(empty);
        } else {
            for (BrowserController.DownloadItem it : list) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padH = AcidTheme.dp(ctx, 8);
                int padV = AcidTheme.dp(ctx, 10);
                row.setPadding(padH, padV, padH, padV);
                row.setBackground(AcidTheme.pillBg(ctx, itemBg, 0, 8));
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rLp.bottomMargin = AcidTheme.dp(ctx, 6);

                AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_DOWNLOAD, accentCol, 17);
                row.addView(icon);

                LinearLayout textColBox = new LinearLayout(ctx);
                textColBox.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tLp.leftMargin = AcidTheme.dp(ctx, 8);

                TextView url = new TextView(ctx);
                url.setText(it.url);
                url.setTextColor(textCol);
                url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                url.setTypeface(Typeface.DEFAULT_BOLD);
                url.setSingleLine(true);
                url.setEllipsize(TextUtils.TruncateAt.END);
                textColBox.addView(url);

                TextView info = new TextView(ctx);
                info.setText((it.mimeType.isEmpty() ? "文件" : it.mimeType) + " | " + it.contentLength + " B");
                info.setTextColor(mutedCol);
                info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                textColBox.addView(info);

                row.addView(textColBox, tLp);
                container.addView(row, rLp);
            }
        }

        show();
    }
}
