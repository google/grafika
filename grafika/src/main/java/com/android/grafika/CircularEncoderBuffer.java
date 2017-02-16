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
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Holds encoded video data in a circular buffer.
 * <p>
 * This is actually a pair of circular buffers, one for the raw data and one for the meta-data
 * (flags and PTS).
 * <p>
 * Not thread-safe.
 */
public class CircularEncoderBuffer {
    private static final String TAG = MainActivity.TAG;
    private static final boolean EXTRA_DEBUG = true;
    private static final boolean VERBOSE = false;

    // Raw data (e.g. AVC NAL units) held here.
    //
    // The MediaMuxer writeSampleData() function takes a ByteBuffer.  If it's a "direct"
    // ByteBuffer it'll access the data directly, if it's a regular ByteBuffer it'll use
    // JNI functions to access the backing byte[] (which, in the current VM, is done without
    // copying the data).
    //
    // It's much more convenient to work with a byte[], so we just wrap it with a ByteBuffer
    // as needed.  This is a bit awkward when we hit the edge of the buffer, but for that
    // we can just do an allocation and data copy (we know it happens at most once per file
    // save operation).
    private ByteBuffer mDataBufferWrapper;
    private byte[] mDataBuffer;

    // Meta-data held here.  We're using a collection of arrays, rather than an array of
    // objects with multiple fields, to minimize allocations and heap footprint.
    private int[] mPacketFlags;
    private long[] mPacketPtsUsec;
    private int[] mPacketStart;
    private int[] mPacketLength;

    // Data is added at head and removed from tail.  Head points to an empty node, so if
    // head==tail the list is empty.
    private int mMetaHead;
    private int mMetaTail;

    /**
     * Allocates the circular buffers we use for encoded data and meta-data.
     */
    public CircularEncoderBuffer(int bitRate, int frameRate, int desiredSpanSec) {
        // For the encoded data, we assume the encoded bit rate is close to what we request.
        //
        // There would be a minor performance advantage to using a power of two here, because
        // not all ARM CPUs support integer modulus.
        int dataBufferSize = bitRate * desiredSpanSec / 8;
        mDataBuffer = new byte[dataBufferSize];
        mDataBufferWrapper = ByteBuffer.wrap(mDataBuffer);

        // Meta-data is smaller than encoded data for non-trivial frames, so we over-allocate
        // a bit.  This should ensure that we drop packets because we ran out of (expensive)
        // data storage rather than (inexpensive) metadata storage.
        int metaBufferCount = frameRate * desiredSpanSec * 2;
        mPacketFlags = new int[metaBufferCount];
        mPacketPtsUsec = new long[metaBufferCount];
        mPacketStart = new int[metaBufferCount];
        mPacketLength = new int[metaBufferCount];

        if (VERBOSE) {
            Log.d(TAG, "CBE: bitRate=" + bitRate + " frameRate=" + frameRate +
                    " desiredSpan=" + desiredSpanSec + ": dataBufferSize=" + dataBufferSize +
                " metaBufferCount=" + metaBufferCount);
        }
    }

    /**
     * Computes the amount of time spanned by the buffered data, based on the presentation
     * time stamps.
     */
    public long computeTimeSpanUsec() {
        final int metaLen = mPacketStart.length;

        if (mMetaHead == mMetaTail) {
            // empty list
            return 0;
        }

        // head points to the next available node, so grab the previous one
        int beforeHead = (mMetaHead + metaLen - 1) % metaLen;
        return mPacketPtsUsec[beforeHead] - mPacketPtsUsec[mMetaTail];
    }

    /**
     * Adds a new encoded data packet to the buffer.
     *
     * @param buf The data.  Set position() to the start offset and limit() to position+size.
     *     The position and limit may be altered by this method.
     * @param size Number of bytes in the packet.
     * @param flags MediaCodec.BufferInfo flags.
     * @param ptsUsec Presentation time stamp, in microseconds.
     */
    public void add(ByteBuffer buf, int flags, long ptsUsec) {
        int size = buf.limit() - buf.position();
        if (VERBOSE) {
            Log.d(TAG, "add size=" + size + " flags=0x" + Integer.toHexString(flags) +
                    " pts=" + ptsUsec);
        }
        while (!canAdd(size)) {
            removeTail();
        }

        final int dataLen = mDataBuffer.length;
        final int metaLen = mPacketStart.length;
        int packetStart = getHeadStart();
        mPacketFlags[mMetaHead] = flags;
        mPacketPtsUsec[mMetaHead] = ptsUsec;
        mPacketStart[mMetaHead] = packetStart;
        mPacketLength[mMetaHead] = size;

        // Copy the data in.  Take care if it gets split in half.
        if (packetStart + size < dataLen) {
            // one chunk
            buf.get(mDataBuffer, packetStart, size);
        } else {
            // two chunks
            int firstSize = dataLen - packetStart;
            if (VERBOSE) { Log.v(TAG, "split, firstsize=" + firstSize + " size=" + size); }
            buf.get(mDataBuffer, packetStart, firstSize);
            buf.get(mDataBuffer, 0, size - firstSize);
        }

        mMetaHead = (mMetaHead + 1) % metaLen;

        if (EXTRA_DEBUG) {
            // The head packet is the next-available spot.
            mPacketFlags[mMetaHead] = 0x77aaccff;
            mPacketPtsUsec[mMetaHead] = -1000000000L;
            mPacketStart[mMetaHead] = -100000;
            mPacketLength[mMetaHead] = Integer.MAX_VALUE;
        }
    }

