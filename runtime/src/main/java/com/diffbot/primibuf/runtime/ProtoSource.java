/*-
 * #%L
 * quickbuf-runtime
 * %%
 * Copyright (C) 2019 HEBI Robotics
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

// Protocol Buffers - Google's data interchange format
// Copyright 2013 Google Inc.  All rights reserved.
// https://developers.google.com/protocol-buffers/
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//     * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.diffbot.primibuf.runtime;

import com.google.protobuf.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.diffbot.primibuf.runtime.InvalidProtocolBufferException.*;
import static com.diffbot.primibuf.runtime.WireFormat.*;
import static com.google.protobuf.CodedInputStream.decodeZigZag32;
import static com.google.protobuf.CodedInputStream.decodeZigZag64;

/**
 * NOTE: the code was modified from {@link CodedInputStream.ArrayDecoder} with the following modifications:
 *
 * 1. support {@link #getPosition} and {@link #rewindToPosition}
 * 2. remove reading bytes to ByteString
 * 3. no extends from CodedInputStream, fix compilation due to private/package-level fields/methods
 */
public class ProtoSource {

    private static final int DEFAULT_RECURSION_LIMIT = 100;
    /** Visible for subclasses. See setRecursionLimit() */
    int recursionDepth;
    int recursionLimit = DEFAULT_RECURSION_LIMIT;


    private final byte[] buffer;
    private int limit;
    private int bufferSizeAfterLimit;
    private int pos;
    private int startPos;
    private int lastTag;

    /** The absolute position of the end of the current message. */
    private int currentLimit = Integer.MAX_VALUE;

    public ProtoSource(final byte[] buffer, final int offset, final int len) {
        this.buffer = buffer;
        limit = offset + len;
        pos = offset;
        startPos = pos;
    }

    public int readTag() throws IOException {
        if (isAtEnd()) {
            lastTag = 0;
            return 0;
        }

        lastTag = readRawVarint32();
        if (com.google.protobuf.WireFormat.getTagFieldNumber(lastTag) == 0) {
            // If we actually read zero (or any tag number corresponding to field
            // number zero), that's not a valid tag.
            throw invalidTag();
        }
        return lastTag;
    }

    public void checkLastTagWas(final int value) throws InvalidProtocolBufferException {
        if (lastTag != value) {
            throw invalidEndTag();
        }
    }

    public int getLastTag() {
        return lastTag;
    }

    public boolean skipField(final int tag) throws IOException {
        switch (com.google.protobuf.WireFormat.getTagWireType(tag)) {
            case com.google.protobuf.WireFormat.WIRETYPE_VARINT:
                skipRawVarint();
                return true;
            case com.google.protobuf.WireFormat.WIRETYPE_FIXED64:
                skipRawBytes(FIXED64_SIZE);
                return true;
            case com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED:
                skipRawBytes(readRawVarint32());
                return true;
            case com.google.protobuf.WireFormat.WIRETYPE_START_GROUP:
                skipMessage();
                checkLastTagWas(
                        makeTag(com.google.protobuf.WireFormat.getTagFieldNumber(tag), com.google.protobuf.WireFormat.WIRETYPE_END_GROUP));
                return true;
            case com.google.protobuf.WireFormat.WIRETYPE_END_GROUP:
                return false;
            case com.google.protobuf.WireFormat.WIRETYPE_FIXED32:
                skipRawBytes(FIXED32_SIZE);
                return true;
            default:
                throw invalidWireType();
        }
    }

    public boolean skipField(final int tag, final CodedOutputStream output) throws IOException {
        switch (com.google.protobuf.WireFormat.getTagWireType(tag)) {
            case com.google.protobuf.WireFormat.WIRETYPE_VARINT:
            {
                long value = readInt64();
                output.writeRawVarint32(tag);
                output.writeUInt64NoTag(value);
                return true;
            }
            case com.google.protobuf.WireFormat.WIRETYPE_FIXED64:
            {
                long value = readRawLittleEndian64();
                output.writeRawVarint32(tag);
                output.writeFixed64NoTag(value);
                return true;
            }
            case com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED:
            {
                byte[] value = readBytes();
                output.writeRawVarint32(tag);
                output.writeByteArrayNoTag(value);
                return true;
            }
            case com.google.protobuf.WireFormat.WIRETYPE_START_GROUP:
            {
                output.writeRawVarint32(tag);
                skipMessage(output);
                int endtag =
                        makeTag(
                                com.google.protobuf.WireFormat.getTagFieldNumber(tag), com.google.protobuf.WireFormat.WIRETYPE_END_GROUP);
                checkLastTagWas(endtag);
                output.writeRawVarint32(endtag);
                return true;
            }
            case com.google.protobuf.WireFormat.WIRETYPE_END_GROUP:
            {
                return false;
            }
            case com.google.protobuf.WireFormat.WIRETYPE_FIXED32:
            {
                int value = readRawLittleEndian32();
                output.writeRawVarint32(tag);
                output.writeFixed32NoTag(value);
                return true;
            }
            default:
                throw invalidWireType();
        }
    }

