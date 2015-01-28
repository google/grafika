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
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.Rect;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FlatShadedProgram;
import com.android.grafika.gles.GeneratedTexture;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.lang.ref.WeakReference;


/**
 * Exercises SurfaceHolder#setFixedSize().
 * <p>
 * http://android-developers.blogspot.com/2013/09/using-hardware-scaler-for-performance.html
 * <p>
 * The purpose of the feature is to allow games to render at 720p or 1080p to get good
 * performance on displays with a large number of pixels.  It's easier (and more fun) to
 * see the effects when we crank the resolution way down.  Normally the resolution would
 * be fixed, perhaps with minor tweaks (e.g. letterboxing via AspectFrameLayout) to match
 * the device aspect ratio, but here we make it variable to match the display window.
 * <p>
 * TODO: examine effects on touch input
 */
public class HardwareScalerActivity extends Activity implements SurfaceHolder.Callback,
        Choreographer.FrameCallback {
    private static final String TAG = MainActivity.TAG;

    // [ This used to have "a few thoughts about app life cycle and SurfaceView".  These
    //   are now at http://source.android.com/devices/graphics/architecture.html in
    //   Appendix B. ]
    //
    // This Activity uses approach #2 (Surface-driven).

    // Indexes into the data arrays.
    private static final int SURFACE_SIZE_TINY = 0;
    private static final int SURFACE_SIZE_SMALL = 1;
    private static final int SURFACE_SIZE_MEDIUM = 2;
    private static final int SURFACE_SIZE_FULL = 3;

    private static final int[] SURFACE_DIM = new int[] { 64, 240, 480, -1 };
    private static final String[] SURFACE_LABEL = new String[] {
        "tiny", "small", "medium", "full"
    };

    private int mSelectedSize;
    private int mFullViewWidth;
    private int mFullViewHeight;
    private int[][] mWindowWidthHeight;
    private boolean mFlatShadingChecked;

    // Rendering code runs on this thread.  The thread's life span is tied to the Surface.
    private RenderThread mRenderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "HardwareScalerActivity: onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hardware_scaler);

        mSelectedSize = SURFACE_SIZE_FULL;
        mFullViewWidth = mFullViewHeight = 512;     // want actual view size, but it's not avail
        mWindowWidthHeight = new int[SURFACE_DIM.length][2];
        updateControls();

        SurfaceView sv = (SurfaceView) findViewById(R.id.hardwareScaler_surfaceView);
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

        // Grab the view's width.  It's not available before now.
        Rect size = holder.getSurfaceFrame();
        mFullViewWidth = size.width();
        mFullViewHeight = size.height();

        // Configure our fixed-size values.  We want to configure it so that the narrowest
        // dimension (e.g. width when device is in portrait orientation) is equal to the
        // value in SURFACE_DIM, and the other dimension is sized to maintain the same
        // aspect ratio.
        float windowAspect = (float) mFullViewHeight / (float) mFullViewWidth;
        for (int i = 0; i < SURFACE_DIM.length; i++) {
            if (i == SURFACE_SIZE_FULL) {
                // special-case for full size
                mWindowWidthHeight[i][0] = mFullViewWidth;
                mWindowWidthHeight[i][1] = mFullViewHeight;
            } else if (mFullViewWidth < mFullViewHeight) {
                // portrait
                mWindowWidthHeight[i][0] = SURFACE_DIM[i];
                mWindowWidthHeight[i][1] = (int) (SURFACE_DIM[i] * windowAspect);
            } else {
                // landscape
                mWindowWidthHeight[i][0] = (int) (SURFACE_DIM[i] / windowAspect);
                mWindowWidthHeight[i][1] = SURFACE_DIM[i];
            }
        }

        // Some controls include text based on the view dimensions, so update now.
        updateControls();

        SurfaceView sv = (SurfaceView) findViewById(R.id.hardwareScaler_surfaceView);
        mRenderThread = new RenderThread(sv.getHolder());
        mRenderThread.setName("HardwareScaler GL render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSetFlatShading(mFlatShadingChecked);
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

    /*
     * Choreographer callback, called near vsync.
     *
     * @see android.view.Choreographer.FrameCallback#doFrame(long)
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            Choreographer.getInstance().postFrameCallback(this);
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    /**
     * onClick handler for radio buttons.
     */
    public void onRadioButtonClicked(View view) {
        int newSize;

        RadioButton rb = (RadioButton) view;
        if (!rb.isChecked()) {
            Log.d(TAG, "Got click on non-checked radio button");
            return;
        }

        switch (rb.getId()) {
            case R.id.surfaceSizeTiny_radio:
                newSize = SURFACE_SIZE_TINY;
                break;
            case R.id.surfaceSizeSmall_radio:
                newSize = SURFACE_SIZE_SMALL;
                break;
            case R.id.surfaceSizeMedium_radio:
                newSize = SURFACE_SIZE_MEDIUM;
                break;
            case R.id.surfaceSizeFull_radio:
                newSize = SURFACE_SIZE_FULL;
                break;
            default:
                throw new RuntimeException("Click from unknown id " + rb.getId());
        }
        mSelectedSize = newSize;

        int[] wh = mWindowWidthHeight[newSize];

        // Update the Surface size.  This causes a "surface changed" event, but does not
        // destroy and re-create the Surface.
        SurfaceView sv = (SurfaceView) findViewById(R.id.hardwareScaler_surfaceView);
        SurfaceHolder sh = sv.getHolder();
        Log.d(TAG, "setting size to " + wh[0] + "x" + wh[1]);
        sh.setFixedSize(wh[0], wh[1]);
    }

    public void onFlatShadingClicked(@SuppressWarnings("unused") View unused) {
        CheckBox cb = (CheckBox) findViewById(R.id.flatShading_checkbox);
        mFlatShadingChecked = cb.isChecked();

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSetFlatShading(mFlatShadingChecked);
        }
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    private void updateControls() {
        configureRadioButton(R.id.surfaceSizeTiny_radio, SURFACE_SIZE_TINY);
        configureRadioButton(R.id.surfaceSizeSmall_radio, SURFACE_SIZE_SMALL);
        configureRadioButton(R.id.surfaceSizeMedium_radio, SURFACE_SIZE_MEDIUM);
        configureRadioButton(R.id.surfaceSizeFull_radio, SURFACE_SIZE_FULL);

        TextView tv = (TextView) findViewById(R.id.viewSizeValue_text);
        tv.setText(mFullViewWidth + "x" + mFullViewHeight);

        CheckBox cb = (CheckBox) findViewById(R.id.flatShading_checkbox);
        cb.setChecked(mFlatShadingChecked);
    }

    /**
     * Generates the radio button text.
     */
    private void configureRadioButton(int id, int index) {
        RadioButton rb;
        rb = (RadioButton) findViewById(id);
        rb.setChecked(mSelectedSize == index);
        rb.setText(SURFACE_LABEL[index] + " (" + mWindowWidthHeight[index][0] + "x" +
                mWindowWidthHeight[index][1] + ")");
    }

    /**
     * This class handles all OpenGL rendering.
     * <p>
     * We use Choreographer to coordinate with the device vsync.  We deliver one frame
     * per vsync.  We can't actually know when the frame we render will be drawn, but at
     * least we get a consistent frame interval.
     * <p>
     * Start the render thread after the Surface has been created.
     */
    private static class RenderThread extends Thread {
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        private volatile RenderHandler mHandler;

        // Used to wait for the thread to start.
        private Object mStartLock = new Object();
        private boolean mReady = false;

        private volatile SurfaceHolder mSurfaceHolder;  // contents may be updated by UI thread
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;
        private FlatShadedProgram mFlatProgram;
        private Texture2dProgram mTexProgram;
        private int mCoarseTexture;
        private int mFineTexture;
        private boolean mUseFlatShading;

        // Orthographic projection matrix.
        private float[] mDisplayProjectionMatrix = new float[16];

        private final Drawable2d mTriDrawable = new Drawable2d(Drawable2d.Prefab.TRIANGLE);
        private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);

        // One spinning triangle, one bouncing rectangle, and four edge-boxes.
        private Sprite2d mTri;
        private Sprite2d mRect;
        private Sprite2d mEdges[];
        private float mRectVelX, mRectVelY;     // velocity, in viewport units per second
        private float mInnerLeft, mInnerTop, mInnerRight, mInnerBottom;

        private final float[] mIdentityMatrix;

        // Previous frame time.
        private long mPrevTimeNanos;


        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         */
        public RenderThread(SurfaceHolder holder) {
            mSurfaceHolder = holder;

            mIdentityMatrix = new float[16];
            Matrix.setIdentityM(mIdentityMatrix, 0);

            mTri = new Sprite2d(mTriDrawable);
            mRect = new Sprite2d(mRectDrawable);
            mEdges = new Sprite2d[4];
            for (int i = 0; i < mEdges.length; i++) {
                mEdges[i] = new Sprite2d(mRectDrawable);
            }
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
            mEglCore = new EglCore(null, 0);
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

            // Programs used for drawing onto the screen.
            mFlatProgram = new FlatShadedProgram();
            mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D);
            mCoarseTexture = GeneratedTexture.createTestTexture(GeneratedTexture.Image.COARSE);
            mFineTexture = GeneratedTexture.createTestTexture(GeneratedTexture.Image.FINE);

            // Set the background color.
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            // Disable depth testing -- we're 2D only.
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);

            // Don't need backface culling.  (If you're feeling pedantic, you can turn it on to
            // make sure we're defining our shapes correctly.)
            GLES20.glDisable(GLES20.GL_CULL_FACE);
        }

        /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing.
         * (Called from RenderHandler.)
         */
        private void surfaceChanged(int width, int height) {
            // This method is called when the surface is first created, and shortly after the
            // call to setFixedSize().  The tricky part is that this is called when the
            // drawing surface is *about* to change size, not when it has *already* changed
            // size.  A query on the EGL surface will confirm that the surface dimensions
            // haven't yet changed.  If you re-query after the next swapBuffers() call,
            // you will see the new dimensions.
            //
            // To have a smooth transition, we should continue to draw at the old size until the
            // surface query tells us that the size of the underlying buffers has actually
            // changed.  I don't really expect a "normal" app will want to call setFixedSize()
            // dynamically though, so in practice this situation shouldn't arise, and it's
            // just not worth the hassle of doing it right.

            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            // Use full window.
            GLES20.glViewport(0, 0, width, height);

            // Simple orthographic projection, with (0,0) in lower-left corner.
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

            int smallDim = Math.min(width, height);

            // Set initial shape size / position / velocity based on window size.  Movement
            // has the same "feel" on all devices, but the actual path will vary depending
            // on the screen proportions.  We do it here, rather than defining fixed values
            // and tweaking the projection matrix, so that our squares are square.
            mTri.setColor(0.1f, 0.9f, 0.1f);
            mTri.setTexture(mFineTexture);
            mTri.setScale(smallDim / 3.0f, smallDim / 3.0f);
            mTri.setPosition(width / 2.0f, height / 2.0f);
            mRect.setColor(0.9f, 0.1f, 0.1f);
            mRect.setTexture(mCoarseTexture);
            mRect.setScale(smallDim / 5.0f, smallDim / 5.0f);
            mRect.setPosition(width / 2.0f, height / 2.0f);
            mRectVelX = 1 + smallDim / 4.0f;
            mRectVelY = 1 + smallDim / 5.0f;

            // left edge
            float edgeWidth = 1 + width / 64.0f;
            mEdges[0].setColor(0.5f, 0.5f, 0.5f);
            mEdges[0].setScale(edgeWidth, height);
            mEdges[0].setPosition(edgeWidth / 2.0f, height / 2.0f);
            // right edge
            mEdges[1].setColor(0.5f, 0.5f, 0.5f);
            mEdges[1].setScale(edgeWidth, height);
            mEdges[1].setPosition(width - edgeWidth / 2.0f, height / 2.0f);
            // top edge
            mEdges[2].setColor(0.5f, 0.5f, 0.5f);
            mEdges[2].setScale(width, edgeWidth);
            mEdges[2].setPosition(width / 2.0f, height - edgeWidth / 2.0f);
            // bottom edge
            mEdges[3].setColor(0.5f, 0.5f, 0.5f);
            mEdges[3].setScale(width, edgeWidth);
            mEdges[3].setPosition(width / 2.0f, edgeWidth / 2.0f);

            // Inner bounding rect, used to bounce objects off the walls.
            mInnerLeft = mInnerBottom = edgeWidth;
            mInnerRight = width - 1 - edgeWidth;
            mInnerTop = height - 1 - edgeWidth;

            Log.d(TAG, "mTri: " + mTri);
            Log.d(TAG, "mRect: " + mRect);
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
            if (mFlatProgram != null) {
                mFlatProgram.release();
                mFlatProgram = null;
            }
            if (mTexProgram != null) {
                mTexProgram.release();
                mTexProgram = null;
            }
            GlUtil.checkGlError("releaseGl done");

            mEglCore.makeNothingCurrent();
        }

        /**
         * Sets whether we use textures or flat shading.
         */
        private void setFlatShading(boolean useFlatShading) {
            mUseFlatShading = useFlatShading;
        }

        /**
         * Handles the frame update.  Runs when Choreographer signals.
         */
        private void doFrame(long timeStampNanos) {
            //Log.d(TAG, "doFrame " + timeStampNanos);

            // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
            // recording is too expensive, maybe the CPU frequency governor thinks we're
            // not doing and wants to drop the clock frequencies -- we need to drop frames
            // to catch up.  The "timeStampNanos" value is based on the system monotonic
            // clock, as is System.nanoTime(), so we can compare the values directly.
            //
            // Our clumsy collision detection isn't sophisticated enough to deal with large
            // time gaps, but it's nearly cost-free, so we go ahead and do the computation
            // either way.
            //
            // We can reduce the overhead of recording, as well as the size of the movie,
            // by recording at ~30fps instead of the display refresh rate.  As a quick hack
            // we just record every-other frame, using a "recorded previous" flag.

            update(timeStampNanos);

            long diff = (System.nanoTime() - timeStampNanos) / 1000000;
            if (diff > 15) {
                // too much, drop a frame
                Log.d(TAG, "diff is " + diff + ", skipping render");
                return;
            }

            draw();
            mWindowSurface.swapBuffers();
        }

        /**
         * Advances animation state.
         *
         * We use the time delta from the previous event to determine how far everything
         * moves.  Ideally this will yield identical animation sequences regardless of
         * the device's actual refresh rate.
         */
        private void update(long timeStampNanos) {
            // Compute time from previous frame.
            long intervalNanos;
            if (mPrevTimeNanos == 0) {
                intervalNanos = 0;
            } else {
                intervalNanos = timeStampNanos - mPrevTimeNanos;

                final long ONE_SECOND_NANOS = 1000000000L;
                if (intervalNanos > ONE_SECOND_NANOS) {
                    // A gap this big should only happen if something paused us.  We can
                    // either cap the delta at one second, or just pretend like this is
                    // the first frame and not advance at all.
                    Log.d(TAG, "Time delta too large: " +
                            (double) intervalNanos / ONE_SECOND_NANOS + " sec");
                    intervalNanos = 0;
                }
            }
            mPrevTimeNanos = timeStampNanos;

            final float ONE_BILLION_F = 1000000000.0f;
            final float elapsedSeconds = intervalNanos / ONE_BILLION_F;

            // Spin the triangle.  We want one full 360-degree rotation every 3 seconds,
            // or 120 degrees per second.
            final int SECS_PER_SPIN = 3;
            float angleDelta = (360.0f / SECS_PER_SPIN) * elapsedSeconds;
            mTri.setRotation(mTri.getRotation() + angleDelta);

            // Bounce the rect around the screen.  The rect is a 1x1 square scaled up to NxN.
            // We don't do fancy collision detection, so it's possible for the box to slightly
            // overlap the edges.  We draw the edges last, so it's not noticeable.
            float xpos = mRect.getPositionX();
            float ypos = mRect.getPositionY();
            float xscale = mRect.getScaleX();
            float yscale = mRect.getScaleY();
            xpos += mRectVelX * elapsedSeconds;
            ypos += mRectVelY * elapsedSeconds;
            if ((mRectVelX < 0 && xpos - xscale/2 < mInnerLeft) ||
                    (mRectVelX > 0 && xpos + xscale/2 > mInnerRight+1)) {
                mRectVelX = -mRectVelX;
            }
            if ((mRectVelY < 0 && ypos - yscale/2 < mInnerBottom) ||
                    (mRectVelY > 0 && ypos + yscale/2 > mInnerTop+1)) {
                mRectVelY = -mRectVelY;
            }
            mRect.setPosition(xpos, ypos);
        }

        /**
         * Draws the scene.
         */
        private void draw() {
            GlUtil.checkGlError("draw start");

            // Clear to a non-black color to make the content easily differentiable from
            // the pillar-/letter-boxing.
            GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Textures may include alpha, so turn blending on.
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            if (mUseFlatShading) {
                mTri.draw(mFlatProgram, mDisplayProjectionMatrix);
                mRect.draw(mFlatProgram, mDisplayProjectionMatrix);
            } else {
                mTri.draw(mTexProgram, mDisplayProjectionMatrix);
                mRect.draw(mTexProgram, mDisplayProjectionMatrix);
            }
            GLES20.glDisable(GLES20.GL_BLEND);

            for (int i = 0; i < 4; i++) {
                mEdges[i].draw(mFlatProgram, mDisplayProjectionMatrix);
            }

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
        private static final int MSG_FLAT_SHADING = 3;
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
            sendMessage(obtainMessage(MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width,
                int height) {
            // ignore format
            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p>
         * Call from UI thread.
         */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /**
         * Sends a new value for the "flat shaded" boolean.
         */
        public void sendSetFlatShading(boolean useFlatShading) {
            // ignore format
            sendMessage(obtainMessage(MSG_FLAT_SHADING, useFlatShading ? 1:0, 0));
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
                case MSG_FLAT_SHADING:
                    renderThread.setFlatShading(msg.arg1 != 0);
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
