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

package org.apache.flink.agents.resource.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** Pure Java agents must start Python solely because they declare a package Skill source. */
public class PackageSkillsCrossLanguageTest {
    @TempDir Path temp;

    public static void readSkill(Event event, RunnerContext ctx) throws Exception {
        Tool tool = (Tool) ctx.getResource("load_skill", ResourceType.TOOL);
        ToolResponse body = tool.call(new ToolParameters(Map.of("name", "demo")));
        ToolResponse attachment =
                tool.call(
                        new ToolParameters(
                                Map.of("name", "demo", "path", "references/example.txt")));
        if (!body.isSuccess() || !attachment.isSuccess()) {
            throw new IllegalStateException(body.getError() + ":" + attachment.getError());
        }
        ctx.sendEvent(new OutputEvent(body.getText() + "\nAttachment: " + attachment.getText()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void javaAgentReadsInstalledWheel(boolean yaml) throws Exception {
        String packageName =
                "issue1194_skills_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String python = System.getenv().getOrDefault("PYTHON_EXECUTABLE", "python");
        Path installer =
                Path.of(
                        getClass()
                                .getClassLoader()
                                .getResource("python/install_skill_fixture.py")
                                .toURI());
        Process process =
                new ProcessBuilder(python, installer.toString(), temp.toString(), packageName)
                        .redirectErrorStream(true)
                        .start();
        String installLog =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(installLog).isZero();
        Path installed = temp.resolve("installed");
        Configuration config = new Configuration();
        config.setString("python.executable", python);
        config.setString(
                "python.pythonpath",
                installed
                        + java.io.File.pathSeparator
                        + System.getenv().getOrDefault("PYTHONPATH", ""));
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        AgentsExecutionEnvironment agents = AgentsExecutionEnvironment.getExecutionEnvironment(env);
        Agent agent = new Agent();
        agent.addResource(
                "packaged", ResourceType.SKILLS, Skills.fromPackage(packageName, "skills"));
        agent.addAction(
                new String[] {InputEvent.EVENT_TYPE},
                getClass().getMethod("readSkill", Event.class, RunnerContext.class));
        org.apache.flink.streaming.api.datastream.DataStream<Object> output;
        if (yaml) {
            Path declaration = temp.resolve("agent.yaml");
            Files.writeString(
                    declaration,
                    "agents:\n  - name: package_agent\n    skills:\n      - name: packaged\n        package:\n          - package: "
                            + packageName
                            + "\n            resource: skills\n    actions:\n      - name: read_skill\n        type: java\n        function: "
                            + getClass().getName()
                            + ":readSkill\n        trigger_conditions: [input]\n");
            agents.loadYaml(declaration);
            output =
                    agents.fromDataStream(env.fromData("read"), value -> "key")
                            .apply("package_agent")
                            .toDataStream();
        } else {
            output =
                    agents.fromDataStream(env.fromData("read"), value -> "key")
                            .apply(agent)
                            .toDataStream();
        }
        List<Object> actual = new ArrayList<>();
        try (CloseableIterator<Object> results = output.collectAsync()) {
            agents.execute();
            while (results.hasNext()) actual.add(results.next());
        }
        assertThat(actual).hasSize(1);
        String result = actual.get(0).toString();
        assertThat(result).contains("Instructions from wheel", "Attachment: wheel-attachment");
        Matcher directory =
                Pattern.compile("Base directory for this skill: ([^\n]+)").matcher(result);
        assertThat(directory.find()).isTrue();
        assertThat(Path.of(directory.group(1))).doesNotExist();
        assertThat(installed.resolve(packageName + "/skills/demo/SKILL.md")).exists();
    }
}
