package com.akshaykadam.pixelboard.extension.clipboard;

import android.app.AlertDialog;
import android.content.Context;
import android.os.IBinder;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Long-press on the clipboard's "Delete selected items" trash key asks to clear every
 * unpinned clip.
 *
 * The patch gives the trash softkey a LONG_PRESS action and routes that event here from
 * {@code ClipboardKeyboard}. The confirm dialog is built the way Gboard builds its own
 * keyboard dialogs (a themed {@link AlertDialog} attached to the keyboard window), and
 * Clear calls back into helper methods the patch adds to {@code ClipboardKeyboard}, which
 * delete through Gboard's own batch-delete path so its "N items deleted · Undo" toast and
 * cleanup still run.
 */
public final class GboardClipboardClearAllRuntime {

    static final String COUNT_METHOD = "pixelboardCountUnpinnedClips";
    static final String CLEAR_METHOD = "pixelboardClearUnpinnedClips";

    static final String TITLE = "Clear clipboard?";
    static final String NOTHING_TO_CLEAR_TITLE = "Nothing to clear";
    static final String NOTHING_TO_CLEAR_MESSAGE = "Only pinned clips are left. Pinned clips are kept.";
    static final String CONFIRM = "Clear";

    private static final String TAG = "PixelBoardClipboard";
    // WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG, as Gboard's own dialogs use.
    private static final int ATTACHED_DIALOG_WINDOW_TYPE = 1003;

    private static WeakReference<AlertDialog> showingDialog = new WeakReference<>(null);

    private GboardClipboardClearAllRuntime() {
    }

    /**
     * Called on the main thread from {@code ClipboardKeyboard}'s key handler when the trash
     * key is long-pressed.
     *
     * @param keyboard the {@code ClipboardKeyboard} instance
     * @param anchor a view inside the keyboard window (the clip list)
     */
    public static void onClearAllLongPress(Object keyboard, View anchor) {
        if (keyboard == null || anchor == null) {
            return;
        }
        try {
            IBinder token = anchor.getWindowToken();
            if (token == null || !anchor.isAttachedToWindow()) {
                return;
            }
            dismissShowing();
            anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

            int unpinned = invokeInt(keyboard, COUNT_METHOD);
            if (unpinned < 0) {
                return;
            }
            Context context = anchor.getContext();
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            if (unpinned == 0) {
                builder.setTitle(NOTHING_TO_CLEAR_TITLE)
                        .setMessage(NOTHING_TO_CLEAR_MESSAGE)
                        .setPositiveButton(android.R.string.ok, null);
            } else {
                builder.setTitle(TITLE)
                        .setMessage(confirmMessage(unpinned))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(CONFIRM, (dialog, which) -> clear(keyboard, anchor));
            }
            builder.setCancelable(true);

            AlertDialog dialog = builder.create();
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.token = token;
            attributes.type = ATTACHED_DIALOG_WINDOW_TYPE;
            window.setAttributes(attributes);

            View.OnAttachStateChangeListener dismissOnDetach = new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    dismissQuietly(dialog);
                }
            };
            anchor.addOnAttachStateChangeListener(dismissOnDetach);
            dialog.setOnDismissListener(d -> anchor.removeOnAttachStateChangeListener(dismissOnDetach));

            dialog.show();
            showingDialog = new WeakReference<>(dialog);
        } catch (Throwable t) {
            Log.w(TAG, "Could not show the clear clipboard dialog", t);
        }
    }

    static String confirmMessage(int unpinned) {
        String clips = unpinned == 1 ? "1 unpinned clip" : unpinned + " unpinned clips";
        return "This deletes " + clips + ". Pinned clips are kept.";
    }

    private static void clear(Object keyboard, View anchor) {
        if (!anchor.isAttachedToWindow()) {
            return;
        }
        int cleared = invokeInt(keyboard, CLEAR_METHOD);
        if (cleared < 0) {
            Log.w(TAG, "Clearing unpinned clips failed");
        }
    }

    private static int invokeInt(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            Object result = method.invoke(target);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Throwable t) {
            Log.w(TAG, "Clipboard helper " + name + " failed", t);
            return -1;
        }
    }

    private static void dismissShowing() {
        AlertDialog previous = showingDialog.get();
        if (previous != null) {
            dismissQuietly(previous);
        }
        showingDialog = new WeakReference<>(null);
    }

    private static void dismissQuietly(AlertDialog dialog) {
        try {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Throwable ignored) {
            // The keyboard window may already be gone.
        }
    }
}
