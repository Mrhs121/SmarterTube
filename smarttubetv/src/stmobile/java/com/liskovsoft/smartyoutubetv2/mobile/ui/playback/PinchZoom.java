package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Locale;

/**
 * Pinch to zoom, pan after zoom, and two-finger 90-degree-step rotation in the fullscreen player:
 * <ul>
 *   <li>Spreading two fingers zooms the video smoothly around their midpoint (up to 5x) and pans
 *   as they move.</li>
 *   <li>Once zoomed in (&gt; 100%), dragging with a single finger pans around the zoomed picture;
 *   quick taps and double taps still pass through untouched.</li>
 *   <li>Twisting two fingers rotates the video and snaps to the four fixed angles (0°, 90°, 180°,
 *   270°), automatically fitting 90°/270° within the screen bounds at 100% zoom.</li>
 * </ul>
 */
final class PinchZoom {
    /** The video view to transform (null while the player has none). */
    interface Target {
        View video();
    }

    /** Re-dispatches an event through the player's normal (non-pan) touch handling. */
    interface Router {
        boolean route(MotionEvent event);
    }

    static final float MAX_SCALE = 5f;
    static final float SNAP_TO_FIT = 1.05f;
    static final float ROTATION_UNLOCK_DEG = 16f;
    static final float ROTATION_STEP_THRESHOLD_DEG = 28f;
    static final float ROTATION_180_THRESHOLD_DEG = 120f;
    private static final int LABEL_HIDE_MS = 900;
    private static final int SNAP_ANIM_MS = 160;
    private static final int INVALID_POINTER_ID = -1;

    private static final int PAN_IDLE = 0;
    private static final int PAN_PENDING = 1;
    private static final int PAN_ACTIVE = 2;

    private final Target mTarget;
    private final TextView mLabel;
    private final ScaleGestureDetector mDetector;
    private final int mTouchSlop;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideLabel;

    private boolean mActive;
    private View mVideo;
    private View mObservedParent;
    private float mScale = 1f;
    private int mRotationDeg = 0; // fixed 0, 90, 180, 270
    private float mLiveAngleOffset = 0f;
    private float mTx = 0f;
    private float mTy = 0f;
    private boolean mFlipped = false;
    private boolean mTransformOwned = false;
    private float mFocusX, mFocusY;

    // Two-finger rotation state
    private int mPtrId1 = INVALID_POINTER_ID;
    private int mPtrId2 = INVALID_POINTER_ID;
    private float mPrevAngleDeg;
    private float mAccumAngleDeg;
    private boolean mRotationUnlocked;
    private ValueAnimator mSnapAnimator;

    // One-finger pan state (when zoomed in)
    private int mPanState = PAN_IDLE;
    private MotionEvent mPendingDown;
    private float mPanDownX, mPanDownY;
    private float mLastPanX, mLastPanY;