    /**
     * Returns the index of the oldest sync frame.  Valid until the next add().
     * <p>
     * When sending output to a MediaMuxer, start here.
     */
    public int getFirstIndex() {
        final int metaLen = mPacketStart.length;

        int index = mMetaTail;
        while (index != mMetaHead) {
            if ((mPacketFlags[index] & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0) {
                break;
            }
            index = (index + 1) % metaLen;
        }

        if (index == mMetaHead) {
            Log.w(TAG, "HEY: could not find sync frame in buffer");
            index = -1;
        }
        return index;
    }

    /**
     * Returns the index of the next packet, or -1 if we've reached the end.
     */
    public int getNextIndex(int index) {
        final int metaLen = mPacketStart.length;
        int next = (index + 1) % metaLen;
        if (next == mMetaHead) {
            next = -1;
        }
        return next;
    }

    /**
     * Returns a reference to a "direct" ByteBuffer with the data, and fills in the
     * BufferInfo.
     * <p>
     * The caller must not modify the contents of the returned ByteBuffer.  Altering
     * the position and limit is allowed.
     */
    public ByteBuffer getChunk(int index, MediaCodec.BufferInfo info) {
        final int dataLen = mDataBuffer.length;
        int packetStart = mPacketStart[index];
        int length = mPacketLength[index];

        info.flags = mPacketFlags[index];
        info.offset = packetStart;
        info.presentationTimeUs = mPacketPtsUsec[index];
        info.size = length;

        if (packetStart + length <= dataLen) {
            // one chunk; return full buffer to avoid copying data
            return mDataBufferWrapper;
        } else {
            // two chunks
            ByteBuffer tempBuf = ByteBuffer.allocateDirect(length);
            int firstSize = dataLen - packetStart;
            tempBuf.put(mDataBuffer, mPacketStart[index], firstSize);
            tempBuf.put(mDataBuffer, 0, length - firstSize);
            info.offset = 0;
            return tempBuf;
        }
    }

    /**
     * Computes the data buffer offset for the next place to store data.
     * <p>
     * Equal to the start of the previous packet's data plus the previous packet's length.
     */
    private int getHeadStart() {
        if (mMetaHead == mMetaTail) {
            // list is empty
            return 0;
        }

        final int dataLen = mDataBuffer.length;
        final int metaLen = mPacketStart.length;

        int beforeHead = (mMetaHead + metaLen - 1) % metaLen;
        return (mPacketStart[beforeHead] + mPacketLength[beforeHead] + 1) % dataLen;
    }

    /**
     * Determines whether this is enough space to fit "size" bytes in the data buffer, and
     * one more packet in the meta-data buffer.
     *
     * @return True if there is enough space to add without removing anything.
     */
    private boolean canAdd(int size) {
        final int dataLen = mDataBuffer.length;
        final int metaLen = mPacketStart.length;

        if (size > dataLen) {
            throw new RuntimeException("Enormous packet: " + size + " vs. buffer " +
                    dataLen);
        }
        if (mMetaHead == mMetaTail) {
            // empty list
            return true;
        }

        // Make sure we can advance head without stepping on the tail.
        int nextHead = (mMetaHead + 1) % metaLen;
        if (nextHead == mMetaTail) {
            if (VERBOSE) {
                Log.v(TAG, "ran out of metadata (head=" + mMetaHead + " tail=" + mMetaTail +")");
            }
            return false;
        }

        // Need the byte offset of the start of the "tail" packet, and the byte offset where
        // "head" will store its data.
        int headStart = getHeadStart();
        int tailStart = mPacketStart[mMetaTail];
        int freeSpace = (tailStart + dataLen - headStart) % dataLen;
        if (size > freeSpace) {
            if (VERBOSE) {
                Log.v(TAG, "ran out of data (tailStart=" + tailStart + " headStart=" + headStart +
                    " req=" + size + " free=" + freeSpace + ")");
            }
            return false;
        }

        if (VERBOSE) {
            Log.v(TAG, "OK: size=" + size + " free=" + freeSpace + " metaFree=" +
                    ((mMetaTail + metaLen - mMetaHead) % metaLen - 1));
        }

        return true;
    }

    /**
     * Removes the tail packet.
     */
    private void removeTail() {
        if (mMetaHead == mMetaTail) {
            throw new RuntimeException("Can't removeTail() in empty buffer");
        }
        final int metaLen = mPacketStart.length;
        mMetaTail = (mMetaTail + 1) % metaLen;
    }
}
