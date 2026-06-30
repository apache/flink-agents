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

package org.apache.flink.agents.api.yaml.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSpecTest {
    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void minimalAgentParses() throws Exception {
        AgentSpec spec = M.readValue("name: a\n", AgentSpec.class);
        assertThat(spec.getName()).isEqualTo("a");
        assertThat(spec.getActions()).isEmpty();
        assertThat(spec.getTools()).isEmpty();
        assertThat(spec.getChatModelConnections()).isEmpty();
    }

    @Test
    void actionEntryCanBeStringOrFullSpec() throws Exception {
        String yaml =
                "name: a\n"
                        + "actions:\n"
                        + "  - shared_one\n"
                        + "  - name: own\n"
                        + "    function: pkg:fn\n"
                        + "    trigger_conditions: [input]\n";
        AgentSpec spec = M.readValue(yaml, AgentSpec.class);
        assertThat(spec.getActions()).hasSize(2);
        assertThat(spec.getActions().get(0).isReference()).isTrue();
        assertThat(spec.getActions().get(0).getReference()).isEqualTo("shared_one");
        assertThat(spec.getActions().get(1).isReference()).isFalse();
        assertThat(spec.getActions().get(1).getSpec().getName()).isEqualTo("own");
    }

    @Test
    void descriptorSectionsParse() throws Exception {
        String yaml =
                "name: a\n"
                        + "chat_model_connections:\n"
                        + "  - name: c1\n"
                        + "    clazz: ollama\n";
        AgentSpec spec = M.readValue(yaml, AgentSpec.class);
        assertThat(spec.getChatModelConnections()).hasSize(1);
        assertThat(spec.getChatModelConnections().get(0).getName()).isEqualTo("c1");
    }
}
