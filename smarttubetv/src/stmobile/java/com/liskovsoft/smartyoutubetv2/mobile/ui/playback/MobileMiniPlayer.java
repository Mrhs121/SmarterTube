package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.exoplayer2.ControlDispatcher;
import com.google.android.exoplayer2.DefaultControlDispatcher;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.mobile.notifications.MediaNotificationService;
import com.liskovsoft.smartyoutubetv2.mobile.ui.base.MobileActivity;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.BackboneQueueNavigator;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app bottom-right floating mini-player for the stmobile flavor.
 *
 * Keeps the live {@link SimpleExoPlayer} and {@link ExoPlayerController} playing without
 * rebuffering when the user swipes down on the non-fullscreen 16:9 strip, floats above phone
 * browsing screens, and either expands back into {@link MobilePlaybackActivity} on tap or stops
 * playback on a downward swipe (or close tap).
 */
public final class MobileMiniPlayer {
    public static final String EXTRA_EXPAND_MINI_PLAYER = "expand_mini_player";

    private static final long PROGRESS_POLL_MS = 500;
    // Safety fallback only: avoid flushing/re-seeking a live HW decoder during normal surface handoff.
    private static final long FRAME_STALL_TIMEOUT_MS = 2500;
    private static final long SHARED_TRANSITION_MS = 260;
    private static final float CARD_CORNER_RADIUS_DP = 12f;
    private static final float CARD_ELEVATION_DP = 10f;
    private static final float DISMISS_THRESHOLD_DP = 44f;
    private static final float DISMISS_VELOCITY_DP = 400f;

    @SuppressLint("StaticFieldLeak")
    private static final MobileMiniPlayer sInstance = new MobileMiniPlayer();

    static final class Session {
        final SimpleExoPlayer player;
        final ExoPlayerController controller;
        final ExoPlayerInitializer initializer;
        Video video;
        int videoWidth;
        int videoHeight;
        final List<VideoGroup> suggestionGroups;
        List<SeekBarSegment> seekBarSegments;
        @Nullable
        final RectF enterBounds;

        Session(
                SimpleExoPlayer player,
                ExoPlayerController controller,
                ExoPlayerInitializer initializer,
                Video video,
                int videoWidth,
                int videoHeight,
                List<VideoGroup> suggestionGroups,
                List<SeekBarSegment> seekBarSegments,
                @Nullable RectF enterBounds) {
            this.player = player;
            this.controller = controller;
            this.initializer = initializer;
            this.video = video;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.suggestionGroups = suggestionGroups != null ? new ArrayList<>(suggestionGroups) : new ArrayList<>();
            this.seekBarSegments = seekBarSegments != null ? new ArrayList<>(seekBarSegments) : null;
            this.enterBounds = enterBounds != null ? new RectF(enterBounds) : null;
        }
    }

    private Context mAppContext;
    @Nullable
    private Session mSession;
    private boolean mExpanding;
    private boolean mClosing;
    private boolean mAnimateNextAttach;
    @Nullable
    private RectF mPendingEnterBounds;
    @Nullable
    private RectF mPendingExpandBounds;
    private int mStartedActivities;

    @Nullable
    private Activity mAttachedActivity;
    @Nullable
    private FrameLayout mCard;
    @Nullable
    private AspectRatioFrameLayout mAspectFrame;
    @Nullable
    private TextureView mTextureView;
    @Nullable
    private Surface mActiveSurface;
    private boolean mAwaitingFirstFrame;
    private boolean mDidRecoverySeek;
    @Nullable
    private View mProgressFill;
    @Nullable
    private ImageButton mPlayPauseBtn;
    @Nullable
    private ImageButton mCloseBtn;

    @Nullable
    private MediaSessionCompat mMediaSession;
    @Nullable
    private MediaSessionConnector mMediaSessionConnector;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mProgressPoller = new Runnable() {
        @Override
        public void run() {
            updateUiState();
            if (mSession != null && mAttachedActivity != null) {
                mHandler.postDelayed(this, PROGRESS_POLL_MS);
            }
        }
    };
    private final Runnable mFrameStallWatchdog = () -> {
        if (!mAwaitingFirstFrame || mDidRecoverySeek || mSession == null) {
            return;
        }
        if (mSession.player.getPlaybackState() == Player.STATE_READY && mSession.player.getPlayWhenReady()) {
            mDidRecoverySeek = true;
            // Flush a potentially wedged HW decoder after surface transition (e.g., DummySurface).
            mSession.player.seekTo(mSession.player.getCurrentPosition());
        }
    };

