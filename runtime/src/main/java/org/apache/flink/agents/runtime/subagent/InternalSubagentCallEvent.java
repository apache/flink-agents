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

package org.apache.flink.agents.runtime.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.Event;

import java.util.Map;
import java.util.UUID;

/**
 * Routing wrapper event for internal sub-agent calls: an inner {@code delegate} event plus
 * immutable routing metadata (scope, callId, sessionId). A pure record — the per-call quiesce state
 * lives in the owning {@code InternalSubagentSetup}, looked up by {@code (sessionId, callId)}.
 *
 * <p>The routing metadata and the delegate's identity are kept in the event attributes because an
 * action state is keyed by the attributes of its triggering event: a replayed call must reproduce
 * the same attributes so the child action replays its recorded output, while two distinct calls
 * must differ so they never share one action state.
 */
public class InternalSubagentCallEvent extends Event {

    public static final String EVENT_TYPE = "InternalSubagentCallEvent";

    private static final String ATTR_TARGET_SCOPE = "targetScope";
    private static final String ATTR_CALL_ID = "callId";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_DELEGATE_TYPE = "delegateType";
    private static final String ATTR_DELEGATE_ATTRIBUTES = "delegateAttributes";

    private final Event delegate;

    public InternalSubagentCallEvent(
            Event delegate, String targetScope, String callId, String sessionId) {
        super(EVENT_TYPE);
        this.delegate = delegate;
        setAttr(ATTR_TARGET_SCOPE, targetScope);
        setAttr(ATTR_CALL_ID, callId);
        setAttr(ATTR_SESSION_ID, sessionId);
        // The delegate's identity separates the events forwarded within one call, which all carry
        // the same (sessionId, callId).
        setAttr(ATTR_DELEGATE_TYPE, delegate.getType());
        setAttr(ATTR_DELEGATE_ATTRIBUTES, delegate.getAttributes());
        if (delegate.hasSourceTimestamp()) {
            setSourceTimestamp(delegate.getSourceTimestamp());
        }
    }

    /** Constructor for deserialization purposes. */
    @JsonCreator
    public InternalSubagentCallEvent(
            @JsonProperty("id") UUID id,
            @JsonProperty("attributes") Map<String, Object> attributes,
            @JsonProperty("delegate") Event delegate) {
        super(id, EVENT_TYPE, attributes);
        this.delegate = delegate;
    }

    /** Initial fan-out from the bootstrapping setup. */
    public static InternalSubagentCallEvent bootstrap(
            Event delegate, String targetScope, String callId, String sessionId) {
        return new InternalSubagentCallEvent(delegate, targetScope, callId, sessionId);
    }

    /** Re-wrap from a scope-aware child context's {@code sendEvent}. */
    public static InternalSubagentCallEvent forward(
            Event delegate, String currentScope, String callId, String sessionId) {
        return new InternalSubagentCallEvent(delegate, currentScope, callId, sessionId);
    }

    public Event getDelegate() {
        return delegate;
    }

    @JsonIgnore
    public String getTargetScope() {
        return (String) getAttr(ATTR_TARGET_SCOPE);
    }

    @JsonIgnore
    public String getCallId() {
        return (String) getAttr(ATTR_CALL_ID);
    }

    @JsonIgnore
    public String getSessionId() {
        return (String) getAttr(ATTR_SESSION_ID);
    }

    @JsonIgnore
    public String getDelegateEventType() {
        return (String) getAttr(ATTR_DELEGATE_TYPE);
    }
}
