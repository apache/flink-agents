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

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBuilderApplyByNameTest {

    @Test
    void defaultApplyByNameThrows() {
        AgentBuilder b = new SimpleStubBuilder();
        assertThatThrownBy(() -> b.apply("any")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void overrideResolvesFromEnv() {
        Agent inc = new Agent();
        AgentBuilder b = new RegistryStubBuilder("inc", inc);
        b.apply("inc");
        assertThatThrownBy(() -> b.apply("ghost")).hasMessageContaining("ghost");
    }

    /** Stub builder that doesn't override apply(String). */
    private static final class SimpleStubBuilder implements AgentBuilder {
        @Override
        public AgentBuilder apply(Agent agent) {
            return this;
        }

        @Override
        public List<Map<String, Object>> toList() {
            return List.of();
        }

        @Override
        public DataStream<Object> toDataStream() {
            return null;
        }

        @Override
        public Table toTable(Schema schema) {
            return null;
        }
    }

    /** Stub builder that overrides apply(String) to resolve from a one-agent registry. */
    private static final class RegistryStubBuilder implements AgentBuilder {
        private final String registeredName;
        private final Agent registeredAgent;
        Agent applied;

        RegistryStubBuilder(String name, Agent agent) {
            this.registeredName = name;
            this.registeredAgent = agent;
        }

        @Override
        public AgentBuilder apply(Agent agent) {
            this.applied = agent;
            return this;
        }

        @Override
        public AgentBuilder apply(String name) {
            if (!registeredName.equals(name)) {
                throw new IllegalArgumentException(
                        "Unknown agent '" + name + "'; no agent with that name is registered.");
            }
            return apply(registeredAgent);
        }

        @Override
        public List<Map<String, Object>> toList() {
            return List.of();
        }

        @Override
        public DataStream<Object> toDataStream() {
            return null;
        }

        @Override
        public Table toTable(Schema schema) {
            return null;
        }
    }
}
