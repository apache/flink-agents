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
package org.apache.flink.agents.runtime.env;

import org.apache.flink.agents.api.AgentBuilder;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the typed output terminals of the remote {@link AgentBuilder}: the {@code
 * toDataStream(TypeInformation)} / {@code toDataStream(Class)} and {@code toTable(TypeInformation)}
 * / {@code toTable(Class)} overloads.
 *
 * <p>A typed terminal must attach its declared type through a downstream conversion operator rather
 * than retyping the shared, cached raw stream. Because the type is applied downstream, several
 * typed terminals of different types can coexist on one execution while the unrestricted {@code
 * toDataStream()} view stays available and unchanged. These tests inspect the constructed graph and
 * never execute a job.
 */
class TypedOutputTerminalTest {

    /** Minimal agent so a plan can be built; the graph is inspected without executing it. */
    public static class EchoAgent extends Agent {
        @Action(EventType.InputEvent)
        public static void handle(Event event, RunnerContext ctx) {
            // Never runs: the terminal wiring is asserted at graph-construction time.
        }
    }

    /** Minimal POJO so a typed table terminal derives one physical column per field. */
    public static class Score {
        public int value;
        public String label;

        public Score() {}

        public Score(int value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    private static AgentBuilder appliedBuilder() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        DataStream<String> inputStream = env.fromData(List.of("a", "b", "c"));
        return agentsEnv.fromDataStream(inputStream).apply(new EchoAgent());
    }

    @Test
    void rawDataStreamIsCachedAcrossCalls() {
        AgentBuilder builder = appliedBuilder();

        DataStream<Object> raw = builder.toDataStream();

        // The agent operator is connected once; repeated calls reuse the same cached stream.
        assertThat(builder.toDataStream()).isSameAs(raw);
    }

    @Test
    void typedTerminalDoesNotWeldTypeOntoSharedRawStream() {
        AgentBuilder builder = appliedBuilder();

        DataStream<Object> raw = builder.toDataStream();
        TypeInformation<?> rawTypeBefore = raw.getType();

        DataStream<Integer> typed = builder.toDataStream(TypeInformation.of(Integer.class));

        // The declared type is attached to the typed stream...
        assertThat(typed.getType()).isEqualTo(TypeInformation.of(Integer.class));
        // ...and is not welded onto the shared raw stream, which keeps its original type. Retyping
        // the cached stream in place (for example via returns() on it) would change raw.getType().
        assertThat(raw.getType()).isEqualTo(rawTypeBefore);
        assertThat(raw.getType()).isNotEqualTo(TypeInformation.of(Integer.class));
        // The unrestricted view is still the same cached stream after the typed terminal was built.
        assertThat(builder.toDataStream()).isSameAs(raw);
    }

    @Test
    void classOverloadDerivesTheSameTypeAsTypeInformation() {
        AgentBuilder builder = appliedBuilder();

        DataStream<Object> raw = builder.toDataStream();
        TypeInformation<?> rawTypeBefore = raw.getType();

        // The Class overload is a convenience that derives the TypeInformation internally.
        DataStream<Integer> typed = builder.toDataStream(Integer.class);

        assertThat(typed.getType()).isEqualTo(TypeInformation.of(Integer.class));
        // It carries the same anti-welding guarantee as the explicit TypeInformation overload.
        assertThat(raw.getType()).isEqualTo(rawTypeBefore);
    }

    @Test
    void multipleTypedTerminalsOfDifferentTypesCoexist() {
        AgentBuilder builder = appliedBuilder();

        DataStream<Object> raw = builder.toDataStream();
        TypeInformation<?> rawTypeBefore = raw.getType();

        // Two independent typed terminals of different types on the same execution. Each layers its
        // own conversion operator on the shared raw stream, so they do not conflict with one
        // another.
        DataStream<Integer> numbers = builder.toDataStream(TypeInformation.of(Integer.class));
        DataStream<String> names = builder.toDataStream(TypeInformation.of(String.class));

        assertThat(numbers.getType()).isEqualTo(TypeInformation.of(Integer.class));
        assertThat(names.getType()).isEqualTo(TypeInformation.of(String.class));
        // Neither terminal welds its type onto the shared raw stream, which stays unrestricted.
        assertThat(raw.getType()).isEqualTo(rawTypeBefore);
        assertThat(builder.toDataStream()).isSameAs(raw);
    }

    @Test
    void typedTableDerivesColumnsFromTypeInformation() {
        AgentBuilder builder = appliedBuilder();

        DataStream<Object> raw = builder.toDataStream();
        TypeInformation<?> rawTypeBefore = raw.getType();

        Table table = builder.toTable(TypeInformation.of(Score.class));

        // The physical schema is derived from the declared type: each POJO field becomes a column.
        assertThat(table.getResolvedSchema().getColumnNames())
                .containsExactlyInAnyOrder("value", "label");
        // The type is attached by a downstream operator, so the shared raw stream stays
        // unrestricted.
        assertThat(raw.getType()).isEqualTo(rawTypeBefore);
    }

    @Test
    void typedTableClassOverloadDerivesTheSameColumns() {
        AgentBuilder builder = appliedBuilder();

        DataStream<Object> raw = builder.toDataStream();
        TypeInformation<?> rawTypeBefore = raw.getType();

        // The Class overload is a convenience that derives the TypeInformation internally.
        Table table = builder.toTable(Score.class);

        assertThat(table.getResolvedSchema().getColumnNames())
                .containsExactlyInAnyOrder("value", "label");
        // It carries the same anti-welding guarantee as the explicit TypeInformation overload.
        assertThat(raw.getType()).isEqualTo(rawTypeBefore);
    }
}
