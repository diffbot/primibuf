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

import com.squareup.javapoet.*;

import javax.lang.model.element.Modifier;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * This class generates all serialization logic and field related accessors.
 * It is a bit of a mess due to lots of switch statements, but I found that
 * splitting the types up similarly to how the protobuf-generator code is
 * organized makes it really difficult to find and manage duplicate code,
 * and to keep track of where things are being called.
 *
 * @author Florian Enner
 * @since 07 Aug 2019
 */
public class FieldGenerator {

    protected com.diffbot.primibuf.RequestInfo.FieldInfo getInfo() {
        return info;
    }

    protected void generateMemberFields(TypeSpec.Builder type) {
        FieldSpec.Builder field = FieldSpec.builder(storeType, info.getFieldName())
                .addJavadoc(named("$commentLine:L"))
                .addModifiers(Modifier.PRIVATE);

        if (info.isRepeated() && info.isMessageOrGroup()) {
            field.addModifiers(Modifier.FINAL).initializer("$T.newEmptyInstance($T.getFactory())", com.diffbot.primibuf.RuntimeClasses.RepeatedMessage, info.getTypeName());
        } else if (info.isRepeated() && info.isEnum()) {
            field.addModifiers(Modifier.FINAL).initializer("$T.newEmptyInstance($T.converter())", com.diffbot.primibuf.RuntimeClasses.RepeatedEnum, info.getTypeName());
        } else if (info.isRepeated() && info.isPrimitive()) {
            // for primitive arrays (such as int[]), we initialize them to null and only allocate array on demand
            // thus there is no final modifier
            field.initializer("null");
        } else if (info.isRepeated() && (info.isPrimitive() || info.isString())) {
            // for primitive arrays (such as int[]), we initialize them to null and only allocate array on demand
            // thus there is no final modifier
            field.initializer("null");
        } else if (info.isRepeated()) {
            field.addModifiers(Modifier.FINAL).initializer("$T.newEmptyInstance()", storeType);
        } else if (info.isBytes()) {
            if (!info.hasDefaultValue()) {
                field.initializer("null");
            } else {
                // TODO: fix this
                field.initializer(named("$storeType:T.newInstance($defaultField:N)"));
            }
        } else if (info.isMessageOrGroup()) {
            field.addModifiers(Modifier.FINAL).initializer("$T.newInstance()", storeType);
        } else if (info.isString()) {
            // string can be mutable
            if (!info.hasDefaultValue()) {
                field.initializer("null");
            } else {
                field.initializer(named("$default:S"));
            }
        } else if (info.isPrimitive() || info.isEnum()) {
            if (info.hasDefaultValue()) {
                field.initializer(named("$default:L"));
            }
        } else {
            throw new IllegalStateException("unhandled field: " + info.getDescriptor());
        }
        type.addField(field.build());

        if (info.isBytes() && info.hasDefaultValue()) {
            // byte[] default values are stored as utf8 strings, so we need to convert it first
            type.addField(FieldSpec.builder(ArrayTypeName.get(byte[].class), info.getDefaultFieldName())
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(named("$abstractMessage:T.bytesDefaultValue(\"$default:L\")"))
                    .build());
        }
    }

    protected void generateEqualsStatement(MethodSpec.Builder method) {
        if (info.isRepeated() || info.isBytes() || info.isMessageOrGroup() || info.isString()) {
            method.addNamedCode("$field:N.equals(other.$field:N)", m);

        } else if (typeName == TypeName.DOUBLE) {
            method.addNamedCode("Double.doubleToLongBits($field:N) == Double.doubleToLongBits(other.$field:N)", m);

        } else if (typeName == TypeName.FLOAT) {
            method.addNamedCode("Float.floatToIntBits($field:N) == Float.floatToIntBits(other.$field:N)", m);

        } else if (info.isPrimitive() || info.isEnum()) {
            method.addNamedCode("$field:N == other.$field:N", m);

        } else {
            throw new IllegalStateException("unhandled field: " + info.getDescriptor());
        }
    }