    private final View.OnLayoutChangeListener mLayoutListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (mVideo != null && (mScale > 1f || mRotationDeg != 0 || mLiveAngleOffset != 0f)) {
                    apply();
                }
            };

    /** Returns null if the label isn't in the player layout. */
    static PinchZoom create(Activity activity, Target target) {
        View label = activity.findViewById(R.id.mobile_zoom_label);
        return label instanceof TextView ? new PinchZoom(activity, (TextView) label, target) : null;
    }

    private PinchZoom(Activity activity, TextView label, Target target) {
        mTarget = target;
        mLabel = label;
        mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        mHideLabel = () -> mLabel.setVisibility(View.GONE);
        mDetector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (!ensureVideoBound()) {
                    return false;
                }
                mFocusX = toParentX(detector.getFocusX());
                mFocusY = toParentY(detector.getFocusY());
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                View video = mVideo;
                View parent = video != null ? (View) video.getParent() : null;
                if (video == null || parent == null || video.getWidth() == 0 || parent.getWidth() == 0) {
                    return false;
                }
                cancelSnapAnimator();
                float fx = toParentX(detector.getFocusX());
                float fy = toParentY(detector.getFocusY());

                float angle = currentDisplayAngle();
                float oldTotal = mScale * baseFitScale(angle, video.getWidth(), video.getHeight(),
                        parent.getWidth(), parent.getHeight());
                float newScale = Math.max(1f, Math.min(MAX_SCALE, mScale * detector.getScaleFactor()));
                float newTotal = newScale * baseFitScale(angle, video.getWidth(), video.getHeight(),
                        parent.getWidth(), parent.getHeight());

                float c0x = video.getLeft() + video.getWidth() / 2f;
                float c0y = video.getTop() + video.getHeight() / 2f;
                float cx = c0x + mTx + (fx - mFocusX);
                float cy = c0y + mTy + (fy - mFocusY);
                float ratio = oldTotal > 0f ? newTotal / oldTotal : 1f;
                cx = fx - (fx - cx) * ratio;
                cy = fy - (fy - cy) * ratio;

                mTx = cx - c0x;
                mTy = cy - c0y;
                mFocusX = fx;
                mFocusY = fy;
                mScale = newScale;
                mTransformOwned = true;
                apply();
                showStatusLabel();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                finishZoomSnapIfNeeded();
            }
        });
        // No quick-scale (double tap and drag): double tap already seeks.
        if (Build.VERSION.SDK_INT >= 19) {
            mDetector.setQuickScaleEnabled(false);
        }
    }

    /** Whether a two-finger pinch/rotate owns the current touch sequence. */
    boolean isActive() {
        return mActive;
    }

    /** Whether the video is zoomed in (&gt; 100%). */
    boolean isZoomed() {
        return mScale > 1f;
    }

    /** Current fixed rotation angle (0, 90, 180, or 270). */
    int getRotationDeg() {
        return mRotationDeg;
    }

    /** Whether a single-finger pan (or pending tap inside zoomed state) is active. */
    boolean isPanning() {
        return mPanState != PAN_IDLE;
    }

    /** Start watching a single-finger touch while zoomed in. */
    boolean startPan(MotionEvent down) {
        clearPendingDown();
        if (!ensureVideoBound()) {
            return false;
        }
        mPendingDown = MotionEvent.obtain(down);
        mPanDownX = down.getX();
        mPanDownY = down.getY();
        mLastPanX = mPanDownX;
        mLastPanY = mPanDownY;
        mPanState = PAN_PENDING;
        return true;
    }

    /** Handle a single-finger pan gesture, or replay a quick tap if the finger didn't drag. */
    boolean handlePan(MotionEvent event, Router router) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float x = event.getX();
                float y = event.getY();
                if (mPanState == PAN_PENDING) {
                    float dx = x - mPanDownX;
                    float dy = y - mPanDownY;
                    if (dx * dx + dy * dy > (float) mTouchSlop * mTouchSlop) {
                        mPanState = PAN_ACTIVE;
                        clearPendingDown();
                        mLastPanX = x;
                        mLastPanY = y;
                    }
                    return true;
                }
                if (mPanState == PAN_ACTIVE) {
                    mTx += x - mLastPanX;
                    mTy += y - mLastPanY;
                    mLastPanX = x;
                    mLastPanY = y;
                    apply();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
                if (mPanState == PAN_ACTIVE) {
                    mPanState = PAN_IDLE;
                    return true;
                }
                if (mPanState == PAN_PENDING) {
                    return replayPendingPan(event, router);
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                cancelPan();
                return true;
            default:
                return true;
        }
    }

    /** Drop a pending/active single-finger pan (e.g., when a second finger touches down). */
    void cancelPan() {
        mPanState = PAN_IDLE;
        clearPendingDown();
    }

    private boolean replayPendingPan(MotionEvent event, Router router) {
        MotionEvent down = mPendingDown;
        mPendingDown = null;
        mPanState = PAN_IDLE;
        if (down != null) {
            router.route(down);
            down.recycle();
        }
        return router.route(event);
    }

    private void clearPendingDown() {
        if (mPendingDown != null) {
            mPendingDown.recycle();
            mPendingDown = null;
        }
    }

    /**
     * Feed every event of a touch sequence in the fullscreen player. Returns true when this event
     * makes a second finger touch down, so pinch/rotate begins and the caller must cancel what the
     * first finger started.
     */
    boolean watch(MotionEvent event) {
        mDetector.onTouchEvent(event);
        if (!mActive && event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            if (!ensureVideoBound()) {
                return false;
            }
            cancelPan();
            beginRotationPointers(event);
            mActive = true;
            return true;
        }
        return false;
    }

    /** The rest of an active two-finger pinch/rotate: always consumed, until the last finger lifts. */
    boolean handle(MotionEvent event) {
        mDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mPtrId1 == INVALID_POINTER_ID || mPtrId2 == INVALID_POINTER_ID) {
                    beginRotationPointers(event);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                updateRotationPointers(event);
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int liftedId = event.getPointerId(event.getActionIndex());
                if (liftedId == mPtrId1 || liftedId == mPtrId2) {
                    commitRotationIfNeeded();
                    mPtrId1 = INVALID_POINTER_ID;
                    mPtrId2 = INVALID_POINTER_ID;
                    finishZoomSnapIfNeeded();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                commitRotationIfNeeded();
                mPtrId1 = INVALID_POINTER_ID;
                mPtrId2 = INVALID_POINTER_ID;
                finishZoomSnapIfNeeded();
                mActive = false;
                break;
            default:
                break;
        }
        return true;
    }

    /** Apply a fixed rotation angle externally (e.g., from Settings -> Rotate). */
    void setRotationAngle(int angle) {
        cancelSnapAnimator();
        mRotationDeg = normalizeFixedAngle(angle);
        mLiveAngleOffset = 0f;
        mRotationUnlocked = false;
        if (mVideo == null) {
            ensureVideoBound();
        }
        if (mVideo != null) {
            mTransformOwned = mScale > 1f || mRotationDeg != 0;
            apply();
        }
    }

    /** Re-apply transforms if layout dimensions changed (e.g. switching portrait/landscape fullscreen). */
    void onLayoutChanged() {
        if (mVideo != null && (mScale > 1f || mRotationDeg != 0 || mLiveAngleOffset != 0f)) {
            apply();
        }
    }

    /** Reset zoom (and optionally rotation) back to the unzoomed, unrotated picture. */
    void reset(boolean showLabel) {
        reset(showLabel, true);
    }

    void reset(boolean showLabel, boolean resetRotation) {
        cancelSnapAnimator();
        cancelPan();
        mActive = false;
        mPtrId1 = INVALID_POINTER_ID;
        mPtrId2 = INVALID_POINTER_ID;
        mRotationUnlocked = false;
        mLiveAngleOffset = 0f;
        mScale = 1f;
        mTx = 0f;
        mTy = 0f;
        if (resetRotation) {
            mRotationDeg = 0;
        }
        mTransformOwned = mRotationDeg != 0;

        View video = mVideo;
        if (video != null) {
            if (resetRotation) {
                video.setScaleX(mFlipped ? -1f : 1f);
                video.setScaleY(1f);
                video.setTranslationX(0f);
                video.setTranslationY(0f);
                video.setRotation(0f);
                resetPivot(video);
            } else {
                apply();
            }
        }
        mHandler.removeCallbacks(mHideLabel);
        if (showLabel) {
            showLabel(mLabel.getContext().getString(R.string.mobile_zoom_fit));
        } else {
            mLabel.setVisibility(View.GONE);
        }
    }

    private boolean ensureVideoBound() {
        View video = mTarget.video();
        if (video == null || video.getWidth() == 0) {
            return false;
        }
        if (video != mVideo) {
            detachLayoutListeners();
            mVideo = video;
            mScale = 1f;
            mTx = 0f;
            mTy = 0f;
            mLiveAngleOffset = 0f;
            mFlipped = video.getScaleX() < 0;
            mTransformOwned = mRotationDeg != 0;
            attachLayoutListeners(video);
        } else if (!mTransformOwned) {
            mFlipped = video.getScaleX() < 0;
        }
        video.setPivotX(video.getWidth() / 2f);
        video.setPivotY(video.getHeight() / 2f);
        return true;
    }

    private void attachLayoutListeners(View video) {
        video.addOnLayoutChangeListener(mLayoutListener);
        View parent = video.getParent() instanceof View ? (View) video.getParent() : null;
        if (parent != null) {
            mObservedParent = parent;
            parent.addOnLayoutChangeListener(mLayoutListener);
        }
    }

    private void detachLayoutListeners() {
        if (mVideo != null) {
            mVideo.removeOnLayoutChangeListener(mLayoutListener);
        }
        if (mObservedParent != null) {
            mObservedParent.removeOnLayoutChangeListener(mLayoutListener);
            mObservedParent = null;
        }
    }

    private void beginRotationPointers(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return;
        }
        mPtrId1 = event.getPointerId(0);
        mPtrId2 = event.getPointerId(1);
        mPrevAngleDeg = pointerAngleDeg(event, 0, 1);
        mAccumAngleDeg = 0f;
        mLiveAngleOffset = 0f;
        mRotationUnlocked = false;
    }

    private void updateRotationPointers(MotionEvent event) {
        if (mPtrId1 == INVALID_POINTER_ID || mPtrId2 == INVALID_POINTER_ID) {
            return;
        }
        int idx1 = event.findPointerIndex(mPtrId1);
        int idx2 = event.findPointerIndex(mPtrId2);
        if (idx1 < 0 || idx2 < 0) {
            return;
        }
        float cur = pointerAngleDeg(event, idx1, idx2);
        float delta = normalizeAngleDelta(cur - mPrevAngleDeg);
        mPrevAngleDeg = cur;
        mAccumAngleDeg += delta;

        if (!mRotationUnlocked && Math.abs(mAccumAngleDeg) >= ROTATION_UNLOCK_DEG) {
            mRotationUnlocked = true;
            cancelSnapAnimator();
        }
        if (mRotationUnlocked) {
            mLiveAngleOffset = mAccumAngleDeg;
            mTransformOwned = true;
            apply();
            showStatusLabel();
        }
    }

    private void commitRotationIfNeeded() {
        if (!mRotationUnlocked) {
            mLiveAngleOffset = 0f;
            return;
        }
        mRotationUnlocked = false;
        int delta90 = snapDeltaTo90(mAccumAngleDeg);
        int targetFixed = normalizeFixedAngle(mRotationDeg + delta90);
        float startOffset = mLiveAngleOffset;
        float targetOffset = delta90;

        mRotationDeg = targetFixed;
        mTransformOwned = mScale > 1f || mRotationDeg != 0;
        showStatusLabel();

        if (Math.abs(targetOffset - startOffset) < 0.5f || mVideo == null) {
            mLiveAngleOffset = 0f;
            apply();
            return;
        }

        // Animate smoothly from current live angle to the snapped 90-degree step.
        float startAbsAngle = (mRotationDeg - delta90) + startOffset;
        float endAbsAngle = (mRotationDeg - delta90) + targetOffset;
        cancelSnapAnimator();
        mSnapAnimator = ValueAnimator.ofFloat(startAbsAngle, endAbsAngle);
        mSnapAnimator.setDuration(SNAP_ANIM_MS);
        mSnapAnimator.setInterpolator(new DecelerateInterpolator());
        mSnapAnimator.addUpdateListener(anim -> {
            float absAngle = (float) anim.getAnimatedValue();
            mLiveAngleOffset = absAngle - mRotationDeg;
            apply();
        });
        mSnapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mLiveAngleOffset = 0f;
                mSnapAnimator = null;
                apply();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mLiveAngleOffset = 0f;
                mSnapAnimator = null;
            }
        });
        mSnapAnimator.start();
    }

    private void finishZoomSnapIfNeeded() {
        if (mScale > 1f && mScale < SNAP_TO_FIT) {
            mScale = 1f;
            mTx = 0f;
            mTy = 0f;
            mTransformOwned = mRotationDeg != 0 || mLiveAngleOffset != 0f;
            apply();
            if (mRotationDeg == 0 && !mRotationUnlocked) {
                showLabel(mLabel.getContext().getString(R.string.mobile_zoom_fit));
            } else {
                showStatusLabel();
            }
        }
    }

    private void cancelSnapAnimator() {
        if (mSnapAnimator != null) {
            mSnapAnimator.cancel();
            mSnapAnimator = null;
        }
    }

    private float currentDisplayAngle() {
        return mRotationDeg + mLiveAngleOffset;
    }

    private void apply() {
        View video = mVideo;
        View parent = video != null ? (View) video.getParent() : null;
        if (video == null || parent == null) {
            return;
        }
        int vw = video.getWidth();
        int vh = video.getHeight();
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (vw == 0 || vh == 0 || pw == 0 || ph == 0) {
            return;
        }

        video.setPivotX(vw / 2f);
        video.setPivotY(vh / 2f);

        float angle = currentDisplayAngle();
        float totalScale = mScale * baseFitScale(angle, vw, vh, pw, ph);

        float[] clamped = clampTranslation(mTx, mTy, angle, totalScale, vw, vh,
                video.getLeft(), video.getTop(), pw, ph);
        mTx = clamped[0];
        mTy = clamped[1];

        video.setRotation(angle);
        video.setScaleX((mFlipped ? -1f : 1f) * totalScale);
        video.setScaleY(totalScale);
        video.setTranslationX(mTx);
        video.setTranslationY(mTy);
    }

    private void showStatusLabel() {
        int pct = Math.round(mScale * 100);
        int targetAngle = mRotationUnlocked
                ? normalizeFixedAngle(mRotationDeg + snapDeltaTo90(mAccumAngleDeg))
                : mRotationDeg;
        if (mRotationUnlocked || targetAngle != 0) {
            if (mScale > 1.02f) {
                showLabel(String.format(Locale.US, "%d%% • %d°", pct, targetAngle));
            } else {
                showLabel(String.format(Locale.US, "%d°", targetAngle));
            }
        } else {
            showLabel(String.format(Locale.US, "%d%%", pct));
        }
    }

    private void showLabel(String text) {
        mLabel.setText(text);
        mLabel.setVisibility(View.VISIBLE);
        mLabel.bringToFront(); // above the Leanback overlay, like the swipe indicator
        mHandler.removeCallbacks(mHideLabel);
        mHandler.postDelayed(mHideLabel, LABEL_HIDE_MS);
    }

    private static void resetPivot(View video) {
        if (Build.VERSION.SDK_INT >= 28) {
            video.resetPivot();
        } else {
            video.setPivotX(video.getWidth() / 2f);
            video.setPivotY(video.getHeight() / 2f);
        }
    }

    // ---- Pure geometry & angle helpers (package-private for unit tests) -----------------------

    static float pointerAngleDeg(MotionEvent event, int idx1, int idx2) {
        float dx = event.getX(idx2) - event.getX(idx1);
        float dy = event.getY(idx2) - event.getY(idx1);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    static float normalizeAngleDelta(float deltaDeg) {
        float d = deltaDeg % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    static int normalizeFixedAngle(int angleDeg) {
        int a = ((angleDeg % 360) + 360) % 360;
        return (Math.round(a / 90f) * 90) % 360;
    }

    static int snapDeltaTo90(float accumDeltaDeg) {
        float abs = Math.abs(accumDeltaDeg);
        if (abs < ROTATION_STEP_THRESHOLD_DEG) {
            return 0;
        }
        if (abs >= ROTATION_180_THRESHOLD_DEG) {
            return 180;
        }
        return accumDeltaDeg > 0 ? 90 : -90;
    }

    /**
     * Fit scale applied on top of user zoom so a video rotated 90° or 270° fits inside the parent
     * bounds at 100% zoom, smoothly interpolated for intermediate live twist angles.
     */
    static float baseFitScale(float angleDeg, int vw, int vh, int pw, int ph) {
        if (vw <= 0 || vh <= 0 || pw <= 0 || ph <= 0) {
            return 1f;
        }
        float s90 = Math.min((float) pw / vh, (float) ph / vw);
        double sin = Math.sin(Math.toRadians(angleDeg));
        float sin2 = (float) (sin * sin);
        return 1f + (s90 - 1f) * sin2;
    }

    /**
     * Clamp translation so a zoomed picture never pulls away from a parent edge it covers, and a
     * picture narrower/shorter than the parent stays centred.
     */
    static float[] clampTranslation(float tx, float ty, float angleDeg, float totalScale,
                                    int vw, int vh, int vLeft, int vTop, int pw, int ph) {
        double rad = Math.toRadians(angleDeg);
        float cos = (float) Math.abs(Math.cos(rad));
        float sin = (float) Math.abs(Math.sin(rad));
        float visW = (vw * cos + vh * sin) * totalScale;
        float visH = (vw * sin + vh * cos) * totalScale;

        float centerTx = pw / 2f - (vLeft + vw / 2f);
        float centerTy = ph / 2f - (vTop + vh / 2f);

        float clampedTx;
        if (visW <= pw) {
            clampedTx = centerTx;
        } else {
            float maxOffX = (visW - pw) / 2f;
            clampedTx = Math.max(centerTx - maxOffX, Math.min(centerTx + maxOffX, tx));
        }

        float clampedTy;
        if (visH <= ph) {
            clampedTy = centerTy;
        } else {
            float maxOffY = (visH - ph) / 2f;
            clampedTy = Math.max(centerTy - maxOffY, Math.min(centerTy + maxOffY, ty));
        }

        return new float[]{clampedTx, clampedTy};
    }

    // Touch events arrive in window coordinates; the transform works in the video's parent.
    private float toParentX(float x) {
        return x - parentOrigin()[0];
    }

    private float toParentY(float y) {
        return y - parentOrigin()[1];
    }

    private final int[] mOrigin = new int[2];

    private int[] parentOrigin() {
        View parent = mVideo != null ? (View) mVideo.getParent() : null;
        if (parent != null) {
            parent.getLocationInWindow(mOrigin);
        } else {
            mOrigin[0] = mOrigin[1] = 0;
        }
        return mOrigin;
    }
}
