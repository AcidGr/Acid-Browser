package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;
import android.view. Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * High-level Tier-3 Dialog Templates built on {@link AcidDialog}.
 *
 * <p>Eliminates repetitive 20-line dialog construction blocks for confirmations, single-field
 * inputs, single-choice pickers, and multi-choice checklists.
 */
public final class AcidDialogs {

    public interface InputSubmitListener {
        /**
         * @return {@code true} when valid (closes dialog), {@code false} to keep dialog open.
         */
        boolean onSubmit(String trimmedValue);
    }

    public interface SingleChoiceCallback {
        void onSelected(int index);
    }

    public interface MultiChoiceCallback {
        boolean onConfirmed(boolean[] checked);
    }

    private AcidDialogs() {}

    public static Dialog confirmDanger(
        Context ctx,
        boolean dark,
        String title,
        String message,
        String dangerActionText,
        final Runnable onConfirm
    ) {
        return new AcidDialog(ctx, dark)
            .setTitle(title)
            .setMessage(message)
            .setDangerButton(dangerActionText, new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (onConfirm != null) onConfirm.run();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public static Dialog confirm(
        Context ctx,
        boolean dark,
        String title,
        String message,
        String positiveText,
        final Runnable onConfirm
    ) {
        return new AcidDialog(ctx, dark)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText, new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    if (onConfirm != null) onConfirm.run();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public static Dialog inputSingleLine(
        final Context ctx,
        boolean dark,
        String title,
        String fieldLabel,
        String initialValue,
        String hint,
        int inputType,
        String confirmText,
        final String emptyErrorToast,
        final InputSubmitListener listener
    ) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        if (!TextUtils.isEmpty(fieldLabel)) {
            form.addView(AcidWidgets.fieldLabel(ctx, dark, fieldLabel));
        }
        final EditText et = AcidWidgets.inputField(ctx, dark, initialValue, hint, true);
        if (inputType != 0) {
            et.setInputType(inputType);
        }
        form.addView(et);

        return new AcidDialog(ctx, dark)
            .setTitle(title)
            .setCustomView(form)
            .setPositiveButton(confirmText, new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String val = et.getText() != null ? et.getText().toString().trim() : "";
                    if (val.isEmpty() && !TextUtils.isEmpty(emptyErrorToast)) {
                        AcidFeedback.warn(ctx, emptyErrorToast);
                        return false;
                    }
                    return listener == null || listener.onSubmit(val);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public static Dialog inputMultiLine(
        final Context ctx,
        boolean dark,
        String title,
        String fieldLabel,
        String initialValue,
        String hint,
        int minLines,
        int maxLines,
        String confirmText,
        final String emptyErrorToast,
        final InputSubmitListener listener
    ) {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        if (!TextUtils.isEmpty(fieldLabel)) {
            form.addView(AcidWidgets.fieldLabel(ctx, dark, fieldLabel));
        }
        final EditText et = AcidWidgets.inputField(ctx, dark, initialValue, hint, false);
        et.setMinLines(minLines);
        et.setMaxLines(maxLines);
        et.setGravity(Gravity.TOP | Gravity.START);
        form.addView(et);

        return new AcidDialog(ctx, dark)
            .setTitle(title)
            .setCustomView(form)
            .setPositiveButton(confirmText, new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String val = et.getText() != null ? et.getText().toString().trim() : "";
                    if (val.isEmpty() && !TextUtils.isEmpty(emptyErrorToast)) {
                        AcidFeedback.warn(ctx, emptyErrorToast);
                        return false;
                    }
                    return listener == null || listener.onSubmit(val);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public static Dialog chooseSingle(
        Context ctx,
        boolean dark,
        String title,
        String[] items,
        int checkedIndex,
        final SingleChoiceCallback callback
    ) {
        return new AcidDialog(ctx, dark)
            .setTitle(title)
            .setSingleChoiceItems(items, checkedIndex, new AcidDialog.OnSingleChoiceListener() {
                @Override
                public void onItemSelected(Dialog dialog, int which) {
                    dialog.dismiss();
                    if (callback != null) callback.onSelected(which);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public static Dialog chooseMulti(
        Context ctx,
        boolean dark,
        String title,
        String[] items,
        final boolean[] checked,
        String confirmText,
        boolean danger,
        final MultiChoiceCallback callback
    ) {
        AcidDialog d = new AcidDialog(ctx, dark)
            .setTitle(title)
            .setMultiChoiceItems(items, checked)
            .setNegativeButton("取消", null);
        AcidDialog.OnActionListener action = new AcidDialog.OnActionListener() {
            @Override
            public boolean onAction(Dialog dialog) {
                return callback == null || callback.onConfirmed(checked);
            }
        };
        if (danger) {
            d.setDangerButton(confirmText, action);
        } else {
            d.setPositiveButton(confirmText, action);
        }
        return d.show();
    }
}
