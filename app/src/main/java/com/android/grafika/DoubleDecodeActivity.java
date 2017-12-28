/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.os.Bundle;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.IOException;

/**
 * Decodes two video streams simultaneously to two TextureViews.
 * <p>
 * One key feature is that the video decoders do not stop when the activity is restarted due
 * to an orientation change.  This is to simulate playback of a real-time video stream.  If
 * the Activity is pausing because it's "finished" (indicating that we're leaving the Activity
 * for a nontrivial amount of time), the video decoders are shut down.
 * <p>
 * TODO: consider shutting down when the screen is turned off, to preserve battery.
 */
public class DoubleDecodeActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private static final int VIDEO_COUNT = 2;

    // Must be static storage so they'll survive Activity restart.
    private static boolean sVideoRunning = false;
    private static VideoBlob[] sBlob = new VideoBlob[VIDEO_COUNT];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_double_decode);

        if (!sVideoRunning) {
            sBlob[0] = new VideoBlob((TextureView) findViewById(R.id.double1_texture_view),
                    ContentManager.MOVIE_SLIDERS, 0);
            sBlob[1] = new VideoBlob((TextureView) findViewById(R.id.double2_texture_view),
                    ContentManager.MOVIE_EIGHT_RECTS, 1);
            sVideoRunning = true;
        } else {
            sBlob[0].recreateView((TextureView) findViewById(R.id.double1_texture_view));
            sBlob[1].recreateView((TextureView) findViewById(R.id.double2_texture_view));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        boolean finishing = isFinishing();
        Log.d(TAG, "isFinishing: " + finishing);
        for (int i = 0; i < VIDEO_COUNT; i++) {
            if (finishing) {
                sBlob[i].stopPlayback();
                sBlob[i] = null;
            }
        }
        sVideoRunning = !finishing;
        Log.d(TAG, "onPause complete");
    }


    /**
     * Video playback blob.
     * <p>
     * Encapsulates the video decoder and playback surface.
     * <p>
     * We want to avoid tearing down and recreating the video decoder on orientation changes,
     * because it can be expensive to do so.  That means keeping the decoder's output Surface
     * around, which means keeping the SurfaceTexture around.
     * <p>
     * It's possible that the orientation change will cause the UI thread's EGL context to be
     * torn down and recreated (the app framework docs don't seem to make any guarantees here),
     * so we need to detach the SurfaceTexture from EGL on destroy, and reattach it when
     * the new SurfaceTexture becomes available.  Happily, TextureView does this for us.
     */
    private static class VideoBlob implements TextureView.SurfaceTextureListener {
        private final String LTAG;
        private TextureView mTextureView;
        private int mMovieTag;

        private SurfaceTexture mSavedSurfaceTexture;
        private PlayMovieThread mPlayThread;
        private SpeedControlCallback mCallback;

        /**
         * Constructs the VideoBlob.
         *
         * @param view The TextureView object we want to draw into.
         * @param movieTag Which movie to play.
         * @param ordinal The blob's ordinal (only used for log messages).
         */
        public VideoBlob(TextureView view, int movieTag, int ordinal) {
            LTAG = TAG + ordinal;
            Log.d(LTAG, "VideoBlob: tag=" + movieTag + " view=" + view);
            mMovieTag = movieTag;

            mCallback = new SpeedControlCallback();

            recreateView(view);
        }

        /**
         * Performs partial construction.  The VideoBlob is already created, but the Activity
         * was recreated, so we need to update our view.
         */
        public void recreateView(TextureView view) {
            Log.d(LTAG, "recreateView: " + view);
            mTextureView = view;
            mTextureView.setSurfaceTextureListener(this);
            if (mSavedSurfaceTexture != null) {
                Log.d(LTAG, "using saved st=" + mSavedSurfaceTexture);
                view.setSurfaceTexture(mSavedSurfaceTexture);
            }
        }

        /**
         * Stop playback and shut everything down.
         */
        public void stopPlayback() {
            Log.d(LTAG, "stopPlayback");
            mPlayThread.requestStop();
            // TODO: wait for the playback thread to stop so we don't kill the Surface
            //       before the video stops

            // We don't need this any more, so null it out.  This also serves as a signal
            // to let onSurfaceTextureDestroyed() know that it can tell TextureView to
            // free the SurfaceTexture.
            mSavedSurfaceTexture = null;
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            Log.d(LTAG, "onSurfaceTextureAvailable size=" + width + "x" + height + ", st=" + st);

            // If this is our first time though, we're going to use the SurfaceTexture that
            // the TextureView provided.  If not, we're going to replace the current one with
            // the original.

            if (mSavedSurfaceTexture == null) {
                mSavedSurfaceTexture = st;

                File sliders = ContentManager.getInstance().getPath(mMovieTag);
                mPlayThread = new PlayMovieThread(sliders, new Surface(st), mCallback);
            } else {
                // Can't do it here in Android <= 4.4.  The TextureView doesn't add a
                // listener on the new SurfaceTexture, so it never sees any updates.
                // Needs to happen from activity onCreate() -- see recreateView().
                //Log.d(LTAG, "using saved st=" + mSavedSurfaceTexture);
                //mTextureView.setSurfaceTexture(mSavedSurfaceTexture);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            Log.d(LTAG, "onSurfaceTextureSizeChanged size=" + width + "x" + height + ", st=" + st);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            Log.d(LTAG, "onSurfaceTextureDestroyed st=" + st);
            // The SurfaceTexture is already detached from the EGL context at this point, so
            // we don't need to do that.
            //
            // The saved SurfaceTexture will be null if we're shutting down, so we want to
            // return "true" in that case (indicating that TextureView can release the ST).
            return (mSavedSurfaceTexture == null);
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            //Log.d(TAG, "onSurfaceTextureUpdated st=" + st);
        }
    }

    /**
     * Thread object that plays a movie from a file to a surface.
     * <p>
     * Currently loops until told to stop.
     */
    private static class PlayMovieThread extends Thread {
        private final File mFile;
        private final Surface mSurface;
        private final SpeedControlCallback mCallback;
        private MoviePlayer mMoviePlayer;

        /**
         * Creates thread and starts execution.
         * <p>
         * The object takes ownership of the Surface, and will access it from the new thread.
         * When playback completes, the Surface will be released.
         */
        public PlayMovieThread(File file, Surface surface, SpeedControlCallback callback) {
            mFile = file;
            mSurface = surface;
            mCallback = callback;

            start();
        }

        /**
         * Asks MoviePlayer to halt playback.  Returns without waiting for playback to halt.
         * <p>
         * Call from UI thread.
         */
        public void requestStop() {
            mMoviePlayer.requestStop();
        }

        @Override
        public void run() {
            try {
                mMoviePlayer = new MoviePlayer(mFile, mSurface, mCallback);
                mMoviePlayer.setLoopMode(true);
                mMoviePlayer.play();
            } catch (IOException ioe) {
                Log.e(TAG, "movie playback failed", ioe);
            } finally {
                mSurface.release();
                Log.d(TAG, "PlayMovieThread stopping");
            }
        }
    }
}
