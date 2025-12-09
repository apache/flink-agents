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
package org.apache.flink.agents.integration.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class OllamaPreparationUtils {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaPreparationUtils.class);

    public static boolean pullModel(String model) throws IOException {
        String path =
                Objects.requireNonNull(
                                ChatModelIntegrationTest.class
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
}
