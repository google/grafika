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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Encode a movie from frames rendered from an external texture image.
 * <p>
 * The object wraps an encoder running partly on two different threads.  An external thread
 * is sending data to the encoder's input surface, and we (the encoder thread) are pulling
 * the encoded data out and feeding it into a MediaMuxer.
 * <p>
 * We could block forever waiting for the encoder, but because of the thread decomposition
 * that turns out to be a little awkward (we want to call signalEndOfInputStream() from the
 * encoder thread to avoid thread-safety issues, but we can't do that if we're blocked on
 * the encoder).  If we don't pull from the encoder often enough, the producer side can back up.
 * <p>
 * The solution is to have the producer trigger drainEncoder() on every frame, before it
 * submits the new frame.  drainEncoder() might run before or after the frame is submitted,
 * but it doesn't matter -- either it runs early and prevents blockage, or it runs late
 * and un-blocks the encoder.
 * <p>
 * TODO: reconcile this with TextureMovieEncoder.
 */
public class TextureMovieEncoder2 implements Runnable {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int MSG_STOP_RECORDING = 1;
    private static final int MSG_FRAME_AVAILABLE = 2;

    // ----- accessed exclusively by encoder thread -----
    private VideoEncoderCore mVideoEncoder;

    // ----- accessed by multiple threads -----
    private volatile EncoderHandler mHandler;

    private Object mReadyFence = new Object();      // guards ready/running
    private boolean mReady;
    private boolean mRunning;


    /**
     * Tells the video recorder to start recording.  (Call from non-encoder thread.)
     * <p>
     * Creates a new thread, which will own the provided VideoEncoderCore.  When the
     * thread exits, the VideoEncoderCore will be released.
     * <p>
     * Returns after the recorder thread has started and is ready to accept Messages.
     */
    public TextureMovieEncoder2(VideoEncoderCore encoderCore) {
        Log.d(TAG, "Encoder: startRecording()");

        mVideoEncoder = encoderCore;

        synchronized (mReadyFence) {
            if (mRunning) {
                Log.w(TAG, "Encoder thread already running");
                return;
            }
            mRunning = true;
            new Thread(this, "TextureMovieEncoder").start();
            while (!mReady) {
                try {
                    mReadyFence.wait();
                } catch (InterruptedException ie) {
                    // ignore
                }
            }
        }
    }

    /**
     * Tells the video recorder to stop recording.  (Call from non-encoder thread.)
     * <p>
     * Returns immediately; the encoder/muxer may not yet be finished creating the movie.
     * <p>
     * TODO: have the encoder thread invoke a callback on the UI thread just before it shuts down
     * so we can provide reasonable status UI (and let the caller know that movie encoding
     * has completed).
     */
    public void stopRecording() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING));
        // We don't know when these will actually finish (or even start).  We don't want to
        // delay the UI thread though, so we return immediately.
    }

    /**
     * Returns true if recording has been started.
     */
    public boolean isRecording() {
        synchronized (mReadyFence) {
            return mRunning;
        }
    }

    /**
     * Tells the video recorder that a new frame is arriving soon.  (Call from non-encoder thread.)
     * <p>
     * This function sends a message and returns immediately.  This is fine -- the purpose is
     * to wake the encoder thread up to do work so the producer side doesn't block.
     */
    public void frameAvailableSoon() {
        synchronized (mReadyFence) {
            if (!mReady) {
                return;
            }
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_FRAME_AVAILABLE));
    }

    /**
     * Encoder thread entry point.  Establishes Looper/Handler and waits for messages.
     * <p>
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        // Establish a Looper for this thread, and define a Handler for it.
        Looper.prepare();
        synchronized (mReadyFence) {
            mHandler = new EncoderHandler(this);
            mReady = true;
            mReadyFence.notify();
        }
        Looper.loop();

        Log.d(TAG, "Encoder thread exiting");
        synchronized (mReadyFence) {
            mReady = mRunning = false;
            mHandler = null;
        }
    }


    /**
     * Handles encoder state change requests.  The handler is created on the encoder thread.
     */
    private static class EncoderHandler extends Handler {
        private WeakReference<TextureMovieEncoder2> mWeakEncoder;

        public EncoderHandler(TextureMovieEncoder2 encoder) {
            mWeakEncoder = new WeakReference<TextureMovieEncoder2>(encoder);
        }

        @Override  // runs on encoder thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Object obj = inputMessage.obj;

            TextureMovieEncoder2 encoder = mWeakEncoder.get();
            if (encoder == null) {
                Log.w(TAG, "EncoderHandler.handleMessage: encoder is null");
                return;
            }

            switch (what) {
                case MSG_STOP_RECORDING:
                    encoder.handleStopRecording();
                    Looper.myLooper().quit();
                    break;
                case MSG_FRAME_AVAILABLE:
                    encoder.handleFrameAvailable();
                    break;
                default:
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }

    /**
     * Handles notification of an available frame.
     */
    private void handleFrameAvailable() {
        if (VERBOSE) Log.d(TAG, "handleFrameAvailable");
        mVideoEncoder.drainEncoder(false);
    }

    /**
     * Handles a request to stop encoding.
     */
    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording");
        mVideoEncoder.drainEncoder(true);
        mVideoEncoder.release();
    }
}
