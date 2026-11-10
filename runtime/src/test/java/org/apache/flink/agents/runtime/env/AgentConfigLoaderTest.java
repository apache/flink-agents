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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.configuration.AgentConfigOptions;
import org.apache.flink.agents.api.logger.TraceLogLevel;
import org.apache.flink.agents.api.logger.TraceLogTarget;
import org.apache.flink.agents.plan.AgentConfiguration;
import org.apache.flink.agents.plan.AgentPlan;
import org.apache.flink.configuration.GlobalConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.apache.flink.agents.runtime.env.AgentConfigLoader.FLINK_CONF_FILENAME;
import static org.apache.flink.agents.runtime.env.AgentConfigLoader.LEGACY_FLINK_CONF_FILENAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class AgentConfigLoaderTest {
    private static final String FLINK_1_20 = "1.20.3";
    private static final String FLINK_2_3 = "2.3.0";

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
                AgentConfigLoader.loadAgentConfiguration(tmpDir.getAbsolutePath());

        assertThat(conf.getConfData().keySet()).hasSize(6);
        assert conf.getConfData().get("key1.key2").equals("v1");
        assert conf.getConfData().get("key1.key3").equals("v2");
        assert conf.getConfData().get("key4").equals(1);
        assert conf.getConfData().get("key5").equals("v3");
        assert conf.getConfData().get("key6").equals(1.5);
        assert conf.getConfData().get("key7").equals(true);
    }

    @Test
    void testIsFlink2OrLater() {
        assertThat(AgentConfigLoader.isFlink2OrLater("1.20.3")).isFalse();
        assertThat(AgentConfigLoader.isFlink2OrLater("1.20-vvr-11.5.0")).isFalse();
        assertThat(AgentConfigLoader.isFlink2OrLater("2.3.0")).isTrue();
        assertThat(AgentConfigLoader.isFlink2OrLater("2.0-SNAPSHOT")).isTrue();
        assertThat(AgentConfigLoader.isFlink2OrLater("<unknown>")).isFalse();
        assertThat(AgentConfigLoader.isFlink2OrLater("")).isFalse();
        assertThat(AgentConfigLoader.isFlink2OrLater(null)).isFalse();
    }

    @Test
    void testFlink1LoadsLegacyFileWhenOnlyLegacyFileExists() throws IOException {
        writeLegacyConf("agent.key1: fromLegacy");

        assertThat(load(FLINK_1_20).getConfData()).containsOnly(entry("key1", "fromLegacy"));
    }

    @Test
    void testFlink1LoadsConfigYamlWhenOnlyConfigYamlExists() throws IOException {
        writeConfigYaml("agent:", "    key1: fromConfigYaml");

        assertThat(load(FLINK_1_20).getConfData()).containsOnly(entry("key1", "fromConfigYaml"));
    }

    @Test
    void testFlink1PrefersLegacyFileOverConfigYaml() throws IOException {
        writeConfigYaml("agent:", "    key1: fromConfigYaml");
        writeLegacyConf("agent.key1: fromLegacy");

        assertThat(load(FLINK_1_20).getConfData()).containsOnly(entry("key1", "fromLegacy"));
    }

    @Test
    void testFlink1FailsWhenNoConfigFileExists() {
        assertThatThrownBy(() -> load(FLINK_1_20)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testFlink2IgnoresLegacyFile() throws IOException {
        writeLegacyConf("agent.key1: fromLegacy");

        assertThatThrownBy(() -> load(FLINK_2_3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(tmpDir.getAbsolutePath());
    }

    @Test
    void testFlink2LoadsConfigYamlWhenOnlyConfigYamlExists() throws IOException {
        writeConfigYaml("agent:", "    key1: fromConfigYaml");

        assertThat(load(FLINK_2_3).getConfData()).containsOnly(entry("key1", "fromConfigYaml"));
    }

    @Test
    void testFlink2LoadsConfigYamlWhenBothFilesExist() throws IOException {
        writeConfigYaml("agent:", "    key1: fromConfigYaml");
        writeLegacyConf("agent.key1: fromLegacy");

        assertThat(load(FLINK_2_3).getConfData()).containsOnly(entry("key1", "fromConfigYaml"));
    }

    @Test
    void testFlink2FailsWhenNoConfigFileExists() {
        assertThatThrownBy(() -> load(FLINK_2_3)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testLegacyFileAcceptsDuplicateKeysAndValuesContainingColonSpace() throws IOException {
        // Both lines are rejected by a standard YAML parser but accepted by Flink 1.20.
        writeLegacyConf(
                "env.java.opts.all: -Dfoo=bar",
                "env.java.opts.all: -Dfoo=baz",
                "kubernetes.jobmanager.annotations: owner: team-a",
                "agent.key1: first",
                "agent.key1: second",
                "agent.key2: a: b");

        assertThat(load(FLINK_1_20).getConfData())
                .containsOnly(entry("key1", "second"), entry("key2", "a: b"));
    }

    @Test
    void testLegacyFileWithoutAgentOptions() throws IOException {
        writeLegacyConf(
                "# Flink configuration as shipped by a distribution",
                "jobmanager.rpc.address: localhost",
                "taskmanager.memory.process.size: 1728m",
                "env.java.opts.all: -XX:+UseG1GC -Dfoo=bar");

        assertThat(load(FLINK_1_20).getConfData()).isEmpty();
    }

    @Test
    void testLegacyFileIgnoresNestedAgentBlock() throws IOException {
        writeLegacyConf("agent:", "    key1: nested", "agent.key2: flat");

        assertThat(load(FLINK_1_20).getConfData()).containsOnly(entry("key2", "flat"));
    }

    @Test
    void testLegacyFileValuesAreStringsAndConvertOnRead() throws IOException {
        writeLegacyConf(
                "agent.trace-log.base-dir: /tmp/agent-traces",
                "agent.trace-log.pretty-print: true");

        AgentConfiguration conf = load(FLINK_1_20);

        assertThat(conf.getConfData()).containsEntry("trace-log.pretty-print", "true");
        assertThat(conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_BASE_DIR))
                .isEqualTo("/tmp/agent-traces");
        assertThat(conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT)).isTrue();
    }

    @Test
    void testConfigYamlAcceptsNestedAndFlattenedKeys() throws IOException {
        writeConfigYaml("agent:", "    key1: v1", "agent.key2: v2");

        assertThat(load(FLINK_2_3).getConfData())
                .containsOnly(entry("key1", "v1"), entry("key2", "v2"));
    }

    @Test
    void testYamlTargetsRetainNestedValuesThroughPlanJson() throws Exception {
        writeConfigYaml(
                "agent:",
                "  trace-log:",
                "    targets:",
                "      - scope:",
                "          entityType: event",
                "          entityName: com.foo.*",
                "        level: VERBOSE");

        AgentConfiguration config = load(FLINK_2_3);
        List<TraceLogTarget> expected =
                List.of(TraceLogTarget.event("com.foo.*", TraceLogLevel.VERBOSE));
        assertThat(config.get(AgentConfigOptions.TRACE_LOG_TARGETS)).isEqualTo(expected);
        ObjectMapper mapper = new ObjectMapper();
        AgentPlan restored =
                mapper.readValue(
                        mapper.writeValueAsString(new AgentPlan(Map.of(), Map.of(), config)),
                        AgentPlan.class);
        assertThat(restored.getConfig().get(AgentConfigOptions.TRACE_LOG_TARGETS))
                .isEqualTo(expected);
    }

    @Test
    void testTypedTargetsAndYamlMatchThroughPlanJson() throws Exception {
        AgentConfiguration typed = new AgentConfiguration();
        typed.set(
                AgentConfigOptions.TRACE_LOG_TARGETS,
                List.of(
                        TraceLogTarget.events(TraceLogLevel.OFF),
                        TraceLogTarget.tools(TraceLogLevel.VERBOSE),
                        TraceLogTarget.tool("com.foo.*", TraceLogLevel.OFF),
                        TraceLogTarget.tool("com.foo.search"),
                        TraceLogTarget.tool("com.foo.visible", TraceLogLevel.STANDARD),
                        TraceLogTarget.entity("custom", null, TraceLogLevel.VERBOSE),
                        TraceLogTarget.entity("custom", "step")));
        String yaml =
                "trace-log:\n"
                        + "  targets:\n"
                        + "    - scope: EVENT_ONLY\n      level: \"OFF\"\n"
                        + "    - scope: {tool: \"*\"}\n      level: VERBOSE\n"
                        + "    - scope: {tool: com.foo.*}\n      level: \"OFF\"\n"
                        + "    - scope: {tool: com.foo.search}\n"
                        + "    - scope: {tool: com.foo.visible}\n      level: STANDARD\n"
                        + "    - scope: {entityType: custom}\n      level: VERBOSE\n"
                        + "    - scope: {entityType: custom, entityName: step}\n";
        String flinkYaml =
                "agent:\n"
                        + yaml.lines()
                                .map(line -> "  " + line + "\n")
                                .collect(java.util.stream.Collectors.joining());
        writeConfigYaml(flinkYaml);
        AgentConfiguration fromYaml = load(FLINK_2_3);
        ObjectMapper mapper = new ObjectMapper();
        AgentPlan restored =
                mapper.readValue(
                        mapper.writeValueAsString(new AgentPlan(Map.of(), Map.of(), fromYaml)),
                        AgentPlan.class);
        assertThat(fromYaml.get(AgentConfigOptions.TRACE_LOG_TARGETS))
                .isEqualTo(typed.get(AgentConfigOptions.TRACE_LOG_TARGETS));
        assertThat(restored.getConfig().get(AgentConfigOptions.TRACE_LOG_TARGETS))
                .isEqualTo(typed.get(AgentConfigOptions.TRACE_LOG_TARGETS));
    }

    @Test
    void testYamlEmptyTargetsOverrideDefaults() throws IOException {
        writeConfigYaml("agent:", "  trace-log:", "    targets: []");
        assertThat(load(FLINK_2_3).get(AgentConfigOptions.TRACE_LOG_TARGETS)).isEmpty();
    }

    @Test
    void testYamlRejectsMapValuedTargets() throws IOException {
        writeConfigYaml("agent:", "  trace-log:", "    targets: {}");
        assertThatThrownBy(() -> load(FLINK_2_3))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("trace-log.targets");
    }

    @ParameterizedTest
    @CsvSource({"1.20.3, true", "1.20.3, false", "2.3.0, true", "2.3.0, false"})
    void testConfigYamlUsesFileOrder(String flinkVersion, boolean flatFirst) throws IOException {
        String nested =
                "agent:\n"
                        + "  job-identifier: demo\n"
                        + "  max-retries: 3\n"
                        + "  trace-log:\n"
                        + "    pretty-print: true\n";
        for (String flat :
                List.of(
                        "agent.trace-log.pretty-print: false\n",
                        "agent.trace-log:\n  pretty-print: false\n")) {
            writeConfigYaml(flatFirst ? flat + nested : nested + flat);

            AgentConfiguration conf = load(flinkVersion);
            assertThat(conf.get(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT))
                    .isEqualTo(flatFirst);
            assertThat(conf.getConfData())
                    .containsEntry("job-identifier", "demo")
                    .containsEntry("max-retries", 3);
            assertThat(
                            GlobalConfiguration.loadConfiguration(tmpDir.getAbsolutePath())
                                    .toMap()
                                    .get("agent.trace-log.pretty-print"))
                    .isEqualTo(Boolean.toString(flatFirst));
        }
    }

    @ParameterizedTest
    @CsvSource({"1.20.3, true", "1.20.3, false", "2.3.0, true", "2.3.0, false"})
    void testConfigYamlUsesFileOrderWithinAgentSection(String flinkVersion, boolean flatFirst)
            throws IOException {
        String flat = "  trace-log.pretty-print: false\n";
        String nested = "  trace-log:\n    pretty-print: true\n";
        writeConfigYaml("agent:\n" + (flatFirst ? flat + nested : nested + flat));

        assertThat(load(flinkVersion).get(AgentConfigOptions.TRACE_LOG_OUTPUT_PRETTY_PRINT))
                .isEqualTo(flatFirst);
        assertThat(
                        GlobalConfiguration.loadConfiguration(tmpDir.getAbsolutePath())
                                .toMap()
                                .get("agent.trace-log.pretty-print"))
                .isEqualTo(Boolean.toString(flatFirst));
    }

    @Test
    void testFailIfConfigYamlUnparseable() throws IOException {
        writeConfigYaml("agent.key1: [unclosed");

        assertThatThrownBy(() -> load(FLINK_2_3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(tmpDir.getAbsolutePath());
    }

    private AgentConfiguration load(String flinkVersion) {
        return AgentConfigLoader.loadAgentConfiguration(tmpDir.getAbsolutePath(), flinkVersion);
    }

    private void writeLegacyConf(String... lines) throws IOException {
        Files.write(new File(tmpDir, LEGACY_FLINK_CONF_FILENAME).toPath(), Arrays.asList(lines));
    }

    private void writeConfigYaml(String... lines) throws IOException {
        Files.write(new File(tmpDir, FLINK_CONF_FILENAME).toPath(), Arrays.asList(lines));
    }

    @Test
    void testLoadAgentConfigurationFailIfNotLoaded() {
        assertThatThrownBy(
                        () ->
                                AgentConfigLoader.loadAgentConfiguration(
                                        "/some/path/" + UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testLoadAgentConfigurationFailIfNull() {
        AgentConfiguration conf = AgentConfigLoader.loadAgentConfiguration(null);
        assertThat(conf.getConfData()).isEmpty();
    }

    @Test
    void testDuplicateKeyOverridesEarlierValue() throws IOException {
        assertThat(parse("key: first", "key: second")).containsOnly(entry("key", "second"));
    }

    @Test
    void testValueContainingColonSpaceIsKeptWhole() throws IOException {
        assertThat(parse("key: a: b: c")).containsOnly(entry("key", "a: b: c"));
    }

    @Test
    void testCommentsAreDropped() throws IOException {
        assertThat(parse("# full-line comment", "key: value # trailing comment", "other: a#b"))
                .containsOnly(entry("key", "value"), entry("other", "a"));
    }

    @Test
    void testLinesWithoutKeyValuePairAreSkipped() throws IOException {
        assertThat(parse("key:value", "nested:", "key-only", "valid: yes"))
                .containsOnly(entry("valid", "yes"));
    }

    @Test
    void testEmptyKeyOrValueIsSkipped() throws IOException {
        assertThat(parse(": value", "key: ", "key2:   # only a comment", "valid: yes"))
                .containsOnly(entry("valid", "yes"));
    }

    @Test
    void testKeysAndValuesAreTrimmedAndKeptAsStrings() throws IOException {
        assertThat(parse("  key   :   1  ", "flag: true", "quoted: 'v'", "list: [a, b]"))
                .containsOnly(
                        entry("key", "1"),
                        entry("flag", "true"),
                        entry("quoted", "'v'"),
                        entry("list", "[a, b]"));
    }

    @Test
    void testIndentedLinesAreReadAsTopLevelKeys() throws IOException {
        assertThat(parse("agent:", "    key1: v1")).containsOnly(entry("key1", "v1"));
    }

    @Test
    void testMissingFileFails() {
        assertThatThrownBy(
                        () -> AgentConfigLoader.parseLegacyConfig(new File(tmpDir, "missing.yaml")))
                .isInstanceOf(IOException.class);
    }

    private Map<String, String> parse(String... lines) throws IOException {
        File file = new File(tmpDir, "flink-conf.yaml");
        Files.write(file.toPath(), Arrays.asList(lines));
        return AgentConfigLoader.parseLegacyConfig(file);
    }
}
