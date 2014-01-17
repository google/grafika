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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.app.Activity;
import android.util.Log;
import android.view.Choreographer;

/**
 * Trivial activity used to test Choreographer behavior.
 */
public class ChorTestActivity extends Activity {
    private static final String TAG = "chor-test";

    ChorRenderThread mRenderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chor_test);

        mRenderThread = new ChorRenderThread();
        mRenderThread.start();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();

        // if we get here too quickly, the handler might still be null; not dealing with that
        Handler handler = mRenderThread.getHandler();
        handler.sendEmptyMessage(0);
        mRenderThread = null;
    }

    private static class ChorRenderThread extends Thread implements Choreographer.FrameCallback {
        private volatile Handler mHandler;

        @Override
        public void run() {
            setName("ChorRenderThread");

            Looper.prepare();

            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    Log.d(TAG, "got message, quitting");
                    Looper.myLooper().quit();
                }
            };
            Choreographer.getInstance().postFrameCallback(this);

            Looper.loop();
            Log.d(TAG, "looper quit");
            Choreographer.getInstance().removeFrameCallback(this);
        }

        public Handler getHandler() {
            return mHandler;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            Log.d(TAG, "doFrame " + frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
