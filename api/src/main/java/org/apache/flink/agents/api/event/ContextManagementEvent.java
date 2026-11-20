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
package org.apache.flink.agents.api.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.Event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Observability event recording one step in which the built-in chat action changed what it sends to
 * the model for a chat request: tool results truncated, the results of older rounds masked, or
 * older rounds replaced by a summary.
 *
 * <p>The event is emitted when a {@link
 * org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy} applies to the
 * request, once per step and not once per model call: a result is reported when it is first sent
 * truncated, a round when it is first masked, and a summarization when it runs. It is a record for
 * logging, tracing and evaluation: it has <b>no built-in consumer</b>, and removing every listener
 * of it does not change what the model receives.
 *
 * <p>{@link #getDetails()} depends on {@link #getStep()}:
 *
 * <ul>
 *   <li>{@link #STEP_TRUNCATE}: {@code max_chars} and {@code results}, a list with {@code round},
 *       {@code call_id}, {@code tool}, {@code original_chars} and {@code sent_chars} for each
 *       truncated result. {@code round} is the number of the result's round in the request, from 1:
 *       a provider can give the calls of two rounds the same id.
 *   <li>{@link #STEP_MASK}: {@code keep_last_rounds}, {@code step_rounds}, {@code rounds} (how many
 *       rounds were masked in this step) and {@code results}, a list with {@code round}, {@code
 *       call_id}, {@code tool} and {@code omitted_chars} for each masked result.
 *   <li>{@link #STEP_SUMMARIZE}: {@code summarizer}, {@code rounds} (how many rounds got their line
 *       in this step), {@code summarized_rounds} (how many rounds the summary that is sent has a
 *       line for; a round without a line is still sent), {@code max_input_tokens}, {@code
 *       estimated_input_tokens_before} and {@code estimate_approximate}, which tells whether the
 *       estimate before is an approximation. When the model is sent the new lines, {@code
 *       summary_chars} and {@code estimated_input_tokens_after}, which is the estimate before, less
 *       what the lines save; the saving is always measured by the approximation. While the messages
 *       would not be smaller with the lines, they are held, and {@code pending_rounds} tells how
 *       many rounds have a line that is held. When the summary could not be written, {@code error}
 *       holds the reason and the request goes on without it; {@code passed_over_round} is then the
 *       number of the round that gets no line from this step on, when the answers for it could not
 *       be used in three steps in a row.
 * </ul>
 */
public class ContextManagementEvent extends Event {

    public static final String EVENT_TYPE = "_context_management_event";

    /** Tool results were cut to the policy's maximum size. */
    public static final String STEP_TRUNCATE = "truncate";

    /** The tool results of older rounds were replaced by placeholders. */
    public static final String STEP_MASK = "mask";

    /** Older rounds were replaced by a summary, or a summary could not be written. */
    public static final String STEP_SUMMARIZE = "summarize";

    private static final List<BuiltInAttribute> ATTRIBUTE_SCHEMA =
            List.of(
                    BuiltInAttribute.requiredUuid("request_id"),
                    BuiltInAttribute.required("model", String.class),
                    BuiltInAttribute.required("step", String.class),
                    BuiltInAttribute.required("details", Map.class));

    /**
     * @param requestId the id of the chat request whose model input was changed
     * @param model the chat model resource the request is being sent to
     * @param step one of {@link #STEP_TRUNCATE}, {@link #STEP_MASK}, {@link #STEP_SUMMARIZE}
     * @param details what the step did, see the class description
     */
    public ContextManagementEvent(
            UUID requestId, String model, String step, Map<String, Object> details) {
        super(EVENT_TYPE);
        setAttr("request_id", Objects.requireNonNull(requestId, "request_id"));
        setAttr("model", Objects.requireNonNull(model, "model"));
        setAttr("step", Objects.requireNonNull(step, "step"));
        setAttr("details", mutableCopy(Objects.requireNonNull(details, "details")));
    }

    @JsonCreator
    public ContextManagementEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, normalizeAttributes(attributes));
    }

    /** Convert the {@code request_id} back to a {@link UUID} after JSON deserialization. */
    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        Map<String, Object> normalized =
                attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        Object rawId = normalized.get("request_id");
        if (rawId instanceof String) {
            normalized.put("request_id", UUID.fromString((String) rawId));
        }
        Object details = normalized.get("details");
        if (details instanceof Map<?, ?>) {
            normalized.put("details", mutableCopy(details));
        }
        return normalized;
    }

    /**
     * Reconstructs a typed ContextManagementEvent from a base Event.
     *
     * <p>Enforces the fixed schema: {@code request_id}, {@code model}, {@code step} and {@code
     * details} are required, and no other attribute is allowed.
     *
     * @throws IllegalArgumentException if the event violates the schema
     */
    public static ContextManagementEvent fromEvent(Event event) {
        validateAttributeSchema(EVENT_TYPE, event.getAttributes(), ATTRIBUTE_SCHEMA);
        return reconstructFrom(event, ContextManagementEvent::new);
    }

    @JsonIgnore
    public UUID getRequestId() {
        Object value = getAttr("request_id");
        if (value instanceof String) {
            return UUID.fromString((String) value);
        }
        return (UUID) value;
    }

    /** The chat model resource the request is being sent to. */
    @JsonIgnore
    public String getModel() {
        return (String) getAttr("model");
    }

    /** One of {@link #STEP_TRUNCATE}, {@link #STEP_MASK}, {@link #STEP_SUMMARIZE}. */
    @JsonIgnore
    public String getStep() {
        return (String) getAttr("step");
    }

    /** What the step did; the keys depend on {@link #getStep()}, see the class description. */
    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDetails() {
        Map<String, Object> details = (Map<String, Object>) getAttr("details");
        return details == null ? new HashMap<>() : details;
    }

    private static Object mutableCopy(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, nested) -> copy.put(key, mutableCopy(nested)));
            return copy;
        }
        if (value instanceof Collection<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object nested : (Collection<?>) value) {
                copy.add(mutableCopy(nested));
            }
            return copy;
        }
        return value;
    }
}
