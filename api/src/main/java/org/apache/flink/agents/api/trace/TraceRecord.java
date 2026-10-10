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

package org.apache.flink.agents.api.trace;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.annotation.Internal;

import javax.annotation.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One observation, with the observed object's context, timestamp, status, and attributes.
 *
 * <p>The context's {@code entityType} identifies the observed object and determines which fields
 * apply:
 *
 * <ul>
 *   <li>For {@code event}, {@code entityName} is the Event's type and {@code
 *       entityMetadata.eventId} is its identity. Metadata may also contain producer and source
 *       references. The execution identifiers, {@code status}, and {@code problemCategory} are
 *       absent; {@code attributes} contains the Event's payload.
 *   <li>Other values, such as {@code action}, {@code llm}, {@code parser}, or {@code tool},
 *       identify one Action execution or component call. Its identity is {@code executionId}, and
 *       an optional {@code parentExecutionId} identifies the containing execution. {@code status}
 *       describes its progress or outcome, {@code problemCategory} may classify a failure, and
 *       {@code attributes} contains observation details such as error information.
 * </ul>
 *
 * <p>The context also carries any available run identity and relationships. A TraceRecord has no
 * separate identity; the same observed object may appear in multiple TraceRecords. Payload limits
 * apply only to {@code attributes}, leaving context, status, and failure classification intact.
 * User payload fields named {@code status} or {@code problemCategory} remain ordinary attributes.
 *
 * <p>JSON output flattens the context fields, while keeping entity metadata and attributes as
 * separate objects.
 */
@Internal
@JsonSerialize(using = TraceRecordJsonSerializer.class)
@JsonDeserialize(using = TraceRecordJsonDeserializer.class)
public final class TraceRecord {

    /** Progress and outcome values for {@link TraceRecord#getStatus()}. */
    public static final class Statuses {
        public static final String CREATED = "created";
        public static final String STARTED = "started";
        public static final String SUCCESS = "success";
        public static final String FAILED = "failed";
        public static final String REUSED = "reused";

        private Statuses() {}
    }

    private final TraceContext context;
    private final String timestamp;
    @Nullable private final String status;
    @Nullable private final String problemCategory;
    private final Map<String, Object> attributes;

    /**
     * Creates a TraceRecord at the supplied observation or occurrence timestamp.
     *
     * <p>The fields must follow the applicability rules described by this class.
     */
    public TraceRecord(
            TraceContext context,
            String timestamp,
            @Nullable String status,
            @Nullable String problemCategory,
            @Nullable Map<String, Object> attributes) {
        this.context = Objects.requireNonNull(context, "context");
        if (context.getEntityType() == null
                || context.getEntityType().isEmpty()
                || context.getEntityName() == null
                || context.getEntityName().isEmpty()) {
            throw new IllegalArgumentException(
                    "A TraceRecord must identify an entity type and name.");
        }
        if (TraceContext.EVENT_ENTITY_TYPE.equals(context.getEntityType())) {
            if (context.getExecutionId() != null || context.getParentExecutionId() != null) {
                throw new IllegalArgumentException(
                        "TraceRecord executionId and parentExecutionId must be absent when entityType is 'event'.");
            }
            if (status != null || problemCategory != null) {
                throw new IllegalArgumentException(
                        "TraceRecord status and problemCategory must be absent when entityType is 'event'.");
            }
            Object eventId = context.getEntityMetadata().get("eventId");
            if (!(eventId instanceof String) || ((String) eventId).isEmpty()) {
                throw new IllegalArgumentException(
                        "TraceRecord entityMetadata.eventId is required when entityType is 'event'.");
            }
        } else if (context.getExecutionId() == null || context.getExecutionId().isEmpty()) {
            throw new IllegalArgumentException(
                    "TraceRecord executionId is required when observing an Action execution or component call.");
        }
        if (timestamp == null || timestamp.isEmpty()) {
            throw new IllegalArgumentException("TraceRecord timestamp must not be null or empty.");
        }
        this.timestamp = timestamp;
        this.status = status;
        this.problemCategory = problemCategory;
        this.attributes =
                attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    /** Creates a record using the current observation time. */
    public static TraceRecord create(
            TraceContext context,
            @Nullable String status,
            @Nullable String problemCategory,
            @Nullable Map<String, Object> attributes) {
        return new TraceRecord(
                context, Instant.now().toString(), status, problemCategory, attributes);
    }

    public TraceContext getContext() {
        return context;
    }

    public String getTimestamp() {
        return timestamp;
    }

    @Nullable
    public String getStatus() {
        return status;
    }

    @Nullable
    public String getProblemCategory() {
        return problemCategory;
    }

    /** Returns a read-only payload map; nested values retain their supplied representation. */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
