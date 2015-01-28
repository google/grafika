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
import android.graphics.Bitmap;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.OffscreenSurface;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An unscientific test of texture upload speed.
 */
public class TextureUploadActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    // Texture width/height.
    private static final int WIDTH = 512;       // must be power of 2
    private static final int HEIGHT = 512;
    private static final int ITERATIONS = 10;   // 10 iterations...
    private static final int TEX_PER_ITER = 8;  // ...uploading 8 textures per iteration

    private volatile boolean mIsCanceled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture_upload);
    }

    /**
     * Sets the text in the message field.
     */
    void setMessage(String msg) {
        TextView result = (TextView) findViewById(R.id.textureResult_text);
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

    public void clickRunTest(@SuppressWarnings("unused") View unused) {
        Resources res = getResources();
        String running = res.getString(R.string.state_running);
        setMessage(running);

        AlertDialog dialog = showProgressDialog();
        TextureUploadTask task = new TextureUploadTask(dialog, WIDTH, HEIGHT, ITERATIONS);
        mIsCanceled = false;
        task.execute();
    }


    /**
     * AsyncTask class that executes the test.
     */
    private class TextureUploadTask extends AsyncTask<Void, Integer, Long> {
        private static final int OUTPUT_WIDTH = 256;
        private static final int OUTPUT_HEIGHT = 256;
        private static final int RGBA_BPP = 4;      // RGBA bytes-per-pixel

        private int mWidth;
        private int mHeight;
        private int mIterations;
        private int mResultTextId;
        private AlertDialog mDialog;

        private ByteBuffer[] mPixelSource;

        private ProgressBar mProgressBar;

        /**
         * Prepare for the glTexImage2d test.
         */
        public TextureUploadTask(AlertDialog dialog, int width, int height, int iterations) {
            mDialog = dialog;
            mWidth = width;
            mHeight = height;
            mIterations = iterations;

            mProgressBar = (ProgressBar) dialog.findViewById(R.id.work_progress);
            mProgressBar.setMax(mIterations);
        }

        @Override
        protected Long doInBackground(Void... params) {
            long result = -1;

            // TODO: this should not use AsyncTask.  The AsyncTask worker thread is run at
            // a lower priority, making it unsuitable for benchmarks.  We can counteract
            // it in the current implementation, but this is not guaranteed to work in
            // future releases.
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            // This can take a second or two.
            createPixelSources();

            EglCore eglCore = null;
            OffscreenSurface surface = null;
            try {
                eglCore = new EglCore(null, 0);
                surface = new OffscreenSurface(eglCore, OUTPUT_WIDTH, OUTPUT_HEIGHT);
                result = runTextureTest(surface);
            } finally {
                if (surface != null) {
                    surface.release();
                }
                if (eglCore != null) {
                    eglCore.release();
                }
            }
            return result < 0 ? result : result / (mIterations * TEX_PER_ITER);
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
                setMessage(res.getString(R.string.did_not_complete));
            } else {
                setMessage((result / 1000) + res.getString(R.string.usec_per_iteration));
            }
        }

        /**
         * Create the bitmaps we create the textures from.
         */
        private void createPixelSources() {
            Log.d(TAG, "Creating pixel data...");
            mPixelSource = new ByteBuffer[TEX_PER_ITER];
            for (int i = 0; i < TEX_PER_ITER; i++) {
                mPixelSource[i] = ByteBuffer.allocateDirect(mWidth * mHeight * RGBA_BPP);
                if (i < 4) {
                    patternPixelSource(mPixelSource[i], i);
                } else {
                    randomPixelSource(mPixelSource[i], i);
                }
            }
            Log.d(TAG, "done");
        }

        /**
         * Fill the buffer with a regular pattern.  This should compress well.
         */
        private void patternPixelSource(ByteBuffer buf, int index) {
            byte[] array = buf.array();     // works in recent Android

            // generate 4 random RGBA colors
            byte[][] colors = new byte[4][4];
            for (int i = 0; i < 4; i++) {
                colors[i][0] = (byte) (256 * Math.random() - 128);
                colors[i][1] = (byte) (256 * Math.random() - 128);
                colors[i][2] = (byte) (256 * Math.random() - 128);
                colors[i][3] = (byte) 255; //(byte) (256 * Math.random() - 128);
            }

            final int repCount = (index % 4) + 1;
            int off = 0;
            for (int y = 0; y < mHeight; y++) {
                int colIndex = (y / repCount) % 4;
                for (int x = 0; x < mWidth; ) {
                    // repeat the color N times (if possible)
                    for (int rep = 0; rep < repCount && x < mWidth; rep++, x++) {
                        // copy the Nth color to the current pixel
                        array[off++] = colors[colIndex][0];
                        array[off++] = colors[colIndex][1];
                        array[off++] = colors[colIndex][2];
                        array[off++] = colors[colIndex][3];
                    }
                    colIndex = (colIndex + 1) % 4;
                }
            }

            if (false) saveTestBitmap(buf, index);
        }


        /**
         * Fill the buffer with random data.  This will not compress at all.
         */
        private void randomPixelSource(ByteBuffer buf, int index) {
            byte[] array = buf.array();     // works in recent Android

            int off = 0;
            for (int y = 0; y < mHeight; y++) {
                for (int x = 0; x < mWidth; x++) {
                    for (int b = 0; b < 4; b++) {
                        array[off++] = (byte) (256 * Math.random() - 128);
                    }
                }
            }

            if (false) saveTestBitmap(buf, index);
        }

        /**
         * Save generated data to a PNG file for debugging.
         */
        private void saveTestBitmap(ByteBuffer buf, int index) {
            // Save the generated bitmap to a PNG so we can see what it looks like.
            String filename = "/sdcard/test-" + index + ".png";
            Log.d(TAG, "Creating " + filename);
            BufferedOutputStream bos = null;
            try {
                bos = new BufferedOutputStream(new FileOutputStream(filename));
                Bitmap bmp = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(buf);
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
                bmp.recycle();
            } catch (IOException ioe) {
                Log.w(TAG, "Failed to create " + filename, ioe);
            } finally {
                if (bos != null) {
                    try {
                        bos.close();
                    } catch (IOException ioe) {
                        Log.w(TAG, "Failed to close " + filename, ioe);
                    }
                }
            }
        }

        /**
         * Attempt to measure the time required to upload a 512x512 texture.
         * <p>
         * The driver may employ various forms of cleverness, like not fully processing
         * a texture that never gets used.  So we want to render something with the texture.
         * To avoid including the texture rendering time in the result, we do a second set
         * of operations that just do rendering with a previously-uploaded texture, and
         * subtract that off the total.
         * <p>
         * This is all rather unscientific, but it should be good for a ball-park value.
         *
         * @return Total time spent on glTexImage2d().
         */
        private long runTextureTest(OffscreenSurface eglSurface) {
            long totalTime = 0;

            // Prep GL/EGL.  We use an identity projection matrix, which means the surface
            // coordinates span from -1 to 1 in both dimensions.
            eglSurface.makeCurrent();
            Texture2dProgram texProgram =
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D);
            Drawable2d rectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);
            Sprite2d rect = new Sprite2d(rectDrawable);

            for (int iteration = 0; iteration < mIterations; iteration++) {
                if (mIsCanceled) {
                    Log.d(TAG, "Canceled!");
                    totalTime = -2;
                    break;
                }
                publishProgress(iteration);

                GLES20.glClearColor(1f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                // Upload all 8 textures.  We're also including the time to generate an ID
                // and do the other housekeeping, but there's no reason not to include it.
                int[] textureHandles = new int[TEX_PER_ITER];
                long uploadStartNanos = System.nanoTime();
                for (int i = 0; i < TEX_PER_ITER; i++) {
                    textureHandles[i] = GlUtil.createImageTexture(mPixelSource[i], mWidth, mHeight,
                            GLES20.GL_RGBA);
                }
                long uploadEndNanos = System.nanoTime();

                // Render all textures, onto the top half of the output window.
                for (int i = 0; i < TEX_PER_ITER; i++) {
                    float rectWidth = 2f / TEX_PER_ITER;
                    float rectHeight = 1f;
                    rect.setScale(rectWidth, rectHeight);
                    rect.setPosition(2f * i / TEX_PER_ITER - 1 + rectWidth / 2, - rectHeight / 2);
                    rect.setTexture(textureHandles[i]);
                    rect.draw(texProgram, GlUtil.IDENTITY_MATRIX);
                }
                GLES20.glFinish();
                long drawEndNanos = System.nanoTime();

                // Render all textures, onto the bottom half of the output window.
                for (int i = 0; i < TEX_PER_ITER; i++) {
                    float rectWidth = 2f / TEX_PER_ITER;
                    float rectHeight = 1f;
                    rect.setScale(rectWidth, rectHeight);
                    rect.setPosition(2f * i / TEX_PER_ITER - 1 + rectWidth / 2, rectHeight / 2);
                    rect.setTexture(textureHandles[TEX_PER_ITER - i - 1]);
                    rect.draw(texProgram, GlUtil.IDENTITY_MATRIX);
                }
                GLES20.glFinish();
                long redrawEndNanos = System.nanoTime();

                long trimmedTime = (drawEndNanos - uploadStartNanos) -
                                   (redrawEndNanos - drawEndNanos);
                Log.d(TAG, "iter " + iteration +
                        " upload=" + (uploadEndNanos - uploadStartNanos) +
                        " draw=" + (drawEndNanos - uploadEndNanos) +
                        " redraw=" + (redrawEndNanos - drawEndNanos) +
                        " trimmed=" + trimmedTime);
                totalTime += trimmedTime;

                GLES20.glDeleteTextures(TEX_PER_ITER, textureHandles, 0);
                eglSurface.swapBuffers();
            }

            Log.d(TAG, "done");

            if (true) {
                // save the final frame into a file
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
