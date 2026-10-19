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

package org.apache.flink.agents.runtime.subgraph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.Event;

import java.util.Map;
import java.util.UUID;

/**
 * Persisted failure of a child action, carried inside a {@link SubgraphEvent}.
 *
 * <p>Only diagnostic text is stored, never the exception object or its stack trace. Live execution
 * and replay both expose the same reconstructed exception to the adapter. This ensures that a
 * completed failed child cannot become a successful empty graph after root replay.
 */
public final class SubgraphFailureEvent extends Event {
    public static final String EVENT_TYPE = "_internal_subgraph_failure";
    private static final String ERROR_TYPE = "error_type";
    private static final String MESSAGE = "message";

    public SubgraphFailureEvent(Throwable failure) {
        super(EVENT_TYPE);
        setAttr(ERROR_TYPE, failure.getClass().getName());
        setAttr(MESSAGE, failure.getMessage());
    }

    @JsonCreator
    public SubgraphFailureEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        super(id, EVENT_TYPE, attributes);
    }

    public IllegalStateException asException() {
        return new IllegalStateException(
                "Subgraph action failed (" + getAttr(ERROR_TYPE) + "): " + getAttr(MESSAGE));
    }
}
