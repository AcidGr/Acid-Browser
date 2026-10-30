package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated Bookmarks & Browsing History Sheet built on {@link AcidBottomSheetView},
 * {@link AcidWidgets}, {@link AcidContextMenu}, and {@link AcidDialog}.
 *
 * <p>Decoupled from the download manager so neither view carries unrelated polling or filter state.
 */
public class BookmarksHistorySheet extends AcidBottomSheetView {

    public interface OnNavigateListener {
        void onNavigateUrl(String url);
    }

    public static final int TAB_BOOKMARKS = 0;
    public static final int TAB_HISTORY = 1;

    private final BrowserDataStore mDataStore;
    private final BrowserController mController;
    private final OnNavigateListener mNavigateListener;

    private int mActiveTab = TAB_BOOKMARKS;
    private String mCurrentPageTitle = "";
    private String mCurrentPageUrl = "";

    public BookmarksHistorySheet(
        Context context,
        BrowserDataStore dataStore,
        BrowserController controller,
        OnNavigateListener navigateListener
    ) {
        super(context);
        mDataStore = dataStore;
        mController = controller;
        mNavigateListener = navigateListener;
    }

    public void showBookmarks(String currentTitle, String currentUrl) {
        mActiveTab = TAB_BOOKMARKS;
        mCurrentPageTitle = currentTitle != null ? currentTitle : "";
        mCurrentPageUrl = currentUrl != null ? currentUrl : "";
        rebuildAll();
        show();
    }

