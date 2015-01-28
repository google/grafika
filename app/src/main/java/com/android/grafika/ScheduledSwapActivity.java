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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Trace;
import android.app.Activity;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView.OnItemSelectedListener;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.WindowSurface;

import java.lang.ref.WeakReference;

/**
 * Exercises a SurfaceFlinger feature that defers acquisition of a buffer until a
 * certain time.  The purpose of the feature is to make A/V sync easier by allowing
 * an app running at normal priority to schedule multiple frames with SurfaceFlinger
 * (which runs at elevated priority) well ahead of time.
 * <p>
 * Requires API 19 (Android 4.4 "KitKat").  In previous releases, frames are shown as
 * soon as possible.
 * <p>
 * We coordinate with the display refresh using Choreographer.  The SurfaceFlinger DispSync
 * enhancements may cause the Choreographer-reported vsync time to be offset from the
 * actual-reported vsync time (which may itself be slightly offset from the actual-actual
 * vsync time).  None of this is terribly important unless you care about A/V sync.
 */
public class ScheduledSwapActivity extends Activity implements OnItemSelectedListener,
        SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final String TAG = MainActivity.TAG;

    private final static long ONE_MILLISECOND_NS = 1000000;

    /**
     * Frame update patterns.  Each digit represents the number of times a given source
     * frame will be repeated.  The associated labels ("30 fps") assume the display refresh
     * is 60fps.  If it's not, to meet the expected frame rate you'd either need to
     * pre-compute arrays for expected refresh rates, or just compute the hold counts
     * dynamically.  Using fixed patterns here removes a potential source of non-determinism,
     * making it easier to analyze the output with systrace.
     * <p>
     * For example, for 24 fps we use an alternating pattern of 3 and 2 (3-2 pulldown).  This
     * means every 2 frames of source material is held on screen for a total of 5 frames;
     * 2x12=24, 5x12=60.
     */
    private static final String[] UPDATE_PATTERNS = {   // sync with scheduledSwapUpdateNames
        "4",        // 15 fps
        "32",       // 24 fps
        "32322",    // 25 fps
        "2",        // 30 fps
        "2111",     // 48 fps
        "1",        // 60 fps
        "15"        // erratic, useful for examination with systrace
    };

    /**
     * How far ahead of time we schedule frames.
     * <p>
     * For example, if choreographer tells us the current time is N, and we want the next
     * frame to be visible at time N+3, and "frames ahead" is set to 2, we'll wait another
     * frame before scheduling it.
     * <p>
     * N=2 is safe, N=1 requires everything to be running quickly *and* be using nonzero
     * DispSync offsets, and N=0 is impossible.  The BufferQueue will probably have three
     * buffers, one of which will be tied up by the display, so N=3 will cause us to stall
     * in eglSwapBuffers() if we're submitting at 60Hz.
     * <p>
     * We could just blast frames as quickly as possible; eglSwapBuffers() will stall
     * when necessary.  However, it will be harder to tell if we're falling behind and need
     * to drop a frame.  (Some devices have drivers with flaws that prevent SurfaceFlinger
     * from dropping frames with "stale" presentation time stamps, so it's necessary to
     * handle that in the app.)
     */
    private static final int[] FRAME_AHEAD = {  // sync with scheduledSwapAheadNames
        0, 1, 2, 3
    };

    // Rendering code runs on this thread.  The thread's life span is tied to the Surface.
    private RenderThread mRenderThread;

    private long mRefreshPeriodNs;

    private int mUpdatePatternIndex = 1;    // 24fps
    private int mFramesAheadIndex = 2;      // +2


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scheduled_swap);

        // Update-rate spinner; specifies the frame rate.
        Spinner spinner = (Spinner) findViewById(R.id.scheduledSwapUpdate_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.scheduledSwapUpdateNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setSelection(mUpdatePatternIndex);
        spinner.setOnItemSelectedListener(this);

        // Frame-ahead spinner; specifies how far ahead we schedule.
        spinner = (Spinner) findViewById(R.id.scheduledSwapAhead_spinner);
        adapter = ArrayAdapter.createFromResource(this,
                R.array.scheduledSwapAheadNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(mFramesAheadIndex);
        spinner.setOnItemSelectedListener(this);

        // Query the display for its approximate refresh rate.
        mRefreshPeriodNs = MiscUtils.getDisplayRefreshNsec(this);
        updateControls(0);

        SurfaceView sv = (SurfaceView) findViewById(R.id.scheduledSwap_surfaceView);
        sv.getHolder().addCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // If the callback was posted, remove it.  This stops the notifications.  Ideally we
        // would send a message to the thread letting it know, so when it wakes up it can
        // reset its notion of when the previous Choreographer event arrived.
        Log.d(TAG, "onPause unhooking choreographer");
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If we already have a Surface, we just need to resume the frame notifications.
        if (mRenderThread != null) {
            Log.d(TAG, "onResume re-hooking choreographer");
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder);
        // If we already have a Surface, we just need to resume the frame notifications.

        SurfaceView sv = (SurfaceView) findViewById(R.id.scheduledSwap_surfaceView);
        mRenderThread = new RenderThread(sv.getHolder(), this);
        mRenderThread.setName("ScheduledSwap GL render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSetParameters(mUpdatePatternIndex, mFramesAheadIndex);
            rh.sendSurfaceCreated();
        }

        // start the draw events
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceChanged(format, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // We need to wait for the render thread to shut down before continuing because we
        // don't want the Surface to disappear out from under it mid-render.  The frame
        // notifications will have been stopped back in onPause(), but there might have
        // been one in progress.

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendShutdown();
            try {
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // not expected
                throw new RuntimeException("join was interrupted", ie);
            }
        }
        mRenderThread = null;

        Log.d(TAG, "surfaceDestroyed complete");
    }

    /**
     * Choreographer callback, called near vsync.
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        // The events should not start until the RenderThread is created, and should stop
        // before it's nulled out.
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            Choreographer.getInstance().postFrameCallback(this);
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    // spinner item selected
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        final int selIndex = spinner.getSelectedItemPosition();
        boolean updated = false;

        if (parent.getId() == R.id.scheduledSwapUpdate_spinner) {
            if (mUpdatePatternIndex != selIndex) {
                Log.d(TAG, "onItemSelected [update-rate]: " + selIndex);
                mUpdatePatternIndex = selIndex;
                updated = true;
            }
        } else if (parent.getId() == R.id.scheduledSwapAhead_spinner) {
            if (mFramesAheadIndex != selIndex) {
                Log.d(TAG, "onItemSelected [frames-ahead]: " + selIndex);
                mFramesAheadIndex = selIndex;
                updated = true;
            }
        } else {
            throw new RuntimeException("Unknown spinner");
        }

        if (mRenderThread == null) {
            // huh
            Log.d(TAG, "In onItemSelected while the activity is paused");
        } else if (updated) {
            RenderHandler rh = mRenderThread.getHandler();
            if (rh != null) {
                rh.sendSetParameters(mUpdatePatternIndex, mFramesAheadIndex);
            }
        }
    }

    @Override public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * Updates UI elements.
     */
    private void updateControls(int droppedFrames) {
        String str = getString(R.string.scheduledSwapStatus, droppedFrames);
        TextView tv = (TextView) findViewById(R.id.scheduledSwapStatus_text);
        tv.setText(str);

        str = getString(R.string.scheduledSwapRefresh, mRefreshPeriodNs);
        tv = (TextView) findViewById(R.id.scheduledSwapRefresh_text);
        tv.setText(str);
    }

    /**
     * This class handles all OpenGL rendering.
     * <p>
     * Start the render thread after the Surface has been created.
     */
    private static class RenderThread extends Thread {
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        private volatile RenderHandler mHandler;

        // A reference to our Activity, so we can update the UI with runOnUiThread().
        private ScheduledSwapActivity mActivity;

        // Used to wait for the thread to start.
        private Object mStartLock = new Object();
        private boolean mReady = false;

        private volatile SurfaceHolder mSurfaceHolder;  // may be updated by UI thread
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;

        private int mUpdatePatternOffset;
        private int mHoldFrames;

        private int mChoreographerSkips;
        private int mDroppedFrames;
        private long mPreviousRefreshNs;

        // These have slightly different names from the equivalents in the Activity to reduce
        // confusion.
        private int mUpdatePatternIdx;
        private int mFramesAheadIdx;

        private int mWidth;
        private int mHeight;
        private int mPosition;
        private int mSpeed;
        private int mBlockWidth;

        private long mRefreshPeriodNs = -1;     // value will be approximate


        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         */
        public RenderThread(SurfaceHolder holder, ScheduledSwapActivity activity) {
            mSurfaceHolder = holder;
            mActivity = activity;

            // Query the display for its approximate refresh rate.
            mRefreshPeriodNs = MiscUtils.getDisplayRefreshNsec(activity);
        }

        /**
         * Thread entry point.
         * <p>
         * The thread should not be started until the Surface associated with the SurfaceHolder
         * has been created.  That way we don't have to wait for a separate "surface created"
         * message to arrive.
         */
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new RenderHandler(this);
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE | EglCore.FLAG_TRY_GLES3);
            synchronized (mStartLock) {
                mReady = true;
                mStartLock.notify();    // signal waitUntilReady()
            }

            Looper.loop();

            Log.d(TAG, "looper quit");
            releaseGl();
            mEglCore.release();

            synchronized (mStartLock) {
                mReady = false;
            }
        }

        /**
         * Waits until the render thread is ready to receive messages.
         * <p>
         * Call from the UI thread.
         */
        public void waitUntilReady() {
            synchronized (mStartLock) {
                while (!mReady) {
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Shuts everything down.
         */
        private void shutdown() {
            Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
        }

        /**
         * Returns the render thread's Handler.  This may be called from any thread.
         */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /**
         * Prepares the surface.
         */
        private void surfaceCreated() {
            Surface surface = mSurfaceHolder.getSurface();
            prepareGl(surface);
        }

        /**
         * Prepares window surface and GL state.
         */
        private void prepareGl(Surface surface) {
            Log.d(TAG, "prepareGl");

            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mWindowSurface.makeCurrent();
        }

        /**
         * Releases most of the GL resources we currently hold.
         * <p>
         * Does not release EglCore.
         */
        private void releaseGl() {
            GlUtil.checkGlError("releaseGl start");

            if (mWindowSurface != null) {
                mWindowSurface.release();
                mWindowSurface = null;
            }

            GlUtil.checkGlError("releaseGl done");

            mEglCore.makeNothingCurrent();
        }

        /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing.
         * (Called from RenderHandler.)
         */
        private void surfaceChanged(int width, int height) {
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            mWidth = width;
            mHeight = height;
            mBlockWidth = mWidth / 16;
            mPosition = 0;
            mSpeed = (mWidth / 120) + 1;
        }

        /**
         * Sets the frame delivery parameters
         */
        private void setParameters(int updatePatternIndex, int framesAheadIndex) {
            if (mUpdatePatternIdx != updatePatternIndex ||
                    mFramesAheadIdx != framesAheadIndex) {
                mUpdatePatternIdx = updatePatternIndex;
                mFramesAheadIdx = framesAheadIndex;
                mUpdatePatternOffset = mHoldFrames = 0;
                Log.d(TAG, "Parameters now " + mUpdatePatternIdx + " / " + mFramesAheadIdx);
            }
        }

        /**
         * Advance state and draw frame in response to a Choreographer vsync event.
         * <p>
         * This currently just kicks out frames with timestamp: reported-vsync + (N * refresh).
         * We don't drop frames, and we complain in situations that are recoverable.
         */
        public void doFrame(long frameTimeNs) {
            // Why do we want to use the PTS feature?
            //
            // When you submit a buffer for display, it gets latched by SurfaceFlinger, and
            // then displayed the next time the display refreshes.  As the system gets busy,
            // a buffer submit that was happening just before SF looks for buffers might
            // sometimes happen just after, and you'll end up displaying the same buffer again.
            // Scheduling things into the future makes it easier to hit buffer submission
            // deadlines reliably.

            // Should we use Choreographer or just sleep()?
            //
            // The advantage to using Choreographer is that it tells us the refresh time of
            // the display.  Keeping our own private clock works fine until our frame-delivery
            // time is close to aligning with the display's time.  Sometimes we're going to
            // submit a little early, sometimes a little late, and if the display (or we) are
            // drifting forward and backward we're going to look jumpy.  Even if we're not
            // using the PTS feature, we need to be aware of what the display is doing.
            //
            // If we're playing a video, the video stream has its own clock -- each frame has
            // a defined presentation time.  That adds a complication that this simple test
            // app doesn't have -- here we're delivering frames at a fixed multiple of the
            // display rate.  To achieve perfect 30fps display of a movie on a display that's
            // updating at 58 or 62Hz, you'd need to double or drop frames occasionally to
            // match the video to the display.  (Or just play the movie a little off.)
            //
            // Sometimes Choreographer skips a frame, but that's the result of the system
            // being busy enough to starve the UI thread.

            // How do we know if we should drop a frame?
            //
            // For safety we want to submit a frame at least two refresh periods before it's
            // supposed to appear, though if we get lucky we can get away with one period.  We
            // submit the frame to SurfaceFlinger, which wakes up once per refresh period and
            // latches all incoming content for composition and display.
            //
            // Suppose we're rendering 24fps video on a 60Hz display (3-2 pattern).  We generate
            // a series of draw calls, two frames ahead of when the frame will appear, with the
            // target presentation time stamp (PTS) specified:
            //
            //   | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
            //   |A@2|   |   |B@5|   |C@7|   |   | ...
            //
            // Ideally, this will result in the following on the display:
            //
            //   | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
            //   ... | . | A | A | A | B | B | C | C | C |
            //
            // If doFrame() gets called at time=0, we draw 'A', and schedule it to appear in
            // frame 2.  When we're next called at time=1, we do nothing, because we want to keep
            // showing 'A', and it's not yet time to draw and schedule 'B'.
            //
            // If we get called for time=0, but the clock says it's already time=1 (because the
            // thread got stalled waiting for higher-priority tasks to execute), we still want to
            // draw 'A'.  It may arrive in time to appear in frame 2, or it might take the full
            // two frames and not appear until frame 3, but there's no harm in trying.  Same
            // story for waking up at time=2.
            //
            // If we don't wake up until time 3, we draw 'B' and schedule it for frame 5.  Should
            // we try to draw 'A' and hope we get lucky and it appears in frame 4?  Ideally it
            // would either get scheduled for frame 4, or if it gets bumped to frame 5 it will
            // get dropped when it collides with 'B'.  On devices with poor drivers, the drop
            // doesn't happen, and we'll end up with 'A' in frame 5 and 'B' first appearing in 6.
            //
            // We don't necessarily need to wait until frame N-2 to submit a buffer.  It's
            // best if we only have two buffers queued up at any time to avoid stalling in
            // eglSwapBuffers() (SurfaceView is currently triple-buffered), but we could
            // submit both 'A' and 'B' right off the bat, and 'C' in frame 2.  The advantage
            // to doing this is that we're less likely to have visible hiccups from GCs or
            // background processes, because we can stall for longer without missing our
            // deadline.  The disadvantage to doing it this way is that it will look terrible
            // on pre-4.4 devices that don't have the PTS handling in SurfaceFlinger.

            // TODO: the existing code isn't particularly sophisticated, and does not fully
            // implement what is described above.  It just kicks out frames and complains a
            // little if we appear to be falling behind.
            boolean draw = advance(frameTimeNs);

            if (draw) {
                Trace.beginSection("doFrame draw");
                mWindowSurface.makeCurrent();
                draw();

                // Set the timestamp.  The refresh period is approximate, so this value may
                // be slightly off of the actual refresh time, but SurfaceFlinger provides
                // for some amount of slop.
                int framesAhead = FRAME_AHEAD[mFramesAheadIdx];
                if (framesAhead > 0) {
                    long presentNs = frameTimeNs + mRefreshPeriodNs * framesAhead;
                    mWindowSurface.setPresentationTime(presentNs);
                }

                mWindowSurface.swapBuffers();
            } else {
                Trace.beginSection("doFrame nodraw");
            }
            Trace.endSection();
        }

        /**
         * Returns the hold time for the current update pattern index/offset.
         */
        private int getHoldTime() {
            char ch = UPDATE_PATTERNS[mUpdatePatternIdx].charAt(mUpdatePatternOffset);
            return ch - '0';
        }

        /**
         * Advances state.
         *
         * @return True if something has visibly changed and we need to redraw.
         */
        private boolean advance(long frameTimeNs) {
            boolean draw = false;

            if (mHoldFrames > 1) {
                mHoldFrames--;
                //Log.v(TAG, "holding (now " + mHoldFrames + ")");
            } else {
                mUpdatePatternOffset =
                        (mUpdatePatternOffset + 1) % UPDATE_PATTERNS[mUpdatePatternIdx].length();
                mHoldFrames = getHoldTime();
                draw = true;
                //Log.v(TAG, "drawing (off=" + mUpdatePatternOffset + " hold=" + mHoldFrames + ")");

                mPosition += mSpeed;
                if (mPosition < -mSpeed || mPosition + mBlockWidth + mSpeed >= mWidth) {
                    // next frame will draw partly offscreen; reverse course now
                    mSpeed = -mSpeed;
                }
            }

            boolean complain = false;

            // Watch for Choreographer skipping frames.  The current implementation doesn't
            // handle these.
            if (mPreviousRefreshNs != 0 &&
                    frameTimeNs - mPreviousRefreshNs > mRefreshPeriodNs + ONE_MILLISECOND_NS) {
                mChoreographerSkips++;
                complain = true;
                Log.d(TAG, frameTimeNs + ": Choreographer skip: " +
                        ((frameTimeNs - mPreviousRefreshNs) / 1000000.0) + " ms");
            }
            mPreviousRefreshNs = frameTimeNs;

            // Check to see if we're falling behind.  We do this by comparing the Choreographer
            // reported-vsync time to the current time, and seeing if we're already into the
            // next refresh.
            //
            // We could drop a frame by changing "draw" from true to false, but as noted elsewhere
            // we don't necessarily want to do that every time we miss our window.  For now we
            // just complain and carry on.
            long diff = System.nanoTime() - frameTimeNs;
            if (diff > mRefreshPeriodNs - ONE_MILLISECOND_NS) {
                Log.d(TAG, frameTimeNs + ": overrun: " + (diff / 1000000.0) + " ms");
                mDroppedFrames++;       // more like "should have dropped" frames
                complain = true;
            }

            if (complain) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mActivity.updateControls(mDroppedFrames + mChoreographerSkips);
                    }
                 });
            }

            return draw;
        }

        /**
         * Draws the scene.
         */
        private void draw() {
            GlUtil.checkGlError("draw start");

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(mPosition, mHeight * 2 / 8, mBlockWidth, mHeight / 8);
            GLES20.glClearColor(1f, 1f * (mDroppedFrames & 0x01),
                    1f * (mChoreographerSkips & 0x01), 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

            GlUtil.checkGlError("draw done");
        }
    }


    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_SET_PARAMETERS = 3;
        private static final int MSG_SHUTDOWN = 5;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface created" message.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceCreated() {
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format,
                int width, int height) {
            // ignore format
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p>
         * Call from UI thread.
         */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(RenderHandler.MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /**
         * Sends the "set parameters" message, updating the indices set by the UI elements.
         * <p>
         * Call from UI thread.
         */
        public void sendSetParameters(int updatePatternIndex, int framesAheadIndex) {
            sendMessage(obtainMessage(RenderHandler.MSG_SET_PARAMETERS,
                    updatePatternIndex, framesAheadIndex));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_CREATED:
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    long timestamp = (((long) msg.arg1) << 32) |
                                     (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_SET_PARAMETERS:
                    renderThread.setParameters(msg.arg1, msg.arg2);
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
               default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}
