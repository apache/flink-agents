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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MCP prompt definition that extends the base Prompt class.
 *
 * <p>This represents a prompt managed by an MCP server. Unlike static prompts, MCP prompts are
 * fetched dynamically from the server and can accept arguments.
 */
public class MCPPrompt extends Prompt {

    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_ARGUMENTS = "arguments";
    private static final String FIELD_MCP_SERVER = "mcpServer";

    @JsonProperty(FIELD_NAME)
    private final String name;

    @JsonProperty(FIELD_DESCRIPTION)
    private final String description;

    @JsonProperty(FIELD_ARGUMENTS)
    private final Map<String, PromptArgument> promptArguments;

    @JsonProperty(FIELD_MCP_SERVER)
    private final MCPServer mcpServer;

    /** Represents an argument that can be passed to an MCP prompt. */
    public static class PromptArgument {
        @JsonProperty("name")
        private final String name;

        @JsonProperty("description")
        private final String description;

        @JsonProperty("required")
        private final boolean required;

        @JsonCreator
        public PromptArgument(
                @JsonProperty("name") String name,
                @JsonProperty("description") String description,
                @JsonProperty("required") boolean required) {
            this.name = Objects.requireNonNull(name, "name cannot be null");
            this.description = description;
            this.required = required;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public boolean isRequired() {
            return required;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PromptArgument that = (PromptArgument) o;
            return required == that.required
                    && Objects.equals(name, that.name)
                    && Objects.equals(description, that.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, description, required);
        }
    }

    /**
     * Create a new MCPPrompt.
     *
     * @param name The prompt name
     * @param description The prompt description
     * @param promptArguments Map of argument names to argument definitions
     * @param mcpServer The MCP server reference
     */
    @JsonCreator
    public MCPPrompt(
            @JsonProperty(FIELD_NAME) String name,
            @JsonProperty(FIELD_DESCRIPTION) String description,
            @JsonProperty(FIELD_ARGUMENTS) Map<String, PromptArgument> promptArguments,
            @JsonProperty(FIELD_MCP_SERVER) MCPServer mcpServer) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = description;
        this.promptArguments =
                promptArguments != null ? new HashMap<>(promptArguments) : new HashMap<>();
        this.mcpServer = Objects.requireNonNull(mcpServer, "mcpServer cannot be null");
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, PromptArgument> getPromptArguments() {
        return new HashMap<>(promptArguments);
    }

    @JsonIgnore
    public MCPServer getMcpServer() {
        return mcpServer;
    }

    /**
     * Format the prompt as a string with the given arguments. Overrides the base Prompt class to
     * fetch prompts from the MCP server.
     *
     * @param arguments Arguments to pass to the prompt (String keys and values)
     * @return The formatted prompt as a string
     */
    @Override
    public String formatString(Map<String, String> arguments) {
        List<ChatMessage> messages = formatMessages(MessageRole.SYSTEM, arguments);
        return messages.stream()
                .map(msg -> msg.getRole().getValue() + ": " + msg.getContent())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Format the prompt as a list of chat messages with the given arguments. Overrides the base
     * Prompt class to fetch prompts from the MCP server.
     *
     * @param defaultRole The default role for messages (usually SYSTEM)
     * @param kwargs Arguments to pass to the prompt (String keys and values)
     * @return List of formatted chat messages
     */
    @Override
    public List<ChatMessage> formatMessages(MessageRole defaultRole, Map<String, String> kwargs) {
        Map<String, Object> objectArgs = new HashMap<>(kwargs);
        return formatMessages(objectArgs);
    }

    /**
     * Format the prompt as a list of chat messages with the given arguments.
     *
     * @param arguments Arguments to pass to the prompt (Object values)
     * @return List of formatted chat messages
     */
    private List<ChatMessage> formatMessages(Map<String, Object> arguments) {
        return mcpServer.getPrompt(name, validateAndPrepareArguments(arguments));
    }

    /**
     * Validate that all required arguments are present and prepare the arguments map.
     *
     * @param arguments The provided arguments
     * @return A validated map of arguments
     * @throws IllegalArgumentException if required arguments are missing
     */
    private Map<String, Object> validateAndPrepareArguments(Map<String, Object> arguments) {
        Map<String, Object> result = new HashMap<>();

        for (PromptArgument arg : promptArguments.values()) {
            if (arg.isRequired()) {
                if (arguments == null || !arguments.containsKey(arg.getName())) {
                    throw new IllegalArgumentException(
                            "Missing required argument: " + arg.getName());
                }
                result.put(arg.getName(), arguments.get(arg.getName()));
            } else if (arguments != null && arguments.containsKey(arg.getName())) {
                result.put(arg.getName(), arguments.get(arg.getName()));
            }
        }

        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MCPPrompt mcpPrompt = (MCPPrompt) o;
        return Objects.equals(name, mcpPrompt.name)
                && Objects.equals(description, mcpPrompt.description)
                && Objects.equals(promptArguments, mcpPrompt.promptArguments)
                && Objects.equals(mcpServer, mcpPrompt.mcpServer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, promptArguments, mcpServer);
    }

    @Override
    public String toString() {
        return String.format("MCPPrompt{name='%s', server='%s'}", name, mcpServer.getEndpoint());
    }
}
