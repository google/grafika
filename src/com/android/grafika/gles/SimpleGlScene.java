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

package com.android.grafika.gles;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import com.android.grafika.MainActivity;
import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.FlatShadedProgram;
import com.android.grafika.gles.GeneratedTexture;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;

/**
 * This class just renders a simple animated GL scene.
 */
public class SimpleGlScene {
    private static final String TAG = MainActivity.TAG;

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

    private int mWidth;
    private int mHeight;

    public SimpleGlScene() {
        mIdentityMatrix = new float[16];
        Matrix.setIdentityM(mIdentityMatrix, 0);

        mTri = new Sprite2d(mTriDrawable);
        mRect = new Sprite2d(mRectDrawable);
        mEdges = new Sprite2d[4];
        for (int i = 0; i < mEdges.length; i++) {
            mEdges[i] = new Sprite2d(mRectDrawable);
        }
    }

    public void init(int width, int height) {
        mWidth = width;
        mHeight = height;

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

    public void release() {
        if (mFlatProgram != null) {
            mFlatProgram.release();
            mFlatProgram = null;
        }
        if (mTexProgram != null) {
            mTexProgram.release();
            mTexProgram = null;
        }
    }

    public void update(float elapsedSeconds) {
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

    public void draw() {
        GlUtil.checkGlError("draw start");

        // Clear to a non-black color to make the content easily differentiable from
        // the pillar-/letter-boxing.
        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 0.0f);
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