    protected void generateMergingCode(MethodSpec.Builder method) {
        if (info.isRepeated() && info.isPrimitive()) {
            method
                    .addCode(clearOtherOneOfs)
                    // find the size of continuous items
                    .addStatement(named("int count = $protoSource:T.getRepeatedFieldArrayLength(input, $tag:L)"))

                    // initialize or resize array
                    .beginControlFlow("if ($N == null)", info.getFieldName())
                    .addStatement(named("$field:N = new $primitiveType:L[count]"))
                    .nextControlFlow("else")
                    .addComment("TODO: resize existing array")
                    .endControlFlow()

                    // read data
                    .beginControlFlow("for (int i = 0; i < count; i++)")
                    .addStatement(named("$field:N[i] = input.read$capitalizedType:L()"))
                    .beginControlFlow("if (i != count -1)")
                    .addStatement("input.readTag()") // discard tag
                    .endControlFlow()
                    .endControlFlow()

                    .addStatement(named("$setHas:L"));
        } else if (info.isRepeated() && info.isString()) {
            method
                    .addCode(clearOtherOneOfs)
                    // find the size of continuous items
                    .addStatement(named("int count = $protoSource:T.getRepeatedFieldArrayLength(input, $tag:L)"))

                    // initialize or resize array
                    .beginControlFlow("if ($N == null)", info.getFieldName())
                    .addStatement(named("$field:N = new String[count]"))
                    .nextControlFlow("else")
                    .addComment("TODO: resize existing array")
                    .endControlFlow()

                    // read data
                    .beginControlFlow("for (int i = 0; i < count; i++)")
                    .addStatement(named("$field:N[i] = input.read$capitalizedType:L()"))
                    .beginControlFlow("if (i != count -1)")
                    .addStatement("input.readTag()") // discard tag
                    .endControlFlow()
                    .endControlFlow()

                    .addStatement(named("$setHas:L"));
        } else if (info.isRepeated()) {
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement("int nextTagPosition")
                    .addNamedCode("do {$>\n" +
                            "// look ahead for more items so we resize only once\n" +
                            "if ($field:N.remainingCapacity() == 0) {$>\n" +
                            "int count = $protoSource:T.getRepeatedFieldArrayLength(input, $tag:L);\n" +
                            "$field:N.reserve(count);\n" +
                            "$<}\n", m)
                    .addCode(code(block -> {
                        if (info.isPrimitive()) {
                            block.addNamed("$field:N.add(input.read$capitalizedType:L());\n", m);
                        } else if (info.isEnum()) {
                            block.addNamed("$field:N.addValue(input.read$capitalizedType:L());\n", m);
                        } else {
                            block.addNamed("input.read$capitalizedType:L($field:N.next()$secondArgs:L);\n", m);
                        }
                    }))
                    .addNamedCode("" +
                            "nextTagPosition = input.getPosition();\n" +
                            "$<} while (input.readTag() == $tag:L);\n" +
                            "input.rewindToPosition(nextTagPosition);\n", m)
                    .addStatement(named("$setHas:L"));

        } else if (info.isString()) {
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement(named("$field:N$secondArgs:L = input.readString()"))
                    .addStatement(named("$setHas:L"));

        } else if (info.isBytes()) {
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement(named("$field:N$secondArgs:L = input.readBytes()"))
                    .addStatement(named("$setHas:L"));

        } else if (info.isMessageOrGroup()) {
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement(named("input.read$capitalizedType:L($field:N$secondArgs:L)"))
                    .addStatement(named("$setHas:L"));

        } else if (info.isPrimitive()) {
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement(named("$field:N = input.read$capitalizedType:L()"))
                    .addStatement(named("$setHas:L"));

        } else if (info.isEnum()) {
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement("final int value = input.readInt32()")
                    .beginControlFlow("if ($T.forNumber(value) != null)", typeName)
                    .addStatement(named("$field:N = value"))
                    .addStatement(named("$setHas:L"));

            method.endControlFlow();

        } else {
            throw new IllegalStateException("unhandled field: " + info.getDescriptor());
        }
    }

