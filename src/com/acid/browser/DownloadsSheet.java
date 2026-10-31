package com.acid.browser;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Download Manager Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage} template,
 * {@link AcidWidgets#filterChipBar}, {@link AcidWidgets.ListRow}, and {@link AcidContextMenu}.
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

    private int mSelectedCategoryIndex = 0;
    private String mSearchKeyword = "";
    private final Map<Long, DownloadRowHolder> mDownloadRows = new LinkedHashMap<Long, DownloadRowHolder>();

    public DownloadsSheet(Context context, BrowserController controller) {
        super(context);
        mController = controller;
        setRootPage(buildDownloadsPage());
    }

    @Override
    public void dismiss() {
        stopProgressPolling();
        super.dismiss();
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildDownloadsPage());
    }

    public void showDownloads() {
        setRootPage(buildDownloadsPage());
        show();
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildDownloadsPage());
        }
        super.show();
        startProgressPolling();
    }

    public void refreshDownloads() {
        if (isShowing()) {
            updateDownloadListRows();
        }
    }

    private SheetPage buildDownloadsPage() {
        return new SheetPage("下载管理", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = getContext();

                // 1. Pinned Search Bar
                final EditText etSearch = new EditText(ctx);
                etSearch.setText(mSearchKeyword);
                LinearLayout searchBox = AcidWidgets.searchBox(ctx, mIsDarkMode, etSearch, "搜索下载文件名...");
                LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                sbLp.bottomMargin = AcidTheme.dp(ctx, 8);
                pinned.addView(searchBox, sbLp);

                // 2. Pinned Category Filter Chips (using AcidTheme.RADIUS_PILL & onColor contrast)
                pinned.addView(AcidWidgets.filterChipBar(
                    ctx, mIsDarkMode, CAT_LABELS, mSelectedCategoryIndex,
                    new AcidWidgets.OnTabSelectedListener() {
                        @Override
                        public void onTabSelected(int index) {
                            mSelectedCategoryIndex = index;
                            updateDownloadListRows();
                        }
                    }
                ), new LinearLayout.LayoutParams(
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
        })
            .withAction(AcidIcons.ICON_FILE, "打开目录", new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mController != null) {
                        mController.openDownloadFolder();
                    }
                }
            })
            .withMinContentHeightDp(280f)
            .withOnPause(new Runnable() {
                @Override
                public void run() {
                    stopProgressPolling();
                }
            });
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
        int selectedCat = (mSelectedCategoryIndex >= 0 && mSelectedCategoryIndex < CAT_IDS.length)
            ? CAT_IDS[mSelectedCategoryIndex]
            : BrowserController.DownloadItem.CAT_ALL;

        List<BrowserController.DownloadItem> allList = mController != null ? mController.getDownloads() : null;
        List<BrowserController.DownloadItem> list = new ArrayList<BrowserController.DownloadItem>();

        if (allList != null) {
            for (BrowserController.DownloadItem it : allList) {
                if (selectedCat != BrowserController.DownloadItem.CAT_ALL && it.getCategory() != selectedCat) {
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
            int iconTint = (it.status == DownloadManager.STATUS_FAILED)
                ? AcidTheme.COLOR_RED
                : AcidTheme.muted(mIsDarkMode);

            ProgressBar pb = null;
            if (it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING) {
                pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(it.getProgressPercent());
                pb.setIndeterminate(it.contentLength <= 0);
            }

            AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
                .leadingIcon(it.getCategoryIcon(), iconTint, 20)
                .title(it.fileName)
                .subtitle(buildDownloadSubtitle(it))
                .subtitleColor(downloadSubtitleColor(it));

            if (pb != null) {
                rowBuilder.bottomView(pb, 30f);
            }

            final LinearLayout row = rowBuilder.build(ctx, mIsDarkMode);
            mDownloadRows.put(
                rowKey(it),
                new DownloadRowHolder(rowBuilder.subtitleView, pb, rowBuilder.iconView)
            );

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mController == null) return;
                    if (it.status == DownloadManager.STATUS_RUNNING) {
                        String speed = it.getFormattedSpeed();
                        String speedInfo = (speed != null && !speed.isEmpty()) ? (" · " + speed) : "";
                        String rem = it.getRemainingTime();
                        String remInfo = (rem != null && !rem.isEmpty()) ? (" · 剩余 " + rem) : "";
                        AcidFeedback.info(ctx, "正在多线程下载: " + it.fileName + " (" + it.getProgressPercent() + "%)" + speedInfo + remInfo);
                    } else if (it.status == DownloadManager.STATUS_PAUSED) {
                        mController.resumeDownload(it.downloadId);
                        AcidFeedback.ok(ctx, "已恢复下载: " + it.fileName);
                        updateDownloadListRows();
                        startProgressPolling();
                    } else if (it.status == DownloadManager.STATUS_FAILED) {
                        mController.resumeDownload(it.downloadId);
                        AcidFeedback.info(ctx, "正在重试下载: " + it.fileName);
                        updateDownloadListRows();
                        startProgressPolling();
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
        if (it.status == DownloadManager.STATUS_RUNNING) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CLOSE, "暂停下载", new Runnable() {
                @Override
                public void run() {
                    if (mController != null) {
                        mController.pauseDownload(it.downloadId);
                        AcidFeedback.info(ctx, "已暂停: " + it.fileName);
                        updateDownloadListRows();
                    }
                }
            }));
        } else if (it.status == DownloadManager.STATUS_PAUSED) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "继续下载", new Runnable() {
                @Override
                public void run() {
                    if (mController != null) {
                        mController.resumeDownload(it.downloadId);
                        AcidFeedback.ok(ctx, "已恢复下载: " + it.fileName);
                        updateDownloadListRows();
                        startProgressPolling();
                    }
                }
            }));
        }
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
                    AcidFeedback.info(ctx, delFile ? "已删除任务及本地源文件" : "已移除下载任务记录");
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
            String speedStr = it.getFormattedSpeed();
            String speedTag = (speedStr != null && !speedStr.isEmpty()) ? " · " + speedStr : "";
            return catTag + "正在下载 " + it.getProgressPercent() + "% · " + progSize + speedTag;
        }
        if (it.status == DownloadManager.STATUS_PAUSED) {
            return catTag + "已暂停 · " + BrowserController.DownloadItem.formatSize(it.downloadedBytes);
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
        if (it.status == DownloadManager.STATUS_PAUSED) return AcidTheme.COLOR_AMBER;
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

            if (holder.infoView != null) {
                holder.infoView.setText(buildDownloadSubtitle(it));
                holder.infoView.setTextColor(downloadSubtitleColor(it));
            }
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
