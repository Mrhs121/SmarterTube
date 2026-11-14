package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure JVM tests for {@link PlaybackNavigationRules}: fullscreen back-exit decisions,
 * portrait-strip vertical swipe classification, and mini-player dismiss/geometry rules.
 */
public class PlaybackNavigationRulesTest {
    @Test
    public void shouldExitFullscreenOnBack_matchesFullscreenStatesOnly() {
        assertTrue(PlaybackNavigationRules.shouldExitFullscreenOnBack(false, false, false, true));
        assertTrue(PlaybackNavigationRules.shouldExitFullscreenOnBack(false, false, true, false));
        assertTrue(PlaybackNavigationRules.shouldExitFullscreenOnBack(false, false, true, true));

        assertFalse(PlaybackNavigationRules.shouldExitFullscreenOnBack(false, false, false, false));
        assertFalse(PlaybackNavigationRules.shouldExitFullscreenOnBack(true, false, false, true));
        assertFalse(PlaybackNavigationRules.shouldExitFullscreenOnBack(false, true, true, true));
    }

    @Test
    public void classifyStripSwipe_distinguishesUpDownAndHorizontalNoise() {
        float minDist = 144f; // 48dp * 3

        assertEquals(
                PlaybackNavigationRules.STRIP_SWIPE_UP,
                PlaybackNavigationRules.classifyStripSwipe(20f, -180f, minDist));
        assertEquals(
                PlaybackNavigationRules.STRIP_SWIPE_DOWN,
                PlaybackNavigationRules.classifyStripSwipe(20f, 180f, minDist));

        // Short movement or mostly horizontal scrub must not trigger strip swipe.
        assertEquals(
                PlaybackNavigationRules.STRIP_SWIPE_NONE,
                PlaybackNavigationRules.classifyStripSwipe(0f, 100f, minDist));
        assertEquals(
                PlaybackNavigationRules.STRIP_SWIPE_NONE,
                PlaybackNavigationRules.classifyStripSwipe(150f, 180f, minDist));
    }

    @Test
    public void shouldDismissMiniPlayer_requiresDownwardDistanceOrFastFlick() {
        float dist = 132f;
        float vel = 1200f;

        assertFalse(PlaybackNavigationRules.shouldDismissMiniPlayer(-50f, -2000f, dist, vel));
        assertFalse(PlaybackNavigationRules.shouldDismissMiniPlayer(80f, 200f, dist, vel));
        assertTrue(PlaybackNavigationRules.shouldDismissMiniPlayer(140f, 50f, dist, vel));
        assertTrue(PlaybackNavigationRules.shouldDismissMiniPlayer(60f, 1500f, dist, vel));
        // Tiny jittery downward movement should not dismiss even with noisy velocity.
        assertFalse(PlaybackNavigationRules.shouldDismissMiniPlayer(20f, 2000f, dist, vel));
    }

    @Test
    public void computeMiniCardWidthPx_matches1_2xHomeCardAcrossPhonesAndTablets() {
        // 1080px phone @3x: home card = (1080 - 36)/2 - 36 = 486px -> 1.2x = 583px.
        assertEquals(Math.round(((1080f - 36f) / 2f - 36f) * 1.2f),
                PlaybackNavigationRules.computeMiniCardWidthPx(1080, 3f));
        // Tablet (~1880px, ~2.8125 density): home card ~889px -> 1.2x = ~1067px.
        float tabletPad = 12f * 2.8125f;
        assertEquals(Math.round(((1880f - tabletPad) / 2f - tabletPad) * 1.2f),
                PlaybackNavigationRules.computeMiniCardWidthPx(1880, 2.8125f));
        // Very wide landscape screen clamps at 440dp max.
        assertEquals(440 * 3, PlaybackNavigationRules.computeMiniCardWidthPx(2880, 3f));
    }

    @Test
    public void stripDragProgressAndPanelAlpha_fadeBelowContentBeforeFinish() {
        assertEquals(0f, PlaybackNavigationRules.computeStripDragProgress(-20f, 300f), 0.001f);
        assertEquals(0.5f, PlaybackNavigationRules.computeStripDragProgress(150f, 300f), 0.001f);
        assertEquals(1f, PlaybackNavigationRules.computeStripDragProgress(450f, 300f), 0.001f);

        assertEquals(1f, PlaybackNavigationRules.computeStripPanelAlpha(0f), 0.001f);
        assertTrue(PlaybackNavigationRules.computeStripPanelAlpha(0.35f) < 0.3f);
        assertEquals(0f, PlaybackNavigationRules.computeStripPanelAlpha(0.55f), 0.001f);

        // Backdrop and Home blur fade to 0 (completely clear & sharp) right around mid-drag (0.55).
        assertEquals(1f, PlaybackNavigationRules.computeStripBackdropAlpha(0f), 0.001f);
        assertTrue(PlaybackNavigationRules.computeStripBackdropAlpha(0.275f) > 0.45f);
        assertEquals(0f, PlaybackNavigationRules.computeStripBackdropAlpha(0.55f), 0.001f);
        assertEquals(0f, PlaybackNavigationRules.computeStripUnderlayBlur(0f), 0.001f);
        assertTrue(PlaybackNavigationRules.computeStripUnderlayBlur(0.2f) > 0.5f);
        assertEquals(0f, PlaybackNavigationRules.computeStripUnderlayBlur(0.55f), 0.001f);

        assertFalse(PlaybackNavigationRules.shouldCommitStripMinimize(0.2f, 80f, 100f, 144f, 1500f));
        assertTrue(PlaybackNavigationRules.shouldCommitStripMinimize(0.4f, 120f, 50f, 144f, 1500f));
        assertTrue(PlaybackNavigationRules.shouldCommitStripMinimize(0.15f, 80f, 2000f, 144f, 1500f));
    }
}
