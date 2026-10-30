package com.acid.browser;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
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
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated Download Manager Sheet built on {@link AcidBottomSheetView}, {@link AcidWidgets},
 * {@link AcidContextMenu}, and {@link AcidDialog}.
 *
 * <p>Keeps the search bar and 8-category filter pills pinned in the top header slot so scrolling
 * long download lists never hides the filter bar, and live search never drops focus.
 */
public class DownloadsSheet extends AcidBottomSheetView {

    private static final int[] CAT_IDS = new int[]{
        BrowserController.DownloadItem.CAT_ALL,
        BrowserController.DownloadItem.CAT_APK,
        BrowserController.DownloadItem.CAT_ARCHIVE,
        BrowserController.DownloadItem.CAT_DOC,
        BrowserController.DownloadItem.CAT_IMAGE,
        BrowserController.DownloadItem.CAT_VIDEO,
        BrowserController.DownloadItem.CAT_AUDIO,
        BrowserController.DownloadItem.CAT_OTHER
    };

    private static final String[] CAT_LABELS = new String[]{
        "全部", "安装包", "压缩包", "文档", "图片", "视频", "音频", "其它"
    };

    private static final class DownloadRowHolder {
        final TextView infoView;
        final ProgressBar progressBar;
        final AcidIcons.SvgIconView iconView;

        DownloadRowHolder(TextView infoView, ProgressBar progressBar, AcidIcons.SvgIconView iconView) {
            this.infoView = infoView;
            this.progressBar = progressBar;
            this.iconView = iconView;
        }
    }

    private static long rowKey(BrowserController.DownloadItem it) {
        return it.downloadId > 0 ? it.downloadId : -Math.abs(it.timestamp);
    }

    private final BrowserController mController;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressPoller = null;

    private int mSelectedCategory = BrowserController.DownloadItem.CAT_ALL;
    private String mSearchKeyword = "";
    private final List<TextView> mCategoryPillViews = new ArrayList<TextView>();
    private final Map<Long, DownloadRowHolder> mDownloadRows = new LinkedHashMap<Long, DownloadRowHolder>();

    public DownloadsSheet(Context context, BrowserController controller) {
        super(context);
        mController = controller;
        setMinContentHeightDp(280);
    }

