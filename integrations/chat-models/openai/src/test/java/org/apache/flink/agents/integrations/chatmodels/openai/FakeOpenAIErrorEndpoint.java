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
import java.util.concurrent.atomic.AtomicReference;

/**
 * A loopback HTTP endpoint that answers every request with the provider error envelope OpenAI
 * returns for a rejected request, so a connection's error path can be exercised without a live API
 * call. A 400 is chosen because the SDK does not retry it, which keeps the exchange to a single
 * request.
 */
final class FakeOpenAIErrorEndpoint implements AutoCloseable {

    static final String ERROR_MESSAGE = "The requested model does not exist.";
    static final String ERROR_CODE = "model_not_found";

    private static final String ERROR_BODY =
            "{\"error\":{\"message\":\""
                    + ERROR_MESSAGE
                    + "\",\"type\":\"invalid_request_error\","
                    + "\"param\":\"model\",\"code\":\""
                    + ERROR_CODE
                    + "\"}}";

    private final HttpServer server;
    private final AtomicReference<String> requestBody;

    private FakeOpenAIErrorEndpoint(HttpServer server, AtomicReference<String> requestBody) {
        this.server = server;
        this.requestBody = requestBody;
    }

    static FakeOpenAIErrorEndpoint rejectingWith400() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = ERROR_BODY.getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext(
                "/",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.setExecutor(null);
        server.start();
        return new FakeOpenAIErrorEndpoint(server, requestBody);
    }

    String requestBody() {
        return requestBody.get();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
