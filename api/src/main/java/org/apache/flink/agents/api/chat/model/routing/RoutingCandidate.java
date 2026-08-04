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

package org.apache.flink.agents.api.chat.model.routing;

import java.io.Serializable;
import java.util.Objects;

/**
 * A candidate chat model a {@link ModelRouter} may select, as seen by a {@link RoutingStrategy}.
 *
 * <p>Carries the candidate's registered model name plus an optional human-readable description
 * (declared via {@code ModelRouter.Builder#describe}) that semantic strategies — and future
 * framework-managed LLM routing — can use to decide. The name must resolve to a registered {@code
 * CHAT_MODEL}. Per-candidate metadata (e.g. cost or load hints) is deferred until a strategy can
 * actually consume it.
 */
public final class RoutingCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;

    public RoutingCandidate(String name, String description) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Candidate name must be non-null and non-empty.");
        }
        this.name = name;
        this.description = description == null ? "" : description;
    }

    public RoutingCandidate(String name) {
        this(name, "");
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RoutingCandidate that = (RoutingCandidate) o;
        return name.equals(that.name) && description.equals(that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description);
    }

    @Override
    public String toString() {
        return "RoutingCandidate{name='" + name + "', description='" + description + "'}";
    }
}