    private final Player.EventListener mPlayerListener = new Player.EventListener() {
        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            updateUiState();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateUiState();
        }
    };

    private final VideoListener mVideoListener = new VideoListener() {
        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            if (mSession != null && width > 0 && height > 0) {
                mSession.videoWidth = width;
                mSession.videoHeight = height;
                if (mAspectFrame != null) {
                    mAspectFrame.setAspectRatio((float) width / height);
                }
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            mAwaitingFirstFrame = false;
            mHandler.removeCallbacks(mFrameStallWatchdog);
        }
    };

    private final PlaybackView mMiniPlaybackView = new MiniPlaybackView();

    private MobileMiniPlayer() {
    }

    public static MobileMiniPlayer getInstance() {
        return sInstance;
    }

    public boolean hasSession() {
        return mSession != null;
    }

    /**
     * Hands a live player session over to the floating bottom-right mini-player.
     * Caller must have detached its fragment/activity UI glue without releasing the engine.
     */
    void start(@NonNull Context context, @NonNull Session session) {
        mAppContext = context.getApplicationContext();
        releaseInternal(false);

        mClosing = false;
        mExpanding = false;
        mDidRecoverySeek = false;
        mAwaitingFirstFrame = false;
        mAnimateNextAttach = true;
        mPendingEnterBounds = session.enterBounds != null ? new RectF(session.enterBounds) : null;
        mPendingExpandBounds = null;
        mSession = session;
        session.controller.setPlayerView(null);
        session.player.addListener(mPlayerListener);
        session.player.addVideoListener(mVideoListener);

        PlaybackPresenter.instance(mAppContext).setView(mMiniPlaybackView);
        startMediaSession(session);
    }

    /** Marks an intent as expanding the active mini-player back into {@link MobilePlaybackActivity}. */
    public static boolean isExpandIntent(@Nullable Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_EXPAND_MINI_PLAYER, false);
    }

    /**
     * Called by {@link MobilePlaybackActivity} before player initialization when another video
     * starts instead of expanding the mini-player.
     */
    public void onPlaybackActivityLaunching(@Nullable Intent intent) {
        if (mSession == null) {
            return;
        }
        if (isExpandIntent(intent) || mExpanding) {
            mExpanding = true;
            return;
        }
        // A different video (or shortcut) is opening: release the minimized engine silently without
        // firing PlaybackPresenter.onFinish() on the newly opened video.
        releaseInternal(false);
    }

    @Nullable
    Session consumeRestoreSession() {
        Session session = mSession;
        if (session == null || !mExpanding) {
            mExpanding = false;
            mPendingExpandBounds = null;
            return null;
        }
        mExpanding = false;
        mSession = null;
        mHandler.removeCallbacks(mProgressPoller);
        mHandler.removeCallbacks(mFrameStallWatchdog);
        session.player.removeListener(mPlayerListener);
        session.player.removeVideoListener(mVideoListener);
        releaseMediaSession();
        // Keep the mini TextureView surface intact until MobilePlaybackFragment binds its own Surface
        // (completeExpandCleanup / onActivityStopped), avoiding a DummySurface detours that can stall c2.mtk.
        return session;
    }

    /** Returns and clears the screen bounds from which {@link MobilePlaybackActivity} should expand. */
    @Nullable
    RectF consumePendingExpandBounds() {
        RectF bounds = mPendingExpandBounds;
        mPendingExpandBounds = null;
        return bounds;
    }

    /** Detaches the old mini-player overlay after the expanded player has bound its own Surface. */
    void completeExpandCleanup() {
        if (mAttachedActivity != null && mCard != null) {
            cleanupMiniTextureView(null);
            removeOverlayFromCurrentParent();
            mAttachedActivity = null;
        }
    }

    /** Activity lifecycle hooks registered once in {@link MobileApplication}. */
    public void onActivityStarted(@NonNull Activity activity) {
        if (activity instanceof MobileActivity) {
            mStartedActivities++;
        }
    }

    public void onActivityResumed(@NonNull Activity activity) {
        if (mSession == null || mClosing || mExpanding) {
            return;
        }
        if (!(activity instanceof MobileActivity) || !((MobileActivity) activity).supportsMiniPlayerOverlay()) {
            return;
        }
        attachToActivity(activity);
    }

    public void onActivityPaused(@NonNull Activity activity) {
        if (mAttachedActivity == activity && !mExpanding) {
            detachFromActivity();
        }
    }

    public void onActivityStopped(@NonNull Activity activity) {
        if (mAttachedActivity == activity) {
            detachFromActivity();
        }
        if (activity instanceof MobileActivity) {
            mStartedActivities = Math.max(0, mStartedActivities - 1);
            if (mStartedActivities == 0 && mSession != null && !mExpanding) {
                int bgMode = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData
                        .instance(mAppContext).getBackgroundMode();
                if (bgMode == com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.BACKGROUND_MODE_DEFAULT) {
                    mSession.controller.setPlayWhenReady(false);
                }
            }
        }
    }

    public void onActivityDestroyed(@NonNull Activity activity) {
        if (mAttachedActivity == activity) {
            detachFromActivity();
        }
    }

    /** Stops playback, saves watch state cleanly, and dismisses the mini-player. */
    public void dismiss() {
        releaseInternal(true);
    }

    private void releaseInternal(boolean notifyPresenter) {
        if (mClosing) {
            return;
        }
        mClosing = true;
        mExpanding = false;
        mAwaitingFirstFrame = false;
        mHandler.removeCallbacks(mProgressPoller);
        mHandler.removeCallbacks(mFrameStallWatchdog);
        mPendingEnterBounds = null;
        mPendingExpandBounds = null;

        Session session = mSession;
        mSession = null;

        detachFromActivity();
        releaseMediaSession();

        if (session != null) {
            session.player.removeListener(mPlayerListener);
            session.player.removeVideoListener(mVideoListener);
            PlaybackPresenter presenter = PlaybackPresenter.instance(mAppContext);
            if (notifyPresenter && presenter != null) {
                presenter.onEngineReleased();
            }
            session.player.clearVideoSurface();
            session.initializer.release();
            session.controller.release();
            if (notifyPresenter && presenter != null) {
                presenter.onFinish();
                if (presenter.getView() == mMiniPlaybackView) {
                    presenter.onViewDestroyed();
                }
            }
        }
        mClosing = false;
    }

    private void attachToActivity(@NonNull Activity activity) {
        if (mSession == null) {
            return;
        }
        if (mAttachedActivity == activity && mCard != null && mCard.getParent() != null) {
            updateLayoutMargins(activity);
            updateUiState();
            return;
        }

        detachFromActivity();

        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        ensureCardBuilt(activity);
        updateLayoutMargins(activity);
        mountFreshTextureView(activity);

        mAttachedActivity = activity;
        content.addView(mCard);

        Session session = mSession;
        if (session != null && session.videoWidth > 0 && session.videoHeight > 0 && mAspectFrame != null) {
            mAspectFrame.setAspectRatio((float) session.videoWidth / session.videoHeight);
        }

        mCard.animate().setListener(null).cancel();
        mCard.setPivotX(0f);
        mCard.setPivotY(0f);
        mCard.setTranslationX(0f);
        mCard.setTranslationY(0f);
        mCard.setAlpha(1f);
        mCard.setScaleX(1f);
        mCard.setScaleY(1f);

        RectF enterBounds = mPendingEnterBounds;
        mPendingEnterBounds = null;
        if (enterBounds != null) {
            mAnimateNextAttach = false;
            runSharedEnterAnimation(enterBounds);
        } else if (mAnimateNextAttach) {
            mAnimateNextAttach = false;
            mCard.setAlpha(0f);
            mCard.setScaleX(0.92f);
            mCard.setScaleY(0.92f);
            mCard.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        updateUiState();
        mHandler.removeCallbacks(mProgressPoller);
        mHandler.postDelayed(mProgressPoller, PROGRESS_POLL_MS);
    }

    private void runSharedEnterAnimation(@NonNull RectF enterBounds) {
        if (mCard == null) {
            return;
        }
        final FrameLayout card = mCard;
        card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver vto = card.getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.removeOnPreDrawListener(this);
                }
                int destW = card.getWidth();
                int destH = card.getHeight();
                if (destW <= 0 || destH <= 0 || enterBounds.width() <= 0f || enterBounds.height() <= 0f) {
                    return true;
                }
                int[] loc = new int[2];
                card.getLocationOnScreen(loc);
                float startScaleX = enterBounds.width() / destW;
                float startScaleY = enterBounds.height() / destH;
                float startTx = enterBounds.left - loc[0];
                float startTy = enterBounds.top - loc[1];

                card.setPivotX(0f);
                card.setPivotY(0f);
                card.setScaleX(startScaleX);
                card.setScaleY(startScaleY);
                card.setTranslationX(startTx);
                card.setTranslationY(startTy);
                card.setAlpha(1f);

                long distPx = Math.round(Math.max(Math.abs(startTx), Math.abs(startTy)));
                long duration = Math.max(45L, Math.min(SHARED_TRANSITION_MS, Math.round(distPx * 0.45f)));
                card.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(duration)
                        .setInterpolator(new FastOutSlowInInterpolator())
                        .start();
                return true;
            }
        });
    }

    private void detachFromActivity() {
        mHandler.removeCallbacks(mProgressPoller);
        mHandler.removeCallbacks(mFrameStallWatchdog);
        mAwaitingFirstFrame = false;
        cleanupMiniTextureView(mSession);
        removeOverlayFromCurrentParent();
        mAttachedActivity = null;
    }

    private void mountFreshTextureView(@NonNull Activity activity) {
        if (mAspectFrame == null) {
            return;
        }
        cleanupMiniTextureView(null);
        Context ctx = mAppContext != null ? mAppContext : activity.getApplicationContext();
        TextureView textureView = new TextureView(ctx);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            private Surface mOwnedSurface;

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                mOwnedSurface = new Surface(surfaceTexture);
                mActiveSurface = mOwnedSurface;
                Session session = mSession;
                if (session != null && mTextureView == textureView) {
                    armStallWatchdog(session);
                    session.player.setVideoSurface(mOwnedSurface);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (mOwnedSurface != null) {
                    Session session = mSession;
                    if (session != null) {
                        session.player.clearVideoSurface(mOwnedSurface);
                    }
                    if (mActiveSurface == mOwnedSurface) {
                        mActiveSurface = null;
                    }
                    mOwnedSurface.release();
                    mOwnedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                if (mAwaitingFirstFrame) {
                    mAwaitingFirstFrame = false;
                    mHandler.removeCallbacks(mFrameStallWatchdog);
                }
            }
        });
        mAspectFrame.removeAllViews();
        mAspectFrame.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        mTextureView = textureView;
    }

    private void cleanupMiniTextureView(@Nullable Session session) {
        if (mActiveSurface != null) {
            if (session != null) {
                session.player.clearVideoSurface(mActiveSurface);
            }
            mActiveSurface.release();
            mActiveSurface = null;
        }
        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(null);
            if (mTextureView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mTextureView.getParent()).removeView(mTextureView);
            }
            mTextureView = null;
        }
    }

    private void armStallWatchdog(@NonNull Session session) {
        mDidRecoverySeek = false;
        mHandler.removeCallbacks(mFrameStallWatchdog);
        if (session.player.getPlaybackState() == Player.STATE_READY && session.player.getPlayWhenReady()) {
            mAwaitingFirstFrame = true;
            mHandler.postDelayed(mFrameStallWatchdog, FRAME_STALL_TIMEOUT_MS);
        } else {
            mAwaitingFirstFrame = false;
        }
    }

    private void removeOverlayFromCurrentParent() {
        if (mCard != null) {
            mCard.animate().setListener(null).cancel();
            if (mCard.getParent() instanceof ViewGroup) {
                ((ViewGroup) mCard.getParent()).removeView(mCard);
            }
        }
    }

    private void ensureCardBuilt(@NonNull Activity activity) {
        if (mCard != null) {
            return;
        }
        Context ctx = mAppContext != null ? mAppContext : activity.getApplicationContext();
        float density = activity.getResources().getDisplayMetrics().density;

        MiniCardView card = new MiniCardView(ctx, density);
        card.setBackgroundColor(0xFF101010);
        card.setElevation(CARD_ELEVATION_DP * density);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), CARD_CORNER_RADIUS_DP * density);
            }
        });

        AspectRatioFrameLayout aspectFrame = new AspectRatioFrameLayout(ctx);
        aspectFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        card.addView(aspectFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int btnSize = Math.round(32 * density);
        int btnMargin = Math.round(6 * density);

        ImageButton closeBtn = new ImageButton(ctx);
        closeBtn.setImageResource(R.drawable.ic_mini_close);
        closeBtn.setBackgroundResource(R.drawable.bg_player_centre_button);
        closeBtn.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int closePad = Math.round(6 * density);
        closeBtn.setPadding(closePad, closePad, closePad, closePad);
        closeBtn.setContentDescription(ctx.getString(android.R.string.cancel));
        closeBtn.setOnClickListener(v -> animateDismiss());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP | Gravity.END);
        closeLp.topMargin = btnMargin;
        closeLp.rightMargin = btnMargin;
        card.addView(closeBtn, closeLp);

        ImageButton playPauseBtn = new ImageButton(ctx);
        playPauseBtn.setImageResource(R.drawable.ic_shorts_pause);
        playPauseBtn.setBackgroundResource(R.drawable.bg_player_centre_button);
        playPauseBtn.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int playPad = Math.round(6 * density);
        playPauseBtn.setPadding(playPad, playPad, playPad, playPad);
        playPauseBtn.setOnClickListener(v -> togglePlayPause());
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(btnSize, btnSize, Gravity.BOTTOM | Gravity.START);
        playLp.bottomMargin = btnMargin + Math.round(2 * density);
        playLp.leftMargin = btnMargin;
        card.addView(playPauseBtn, playLp);

        View progressBg = new View(ctx);
        progressBg.setBackgroundColor(0x55FFFFFF);
        int barHeight = Math.max(2, Math.round(2.5f * density));
        card.addView(progressBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM));

        View progressFill = new View(ctx);
        progressFill.setBackgroundColor(0xFFFF0000);
        card.addView(progressFill, new FrameLayout.LayoutParams(0, barHeight, Gravity.BOTTOM | Gravity.START));

        mCard = card;
        mAspectFrame = aspectFrame;
        mCloseBtn = closeBtn;
        mPlayPauseBtn = playPauseBtn;
        mProgressFill = progressFill;
    }

    /**
     * Container that lets taps reach child ImageButtons cleanly while intercepting vertical drags
     * anywhere on the card (and treating non-button taps as "expand back to player").
     */
    private final class MiniCardView extends FrameLayout {
        private final int mTouchSlop;
        private final float mDismissDistancePx;
        private final float mDismissVelocityPx;
        private float mDownRawX;
        private float mDownRawY;
        private boolean mDragging;
        private VelocityTracker mVelocityTracker;

        MiniCardView(@NonNull Context context, float density) {
            super(context);
            mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            mDismissDistancePx = DISMISS_THRESHOLD_DP * density;
            mDismissVelocityPx = DISMISS_VELOCITY_DP * density;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownRawX = ev.getRawX();
                    mDownRawY = ev.getRawY();
                    mDragging = false;
                    resetTracker(ev);
                    return false;

                case MotionEvent.ACTION_MOVE: {
                    addTracker(ev);
                    float dx = ev.getRawX() - mDownRawX;
                    float dy = ev.getRawY() - mDownRawY;
                    if (!mDragging && Math.abs(dy) > mTouchSlop && Math.abs(dy) >= Math.abs(dx)) {
                        mDragging = true;
                        return true;
                    }
                    return false;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    recycleTracker();
                    return mDragging;

                default:
                    return false;
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownRawX = event.getRawX();
                    mDownRawY = event.getRawY();
                    mDragging = false;
                    resetTracker(event);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    addTracker(event);
                    float dx = event.getRawX() - mDownRawX;
                    float dy = event.getRawY() - mDownRawY;
                    if (!mDragging && Math.abs(dy) > mTouchSlop && Math.abs(dy) >= Math.abs(dx)) {
                        mDragging = true;
                    }
                    if (mDragging) {
                        float clampedY = Math.max(0f, dy);
                        setTranslationY(clampedY);
                        float alpha = Math.max(0.25f, 1f - clampedY / (mDismissDistancePx * 2.4f));
                        setAlpha(alpha);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    float dy = event.getRawY() - mDownRawY;
                    float vy = computeYVelocity(event);
                    if (mDragging) {
                        mDragging = false;
                        if (PlaybackNavigationRules.shouldDismissMiniPlayer(
                                dy, vy, mDismissDistancePx, mDismissVelocityPx)) {
                            animateDismiss();
                        } else {
                            springBack();
                        }
                        return true;
                    }
                    // No child ImageButton consumed the tap -> expand back into MobilePlaybackActivity.
                    if (Math.hypot(event.getRawX() - mDownRawX, dy) <= mTouchSlop * 1.5f) {
                        expandFromMiniPlayer();
                    }
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    recycleTracker();
                    mDragging = false;
                    springBack();
                    return true;

                default:
                    return false;
            }
        }

        private void resetTracker(MotionEvent ev) {
            recycleTracker();
            mVelocityTracker = VelocityTracker.obtain();
            mVelocityTracker.addMovement(ev);
        }

        private void addTracker(MotionEvent ev) {
            if (mVelocityTracker != null) {
                mVelocityTracker.addMovement(ev);
            }
        }

        private float computeYVelocity(MotionEvent ev) {
            float vy = 0f;
            if (mVelocityTracker != null) {
                mVelocityTracker.addMovement(ev);
                mVelocityTracker.computeCurrentVelocity(1000);
                vy = mVelocityTracker.getYVelocity();
                recycleTracker();
            }
            return vy;
        }

        private void recycleTracker() {
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
        }
    }

    private void springBack() {
        if (mCard == null) {
            return;
        }
        mCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateDismiss() {
        if (mCard == null) {
            dismiss();
            return;
        }
        float travel = mCard.getHeight() + 48f * mCard.getResources().getDisplayMetrics().density;
        mCard.animate()
                .translationY(travel)
                .alpha(0f)
                .setDuration(150)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mCard != null) {
                            mCard.animate().setListener(null);
                        }
                        dismiss();
                    }
                })
                .start();
    }

    private void togglePlayPause() {
        if (mSession == null) {
            return;
        }
        boolean next = !mSession.controller.getPlayWhenReady();
        mSession.controller.setPlayWhenReady(next);
        updateUiState();
    }

    private void expandFromMiniPlayer() {
        if (mSession == null || mExpanding) {
            return;
        }
        Activity host = mAttachedActivity;
        Context ctx = host != null ? host : mAppContext;
        if (ctx == null) {
            return;
        }
        if (mCard != null && mCard.getWidth() > 0 && mCard.getHeight() > 0) {
            int[] loc = new int[2];
            mCard.getLocationOnScreen(loc);
            mPendingExpandBounds = new RectF(
                    loc[0],
                    loc[1],
                    loc[0] + mCard.getWidth() * mCard.getScaleX(),
                    loc[1] + mCard.getHeight() * mCard.getScaleY());
        } else {
            mPendingExpandBounds = null;
        }
        mExpanding = true;
        Intent intent = new Intent(ctx, MobilePlaybackActivity.class);
        intent.putExtra(EXTRA_EXPAND_MINI_PLAYER, true);
        if (host == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(intent);
        if (host != null) {
            host.overridePendingTransition(0, 0);
        }
    }

    private void updateLayoutMargins(@NonNull Activity activity) {
        if (mCard == null) {
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int width = PlaybackNavigationRules.computeMiniCardWidthPx(screenWidth, density);
        int height = Math.round(width * 9f / 16f);

        View bottomNav = activity.findViewById(R.id.mobile_bottom_nav);
        boolean hasBottomNav = bottomNav != null && bottomNav.getVisibility() == View.VISIBLE;
        int bottomDp = hasBottomNav ? 68 : 16;

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = Math.round(12 * density);
        lp.bottomMargin = Math.round(bottomDp * density);
        mCard.setLayoutParams(lp);
    }

    private void updateUiState() {
        if (mSession == null || mCard == null) {
            return;
        }
        if (mPlayPauseBtn != null) {
            boolean playing = mSession.controller.getPlayWhenReady();
            mPlayPauseBtn.setImageResource(playing ? R.drawable.ic_shorts_pause : R.drawable.ic_shorts_play);
        }
        if (mProgressFill != null) {
            long dur = mSession.controller.getDurationMs();
            long pos = mSession.controller.getPositionMs();
            int cardWidth = mCard.getWidth();
            if (dur > 0 && cardWidth > 0) {
                float frac = Math.max(0f, Math.min(1f, (float) pos / dur));
                ViewGroup.LayoutParams lp = mProgressFill.getLayoutParams();
                int targetWidth = Math.round(cardWidth * frac);
                if (lp.width != targetWidth) {
                    lp.width = targetWidth;
                    mProgressFill.setLayoutParams(lp);
                }
            }
        }
    }

    private void startMediaSession(@NonNull Session session) {
        if (Build.VERSION.SDK_INT <= 19 || mAppContext == null) {
            return;
        }
        boolean disableNotifications = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
                .instance(mAppContext).isPlaybackNotificationsDisabled();
        MediaSessionCompat mediaSession = new MediaSessionCompat(mAppContext, mAppContext.getPackageName());
        mediaSession.setActive(!disableNotifications);

        Intent expandIntent = new Intent(mAppContext, MobilePlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_EXPAND_MINI_PLAYER, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        mediaSession.setSessionActivity(PendingIntent.getActivity(mAppContext, 0, expandIntent, flags));

        MediaSessionConnector connector = new MediaSessionConnector(mediaSession);
        try {
            connector.setPlayer(session.player);
        } catch (NoSuchMethodError e) {
            mediaSession.release();
            return;
        }
        connector.setMediaMetadataProvider(player -> {
            Video v = mSession != null ? mSession.video : null;
            if (v == null) {
                return null;
            }
            MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
            b.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, v.videoId);
            b.putString(MediaMetadataCompat.METADATA_KEY_TITLE, v.getTitleFull());
            b.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, v.getTitleFull());
            b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, v.getAuthor());
            b.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, Helpers.toString(v.getSecondTitleFull()));
            b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, v.getCardImageUrl());
            b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, session.controller.getDurationMs());
            return b.build();
        });
        connector.setQueueNavigator(new BackboneQueueNavigator() {
            @Override
            public long getSupportedQueueNavigatorActions(Player player) {
                return PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
            }

            @Override
            public void onSkipToPrevious(Player player, ControlDispatcher controlDispatcher) {
                PlaybackPresenter.instance(mAppContext).onPreviousClicked();
            }

            @Override
            public void onSkipToNext(Player player, ControlDispatcher controlDispatcher) {
                PlaybackPresenter.instance(mAppContext).onNextClicked();
            }
        });
        connector.setControlDispatcher(new DefaultControlDispatcher());

        mMediaSession = mediaSession;
        mMediaSessionConnector = connector;
        MediaNotificationService.start(mAppContext, mediaSession.getSessionToken());
    }

    private void releaseMediaSession() {
        if (mMediaSessionConnector != null) {
            mMediaSessionConnector.setPlayer(null);
            mMediaSessionConnector.setControlDispatcher(null);
            mMediaSessionConnector.setMediaMetadataProvider(null);
            mMediaSessionConnector.setQueueNavigator(null);
            mMediaSessionConnector = null;
        }
        if (mMediaSession != null) {
            if (mAppContext != null) {
                MediaNotificationService.stop(mAppContext);
            }
            mMediaSession.setActive(false);
            mMediaSession.release();
            mMediaSession = null;
        }
    }

    private final class MiniPlaybackView implements PlaybackView {
        @Override
        public void setVideo(Video item) {
            if (mSession == null) {
                return;
            }
            mSession.video = item;
            mSession.controller.setVideo(item);
            if (mMediaSession != null && mAppContext != null && item != null) {
                MediaNotificationService.start(mAppContext, mMediaSession.getSessionToken());
            }
        }

        @Override
        public Video getVideo() {
            return mSession != null ? mSession.controller.getVideo() : null;
        }

        @Override
        public void finish() {
            dismiss();
        }

        @Override
        public void finishReally() {
            dismiss();
        }

        @Override
        public void showBackground(String url) {
        }

        @Override
        public void showBackgroundColor(int colorResId) {
        }

        @Override
        public void resetPlayerState() {
            if (mSession != null) {
                mSession.controller.resetPlayerState();
                mSession.seekBarSegments = null;
            }
        }

        @Override
        public boolean isEmbed() {
            return false;
        }

        @Override
        public void updateSuggestions(VideoGroup group) {
            if (mSession != null && group != null) {
                mSession.suggestionGroups.add(group);
            }
        }

        @Override
        public void removeSuggestions(VideoGroup group) {
            if (mSession != null && group != null) {
                mSession.suggestionGroups.remove(group);
            }
        }

        @Override
        public int getSuggestionsIndex(VideoGroup group) {
            return mSession != null && group != null ? mSession.suggestionGroups.indexOf(group) : -1;
        }

        @Override
        public VideoGroup getSuggestionsByIndex(int index) {
            if (mSession == null || index < 0 || index >= mSession.suggestionGroups.size()) {
                return null;
            }
            return mSession.suggestionGroups.get(index);
        }

        @Override
        public void focusSuggestedItem(int index) {
        }

        @Override
        public void focusSuggestedItem(Video video) {
        }

        @Override
        public void resetSuggestedPosition() {
        }

        @Override
        public boolean isSuggestionsEmpty() {
            return mSession == null || mSession.suggestionGroups.isEmpty();
        }

        @Override
        public void clearSuggestions() {
            if (mSession != null) {
                mSession.suggestionGroups.clear();
            }
        }

        @Override
        public void showOverlay(boolean show) {
        }

        @Override
        public boolean isOverlayShown() {
            return false;
        }

        @Override
        public void showSuggestions(boolean show) {
        }

        @Override
        public boolean isSuggestionsShown() {
            return false;
        }

        @Override
        public void showControls(boolean show) {
        }

        @Override
        public boolean isControlsShown() {
            return false;
        }

        @Override
        public int getButtonState(int buttonId) {
            return BUTTON_OFF;
        }

        @Override
        public void setButtonState(int buttonId, int buttonState) {
        }

        @Override
        public void setChannelIcon(String iconUrl) {
        }

        @Override
        public void setSeekPreviewTitle(String title) {
        }

        @Override
        public void setNextTitle(Video nextVideo) {
        }

        @Override
        public void showDebugInfo(boolean show) {
        }

        @Override
        public void showSubtitles(boolean show) {
        }

        @Override
        public void loadStoryboard() {
        }

        @Override
        public void setTitle(String title) {
        }

        @Override
        public void showProgressBar(boolean show) {
        }

        @Override
        public void setSeekBarSegments(List<SeekBarSegment> segments) {
            if (mSession != null) {
                mSession.seekBarSegments = segments != null ? new ArrayList<>(segments) : null;
            }
        }

        @Override
        public void updateEndingTime() {
        }

        @Override
        public void setChatReceiver(ChatReceiver chatReceiver) {
        }

        @Override
        public void openSabr(MediaItemFormatInfo formatInfo) {
            if (mSession != null) mSession.controller.openSabr(formatInfo);
        }

        @Override
        public void openDash(MediaItemFormatInfo formatInfo) {
            if (mSession != null) mSession.controller.openDash(formatInfo);
        }

        @Override
        public void openDash(InputStream dashManifest) {
            if (mSession != null) mSession.controller.openDash(dashManifest);
        }

        @Override
        public void openDashUrl(String dashManifestUrl) {
            if (mSession != null) mSession.controller.openDashUrl(dashManifestUrl);
        }

        @Override
        public void openHlsUrl(String hlsPlaylistUrl) {
            if (mSession != null) mSession.controller.openHlsUrl(hlsPlaylistUrl);
        }

        @Override
        public void openUrlList(List<String> urlList) {
            if (mSession != null) mSession.controller.openUrlList(urlList);
        }

        @Override
        public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
            if (mSession != null) mSession.controller.openMerged(formatInfo, hlsPlaylistUrl);
        }

        @Override
        public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
            if (mSession != null) mSession.controller.openMerged(dashManifest, hlsPlaylistUrl);
        }

        @Override
        public long getPositionMs() {
            return mSession != null ? mSession.controller.getPositionMs() : 0;
        }

        @Override
        public void setPositionMs(long positionMs) {
            if (mSession != null) mSession.controller.setPositionMs(positionMs);
        }

        @Override
        public long getDurationMs() {
            return mSession != null ? mSession.controller.getDurationMs() : 0;
        }

        @Override
        public void setPlayWhenReady(boolean play) {
            if (mSession != null) mSession.controller.setPlayWhenReady(play);
        }

        @Override
        public boolean getPlayWhenReady() {
            return mSession != null && mSession.controller.getPlayWhenReady();
        }

        @Override
        public boolean isPlaying() {
            return mSession != null && mSession.controller.isPlaying();
        }

        @Override
        public boolean isLoading() {
            return mSession != null && mSession.controller.isLoading();
        }

        @Override
        public List<FormatItem> getVideoFormats() {
            return mSession != null ? mSession.controller.getVideoFormats() : null;
        }

        @Override
        public List<FormatItem> getAudioFormats() {
            return mSession != null ? mSession.controller.getAudioFormats() : null;
        }

        @Override
        public List<FormatItem> getSubtitleFormats() {
            return mSession != null ? mSession.controller.getSubtitleFormats() : null;
        }

        @Override
        public void setFormat(FormatItem option) {
            if (mSession != null) mSession.controller.selectFormat(option);
        }

        @Override
        public FormatItem getVideoFormat() {
            return mSession != null ? mSession.controller.getVideoFormat() : null;
        }

        @Override
        public FormatItem getAudioFormat() {
            return mSession != null ? mSession.controller.getAudioFormat() : null;
        }

        @Override
        public FormatItem getSubtitleFormat() {
            return mSession != null ? mSession.controller.getSubtitleFormat() : null;
        }

        @Override
        public boolean isEngineInitialized() {
            return mSession != null;
        }

        @Override
        public void restartEngine() {
        }

        @Override
        public void reloadPlayback() {
        }

        @Override
        public void blockEngine(boolean block) {
        }

        @Override
        public boolean isEngineBlocked() {
            return false;
        }

        @Override
        public boolean isInPIPMode() {
            return false;
        }

        @Override
        public boolean containsMedia() {
            return mSession != null && mSession.controller.containsMedia();
        }

        @Override
        public void setSpeed(float speed) {
            if (mSession != null) mSession.controller.setSpeed(speed);
        }

        @Override
        public float getSpeed() {
            return mSession != null ? mSession.controller.getSpeed() : 1f;
        }

        @Override
        public void setPitch(float pitch) {
            if (mSession != null) mSession.controller.setPitch(pitch);
        }

        @Override
        public float getPitch() {
            return mSession != null ? mSession.controller.getPitch() : 1f;
        }

        @Override
        public void setVolume(float volume) {
            if (mSession != null) mSession.controller.setVolume(volume);
        }

        @Override
        public float getVolume() {
            return mSession != null ? mSession.controller.getVolume() : 1f;
        }

        @Override
        public void setResizeMode(int mode) {
        }

        @Override
        public int getResizeMode() {
            return 0;
        }

        @Override
        public void setZoomPercents(int percents) {
        }

        @Override
        public void setAspectRatio(float ratio) {
        }

        @Override
        public void setRotationAngle(int angle) {
        }

        @Override
        public void setVideoFlipEnabled(boolean enabled) {
        }

        @Override
        public void setVideoGravity(int gravity) {
        }
    }
}
