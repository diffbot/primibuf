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

package com.diffbot.primibuf.runtime;

import java.io.IOException;

/**
 * Abstract interface implemented by Protocol Message objects.
 * <p>
 * API partially copied from Google's MessageNano
 *
 * @author Florian Enner
 */
public abstract class ProtoMessage<MessageType extends ProtoMessage> {

    private static final long serialVersionUID = 0L;

    // Keep the first bitfield in the parent class so that it
    // is likely in the same cache line as the object header
    protected int bitField0_;

    protected ProtoMessage() {}

    /**
     * Parse {@code input} as a message of this type and merge it with the
     * message being built.
     */
    public abstract ProtoMessage mergeFrom(ProtoSource input) throws IOException;

    /**
     * Merge {@code other} into the message being built. {@code other} must have the exact same type
     * as {@code this}.
     *
     * <p>Merging occurs as follows. For each field:<br>
     * * For singular primitive fields, if the field is set in {@code other}, then {@code other}'s
     * value overwrites the value in this message.<br>
     * * For singular message fields, if the field is set in {@code other}, it is merged into the
     * corresponding sub-message of this message using the same merging rules.<br>
     * * For repeated fields, the elements in {@code other} are concatenated with the elements in
     * this message.<br>
     * * For oneof groups, if the other message has one of the fields set, the group of this message
     * is cleared and replaced by the field of the other message, so that the oneof constraint is
     * preserved.
     *
     * <p>This is equivalent to the {@code Message::MergeFrom} method in C++.
     */
    public MessageType mergeFrom(MessageType other) {
        throw new RuntimeException("MergeFrom method not generated");
    }

    /**
     * Parse {@code data} as a message of this type and merge it with the
     * message being built.
     */
    public static final <T extends ProtoMessage> T mergeFrom(T msg, final byte[] data)
            throws InvalidProtocolBufferException {
        return mergeFrom(msg, data, 0, data.length);
    }

    /**
     * Parse {@code data} as a message of this type and merge it with the
     * message being built.
     */
    public static final <T extends ProtoMessage> T mergeFrom(T msg, final byte[] data,
                                                             final int off, final int len) throws InvalidProtocolBufferException {
        try {
            final ProtoSource input = new ProtoSource(data, off, len);
            msg.mergeFrom(input);
            input.checkLastTagWas(0);
            return msg;
        } catch (InvalidProtocolBufferException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Reading from a byte array threw an IOException (should "
                    + "never happen).");
        }
    }

    /**
     * Indicates whether another object is "equal to" this one.
     * <p>
     * An object is considered equal when it is of the same message
     * type, contains the same fields (same has state), and all of
     * the field contents are equal.
     * <p>
     * This comparison ignores unknown fields, so the serialized binary
     * form may not be equal.
     *
     * @param obj the reference object with which to compare
     * @return {@code true} if this object is the same as the obj
     * argument; {@code false} otherwise.
     */
    @Override
    public abstract boolean equals(Object obj);

    /**
     * Messages have no immutable state and should not
     * be used in hashing structures. This implementation
     * returns a constant value in order to satisfy the
     * contract.
     */
    @Override
    public final int hashCode() {
        return 0;
    }

    /**
     * Helper to determine the default value for 'Bytes' fields. The Protobuf
     * generator encodes raw bytes as strings with ISO-8859-1 encoding.
     */
    protected static byte[] bytesDefaultValue(String bytes) {
        return bytes.getBytes(ProtoUtil.Charsets.ISO_8859_1);
    }

}
