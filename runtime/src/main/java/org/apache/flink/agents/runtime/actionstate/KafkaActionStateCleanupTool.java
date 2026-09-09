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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.state.api.OperatorIdentifier;
import org.apache.flink.state.api.SavepointReader;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Preconditions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR;
import static org.apache.flink.agents.api.configuration.AgentConfigOptions.KAFKA_BOOTSTRAP_SERVERS;

/** Command-line entry point for planning and applying checkpoint-aligned Kafka cleanup. */
public final class KafkaActionStateCleanupTool {

    private KafkaActionStateCleanupTool() {}

    public static void main(String[] args) throws Exception {
        Preconditions.checkArgument(args.length > 0, usage());
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println(usage());
            return;
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        if ("plan".equals(command)) {
            createPlan(options);
        } else if ("apply".equals(command)) {
            applyPlan(options);
        } else {
            throw new IllegalArgumentException("Unknown command: " + command + "\n" + usage());
        }
    }

    private static void createPlan(Map<String, String> options) throws Exception {
        requireOnly(options, Set.of("checkpoint", "output", "operator-uid", "operator-uid-hash"));
        String checkpoint = required(options, "checkpoint");
        String output = required(options, "output");
        String uid = options.get("operator-uid");
        String uidHash = options.get("operator-uid-hash");
        Preconditions.checkArgument(
                (uid == null) != (uidHash == null),
                "Exactly one of --operator-uid or --operator-uid-hash is required");

        StreamExecutionEnvironment environment =
                StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setRuntimeMode(RuntimeExecutionMode.BATCH);
        SavepointReader reader = SavepointReader.read(environment, checkpoint);
        OperatorIdentifier identifier =
                uid == null
                        ? OperatorIdentifier.forUidHash(uidHash)
                        : OperatorIdentifier.forUid(uid);
        DataStream<Object> markerStream =
                reader.readUnionState(
                        identifier,
                        KafkaActionStateRecoveryMarker.UNION_STATE_NAME,
                        TypeInformation.of(Object.class));
        List<Object> markers = new ArrayList<>();
        try (CloseableIterator<Object> iterator = markerStream.executeAndCollect()) {
            iterator.forEachRemaining(markers::add);
        }

        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromRecoveryMarkers(checkpoint, markers);
        Files.writeString(
                Path.of(output),
                plan.toJson() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(
                "Created cleanup plan "
                        + plan.getPlanId()
                        + " at "
                        + Path.of(output).toAbsolutePath());
    }

    private static void applyPlan(Map<String, String> options) throws Exception {
        requireOnly(
                options,
                Set.of("plan", "bootstrap-servers", "control-topic", "replication-factor"));
        Path planPath = Path.of(required(options, "plan"));
        KafkaActionStateCleanupPlan plan =
                KafkaActionStateCleanupPlan.fromJson(
                        Files.readString(planPath, StandardCharsets.UTF_8));
        AgentConfiguration configuration = new AgentConfiguration();
        configuration.set(KAFKA_BOOTSTRAP_SERVERS, required(options, "bootstrap-servers"));
        configuration.set(
                KAFKA_ACTION_STATE_CLEANUP_CONTROL_TOPIC, required(options, "control-topic"));
        configuration.set(KAFKA_ACTION_STATE_TOPIC, plan.getTopic());
        if (options.containsKey("replication-factor")) {
            int replicationFactor = Integer.parseInt(options.get("replication-factor"));
            Preconditions.checkArgument(
                    replicationFactor > 0, "Replication factor must be positive");
            configuration.set(KAFKA_ACTION_STATE_TOPIC_REPLICATION_FACTOR, replicationFactor);
        }

        try (KafkaActionStateCleanupCoordinator coordinator =
                KafkaActionStateCleanupCoordinator.create(configuration)) {
            KafkaActionStateCleanupCoordinator.Status status = coordinator.apply(plan);
            System.out.println("Cleanup plan " + plan.getPlanId() + " is " + status);
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Preconditions.checkArgument(
                (args.length - 1) % 2 == 0, "Options must be provided as --name value pairs");
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            String name = args[index];
            Preconditions.checkArgument(name.startsWith("--"), "Invalid option: %s", name);
            String previous = options.put(name.substring(2), args[index + 1]);
            Preconditions.checkArgument(previous == null, "Duplicate option: %s", name);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        Preconditions.checkArgument(
                value != null && !value.trim().isEmpty(), "Missing required option --%s", name);
        return value;
    }

    private static void requireOnly(Map<String, String> options, Set<String> allowed) {
        options.keySet()
                .forEach(
                        option ->
                                Preconditions.checkArgument(
                                        allowed.contains(option), "Unknown option: --%s", option));
    }

    private static String usage() {
        return "Usage:\n"
                + "  plan --checkpoint PATH (--operator-uid UID | --operator-uid-hash HASH) --output FILE\n"
                + "  apply --plan FILE --bootstrap-servers SERVERS --control-topic TOPIC [--replication-factor N]";
    }
}
