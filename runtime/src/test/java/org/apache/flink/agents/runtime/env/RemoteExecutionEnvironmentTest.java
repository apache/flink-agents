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

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.apache.flink.agents.runtime.env.RemoteExecutionEnvironment.FLINK_CONF_FILENAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RemoteExecutionEnvironmentTest {
    @TempDir private File tmpDir;

    @Test
    void testLoadAgentConfiguration() throws FileNotFoundException {
        File confFile = new File(tmpDir, FLINK_CONF_FILENAME);

        try (final PrintWriter pw = new PrintWriter(confFile)) {
            pw.println("agent:");
            pw.println("    key1: ");
            pw.println("        key2: v1");
            pw.println("        key3: 'v2'");
            pw.println("    key4: 1");
            pw.println("    key5: 'v3'");
            pw.println("    key6: 1.5");
            pw.println("    key7: true");
        }

        AgentConfiguration conf =
                RemoteExecutionEnvironment.loadAgentConfiguration(tmpDir.getAbsolutePath());

        assertThat(conf.getConfData().keySet()).hasSize(6);
        assert conf.getConfData().get("key1.key2").equals("v1");
        assert conf.getConfData().get("key1.key3").equals("v2");
        assert conf.getConfData().get("key4").equals(1);
        assert conf.getConfData().get("key5").equals("v3");
        assert conf.getConfData().get("key6").equals(1.5);
        assert conf.getConfData().get("key7").equals(true);
    }

    @Test
    void testLoadAgentConfigurationFailIfNotLoaded() {
        assertThatThrownBy(
                        () ->
                                RemoteExecutionEnvironment.loadAgentConfiguration(
                                        "/some/path/" + UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testLoadAgentConfigurationFailIfNull() {
        AgentConfiguration conf = RemoteExecutionEnvironment.loadAgentConfiguration(null);
        assertThat(conf.getConfData()).isEmpty();
    }

    @Test
    void testExecuteWithCustomJobNameDelegatesToStreamExecutionEnvironment() throws Exception {
        StreamExecutionEnvironment flinkEnv = mock(StreamExecutionEnvironment.class);
        when(flinkEnv.execute(anyString())).thenReturn(mock(JobExecutionResult.class));

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(flinkEnv);

        String customJobName = "my-custom-agents-job";
        agentsEnv.execute(customJobName);

        verify(flinkEnv).execute(customJobName);
    }

    @Test
    void fromDataStreamPreservesExistingKeyedStreamKeyWhenNoSelectorGiven() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        KeyedStream<String, String> keyedBySession =
                env.fromData("same:first", "same:second", "same:first again", "other:first")
                        .keyBy(value -> value.split(":")[0]);

        DataStream<Object> output =
                agentsEnv.fromDataStream(keyedBySession).apply(new CountingAgent()).toDataStream();

        List<Object> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
            iterator.forEachRemaining(results::add);
        }

        // Messages of the "same" session share short-term memory and count 1, 2, 3 in their
        // arrival order, while the independent "other" session starts from 1. A pass-through
        // selector that replaces the session key with the whole message would count every
        // "same" message as 1.
        assertThat(results).containsExactlyInAnyOrder("same:1", "same:2", "same:3", "other:1");
    }

    @Test
    void fromDataStreamUsesExplicitSelectorEvenOnKeyedInput() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // The stream is keyed by the whole record; the explicit selector keys by session, and
        // the selector must win: the "same" messages share short-term memory either way only
        // if the session key is applied rather than the stream's own whole-record key.
        KeyedStream<String, String> keyedByWholeRecord =
                env.fromData("same:first", "same:second", "other:first").keyBy(value -> value);

        DataStream<Object> output =
                agentsEnv
                        .fromDataStream(keyedByWholeRecord, value -> value.split(":")[0])
                        .apply(new CountingAgent())
                        .toDataStream();

        List<Object> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
            iterator.forEachRemaining(results::add);
        }

        assertThat(results).containsExactlyInAnyOrder("same:1", "same:2", "other:1");
    }

    @Test
    void fromDataStreamKeysUnkeyedInputBySelector() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        DataStream<String> unkeyed = env.fromData("same:first", "same:second", "other:first");

        DataStream<Object> output =
                agentsEnv
                        .fromDataStream(unkeyed, value -> value.split(":")[0])
                        .apply(new CountingAgent())
                        .toDataStream();

        List<Object> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
            iterator.forEachRemaining(results::add);
        }

        assertThat(results).containsExactlyInAnyOrder("same:1", "same:2", "other:1");
    }

    @Test
    void fromDataStreamRejectsUnkeyedInputWithoutSelector() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        DataStream<String> unkeyed = env.fromData("same:first");

        assertThatThrownBy(
                        () ->
                                agentsEnv
                                        .fromDataStream(unkeyed)
                                        .apply(new CountingAgent())
                                        .toDataStream())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key selector must be provided");
    }

    @Test
    void fromTableRejectsMissingSelector() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);
        Table input = tableEnv.fromValues(DataTypes.STRING(), "same:first");

        assertThatThrownBy(() -> agentsEnv.fromTable(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key selector")
                .hasMessageContaining("Table input");
        assertThatThrownBy(() -> agentsEnv.fromTable(input, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key selector")
                .hasMessageContaining("Table input");
    }

    @Test
    void fromTableUsesExplicitBusinessKey() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);
        Table input =
                tableEnv.fromValues(DataTypes.STRING(), "same:first", "same:second", "other:first");

        DataStream<Object> output =
                agentsEnv
                        .fromTable(input, row -> ((String) ((Row) row).getField(0)).split(":")[0])
                        .apply(new CountingAgent())
                        .toDataStream();

        List<Object> results = new ArrayList<>();
        try (CloseableIterator<Object> iterator = output.executeAndCollect()) {
            iterator.forEachRemaining(results::add);
        }
        assertThat(results).containsExactlyInAnyOrder("same:1", "same:2", "other:1");
    }

    /** Model-free agent counting inputs per business key in short-term memory. */
    public static class CountingAgent extends Agent {

        @Action(EventType.InputEvent)
        public static void countInputs(Event event, RunnerContext context) throws Exception {
            Object value = InputEvent.fromEvent(event).getInput();
            String input = (String) (value instanceof Row ? ((Row) value).getField(0) : value);
            String session = input.split(":")[0];
            MemoryObject memory = context.getShortTermMemory();
            int count = 1;
            if (memory.isExist("count")) {
                count = ((Number) memory.get("count").getValue()).intValue() + 1;
            }
            memory.set("count", count);
            context.sendEvent(new OutputEvent(session + ":" + count));
        }
    }
}
