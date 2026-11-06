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
import org.apache.flink.runtime.OperatorIDPair;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.graph.StreamNode;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the optional operator UID of {@code AgentBuilder.apply(agent, uid)} and {@code
 * AgentBuilder.apply(agentName, uid)}.
 *
 * <p>Each test builds a job through the public builder and inspects the {@link StreamGraph} and
 * {@link JobGraph}; no job is executed.
 */
class AgentOperatorUidTest {

    private static final String AGENT_NAME = "uid-test-agent";
    private static final String UID = "my-agent-uid";

    /** Minimal agent so a plan can be built; the graph is inspected without executing it. */
    public static class EchoAgent extends Agent {
        @Action(EventType.InputEvent)
        public static void handle(Event event, RunnerContext ctx) {
            // Never runs: the UID is asserted at graph-construction time.
        }
    }

    /** How the test applies the agent to the builder. */
    private interface Applier {
        AgentBuilder apply(AgentsExecutionEnvironment agentsEnv, AgentBuilder builder);
    }

    private static StreamGraph buildGraph(Applier applier, boolean prependUpstreamMap) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.disableOperatorChaining();
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getAgents().put(AGENT_NAME, new EchoAgent());
        DataStream<String> input = env.fromData(List.of("a", "b", "c"));
        if (prependUpstreamMap) {
            // An extra upstream operator changes the topology the generated IDs are derived from.
            input = input.map(value -> value).name("extra-upstream-operator");
        }
        AgentBuilder builder = agentsEnv.fromDataStream(input, value -> value);
        applier.apply(agentsEnv, builder).toDataStream().print();
        return env.getStreamGraph();
    }

    private static boolean isAgentNode(StreamNode node) {
        // The operator is named after the agent: its class for apply(agent), its name for
        // apply(agentName).
        return EchoAgent.class.getSimpleName().equals(node.getOperatorName())
                || AGENT_NAME.equals(node.getOperatorName());
    }

    private static StreamNode agentNode(StreamGraph graph) {
        List<StreamNode> nodes =
                graph.getStreamNodes().stream()
                        .filter(AgentOperatorUidTest::isAgentNode)
                        .collect(Collectors.toList());
        assertThat(nodes).hasSize(1);
        return nodes.get(0);
    }

    /** Operator IDs of the agent operator; chaining is disabled so it is its own vertex. */
    private static OperatorIDPair agentOperatorIds(StreamGraph graph) {
        String agentOperatorName = agentNode(graph).getOperatorName();
        List<JobVertex> vertices =
                StreamSupport.stream(graph.getJobGraph().getVertices().spliterator(), false)
                        .filter(vertex -> vertex.getName().equals(agentOperatorName))
                        .collect(Collectors.toList());
        assertThat(vertices).hasSize(1);
        assertThat(vertices.get(0).getOperatorIDs()).hasSize(1);
        return vertices.get(0).getOperatorIDs().get(0);
    }

    @Test
    void applyAgentWithUidSetsTransformationAndOperatorUid() {
        StreamGraph graph = buildGraph((e, b) -> b.apply(new EchoAgent(), UID), false);

        assertThat(agentNode(graph).getTransformationUID()).isEqualTo(UID);
    }

    @Test
    void applyAgentNameWithUidSetsTransformationAndOperatorUid() {
        StreamGraph graph = buildGraph((e, b) -> b.apply(AGENT_NAME, UID), false);

        assertThat(agentNode(graph).getTransformationUID()).isEqualTo(UID);
    }

    @Test
    void tableInputSetsUidOnAgentOperator() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);
        Table input = tableEnv.fromValues(DataTypes.STRING(), "a", "b");

        agentsEnv
                .fromTable(input, row -> ((Row) row).getField(0))
                .apply(new EchoAgent(), UID)
                .toDataStream()
                .print();

        assertThat(agentNode(env.getStreamGraph()).getTransformationUID()).isEqualTo(UID);
    }

    @Test
    void yamlLoadedAgentSetsUidOnAgentOperator(@TempDir Path dir) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        Path yaml =
                Files.writeString(
                        dir.resolve("agent.yaml"),
                        "agents:\n  - name: "
                                + AGENT_NAME
                                + "\n    actions:\n      - name: handle\n        function: "
                                + EchoAgent.class.getName()
                                + ":handle\n        type: java\n        trigger_conditions: [_input_event]\n");
        agentsEnv.loadYaml(yaml);

        agentsEnv
                .fromDataStream(env.fromData(List.of("a", "b")), value -> value)
                .apply(AGENT_NAME, UID)
                .toDataStream()
                .print();

        assertThat(agentNode(env.getStreamGraph()).getTransformationUID()).isEqualTo(UID);
    }

    @Test
    void sameUidKeepsOperatorIdAcrossUpstreamTopologyChange() {
        OperatorIDPair before =
                agentOperatorIds(buildGraph((e, b) -> b.apply(new EchoAgent(), UID), false));
        OperatorIDPair after =
                agentOperatorIds(buildGraph((e, b) -> b.apply(new EchoAgent(), UID), true));

        // With a UID set, Flink derives the operator ID from the UID, not from the topology.
        assertThat(after.getGeneratedOperatorID()).isEqualTo(before.getGeneratedOperatorID());
    }

    @Test
    void differentUidsGiveDifferentOperatorIds() {
        OperatorIDPair first =
                agentOperatorIds(buildGraph((e, b) -> b.apply(new EchoAgent(), "uid-1"), false));
        OperatorIDPair second =
                agentOperatorIds(buildGraph((e, b) -> b.apply(new EchoAgent(), "uid-2"), false));

        assertThat(first.getGeneratedOperatorID()).isNotEqualTo(second.getGeneratedOperatorID());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUidChangeAfterBuildingOutput(boolean byName) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getAgents().put(AGENT_NAME, new EchoAgent());
        AgentBuilder builder =
                agentsEnv
                        .fromDataStream(env.fromData(List.of("a")), value -> value)
                        .apply(new EchoAgent(), UID);
        DataStream<Object> output = builder.toDataStream();

        assertThatThrownBy(
                        () -> {
                            if (byName) {
                                builder.apply(AGENT_NAME, "changed-uid");
                            } else {
                                builder.apply(new EchoAgent(), "changed-uid");
                            }
                        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after the output stream has been built");

        assertThat(builder.toDataStream()).isSameAs(output);
        assertThat(output.getTransformation().getUid()).isEqualTo(UID);
    }

    @Test
    void rejectsReapplyingWithoutUidAfterBuildingOutput() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        AgentBuilder builder =
                agentsEnv
                        .fromDataStream(env.fromData(List.of("a")), value -> value)
                        .apply(new EchoAgent(), UID);
        DataStream<Object> output = builder.toDataStream();

        assertThatThrownBy(() -> builder.apply(new EchoAgent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after the output stream has been built");
        assertThat(builder.toDataStream()).isSameAs(output);
        assertThat(output.getTransformation().getUid()).isEqualTo(UID);
    }

    @Test
    void canChangeUidBeforeBuildingOutput() {
        StreamGraph graph =
                buildGraph(
                        (e, b) ->
                                b.apply(new EchoAgent(), "previous-uid")
                                        .apply(new EchoAgent(), UID),
                        false);

        assertThat(agentNode(graph).getTransformationUID()).isEqualTo(UID);
    }

    @Test
    void omittedUidLeavesGeneratedOperatorIdUnchanged() {
        // Control: apply(agent) is the existing path. apply(agent, null) must build the same graph.
        OperatorIDPair withoutUid =
                agentOperatorIds(buildGraph((e, b) -> b.apply(new EchoAgent()), false));
        OperatorIDPair nullUid =
                agentOperatorIds(
                        buildGraph((e, b) -> b.apply(new EchoAgent(), (String) null), false));
        OperatorIDPair nullUidByName =
                agentOperatorIds(buildGraph((e, b) -> b.apply(AGENT_NAME, null), false));
        OperatorIDPair byName = agentOperatorIds(buildGraph((e, b) -> b.apply(AGENT_NAME), false));

        assertThat(withoutUid.getUserDefinedOperatorID()).isEmpty();
        assertThat(nullUid.getUserDefinedOperatorID()).isEmpty();
        assertThat(nullUidByName.getUserDefinedOperatorID()).isEmpty();
        assertThat(nullUid.getGeneratedOperatorID()).isEqualTo(withoutUid.getGeneratedOperatorID());
        assertThat(nullUidByName.getGeneratedOperatorID())
                .isEqualTo(byName.getGeneratedOperatorID());
        StreamGraph graph = buildGraph((e, b) -> b.apply(new EchoAgent()), false);
        assertThat(agentNode(graph).getTransformationUID()).isNull();
    }

    @Test
    void omittedUidChangesGeneratedIdWhenTopologyChanges() {
        // Documents why a UID is needed: without it the generated ID follows the topology.
        OperatorIDPair before =
                agentOperatorIds(buildGraph((e, b) -> b.apply(new EchoAgent()), false));
        OperatorIDPair after =
                agentOperatorIds(buildGraph((e, b) -> b.apply(new EchoAgent()), true));

        assertThat(after.getGeneratedOperatorID()).isNotEqualTo(before.getGeneratedOperatorID());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void blankUidIsRejectedForAgent(String uid) {
        assertThatThrownBy(() -> buildGraph((e, b) -> b.apply(new EchoAgent(), uid), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty or blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void blankUidIsRejectedForAgentName(String uid) {
        assertThatThrownBy(() -> buildGraph((e, b) -> b.apply(AGENT_NAME, uid), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty or blank");
    }
}
