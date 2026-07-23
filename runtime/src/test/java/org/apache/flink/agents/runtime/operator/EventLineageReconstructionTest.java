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
package org.apache.flink.agents.runtime.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.EventLogLevel;
import org.apache.flink.agents.api.logger.LoggerType;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.agents.runtime.eventlog.EventLogRecord;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the event log alone contains the minimal causal chain between Events. */
class EventLineageReconstructionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recordsMinimalEventLineageInTheEventLog(@TempDir Path logDir) throws Exception {
        AgentPlan agentPlan =
                ActionExecutionOperatorTest.TestAgent.getAgentPlanWithConfig(
                        fileLoggerConfig(logDir));

        try (KeyedOneInputStreamOperatorTestHarness<Long, Long, Object> harness =
                new KeyedOneInputStreamOperatorTestHarness<>(
                        new ActionExecutionOperatorFactory<>(agentPlan, true),
                        (KeySelector<Long, Long>) value -> value,
                        TypeInformation.of(Long.class))) {
            harness.open();
            harness.processElement(new StreamRecord<>(1L));
            ((ActionExecutionOperator<Long, Object>) harness.getOperator())
                    .waitInFlightEventsFinished();
        }

        Map<String, JsonNode> recordsByType = readRecordsByType(logDir);
        EventLogRecord inputRecord =
                MAPPER.treeToValue(recordsByType.get(InputEvent.EVENT_TYPE), EventLogRecord.class);
        EventLogRecord outputRecord =
                MAPPER.treeToValue(recordsByType.get(OutputEvent.EVENT_TYPE), EventLogRecord.class);
        JsonNode input = recordsByType.get(InputEvent.EVENT_TYPE).get("event");
        JsonNode middle =
                recordsByType
                        .get(ActionExecutionOperatorTest.TestAgent.MiddleEvent.EVENT_TYPE)
                        .get("event");
        JsonNode output = recordsByType.get(OutputEvent.EVENT_TYPE).get("event");

        assertThat(recordsByType).hasSize(3);
        assertThat(inputRecord.getEvent().getType()).isEqualTo(InputEvent.EVENT_TYPE);
        assertThat(outputRecord.getEvent().getType()).isEqualTo(OutputEvent.EVENT_TYPE);
        assertThat(input.has("upstreamEventId")).isFalse();
        assertThat(input.has("upstreamActionName")).isFalse();

        assertThat(middle.get("upstreamEventId").isTextual()).isTrue();
        assertThat(middle.get("upstreamActionName").isTextual()).isTrue();
        assertThat(middle.get("upstreamEventId").asText()).isEqualTo(input.get("id").asText());
        assertThat(middle.get("upstreamActionName").asText()).isEqualTo("action1");

        assertThat(output.get("upstreamEventId").isTextual()).isTrue();
        assertThat(output.get("upstreamActionName").isTextual()).isTrue();
        assertThat(output.get("upstreamEventId").asText()).isEqualTo(middle.get("id").asText());
        assertThat(output.get("upstreamActionName").asText()).isEqualTo("action2");

        assertThat(middle.get("attributes").has("upstreamEventId")).isFalse();
        assertThat(middle.get("attributes").has("upstreamActionName")).isFalse();
    }

    private static AgentConfiguration fileLoggerConfig(Path logDir) {
        AgentConfiguration config = new AgentConfiguration();
        config.set(AgentConfigOptions.EVENT_LOGGER_TYPE, LoggerType.FILE);
        config.set(AgentConfigOptions.BASE_LOG_DIR, logDir.toString());
        config.set(AgentConfigOptions.EVENT_LOG_LEVEL, EventLogLevel.STANDARD);
        config.set(AgentConfigOptions.EVENT_LOG_MAX_STRING_LENGTH, 3);
        return config;
    }

    private static Map<String, JsonNode> readRecordsByType(Path logDir) throws Exception {
        List<String> lines;
        try (Stream<Path> files = Files.list(logDir)) {
            List<Path> logFiles =
                    files.filter(path -> path.getFileName().toString().endsWith(".log"))
                            .collect(Collectors.toList());
            assertThat(logFiles).hasSize(1);
            lines = Files.readAllLines(logFiles.get(0));
        }

        Map<String, JsonNode> recordsByType = new LinkedHashMap<>();
        for (String line : lines) {
            JsonNode record = MAPPER.readTree(line);
            recordsByType.put(record.get("eventType").asText(), record);
        }
        return recordsByType;
    }
}
