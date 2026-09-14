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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.agents.api.subagent.SubagentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Minimal HTTP client for the external async-task agent demo service. The service assigns its own
 * {@code task_id} per submission, so the setups keep their own {@code (sessionId, callId)} to
 * {@code task_id} mapping.
 *
 * <p>Protocol contract:
 *
 * <pre>{@code
 * POST /tasks              -> 202 {"task_id", "status": "pending"} when a new task is created;
 *                             200 {"task_id", "status"} when the idempotency key already exists
 *                             body: {"prompt", "session_id", "task_id" (optional idempotency key)}
 * GET  /tasks/{id}         -> 200 {"task_id", "status", "session_id", "created_at",
 *                             "updated_at", "error"}; 404 when the task is unknown
 * GET  /tasks/{id}/result  -> 200 {"task_id", "status", "result", "error"} once terminal;
 *                             409 {"detail", "status"} while not finished;
 *                             404 when the task is unknown
 * GET  /health             -> 200 {"status": "ok", "llm_backend", "task_count"}
 * }</pre>
 *
 * <p>Status lifecycle: {@code pending} {@literal ->} {@code running} {@literal ->} {@code
 * succeeded} | {@code failed}. {@code error} carries {@code "ExceptionType: message"} on failure.
 *
 * <p>The {@code task_id} body field is the idempotency key: resubmitting the same key returns the
 * existing task instead of creating a duplicate, so a reconciled POST after a crash-window never
 * starts a second run. {@link #taskIdFor} derives that key deterministically from the {@code
 * (sessionId, callId)} pair, which makes every remote task traceable across failovers without any
 * client-side cache.
 */
public class ExternalAgentClient {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalAgentClient.class);

    /** Lifecycle status values reported by the service. */
    public static final String PENDING = "pending";

    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExternalAgentClient(String baseUrl) {
        this.baseUrl = baseUrl;
        // HTTP/1.1 only: the demo service (uvicorn without websockets) warns on h2c upgrades.
        this.http =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
    }

    /** True when {@code GET /health} answers 200. */
    public boolean reachable() {
        try {
            HttpResponse<String> response = send(builder("/health").GET().build(), "GET /health");
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** The {@code llm_backend} reported by {@code GET /health}. */
    public String llmBackend() throws Exception {
        JsonNode body = parse(expect(get("/health"), 200, "/health"));
        return body.get("llm_backend").asText();
    }

    /** The {@code task_count} reported by {@code GET /health}. */
    public int taskCount() throws Exception {
        JsonNode body = parse(expect(get("/health"), 200, "/health"));
        return body.get("task_count").asInt();
    }

    /**
     * A per-JVM namespace keeping consecutive test runs against a long-lived service isolated:
     * without it, a second run would deterministically hit the terminal tasks of the first.
     */
    private static final String RUN_NAMESPACE = UUID.randomUUID().toString();

    /**
     * The deterministic remote task id of one logical invocation: a name-based (version 3) UUID of
     * {@code sessionId#callId} within {@link #RUN_NAMESPACE}, stable across in-process failovers
     * and replays.
     */
    public static String taskIdFor(String sessionId, String callId) {
        return UUID.nameUUIDFromBytes(
                        (RUN_NAMESPACE + "#" + sessionId + "#" + callId)
                                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /**
     * Submits a task under the given idempotency key {@code taskId}; returns the remote {@code
     * task_id}. The service answers 202 for a new task and 200 when the key already exists, so a
     * resubmission after a crash never creates a duplicate.
     */
    public String submit(String prompt, @Nullable String sessionId, String taskId)
            throws Exception {
        ObjectNode requestBody = mapper.createObjectNode().put("prompt", prompt);
        if (sessionId != null) {
            requestBody.put("session_id", sessionId);
        }
        requestBody.put("task_id", taskId);
        HttpRequest request =
                builder("/tasks")
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        mapper.writeValueAsString(requestBody)))
                        .build();
        HttpResponse<String> response = send(request, "POST /tasks");
        if (response.statusCode() != 202 && response.statusCode() != 200) {
            throw new IllegalStateException(
                    "POST /tasks expected 202/200 but got "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }
        JsonNode body = parse(response);
        String returnedTaskId = body.get("task_id").asText();
        LOG.info(
                "submit(prompt={}, session={}, taskId={}) -> task {} ({})",
                prompt,
                sessionId,
                taskId,
                returnedTaskId,
                response.statusCode() == 202 ? "created" : "idempotent replay");
        return returnedTaskId;
    }

    /**
     * Probes the status of one task; {@code null} when the service has no record of the id (a 404).
     */
    @Nullable
    public TaskStatus status(String taskId) throws Exception {
        HttpRequest request = builder("/tasks/" + taskId).GET().build();
        HttpResponse<String> response = send(request, "GET /tasks/" + taskId);
        if (response.statusCode() == 404) {
            return null;
        }
        expectStatus(response, 200, "GET /tasks/" + taskId);
        JsonNode body = parse(response);
        TaskStatus taskStatus =
                new TaskStatus(
                        body.get("status").asText(),
                        body.hasNonNull("error") ? body.get("error").asText() : null);
        LOG.info("status(task={}) -> {}", taskId, taskStatus.getStatus());
        return taskStatus;
    }

    /**
     * Fetches the terminal result of one task. A non-terminal task (409) surfaces as an error
     * result instead of throwing.
     */
    public SubagentResult fetchResult(String taskId) throws Exception {
        HttpRequest request = builder("/tasks/" + taskId + "/result").GET().build();
        HttpResponse<String> response = send(request, "GET /tasks/" + taskId + "/result");
        if (response.statusCode() == 404) {
            return SubagentResult.error("task not found: " + taskId);
        }
        if (response.statusCode() == 409) {
            return SubagentResult.error("task not finished: " + taskId);
        }
        expectStatus(response, 200, "GET /tasks/" + taskId + "/result");
        JsonNode body = parse(response);
        String status = body.get("status").asText();
        if (FAILED.equals(status)) {
            String error = body.hasNonNull("error") ? body.get("error").asText() : "run failed";
            LOG.info("fetchResult(task={}) -> failed: {}", taskId, error);
            return SubagentResult.error(error);
        }
        JsonNode result = body.get("result");
        SubagentResult outcome =
                SubagentResult.ok(
                        result == null || result.isNull() ? null : result.get(0).asText());
        LOG.info("fetchResult(task={}) -> {}: {}", taskId, status, outcome.getResult());
        return outcome;
    }

    /** The status snapshot of one remote task. */
    public static final class TaskStatus {
        private final String status;
        @Nullable private final String error;

        TaskStatus(String status, @Nullable String error) {
            this.status = status;
            this.error = error;
        }

        public String getStatus() {
            return status;
        }

        @Nullable
        public String getError() {
            return error;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------------------------------

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(builder(path).GET().build(), "GET " + path);
    }

    private HttpResponse<String> send(HttpRequest request, String what) throws Exception {
        long started = System.currentTimeMillis();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        LOG.info(
                "{} -> {} ({} ms)",
                what,
                response.statusCode(),
                System.currentTimeMillis() - started);
        return response;
    }

    private HttpResponse<String> expect(HttpResponse<String> response, int expected, String what)
            throws Exception {
        expectStatus(response, expected, what);
        return response;
    }

    private void expectStatus(HttpResponse<String> response, int expected, String what)
            throws Exception {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(
                    what
                            + " expected "
                            + expected
                            + " but got "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }
    }

    private JsonNode parse(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }
}
