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
 * Representation of an agent action with unified trigger conditions.
 *
 * <p>Each entry of {@code triggerConditions} is an event type name string. Multiple entries combine
 * with OR.
 */
@JsonSerialize(using = ActionJsonSerializer.class)
@JsonDeserialize(using = ActionJsonDeserializer.class)
public class Action {
    private final String name;
    private final Function exec;
    private final List<String> triggerConditions;

    // TODO: support nested map/list with non primitive type value.
    @Nullable private final Map<String, Object> config;

    public Action(
            String name,
            Function exec,
            List<String> triggerConditions,
            @Nullable Map<String, Object> config)
            throws Exception {
        this.name = name;
        this.exec = exec;
        this.triggerConditions = triggerConditions;
        this.config = config;
        exec.checkSignature(new Class[] {Event.class, RunnerContext.class});
    }

    public Action(String name, Function exec, List<String> triggerConditions) throws Exception {
        this(name, exec, triggerConditions, null);
    }

    public String getName() {
        return name;
    }

    public Function getExec() {
        return exec;
    }

    /** Returns the full trigger conditions list. */
    public List<String> getTriggerConditions() {
        return triggerConditions;
    }

    /**
     * Returns event-type names. Kept for callers that still consume the old naming; in this PR all
     * trigger entries are plain event-type names so the list is identical to {@link
     * #getTriggerConditions()}. A follow-up PR introduces CEL expressions and overrides this to
     * filter out non-type entries.
     */
    public List<String> getListenEventTypes() {
        return triggerConditions;
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
                && Objects.equals(triggerConditions, other.triggerConditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, exec, triggerConditions);
    }
}