    protected void generateMergingCodeFromPacked(MethodSpec.Builder method) {
        if (info.isFixedWidth()) {

            // For fixed width types we can copy the raw memory
            method.addCode(clearOtherOneOfs);
            method.addStatement(named("input.readPacked$capitalizedType:L($field:N)"));
            method.addStatement(named("$setHas:L"));

        } else if (info.isEnum()) {

            // We don't know how many items there actually are, so we need to
            // look-ahead once we run out of space in the backing store.
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement("final int length = input.readRawVarint32()")
                    .addStatement("final int limit = input.pushLimit(length)")
                    .beginControlFlow("while (input.getBytesUntilLimit() > 0)")

                    // Defer count-checks until we run out of capacity
                    .addComment("look ahead for more items so we resize only once")
                    .beginControlFlow("if ($N.remainingCapacity() == 0)", info.getFieldName())
                    .addStatement("final int position = input.getPosition()")
                    .addStatement("int count = 0")
                    .beginControlFlow("while (input.getBytesUntilLimit() > 0)")
                    .addStatement(named("input.read$capitalizedType:L()"))
                    .addStatement("count++")
                    .endControlFlow()
                    .addStatement("input.rewindToPosition(position)")
                    .addStatement(named("$field:N.reserve(count)"))
                    .endControlFlow()

                    // Add data
                    .addStatement(named(info.isPrimitive() ?
                            "$field:N.add(input.read$capitalizedType:L())" :
                            "$field:N.addValue(input.read$capitalizedType:L())"))
                    .endControlFlow()

                    .addStatement("input.popLimit(limit)")
                    .addStatement(named("$setHas:L"));

        } else if (info.isPrimitive()) {
            // optimized way to read into primitive arrays

            // We don't know how many items there actually are, so we need to
            // look-ahead once we run out of space in the backing store.
            method
                    .addCode(clearOtherOneOfs)
                    .addStatement("final int length = input.readRawVarint32()")
                    .addStatement("final int limit = input.pushLimit(length)")
                    .beginControlFlow("if (input.getBytesUntilLimit() > 0)")

                    // Defer count-checks until we run out of capacity
                    .addComment("look ahead for more items so we resize only once")
                    .addStatement("int count = 0")
                    .addStatement("final int position = input.getPosition()")
                    .beginControlFlow("while (input.getBytesUntilLimit() > 0)")
                    .addStatement(named("input.read$capitalizedType:L()"))
                    .addStatement("count++")
                    .endControlFlow()
                    .addStatement("input.rewindToPosition(position)")

                    .beginControlFlow("if ($N == null)", info.getFieldName())
                    .addStatement(named("$field:N = new $primitiveType:L[count]"))
                    .nextControlFlow("else")
                    .addComment("TODO: resize existing array")
                    .endControlFlow()

                    // Add data
                    .beginControlFlow("for (int i = 0; i < count; i++)")
                    .addStatement(named("$field:N[i] = input.read$capitalizedType:L()"))
                    .endControlFlow()

                    .endControlFlow()

                    .addStatement("input.popLimit(limit)")
                    .addStatement(named("$setHas:L"));
        } else {
            // Only primitives and enums can be packed
            throw new IllegalStateException("unhandled field: " + info.getDescriptor());
        }
    }

    protected void generateMemberMethods(TypeSpec.Builder type) {
        generateHasMethod(type);
        generateGetMethods(type);
        if (info.isEnum()) {
            generateExtraEnumAccessors(type);
        }
        if (info.getParentFile().getParentRequest().generateTryGetAccessors()) {
            generateTryGetMethod(type);
        }
    }

    protected void generateHasMethod(TypeSpec.Builder type) {
        type.addMethod(MethodSpec.methodBuilder(info.getHazzerName())
                .addAnnotations(info.getMethodAnnotations())
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addStatement(named("return $getHas:L"))
                .build());
    }

    /**
     * Enums are odd because they need to be converter back and forth and they
     * don't have the same type as the internal/repeated store. The normal
     * accessors provide access to the enum value, but for performance reasons
     * we also add accessors for the internal storage type that do not require
     * conversions.
     *
     * @param type
     */
    protected void generateExtraEnumAccessors(TypeSpec.Builder type) {
        if (!info.isEnum() || info.isRepeated())
            return;

        // Overload to get the internal store without conversion
        type.addMethod(MethodSpec.methodBuilder(info.getGetterName() + "Value")
                .addAnnotations(info.getMethodAnnotations())
                .addJavadoc(named("" +
                        "Gets the value of the internal enum store. The result is\n" +
                        "equivalent to {@link $message:T#$getMethod:N()}.getNumber().\n"))
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addCode(enforceHasCheck)
                .addStatement(named("return $field:N"))
                .build());
    }

    protected void generateTryGetMethod(TypeSpec.Builder type) {
        MethodSpec.Builder tryGet = MethodSpec.methodBuilder(info.getTryGetName())
                .addAnnotations(info.getMethodAnnotations())
                .addModifiers(Modifier.PUBLIC)
                .returns(info.getOptionalReturnType());

        tryGet.beginControlFlow(named("if ($hasMethod:N())"))
                .addStatement(named("return $optional:T.of($getMethod:N())"))
                .nextControlFlow("else")
                .addStatement(named("return $optional:T.empty()"))
                .endControlFlow();

        type.addMethod(tryGet.build());
    }

