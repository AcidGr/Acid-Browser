package com.acid.browser;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
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

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressPoller = null;
    private int mSelectedCategory = BrowserController.DownloadItem.CAT_ALL;
    private String mDownloadSearchKeyword = "";
    private LinearLayout mDownloadsItemsContainer = null;
    private final List<TextView> mCategoryPillViews = new ArrayList<TextView>();

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
    public void dismiss() {
        stopProgressPolling();
        super.dismiss();
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
    // 3. Downloads Sheet (Via-Style Categorized with Live Progress)
    // =========================================================================

    public void showDownloads() {
        setTitle("下载管理");
        hideAction();

        LinearLayout container = getContentContainer();
        container.removeAllViews();

        final Context ctx = getContext();
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int searchBg = AcidTheme.searchBg(mIsDarkMode);
        int searchBorder = AcidTheme.searchBorder(mIsDarkMode);

        // 1. Search Box
        LinearLayout searchBox = new LinearLayout(ctx);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 10);
        int padV = AcidTheme.dp(ctx, 4);
        searchBox.setPadding(padH, padV, padH, padV);
        searchBox.setBackground(AcidTheme.pillBg(ctx, searchBg, searchBorder, 8));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sbLp.bottomMargin = AcidTheme.dp(ctx, 8);

        AcidIcons.SvgIconView searchIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_SEARCH, mutedCol, 14);
        searchBox.addView(searchIcon);

        final EditText etSearch = new EditText(ctx);
        etSearch.setHint("搜索下载记录...");
        etSearch.setHintTextColor(mutedCol);
        etSearch.setTextColor(textCol);
        etSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        etSearch.setBackground(null);
        etSearch.setSingleLine(true);
        etSearch.setText(mDownloadSearchKeyword);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        etLp.leftMargin = AcidTheme.dp(ctx, 6);
        searchBox.addView(etSearch, etLp);

        container.addView(searchBox, sbLp);

        // 2. Horizontal Category Filter Pills
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hsvLp.bottomMargin = AcidTheme.dp(ctx, 10);

        LinearLayout pillBar = new LinearLayout(ctx);
        pillBar.setOrientation(LinearLayout.HORIZONTAL);

        final int[] catIds = new int[]{
            BrowserController.DownloadItem.CAT_ALL,
            BrowserController.DownloadItem.CAT_APK,
            BrowserController.DownloadItem.CAT_ARCHIVE,
            BrowserController.DownloadItem.CAT_DOC,
            BrowserController.DownloadItem.CAT_IMAGE,
            BrowserController.DownloadItem.CAT_MEDIA
        };
        final String[] catLabels = new String[]{
            "全部", "安装包", "压缩包", "文档", "图片", "音视频"
        };

        mCategoryPillViews.clear();
        for (int i = 0; i < catIds.length; i++) {
            final int catId = catIds[i];
            final TextView pill = new TextView(ctx);
            pill.setText(catLabels[i]);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setGravity(Gravity.CENTER);
            int pH = AcidTheme.dp(ctx, 12);
            int pV = AcidTheme.dp(ctx, 5);
            pill.setPadding(pH, pV, pH, pV);
            pill.setClickable(true);
            pill.setFocusable(true);

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) pLp.leftMargin = AcidTheme.dp(ctx, 6);

            pill.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mSelectedCategory = catId;
                    updateCategoryPillsStyle();
                    if (mDownloadsItemsContainer != null) {
                        updateDownloadListRows(mDownloadsItemsContainer);
                    }
                }
            });

            mCategoryPillViews.add(pill);
            pillBar.addView(pill, pLp);
        }
        updateCategoryPillsStyle();
        hsv.addView(pillBar);
        container.addView(hsv, hsvLp);

        // 3. Items Container
        mDownloadsItemsContainer = new LinearLayout(ctx);
        mDownloadsItemsContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(mDownloadsItemsContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                mDownloadSearchKeyword = s != null ? s.toString().trim() : "";
                if (mDownloadsItemsContainer != null) {
                    updateDownloadListRows(mDownloadsItemsContainer);
                }
            }
        });

        updateDownloadListRows(mDownloadsItemsContainer);
        show();
        startProgressPolling();
    }

    public void refreshDownloads() {
        if (isShowing() && mDownloadsItemsContainer != null) {
            updateDownloadListRows(mDownloadsItemsContainer);
        }
    }

    private void startProgressPolling() {
        stopProgressPolling();
        mProgressPoller = new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) {
                    stopProgressPolling();
                    return;
                }
                boolean hasActive = false;
                if (mController != null) {
                    List<BrowserController.DownloadItem> list = mController.getDownloads();
                    for (BrowserController.DownloadItem item : list) {
                        if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING) {
                            hasActive = true;
                            break;
                        }
                    }
                }
                if (mDownloadsItemsContainer != null) {
                    updateDownloadListRows(mDownloadsItemsContainer);
                }
                if (hasActive && isShowing()) {
                    mMainHandler.postDelayed(this, 500);
                }
            }
        };
        mMainHandler.postDelayed(mProgressPoller, 500);
    }

    private void stopProgressPolling() {
        if (mProgressPoller != null) {
            mMainHandler.removeCallbacks(mProgressPoller);
            mProgressPoller = null;
        }
    }

    private void updateCategoryPillsStyle() {
        Context ctx = getContext();
        int accentCol = AcidTheme.accent(mIsDarkMode);
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        final int[] catIds = new int[]{
            BrowserController.DownloadItem.CAT_ALL,
            BrowserController.DownloadItem.CAT_APK,
            BrowserController.DownloadItem.CAT_ARCHIVE,
            BrowserController.DownloadItem.CAT_DOC,
            BrowserController.DownloadItem.CAT_IMAGE,
            BrowserController.DownloadItem.CAT_MEDIA
        };

        for (int i = 0; i < mCategoryPillViews.size() && i < catIds.length; i++) {
            TextView p = mCategoryPillViews.get(i);
            boolean sel = (mSelectedCategory == catIds[i]);
            if (sel) {
                p.setBackground(AcidTheme.pillBg(ctx, accentCol, 0, 12));
                p.setTextColor(Color.WHITE);
            } else {
                p.setBackground(AcidTheme.pillBg(ctx, itemBg, 0, 12));
                p.setTextColor(mutedCol);
            }
        }
    }

    private void updateDownloadListRows(final LinearLayout itemsContainer) {
        if (itemsContainer == null) return;
        itemsContainer.removeAllViews();

        Context ctx = getContext();
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int accentCol = AcidTheme.accent(mIsDarkMode);

        List<BrowserController.DownloadItem> allList = mController != null ? mController.getDownloads() : null;
        List<BrowserController.DownloadItem> list = new ArrayList<BrowserController.DownloadItem>();

        if (allList != null) {
            for (BrowserController.DownloadItem it : allList) {
                if (mSelectedCategory != BrowserController.DownloadItem.CAT_ALL && it.getCategory() != mSelectedCategory) {
                    continue;
                }
                if (!mDownloadSearchKeyword.isEmpty()) {
                    String fn = it.fileName != null ? it.fileName.toLowerCase() : "";
                    if (!fn.contains(mDownloadSearchKeyword.toLowerCase())) {
                        continue;
                    }
                }
                list.add(it);
            }
        }

        if (list.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(allList == null || allList.isEmpty() ? "暂无下载任务记录" : "未找到匹配的下载项");
            empty.setTextColor(mutedCol);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, AcidTheme.dp(ctx, 30), 0, AcidTheme.dp(ctx, 30));
            itemsContainer.addView(empty);
            return;
        }

        for (final BrowserController.DownloadItem it : list) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            int padH = AcidTheme.dp(ctx, 10);
            int padV = AcidTheme.dp(ctx, 10);
            row.setPadding(padH, padV, padH, padV);
            row.setBackground(AcidTheme.pillBg(ctx, itemBg, 0, 8));
            row.setClickable(true);
            row.setFocusable(true);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = AcidTheme.dp(ctx, 6);

            // Top Header: Icon + File Name + Subtitle
            LinearLayout headerRow = new LinearLayout(ctx);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            int statusCol = accentCol;
            if (it.status == DownloadManager.STATUS_SUCCESSFUL) {
                statusCol = AcidTheme.COLOR_GREEN;
            } else if (it.status == DownloadManager.STATUS_FAILED) {
                statusCol = AcidTheme.COLOR_RED;
            } else if (it.status == DownloadManager.STATUS_RUNNING) {
                statusCol = AcidTheme.COLOR_BLUE;
            } else if (it.status == DownloadManager.STATUS_PENDING) {
                statusCol = AcidTheme.COLOR_AMBER;
            }

            int iconType = AcidIcons.ICON_DOWNLOAD;
            if (it.getCategory() == BrowserController.DownloadItem.CAT_DOC) {
                iconType = AcidIcons.ICON_FILE;
            }

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, iconType, statusCol, 18);
            headerRow.addView(icon);

            LinearLayout textColBox = new LinearLayout(ctx);
            textColBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = AcidTheme.dp(ctx, 10);

            TextView title = new TextView(ctx);
            title.setText(it.fileName);
            title.setTextColor(textCol);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textColBox.addView(title);

            TextView info = new TextView(ctx);
            String catTag = "";
            switch (it.getCategory()) {
                case BrowserController.DownloadItem.CAT_APK: catTag = "安装包 · "; break;
                case BrowserController.DownloadItem.CAT_ARCHIVE: catTag = "压缩包 · "; break;
                case BrowserController.DownloadItem.CAT_DOC: catTag = "文档 · "; break;
                case BrowserController.DownloadItem.CAT_IMAGE: catTag = "图片 · "; break;
                case BrowserController.DownloadItem.CAT_MEDIA: catTag = "音视频 · "; break;
            }

            if (it.status == DownloadManager.STATUS_RUNNING) {
                int p = it.getProgressPercent();
                String progSize = BrowserController.DownloadItem.formatSize(it.downloadedBytes) + " / " + BrowserController.DownloadItem.formatSize(it.contentLength);
                info.setText(catTag + "↓ 正在下载 " + p + "% · " + progSize);
                info.setTextColor(AcidTheme.COLOR_BLUE);
            } else if (it.status == DownloadManager.STATUS_PENDING) {
                info.setText(catTag + "⏱ 排队等待下载...");
                info.setTextColor(AcidTheme.COLOR_AMBER);
            } else if (it.status == DownloadManager.STATUS_FAILED) {
                info.setText(catTag + "✕ 下载失败 · " + BrowserController.DownloadItem.formatSize(it.contentLength));
                info.setTextColor(AcidTheme.COLOR_RED);
            } else {
                String sizeStr = BrowserController.DownloadItem.formatSize(it.contentLength > 0 ? it.contentLength : it.downloadedBytes);
                info.setText(catTag + "✓ 已完成 · " + sizeStr + " · /Download/");
                info.setTextColor(mutedCol);
            }

            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            info.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
            textColBox.addView(info);

            headerRow.addView(textColBox, tLp);
            row.addView(headerRow);

            // Dynamic progress bar if active
            if (it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING) {
                ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(it.getProgressPercent());
                pb.setIndeterminate(it.contentLength <= 0);
                LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(ctx, 4)
                );
                pbLp.topMargin = AcidTheme.dp(ctx, 6);
                pbLp.leftMargin = AcidTheme.dp(ctx, 28);
                row.addView(pb, pbLp);
            }

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mController != null) {
                        if (it.status == DownloadManager.STATUS_RUNNING) {
                            Toast.makeText(getContext(), "文件正在下载中: " + it.fileName + " (" + it.getProgressPercent() + "%)", Toast.LENGTH_SHORT).show();
                        } else if (it.status == DownloadManager.STATUS_FAILED) {
                            Toast.makeText(getContext(), "该文件下载失败: " + it.fileName, Toast.LENGTH_SHORT).show();
                        } else {
                            mController.openDownloadedFile(it);
                        }
                    }
                }
            });

            row.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    final String[] options = new String[]{
                        "打开文件",
                        "其它应用打开",
                        "打开所在目录",
                        "复制下载链接",
                        "重新下载",
                        "删除任务"
                    };
                    AlertDialog.Builder menuBuilder = new AlertDialog.Builder(getContext());
                    menuBuilder.setTitle(it.fileName);
                    menuBuilder.setItems(options, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (mController == null) return;
                            switch (which) {
                                case 0:
                                    mController.openDownloadedFile(it, false);
                                    break;
                                case 1:
                                    mController.openDownloadedFile(it, true);
                                    break;
                                case 2:
                                    mController.openDownloadFolder();
                                    break;
                                case 3:
                                    mController.copyToClipboard(it.url);
                                    break;
                                case 4:
                                    mController.startDownload(it.url, it.fileName, null, null, it.mimeType, it.contentLength);
                                    updateDownloadListRows(itemsContainer);
                                    break;
                                case 5:
                                    showDeleteConfirmDialog(it, itemsContainer);
                                    break;
                            }
                        }
                    });
                    menuBuilder.show();
                    return true;
                }
            });

            itemsContainer.addView(row, rLp);
        }
    }

    private void showDeleteConfirmDialog(final BrowserController.DownloadItem it, final LinearLayout itemsContainer) {
        Context ctx = getContext();
        AlertDialog.Builder delBuilder = new AlertDialog.Builder(ctx);
        delBuilder.setTitle("删除");

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = AcidTheme.dp(ctx, 22);
        int padV = AcidTheme.dp(ctx, 14);
        box.setPadding(padH, padV, padH, padV);

        TextView tvMsg = new TextView(ctx);
        tvMsg.setText("确定删除这个下载任务吗？\n\n" + it.fileName);
        tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvMsg.setTextColor(AcidTheme.text(mIsDarkMode));
        box.addView(tvMsg);

        final CheckBox cbDeleteFile = new CheckBox(ctx);
        cbDeleteFile.setText("同时删除已下载的文件");
        cbDeleteFile.setChecked(false);
        cbDeleteFile.setTextColor(AcidTheme.muted(mIsDarkMode));
        cbDeleteFile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cbLp.topMargin = AcidTheme.dp(ctx, 16);
        box.addView(cbDeleteFile, cbLp);

        delBuilder.setView(box);

        delBuilder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                boolean delFile = cbDeleteFile.isChecked();
                if (mController != null) {
                    mController.deleteDownload(it.downloadId, delFile);
                }
                updateDownloadListRows(itemsContainer);
                Toast.makeText(getContext(), delFile ? "已删除任务及本地源文件" : "已移除下载任务记录", Toast.LENGTH_SHORT).show();
            }
        });
        delBuilder.setNegativeButton("取消", null);
        delBuilder.show();
    }
}
