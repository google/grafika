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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

/**
 * Opens a large number of MediaCodec encoders, just to see what happens.
 * <p>
 * We never explicitly release the instances, though they will get garbage collected
 * eventually.  The activity provides a "GC" button (so you can force the GC to happen)
 * and a "Halt" button (which kills the app so you can see if mediaserver is cleaning up).
 */
public class CodecOpenActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private static final int MAX_OPEN = 256;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_codec_open);
    }

    /**
     * onClick handler for "start" button.
     *
     * We create as many codecs as we can and return without releasing them.  The codecs
     * will remain in use until the next GC.
     */
    public void clickStart(@SuppressWarnings("unused") View unused) {
        final String MIME_TYPE = "video/avc";
        final int WIDTH = 320;
        final int HEIGHT = 240;
        final int BIT_RATE = 1000000;
        final int FRAME_RATE = 15;
        final int IFRAME_INTERVAL = 1;
        final boolean START_CODEC = true;

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        Log.d(TAG, "format: " + format);

        MediaCodec[] codecs = new MediaCodec[MAX_OPEN];
        int i;
        for (i = 0; i < MAX_OPEN; i++) {
            try {
                codecs[i] = MediaCodec.createEncoderByType(MIME_TYPE);
                codecs[i].configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                if (START_CODEC) {
                    codecs[i].createInputSurface();
                    codecs[i].start();
                }
            } catch (Exception ex) {
                Log.i(TAG, "Failed on creation of codec #" + i, ex);
                break;
            }
        }

        showCountDialog(i);
    }

    /**
     * Puts up a dialog showing how many codecs we created.
     */
    private void showCountDialog(int count) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.codecOpenCountTitle);
        String msg = getString(R.string.codecOpenCountMsg, count);
        builder.setMessage(msg);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * onClick handler for "GC" button.
     * <p>
     * Initiates manual garbage collection.  Some of the native stuff might not get cleaned up
     * until finalizers run, so we request those too.
     */
    public void clickGc(@SuppressWarnings("unused") View unused) {
        Log.i(TAG, "Collecting garbage");
        System.gc();
        System.runFinalization();
        System.gc();
    }

    /**
     * onClick handler for "halt" button.
     * <p>
     * This kills the process, which will be immediately restarted.
     */
    public void clickHalt(@SuppressWarnings("unused") View unused) {
        Log.w(TAG, "HALTING VM");
        Runtime.getRuntime().halt(1);
    }
}