    protected void generateGetMethods(TypeSpec.Builder type) {
        MethodSpec.Builder getter = MethodSpec.methodBuilder(info.getGetterName())
                .addAnnotations(info.getMethodAnnotations())
                .addModifiers(Modifier.PUBLIC)
                .addCode(enforceHasCheck);

        if (info.isRepeated()) {
            getter.returns(storeType).addStatement(named("return $field:N"));
        } else if (info.isString()) {
            getter.returns(typeName).addStatement(named("return $field:N"));
        } else if (info.isEnum()) {
            if (info.hasDefaultValue()) {
                getter.returns(typeName).addStatement(named("return $type:T.forNumberOr($field:N, $defaultEnumValue:L)"));
            } else {
                getter.returns(typeName).addStatement(named("return $type:T.forNumber($field:N)"));
            }
        } else {
            getter.returns(typeName).addStatement(named("return $field:N"));
        }

        type.addMethod(getter.build());
    }

    private CodeBlock generateClearOtherOneOfs() {
        if (!info.hasOtherOneOfFields())
            return EMPTY_BLOCK;

        return CodeBlock.builder()
                .addStatement("$N()", info.getClearOtherOneOfName())
                .build();
    }

    private CodeBlock generateEnforceHasCheck() {
        if (!info.getParentTypeInfo().isEnforceHasChecks())
            return EMPTY_BLOCK;

        return CodeBlock.builder()
                .beginControlFlow("if (!$N())", info.getHazzerName())
                .addStatement("throw new $T($S)", IllegalStateException.class,
                        "Field is not set. Check has state before accessing.")
                .endControlFlow()
                .build();
    }

    protected FieldGenerator(com.diffbot.primibuf.RequestInfo.FieldInfo info) {
        this.info = info;
        typeName = info.getTypeName();
        storeType = info.getStoreType();
        clearOtherOneOfs = generateClearOtherOneOfs();
        enforceHasCheck = generateEnforceHasCheck();

        // Common-variable map for named arguments
        m.put("field", info.getFieldName());
        m.put("default", info.getDefaultValue());
        if (info.isEnum()) {
            m.put("default", info.hasDefaultValue() ? info.getTypeName() + "." + info.getDefaultValue() + "_VALUE" : "0");
            m.put("defaultEnumValue", info.getTypeName() + "." + info.getDefaultValue());
        }
        m.put("storeType", storeType);
        // required to generate array initialization with size, such as `new int[count]`
        m.put("primitiveType", info.isPrimitive() ? info.getPrimitiveType() : null);
        m.put("commentLine", info.getJavadoc());
        m.put("getMethod", info.getGetterName());
        m.put("addMethod", info.getAdderName());
        m.put("hasMethod", info.getHazzerName());
        m.put("getHas", info.getHasBit());
        m.put("setHas", info.getSetBit());
        m.put("clearHas", info.getClearBit());
        m.put("message", info.getParentType());
        m.put("type", typeName);
        m.put("number", info.getNumber());
        m.put("tag", info.getTag());
        m.put("capitalizedType", com.diffbot.primibuf.FieldUtil.getCapitalizedType(info.getDescriptor().getType()));
        m.put("secondArgs", info.isGroup() ? ", " + info.getNumber() : "");
        m.put("defaultField", info.getDefaultFieldName());
        m.put("bytesPerTag", info.getBytesPerTag());
        m.put("valueOrNumber", info.isEnum() ? "value.getNumber()" : "value");
        m.put("optional", info.getOptionalClass());
        if (info.isPackable()) m.put("packedTag", info.getPackedTag());
        if (info.isFixedWidth()) m.put("fixedWidth", info.getFixedWidth());
        if (info.isRepeated())
            m.put("getRepeatedIndex_i", info.isPrimitive() || info.isEnum() ? "array()[i]" : "get(i)");

        // utility classes
        m.put("fieldNames", getInfo().getParentTypeInfo().getFieldNamesClass());
        m.put("abstractMessage", com.diffbot.primibuf.RuntimeClasses.AbstractMessage);
        m.put("protoSource", com.diffbot.primibuf.RuntimeClasses.ProtoSource);
        m.put("protoSink", com.diffbot.primibuf.RuntimeClasses.ProtoSink);
        m.put("protoUtil", com.diffbot.primibuf.RuntimeClasses.ProtoUtil);
    }

    protected final com.diffbot.primibuf.RequestInfo.FieldInfo info;
    protected final TypeName typeName;
    protected final TypeName storeType;
    protected final CodeBlock clearOtherOneOfs;
    protected final CodeBlock enforceHasCheck;
    private static final CodeBlock EMPTY_BLOCK = CodeBlock.builder().build();

    protected final HashMap<String, Object> m = new HashMap<>();

    private CodeBlock named(String format, Object... args /* does nothing, but makes IDE hints disappear */) {
        return CodeBlock.builder().addNamed(format, m).build();
    }

    private CodeBlock code(Consumer<CodeBlock.Builder> c) {
        CodeBlock.Builder block = CodeBlock.builder();
        c.accept(block);
        return block.build();
    }

}
