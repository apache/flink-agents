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
package org.apache.flink.agents.integrations.observability.otel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;

/**
 * One flat Event Log record, as written by the SLF4J and File event loggers with {@code
 * event-log.trace.enabled: true}.
 *
 * <p>Only the fields the exporter consumes are declared; unknown fields (e.g. {@code logLevel},
 * SLF4J-only fields like {@code jobId}) are ignored so the parser stays tolerant to record-format
 * additions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TraceRecord {

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("inputRunId")
    private String inputRunId;

    @JsonProperty("businessKey")
    private String businessKey;

    @JsonProperty("agentName")
    private String agentName;

    @JsonProperty("executionId")
    private String executionId;

    @JsonProperty("parentExecutionId")
    private String parentExecutionId;

    @JsonProperty("entityType")
    private String entityType;

    @JsonProperty("entityName")
    private String entityName;

    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("problemCategory")
    private String problemCategory;

    @JsonProperty("entityMetadata")
    private Map<String, Object> entityMetadata;

    @JsonProperty("eventAttributes")
    private Map<String, Object> eventAttributes;

    public String getTimestamp() {
        return timestamp;
    }

    public String getInputRunId() {
        return inputRunId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getParentExecutionId() {
        return parentExecutionId;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getStatus() {
        return status;
    }

    public String getProblemCategory() {
        return problemCategory;
    }

    /** Small structured metadata recorded with the execution, or an empty map when absent. */
    public Map<String, Object> getEntityMetadata() {
        return entityMetadata != null ? entityMetadata : Collections.emptyMap();
    }

    /** The recorded error type of a failed execution, carried in its event attributes. */
    public String getErrorType() {
        return eventAttribute("errorType");
    }

    /** The recorded error message of a failed execution, carried in its event attributes. */
    public String getErrorMessage() {
        return eventAttribute("errorMessage");
    }

    private String eventAttribute(String name) {
        Object value = eventAttributes != null ? eventAttributes.get(name) : null;
        return value instanceof String ? (String) value : null;
    }

    public Map<String, Object> getEventAttributes() {
        return eventAttributes;
    }
}
