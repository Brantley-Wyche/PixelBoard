/**
 * PixelBoard - Gboard Enhancement Mod
 *
 * Maintained and customized by Akshay Kadam (@Akshayykadam)
 * Repository: https://github.com/Akshayykadam/PixelBoard
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.keyboard;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Material 3 Expressive spring and easing motion primitives shared across
 * keyboard animations (sunburst toolbar buttons, key presses, popups,
 * panel/toolbar transitions, and clipboard dialogs).
 *
 * <p>The springs use the expressive motion scheme's tokens from Compose
 * Material 3 1.5.0 ({@code ExpressiveMotionTokens}: damping ratio and
 * stiffness, unit mass). Compose can't run inside Gboard, so each spring is
 * solved analytically and played through a {@link ValueAnimator}: the
 * duration is the spring's settling time, and the interpolator follows the
 * real spring over that time.</p>
 */
public final class M3ExpressiveMotion {
    static final float FAST_SPATIAL_DAMPING = 0.6f;
    static final float FAST_SPATIAL_STIFFNESS = 800f;
    static final float DEFAULT_SPATIAL_DAMPING = 0.8f;
    static final float DEFAULT_SPATIAL_STIFFNESS = 380f;
    static final float SLOW_SPATIAL_DAMPING = 0.8f;
    static final float SLOW_SPATIAL_STIFFNESS = 200f;
    static final float FAST_EFFECTS_DAMPING = 1.0f;
    static final float FAST_EFFECTS_STIFFNESS = 3800f;

    /**
     * A spring counts as settled once it stays within this fraction of its
     * travel; past that point the remaining motion is below a pixel.
     */
    static final float SETTLE_THRESHOLD = 0.004f;

    /** Press-in is a quick tactile ease, not a spring, so a tap feels instant. */
    public static final long DURATION_PRESS_IN_MS = 65L;
    public static final long DURATION_SUNBURST_PRESS_MS = 110L;

    public static final long DURATION_FAST_SPATIAL_MS =
            settleMillis(FAST_SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS);
    public static final long DURATION_DEFAULT_SPATIAL_MS =
            settleMillis(DEFAULT_SPATIAL_DAMPING, DEFAULT_SPATIAL_STIFFNESS);
    public static final long DURATION_SLOW_SPATIAL_MS =
            settleMillis(SLOW_SPATIAL_DAMPING, SLOW_SPATIAL_STIFFNESS);
    public static final long DURATION_EFFECTS_FAST_MS =
            settleMillis(FAST_EFFECTS_DAMPING, FAST_EFFECTS_STIFFNESS);

    private static final TimeInterpolator SPATIAL_FAST =
            new SpringInterpolator(FAST_SPATIAL_DAMPING, FAST_SPATIAL_STIFFNESS);
    private static final TimeInterpolator SPATIAL_DEFAULT =
            new SpringInterpolator(DEFAULT_SPATIAL_DAMPING, DEFAULT_SPATIAL_STIFFNESS);
    private static final TimeInterpolator SPATIAL_SLOW =
            new SpringInterpolator(SLOW_SPATIAL_DAMPING, SLOW_SPATIAL_STIFFNESS);
    private static final TimeInterpolator EFFECTS_FAST =
            new SpringInterpolator(FAST_EFFECTS_DAMPING, FAST_EFFECTS_STIFFNESS);
    private static final TimeInterpolator PRESS_IN = Build.VERSION.SDK_INT >= 21
            ? new PathInterpolator(0.2f, 0f, 0f, 1f)
            : new DecelerateInterpolator(1.5f);

    private M3ExpressiveMotion() {
    }

    /** Small, quick movements: key and button release, key previews. */
    public static TimeInterpolator spatialFast() {
        return SPATIAL_FAST;
    }

    /** Medium movements: the sunburst morph, panel entrance, dialogs. */
    public static TimeInterpolator spatialDefault() {
        return SPATIAL_DEFAULT;
    }

    /** Larger, calmer movements the eye follows, such as toolbar rows gliding. */
    public static TimeInterpolator spatialSlow() {
        return SPATIAL_SLOW;
    }

    public static TimeInterpolator pressIn() {
        return PRESS_IN;
    }

    /** Fades and other non-spatial changes; critically damped, so it never overshoots. */
    public static TimeInterpolator effectsFast() {
        return EFFECTS_FAST;
    }

    /**
     * Returns {@code true} when system animations are enabled and duration scale is non-zero.
     */
    public static boolean shouldAnimate(Context context) {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }
        if (context == null) {
            return true;
        }
        try {
            float scale = Settings.Global.getFloat(
                    context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1.0f);
            return scale > 0.0f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Progress of a unit-mass spring from 0 to 1 at fraction {@code t} of
     * {@code durationSeconds}. The small remainder left at the end of the
     * duration is blended out so the curve lands exactly on 1.
     */
    static float spring(float t, float dampingRatio, float stiffness, float durationSeconds) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        double raw = springResponse(t * durationSeconds, dampingRatio, stiffness);
        double end = springResponse(durationSeconds, dampingRatio, stiffness);
        return (float) (raw + t * (1.0 - end));
    }

    /** Position of a unit-mass spring released from 0 towards 1, at {@code seconds}. */
    private static double springResponse(double seconds, float dampingRatio, float stiffness) {
        double omega = Math.sqrt(stiffness);
        double zeta = Math.min(1.0, Math.max(0.05, dampingRatio));
        if (zeta >= 1.0) {
            return 1.0 - (1.0 + omega * seconds) * Math.exp(-omega * seconds);
        }
        double root = Math.sqrt(1.0 - zeta * zeta);
        double omegaD = omega * root;
        double decay = Math.exp(-zeta * omega * seconds);
        return 1.0 - decay * (Math.cos(omegaD * seconds) + (zeta / root) * Math.sin(omegaD * seconds));
    }

    /** Time for the spring to stay within {@link #SETTLE_THRESHOLD} of its target. */
    static long settleMillis(float dampingRatio, float stiffness) {
        double omega = Math.sqrt(stiffness);
        double zeta = Math.min(1.0, Math.max(0.05, dampingRatio));
        double seconds;
        if (zeta >= 1.0) {
            // (1 + wt) e^(-wt) only falls, so step until it is under the threshold.
            seconds = 0.0;
            while ((1.0 + omega * seconds) * Math.exp(-omega * seconds) > SETTLE_THRESHOLD
                    && seconds < 3.0) {
                seconds += 0.001;
            }
        } else {
            // Underdamped: the error stays under its envelope e^(-zwt) / sqrt(1 - z^2).
            double envelope = 1.0 / Math.sqrt(1.0 - zeta * zeta);
            seconds = Math.log(envelope / SETTLE_THRESHOLD) / (zeta * omega);
        }
        return Math.round(seconds * 1000.0);
    }

    static final class SpringInterpolator implements TimeInterpolator {
        private final float dampingRatio;
        private final float stiffness;
        private final float durationSeconds;

        SpringInterpolator(float dampingRatio, float stiffness) {
            this.dampingRatio = dampingRatio;
            this.stiffness = stiffness;
            this.durationSeconds = settleMillis(dampingRatio, stiffness) / 1000f;
        }

        @Override
        public float getInterpolation(float input) {
            return spring(input, dampingRatio, stiffness, durationSeconds);
        }
    }
}
