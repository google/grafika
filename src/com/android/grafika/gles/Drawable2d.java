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

import java.nio.FloatBuffer;

/**
 * Base class for stuff we like to draw.
 */
public class Drawable2d {
    private static final int SIZEOF_FLOAT = 4;

    /**
     * Simple equilateral triangle (1.0 per side).  Centered on (0,0).
     */
    private static final float TRIANGLE_COORDS[] = {
         0.0f,  0.577350269f,   // 0 top
        -0.5f, -0.288675135f,   // 1 bottom left
         0.5f, -0.288675135f    // 2 bottom right
    };
    private static final float TRIANGLE_TEX_COORDS[] = {
        0.5f, 0.0f,     // 0 top center
        0.0f, 1.0f,     // 1 bottom left
        1.0f, 1.0f,     // 2 bottom right
    };
    private static final FloatBuffer TRIANGLE_BUF =
            GlUtil.createFloatBuffer(TRIANGLE_COORDS);
    private static final FloatBuffer TRIANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(TRIANGLE_TEX_COORDS);

    /**
     * Simple square, specified as a triangle strip.  The square is centered on (0,0) and has
     * a size of 1x1.
     * <p>
     * Triangles are 0-1-2 and 2-1-3 (counter-clockwise winding).
     */
    private static final float RECTANGLE_COORDS[] = {
        -0.5f, -0.5f,   // 0 bottom left
         0.5f, -0.5f,   // 1 bottom right
        -0.5f,  0.5f,   // 2 top left
         0.5f,  0.5f,   // 3 top right
    };
    private static final float RECTANGLE_TEX_COORDS[] = {
        0.0f, 1.0f,     // 0 bottom left
        1.0f, 1.0f,     // 1 bottom right
        0.0f, 0.0f,     // 2 top left
        1.0f, 0.0f      // 3 top right
    };
    private static final FloatBuffer RECTANGLE_BUF =
            GlUtil.createFloatBuffer(RECTANGLE_COORDS);
    private static final FloatBuffer RECTANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(RECTANGLE_TEX_COORDS);

    /**
     * A "full" square, extending from -1 to +1 in both dimensions.  When the model/view/projection
     * matrix is identity, this will exactly cover the viewport.
     * <p>
     * The texture coordinates are Y-inverted relative to RECTANGLE.  (This seems to work out
     * right with external textures from SurfaceTexture.)
     */
    private static final float FULL_RECTANGLE_COORDS[] = {
        -1.0f, -1.0f,   // 0 bottom left
         1.0f, -1.0f,   // 1 bottom right
        -1.0f,  1.0f,   // 2 top left
         1.0f,  1.0f,   // 3 top right
    };
    private static final float FULL_RECTANGLE_TEX_COORDS[] = {
        0.0f, 0.0f,     // 0 bottom left
        1.0f, 0.0f,     // 1 bottom right
        0.0f, 1.0f,     // 2 top left
        1.0f, 1.0f      // 3 top right
    };
    private static final FloatBuffer FULL_RECTANGLE_BUF =
            GlUtil.createFloatBuffer(FULL_RECTANGLE_COORDS);
    private static final FloatBuffer FULL_RECTANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(FULL_RECTANGLE_TEX_COORDS);


    private FloatBuffer mVertexArray;
    private FloatBuffer mTexCoordArray;
    private int mVertexCount;
    private int mCoordsPerVertex;
    private int mVertexStride;
    private int mTexCoordStride;
    private Prefab mPrefab;

    /**
     * Enum values for constructor.
     */
    public enum Prefab {
        TRIANGLE, RECTANGLE, FULL_RECTANGLE
    }

    /**
     * Prepares a drawable from a "pre-fabricated" shape definition.
     * <p>
     * Does no EGL/GL operations, so this can be done at any time.
     */
    public Drawable2d(Prefab shape) {
        switch (shape) {
            case TRIANGLE:
                mVertexArray = TRIANGLE_BUF;
                mTexCoordArray = TRIANGLE_TEX_BUF;
                mCoordsPerVertex = 2;
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                mVertexCount = TRIANGLE_COORDS.length / mCoordsPerVertex;
                break;
            case RECTANGLE:
                mVertexArray = RECTANGLE_BUF;
                mTexCoordArray = RECTANGLE_TEX_BUF;
                mCoordsPerVertex = 2;
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                mVertexCount = RECTANGLE_COORDS.length / mCoordsPerVertex;
                break;
            case FULL_RECTANGLE:
                mVertexArray = FULL_RECTANGLE_BUF;
                mTexCoordArray = FULL_RECTANGLE_TEX_BUF;
                mCoordsPerVertex = 2;
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                mVertexCount = FULL_RECTANGLE_COORDS.length / mCoordsPerVertex;
                break;
            default:
                throw new RuntimeException("Unknown shape " + shape);
        }
        mTexCoordStride = 2 * SIZEOF_FLOAT;
        mPrefab = shape;
    }

    /**
     * Returns the array of vertices.
     * <p>
     * To avoid allocations, this returns internal state.  The caller must not modify it.
     */
    public FloatBuffer getVertexArray() {
        return mVertexArray;
    }

    /**
     * Returns the array of texture coordinates.
     * <p>
     * To avoid allocations, this returns internal state.  The caller must not modify it.
     */
    public FloatBuffer getTexCoordArray() {
        return mTexCoordArray;
    }

    /**
     * Returns the number of vertices stored in the vertex array.
     */
    public int getVertexCount() {
        return mVertexCount;
    }

    /**
     * Returns the width, in bytes, of the data for each vertex.
     */
    public int getVertexStride() {
        return mVertexStride;
    }

    /**
     * Returns the width, in bytes, of the data for each texture coordinate.
     */
    public int getTexCoordStride() {
        return mTexCoordStride;
    }

    /**
     * Returns the number of position coordinates per vertex.  This will be 2 or 3.
     */
    public int getCoordsPerVertex() {
        return mCoordsPerVertex;
    }

    @Override
    public String toString() {
        if (mPrefab != null) {
            return "[Drawable2d: " + mPrefab + "]";
        } else {
            return "[Drawable2d: ...]";
        }
    }
}
