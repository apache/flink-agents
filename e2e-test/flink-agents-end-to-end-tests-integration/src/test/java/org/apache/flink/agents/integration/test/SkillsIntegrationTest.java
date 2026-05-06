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

package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.skills.Skills;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.apache.flink.agents.api.agents.AgentExecutionOptions.ERROR_HANDLING_STRATEGY;
import static org.apache.flink.agents.api.agents.AgentExecutionOptions.MAX_RETRIES;

/**
 * End-to-end tests for agent skills. Mirrors the Python {@code
 * python/flink_agents/e2e_tests/e2e_tests_integration/agent_skills_test.py}, including both the
 * workflow-style agent and the {@link ReActAgent} variant.
 *
 * <ul>
 *   <li>{@link #testWorkflowWithSkills()} — feeds prompts through {@link SkillsIntegrationAgent}
 *       (workflow agent) and asserts on the math/joke responses.
 *   <li>{@link #testReActAgentWithSkills()} — uses {@link ReActAgent} with a structured output
 *       schema; asserts the parsed {@code result} field equals 8 ({@code 2 ^ 3}).
 * </ul>
 *
 * <p>Skipped unless {@code ACTION_API_KEY} (the GitHub Actions-injected env var, mirroring the
 * Python test) is set; small local models do not reliably handle the multi-turn skill-loading flow.
 * {@code ACTION_BASE_URL} optionally overrides the default dashscope endpoint.
 */
public class SkillsIntegrationTest {

    @Test
    public void testWorkflowWithSkills() throws Exception {
        Assumptions.assumeTrue(
                System.getenv().get("ACTION_API_KEY") != null,
                "ACTION_API_KEY is required for the skills end-to-end test.");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<String> inputStream =
                env.fromData(
                        "Please evaluate the expression: (2 ^ 3)", "Tell me a joke about cat.");

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, (KeySelector<String, String>) value -> value)
                        .apply(new SkillsIntegrationAgent())
                        .toDataStream();

        CloseableIterator<Object> results = outputStream.collectAsync();
        agentsEnv.execute();

        List<String> responses = new ArrayList<>();
        while (results.hasNext()) {
            responses.add(String.valueOf(results.next()));
        }

        Assertions.assertEquals(
                2, responses.size(), String.format("Expected 2 responses, got: %s", responses));

        String text = String.join("\n", responses);
        Assertions.assertTrue(
                text.contains("8"),
                String.format("Math response should contain '8'. Full responses: %s", text));
        Assertions.assertTrue(
                text.contains("Too many cheetahs"),
                String.format(
                        "Joke response should contain script punchline 'Too many cheetahs'. "
                                + "Full responses: %s",
                        text));
    }

    @Test
    public void testReActAgentWithSkills() throws Exception {
        Assumptions.assumeTrue(
                System.getenv().get("ACTION_API_KEY") != null,
                "ACTION_API_KEY is required for the skills end-to-end test.");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);

        String apiKey = System.getenv("ACTION_API_KEY");
        String baseUrl =
                System.getenv()
                        .getOrDefault("ACTION_BASE_URL", SkillsIntegrationAgent.DEFAULT_BASE_URL);

        // Resolve the bundled skills/ test resource directory (same fixtures as the workflow test).
        URL url =
                Objects.requireNonNull(
                        SkillsIntegrationTest.class.getClassLoader().getResource("skills"),
                        "skills/ test resources are missing");
        String skillsPath = Paths.get(url.getPath()).toString();

        agentsEnv
                .addResource(
                        "openai",
                        ResourceType.CHAT_MODEL_CONNECTION,
                        ResourceDescriptor.Builder.newBuilder(
                                        ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                                .addInitialArgument("api_key", apiKey)
                                .addInitialArgument("api_base_url", baseUrl)
                                .build())
                .addResource("my_skill", ResourceType.SKILLS, Skills.fromLocalDir(skillsPath));

        agentsEnv.getConfig().set(ERROR_HANDLING_STRATEGY, ReActAgent.ErrorHandlingStrategy.RETRY);
        agentsEnv.getConfig().set(MAX_RETRIES, 3);

        ResourceDescriptor chatModelDescriptor =
                ResourceDescriptor.Builder.newBuilder(
                                ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
                        .addInitialArgument("connection", "openai")
                        .addInitialArgument("model", SkillsIntegrationAgent.MODEL)
                        .addInitialArgument("skills", List.of("math-calculator"))
                        .addInitialArgument("allowed_commands", List.of("echo", "bc"))
                        .build();

        Prompt prompt =
                Prompt.fromMessages(
                        List.of(
                                new ChatMessage(
                                        MessageRole.SYSTEM,
                                        "You are a math calculate assistant. Use the math-calculator "
                                                + "skill when asked to evaluate an expression. You "
                                                + "must load the skill first and strictly follow the "
                                                + "instructions of the skill."),
                                new ChatMessage(
                                        MessageRole.USER,
                                        "Please evaluate the expression: {a} ^ {b}")));

        RowTypeInfo outputTypeInfo =
                new RowTypeInfo(
                        new TypeInformation[] {BasicTypeInfo.INT_TYPE_INFO},
                        new String[] {"result"});

        Agent agent = new ReActAgent(chatModelDescriptor, prompt, outputTypeInfo);

        Table inputTable =
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("a", DataTypes.INT()),
                                DataTypes.FIELD("b", DataTypes.INT())),
                        Row.of(2, 3));

        Schema outputSchema =
                Schema.newBuilder()
                        .column("f0", DataTypes.ROW(DataTypes.FIELD("result", DataTypes.INT())))
                        .build();

        Table outputTable =
                agentsEnv
                        .fromTable(
                                inputTable,
                                (KeySelector<Object, Integer>)
                                        value -> (Integer) ((Row) value).getField("a"))
                        .apply(agent)
                        .toTable(outputSchema);

        CloseableIterator<Row> results =
                tableEnv.toDataStream(outputTable)
                        .map((MapFunction<Row, Row>) x -> (Row) x.getField("f0"))
                        .collectAsync();

        env.execute();

        Assertions.assertTrue(
                results.hasNext(),
                "ReAct agent did not produce any output — the LLM response may not have matched the "
                        + "output schema; rerun if so.");
        Row row = (Row) results.next();
        Object result = row.getField("result");
        Assertions.assertNotNull(result, String.format("Missing result field in row %s", row));
        Assertions.assertEquals(
                8, ((Integer) result).intValue(), String.format("Expected 2 ^ 3 = 8, got %s", row));
    }
}
