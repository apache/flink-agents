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

package org.apache.flink.agents.runtime.subagent.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process stand-in for the external async-task agent service, speaking the same HTTP protocol on
 * loopback so the integration tests need no service installed anywhere.
 *
 * <p>A run turns terminal after a configured delay measured in wall-clock time, not after a number
 * of probes, which is what lets the tests assert the real polling pacing of both execution modes. A
 * prompt containing {@code "fail"} produces a failed run, and any other prompt echoes the prompt
 * exactly as the offline mock backend of the demo service does.
 *
 * <p>Submissions are idempotent in the {@code task_id} the caller supplies: a repeated submission
 * answers 200 with the existing run instead of starting a second one, which is what the reconcile
 * paths depend on.
 */
final class ExternalAgentStubService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalAgentStubService.class);

    /**
     * The backend name reported by {@code GET /health}, matching the demo service's offline mode.
     */
    static final String LLM_BACKEND = "mock";

    /** A run stays {@code pending} for this long before it starts running. */
    private static final long PENDING_MILLIS = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final long taskDelayMillis;

    private ExternalAgentStubService(HttpServer server, long taskDelayMillis) {
        this.server = server;
        this.taskDelayMillis = taskDelayMillis;
    }

    /** Starts the service on an ephemeral loopback port with the given per-run delay. */
    static ExternalAgentStubService start(long taskDelayMillis) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExternalAgentStubService service = new ExternalAgentStubService(server, taskDelayMillis);
        server.createContext("/health", service::handleHealth);
        server.createContext("/tasks", service::handleTasks);
        server.setExecutor(null);
        server.start();
        LOG.info(
                "external agent stub listening on {} with a {} ms task delay",
                service.baseUrl(),
                taskDelayMillis);
        return service;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        LOG.info("external agent stub stopped after serving {} runs", runs.size());
    }

    // ------------------------------------------------------------------------------------------
    // Endpoints
    // ------------------------------------------------------------------------------------------

    private void handleHealth(HttpExchange exchange) throws IOException {
        ObjectNode body =
                MAPPER.createObjectNode()
                        .put("status", "ok")
                        .put("llm_backend", LLM_BACKEND)
                        .put("task_count", runs.size());
        respond(exchange, 200, body);
    }

    private void handleTasks(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(exchange.getRequestMethod())) {
            handleSubmit(exchange);
        } else if (path.endsWith("/result")) {
            handleResult(exchange, taskIdOf(path, "/result"));
        } else {
            handleStatus(exchange, taskIdOf(path, ""));
        }
    }

    private void handleSubmit(HttpExchange exchange) throws IOException {
        ObjectNode request =
                (ObjectNode)
                        MAPPER.readTree(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
        String prompt = request.path("prompt").asText("");
        String sessionId = request.path("session_id").asText(null);
        String taskId = request.path("task_id").asText(null);
        if (taskId == null || taskId.isEmpty()) {
            respond(exchange, 400, MAPPER.createObjectNode().put("detail", "task_id is required"));
            return;
        }
        Run existing = runs.putIfAbsent(taskId, new Run(prompt, sessionId));
        ObjectNode body =
                MAPPER.createObjectNode()
                        .put("task_id", taskId)
                        .put("status", existing == null ? "pending" : existing.status(now()));
        respond(exchange, existing == null ? 202 : 200, body);
    }

    private void handleStatus(HttpExchange exchange, String taskId) throws IOException {
        Run run = runs.get(taskId);
        if (run == null) {
            respond(exchange, 404, MAPPER.createObjectNode().put("detail", "unknown task"));
            return;
        }
        String status = run.status(now());
        ObjectNode body =
                MAPPER.createObjectNode()
                        .put("task_id", taskId)
                        .put("status", status)
                        .put("session_id", run.sessionId)
                        .put("created_at", run.createdAt)
                        .put("updated_at", now());
        if (ExternalAgentClient.FAILED.equals(status)) {
            body.put("error", run.error());
        } else {
            body.putNull("error");
        }
        respond(exchange, 200, body);
    }

    private void handleResult(HttpExchange exchange, String taskId) throws IOException {
        Run run = runs.get(taskId);
        if (run == null) {
            respond(exchange, 404, MAPPER.createObjectNode().put("detail", "unknown task"));
            return;
        }
        String status = run.status(now());
        if (!ExternalAgentClient.SUCCEEDED.equals(status)
                && !ExternalAgentClient.FAILED.equals(status)) {
            respond(
                    exchange,
                    409,
                    MAPPER.createObjectNode().put("detail", "not finished").put("status", status));
            return;
        }
        ObjectNode body = MAPPER.createObjectNode().put("task_id", taskId).put("status", status);
        if (ExternalAgentClient.FAILED.equals(status)) {
            body.putNull("result").put("error", run.error());
        } else {
            // The service reports the answer as the message list of the finished run.
            body.putNull("error");
            body.putArray("result").add(run.answer());
        }
        respond(exchange, 200, body);
    }

    // ------------------------------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------------------------------

    private static String taskIdOf(String path, String suffix) {
        String trimmed = path.substring("/tasks/".length());
        return suffix.isEmpty()
                ? trimmed
                : trimmed.substring(0, trimmed.length() - suffix.length());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void respond(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** One submitted run, whose state is a pure function of the time since its submission. */
    private final class Run {
        private final String prompt;
        private final String sessionId;
        private final long createdAt = now();

        private Run(String prompt, String sessionId) {
            this.prompt = prompt;
            this.sessionId = sessionId;
        }

        private String status(long now) {
            long elapsed = now - createdAt;
            if (elapsed < PENDING_MILLIS) {
                return ExternalAgentClient.PENDING;
            }
            if (elapsed < taskDelayMillis) {
                return ExternalAgentClient.RUNNING;
            }
            return failing() ? ExternalAgentClient.FAILED : ExternalAgentClient.SUCCEEDED;
        }

        private boolean failing() {
            return prompt.toLowerCase().contains("fail");
        }

        private String answer() {
            return "[offline-mock] echo: " + prompt;
        }

        private String error() {
            return "RuntimeError: mock agent failed on demand";
        }
    }
}
