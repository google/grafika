/*
 * Copyright 2013 Google Inc. All rights reserved.
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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.OffscreenSurface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/**
 * Basic glReadPixels() speed test.
 */
public class ReadPixelsActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int ITERATIONS = 100;

    private volatile boolean mIsCanceled;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_read_pixels);
    }

    /**
     * Sets the text in the message field.
     */
    void setMessage(int id, String msg) {
        TextView result = (TextView) findViewById(id);
        result.setText(msg);
    }

    /**
     * Creates and displays the progress dialog.
     *
     * @return the dialog
     */
    private AlertDialog showProgressDialog() {
        // Put up the progress dialog.
        AlertDialog.Builder builder = WorkDialog.create(this, R.string.running_test);
        builder.setCancelable(false);   // only by button
        builder.setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mIsCanceled = true;
                // let the async task handle dismiss the dialog
            }
        });
        return builder.show();

    }

    /**
     * onClick handler for gfx test button.
     */
    public void clickRunGfxTest(@SuppressWarnings("unused") View unused) {
        Resources res = getResources();
        String running = res.getString(R.string.state_running);
        setMessage(R.id.gfxResult_text, running);

        AlertDialog dialog = showProgressDialog();
        ReadPixelsTask task = new ReadPixelsTask(dialog, R.id.gfxResult_text,
                WIDTH, HEIGHT, ITERATIONS);
        mIsCanceled = false;
        task.execute();
    }

    /**
     * AsyncTask class that executes the test.
     */
    private class ReadPixelsTask extends AsyncTask<Void, Integer, Long> {
        private int mWidth;
        private int mHeight;
        private int mIterations;
        private int mResultTextId;
        private AlertDialog mDialog;

        private ProgressBar mProgressBar;

        /**
         * Prepare for the glReadPixels test.
         */
        public ReadPixelsTask(AlertDialog dialog, int resultTextId,
                int width, int height, int iterations) {
            mDialog = dialog;
            mResultTextId = resultTextId;
            mWidth = width;
            mHeight = height;
            mIterations = iterations;

            mProgressBar = (ProgressBar) dialog.findViewById(R.id.work_progress);
            mProgressBar.setMax(mIterations);
        }

        @Override
        protected Long doInBackground(Void... params) {
            long result = -1;
            EglCore eglCore = null;
            OffscreenSurface surface = null;

            // TODO: this should not use AsyncTask.  The AsyncTask worker thread is run at
            // a lower priority, making it unsuitable for benchmarks.  We can counteract
            // it in the current implementation, but this is not guaranteed to work in
            // future releases.
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            try {
                eglCore = new EglCore(null, 0);
                surface = new OffscreenSurface(eglCore, mWidth, mHeight);
                Log.d(TAG, "Buffer size " + mWidth + "x" + mHeight);
                result = runGfxTest(surface);
            } finally {
                if (surface != null) {
                    surface.release();
                }
                if (eglCore != null) {
                    eglCore.release();
                }
            }
            return result < 0 ? result : result / mIterations;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            mProgressBar.setProgress(progress[0]);
        }

        @Override
        protected void onPostExecute(Long result) {
            Log.d(TAG, "onPostExecute result=" + result);
            mDialog.dismiss();
            mDialog = null;

            Resources res = getResources();
            if (result < 0) {
                setMessage(mResultTextId, res.getString(R.string.did_not_complete));
            } else {
                setMessage(mResultTextId, (result / 1000) +
                        res.getString(R.string.usec_per_iteration));
            }
        }

        /**
         * Does a simple bit of rendering and then reads the pixels back.
         *
         * @return total time spent on glReadPixels()
         */
        private long runGfxTest(OffscreenSurface eglSurface) {
            long totalTime = 0;

            eglSurface.makeCurrent();

            ByteBuffer pixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4);
            pixelBuf.order(ByteOrder.LITTLE_ENDIAN);

            Log.d(TAG, "Running...");
            float colorMult = 1.0f / mIterations;
            for (int i = 0; i < mIterations; i++) {
                if (mIsCanceled) {
                    Log.d(TAG, "Canceled!");
                    totalTime = -2;
                    break;
                }
                if ((i % (mIterations / 8)) == 0) {
                    publishProgress(i);
                }

                // Clear the screen to a solid color, then add a rectangle.  Change the color
                // each time.
                float r = i * colorMult;
                float g = 1.0f - r;
                float b = (r + g) / 2.0f;
                GLES20.glClearColor(r, g, b, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                GLES20.glScissor(mWidth / 4, mHeight / 4, mWidth / 2, mHeight / 2);
                GLES20.glClearColor(b, g, r, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

                // Try to ensure that rendering has finished.
                GLES20.glFinish();
                GLES20.glReadPixels(0, 0, 1, 1,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf);

                // Time individual extraction.  Ideally we'd be timing a bunch of these calls
                // and measuring the aggregate time, but we want the isolated time, and if we
                // just read the same buffer repeatedly we might get some sort of cache effect.
                long startWhen = System.nanoTime();
                GLES20.glReadPixels(0, 0, mWidth, mHeight,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf);
                totalTime += System.nanoTime() - startWhen;
            }
            Log.d(TAG, "done");

            if (true) {
                // save the last one off into a file
                long startWhen = System.nanoTime();
                try {
                    eglSurface.saveFrame(new File(Environment.getExternalStorageDirectory(),
                            "test.png"));
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
                Log.d(TAG, "Saved frame in " + ((System.nanoTime() - startWhen) / 1000000) + "ms");
            }

            return totalTime;
        }
    }
}
