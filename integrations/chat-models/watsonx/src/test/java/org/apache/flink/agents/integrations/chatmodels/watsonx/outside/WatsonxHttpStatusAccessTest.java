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
package org.apache.flink.agents.integrations.chatmodels.watsonx.outside;

import com.sun.net.httpserver.HttpServer;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.TestResourceContexts;
import org.apache.flink.agents.integrations.chatmodels.watsonx.WatsonxChatModelConnection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Lives outside {@code org.apache.flink.agents.integrations.chatmodels.watsonx} so reflective
 * access matches {@code ChatModelInvoker} rather than sharing package-private visibility.
 */
class WatsonxHttpStatusAccessTest {

    @Test
    void statusCodeAccessorIsReflectivelyInvocableFromAnotherPackage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ml/v1/text/chat",
                exchange -> {
                    byte[] body = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ResourceDescriptor descriptor =
                    ResourceDescriptor.Builder.newBuilder(
                                    WatsonxChatModelConnection.class.getName())
                            .addInitialArgument("url", baseUrl)
                            .addInitialArgument("token", "test-token")
                            .addInitialArgument("project_id", "test-project")
                            .addInitialArgument("max_retries", 0)
                            .build();
            WatsonxChatModelConnection connection =
                    new WatsonxChatModelConnection(
                            descriptor, TestResourceContexts.fromGetResource((a, b) -> null));

            Throwable thrown =
                    catchThrowable(
                            () ->
                                    connection.chat(
                                            List.of(new ChatMessage(MessageRole.USER, "hello")),
                                            List.of(),
                                            Map.of("model", "ibm/granite-3-3-8b-instruct")));

            assertThat(thrown).isInstanceOf(RuntimeException.class);
            assertThat(Modifier.isPublic(thrown.getClass().getModifiers())).isTrue();
            Object status = thrown.getClass().getMethod("statusCode").invoke(thrown);
            assertThat(status).isEqualTo(400);
        } finally {
            server.stop(0);
        }
    }
}
