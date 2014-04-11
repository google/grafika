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

import android.opengl.EGL14;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.app.Activity;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.OffscreenSurface;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

/**
 * Simple activity that gathers and displays information from the GLES driver.
 */
public class GlesInfoActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private String mGlInfo;
    private File mOutputFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gles_info);

        mOutputFile = new File(getFilesDir(), "gles-info.txt");
        TextView tv = (TextView) findViewById(R.id.glesInfoFile_text);
        tv.setText(mOutputFile.toString());

        mGlInfo = gatherGlInfo();

        tv = (TextView) findViewById(R.id.glesInfo_text);
        tv.setText(mGlInfo);
    }

    /**
     * onClick handler for "save" button.
     */
    public void clickSave(@SuppressWarnings("unused") View unused) {
        try {
            FileWriter writer = new FileWriter(mOutputFile);
            writer.write(mGlInfo);
            writer.close();
            Log.d(TAG, "Output written to '" + mOutputFile + "'");
        } catch (IOException ioe) {
            Log.e(TAG, "Failed writing file", ioe);
            // TODO: notify the user, not just logcat
        }
    }

    /**
     * Queries EGL/GL for information, then formats it all into one giant string.
     */
    private String gatherGlInfo() {
        // We need a GL context to examine, which means we need an EGL surface.  Create a 1x1
        // pbuffer.
        EglCore eglCore = new EglCore(null, EglCore.FLAG_TRY_GLES3);
        OffscreenSurface surface = new OffscreenSurface(eglCore, 1, 1);
        surface.makeCurrent();

        StringBuilder sb = new StringBuilder();
        sb.append("===== GL Information =====");
        sb.append("\nvendor    : ");
        sb.append(GLES20.glGetString(GLES20.GL_VENDOR));
        sb.append("\nversion   : ");
        sb.append(GLES20.glGetString(GLES20.GL_VERSION));
        sb.append("\nrenderer  : ");
        sb.append(GLES20.glGetString(GLES20.GL_RENDERER));
        sb.append("\nextensions:\n");
        sb.append(formatExtensions(GLES20.glGetString(GLES20.GL_EXTENSIONS)));

        sb.append("\n===== EGL Information =====");
        sb.append("\nvendor    : ");
        sb.append(eglCore.queryString(EGL14.EGL_VENDOR));
        sb.append("\nversion   : ");
        sb.append(eglCore.queryString(EGL14.EGL_VERSION));
        sb.append("\nclient API: ");
        sb.append(eglCore.queryString(EGL14.EGL_CLIENT_APIS));
        sb.append("\nextensions:\n");
        sb.append(formatExtensions(eglCore.queryString(EGL14.EGL_EXTENSIONS)));

        surface.release();
        eglCore.release();

        sb.append("\n===== System Information =====");
        sb.append("\nmfgr      : ");
        sb.append(Build.MANUFACTURER);
        sb.append("\nbrand     : ");
        sb.append(Build.BRAND);
        sb.append("\nmodel     : ");
        sb.append(Build.MODEL);
        sb.append("\nrelease   : ");
        sb.append(Build.VERSION.RELEASE);
        sb.append("\nbuild     : ");
        sb.append(Build.DISPLAY);
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Formats the extensions string, which is a space-separated list, into a series of indented
     * values followed by newlines.  The list is sorted.
     */
    private String formatExtensions(String ext) {
        String[] values = ext.split(" ");
        Arrays.sort(values);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append("  ");
            sb.append(values[i]);
            sb.append("\n");
        }
        return sb.toString();
    }
}