    public void skipMessage() throws IOException {
        while (true) {
            final int tag = readTag();
            if (tag == 0 || !skipField(tag)) {
                return;
            }
        }
    }

    public void skipMessage(CodedOutputStream output) throws IOException {
        while (true) {
            final int tag = readTag();
            if (tag == 0 || !skipField(tag, output)) {
                return;
            }
        }
    }


    // -----------------------------------------------------------------

    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readRawLittleEndian64());
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readRawLittleEndian32());
    }

    public long readUInt64() throws IOException {
        return readRawVarint64();
    }

    public long readInt64() throws IOException {
        return readRawVarint64();
    }

    public int readInt32() throws IOException {
        return readRawVarint32();
    }

    public long readFixed64() throws IOException {
        return readRawLittleEndian64();
    }

    public int readFixed32() throws IOException {
        return readRawLittleEndian32();
    }

    public boolean readBool() throws IOException {
        return readRawVarint64() != 0;
    }

    public String readString() throws IOException {
        final int size = readRawVarint32();
        if (size > 0 && size <= (limit - pos)) {
            // Fast path:  We already have the bytes in a contiguous buffer, so
            //   just copy directly from it.
            final String result = new String(buffer, pos, size, StandardCharsets.UTF_8);
            pos += size;
            return result;
        }

        if (size == 0) {
            return "";
        }
        if (size < 0) {
            throw negativeSize();
        }
        throw truncatedMessage();
    }

    public void readGroup(
            final ProtoMessage builder,
            final int fieldNumber)
            throws IOException {
        if (recursionDepth >= recursionLimit) {
            throw recursionLimitExceeded();
        }
        ++recursionDepth;
        builder.mergeFrom(this);
        checkLastTagWas(makeTag(fieldNumber, com.google.protobuf.WireFormat.WIRETYPE_END_GROUP));
        --recursionDepth;
    }

    public void readMessage(final ProtoMessage message) throws IOException {
        final int length = readRawVarint32();
        if (recursionDepth >= recursionLimit) {
            throw recursionLimitExceeded();
        }
        final int oldLimit = pushLimit(length);
        ++recursionDepth;
        message.mergeFrom(this);
        checkLastTagWas(0);
        --recursionDepth;
        popLimit(oldLimit);
    }

    public byte[] readBytes() throws IOException {
        final int size = readRawVarint32();
        return readRawBytes(size);
    }

    public int readUInt32() throws IOException {
        return readRawVarint32();
    }

    public int readEnum() throws IOException {
        return readRawVarint32();
    }

    public int readSFixed32() throws IOException {
        return readRawLittleEndian32();
    }

    public long readSFixed64() throws IOException {
        return readRawLittleEndian64();
    }

    public int readSInt32() throws IOException {
        return decodeZigZag32(readRawVarint32());
    }

    public long readSInt64() throws IOException {
        return decodeZigZag64(readRawVarint64());
    }

    // =================================================================

    public int readRawVarint32() throws IOException {
        // See implementation notes for readRawVarint64
        fastpath:
        {
            int tempPos = pos;

            if (limit == tempPos) {
                break fastpath;
            }

            final byte[] buffer = this.buffer;
            int x;
            if ((x = buffer[tempPos++]) >= 0) {
                pos = tempPos;
                return x;
            } else if (limit - tempPos < 9) {
                break fastpath;
            } else if ((x ^= (buffer[tempPos++] << 7)) < 0) {
                x ^= (~0 << 7);
            } else if ((x ^= (buffer[tempPos++] << 14)) >= 0) {
                x ^= (~0 << 7) ^ (~0 << 14);
            } else if ((x ^= (buffer[tempPos++] << 21)) < 0) {
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21);
            } else {
                int y = buffer[tempPos++];
                x ^= y << 28;
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21) ^ (~0 << 28);
                if (y < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0
                        && buffer[tempPos++] < 0) {
                    break fastpath; // Will throw malformedVarint()
                }
            }
            pos = tempPos;
            return x;
        }
        return (int) readRawVarint64SlowPath();
    }

    private void skipRawVarint() throws IOException {
        if (limit - pos >= MAX_VARINT_SIZE) {
            skipRawVarintFastPath();
        } else {
            skipRawVarintSlowPath();
        }
    }

    private void skipRawVarintFastPath() throws IOException {
        for (int i = 0; i < MAX_VARINT_SIZE; i++) {
            if (buffer[pos++] >= 0) {
                return;
            }
        }
        throw malformedVarint();
    }

    private void skipRawVarintSlowPath() throws IOException {
        for (int i = 0; i < MAX_VARINT_SIZE; i++) {
            if (readRawByte() >= 0) {
                return;
            }
        }
        throw malformedVarint();
    }

    public long readRawVarint64() throws IOException {
        // Implementation notes:
        //
        // Optimized for one-byte values, expected to be common.
        // The particular code below was selected from various candidates
        // empirically, by winning VarintBenchmark.
        //
        // Sign extension of (signed) Java bytes is usually a nuisance, but
        // we exploit it here to more easily obtain the sign of bytes read.
        // Instead of cleaning up the sign extension bits by masking eagerly,
        // we delay until we find the final (positive) byte, when we clear all
        // accumulated bits with one xor.  We depend on javac to constant fold.
        fastpath:
        {
            int tempPos = pos;

            if (limit == tempPos) {
                break fastpath;
            }

            final byte[] buffer = this.buffer;
            long x;
            int y;
            if ((y = buffer[tempPos++]) >= 0) {
                pos = tempPos;
                return y;
            } else if (limit - tempPos < 9) {
                break fastpath;
            } else if ((y ^= (buffer[tempPos++] << 7)) < 0) {
                x = y ^ (~0 << 7);
            } else if ((y ^= (buffer[tempPos++] << 14)) >= 0) {
                x = y ^ ((~0 << 7) ^ (~0 << 14));
            } else if ((y ^= (buffer[tempPos++] << 21)) < 0) {
                x = y ^ ((~0 << 7) ^ (~0 << 14) ^ (~0 << 21));
            } else if ((x = y ^ ((long) buffer[tempPos++] << 28)) >= 0L) {
                x ^= (~0L << 7) ^ (~0L << 14) ^ (~0L << 21) ^ (~0L << 28);
            } else if ((x ^= ((long) buffer[tempPos++] << 35)) < 0L) {
                x ^= (~0L << 7) ^ (~0L << 14) ^ (~0L << 21) ^ (~0L << 28) ^ (~0L << 35);
            } else if ((x ^= ((long) buffer[tempPos++] << 42)) >= 0L) {
                x ^= (~0L << 7) ^ (~0L << 14) ^ (~0L << 21) ^ (~0L << 28) ^ (~0L << 35) ^ (~0L << 42);
            } else if ((x ^= ((long) buffer[tempPos++] << 49)) < 0L) {
                x ^=
                        (~0L << 7)
                                ^ (~0L << 14)
                                ^ (~0L << 21)
                                ^ (~0L << 28)
                                ^ (~0L << 35)
                                ^ (~0L << 42)
                                ^ (~0L << 49);
            } else {
                x ^= ((long) buffer[tempPos++] << 56);
                x ^=
                        (~0L << 7)
                                ^ (~0L << 14)
                                ^ (~0L << 21)
                                ^ (~0L << 28)
                                ^ (~0L << 35)
                                ^ (~0L << 42)
                                ^ (~0L << 49)
                                ^ (~0L << 56);
                if (x < 0L) {
                    if (buffer[tempPos++] < 0L) {
                        break fastpath; // Will throw malformedVarint()
                    }
                }
            }
            pos = tempPos;
            return x;
        }
        return readRawVarint64SlowPath();
    }

    long readRawVarint64SlowPath() throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            final byte b = readRawByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw malformedVarint();
    }

    public int readRawLittleEndian32() throws IOException {
        int tempPos = pos;

        if (limit - tempPos < FIXED32_SIZE) {
            throw truncatedMessage();
        }

        final byte[] buffer = this.buffer;
        pos = tempPos + FIXED32_SIZE;
        return (((buffer[tempPos] & 0xff))
                | ((buffer[tempPos + 1] & 0xff) << 8)
                | ((buffer[tempPos + 2] & 0xff) << 16)
                | ((buffer[tempPos + 3] & 0xff) << 24));
    }

    public long readRawLittleEndian64() throws IOException {
        int tempPos = pos;

        if (limit - tempPos < FIXED64_SIZE) {
            throw truncatedMessage();
        }

        final byte[] buffer = this.buffer;
        pos = tempPos + FIXED64_SIZE;
        return (((buffer[tempPos] & 0xffL))
                | ((buffer[tempPos + 1] & 0xffL) << 8)
                | ((buffer[tempPos + 2] & 0xffL) << 16)
                | ((buffer[tempPos + 3] & 0xffL) << 24)
                | ((buffer[tempPos + 4] & 0xffL) << 32)
                | ((buffer[tempPos + 5] & 0xffL) << 40)
                | ((buffer[tempPos + 6] & 0xffL) << 48)
                | ((buffer[tempPos + 7] & 0xffL) << 56));
    }

    public void resetSizeCounter() {
        startPos = pos;
    }

    public int pushLimit(int byteLimit) throws InvalidProtocolBufferException {
        if (byteLimit < 0) {
            throw negativeSize();
        }
        byteLimit += getTotalBytesRead();
        final int oldLimit = currentLimit;
        if (byteLimit > oldLimit) {
            throw truncatedMessage();
        }
        currentLimit = byteLimit;

        recomputeBufferSizeAfterLimit();

        return oldLimit;
    }

    private void recomputeBufferSizeAfterLimit() {
        limit += bufferSizeAfterLimit;
        final int bufferEnd = limit - startPos;
        if (bufferEnd > currentLimit) {
            // Limit is in current buffer.
            bufferSizeAfterLimit = bufferEnd - currentLimit;
            limit -= bufferSizeAfterLimit;
        } else {
            bufferSizeAfterLimit = 0;
        }
    }

    public void popLimit(final int oldLimit) {
        currentLimit = oldLimit;
        recomputeBufferSizeAfterLimit();
    }

    public int getBytesUntilLimit() {
        if (currentLimit == Integer.MAX_VALUE) {
            return -1;
        }

        return currentLimit - getTotalBytesRead();
    }

    public boolean isAtEnd() throws IOException {
        return pos == limit;
    }

    public int getTotalBytesRead() {
        return pos - startPos;
    }

    public byte readRawByte() throws IOException {
        if (pos == limit) {
            throw truncatedMessage();
        }
        return buffer[pos++];
    }

    public byte[] readRawBytes(final int length) throws IOException {
        if (length > 0 && length <= (limit - pos)) {
            final int tempPos = pos;
            pos += length;
            return Arrays.copyOfRange(buffer, tempPos, pos);
        }

        if (length <= 0) {
            if (length == 0) {
                return Internal.EMPTY_BYTE_ARRAY;
            } else {
                throw negativeSize();
            }
        }
        throw truncatedMessage();
    }

    public void skipRawBytes(final int length) throws IOException {
        if (length >= 0 && length <= (limit - pos)) {
            // We have all the bytes we need already.
            pos += length;
            return;
        }

        if (length < 0) {
            throw negativeSize();
        }
        throw truncatedMessage();
    }

    /** Get current position in buffer relative to beginning offset. */
    public int getPosition() {
        return pos - startPos;
    }

    /** Rewind to previous position. Cannot go forward. */
    public void rewindToPosition(int position) {
        if (position > pos - startPos) {
            throw new IllegalArgumentException(
                    "Position " + position + " is beyond current " + (pos - startPos));
        }
        if (position < 0) {
            throw new IllegalArgumentException("Bad position " + position);
        }
        pos = startPos + position;
    }

    protected int remaining() {
        // bufferSize is always the same as currentLimit
        // in cases where currentLimit != Integer.MAX_VALUE
        return limit - pos;
    }

    protected void requireRemaining(int numBytes) throws IOException {
        if (numBytes < 0) {
            throw negativeSize();

        } else if (numBytes > remaining()) {
            // Read to the end of the current sub-message before failing
            if (numBytes > currentLimit - pos) {
                pos = currentLimit;
            }
            throw InvalidProtocolBufferException.truncatedMessage();
        }
    }

    /**
     * Computes the array length of a repeated field. We assume that in the common case repeated
     * fields are contiguously serialized but we still correctly handle interspersed values of a
     * repeated field (but with extra allocations).
     * <p>
     * Rewinds to current input position before returning.
     *
     * @param input source input, pointing to the byte after the first tag
     * @param tag   repeated field tag just read
     * @return length of array
     * @throws IOException
     */
    public static int getRepeatedFieldArrayLength(final ProtoSource input, final int tag) throws IOException {
        int arrayLength = 1;
        int startPos = input.getPosition();
        input.skipField(tag);
        while (input.readTag() == tag) {
            input.skipField(tag);
            arrayLength++;
        }
        input.rewindToPosition(startPos);
        return arrayLength;
    }

}
