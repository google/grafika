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
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;

/**
 * Show color bars.
 */
public class ColorBarActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = MainActivity.TAG;

    private SurfaceView mSurfaceView;

    private static final String[] COLOR_NAMES = {
        "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_color_bar);

        mSurfaceView = (SurfaceView) findViewById(R.id.colorBarSurfaceView);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // ignore
        Log.v(TAG, "surfaceCreated holder=" + holder);
    }

    /**
     * SurfaceHolder.Callback method
     * <p>
     * Draws when the surface changes.  Since nothing else is touching the surface, and
     * we're not animating, we just draw here and ignore it.
     */
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.v(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);
        Surface surface = holder.getSurface();
        drawColorBars(surface);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // ignore
        Log.v(TAG, "Surface destroyed holder=" + holder);
    }

    /**
     * Draw color bars with text labels.
     */
    private void drawColorBars(Surface surface) {
        Canvas canvas = surface.lockCanvas(null);
        try {
            // TODO: if the device is in portrait, draw the color bars horizontally.  Right
            // now this only looks good in landscape.
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            int least = Math.min(width, height);

            Log.d(TAG, "Drawing color bars at " + width + "x" + height);

            Paint textPaint = new Paint();
            Typeface typeface = Typeface.defaultFromStyle(Typeface.NORMAL);
            textPaint.setTypeface(typeface);
            textPaint.setTextSize(least / 20);
            textPaint.setAntiAlias(true);

            Paint rectPaint = new Paint();
            for (int i = 0; i < 8; i++) {
                int color = 0xff000000;
                if ((i & 0x01) != 0) {
                    color |= 0x00ff0000;
                }
                if ((i & 0x02) != 0) {
                    color |= 0x0000ff00;
                }
                if ((i & 0x04) != 0) {
                    color |= 0x000000ff;
                }
                rectPaint.setColor(color);

                float sliceWidth = width / 8;
                canvas.drawRect(sliceWidth * i, 0, sliceWidth * (i+1), height, rectPaint);
            }
            rectPaint.setColor(0x80808080);     // ARGB 50/50 grey (non-premul)
            float sliceHeight = height / 8;
            int posn = 6;
            canvas.drawRect(0, sliceHeight * posn, width, sliceHeight * (posn+1), rectPaint);

            // Draw the labels last so they're on top of everything.
            for (int i = 0; i < 8; i++) {
                drawOutlineText(canvas, textPaint, COLOR_NAMES[i],
                        (width / 8) * i + 4, (height / 8) * ((i & 1) + 1));
            }
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }

    /**
     * Draw white text surrounded by a 1-pixel black outline.
     */
    private static void drawOutlineText(Canvas canvas, Paint textPaint, String str,
            float x, float y) {
        // Is there a better way to do this?
        textPaint.setColor(0xff000000);
        canvas.drawText(str, x-1,    y,      textPaint);
        canvas.drawText(str, x+1,    y,      textPaint);
        canvas.drawText(str, x,      y-1,    textPaint);
        canvas.drawText(str, x,      y+1,    textPaint);
        canvas.drawText(str, x-0.7f, y-0.7f, textPaint);
        canvas.drawText(str, x+0.7f, y-0.7f, textPaint);
        canvas.drawText(str, x-0.7f, y+0.7f, textPaint);
        canvas.drawText(str, x+0.7f, y+0.7f, textPaint);
        textPaint.setColor(0xffffffff);
        canvas.drawText(str, x, y, textPaint);
    }
}
