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

import com.diffbot.primibuf.RequestInfo.FileInfo;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Protoc plugin that gets called by the protoc executable. The communication happens
 * via protobuf messages on System.in / System.out
 *
 * @author Florian Enner
 * @since 05 Aug 2019
 */
public class CompilerPlugin {

    /**
     * The protoc-gen-plugin communicates via proto messages on System.in and System.out
     *
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        handleRequest(System.in).writeTo(System.out);
    }

    static CodeGeneratorResponse handleRequest(InputStream input) throws IOException {
        try {
            return handleRequest(CodeGeneratorRequest.parseFrom(input));
        } catch (GeneratorException ge) {
            return ParserUtil.asError(ge.getMessage());
        } catch (Exception ex) {
            return ParserUtil.asErrorWithStackTrace(ex);
        }
    }

    static CodeGeneratorResponse handleRequest(CodeGeneratorRequest requestProto) {
        CodeGeneratorResponse.Builder response = CodeGeneratorResponse.newBuilder();
        RequestInfo request = RequestInfo.withTypeRegistry(requestProto);

        for (FileInfo file : request.getFiles()) {
            if (file.isGenerateMultipleFiles()) {
                throw new RuntimeException("Currently we only support generating code in a single file");
            }

            // Generate type specifications
            List<TypeSpec> topLevelTypes = new ArrayList<>();
            TypeSpec.Builder outerClassSpec = TypeSpec.classBuilder(file.getOuterClassName())
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
            Consumer<TypeSpec> list = file.isGenerateMultipleFiles() ? topLevelTypes::add : outerClassSpec::addType;

            for (RequestInfo.EnumInfo type : file.getEnumTypes()) {
                list.accept(new EnumGenerator(type).generate());
            }

            for (RequestInfo.MessageInfo type : file.getMessageTypes()) {
                list.accept(new MessageGenerator(type).generate());
            }

            // Omit completely empty outer classes
            if (!file.isGenerateMultipleFiles()) {
                topLevelTypes.add(outerClassSpec.build());
            }

            // Generate Java files
            for (TypeSpec typeSpec : topLevelTypes) {

                JavaFile javaFile = JavaFile.builder(file.getJavaPackage(), typeSpec)
                        .addFileComment("Code generated by protocol buffer compiler. Do not edit!")
                        .indent(request.getIndentString())
                        .skipJavaLangImports(true)
                        .build();

                StringBuilder content = new StringBuilder(1000);
                try {
                    javaFile.writeTo(content);
                } catch (IOException e) {
                    throw new AssertionError("Could not write to StringBuilder?");
                }

                response.addFile(CodeGeneratorResponse.File.newBuilder()
                        .setName(file.getOutputDirectory() + typeSpec.name + ".java")
                        .setContent(content.toString())
                        .build());
            }

        }

        return response.build();

    }

}
