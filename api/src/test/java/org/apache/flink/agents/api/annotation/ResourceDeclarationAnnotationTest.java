/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.api.annotation;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks declaration targets through the Java compiler used by agent authors. */
class ResourceDeclarationAnnotationTest {

    @TempDir private Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"ChatModelSetup", "Prompt", "VectorStore"})
    void acceptsMethodAndRejectsField(String annotation) throws Exception {
        String returnType =
                annotation.equals("Prompt")
                        ? "org.apache.flink.agents.api.prompt.Prompt"
                        : "org.apache.flink.agents.api.resource.ResourceDescriptor";
        assertCompiles(
                annotation, "public static " + returnType + " resource() { return null; }", true);
        assertCompiles(annotation, "public static " + returnType + " resource;", false);
    }

    private void assertCompiles(String annotation, String declaration, boolean expected)
            throws Exception {
        Path source = temporaryDirectory.resolve("TestAgent.java");
        Files.writeString(
                source,
                "import org.apache.flink.agents.api.annotation."
                        + annotation
                        + ";\npublic class TestAgent { @"
                        + annotation
                        + " "
                        + declaration
                        + " }");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Tests require a JDK").isNotNull();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean compiled =
                    compiler.getTask(
                                    null,
                                    files,
                                    diagnostics,
                                    List.of(
                                            "-proc:none",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            temporaryDirectory.toString()),
                                    null,
                                    files.getJavaFileObjects(source.toFile()))
                            .call();
            assertThat(compiled)
                    .as("%s: %s", annotation, diagnostics.getDiagnostics())
                    .isEqualTo(expected);
            if (!expected) {
                assertThat(diagnostics.getDiagnostics())
                        .anySatisfy(
                                diagnostic -> {
                                    assertThat(diagnostic.getKind())
                                            .isEqualTo(Diagnostic.Kind.ERROR);
                                    assertThat(diagnostic.getCode())
                                            .isEqualTo(
                                                    "compiler.err.annotation.type.not.applicable");
                                });
            }
        }
    }
}
