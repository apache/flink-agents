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

package org.apache.flink.agents.plan.condition;

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.yaml.YamlLoader;
import org.apache.flink.agents.plan.condition.TriggerCondition.EventTypeCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the YAML loader-to-Plan classification boundary for trigger conditions. */
class YamlTriggerConditionTest {

    @Test
    void classifiesHyphenatedEventType(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("hyphenated-event-type.yaml");
        Files.writeString(
                file,
                "agents:\n"
                        + "  - name: agent\n"
                        + "    actions:\n"
                        + "      - name: route_order\n"
                        + "        function: pkg.actions:route_order\n"
                        + "        trigger_conditions:\n"
                        + "          - order-created\n");

        Agent agent = YamlLoader.buildAgents(file).getAgents().get("agent");
        String source = agent.getActions().get("route_order").f0[0];
        TriggerCondition condition = TriggerCondition.classify(source);

        assertThat(source).isEqualTo("order-created");
        assertThat(condition).isInstanceOf(EventTypeCondition.class);
        assertThat(((EventTypeCondition) condition).eventType()).isEqualTo("order-created");
    }
}
