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
import org.apache.flink.agents.plan.condition.ParsedCondition;
import org.apache.flink.agents.plan.condition.ParsedCondition.CelExpression;
import org.apache.flink.agents.plan.condition.ParsedCondition.TypeMatch;
import org.apache.flink.agents.plan.serializer.ActionJsonDeserializer;
import org.apache.flink.agents.plan.serializer.ActionJsonSerializer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Representation of an agent action with unified trigger conditions.
 *
 * <p>Each entry of {@code triggerConditions} is either a plain event-type name (matched against
 * {@code event.getType()}) or a CEL expression. Multiple entries combine with OR.
 */
@JsonSerialize(using = ActionJsonSerializer.class)
@JsonDeserialize(using = ActionJsonDeserializer.class)
public class Action {
    private final String name;
    private final Function exec;
    private final List<String> triggerConditions;

    /**
     * Derived from {@link #triggerConditions}; not part of the persisted state because the CEL AST
     * objects inside CelExpression are not Kryo-serialisable. Rebuilt lazily on first access after
     * deserialization via {@link #parsedConditions()}.
     */
    private transient List<ParsedCondition> parsedConditions;

    // TODO: support nested map/list with non primitive type value.
    @Nullable private final Map<String, Object> config;

    public Action(
            String name,
            Function exec,
            List<String> triggerConditions,
            @Nullable Map<String, Object> config)
            throws Exception {
        if (triggerConditions == null || triggerConditions.isEmpty()) {
            throw new IllegalArgumentException(
                    "Action '" + name + "' must have at least one entry in 'triggerConditions'");
        }
        this.name = name;
        this.exec = exec;
        this.triggerConditions = triggerConditions;
        this.config = config;

        // Eagerly build (and validate) parsedConditions at construction time so any classifier
        // error fires early. Stored transiently; rebuilt on first access after deserialization.
        this.parsedConditions = buildParsedConditions(name, triggerConditions);

        exec.checkSignature(new Class[] {Event.class, RunnerContext.class});
    }

    private static List<ParsedCondition> buildParsedConditions(
            String name, List<String> triggerConditions) {
        List<ParsedCondition> parsed = new ArrayList<>(triggerConditions.size());
        for (String entry : triggerConditions) {
            if (entry == null || entry.isEmpty()) {
                throw new IllegalArgumentException(
                        "Action '" + name + "' has a null/empty trigger entry");
            }
            parsed.add(ParsedCondition.classify(entry));
        }
        return Collections.unmodifiableList(parsed);
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

    /** Returns the full trigger conditions list (type names and CEL expressions). */
    public List<String> getTriggerConditions() {
        return triggerConditions;
    }

    /** Returns parsed conditions in declaration order (unmodifiable). */
    public List<ParsedCondition> getParsedConditions() {
        return parsedConditions();
    }

    /** Lazily rebuilds parsedConditions on first access after deserialization. */
    private synchronized List<ParsedCondition> parsedConditions() {
        if (parsedConditions == null) {
            parsedConditions = buildParsedConditions(name, triggerConditions);
        }
        return parsedConditions;
    }

    /** Returns event-type names extracted from {@link TypeMatch} entries (CEL entries skipped). */
    public List<String> getListenEventTypes() {
        List<String> typeNames = new ArrayList<>();
        for (ParsedCondition pc : parsedConditions()) {
            if (pc instanceof TypeMatch) {
                typeNames.add(pc.source());
            }
        }
        return typeNames;
    }

    /** Returns whether this action carries at least one CEL expression entry. */
    public boolean hasCelCondition() {
        for (ParsedCondition pc : parsedConditions()) {
            if (pc instanceof CelExpression) {
                return true;
            }
        }
        return false;
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
