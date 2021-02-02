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

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;

/**
 * TypeNames of all API classes that can be referenced from generated code
 *
 * @author Florian Enner
 * @since 07 Aug 2019
 */
class RuntimeClasses {

    private static final String API_PACKAGE = "com.diffbot.primibuf.runtime";

    static final ClassName ProtoSource = ClassName.get(API_PACKAGE, "ProtoSource");
    static final ClassName ProtoSink = ClassName.get(API_PACKAGE, "ProtoSink");
    static final ClassName ProtoUtil = ClassName.get(API_PACKAGE, "ProtoUtil");
    static final ClassName AbstractMessage = ClassName.get(API_PACKAGE, "ProtoMessage");
    static final ClassName MessageFactory = ClassName.get(API_PACKAGE, "MessageFactory");
    static final ClassName BytesType = ClassName.get(API_PACKAGE, "RepeatedByte");
    static final ClassName InvalidProtocolBufferException = ClassName.get(API_PACKAGE, "InvalidProtocolBufferException");
    static final ClassName ProtoEnum = ClassName.get(API_PACKAGE, "ProtoEnum");
    static final ClassName EnumConverter = ProtoEnum.nestedClass("EnumConverter");

    private static final ClassName RepeatedString = ClassName.get(API_PACKAGE, "RepeatedString");
    static final ClassName RepeatedMessage = ClassName.get(API_PACKAGE, "RepeatedMessage");
    static final ClassName RepeatedEnum = ClassName.get(API_PACKAGE, "RepeatedEnum");

    static ClassName getRepeatedStoreType(FieldDescriptorProto.Type type) {
        switch (type) {
            case TYPE_ENUM:
                return RepeatedEnum;

            case TYPE_STRING:
                return RepeatedString;

            case TYPE_GROUP:
            case TYPE_MESSAGE:
                return RepeatedMessage;

            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
    }

    static TypeName getPrimitiveStoreType(FieldDescriptorProto.Type type) {
        switch (type) {
            case TYPE_DOUBLE:
                return TypeName.DOUBLE;

            case TYPE_FLOAT:
                return TypeName.FLOAT;

            case TYPE_SFIXED64:
            case TYPE_FIXED64:
            case TYPE_SINT64:
            case TYPE_INT64:
            case TYPE_UINT64:
                return TypeName.LONG;

            case TYPE_SFIXED32:
            case TYPE_FIXED32:
            case TYPE_SINT32:
            case TYPE_INT32:
            case TYPE_UINT32:
                return TypeName.INT;

            case TYPE_BOOL:
                return TypeName.BOOLEAN;

            case TYPE_BYTES:
                return TypeName.BYTE;

            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
    }

    static TypeName getPrimitiveRepeatedStoreType(FieldDescriptorProto.Type type) {
        TypeName primitiveType = getPrimitiveStoreType(type);
        return ArrayTypeName.of(primitiveType);
    }

}
