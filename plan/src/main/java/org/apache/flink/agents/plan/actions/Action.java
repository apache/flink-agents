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

package org.apache.flink.agents.plan.actions;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.Function;
import org.apache.flink.agents.plan.serializer.ActionJsonDeserializer;
import org.apache.flink.agents.plan.serializer.ActionJsonSerializer;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Representation of an agent action with event listening and function execution.
 *
 * <p>This class encapsulates a named agent action that listens for specific event types and
 * executes an associated function when those events occur.
 */
@JsonSerialize(using = ActionJsonSerializer.class)
@JsonDeserialize(using = ActionJsonDeserializer.class)
public class Action {
    private final String name;
    private final Function exec;
    private final List<String> listenEventTypes;

    // TODO: support nested map/list with non primitive type value.
    @Nullable private final Map<String, Object> config;

    public Action(
            String name,
            Function exec,
            List<String> listenEventTypes,
            @Nullable Map<String, Object> config)
            throws Exception {
        this.name = name;
        this.exec = exec;
        this.listenEventTypes = listenEventTypes;
        this.config = config;
        exec.checkSignature(new Class[] {Event.class, RunnerContext.class});
    }

    public Action(String name, Function exec, List<String> listenEventTypes) throws Exception {
        this(name, exec, listenEventTypes, null);
    }

    public String getName() {
        return name;
    }

    public Function getExec() {
        return exec;
    }

    public List<String> getListenEventTypes() {
        return listenEventTypes;
    }

    @Nullable
    public Map<String, Object> getConfig() {
        return config;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Action other = (Action) o;
        return name.equals(other.name)
                && exec.equals(other.exec)
                && listenEventTypes.equals(other.listenEventTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, exec, listenEventTypes);
    }
}
