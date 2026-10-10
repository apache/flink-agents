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
package org.apache.flink.agents.runtime.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.flink.agents.api.Event;

import java.util.Map;
import java.util.UUID;

/** Private routing envelope. Only its bootstrap may leave an unfinished action. */
public class ChatCallEvent extends Event {
    public static final String EVENT_TYPE = "_internal_chat_call";
    private static final String CALL_ID = "call_id";
    private static final String BOOTSTRAP = "bootstrap";
    private static final String DELEGATE_TYPE = "delegate_type";
    private static final String DELEGATE_ATTRIBUTES = "delegate_attributes";
    private static final String DELEGATE_ID = "delegate_id";
    private final Event delegate;

    public ChatCallEvent(String callId, Event delegate, boolean bootstrap) {
        super(EVENT_TYPE);
        this.delegate = delegate;
        setAttr(CALL_ID, callId);
        setAttr(BOOTSTRAP, bootstrap);
        setAttr(DELEGATE_TYPE, delegate.getType());
        setAttr(DELEGATE_ATTRIBUTES, delegate.getAttributes());
        // Child state keys must distinguish identical messages in separate model/tool turns.
        setAttr(DELEGATE_ID, delegate.getId().toString());
    }

    @JsonCreator
    public ChatCallEvent(
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
    public String getCallId() {
        return (String) getAttr(CALL_ID);
    }

    @JsonIgnore
    public boolean isBootstrap() {
        return Boolean.TRUE.equals(getAttr(BOOTSTRAP));
    }
}