    public void showHistory(String currentTitle, String currentUrl) {
        mActiveTab = TAB_HISTORY;
        mCurrentPageTitle = currentTitle != null ? currentTitle : "";
        mCurrentPageUrl = currentUrl != null ? currentUrl : "";
        rebuildAll();
        show();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            rebuildAll();
        }
    }

    private void rebuildAll() {
        final Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);

        if (mActiveTab == TAB_BOOKMARKS) {
            setTitle("书签收藏");
            if (!TextUtils.isEmpty(mCurrentPageUrl)) {
                setAction(AcidIcons.ICON_PLUS, "收藏当前页", accent, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mDataStore.addBookmark(mCurrentPageTitle, mCurrentPageUrl);
                        rebuildAll();
                        Toast.makeText(ctx, "已收藏当前网页", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                hideAction();
            }
        } else {
            setTitle("历史浏览");
            List<BrowserDataStore.EntryItem> hist = mDataStore.loadHistory();
            if (!hist.isEmpty()) {
                setAction(AcidIcons.ICON_TRASH, "清空历史", AcidTheme.COLOR_RED, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmClearAllHistory();
                    }
                });
            } else {
                hideAction();
            }
        }

        clearPinnedHeader();
        getPinnedHeaderContainer().addView(AcidWidgets.segmentedTabs(
            ctx,
            mIsDarkMode,
            new String[]{"书签收藏", "历史浏览"},
            mActiveTab,
            accent,
            new AcidWidgets.OnTabSelectedListener() {
                @Override
                public void onTabSelected(int index) {
                    if (mActiveTab != index) {
                        mActiveTab = index;
                        rebuildAll();
                    }
                }
            }
        ));

        LinearLayout container = getContentContainer();
        container.removeAllViews();

        if (mActiveTab == TAB_BOOKMARKS) {
            renderBookmarks(container);
        } else {
            renderHistory(container);
        }
    }

    private void renderBookmarks(LinearLayout container) {
        final Context ctx = getContext();
        final List<BrowserDataStore.EntryItem> list = mDataStore.loadBookmarks();

        if (list.isEmpty()) {
            container.addView(AcidWidgets.emptyState(
                ctx, mIsDarkMode, AcidIcons.ICON_BOOKMARK, "暂无书签收藏，点击右上角可收藏当前页"
            ));
            return;
        }

        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            final BrowserDataStore.EntryItem item = list.get(i);

            final LinearLayout row = AcidWidgets.listItemCard(ctx, mIsDarkMode, false, 0);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView avatar = AcidIcons.createLetterAvatar(
                ctx, item.title, item.url, 34, AcidTheme.avatarColor(item.title), Color.WHITE
            );
            row.addView(avatar);

            LinearLayout textBox = new LinearLayout(ctx);
            textBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            tLp.leftMargin = AcidTheme.dp(ctx, 10);

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(item.title);
            tvTitle.setTextColor(textCol);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            textBox.addView(tvTitle);

            TextView tvUrl = new TextView(ctx);
            tvUrl.setText(item.url);
            tvUrl.setTextColor(mutedCol);
            tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvUrl.setSingleLine(true);
            tvUrl.setEllipsize(TextUtils.TruncateAt.END);
            tvUrl.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
            textBox.addView(tvUrl);

            row.addView(textBox, tLp);

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mNavigateListener != null) {
                        mNavigateListener.onNavigateUrl(item.url);
                    }
                }
            });

            row.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showBookmarkContextMenu(row, index, item);
                    return true;
                }
            });

            container.addView(row);
        }
    }

    private void renderHistory(LinearLayout container) {
        final Context ctx = getContext();
        final List<BrowserDataStore.EntryItem> list = mDataStore.loadHistory();

        if (list.isEmpty()) {
            container.addView(AcidWidgets.emptyState(
                ctx, mIsDarkMode, AcidIcons.ICON_HISTORY, "暂无历史浏览记录"
            ));
            return;
        }

        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        for (int i = 0; i < list.size(); i++) {
            final BrowserDataStore.EntryItem item = list.get(i);

            final LinearLayout row = AcidWidgets.listItemCard(ctx, mIsDarkMode, false, 0);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
                ctx, AcidIcons.ICON_HISTORY, AcidTheme.COLOR_AMBER, 18
            );
            row.addView(icon);

            LinearLayout textBox = new LinearLayout(ctx);
            textBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            tLp.leftMargin = AcidTheme.dp(ctx, 10);

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(item.title);
            tvTitle.setTextColor(textCol);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            textBox.addView(tvTitle);

            TextView tvUrl = new TextView(ctx);
            tvUrl.setText(item.url);
            tvUrl.setTextColor(mutedCol);
            tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            tvUrl.setSingleLine(true);
            tvUrl.setEllipsize(TextUtils.TruncateAt.END);
            tvUrl.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
            textBox.addView(tvUrl);

            row.addView(textBox, tLp);

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mNavigateListener != null) {
                        mNavigateListener.onNavigateUrl(item.url);
                    }
                }
            });

            row.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showHistoryContextMenu(row, item);
                    return true;
                }
            });

            container.addView(row);
        }
    }

    private void showBookmarkContextMenu(View anchor, final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TABS, "新标签页打开", new Runnable() {
            @Override
            public void run() {
                dismiss();
                if (mController != null) mController.createNewTab(item.url, true);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_PLUS, "后台打开", new Runnable() {
            @Override
            public void run() {
                dismiss();
                if (mController != null) mController.createNewTab(item.url, false);
                Toast.makeText(ctx, "已在后台打开新标签", Toast.LENGTH_SHORT).show();
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_EDIT, "编辑书签", new Runnable() {
            @Override
            public void run() {
                showEditBookmarkDialog(index, item);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除书签", true, new Runnable() {
            @Override
            public void run() {
                showDeleteBookmarkConfirm(index, item);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, item.title, actions);
    }

    private void showHistoryContextMenu(View anchor, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TABS, "新标签页打开", new Runnable() {
            @Override
            public void run() {
                dismiss();
                if (mController != null) mController.createNewTab(item.url, true);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_PLUS, "后台打开", new Runnable() {
            @Override
            public void run() {
                dismiss();
                if (mController != null) mController.createNewTab(item.url, false);
                Toast.makeText(ctx, "已在后台打开新标签", Toast.LENGTH_SHORT).show();
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_BOOKMARK, "添加到书签", new Runnable() {
            @Override
            public void run() {
                mDataStore.addBookmark(item.title, item.url);
                Toast.makeText(ctx, "已添加到书签收藏", Toast.LENGTH_SHORT).show();
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "复制链接", new Runnable() {
            @Override
            public void run() {
                if (mController != null) mController.copyToClipboard(item.url);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, item.title, actions);
    }

    private void showEditBookmarkDialog(final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);

        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "显示名称"));
        final EditText etTitle = AcidWidgets.inputField(
            ctx, mIsDarkMode, item.title, "留空则使用网址", true
        );
        form.addView(etTitle);

        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "网页地址"));
        final EditText etUrl = AcidWidgets.inputField(
            ctx, mIsDarkMode, item.url, "https://example.com", true
        );
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(etUrl);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("编辑书签")
            .setCustomView(form)
            .setPositiveButton("保存", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String newTitle = etTitle.getText().toString().trim();
                    String newUrl = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(newUrl)) {
                        Toast.makeText(ctx, "网址不能为空", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    if (mDataStore.updateBookmark(index, newTitle, newUrl)) {
                        rebuildAll();
                        Toast.makeText(ctx, "书签已更新", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(ctx, "更新失败，书签可能已被删除", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showDeleteBookmarkConfirm(final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("删除书签")
            .setMessage("确定删除书签「" + item.title + "」吗？")
            .setDangerButton("删除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    mDataStore.removeBookmark(index);
                    rebuildAll();
                    Toast.makeText(ctx, "书签已删除", Toast.LENGTH_SHORT).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmClearAllHistory() {
        final Context ctx = getContext();
        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("清空浏览历史")
            .setMessage("确定清空全部历史浏览记录吗？此操作无法撤销。")
            .setDangerButton("立即清空", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    mDataStore.clearHistory();
                    if (mController != null) {
                        mController.clearAllHistory();
                    }
                    rebuildAll();
                    Toast.makeText(ctx, "历史浏览记录已清空", Toast.LENGTH_SHORT).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
