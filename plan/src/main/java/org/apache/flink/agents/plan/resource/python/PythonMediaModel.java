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

package org.apache.flink.agents.plan.resource.python;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.media.BaseMediaModel;
import org.apache.flink.agents.api.media.MediaJob;
import org.apache.flink.agents.api.media.MediaJobException;
import org.apache.flink.agents.api.media.MediaJobState;
import org.apache.flink.agents.api.media.MediaJobStatus;
import org.apache.flink.agents.api.media.MediaJobTimeoutException;
import org.apache.flink.agents.api.media.MediaResult;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import pemja.core.object.PyObject;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Java access to a Python media model. */
public final class PythonMediaModel extends BaseMediaModel implements PythonResourceWrapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GENERATE = "python_java_utils.call_media_generate";
    private static final String SUPPORTS_ASYNC = "python_java_utils.media_supports_async_task";
    private static final String SUBMIT = "python_java_utils.call_media_submit";
    private static final String FETCH = "python_java_utils.call_media_fetch";
    private static final String WAIT = "python_java_utils.call_media_wait";
    private static final String CANCEL = "python_java_utils.call_media_cancel";

    private final PythonResourceAdapter adapter;
    private final PyObject resource;
    private boolean closed;

    public PythonMediaModel(
            PythonResourceAdapter adapter,
            PyObject resource,
            ResourceDescriptor descriptor,
            ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.adapter = adapter;
        this.resource = resource;
    }

    @Override
    public void open() {
        adapter.callMethod(resource, "open", Map.of());
    }

    @Override
    public MediaResult generate(
            List<DataContentBlock> content,
            @Nullable Double timeout,
            Map<String, Object> parameters) {
        return read(
                adapter.invoke(GENERATE, resource, json(content), timeout, parameters),
                MediaResult.class,
                "MediaModel result");
    }

    @Override
    public boolean supportsAsyncTask() {
        Object value = adapter.invoke(SUPPORTS_ASYNC, resource);
        if (!(value instanceof Boolean)) {
            throw new IllegalStateException("Invalid MediaModel invocation outcome from Python");
        }
        return (Boolean) value;
    }

    @Override
    public MediaJob submit(List<DataContentBlock> content, Map<String, Object> parameters) {
        return read(
                adapter.invoke(SUBMIT, resource, json(content), parameters),
                MediaJob.class,
                "MediaModel job");
    }

    @Override
    public MediaJobState fetch(MediaJob job) {
        return read(
                adapter.invoke(FETCH, resource, json(job)),
                MediaJobState.class,
                "MediaModel job state");
    }

    @Override
    public MediaResult wait(MediaJob job, @Nullable Double timeout) {
        return read(
                adapter.invoke(WAIT, resource, json(job), timeout),
                MediaResult.class,
                "MediaModel result");
    }

    @Override
    public void cancel(MediaJob job) {
        outcome(adapter.invoke(CANCEL, resource, json(job)));
    }

    @Override
    public Object getPythonResource() {
        return resource;
    }

    @Override
    public PythonResourceAdapter getPythonResourceAdapter() {
        return adapter;
    }

    @Override
    public void setMetricGroup(FlinkAgentsMetricGroup metricGroup) {
        super.setMetricGroup(metricGroup);
        setPythonResourceMetricGroup(metricGroup);
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try (resource) {
            adapter.callMethod(resource, "close", Map.of());
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Media request must contain JSON-compatible values", e);
        }
    }

    private static <T> T read(Object value, Class<T> type, String what) {
        String payload = outcome(value);
        try {
            return MAPPER.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid " + what + " from Python", e);
        }
    }

    /** Return the success payload. Cancel completes with a null result. */
    private static String outcome(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException("Invalid MediaModel invocation outcome from Python");
        }
        Map<?, ?> outcome = (Map<?, ?>) value;
        if ("INTERRUPTED".equals(outcome.get("status"))
                || "CANCELLED".equals(outcome.get("status"))) {
            Object message = outcome.get("message");
            if (!(message instanceof String)) {
                throw new IllegalStateException(
                        "Invalid MediaModel cancellation message from Python");
            }
            CancellationException cancellation = new CancellationException((String) message);
            if ("INTERRUPTED".equals(outcome.get("status"))) {
                cancellation.initCause(new InterruptedException(cancellation.getMessage()));
            }
            throw cancellation;
        }
        Object status = outcome.get("status");
        if ("SUCCESS".equals(status)) {
            Object result = outcome.get("result");
            if (result == null) {
                return null;
            }
            if (!(result instanceof String)) {
                throw new IllegalStateException(
                        "Invalid MediaModel invocation payload from Python");
            }
            return (String) result;
        }
        if ("JOB_ERROR".equals(status) || "TIMEOUT".equals(status)) {
            throw jobException(outcome, "TIMEOUT".equals(status));
        }
        throw new IllegalStateException("Invalid MediaModel invocation status from Python");
    }

    private static MediaJobException jobException(Map<?, ?> outcome, boolean timeout) {
        Object jobJson = outcome.get("job");
        Object statusValue = outcome.get("job_status");
        if (!(jobJson instanceof String) || !(statusValue instanceof String)) {
            throw new IllegalStateException("Invalid MediaModel job error from Python");
        }
        final MediaJob job;
        try {
            job = MAPPER.readValue((String) jobJson, MediaJob.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid MediaModel job from Python", e);
        }
        MediaJobStatus status = MediaJobStatus.fromValue((String) statusValue);
        String message =
                outcome.get("message") instanceof String ? (String) outcome.get("message") : null;
        if (timeout) {
            Object timeoutValue = outcome.get("timeout");
            double seconds =
                    timeoutValue instanceof Number ? ((Number) timeoutValue).doubleValue() : 0;
            return new MediaJobTimeoutException(job, status, seconds, message);
        }
        String code = outcome.get("code") instanceof String ? (String) outcome.get("code") : null;
        return new MediaJobException(job, status, code, message);
    }
}
