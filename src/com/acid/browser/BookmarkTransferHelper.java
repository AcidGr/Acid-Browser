package com.acid.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.acid.browser.download.DownloadCoordinator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Handles SAF document picking, asynchronous stream parsing, and Download-directory HTML export
 * + system share prompting for bookmarks, keeping {@link BrowserActivity} as a thin orchestrator.
 */
public final class BookmarkTransferHelper {

    private static final String TAG = "AcidBookmarkTransfer";

    private BookmarkTransferHelper() {}

    public static void startImportPicker(Activity activity, int requestCode) {
        startDocumentPicker(activity, requestCode, "选择书签文件 (.html / .json)");
    }

    public static void startDocumentPicker(Activity activity, int requestCode, String chooserTitle) {
        if (activity == null) return;
        Intent docIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        docIntent.addCategory(Intent.CATEGORY_OPENABLE);
        docIntent.setType("*/*");
        docIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(docIntent, requestCode);
        } catch (Exception e1) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            try {
                activity.startActivityForResult(
                    Intent.createChooser(fallback, AcidLocale.tr(chooserTitle)),
                    requestCode
                );
            } catch (Exception e2) {
                AcidFeedback.warn(activity, "未找到可用的文件选择器");
            }
        }
    }

    public static Uri validatePickedUri(Activity activity, int resultCode, Intent data) {
        if (activity == null || resultCode != Activity.RESULT_OK || data == null) {
            return null;
        }
        Uri uri = data.getData();
        if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            uri = data.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) return null;
        if (!AcidFileProvider.isSafeFileChooserUri(activity, uri)) {
            AcidFeedback.warn(activity, "无法读取不受支持的文件路径");
            return null;
        }
        return uri;
    }

    public static File resolveExportDir(Activity activity, BrowserController controller) {
        DownloadCoordinator dc = controller != null ? controller.getDownloadCoordinator() : null;
        File destDir = dc != null ? dc.getDownloadDestinationDir() : null;
        if (destDir == null && activity != null) {
            File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (pub != null && (pub.exists() || pub.mkdirs()) && pub.canWrite()) {
                destDir = pub;
            } else {
                destDir = new File(activity.getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
            }
        }
        if (destDir != null && !destDir.exists()) {
            destDir.mkdirs();
        }
        return destDir;
    }

    public static void promptExportShare(
        final Activity activity,
        final boolean isDarkMode,
        final String dialogTitle,
        final String messageBody,
        final File outFile,
        final String mimeType,
        final String shareChooserTitle
    ) {
        if (activity == null || outFile == null) return;
        new AcidDialog(activity, isDarkMode)
            .setTitle(dialogTitle)
            .setRawMessage(messageBody)
            .setPositiveButton("分享文件", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    try {
                        Uri shareUri = AcidFileProvider.getUriForFile(outFile);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType(mimeType);
                        share.putExtra(Intent.EXTRA_STREAM, shareUri);
                        share.putExtra(Intent.EXTRA_SUBJECT, outFile.getName());
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(
                            Intent.createChooser(share, AcidLocale.tr(shareChooserTitle))
                        );
                    } catch (Exception ex) {
                        AcidFeedback.warn(
                            activity,
                            "无法拉起系统分享: " + ex.getMessage()
                        );
                    }
                    return true;
                }
            })
            .setNegativeButton("完成", null)
            .show();
    }

    public static void handleImportResult(
        final Activity activity,
        final BrowserDataStore dataStore,
        int resultCode,
        Intent data,
        final Runnable onImportedUiCallback
    ) {
        if (dataStore == null) return;
        final Uri targetUri = validatePickedUri(activity, resultCode, data);
        if (targetUri == null) return;
        AcidAsync.run(new Runnable() {
            @Override
            public void run() {
                InputStream is = null;
                try {
                    is = activity.getContentResolver().openInputStream(targetUri);
                    if (is == null) {
                        runIfAlive(activity, new Runnable() {
                            @Override
                            public void run() {
                                AcidFeedback.warn(activity, "无法打开选中的书签文件");
                            }
                        });
                        return;
                    }
                    final byte[] rawBytes;
                    try {
                        rawBytes = AcidIo.readBytesCapped(is, NetscapeBookmarkCodec.MAX_IMPORT_BYTES);
                    } catch (AcidIo.SizeLimitExceededException tooLarge) {
                        runIfAlive(activity, new Runnable() {
                            @Override
                            public void run() {
                                AcidFeedback.warn(activity, "文件过大（超过 16 MB 上限）");
                            }
                        });
                        return;
                    }
                    String content = new String(rawBytes, StandardCharsets.UTF_8);
                    final NetscapeBookmarkCodec.ImportParseResult parsed =
                        NetscapeBookmarkCodec.parseImportContent(content);
                    if (!parsed.ok) {
                        runIfAlive(activity, new Runnable() {
                            @Override
                            public void run() {
                                AcidFeedback.warn(activity, parsed.errorMessage);
                            }
                        });
                        return;
                    }

                    final BookmarkRepository.ImportSummary summary = dataStore.importBookmarks(parsed.items);
                    runIfAlive(activity, new Runnable() {
                        @Override
                        public void run() {
                            if (onImportedUiCallback != null) {
                                onImportedUiCallback.run();
                            }
                            if (summary.addedCount > 0) {
                                String msg = "已导入 " + summary.addedCount + " 条书签";
                                if (summary.skippedDuplicateCount > 0) {
                                    msg += "（跳过 " + summary.skippedDuplicateCount + " 条重复）";
                                }
                                AcidFeedback.ok(activity, msg);
                            } else {
                                AcidFeedback.info(
                                    activity,
                                    "没有新增书签（" + summary.skippedDuplicateCount + " 条与现有书签重复）"
                                );
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "Failed to import bookmarks", e);
                    runIfAlive(activity, new Runnable() {
                        @Override
                        public void run() {
                            AcidFeedback.error(
                                activity,
                                "导入书签失败: " + (e.getMessage() != null ? e.getMessage() : "读取错误")
                            );
                        }
                    });
                } finally {
                    AcidIo.closeQuietly(is);
                }
            }
        });
    }

    public static void exportAndPromptShare(
        final Activity activity,
        final BrowserDataStore dataStore,
        final BrowserController controller,
        final boolean isDarkMode
    ) {
        if (activity == null || dataStore == null) return;
        final List<BrowserDataStore.EntryItem> snapshot = dataStore.loadBookmarks();
        if (snapshot == null || snapshot.isEmpty()) {
            AcidFeedback.warn(activity, "当前没有可导出的书签");
            return;
        }
        AcidAsync.run(new Runnable() {
            @Override
            public void run() {
                try {
                    String html = NetscapeBookmarkCodec.exportToHtml(snapshot);
                    File destDir = resolveExportDir(activity, controller);
                    if (destDir == null) {
                        throw new IOException("无法定位本地下载目录");
                    }
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    String fileName = DownloadCoordinator.resolveUniqueFileName(
                        destDir,
                        "acid_bookmarks_" + stamp + ".html"
                    );
                    final File outFile = new File(destDir, fileName);
                    AcidIo.writeFileUtf8(outFile, html);

                    runIfAlive(activity, new Runnable() {
                        @Override
                        public void run() {
                            String body = AcidLocale.tr("已导出 ")
                                + snapshot.size()
                                + AcidLocale.tr(" 条书签至：")
                                + "\n" + outFile.getAbsolutePath();
                            promptExportShare(
                                activity, isDarkMode,
                                "书签导出成功", body,
                                outFile, "text/html", "分享书签文件"
                            );
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "Failed to export bookmarks", e);
                    runIfAlive(activity, new Runnable() {
                        @Override
                        public void run() {
                            AcidFeedback.error(
                                activity,
                                "导出书签失败: " + (e.getMessage() != null ? e.getMessage() : "写入错误")
                            );
                        }
                    });
                }
            }
        });
    }

    public static void runIfAlive(final Activity activity, final Runnable action) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    action.run();
                }
            }
        });
    }
}
