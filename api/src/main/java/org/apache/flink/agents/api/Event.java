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

package org.apache.flink.agents.api;

import org.apache.flink.agents.api.utils.SerializableChecker;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonProcessingException;

import java.io.Serializable;
import java.security.InvalidParameterException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Base class for all event types in the system. */
public abstract class Event implements Serializable {
    private static final long serialVersionUID = 1L;
    private final UUID id;
    private final Map<String, Object> attributes;

    public Event() {
        this.id = UUID.randomUUID();
        attributes = new HashMap<>();
    }

    public UUID getId() {
        return id;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Object getAttr(String name) {
        return attributes.get(name);
    }

    public void setAttr(String name, Object value) {
        attributes.put(name, value);
        checkSerializable();
    }

    public void checkSerializable() {
        try {
            SerializableChecker.checkSerializable(this);
        } catch (JsonProcessingException e) {
            throw new InvalidParameterException(e.getMessage());
        }
    }
}
