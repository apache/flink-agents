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
package org.apache.flink.agents.runtime.env;

import org.apache.flink.agents.plan.AgentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.UUID;

import static org.apache.flink.agents.runtime.env.RemoteExecutionEnvironment.FLINK_CONF_FILENAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RemoteExecutionEnvironmentTest {
    @TempDir private File tmpDir;

    @Test
    void testLoadAgentConfiguration() throws FileNotFoundException {
        File confFile = new File(tmpDir, FLINK_CONF_FILENAME);

        try (final PrintWriter pw = new PrintWriter(confFile)) {
            pw.println("agent:");
            pw.println("    key1: ");
            pw.println("        key2: v1");
            pw.println("        key3: 'v2'");
            pw.println("    key4: 1");
            pw.println("    key5: 'v3'");
            pw.println("    key6: 1.5");
            pw.println("    key7: true");
        }

        AgentConfiguration conf =
                RemoteExecutionEnvironment.loadAgentConfiguration(tmpDir.getAbsolutePath());

        assertThat(conf.getConfData().keySet()).hasSize(6);
        assert conf.getConfData().get("key1.key2").equals("v1");
        assert conf.getConfData().get("key1.key3").equals("v2");
        assert conf.getConfData().get("key4").equals(1);
        assert conf.getConfData().get("key5").equals("v3");
        assert conf.getConfData().get("key6").equals(1.5);
        assert conf.getConfData().get("key7").equals(true);
    }

    @Test
    void testLoadAgentConfigurationFailIfNotLoaded() {
        assertThatThrownBy(
                        () ->
                                RemoteExecutionEnvironment.loadAgentConfiguration(
                                        "/some/path/" + UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testLoadAgentConfigurationFailIfNull() {
        AgentConfiguration conf = RemoteExecutionEnvironment.loadAgentConfiguration(null);
        assertThat(conf.getConfData()).isEmpty();
    }
}
