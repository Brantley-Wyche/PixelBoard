package com.akshaykadam.pixelboard.extension.clipboard;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

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
            KeyboardPalette palette = KeyboardPalette.from(anchor);
            AlertDialog.Builder builder = new AlertDialog.Builder(context, palette.dark
                    ? android.R.style.Theme_Material_Dialog_Alert
                    : android.R.style.Theme_Material_Light_Dialog_Alert);
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
            palette.applyWindow(window, context);
            dialog.setOnShowListener(d -> palette.applyContent(dialog));

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

    /**
     * Colors and font read off the live clipboard views, so the dialog follows whatever
     * keyboard theme is active. Gboard themes its views at runtime with its own theme
     * engine, so Android theme attributes don't carry these colors.
     */
    static final class KeyboardPalette {
        private static final float CORNER_RADIUS_DP = 28f;
        private static final float HORIZONTAL_INSET_DP = 24f;
        private static final String LABEL_TAG = ".label";

        final boolean dark;
        final int surface;
        final int onSurface;
        final int onSurfaceVariant;
        final int accent;
        final Typeface typeface;
        private final boolean sampled;

        private KeyboardPalette(Integer surface, Integer onSurface, Typeface typeface, Context context) {
            this.sampled = surface != null;
            this.surface = surface != null ? surface : 0xFF2B2930;
            this.dark = isDark(this.surface);
            this.onSurface = onSurface != null ? onSurface : (dark ? 0xFFE6E0E9 : 0xFF1D1B20);
            this.onSurfaceVariant = withAlpha(this.onSurface, 0xCC);
            this.accent = accentColor(context, dark, this.onSurface);
            this.typeface = typeface;
        }

        static KeyboardPalette from(View anchor) {
            Integer surface = null;
            Integer text = null;
            Typeface typeface = null;
            if (anchor instanceof ViewGroup) {
                ViewGroup list = (ViewGroup) anchor;
                // Prefer a card that isn't selected, in case selection tints its background.
                for (int i = 0; i < list.getChildCount() * 2 && surface == null; i++) {
                    View card = list.getChildAt(i % list.getChildCount());
                    boolean firstPass = i < list.getChildCount();
                    TextView label = findLabel(card);
                    if (label == null || card.getBackground() == null
                            || (firstPass && (card.isSelected() || card.isActivated()))) {
                        continue;
                    }
                    Integer color = backgroundColor(card);
                    if (color != null) {
                        surface = color;
                        text = label.getCurrentTextColor();
                        typeface = label.getTypeface();
                    }
                }
            }
            if (surface == null) {
                // No clip card on screen: fall back to the keyboard's own background.
                for (View view = anchor; view != null; ) {
                    if (view.getBackground() != null) {
                        surface = backgroundColor(view);
                        if (surface != null) {
                            break;
                        }
                    }
                    ViewParent parent = view.getParent();
                    view = parent instanceof View ? (View) parent : null;
                }
            }
            return new KeyboardPalette(surface, text, typeface, anchor.getContext());
        }

        void applyWindow(Window window, Context context) {
            if (!sampled) {
                return;
            }
            float density = context.getResources().getDisplayMetrics().density;
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(surface);
            shape.setCornerRadius(CORNER_RADIUS_DP * density);
            int inset = Math.round(HORIZONTAL_INSET_DP * density);
            window.setBackgroundDrawable(new InsetDrawable(shape, inset, 0, inset, 0));
        }

        void applyContent(AlertDialog dialog) {
            if (!sampled) {
                return;
            }
            try {
                int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
                if (titleId != 0) {
                    style(dialog.findViewById(titleId), onSurface);
                }
                style(dialog.findViewById(android.R.id.message), onSurfaceVariant);
                for (int which : new int[] {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE,
                        DialogInterface.BUTTON_NEUTRAL}) {
                    Button button = dialog.getButton(which);
                    if (button != null) {
                        style(button, accent);
                        button.setAllCaps(false);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not theme the clear clipboard dialog", t);
            }
        }

        private void style(View view, int color) {
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                text.setTextColor(color);
                if (typeface != null) {
                    text.setTypeface(Typeface.create(typeface, text.getTypeface() != null
                            ? text.getTypeface().getStyle() : Typeface.NORMAL));
                }
            }
        }

        private static TextView findLabel(View view) {
            if (view instanceof TextView && LABEL_TAG.equals(view.getTag())
                    && ((TextView) view).length() > 0) {
                return (TextView) view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    TextView found = findLabel(group.getChildAt(i));
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        /**
         * Most common opaque color of the view's background, drawn at a quarter size.
         * The drawable is drawn at its own bounds into a scaled canvas and never resized:
         * changing its bounds makes the view rebuild its clip outline at the wrong size.
         */
        private static Integer backgroundColor(View view) {
            Drawable background = view.getBackground();
            if (background == null) {
                return null;
            }
            Rect bounds = background.getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return null;
            }
            int width = Math.max(1, bounds.width() / 4);
            int height = Math.max(1, bounds.height() / 4);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.scale(width / (float) bounds.width(), height / (float) bounds.height());
                canvas.translate(-bounds.left, -bounds.top);
                background.draw(canvas);
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                return modeOpaqueColor(pixels);
            } finally {
                bitmap.recycle();
            }
        }
    }

    /** The most frequent nearly opaque color, or null when there is none. */
    static Integer modeOpaqueColor(int[] pixels) {
        Map<Integer, Integer> counts = new HashMap<>();
        Integer best = null;
        int bestCount = 0;
        for (int pixel : pixels) {
            if ((pixel >>> 24) < 0xF0) {
                continue;
            }
            int color = pixel | 0xFF000000;
            int count = counts.merge(color, 1, Integer::sum);
            if (count > bestCount) {
                best = color;
                bestCount = count;
            }
        }
        return best;
    }

    static boolean isDark(int color) {
        double r = channel((color >> 16) & 0xFF);
        double g = channel((color >> 8) & 0xFF);
        double b = channel(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b < 0.25;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Material You accent, the same tone family the Expressive look's sunburst uses. */
    private static int accentColor(Context context, boolean dark, int fallback) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return context.getResources().getColor(dark
                        ? android.R.color.system_accent1_200
                        : android.R.color.system_accent1_600, null);
            } catch (Throwable ignored) {
                // Fall back to the text color.
            }
        }
        return fallback;
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
