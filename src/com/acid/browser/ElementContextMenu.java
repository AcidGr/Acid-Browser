package com.acid.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * WebView Element Long-Press Context Menu builder delegating presentation to {@link AcidContextMenu}.
 */
public final class ElementContextMenu {

    private ElementContextMenu() {}

    public static void show(
        final Context context,
        final BrowserController controller,
        final View anchor,
        final int touchX,
        final int touchY,
        final String linkUrl,
        final String imageUrl,
        final boolean isDarkMode
    ) {
        if (context == null || controller == null || anchor == null) return;
        final boolean hasLink = linkUrl != null && !linkUrl.isEmpty();
        final boolean hasImage = imageUrl != null && !imageUrl.isEmpty();
        if (!hasLink && !hasImage) return;

        List<AcidContextMenu.ActionItem> items = new ArrayList<AcidContextMenu.ActionItem>();
        String header = hasLink ? linkUrl : imageUrl;

        if (hasLink && linkUrl.startsWith("tel:")) {
            final String phone = linkUrl.substring(4);
            header = phone;
            items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_MOBILE, "拨打电话", new Runnable() {
                @Override
                public void run() {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(linkUrl)));
                    } catch (Exception e) {
                        Toast.makeText(context, "无法拨打电话", Toast.LENGTH_SHORT).show();
                    }
                }
            }));
            items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "复制号码", new Runnable() {
                @Override
                public void run() {
                    copyText(context, phone, "号码已复制");
                }
            }));
        } else if (hasLink && linkUrl.startsWith("mailto:")) {
            final String email = linkUrl.substring(7);
            header = email;
            items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_SEND, "发送邮件", new Runnable() {
                @Override
                public void run() {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(linkUrl)));
                    } catch (Exception e) {
                        Toast.makeText(context, "无法发送邮件", Toast.LENGTH_SHORT).show();
                    }
                }
            }));
            items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "复制邮箱", new Runnable() {
                @Override
                public void run() {
                    copyText(context, email, "邮箱已复制");
                }
            }));
        } else {
            if (hasLink) {
                items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_PLUS, "后台打开", new Runnable() {
                    @Override
                    public void run() {
                        controller.createNewTab(linkUrl, false);
                        Toast.makeText(context, "已在后台打开新标签", Toast.LENGTH_SHORT).show();
                    }
                }));
                items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TABS, "新标签页打开", new Runnable() {
                    @Override
                    public void run() {
                        controller.createNewTab(linkUrl, true);
                    }
                }));
            }

            if (hasImage) {
                items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE_IMAGE, "查看图片", new Runnable() {
                    @Override
                    public void run() {
                        controller.createNewTab(imageUrl, true);
                    }
                }));
                items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_DOWNLOAD, "下载图片", new Runnable() {
                    @Override
                    public void run() {
                        controller.startDownload(imageUrl, null, null, null, "image/*", 0);
                    }
                }));
            }

            if (hasLink) {
                items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_GLOBE, "复制链接", new Runnable() {
                    @Override
                    public void run() {
                        copyText(context, linkUrl, "链接已复制");
                    }
                }));
            }

            if (hasImage) {
                items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "复制图片链接", new Runnable() {
                    @Override
                    public void run() {
                        copyText(context, imageUrl, "图片链接已复制");
                    }
                }));
            }

            final String shareTarget = hasLink ? linkUrl : imageUrl;
            String shareLabel = (hasLink && hasImage) ? "分享链接" : (hasLink ? "分享链接" : "分享图片");
            items.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_SEND, shareLabel, new Runnable() {
                @Override
                public void run() {
                    shareText(context, shareTarget);
                }
            }));
        }

        AcidContextMenu.showAtPoint(context, anchor, touchX, touchY, isDarkMode, header, items);
    }

    private static void copyText(Context context, String text, String toastMsg) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("text", text));
                Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static void shareText(Context context, String text) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            context.startActivity(Intent.createChooser(intent, "分享"));
        } catch (Exception e) {
            Toast.makeText(context, "无法发起分享", Toast.LENGTH_SHORT).show();
        }
    }
}
