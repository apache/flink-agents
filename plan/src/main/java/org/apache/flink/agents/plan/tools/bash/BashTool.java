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

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Standalone bash execution tool.
 *
 * <p>Mirrors the Python {@code flink_agents.plan.tools.bash.bash_tool.BashTool}. The framework
 * (e.g. {@code ChatModelAction}) injects {@code allowed_commands} and {@code allowed_script_dirs}
 * at call time; the model only sees {@code command}, {@code timeout} and {@code cwd}.
 */
public class BashTool extends Tool {

    private static final String DESCRIPTION =
            "Execute a shell command. Only commands on the allowed list or scripts under the allowed directories may run.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"command\":{\"type\":\"string\",\"description\":\"The shell command to execute.\"},"
                    + "\"timeout\":{\"type\":\"integer\",\"description\":\"Timeout in seconds. Defaults to 60.\",\"default\":60},"
                    + "\"cwd\":{\"type\":\"string\",\"description\":\"The working directory to run the command in. Defaults to the current directory. Use this instead of `cd` commands.\"}"
                    + "},"
                    + "\"required\":[\"command\"]}";

    public BashTool(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(new ToolMetadata("bash", DESCRIPTION, INPUT_SCHEMA));
        this.resourceContext = resourceContext;
    }

    @Override
    public ToolType getToolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public ToolResponse call(ToolParameters parameters) {
        @SuppressWarnings("unchecked")
        List<String> allowedCommands =
                parameters.hasParameter("allowed_commands")
                        ? (List<String>) parameters.getParameter("allowed_commands")
                        : Collections.emptyList();
        @SuppressWarnings("unchecked")
        List<String> allowedScriptDirs =
                parameters.hasParameter("allowed_script_dirs")
                        ? (List<String>) parameters.getParameter("allowed_script_dirs")
                        : Collections.emptyList();

        String command = parameters.getParameter("command", String.class);
        int timeout =
                parameters.hasParameter("timeout")
                        ? parameters.getParameter("timeout", Integer.class)
                        : 60;
        String cwd =
                parameters.hasParameter("cwd")
                        ? parameters.getParameter("cwd", String.class)
                        : null;

        if (cwd != null && !BashValidator.isUnderAllowedDirs(cwd, allowedScriptDirs, null)) {
            List<String> sorted = new ArrayList<>(allowedScriptDirs);
            Collections.sort(sorted);
            return ToolResponse.success(
                    "Command rejected: cwd '"
                            + cwd
                            + "' is not under any allowed script dir. Allowed script dirs: "
                            + sorted
                            + ".");
        }

        Optional<String> error =
                BashValidator.validate(command, allowedCommands, allowedScriptDirs, cwd);
        if (error.isPresent()) {
            return ToolResponse.success("Command rejected: " + error.get());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            if (cwd != null) {
                pb.directory(new File(cwd));
            }
            Process process = pb.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            // Drain output streams to avoid blocking on pipe buffer fill.
            Thread tOut = drainAsync(process.getInputStream(), stdout);
            Thread tErr = drainAsync(process.getErrorStream(), stderr);
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResponse.success(
                        "Error: Command timed out after " + timeout + " seconds");
            }
            tOut.join();
            tErr.join();
            int exit = process.exitValue();
            String stdoutStr = new String(stdout.toByteArray(), StandardCharsets.UTF_8).strip();
            String stderrStr = new String(stderr.toByteArray(), StandardCharsets.UTF_8).strip();
            if (exit == 0) {
                return ToolResponse.success(stdoutStr.isEmpty() ? "Success" : stdoutStr);
            }
            return ToolResponse.success("Error (exit code " + exit + "): " + stderrStr);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ToolResponse.success("Error: " + e.getMessage());
        }
    }

    private static Thread drainAsync(InputStream stream, ByteArrayOutputStream sink) {
        Thread t =
                new Thread(
                        () -> {
                            try (InputStream in = stream) {
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    sink.write(buf, 0, n);
                                }
                            } catch (IOException ignored) {
                                // process exit closes stream
                            }
                        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
