package com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.surface;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.ViewGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;

public class TextureViewWrapper implements SurfaceWrapper {
    private int mState = SURFACE_NOT_CREATED;
    private final TextureView mVideoSurface;
    private SurfaceHolder.Callback mMediaPlaybackCallback;
    private Surface mSurface;

    @SuppressLint("WrongConstant")
    public TextureViewWrapper(Context context, ViewGroup root) {
        mVideoSurface = (TextureView) LayoutInflater.from(context).inflate(
                R.layout.lb_video_texture, root, false);
        mVideoSurface.setSurfaceTextureListener(new SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (mSurface != null) {
                    mSurface.release();
                }
                mSurface = new Surface(surface);
                mState = SURFACE_CREATED;
                if (mMediaPlaybackCallback != null) {
                    mMediaPlaybackCallback.surfaceCreated(new TextureViewSurfaceHolder(mSurface));
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                if (mMediaPlaybackCallback != null && mSurface != null) {
                    mMediaPlaybackCallback.surfaceChanged(new TextureViewSurfaceHolder(mSurface), 4, width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Surface destroyedSurface = mSurface;
                mSurface = null;
                mState = SURFACE_NOT_CREATED;
                if (mMediaPlaybackCallback != null && destroyedSurface != null) {
                    mMediaPlaybackCallback.surfaceDestroyed(new TextureViewSurfaceHolder(destroyedSurface));
                }
                if (destroyedSurface != null) {
                    destroyedSurface.release();
                }

                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    /**
     * Adds {@link SurfaceHolder.Callback} to {@link SurfaceView}.
     */
    public void setSurfaceHolderCallback(SurfaceHolder.Callback callback) {
        mMediaPlaybackCallback = callback;

        if (callback != null) {
            if (mState == SURFACE_CREATED) {
                if (mSurface == null && mVideoSurface.getSurfaceTexture() != null) {
                    mSurface = new Surface(mVideoSurface.getSurfaceTexture());
                }
                if (mSurface != null) {
                    mMediaPlaybackCallback.surfaceCreated(new TextureViewSurfaceHolder(mSurface));
                }
            }
        }
    }

    @Override
    public View getSurfaceView() {
        return mVideoSurface;
    }
}
