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

import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;
import android.app.Activity;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;

import java.io.File;
import java.io.IOException;

/**
 * Play a movie from a file on disk.  Output goes to a SurfaceView.
 * <p>
 * This is very similar to PlayMovieActivity, but the output goes to a SurfaceView instead of
 * a TextureView.  There are some important differences:
 * <ul>
 *   <li> TextureViews behave like normal views.  SurfaceViews don't.  A SurfaceView has
 *        a transparent "hole" in the UI through which an independent Surface layer can
 *        be seen.  This Surface is sent directly to the system graphics compositor.
 *   <li> Because the video is being composited with the UI by the system compositor,
 *        rather than the application, it can often be done more efficiently (e.g. using
 *        a hardware composer "overlay").  This can lead to significant battery savings
 *        when playing a long movie.
 *   <li> On the other hand, the TextureView contents can be freely scaled and rotated
 *        with a simple matrix.  The SurfaceView output is limited to scaling, and it's
 *        more awkward to do.
 *   <li> DRM-protected content can't be touched by the app (or even the system compositor).
 *        We have to point the MediaCodec decoder at a Surface that is composited by a
 *        hardware composer overlay.  The only way to do the app side of this is with
 *        SurfaceView.
 * </ul>
 * <p>
 * The MediaCodec decoder requests buffers from the Surface, passing the video dimensions
 * in as arguments.  The Surface provides buffers with a matching size, which means
 * the video data will completely cover the Surface.  As a result, there's no need to
 * use SurfaceHolder#setFixedSize() to set the dimensions.  The hardware scaler will scale
 * the video to match the view size, so if we want to preserve the correct aspect ratio
 * we need to adjust the View layout.  We can use our custom AspectFrameLayout for this.
 * <p>
 * The actual playback of the video -- sending frames to a Surface -- is the same for
 * TextureView and SurfaceView.
 */
public class PlayMovieSurfaceActivity extends Activity implements OnItemSelectedListener,
        SurfaceHolder.Callback, MoviePlayer.PlayerFeedback {
    private static final String TAG = MainActivity.TAG;

    private SurfaceView mSurfaceView;
    private String[] mMovieFiles;
    private int mSelectedMovie;
    private boolean mShowStopLabel;
    private MoviePlayer.PlayTask mPlayTask;
    private boolean mSurfaceHolderReady = false;

    /**
     * Overridable  method to get layout id.  Any provided layout needs to include
     * the same views (or compatible) as active_play_movie_surface
     *
     */
    protected int getContentViewId() {
        return R.layout.activity_play_movie_surface;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getContentViewId());

        mSurfaceView = (SurfaceView) findViewById(R.id.playMovie_surface);
        mSurfaceView.getHolder().addCallback(this);

        // Populate file-selection spinner.
        Spinner spinner = (Spinner) findViewById(R.id.playMovieFile_spinner);
        // Need to create one of these fancy ArrayAdapter thingies, and specify the generic layout
        // for the widget itself.
        mMovieFiles = MiscUtils.getFiles(getFilesDir(), "*.mp4");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mMovieFiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        updateControls();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "PlayMovieSurfaceActivity onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "PlayMovieSurfaceActivity onPause");
        super.onPause();
        // We're not keeping track of the state in static fields, so we need to shut the
        // playback down.  Ideally we'd preserve the state so that the player would continue
        // after a device rotation.
        //
        // We want to be sure that the player won't continue to send frames after we pause,
        // because we're tearing the view down.  So we wait for it to stop here.
        if (mPlayTask != null) {
            stopPlayback();
            mPlayTask.waitForStop();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // There's a short delay between the start of the activity and the initialization
        // of the SurfaceHolder that backs the SurfaceView.  We don't want to try to
        // send a video stream to the SurfaceView before it has initialized, so we disable
        // the "play" button until this callback fires.
        Log.d(TAG, "surfaceCreated");
        mSurfaceHolderReady = true;
        updateControls();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // ignore
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // ignore
        Log.d(TAG, "Surface destroyed");
    }

    /*
     * Called when the movie Spinner gets touched.
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        mSelectedMovie = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + mSelectedMovie + " '" + mMovieFiles[mSelectedMovie] + "'");
    }

    @Override public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * onClick handler for "play"/"stop" button.
     */
    public void clickPlayStop(@SuppressWarnings("unused") View unused) {
        if (mShowStopLabel) {
            Log.d(TAG, "stopping movie");
            stopPlayback();
            // Don't update the controls here -- let the task thread do it after the movie has
            // actually stopped.
            //mShowStopLabel = false;
            //updateControls();
        } else {
            if (mPlayTask != null) {
                Log.w(TAG, "movie already playing");
                return;
            }

            Log.d(TAG, "starting movie");
            SpeedControlCallback callback = new SpeedControlCallback();
            SurfaceHolder holder = mSurfaceView.getHolder();
            Surface surface = holder.getSurface();

            // Don't leave the last frame of the previous video hanging on the screen.
            // Looks weird if the aspect ratio changes.
            clearSurface(surface);

            MoviePlayer player = null;
            try {
                 player = new MoviePlayer(
                        new File(getFilesDir(), mMovieFiles[mSelectedMovie]), surface, callback);
            } catch (IOException ioe) {
                Log.e(TAG, "Unable to play movie", ioe);
                surface.release();
                return;
            }

            AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.playMovie_afl);
            int width = player.getVideoWidth();
            int height = player.getVideoHeight();
            layout.setAspectRatio((double) width / height);
            //holder.setFixedSize(width, height);

            mPlayTask = new MoviePlayer.PlayTask(player, this);

            mShowStopLabel = true;
            updateControls();
            mPlayTask.execute();
        }
    }

    /**
     * Requests stoppage if a movie is currently playing.
     */
    private void stopPlayback() {
        if (mPlayTask != null) {
            mPlayTask.requestStop();
        }
    }

    @Override   // MoviePlayer.PlayerFeedback
    public void playbackStopped() {
        Log.d(TAG, "playback stopped");
        mShowStopLabel = false;
        mPlayTask = null;
        updateControls();
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    private void updateControls() {
        Button play = (Button) findViewById(R.id.play_stop_button);
        if (mShowStopLabel) {
            play.setText(R.string.stop_button_text);
        } else {
            play.setText(R.string.play_button_text);
        }
        play.setEnabled(mSurfaceHolderReady);
    }

    /**
     * Clears the playback surface to black.
     */
    private void clearSurface(Surface surface) {
        // We need to do this with OpenGL ES (*not* Canvas -- the "software render" bits
        // are sticky).  We can't stay connected to the Surface after we're done because
        // that'd prevent the video encoder from attaching.
        //
        // If the Surface is resized to be larger, the new portions will be black, so
        // clearing to something other than black may look weird unless we do the clear
        // post-resize.
        EglCore eglCore = new EglCore();
        WindowSurface win = new WindowSurface(eglCore, surface, false);
        win.makeCurrent();
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        win.swapBuffers();
        win.release();
        eglCore.release();
    }
}
