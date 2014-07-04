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
import android.graphics.PixelFormat;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.SimpleGlScene;
import com.android.grafika.gles.WindowSurface;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Exercises compositing two GL surfaces.
 */
public class DualGLCompositeActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    // Rendering code runs on this thread.  The thread's life span is tied to the Surface.
    private RenderThread mRenderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "DualGLCompositeActivity: onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dual_gl_composite);

        SurfaceView bsv = (SurfaceView) findViewById(R.id.dualGLComposite_bottomSurfaceView);
        bsv.getHolder().addCallback(mBottomSurfaceCallback);
        bsv.getHolder().setFormat(PixelFormat.OPAQUE);

        SurfaceView tsv = (SurfaceView) findViewById(R.id.dualGLComposite_topSurfaceView);
        tsv.getHolder().addCallback(mTopSurfaceCallback);
        tsv.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        tsv.setZOrderOnTop(true);

        mRenderThread = new RenderThread();
        mRenderThread.setName("GL render");
        mRenderThread.start();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        mRenderThread.pauseRender();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        mRenderThread.resumeRender();
    }

    // Declare two callbacks, one for each surface.
    SurfaceHolder.Callback mTopSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Rect size = holder.getSurfaceFrame();
            Log.d(TAG, "Top surfaceCreated " + size.width() + " " + size.height());
            mRenderThread.giveTopSurface(holder.getSurface(), size.width(), size.height());
        }
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }
    };

    SurfaceHolder.Callback mBottomSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Rect size = holder.getSurfaceFrame();
            int width = size.width() / 4;
            int height = size.height() / 4;
            Log.d(TAG, "Top surfaceCreated " + width + " " + height);
            holder.setFixedSize(width, height);
            mRenderThread.giveBottomSurface(holder.getSurface(), width, height);
        }
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }
    };

    /**
     * This class handles all OpenGL rendering.
     */
    private static class RenderThread extends Thread {
        // Lock that allows us to render frames. The UI thread can hold this
        // lock multiple times, once for each thing that should inhibit rendering
        // frames, such as application pauses, or missing surfaces.
        private ReentrantLock mRenderLock = new ReentrantLock();

        private EglCore mEglCore;

        private Surface mBottomSurface;
        private WindowSurface mBottomWindowSurface;
        private int mBottomSurfaceWidth;
        private int mBottomSurfaceHeight;
        private SimpleGlScene mBottomGlScene = new SimpleGlScene();

        private Surface mTopSurface;
        private WindowSurface mTopWindowSurface;
        private int mTopSurfaceWidth;
        private int mTopSurfaceHeight;
        private SimpleGlScene mTopGlScene = new SimpleGlScene();

        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         */
        public RenderThread() {
            // Don't render until we are unpaused and given both surfaces.
            pauseRender();
            takeBottomSurface();
            takeTopSurface();
        }

        public void pauseRender() {
            mRenderLock.lock();
        }

        public void takeTopSurface() {
            mRenderLock.lock();
            mTopSurface = null;
        }

        public void takeBottomSurface() {
            mRenderLock.lock();
            mBottomSurface = null;
        }

        public void resumeRender() {
            assert mRenderLock.isHeldByCurrentThread();
            mRenderLock.unlock();
        }

        public void giveTopSurface(Surface surface, int width, int height) {
            assert mRenderLock.isHeldByCurrentThread();
            mTopSurface = surface;
            mTopSurfaceWidth = width;
            mTopSurfaceHeight = height;
            mRenderLock.unlock();
        }

        public void giveBottomSurface(Surface surface, int width, int height) {
            assert mRenderLock.isHeldByCurrentThread();
            mBottomSurface = surface;
            mBottomSurfaceWidth = width;
            mBottomSurfaceHeight = height;
            mRenderLock.unlock();
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
            mEglCore = new EglCore(null, 0);

            do {
                mRenderLock.lock();

                if (mBottomWindowSurface == null) {
                    assert mTopWindowSurface == null;
                    assert mTopGlScene == null;
                    assert mBottomGlScene == null;
                    mTopWindowSurface = new WindowSurface(mEglCore, mTopSurface, false);
                    mTopWindowSurface.makeCurrent();
                    mTopGlScene.init(mTopSurfaceWidth, mTopSurfaceHeight);
                    mBottomWindowSurface = new WindowSurface(mEglCore, mBottomSurface, false);
                    mBottomWindowSurface.makeCurrent();
                    mBottomGlScene.init(mBottomSurfaceWidth, mBottomSurfaceHeight);
                }

                // If this were real app we should calculate this for real.
                float elapsed_time_seconds = 0.0167f;

                // Make the top surface current and draw into it.
                mTopWindowSurface.makeCurrent();
                GLES20.glViewport(0, 0, mTopSurfaceWidth, mTopSurfaceHeight);
                mTopGlScene.update(elapsed_time_seconds);
                mTopGlScene.draw();
                mTopWindowSurface.swapBuffers();

                // Now make the bottom surface current and draw into it.
                mBottomWindowSurface.makeCurrent();
                GLES20.glViewport(0, 0, mBottomSurfaceWidth, mBottomSurfaceHeight);
                mBottomGlScene.update(elapsed_time_seconds * 2);
                mBottomGlScene.draw();
                mBottomWindowSurface.swapBuffers();

                mRenderLock.unlock();
            } while(mBottomSurface != null);

            mTopGlScene.release();
            mBottomGlScene.release();
            release();

            mEglCore.makeNothingCurrent();
            mEglCore.release();
        }

        /**
         * Shuts everything down.
         */
        private void shutdown() {
            Log.d(TAG, "shutdown");
        }

        /**
         * Releases most of the GL resources we currently hold.
         * <p>
         * Does not release EglCore.
         */
        private void release() {
            GlUtil.checkGlError("releaseGl start");
            if (mTopWindowSurface != null) {
                mTopWindowSurface.release();
                mTopWindowSurface = null;
            }
            if (mBottomWindowSurface != null) {
                mBottomWindowSurface.release();
                mBottomWindowSurface = null;
            }

            GlUtil.checkGlError("releaseGl done");
        }
    }
}
