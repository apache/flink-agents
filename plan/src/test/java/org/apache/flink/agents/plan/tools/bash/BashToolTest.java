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

package org.apache.flink.agents.plan.tools.bash;

import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolTest {

    @ParameterizedTest
    @ValueSource(strings = {"pwd", "echo ok | pwd", "echo ok && pwd", "run.sh", ".run.sh"})
    void cwdDoesNotAllowBareCommand(String command, @TempDir Path tempDir) throws IOException {
        for (boolean existingFile : List.of(false, true)) {
            if (existingFile) {
                for (String name : List.of("pwd", "run.sh", ".run.sh")) {
                    Files.createFile(tempDir.resolve(name));
                }
            }
            String result =
                    tool().call(
                                    new ToolParameters(
                                            Map.of(
                                                    "command", command,
                                                    "cwd", tempDir.toString(),
                                                    "allowed_commands", List.of("echo"),
                                                    "allowed_script_dirs",
                                                            List.of(tempDir.toString()))))
                            .getText();
            assertTrue(result.startsWith("Command rejected:"), result);
            assertTrue(result.contains("is not allowed"), result);
        }
    }

    @Test
    void cwdAllowsExplicitScriptPaths(@TempDir Path tempDir) throws IOException {
        Path script = tempDir.resolve("scripts/run_task-2.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/bin/sh\necho \"script-ok:$1\"\n");
        assertTrue(script.toFile().setExecutable(true));
        for (String command :
                List.of("./scripts/run_task-2.sh", "scripts/run_task-2.sh", script.toString())) {
            assertEquals(
                    "script-ok:argument-ok",
                    tool().call(
                                    new ToolParameters(
                                            Map.of(
                                                    "command",
                                                            command
                                                                    + " \"${FLINK_BASH_TEST_UNSET:-argument-ok}\"",
                                                    "cwd", tempDir.toString(),
                                                    "allowed_commands", List.of(),
                                                    "allowed_script_dirs",
                                                            List.of(tempDir.toString()))))
                            .getText());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "${FLINK_BASH_TEST_UNSET:+/}pwd",
                "${FLINK_BASH_TEST_UNSET:-/bin/pwd}",
                "./$SCRIPT",
                "./*.sh",
                "./run?.sh",
                "./[r]un.sh",
                "./{run,other}.sh",
                "~/run.sh",
                "'./run.sh'",
                "\"./run.sh\"",
                "./r\"un\".sh",
                "./run\\ script.sh"
            })
    void nonliteralExecutableRejected(String command, @TempDir Path tempDir) throws IOException {
        String cwd = tempDir.toRealPath().toString();
        String result =
                tool().call(
                                new ToolParameters(
                                        Map.of(
                                                "command",
                                                command,
                                                "cwd",
                                                cwd,
                                                "allowed_commands",
                                                List.of(),
                                                "allowed_script_dirs",
                                                List.of(cwd))))
                        .getText();
        assertTrue(result.startsWith("Command rejected:"), result);
    }

    @Test
    void cwdAllowsWhitelistedCommand(@TempDir Path tempDir) {
        assertEquals(
                "hello",
                tool().call(
                                new ToolParameters(
                                        Map.of(
                                                "command", "echo hello",
                                                "cwd", tempDir.toString(),
                                                "allowed_commands", List.of("echo"),
                                                "allowed_script_dirs",
                                                        List.of(tempDir.toString()))))
                        .getText());
    }

    @Test
    void cwdRejectsScriptOutsideAllowedDir(@TempDir Path tempDir) throws IOException {
        Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
        Path outside = tempDir.resolve("outside.sh");
        Files.writeString(outside, "#!/bin/sh\necho outside\n");
        assertTrue(outside.toFile().setExecutable(true));
        Files.createSymbolicLink(allowed.resolve("link.sh"), outside);
        for (String command : List.of("../outside.sh", "./link.sh", outside.toString())) {
            String result =
                    tool().call(
                                    new ToolParameters(
                                            Map.of(
                                                    "command", command,
                                                    "cwd", allowed.toString(),
                                                    "allowed_commands", List.of(),
                                                    "allowed_script_dirs",
                                                            List.of(allowed.toString()))))
                            .getText();
            assertTrue(result.startsWith("Command rejected:"), result);
        }
    }

    private static BashTool tool() {
        return new BashTool(
                new ResourceDescriptor(BashTool.class.getName(), Map.of()),
                TestResourceContexts.fromGetResource((n, t) -> null));
    }

    private static ToolParameters args(
            String command, List<String> allowedCommands, List<String> allowedScriptDirs) {
        Map<String, Object> m = new HashMap<>();
        m.put("command", command);
        m.put("allowed_commands", allowedCommands);
        m.put("allowed_script_dirs", allowedScriptDirs);
        return new ToolParameters(m);
    }

    @Test
    void allowedSimpleCommandRuns() {
        ToolResponse r = tool().call(args("echo hello", List.of("echo"), List.of()));
        assertEquals("hello", r.getText());
    }

    @Test
    void disallowedCommandRejected() {
        ToolResponse r = tool().call(args("rm -rf /", List.of("echo"), List.of()));
        String out = r.getText();
        assertTrue(out.startsWith("Command rejected:"));
        assertTrue(out.contains("'rm' is not allowed"));
    }

    @Test
    void controlFlowRejected() {
        ToolResponse r =
                tool().call(args("for i in 1 2 3; do echo $i; done", List.of("echo"), List.of()));
        String out = r.getText();
        assertTrue(out.startsWith("Command rejected:"));
    }

    @Test
    void integerDeclarationCannotReevaluateCommandSubstitution(@TempDir Path tempDir) {
        Path marker = tempDir.resolve("integer-declaration-marker");
        ToolResponse r =
                tool().call(
                                args(
                                        "declare -i VALUE='$(touch " + marker + ")'",
                                        List.of(),
                                        List.of()));
        String out = r.getText();
        assertTrue(out.startsWith("Command rejected:"));
        assertFalse(marker.toFile().exists());
    }

    @Test
    void arithmeticExpansionCannotReevaluateAssignedValue(@TempDir Path tempDir) {
        Path marker = tempDir.resolve("arithmetic-expansion-marker");
        ToolResponse r =
                tool().call(
                                args(
                                        "VALUE='$(touch " + marker + ")'; echo $((VALUE))",
                                        List.of("echo"),
                                        List.of()));
        String out = r.getText();
        assertTrue(out.startsWith("Command rejected:"));
        assertFalse(marker.toFile().exists());
    }

    @Test
    void successfulCommandWithEmptyOutput() {
        ToolResponse r = tool().call(args("true", List.of("true"), List.of()));
        assertEquals("Success", r.getText());
    }

    @Test
    void interruptionPropagatesInsteadOfReturningToolResult() {
        Thread.currentThread().interrupt();
        try {
            CancellationException failure =
                    assertThrows(
                            CancellationException.class,
                            () -> tool().call(args("sleep 1", List.of("sleep"), List.of())));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void timeoutEnforced() {
        Map<String, Object> m = new HashMap<>();
        m.put("command", "sleep 5");
        m.put("allowed_commands", List.of("sleep"));
        m.put("allowed_script_dirs", List.of());
        m.put("timeout", 1);
        ToolResponse r = tool().call(new ToolParameters(m));
        String out = r.getText();
        assertTrue(out.startsWith("Error: Command timed out"));
    }
}
