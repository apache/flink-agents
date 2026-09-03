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

package org.apache.flink.agents.integrations.chatmodels.openai;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A loopback HTTP endpoint that answers every request with a successful chat completion, so a
 * connection's response-handling path can be exercised without a live API call. The choice's {@code
 * finish_reason} and the top-level {@code usage} block are chosen per instance, including the
 * shapes that carry no finish reason at all: the member set to JSON null, and the member left out
 * of the choice entirely.
 */
final class FakeOpenAICompletionsEndpoint implements AutoCloseable {

    private static final String CONTENT = "hi";

    private static final String NO_FINISH_REASON_MEMBER = "";

    private static final String USAGE_MEMBER =
            ",\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}";

    private final HttpServer server;

    private FakeOpenAICompletionsEndpoint(HttpServer server) {
        this.server = server;
    }

    static FakeOpenAICompletionsEndpoint servingFinishReason(String finishReason)
            throws IOException {
        return serving(finishReasonMember("\"" + finishReason + "\""), USAGE_MEMBER);
    }

    static FakeOpenAICompletionsEndpoint servingFinishReasonWithoutUsage(String finishReason)
            throws IOException {
        return serving(finishReasonMember("\"" + finishReason + "\""), "");
    }

    static FakeOpenAICompletionsEndpoint servingNullFinishReason() throws IOException {
        return serving(finishReasonMember("null"), USAGE_MEMBER);
    }

    static FakeOpenAICompletionsEndpoint servingNoFinishReasonMember() throws IOException {
        return serving(NO_FINISH_REASON_MEMBER, USAGE_MEMBER);
    }

    private static String finishReasonMember(String value) {
        return "\"finish_reason\":" + value + ",";
    }

    private static FakeOpenAICompletionsEndpoint serving(
            String finishReasonMember, String usageMember) throws IOException {
        String completion =
                "{\"id\":\"completion-1\",\"object\":\"chat.completion\",\"created\":0,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{"
                        + finishReasonMember
                        + "\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                        + CONTENT
                        + "\"}}]"
                        + usageMember
                        + "}";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = completion.getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        return new FakeOpenAICompletionsEndpoint(server);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
