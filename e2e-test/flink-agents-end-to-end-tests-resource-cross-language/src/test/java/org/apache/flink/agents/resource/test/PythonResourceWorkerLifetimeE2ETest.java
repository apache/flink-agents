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

package org.apache.flink.agents.resource.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Native Pemja regression coverage for cached resources outliving managed workers. */
public class PythonResourceWorkerLifetimeE2ETest {

    @TempDir private Path temporaryDirectory;

    @Test
    @Timeout(45)
    public void testCachedPythonResourceOutlivesCreatingWorkerInterpreter() throws Exception {
        Path outputFile = temporaryDirectory.resolve("scenario-output.log");
        Path errorFile = temporaryDirectory.resolve("hs_err_pid%p.log");

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        command.add("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED");
        command.add("-XX:ErrorFile=" + errorFile);
        command.add("-cp");
        command.add(
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(PythonResourceWorkerLifetimeScenario.class.getName());

        Process process =
                new ProcessBuilder(command)
                        .directory(temporaryDirectory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = Files.exists(outputFile) ? Files.readString(outputFile) : "";

        assertThat(finished).as("native lifetime scenario timed out; output:%n%s", output).isTrue();
        assertThat(process.exitValue())
                .as("native lifetime scenario failed; output:%n%s", output)
                .isZero();
        assertThat(output).contains(PythonResourceWorkerLifetimeScenario.SUCCESS_MARKER);
    }
}
