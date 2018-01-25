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

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.IOException;

/**
 * More or less straight out of TextureView's doc.
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 */
public class LiveCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = MainActivity.TAG;

    private Camera mCamera;
    private SurfaceTexture mSurfaceTexture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextureView textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);

        setContentView(textureView);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {

        mSurfaceTexture = surface;
        if (!PermissionHelper.hasCameraPermission(this)) {
            PermissionHelper.requestCameraPermission(this, false);
        } else {
            startPreview();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mCamera.stopPreview();
        mCamera.release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Invoked every time there's a new Camera preview frame
        //Log.d(TAG, "updated, ts=" + surface.getTimestamp());
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
                PermissionHelper.launchPermissionSettings(this);
            finish();
        } else {
            startPreview();
        }
    }

    private void startPreview() {
        mCamera = Camera.open();
        if (mCamera == null) {
            // Seeing this on Nexus 7 2012 -- I guess it wants a rear-facing camera, but
            // there isn't one.  TODO: fix
            throw new RuntimeException("Default camera not available");
        }

        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
            Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

            if(display.getRotation() == Surface.ROTATION_0) {
                mCamera.setDisplayOrientation(90);
            }
            if(display.getRotation() == Surface.ROTATION_270) {
                mCamera.setDisplayOrientation(180);
            }
            mCamera.startPreview();
        } catch (IOException ioe) {
            // Something bad happened
            Log.e(TAG,"Exception starting preview", ioe);
        }
    }
}
