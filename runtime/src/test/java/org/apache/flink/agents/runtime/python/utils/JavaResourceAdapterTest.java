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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flink.agents.runtime.python.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.tools.ToolParameterSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class JavaResourceAdapterTest {

    @Test
    void buildsJavaChatResultFromPythonAssistantMessage() {
        JavaResourceAdapter adapter =
                new JavaResourceAdapter(null, Thread.currentThread().getContextClassLoader());
        ChatResult response =
                adapter.fromPythonChatResult(
                        Map.of(
                                "message",
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "metadata",
                                                Map.of("turn", 1),
                                                "blocks",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "reasoning",
                                                                "text",
                                                                "private"),
                                                        Map.of("type", "text", "text", "answer"),
                                                        Map.of(
                                                                "type",
                                                                "tool_call",
                                                                "call_id",
                                                                "provider-id",
                                                                "name",
                                                                "tool",
                                                                "input",
                                                                Map.of("x", 1)),
                                                        Map.of(
                                                                "type",
                                                                "image",
                                                                "media_type",
                                                                "image/png",
                                                                "source",
                                                                Map.of(
                                                                        "type", "base64", "data",
                                                                        "aGk=")))),
                                "response_id", "response-id",
                                "usage", Map.of("prompt_tokens", 0),
                                "finish_reason", "tool_calls",
                                "metadata", Map.of("opaque", List.of(1))));
        assertThat(response.getText()).isEqualTo("answer");
        assertThat(response.getMessage().getBlocks()).hasSize(4);
        assertThat(response.getMessage().getBlocks().get(3)).isInstanceOf(ImageBlock.class);
        assertThat(response.getToolCalls().get(0).getCallId()).isEqualTo("provider-id");
        assertThat(response.getUsage().getPromptTokens()).isZero();
        assertThat(response.getUsage().getCompletionTokens()).isNull();
        assertThat(response.getMetadata()).isEqualTo(Map.of("opaque", List.of(1)));
        assertThat(response.getResponseId()).isEqualTo("response-id");
        assertThat(response.toMap()).containsKey("message").doesNotContainKeys("blocks", "role");
    }

    @Test
    void buildsJavaChatMessageFromExtractedPythonFields() {
        JavaResourceAdapter adapter =
                new JavaResourceAdapter(null, Thread.currentThread().getContextClassLoader());
        List<Map<String, Object>> toolCalls = List.of(Map.of("id", "call-1", "type", "function"));
        Map<String, Object> extraArgs = Map.of("reasoning", "brief");

        List<Map<String, Object>> blocks =
                List.of(
                        Map.of("type", "text", "text", "hello"),
                        Map.of(
                                "type",
                                "image",
                                "media_type",
                                "image/png",
                                "source",
                                Map.of("type", "base64", "data", "aGk=")));
        ChatMessage converted =
                adapter.fromPythonChatMessage(
                        Map.of("role", "user", "blocks", blocks, "metadata", extraArgs));

        assertThat(converted.getRole()).isEqualTo(MessageRole.USER);
        assertThat(converted.getText()).isEqualTo("hello");
        assertThat(converted.getBlocks()).hasSize(2);
        assertThat(converted.getBlocks().get(1)).isInstanceOf(ImageBlock.class);
        assertThat(converted.getToolCalls()).isEmpty();
        assertThat(converted.getMetadata()).isEqualTo(extraArgs);
    }

    @Test
    void getJavaToolMetadataHidesInjectedArgsAndReturnsAnnotatedDeclaration() throws Exception {
        JavaResourceAdapter adapter =
                new JavaResourceAdapter(null, Thread.currentThread().getContextClassLoader());

        Map<String, String> metadata =
                adapter.getJavaToolMetadata(
                        JavaResourceAdapterTest.class.getName(),
                        "queryOrder",
                        List.of(
                                String.class.getName(),
                                String.class.getName(),
                                String.class.getName()),
                        List.of("request_id"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(metadata.get("inputSchema"));
        assertThat(schema.get("properties").has("order_id")).isTrue();
        assertThat(schema.get("properties").has("tenant_id")).isFalse();
        assertThat(schema.get("properties").has("request_id")).isFalse();

        JsonNode injectedArgs = mapper.readTree(metadata.get("injectedArgs"));
        assertThat(injectedArgs.get("tenant_id").get("source").asText()).isEqualTo("config");
        assertThat(injectedArgs.get("tenant_id").get("key").asText()).isEqualTo("tenant.id");
    }

    @Test
    void invokeJavaToolPreservesSuccessResponseForPythonCaller() throws Exception {
        JavaResourceAdapter adapter =
                new JavaResourceAdapter(null, Thread.currentThread().getContextClassLoader());

        Map<String, Object> result =
                adapter.invokeJavaTool(
                        JavaResourceAdapterTest.class.getName(),
                        "queryOrder",
                        List.of(
                                String.class.getName(),
                                String.class.getName(),
                                String.class.getName()),
                        Map.of(
                                "order_id",
                                "order-1",
                                "tenant_id",
                                "tenant-1",
                                "request_id",
                                "request-1"));

        assertThat(result)
                .containsEntry("__flink_agents_tool_result__", "response")
                .containsEntry("success", true)
                .containsEntry("result", "tenant-1:request-1:order-1")
                .containsEntry("execution_time_ms", 0L);
        assertThat(result.get("error")).isNull();
    }

    @Test
    void invokeJavaToolPreservesErrorResponseForPythonCaller() throws Exception {
        JavaResourceAdapter adapter =
                new JavaResourceAdapter(null, Thread.currentThread().getContextClassLoader());

        Map<String, Object> result =
                adapter.invokeJavaTool(
                        JavaResourceAdapterTest.class.getName(),
                        "failingTool",
                        List.of(String.class.getName()),
                        Map.of("value", "input"));

        assertThat(result)
                .containsEntry("__flink_agents_tool_result__", "response")
                .containsEntry("success", false)
                .containsEntry("error", "tool rejected input")
                .containsEntry("execution_time_ms", 0L);
        assertThat(result.get("result")).isNull();
    }

    @Tool(description = "Query order.")
    public static String queryOrder(
            @ToolParam(name = "order_id") String orderId,
            @ToolParam(
                            name = "tenant_id",
                            injected = true,
                            source = ToolParameterSource.CONFIG,
                            key = "tenant.id")
                    String tenantId,
            @ToolParam(name = "request_id") String requestId) {
        return tenantId + ":" + requestId + ":" + orderId;
    }

    @Tool(description = "Fail a tool call.")
    public static String failingTool(@ToolParam(name = "value") String value) {
        throw new IllegalStateException("tool rejected " + value);
    }

    @Test
    void toolBridgePreservesTypedResultBlocks() throws Exception {
        JavaResourceAdapter adapter =
                new JavaResourceAdapter(null, Thread.currentThread().getContextClassLoader());
        Map<String, Object> wire =
                adapter.invokeJavaTool(
                        JavaResourceAdapterTest.class.getName(),
                        "mediaResultTool",
                        List.of(),
                        Map.of());
        org.apache.flink.agents.api.tools.ToolResponse restored =
                org.apache.flink.agents.plan.resource.python.PythonToolResultConverter
                        .fromBridgeResult(wire);
        assertThat(restored.getBlocks()).hasSize(2);
        assertThat(restored.getBlocks().get(1)).isInstanceOf(ImageBlock.class);
        assertThat(restored.toResultBlock("id").getText()).isEqualTo("visible");
    }

    @Tool(description = "Return explicit media content.")
    public static org.apache.flink.agents.api.tools.ToolResponse mediaResultTool() {
        return org.apache.flink.agents.api.tools.ToolResponse.success(
                        Map.of("internal", "diagnostics"))
                .withBlocks(
                        List.of(
                                new org.apache.flink.agents.api.chat.messages.TextBlock("visible"),
                                ImageBlock.fromBase64("image/png", "aGk=")));
    }
}
