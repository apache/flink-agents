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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.Event;

import java.util.Map;
import java.util.UUID;

/**
 * Private event envelope shared by all internal graph calls. Only bootstraps can be dispatched
 * while their producing action is suspended. The delegate is what a business action receives.
 *
 * <p>Action-state lookup uses the invocation id, bootstrap flag, and delegate type and attributes.
 * The delegate's event id is retained on the event but is not part of the envelope attributes used
 * for lookup. Event occurrence identity remains subject to the strategy tracked by issue 1084.
 */
public final class SubgraphEvent extends Event {
    public static final String EVENT_TYPE = "_internal_subgraph";
    private static final String INVOCATION_ID = "invocation_id";
    private static final String BOOTSTRAP = "bootstrap";
    private static final String DELEGATE_TYPE = "delegate_type";
    private static final String DELEGATE_ATTRIBUTES = "delegate_attributes";
    private final Event delegate;

    public SubgraphEvent(String invocationId, Event delegate, boolean bootstrap) {
        super(EVENT_TYPE);
        this.delegate = delegate;
        setAttr(INVOCATION_ID, invocationId);
        setAttr(BOOTSTRAP, bootstrap);
        setAttr(DELEGATE_TYPE, delegate.getType());
        setAttr(DELEGATE_ATTRIBUTES, delegate.getAttributes());
        if (delegate.hasSourceTimestamp()) {
            setSourceTimestamp(delegate.getSourceTimestamp());
        }
    }

    @JsonCreator
    public SubgraphEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes,
            @JsonProperty("delegate") Event delegate) {
        super(id, EVENT_TYPE, attributes);
        this.delegate = delegate;
    }

    public Event getDelegate() {
        return delegate;
    }

    @JsonIgnore
    public String getInvocationId() {
        return (String) getAttr(INVOCATION_ID);
    }

    @JsonIgnore
    public boolean isBootstrap() {
        return Boolean.TRUE.equals(getAttr(BOOTSTRAP));
    }
}
