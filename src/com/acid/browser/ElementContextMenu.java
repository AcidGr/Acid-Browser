package com.acid.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating popup context menu triggered on long-clicking WebView elements (links, images).
 * Displays near the user's touch location without emojis.
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
        if ((linkUrl == null || linkUrl.isEmpty()) && (imageUrl == null || imageUrl.isEmpty())) {
            return;
        }

        final PopupWindow popup = new PopupWindow(context);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(AcidTheme.dp(context, 10));
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        int bgColor = isDarkMode ? Color.parseColor("#121212") : Color.WHITE;
        int borderColor = isDarkMode ? Color.parseColor("#27272A") : Color.parseColor("#E2E8F0");
        root.setBackground(AcidTheme.cardBorderBg(context, bgColor, borderColor, 1, 12));

        int padV = AcidTheme.dp(context, 6);
        int padH = AcidTheme.dp(context, 6);
        root.setPadding(padH, padV, padH, padV);

        boolean hasLink = linkUrl != null && !linkUrl.isEmpty();
        boolean hasImage = imageUrl != null && !imageUrl.isEmpty();

        // Telephone link
        if (hasLink && linkUrl.startsWith("tel:")) {
            final String phone = linkUrl.substring(4);
            addItem(root, context, isDarkMode, "拨打电话", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    try {
                        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(linkUrl));
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(context, "无法拨打电话", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            addItem(root, context, isDarkMode, "复制号码", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    copyText(context, phone, "号码已复制");
                }
            });
        }
        // Email link
        else if (hasLink && linkUrl.startsWith("mailto:")) {
            final String email = linkUrl.substring(7);
            addItem(root, context, isDarkMode, "发送邮件", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    try {
                        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse(linkUrl));
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(context, "无法发送邮件", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            addItem(root, context, isDarkMode, "复制邮箱", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    copyText(context, email, "邮箱已复制");
                }
            });
        }
        // Regular link / image
        else {
            if (hasLink) {
                addItem(root, context, isDarkMode, "后台打开", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        controller.createNewTab(linkUrl, false);
                        Toast.makeText(context, "已在后台打开新标签", Toast.LENGTH_SHORT).show();
                    }
                });
                addItem(root, context, isDarkMode, "新标签打开", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        controller.createNewTab(linkUrl, true);
                    }
                });
            }

            if (hasImage) {
                addItem(root, context, isDarkMode, "查看图片", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        controller.createNewTab(imageUrl, true);
                    }
                });
                addItem(root, context, isDarkMode, "下载图片", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        controller.startDownload(imageUrl, null, null, null, "image/*", 0);
                    }
                });
            }

            if (hasLink) {
                addItem(root, context, isDarkMode, "复制链接", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        copyText(context, linkUrl, "链接已复制");
                    }
                });
            }

            if (hasImage) {
                addItem(root, context, isDarkMode, "复制图片链接", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        popup.dismiss();
                        copyText(context, imageUrl, "图片链接已复制");
                    }
                });
            }

            final String shareTarget = hasLink ? linkUrl : imageUrl;
            addItem(root, context, isDarkMode, hasLink && hasImage ? "分享" : (hasLink ? "分享链接" : "分享图片"), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    shareText(context, shareTarget);
                }
            });
        }

        popup.setContentView(root);

        // Compute popup coordinates
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int menuWidth = AcidTheme.dp(context, 175);
        popup.setWidth(menuWidth);
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);

        root.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int menuHeight = root.getMeasuredHeight();

        int margin = AcidTheme.dp(context, 12);
        int posX = touchX - AcidTheme.dp(context, 20);
        if (posX + menuWidth > dm.widthPixels - margin) {
            posX = dm.widthPixels - margin - menuWidth;
        }
        if (posX < margin) {
            posX = margin;
        }

        int posY = touchY + AcidTheme.dp(context, 8);
        if (posY + menuHeight > dm.heightPixels - AcidTheme.dp(context, 68)) {
            posY = touchY - menuHeight - AcidTheme.dp(context, 8);
        }
        if (posY < AcidTheme.dp(context, 48)) {
            posY = AcidTheme.dp(context, 48);
        }

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, posX, posY);
    }

    private static void addItem(LinearLayout container, Context context, boolean isDarkMode, String text, View.OnClickListener listener) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(isDarkMode ? Color.parseColor("#F1F5F9") : Color.parseColor("#1E293B"));
        tv.setTypeface(Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER_VERTICAL);

        int padH = AcidTheme.dp(context, 14);
        tv.setPadding(padH, 0, padH, 0);

        StateListDrawable sld = new StateListDrawable();
        int pressedColor = isDarkMode ? Color.parseColor("#27272A") : Color.parseColor("#F1F5F9");
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setCornerRadius(AcidTheme.dp(context, 8));
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));

        tv.setBackground(sld);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(listener);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            AcidTheme.dp(context, 42)
        );
        container.addView(tv, lp);
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
