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

class YamlAgentsDocumentTest {
    private static final ObjectMapper M = new ObjectMapper(new YAMLFactory());

    @Test
    void singleAgentOnly() throws Exception {
        YamlAgentsDocument doc = M.readValue("agents:\n  - name: a\n", YamlAgentsDocument.class);
        assertThat(doc.getAgents()).hasSize(1);
        assertThat(doc.getActions()).isEmpty();
        assertThat(doc.getChatModelConnections()).isEmpty();
    }

    @Test
    void sharedSectionsAtFileLevel() throws Exception {
        String yaml =
                "agents:\n  - name: a\n"
                        + "chat_model_connections:\n  - name: shared\n    clazz: x.Y\n"
                        + "actions:\n  - name: shared_a\n    function: pkg:fn\n"
                        + "    trigger_conditions: [input]\n";
        YamlAgentsDocument doc = M.readValue(yaml, YamlAgentsDocument.class);
        assertThat(doc.getChatModelConnections()).hasSize(1);
        assertThat(doc.getActions()).hasSize(1);
        assertThat(doc.getActions().get(0).getName()).isEqualTo("shared_a");
    }

    @Test
    void infraOnlyFile() throws Exception {
        YamlAgentsDocument doc =
                M.readValue(
                        "chat_model_connections:\n  - name: x\n    clazz: ollama\n",
                        YamlAgentsDocument.class);
        assertThat(doc.getAgents()).isEmpty();
        assertThat(doc.getChatModelConnections()).hasSize(1);
    }
}
