/*-
 * #%L
 * quickbuf-generator
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

package com.diffbot.primibuf;

import com.diffbot.primibuf.RequestInfo.FieldInfo;

import java.util.List;

/**
 * Utilities for creating protobuf-like bit-sets to keep has state
 *
 * @author Florian Enner
 * @since 19 Jun 2015
 */
class BitField {

    static int BITS_PER_FIELD = 32;

    static int getFieldIndex(int fieldIndex) {
        return fieldIndex / BITS_PER_FIELD;
    }

    static int getBitIndex(int fieldIndex) {
        return fieldIndex % BITS_PER_FIELD;
    }

    static String hasBit(int hasBitIndex) {
        return String.format("(bitField%d_ & 0x%08x) != 0", getFieldIndex(hasBitIndex), 1 << getBitIndex(hasBitIndex));
    }

    static String setBit(int hasBitIndex) {
        return String.format("bitField%d_ |= 0x%08x", getFieldIndex(hasBitIndex), 1 << getBitIndex(hasBitIndex));
    }

    static String clearBit(int hasBitIndex) {
        int field = getFieldIndex(hasBitIndex);
        return String.format("bitField%d_ &= ~0x%08x", field, 1 << getBitIndex(hasBitIndex));
    }

    static String hasNoBits(int numBitFields) {
        String output = "((" + fieldName(0);
        for (int i = 1; i < numBitFields; i++) {
            output += " | " + fieldName(i);
        }
        return output + ") == 0)";
    }

    static String isMissingAnyBit(int[] bitset) {
        StringBuilder builder = new StringBuilder();
        int usedFields = 0;
        for (int i = 0; i < bitset.length; i++) {
            if (bitset[i] == 0) continue;
            builder.append(usedFields++ == 0 ? "(" : " || ");
            builder.append(String.format("((bitField%d_ & 0x%08x) != 0x%08x)",
                    i, bitset[i], bitset[i]));
        }
        builder.append(usedFields > 0 ? ")" : "true");
        return builder.toString();
    }

    static String hasAnyBit(int[] bitset) {
        StringBuilder builder = new StringBuilder();
        int usedFields = 0;
        for (int i = 0; i < bitset.length; i++) {
            if (bitset[i] == 0) continue;
            builder.append(usedFields++ == 0 ? "((" : " | ");
            builder.append(String.format("(bitField%d_ & 0x%08x)", i, bitset[i]));
        }
        builder.append(usedFields > 0 ? ") != 0)" : "true");
        return builder.toString();
    }

    static int[] generateBitset(List<FieldInfo> fields) {
        int maxIndex = fields.stream().mapToInt(FieldInfo::getBitIndex).max().orElse(0);
        int[] bits = new int[getNumberOfFields(maxIndex) + 1];
        for (FieldInfo field : fields) {
            int fieldIndex = getFieldIndex(field.getBitIndex());
            bits[fieldIndex] |= 1 << getBitIndex(field.getBitIndex());
        }
        return bits;
    }

    /**
     * string that results in 1 if the bit is set, or zero if it is not set
     *
     * Initial tests for using this as an optimization for removing if statements
     * for fixed width types had no effect. The JIT probably already spits out
     * similar code anyways.
     *
     * @param hasBitIndex
     * @return
     */
    static String oneOrZeroBit(int hasBitIndex) {
        int intSlot = hasBitIndex / BITS_PER_FIELD;
        int indexInSlot = hasBitIndex - (intSlot * BITS_PER_FIELD);
        return String.format("((bitField%d_ & 0x%08x) >>> %d)", intSlot, 1 << indexInSlot, indexInSlot);
    }

    static String fieldName(int intIndex) {
        return String.format("bitField%d_", intIndex);
    }

    static int getNumberOfFields(int fieldCount) {
        return (fieldCount + (BITS_PER_FIELD - 1)) / BITS_PER_FIELD;
    }

}
