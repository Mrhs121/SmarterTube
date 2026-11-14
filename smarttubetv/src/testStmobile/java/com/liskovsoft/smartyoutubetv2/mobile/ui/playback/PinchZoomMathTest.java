package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pure JVM math tests for {@link PinchZoom} angle normalization, 90-degree snap thresholds,
 * rotated fit-scaling, and pan translation clamping.
 */
public class PinchZoomMathTest {
    private static final float EPS = 1e-3f;

    @Test
    public void normalizeAngleDelta_wrapsAcrossPiBoundary() {
        assertEquals(0f, PinchZoom.normalizeAngleDelta(0f), EPS);
        assertEquals(20f, PinchZoom.normalizeAngleDelta(20f), EPS);
        assertEquals(-20f, PinchZoom.normalizeAngleDelta(340f), EPS);
        assertEquals(20f, PinchZoom.normalizeAngleDelta(-340f), EPS);
        assertEquals(180f, PinchZoom.normalizeAngleDelta(180f), EPS);
        assertEquals(-180f, PinchZoom.normalizeAngleDelta(-180f), EPS);
        assertEquals(-10f, PinchZoom.normalizeAngleDelta(350f), EPS);
        assertEquals(10f, PinchZoom.normalizeAngleDelta(-350f), EPS);
    }

    @Test
    public void normalizeFixedAngle_mapsToFourRightAngles() {
        assertEquals(0, PinchZoom.normalizeFixedAngle(0));
        assertEquals(90, PinchZoom.normalizeFixedAngle(90));
        assertEquals(180, PinchZoom.normalizeFixedAngle(180));
        assertEquals(270, PinchZoom.normalizeFixedAngle(270));
        assertEquals(0, PinchZoom.normalizeFixedAngle(360));
        assertEquals(270, PinchZoom.normalizeFixedAngle(-90));
        assertEquals(180, PinchZoom.normalizeFixedAngle(-180));
        assertEquals(90, PinchZoom.normalizeFixedAngle(-270));
        assertEquals(90, PinchZoom.normalizeFixedAngle(88));
        assertEquals(0, PinchZoom.normalizeFixedAngle(355));
    }

    @Test
    public void snapDeltaTo90_respectsDeadzoneAndThresholds() {
        assertEquals(0, PinchZoom.snapDeltaTo90(0f));
        assertEquals(0, PinchZoom.snapDeltaTo90(27.9f));
        assertEquals(0, PinchZoom.snapDeltaTo90(-27.9f));

        assertEquals(90, PinchZoom.snapDeltaTo90(28f));
        assertEquals(90, PinchZoom.snapDeltaTo90(90f));
        assertEquals(90, PinchZoom.snapDeltaTo90(119.9f));

        assertEquals(-90, PinchZoom.snapDeltaTo90(-28f));
        assertEquals(-90, PinchZoom.snapDeltaTo90(-90f));
        assertEquals(-90, PinchZoom.snapDeltaTo90(-119.9f));

        assertEquals(180, PinchZoom.snapDeltaTo90(120f));
        assertEquals(180, PinchZoom.snapDeltaTo90(-120f));
    }

    @Test
    public void baseFitScale_fitsRotatedVideoInsideLandscapeAndPortraitParents() {
        // 16:9 video (1920x1080) inside 16:9 landscape parent (1920x1080)
        assertEquals(1f, PinchZoom.baseFitScale(0f, 1920, 1080, 1920, 1080), EPS);
        assertEquals(1f, PinchZoom.baseFitScale(180f, 1920, 1080, 1920, 1080), EPS);
        // Rotated 90 deg -> visual aspect swaps to 1080x1920; fitting height 1920 into 1080 -> 1080/1920 = 0.5625
        assertEquals(1080f / 1920f, PinchZoom.baseFitScale(90f, 1920, 1080, 1920, 1080), EPS);
        assertEquals(1080f / 1920f, PinchZoom.baseFitScale(270f, 1920, 1080, 1920, 1080), EPS);

        // Portrait fullscreen tablet/phone parent: 1080x1920, centered 16:9 video strip is 1080x607
        // Rotated 90 deg -> visual size 607x1080 inside 1080x1920 can even scale UP to fill width/height (1920/1080 = 1.777...)
        assertEquals(1f, PinchZoom.baseFitScale(0f, 1080, 607, 1080, 1920), EPS);
        float expectedPortraitFit = Math.min(1080f / 607f, 1920f / 1080f);
        assertEquals(expectedPortraitFit, PinchZoom.baseFitScale(90f, 1080, 607, 1080, 1920), EPS);
    }

    @Test
    public void clampTranslation_keepsUnzoomedCenteredAndClampsZoomedPan() {
        // Centered 1920x1080 video at (0,0) inside 1920x1080 parent
        float[] unzoomed = PinchZoom.clampTranslation(150f, -200f, 0f, 1f, 1920, 1080, 0, 0, 1920, 1080);
        assertEquals(0f, unzoomed[0], EPS);
        assertEquals(0f, unzoomed[1], EPS);

        // Zoom 2x -> vis = 3840x2160, max offset = (960, 540)
        float[] panned = PinchZoom.clampTranslation(1200f, -800f, 0f, 2f, 1920, 1080, 0, 0, 1920, 1080);
        assertEquals(960f, panned[0], EPS);
        assertEquals(-540f, panned[1], EPS);

        float[] withinBounds = PinchZoom.clampTranslation(300f, -150f, 0f, 2f, 1920, 1080, 0, 0, 1920, 1080);
        assertEquals(300f, withinBounds[0], EPS);
        assertEquals(-150f, withinBounds[1], EPS);

        // Centered landscape video rotated 90 deg in landscape parent (baseFit = 0.5625) at 2x user zoom -> totalScale = 1.125
        // visW = 1080 * 1.125 = 1215 (<= 1920 -> locked to center 0)
        // visH = 1920 * 1.125 = 2160 (> 1080 -> maxOffY = (2160 - 1080) / 2 = 540)
        float totalScale = 2f * PinchZoom.baseFitScale(90f, 1920, 1080, 1920, 1080);
        float[] rotatedPan = PinchZoom.clampTranslation(400f, 700f, 90f, totalScale, 1920, 1080, 0, 0, 1920, 1080);
        assertEquals(0f, rotatedPan[0], EPS);
        assertEquals(540f, rotatedPan[1], EPS);
    }

    @Test
    public void clampTranslation_recentersOffsetChildView() {
        // Video strip 1080x608 shifted to vLeft=100, vTop=200 inside 1080x1920 portrait parent:
        // centerTx = 540 - (100 + 540) = -100, centerTy = 960 - (200 + 304) = 456
        float[] centered = PinchZoom.clampTranslation(0f, 0f, 0f, 1f, 1080, 608, 100, 200, 1080, 1920);
        assertEquals(540f - 640f, centered[0], EPS);
        assertEquals(960f - 504f, centered[1], EPS);
    }
}
