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

import com.diffbot.primibuf.RequestInfo.ExpectedIncomingOrder;
import com.diffbot.primibuf.RequestInfo.MessageInfo;
import com.squareup.javapoet.*;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * @author Florian Enner
 * @since 07 Aug 2019
 */
class MessageGenerator {

    TypeSpec generate() {
        TypeSpec.Builder type = TypeSpec.classBuilder(info.getTypeName())
                .superclass(ParameterizedTypeName.get(com.diffbot.primibuf.RuntimeClasses.AbstractMessage, info.getTypeName()))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        if (info.isNested()) {
            type.addModifiers(Modifier.STATIC);
        }

        if (!info.isNested()) {
            // Note: constants from enums and fields may have the same names
            // as constants in the nested classes. This causes Java warnings,
            // but is not fatal, so we suppress those warnings in the top-most
            // class declaration /javanano
            type.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "hiding")
                    .build());
        }

        // Nested Enums
        info.getNestedEnums().stream()
                .map(com.diffbot.primibuf.EnumGenerator::new)
                .map(com.diffbot.primibuf.EnumGenerator::generate)
                .forEach(type::addType);

        // Nested Types
        info.getNestedTypes().stream()
                .map(MessageGenerator::new)
                .map(MessageGenerator::generate)
                .forEach(type::addType);

        // newInstance() method
        type.addMethod(MethodSpec.methodBuilder("newInstance")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(info.getTypeName())
                .addStatement("return new $T()", info.getTypeName())
                .build());

        // Constructor
        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addStatement("super()")
                .build());

        // Member state (the first bitfield is in the parent class)
        for (int i = 1; i < numBitFields; i++) {
            type.addField(FieldSpec.builder(int.class, com.diffbot.primibuf.BitField.fieldName(i), Modifier.PRIVATE).build());
        }
        fields.forEach(f -> f.generateMemberFields(type));

        // OneOf Accessors
        info.getOneOfs().stream()
                .map(com.diffbot.primibuf.OneOfGenerator::new)
                .forEach(oneOf -> oneOf.generateMemberMethods(type));

        // Fields accessors
        fields.forEach(f -> f.generateMemberMethods(type));
        generateGetByFieldName(type);
        generateEquals(type);
        generateMergeFrom(type);

        // Static utilities
        generateParseFrom(type);
        generateMessageFactory(type);
        type.addField(FieldSpec.builder(TypeName.LONG, "serialVersionUID")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("0L")
                .build());

        return type.build();
    }

    private void generateGetByFieldName(TypeSpec.Builder type) {
        MethodSpec.Builder getFieldByName = MethodSpec.methodBuilder("getFieldByName")
                .addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .addParameter(String.class, "fieldName");

        getFieldByName.beginControlFlow("switch (fieldName)");
        for (FieldGenerator field : fields) {
            getFieldByName
                    .addCode("case $S: ", field.getInfo().getDescriptor().getName())
                    .addStatement("return $N", field.getInfo().getFieldName());
        }
        getFieldByName
                .addCode("default: ")
                .addStatement("throw new IllegalArgumentException(\"Field doesn't exist\" + fieldName)");
        getFieldByName.endControlFlow();

        type.addMethod(getFieldByName.build());
    }

    private void generateEquals(TypeSpec.Builder type) {
        MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(Object.class, "o");

        // Reference equality check
        equals.beginControlFlow("if (o == this)")
                .addStatement("return true")
                .endControlFlow();

        // Type check
        equals.beginControlFlow("if (!(o instanceof $T))", info.getTypeName())
                .addStatement("return false")
                .endControlFlow();
        equals.addStatement("$1T other = ($1T) o", info.getTypeName());

        // Check whether all of the same fields are set
        if (info.getFieldCount() > 0) {
            equals.addCode("return $1L == other.$1L$>", com.diffbot.primibuf.BitField.fieldName(0));
            for (int i = 1; i < numBitFields; i++) {
                equals.addCode("\n&& $1L == other.$1L", com.diffbot.primibuf.BitField.fieldName(i));
            }

            for (FieldGenerator field : fields) {
                equals.addCode("\n&& (!$1N() || ", field.getInfo().getHazzerName());
                field.generateEqualsStatement(equals);
                equals.addCode(")");
            }

            equals.addCode(";$<\n");
        } else {
            equals.addCode("return true;\n");
        }

        type.addMethod(equals.build());
    }

    private void generateMergeFrom(TypeSpec.Builder type) {
        MethodSpec.Builder mergeFrom = MethodSpec.methodBuilder("mergeFrom")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.getTypeName())
                .addParameter(com.diffbot.primibuf.RuntimeClasses.ProtoSource, "input", Modifier.FINAL)
                .addException(IOException.class);

        // Fallthrough optimization:
        //
        // Reads tag after case parser and checks if it can fall-through. In the ideal case if all fields are set
        // and the expected order matches the incoming data, the switch would only need to be executed once
        // for the first field.
        //
        // Packable fields make this a bit more complex since they need to generate two cases to preserve
        // backwards compatibility. However, any production proto file should already be using the packed
        // option whenever possible, so we don't need to optimize the non-packed case.
        final boolean enableFallthroughOptimization = info.getExpectedIncomingOrder() != ExpectedIncomingOrder.None;
        final List<FieldGenerator> sortedFields = new ArrayList<>(fields);
        switch (info.getExpectedIncomingOrder()) {
            case AscendingNumber:
                sortedFields.sort(com.diffbot.primibuf.FieldUtil.AscendingNumberSorter);
                break;
            case Quickbuf: // keep existing order
            case None: // no optimization
                break;
        }

        m.put("readTag", "readTag");

        if (enableFallthroughOptimization) {
            mergeFrom.addComment("Enabled Fall-Through Optimization (" + info.getExpectedIncomingOrder() + ")");
            mergeFrom.addStatement(named("int tag = input.$readTag:N()"));
            mergeFrom.beginControlFlow("while (true)");
        } else {
            mergeFrom.beginControlFlow("while (true)");
            mergeFrom.addStatement(named("int tag = input.$readTag:N()"));
        }
        mergeFrom.beginControlFlow("switch (tag)");

        // Add fields by the expected order and type
        for (int i = 0; i < sortedFields.size(); i++) {
            FieldGenerator field = sortedFields.get(i);

            // Assume all packable fields are written packed. Add non-packed cases to the end.
            if (field.getInfo().isPackable()) {
                mergeFrom.beginControlFlow("case $L:", field.getInfo().getPackedTag());
                field.generateMergingCodeFromPacked(mergeFrom);
            } else {
                mergeFrom.beginControlFlow("case $L:", field.getInfo().getTag());
                field.generateMergingCode(mergeFrom);
            }

            if (enableFallthroughOptimization) {
                // try falling to 0 (exit) at last field
                final int nextCase = (i == sortedFields.size() - 1) ? 0 : getPackedTagOrTag(sortedFields.get(i + 1));
                mergeFrom.addCode(named("if ((tag = input.$readTag:N())"));
                mergeFrom.beginControlFlow(" != $L)", nextCase);
                mergeFrom.addStatement("break");
                mergeFrom.endControlFlow();
            } else {
                mergeFrom.addStatement("break");
            }
            mergeFrom.endControlFlow();

        }

        // zero means invalid tag / end of data
        mergeFrom.beginControlFlow("case 0:")
                .addStatement("return this")
                .endControlFlow();

        // default case -> skip field
        mergeFrom.beginControlFlow("default:")
                .beginControlFlow("if (!input.skipField(tag))")
                .addStatement("return this");
        mergeFrom.endControlFlow();

        if (enableFallthroughOptimization) {
            mergeFrom.addStatement(named("tag = input.$readTag:N()"));
        }
        mergeFrom.addStatement("break").endControlFlow();

        // Generate missing non-packed cases for packable fields for compatibility reasons
        for (FieldGenerator field : sortedFields) {
            if (field.getInfo().isPackable()) {
                mergeFrom.beginControlFlow("case $L:", field.getInfo().getTag());
                field.generateMergingCode(mergeFrom);
                if (enableFallthroughOptimization) {
                    mergeFrom.addStatement(named("tag = input.$readTag:N()"));
                }
                mergeFrom.addStatement("break").endControlFlow();
            }
        }

        mergeFrom.endControlFlow();
        mergeFrom.endControlFlow();
        type.addMethod(mergeFrom.build());
    }

    private int getPackedTagOrTag(FieldGenerator field) {
        if (field.getInfo().isPackable())
            return field.getInfo().getPackedTag();
        return field.getInfo().getTag();
    }

    private void generateParseFrom(TypeSpec.Builder type) {
        type.addMethod(MethodSpec.methodBuilder("parseFrom")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addException(com.diffbot.primibuf.RuntimeClasses.InvalidProtocolBufferException)
                .addParameter(byte[].class, "data", Modifier.FINAL)
                .returns(info.getTypeName())
                .addStatement("return $T.mergeFrom(new $T(), data)", com.diffbot.primibuf.RuntimeClasses.AbstractMessage, info.getTypeName())
                .build());
    }

    private void generateMessageFactory(TypeSpec.Builder type) {
        ParameterizedTypeName factoryReturnType = ParameterizedTypeName.get(RuntimeClasses.MessageFactory, info.getTypeName());
        ClassName factoryTypeName = info.getTypeName().nestedClass(info.getTypeName().simpleName() + "Factory");

        MethodSpec factoryMethod = MethodSpec.methodBuilder("create")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.getTypeName())
                .addStatement("return $T.newInstance()", info.getTypeName())
                .build();

        TypeSpec factoryEnum = TypeSpec.enumBuilder(factoryTypeName.simpleName())
                .addModifiers(Modifier.PRIVATE)
                .addSuperinterface(factoryReturnType)
                .addEnumConstant("INSTANCE")
                .addMethod(factoryMethod)
                .build();

        type.addType(factoryEnum);

        type.addMethod(MethodSpec.methodBuilder("getFactory")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(factoryReturnType)
                .addStatement("return $T.INSTANCE", factoryTypeName)
                .build());

    }

    MessageGenerator(MessageInfo info) {
        this.info = info;
        info.getFields().forEach(f -> fields.add(new FieldGenerator(f)));
        numBitFields = info.getNumBitFields();

        m.put("abstractMessage", com.diffbot.primibuf.RuntimeClasses.AbstractMessage);
    }

    final MessageInfo info;
    final List<FieldGenerator> fields = new ArrayList<>();
    final int numBitFields;
    final HashMap<String, Object> m = new HashMap<>();

    private CodeBlock named(String format, Object... args /* makes IDE hints disappear */) {
        return CodeBlock.builder().addNamed(format, m).build();
    }

}
