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

package org.apache.flink.agents.integrations.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.util.HashMap;
import java.util.Map;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link MCPPrompt}. */
class MCPPromptTest {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8000/mcp";

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Create MCPPrompt with required arguments")
    void testCreationWithRequiredArgs() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("name", new MCPPrompt.PromptArgument("name", "User name", true));

        MCPPrompt prompt = new MCPPrompt("greeting", "Greeting prompt", args, server);

        assertThat(prompt.getName()).isEqualTo("greeting");
        assertThat(prompt.getDescription()).isEqualTo("Greeting prompt");
        assertThat(prompt.getPromptArguments()).hasSize(1);
        assertThat(prompt.getMcpServer()).isEqualTo(server);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Create MCPPrompt with optional arguments")
    void testCreationWithOptionalArgs() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("city", new MCPPrompt.PromptArgument("city", "City name", true));
        args.put("units", new MCPPrompt.PromptArgument("units", "Temperature units", false));

        MCPPrompt prompt = new MCPPrompt("weather", "Weather prompt", args, server);

        assertThat(prompt.getPromptArguments()).hasSize(2);
        assertThat(prompt.getPromptArguments().get("city").isRequired()).isTrue();
        assertThat(prompt.getPromptArguments().get("units").isRequired()).isFalse();
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Validate argument handling - required vs optional")
    void testArgumentValidation() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("required", new MCPPrompt.PromptArgument("required", "Required", true));
        args.put("optional", new MCPPrompt.PromptArgument("optional", "Optional", false));

        MCPPrompt prompt = new MCPPrompt("test", "Test prompt", args, server);

        // Verify the prompt was created with correct arguments
        assertThat(prompt.getPromptArguments()).hasSize(2);
        assertThat(prompt.getPromptArguments().get("required").isRequired()).isTrue();
        assertThat(prompt.getPromptArguments().get("optional").isRequired()).isFalse();
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test PromptArgument creation and getters")
    void testPromptArgument() {
        MCPPrompt.PromptArgument arg = new MCPPrompt.PromptArgument("city", "City name", true);

        assertThat(arg.getName()).isEqualTo("city");
        assertThat(arg.getDescription()).isEqualTo("City name");
        assertThat(arg.isRequired()).isTrue();
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test PromptArgument equals and hashCode")
    void testPromptArgumentEquals() {
        MCPPrompt.PromptArgument arg1 = new MCPPrompt.PromptArgument("name", "Name", true);
        MCPPrompt.PromptArgument arg2 = new MCPPrompt.PromptArgument("name", "Name", true);
        MCPPrompt.PromptArgument arg3 = new MCPPrompt.PromptArgument("name", "Different", true);

        assertThat(arg1).isEqualTo(arg2).hasSameHashCodeAs(arg2).isNotEqualTo(arg3);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test MCPPrompt equals and hashCode")
    void testEquals() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("arg1", new MCPPrompt.PromptArgument("arg1", "Arg 1", true));

        MCPPrompt prompt1 = new MCPPrompt("test", "Test", args, server);
        MCPPrompt prompt2 = new MCPPrompt("test", "Test", args, server);
        MCPPrompt prompt3 = new MCPPrompt("other", "Other", args, server);

        assertThat(prompt1).isEqualTo(prompt2).hasSameHashCodeAs(prompt2).isNotEqualTo(prompt3);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Test toString")
    void testToString() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        MCPPrompt prompt = new MCPPrompt("greeting", "Greeting prompt", new HashMap<>(), server);

        String str = prompt.toString();
        assertThat(str).contains("MCPPrompt").contains("greeting").contains(DEFAULT_ENDPOINT);
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("JSON serialization and deserialization")
    void testJsonSerialization() throws Exception {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("topic", new MCPPrompt.PromptArgument("topic", "Topic name", true));
        args.put("style", new MCPPrompt.PromptArgument("style", "Writing style", false));

        MCPPrompt original = new MCPPrompt("essay", "Essay prompt", args, server);

        ObjectMapper mapper = new ObjectMapper();
        // Configure to ignore unknown properties during deserialization
        mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);

        String json = mapper.writeValueAsString(original);

        MCPPrompt deserialized = mapper.readValue(json, MCPPrompt.class);

        assertThat(deserialized.getName()).isEqualTo(original.getName());
        assertThat(deserialized.getDescription()).isEqualTo(original.getDescription());
        assertThat(deserialized.getPromptArguments()).hasSize(original.getPromptArguments().size());
        assertThat(deserialized.getMcpServer()).isEqualTo(original.getMcpServer());
    }

    @Test
    @DisabledOnJre(JRE.JAVA_11)
    @DisplayName("Arguments map is immutable from outside")
    void testArgumentsImmutability() {
        MCPServer server = new MCPServer(DEFAULT_ENDPOINT);
        Map<String, MCPPrompt.PromptArgument> args = new HashMap<>();
        args.put("arg1", new MCPPrompt.PromptArgument("arg1", "Arg 1", true));

        MCPPrompt prompt = new MCPPrompt("test", "Test", args, server);

        // Modify original map
        args.put("arg2", new MCPPrompt.PromptArgument("arg2", "Arg 2", false));

        // Prompt should not be affected
        assertThat(prompt.getPromptArguments()).hasSize(1);
        assertThat(prompt.getPromptArguments()).doesNotContainKey("arg2");

        // Modify returned map
        Map<String, MCPPrompt.PromptArgument> returnedArgs = prompt.getPromptArguments();
        returnedArgs.put("arg3", new MCPPrompt.PromptArgument("arg3", "Arg 3", false));

        // Prompt should not be affected
        assertThat(prompt.getPromptArguments()).hasSize(1);
        assertThat(prompt.getPromptArguments()).doesNotContainKey("arg3");
    }
}
