package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

/**
 * Pure decision and geometry helpers for mobile player navigation, strip gestures, and the
 * floating bottom-right mini-player. Extracted for direct JVM unit testing.
 */
final class PlaybackNavigationRules {
    static final int STRIP_SWIPE_NONE = 0;
    static final int STRIP_SWIPE_UP = 1;
    static final int STRIP_SWIPE_DOWN = -1;

    private static final float SWIPE_DIRECTION_RATIO = 1.8f;
    // Mini-player width = 1.2x a Home 2-column preview card width (6dp outer + 6dp card padding).
    private static final float MINI_HOME_CARD_SCALE = 1.2f;
    private static final float MINI_MIN_WIDTH_DP = 180f;
    private static final float MINI_MAX_WIDTH_DP = 440f;

    private PlaybackNavigationRules() {
    }

    /**
     * Back in fullscreen exits fullscreen instead of closing the player.
     * Shorts do not have a strip/fullscreen toggle, and PiP is left to the system.
     */
    static boolean shouldExitFullscreenOnBack(
            boolean isInPip,
            boolean isShorts,
            boolean isPortraitFullscreen,
            boolean isLandscape) {
        if (isInPip || isShorts) {
            return false;
        }
        return isPortraitFullscreen || isLandscape;
    }

    /**
     * Classifies a vertical swipe gesture in the non-fullscreen 16:9 strip.
     */
    static int classifyStripSwipe(float dx, float dy, float minDistancePx) {
        if (Math.abs(dy) <= minDistancePx || Math.abs(dy) <= SWIPE_DIRECTION_RATIO * Math.abs(dx)) {
            return STRIP_SWIPE_NONE;
        }
        return dy < 0f ? STRIP_SWIPE_UP : STRIP_SWIPE_DOWN;
    }

    /**
     * Whether releasing a downward drag on the floating mini-player should dismiss playback.
     */
    static boolean shouldDismissMiniPlayer(
            float dy,
            float velocityY,
            float distanceThresholdPx,
            float velocityThresholdPx) {
        if (dy <= 0f) {
            return false;
        }
        return dy >= distanceThresholdPx || (dy >= distanceThresholdPx * 0.35f && velocityY >= velocityThresholdPx);
    }

    /**
     * Responsive 16:9 width for the bottom-right mini-player: 1.2x the width of a portrait
     * Home 2-column preview card, clamped between sensible phone/tablet bounds.
     */
    static int computeMiniCardWidthPx(int screenWidthPx, float density) {
        float safeDensity = density > 0f ? density : 1f;
        float padPx = 12f * safeDensity;
        float homeCardWidthPx = Math.max(0f, (screenWidthPx - padPx) / 2f - padPx);
        float minPx = MINI_MIN_WIDTH_DP * safeDensity;
        float maxPx = MINI_MAX_WIDTH_DP * safeDensity;
        float target = homeCardWidthPx * MINI_HOME_CARD_SCALE;
        return Math.round(Math.max(minPx, Math.min(maxPx, target)));
    }

    /** Normalized [0..1] drag progress while swiping down to minimize from the portrait strip. */
    static float computeStripDragProgress(float dyPx, float maxDragPx) {
        if (dyPx <= 0f || maxDragPx <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, dyPx / maxDragPx));
    }

    /** Fades out below-video content earlier than the video shrink so only the video box stays visible. */
    static float computeStripPanelAlpha(float dragProgress) {
        return Math.max(0f, Math.min(1f, 1f - dragProgress * 2.2f));
    }

    /** Fades out the black window backdrop so the Home feed underneath becomes clear around mid-drag. */
    static float computeStripBackdropAlpha(float dragProgress) {
        return Math.max(0f, Math.min(1f, 1f - dragProgress / 0.55f));
    }

    /** Underlay blur intensity [0..1] during strip drag: blurred early, completely sharp by mid-drag (0.55). */
    static float computeStripUnderlayBlur(float dragProgress) {
        if (dragProgress <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, 1f - dragProgress / 0.55f));
    }

    /** Whether releasing a downward strip drag should commit into the floating mini-player. */
    static boolean shouldCommitStripMinimize(
            float dragProgress,
            float dyPx,
            float velocityYPx,
            float minDistancePx,
            float minVelocityPx) {
        if (dyPx <= 0f) {
            return false;
        }
        return dragProgress >= 0.35f
                || dyPx >= minDistancePx
                || (velocityYPx >= minVelocityPx && dyPx >= minDistancePx * 0.4f);
    }

    static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }
}
