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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class CrossLanguageTestPreparationUtils {
    private static final Logger LOG =
            LoggerFactory.getLogger(CrossLanguageTestPreparationUtils.class);

    public static boolean pullModel(String model) throws IOException {
        String path =
                Objects.requireNonNull(
                                CrossLanguageTestPreparationUtils.class
                                        .getClassLoader()
                                        .getResource("ollama_pull_model.sh"))
                        .getPath();
        ProcessBuilder builder = new ProcessBuilder("bash", path, model);
        Process process = builder.start();
        try {
            process.waitFor(120, TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOG.warn("Pull {} failed, will skip test", model);
        }
        return false;
    }

    public static Process startMCPServer() {
        LOG.info("MCP Server is already running");

        String path =
                Objects.requireNonNull(
                                CrossLanguageTestPreparationUtils.class
                                        .getClassLoader()
                                        .getResource("mcp_server.py"))
                        .getPath();
        ProcessBuilder builder = new ProcessBuilder("python", path);
        builder.redirectErrorStream(true);

        try {
            Process mcpServerProcess = builder.start();
            // Give the server a moment to start up
            Thread.sleep(2000);

            if (mcpServerProcess.isAlive()) {
                LOG.info("MCP Server started successfully with PID: {}", mcpServerProcess.pid());
                return mcpServerProcess;
            } else {
                LOG.warn(
                        "MCP Server process exited immediately with code: {}",
                        mcpServerProcess.exitValue());
                return null;
            }
        } catch (Exception e) {
            LOG.warn("Start MCP Server failed, will skip test", e);
            return null;
        }
    }
}
