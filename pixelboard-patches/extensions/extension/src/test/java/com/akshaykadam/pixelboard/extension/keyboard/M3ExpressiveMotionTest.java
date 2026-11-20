package com.akshaykadam.pixelboard.extension.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.animation.TimeInterpolator;

import org.junit.Test;

public class M3ExpressiveMotionTest {

    @Test
    public void springsUseTheOfficialExpressiveMotionTokens() {
        // Compose Material 3 1.5.0 ExpressiveMotionTokens.
        assertEquals(0.6f, M3ExpressiveMotion.FAST_SPATIAL_DAMPING, 0f);
        assertEquals(800f, M3ExpressiveMotion.FAST_SPATIAL_STIFFNESS, 0f);
        assertEquals(0.8f, M3ExpressiveMotion.DEFAULT_SPATIAL_DAMPING, 0f);
        assertEquals(380f, M3ExpressiveMotion.DEFAULT_SPATIAL_STIFFNESS, 0f);
        assertEquals(0.8f, M3ExpressiveMotion.SLOW_SPATIAL_DAMPING, 0f);
        assertEquals(200f, M3ExpressiveMotion.SLOW_SPATIAL_STIFFNESS, 0f);
        assertEquals(1.0f, M3ExpressiveMotion.FAST_EFFECTS_DAMPING, 0f);
        assertEquals(3800f, M3ExpressiveMotion.FAST_EFFECTS_STIFFNESS, 0f);
    }

    @Test
    public void springStartsAtZeroAndEndsAtOne() {
        assertEquals(0f, M3ExpressiveMotion.spring(0f, 0.6f, 800f, 0.4f), 0.0001f);
        assertEquals(1f, M3ExpressiveMotion.spring(1f, 0.6f, 800f, 0.4f), 0.0001f);
        assertEquals(0f, M3ExpressiveMotion.spring(-0.2f, 1.0f, 3800f, 0.12f), 0.0001f);
        assertEquals(1f, M3ExpressiveMotion.spring(1.3f, 1.0f, 3800f, 0.12f), 0.0001f);
    }

    @Test
    public void fastSpatialOvershootsLikeADampingRatioOfPointSix() {
        // exp(-pi * 0.6 / sqrt(1 - 0.36)) = 9.5% overshoot.
        float peak = peak(M3ExpressiveMotion.spatialFast());
        assertTrue("Fast spatial peak was " + peak, peak > 1.08f && peak < 1.11f);
    }

    @Test
    public void defaultAndSlowSpatialOnlyBarelyOvershoot() {
        // exp(-pi * 0.8 / 0.6) = 1.5% overshoot.
        float defaultPeak = peak(M3ExpressiveMotion.spatialDefault());
        float slowPeak = peak(M3ExpressiveMotion.spatialSlow());
        assertTrue("Default spatial peak was " + defaultPeak, defaultPeak > 1.01f && defaultPeak < 1.02f);
        assertTrue("Slow spatial peak was " + slowPeak, slowPeak > 1.01f && slowPeak < 1.02f);
        assertEquals(1f, M3ExpressiveMotion.spatialSlow().getInterpolation(1f), 0.0001f);
    }

    @Test
    public void effectsSpringsNeverOvershootOrReverse() {
        TimeInterpolator effects = M3ExpressiveMotion.effectsFast();
        float previous = 0f;
        for (int i = 1; i <= 200; i++) {
            float value = effects.getInterpolation(i / 200f);
            assertTrue("Effects spring overshot to " + value, value <= 1.0001f);
            assertTrue("Effects spring moved backwards", value >= previous - 0.0001f);
            previous = value;
        }
    }

    @Test
    public void durationsComeFromEachSpringsSettlingTime() {
        assertTrue(M3ExpressiveMotion.DURATION_EFFECTS_FAST_MS < M3ExpressiveMotion.DURATION_FAST_SPATIAL_MS);
        assertTrue(M3ExpressiveMotion.DURATION_FAST_SPATIAL_MS < M3ExpressiveMotion.DURATION_DEFAULT_SPATIAL_MS);
        assertTrue(M3ExpressiveMotion.DURATION_DEFAULT_SPATIAL_MS < M3ExpressiveMotion.DURATION_SLOW_SPATIAL_MS);
        assertTrue(M3ExpressiveMotion.DURATION_EFFECTS_FAST_MS >= 80L
                && M3ExpressiveMotion.DURATION_EFFECTS_FAST_MS <= 160L);
        assertTrue(M3ExpressiveMotion.DURATION_FAST_SPATIAL_MS >= 250L
                && M3ExpressiveMotion.DURATION_FAST_SPATIAL_MS <= 400L);
        assertTrue(M3ExpressiveMotion.DURATION_SLOW_SPATIAL_MS <= 600L);
    }

    @Test
    public void springHasSettledWhenItsDurationEnds() {
        // Just before the end, the raw spring is already within the settling threshold.
        float nearEnd = M3ExpressiveMotion.spatialFast().getInterpolation(0.99f);
        assertEquals(1f, nearEnd, 0.006f);
    }

    @Test
    public void alignedRotationStepsForwardBySunburstTooth() {
        assertEquals(30f, GboardExpressiveLookRuntime.nextAlignedRotation(0f), 0.001f);
        assertEquals(60f, GboardExpressiveLookRuntime.nextAlignedRotation(30f), 0.001f);
        assertEquals(60f, GboardExpressiveLookRuntime.nextAlignedRotation(31.5f), 0.001f);
        assertEquals(120f, GboardExpressiveLookRuntime.nextAlignedRotation(90f), 0.001f);
    }

    @Test
    public void keyPressScaleUsesSofterHorizontalSquishForWideKeys() {
        float[] regular = GboardExpressiveLookRuntime.keyPressScale(100, 120);
        float[] wide = GboardExpressiveLookRuntime.keyPressScale(480, 120);

        assertEquals(regular[0], regular[1], 0.001f);
        assertTrue(regular[0] < 1.0f && regular[0] > 0.85f);
        assertTrue(wide[0] > regular[0]);
        assertTrue(wide[1] < 1.0f);
    }

    @Test
    public void sunburstRadiusSupportsAnimatedDepthMorph() {
        float subtleValley = GboardExpressiveLookRuntime.sunburstRadius(40f, Math.PI / 12, 0.06f);
        float deepValley = GboardExpressiveLookRuntime.sunburstRadius(40f, Math.PI / 12, 0.095f);
        assertTrue("Pressed depth should deepen valleys", deepValley < subtleValley);
        assertEquals(40f, GboardExpressiveLookRuntime.sunburstRadius(40f, 0.0, 0.095f), 0.001f);
    }

    private static float peak(TimeInterpolator interpolator) {
        float peak = 0f;
        for (int i = 1; i < 400; i++) {
            peak = Math.max(peak, interpolator.getInterpolation(i / 400f));
        }
        return peak;
    }
}