    @Override
    public void dismiss() {
        stopProgressPolling();
        super.dismiss();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            rebuildShellAndRows();
        }
    }

    public void showDownloads() {
        rebuildShellAndRows();
        show();
        startProgressPolling();
    }

    public void refreshDownloads() {
        if (isShowing()) {
            updateDownloadListRows();
        }
    }

    private void rebuildShellAndRows() {
        final Context ctx = getContext();
        setTitle("下载管理");
        setAction(AcidIcons.ICON_FILE, "打开目录", AcidTheme.accent(mIsDarkMode), new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.openDownloadFolder();
                }
            }
        });

        clearPinnedHeader();
        LinearLayout pinned = getPinnedHeaderContainer();

        // 1. Pinned Search Bar
        final EditText etSearch = new EditText(ctx);
        etSearch.setText(mSearchKeyword);
        LinearLayout searchBox = AcidWidgets.searchBox(ctx, mIsDarkMode, etSearch, "搜索下载文件名...");
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sbLp.bottomMargin = AcidTheme.dp(ctx, 8);
        pinned.addView(searchBox, sbLp);

        // 2. Pinned Category Filter Pills
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);

        LinearLayout pillBar = new LinearLayout(ctx);
        pillBar.setOrientation(LinearLayout.HORIZONTAL);

        mCategoryPillViews.clear();
        for (int i = 0; i < CAT_IDS.length; i++) {
            final int catId = CAT_IDS[i];
            TextView pill = new TextView(ctx);
            pill.setText(CAT_LABELS[i]);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
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
                    updateDownloadListRows();
                }
            });

            mCategoryPillViews.add(pill);
            pillBar.addView(pill, pLp);
        }
        updateCategoryPillsStyle();
        hsv.addView(pillBar);
        pinned.addView(hsv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                mSearchKeyword = s != null ? s.toString().trim() : "";
                updateDownloadListRows();
            }
        });

        updateDownloadListRows();
    }

    private void updateCategoryPillsStyle() {
        Context ctx = getContext();
        int accentCol = AcidTheme.accent(mIsDarkMode);
        int itemBg = AcidTheme.menuItemBg(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);
        int borderCol = AcidTheme.border(mIsDarkMode);

        for (int i = 0; i < mCategoryPillViews.size() && i < CAT_IDS.length; i++) {
            TextView p = mCategoryPillViews.get(i);
            boolean sel = (mSelectedCategory == CAT_IDS[i]);
            if (sel) {
                p.setBackground(AcidTheme.interactiveBg(
                    ctx, accentCol, AcidTheme.adjustPressFill(accentCol, mIsDarkMode), 0, 12
                ));
                p.setTextColor(mIsDarkMode ? Color.BLACK : Color.WHITE);
            } else {
                p.setBackground(AcidTheme.interactiveBg(
                    ctx, itemBg, AcidTheme.rowPressed(mIsDarkMode), borderCol, 12
                ));
                p.setTextColor(mutedCol);
            }
        }
    }

    private void startProgressPolling() {
        stopProgressPolling();
        mProgressPoller = new Runnable() {
            @Override
            public void run() {
                if (!isShowing() || mController == null) {
                    stopProgressPolling();
                    return;
                }
                mController.syncDownloadStatusAsync(new BrowserController.DownloadSyncCallback() {
                    @Override
                    public void onDownloadSyncDone(boolean anyActive) {
                        if (!isShowing()) {
                            stopProgressPolling();
                            return;
                        }
                        refreshDownloadProgressInPlace();
                        if (anyActive) {
                            mMainHandler.postDelayed(mProgressPoller, 500);
                        } else {
                            updateDownloadListRows();
                        }
                    }
                });
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

    private void updateDownloadListRows() {
        LinearLayout itemsContainer = getContentContainer();
        if (itemsContainer == null) return;

        final int scrollY = getScrollView() != null ? getScrollView().getScrollY() : 0;
        itemsContainer.removeAllViews();
        mDownloadRows.clear();

        final Context ctx = getContext();
        int textCol = AcidTheme.text(mIsDarkMode);

        List<BrowserController.DownloadItem> allList = mController != null ? mController.getDownloads() : null;
        List<BrowserController.DownloadItem> list = new ArrayList<BrowserController.DownloadItem>();

        if (allList != null) {
            for (BrowserController.DownloadItem it : allList) {
                if (mSelectedCategory != BrowserController.DownloadItem.CAT_ALL && it.getCategory() != mSelectedCategory) {
                    continue;
                }
                if (!mSearchKeyword.isEmpty()) {
                    String fn = it.fileName != null ? it.fileName.toLowerCase() : "";
                    if (!fn.contains(mSearchKeyword.toLowerCase())) {
                        continue;
                    }
                }
                list.add(it);
            }
        }

        if (list.isEmpty()) {
            itemsContainer.addView(AcidWidgets.emptyState(
                ctx,
                mIsDarkMode,
                AcidIcons.ICON_DOWNLOAD,
                allList == null || allList.isEmpty() ? "暂无下载任务记录" : "未找到匹配的下载任务"
            ));
            return;
        }

        for (final BrowserController.DownloadItem it : list) {
            final LinearLayout row = AcidWidgets.listItemCard(ctx, mIsDarkMode, false, 0);
            row.setOrientation(LinearLayout.VERTICAL);

            LinearLayout headerRow = new LinearLayout(ctx);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);

            int iconTint = (it.status == DownloadManager.STATUS_FAILED)
                ? AcidTheme.COLOR_RED
                : AcidTheme.muted(mIsDarkMode);
            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, it.getCategoryIcon(), iconTint, 20);
            headerRow.addView(icon);

            LinearLayout textBox = new LinearLayout(ctx);
            textBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            tLp.leftMargin = AcidTheme.dp(ctx, 10);

            TextView title = new TextView(ctx);
            title.setText(it.fileName);
            title.setTextColor(textCol);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textBox.addView(title);

            TextView info = new TextView(ctx);
            info.setText(buildDownloadSubtitle(it));
            info.setTextColor(downloadSubtitleColor(it));
            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            info.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
            textBox.addView(info);

            headerRow.addView(textBox, tLp);
            row.addView(headerRow);

            ProgressBar pb = null;
            if (it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING) {
                pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(it.getProgressPercent());
                pb.setIndeterminate(it.contentLength <= 0);
                LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(ctx, 4)
                );
                pbLp.topMargin = AcidTheme.dp(ctx, 6);
                pbLp.leftMargin = AcidTheme.dp(ctx, 30);
                row.addView(pb, pbLp);
            }

            mDownloadRows.put(rowKey(it), new DownloadRowHolder(info, pb, icon));

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mController == null) return;
                    if (it.status == DownloadManager.STATUS_RUNNING) {
                        Toast.makeText(ctx, "正在下载: " + it.fileName + " (" + it.getProgressPercent() + "%)", Toast.LENGTH_SHORT).show();
                    } else if (it.status == DownloadManager.STATUS_FAILED) {
                        Toast.makeText(ctx, "该任务下载失败，长按可重新下载", Toast.LENGTH_SHORT).show();
                    } else {
                        mController.openDownloadedFile(it);
                    }
                }
            });

            row.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showDownloadContextMenu(row, it);
                    return true;
                }
            });

            itemsContainer.addView(row);
        }

        if (scrollY > 0 && getScrollView() != null) {
            getScrollView().post(new Runnable() {
                @Override
                public void run() {
                    getScrollView().scrollTo(0, scrollY);
                }
            });
        }
    }

    private void showDownloadContextMenu(View anchor, final BrowserController.DownloadItem it) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "打开文件", new Runnable() {
            @Override
            public void run() {
                if (mController != null) mController.openDownloadedFile(it, false);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_SEND, "其他应用打开", new Runnable() {
            @Override
            public void run() {
                if (mController != null) mController.openDownloadedFile(it, true);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_DOWNLOAD, "打开所在目录", new Runnable() {
            @Override
            public void run() {
                if (mController != null) mController.openDownloadFolder();
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_GLOBE, "复制下载链接", new Runnable() {
            @Override
            public void run() {
                if (mController != null) mController.copyToClipboard(it.url);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "重新下载", new Runnable() {
            @Override
            public void run() {
                if (mController != null) {
                    mController.startDownload(it.url, it.fileName, null, null, it.mimeType, it.contentLength);
                    updateDownloadListRows();
                    startProgressPolling();
                }
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除任务", true, new Runnable() {
            @Override
            public void run() {
                showDeleteConfirmDialog(it);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, it.fileName, actions);
    }

    private void showDeleteConfirmDialog(final BrowserController.DownloadItem it) {
        final Context ctx = getContext();
        final boolean[] deletePhysical = new boolean[]{false};

        LinearLayout extraBox = new LinearLayout(ctx);
        extraBox.setOrientation(LinearLayout.VERTICAL);
        extraBox.addView(AcidDialog.createCheckableRow(
            ctx, mIsDarkMode, "同时删除本地已下载的源文件", false,
            new AcidWidgets.OnToggleListener() {
                @Override
                public void onToggle(boolean isChecked) {
                    deletePhysical[0] = isChecked;
                }
            }
        ));

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("删除下载任务")
            .setMessage("确定移除下载记录「" + it.fileName + "」吗？")
            .setCustomView(extraBox)
            .setDangerButton("删除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    boolean delFile = deletePhysical[0];
                    if (mController != null) {
                        mController.deleteDownload(it.downloadId, delFile);
                    }
                    updateDownloadListRows();
                    Toast.makeText(
                        ctx,
                        delFile ? "已删除任务及本地源文件" : "已移除下载任务记录",
                        Toast.LENGTH_SHORT
                    ).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String buildDownloadSubtitle(BrowserController.DownloadItem it) {
        String catTag = it.getCategoryLabel() + " · ";
        if (it.status == DownloadManager.STATUS_RUNNING) {
            String progSize = BrowserController.DownloadItem.formatSize(it.downloadedBytes)
                + " / " + BrowserController.DownloadItem.formatSize(it.contentLength);
            return catTag + "正在下载 " + it.getProgressPercent() + "% · " + progSize;
        }
        if (it.status == DownloadManager.STATUS_PENDING) {
            return catTag + "排队等待下载...";
        }
        if (it.status == DownloadManager.STATUS_FAILED) {
            return catTag + "下载失败 · " + BrowserController.DownloadItem.formatSize(it.contentLength);
        }
        String sizeStr = BrowserController.DownloadItem.formatSize(
            it.contentLength > 0 ? it.contentLength : it.downloadedBytes
        );
        return catTag + "已完成 · " + sizeStr + " · /Download/";
    }

    private int downloadSubtitleColor(BrowserController.DownloadItem it) {
        if (it.status == DownloadManager.STATUS_RUNNING) return AcidTheme.COLOR_BLUE;
        if (it.status == DownloadManager.STATUS_PENDING) return AcidTheme.COLOR_AMBER;
        if (it.status == DownloadManager.STATUS_FAILED) return AcidTheme.COLOR_RED;
        return AcidTheme.muted(mIsDarkMode);
    }

    private void refreshDownloadProgressInPlace() {
        if (mController == null || mDownloadRows.isEmpty()) return;
        int mutedTint = AcidTheme.muted(mIsDarkMode);
        List<BrowserController.DownloadItem> allList = mController.getDownloads();

        for (BrowserController.DownloadItem it : allList) {
            DownloadRowHolder holder = mDownloadRows.get(rowKey(it));
            if (holder == null) continue;

            holder.infoView.setText(buildDownloadSubtitle(it));
            holder.infoView.setTextColor(downloadSubtitleColor(it));

            if (holder.progressBar != null) {
                if (it.contentLength > 0) {
                    holder.progressBar.setIndeterminate(false);
                    holder.progressBar.setProgress(it.getProgressPercent());
                } else {
                    holder.progressBar.setIndeterminate(true);
                }
            }
            if (holder.iconView != null) {
                holder.iconView.setColor(it.status == DownloadManager.STATUS_FAILED
                    ? AcidTheme.COLOR_RED : mutedTint);
            }
        }
    }
}
