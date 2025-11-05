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

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.agents.ReActAgentConfigOptions;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;

import java.util.List;

public class ReActAgentExample {
    @org.apache.flink.agents.api.annotation.Tool(
            description = "Useful function to add two numbers.")
    public static double add(@ToolParam(name = "a") Double a, @ToolParam(name = "b") Double b) {
        return a + b;
    }

    @org.apache.flink.agents.api.annotation.Tool(
            description = "Useful function to multiply two numbers.")
    public static double multiply(
            @ToolParam(name = "a") Double a, @ToolParam(name = "b") Double b) {
        return a * b;
    }

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create the table environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        tableEnv.getConfig().set("table.exec.result.display.max-column-width", "100");

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env, tableEnv);

        // register resource to agents execution environment.
        agentsEnv
                .addResource(
                        "ollama",
                        ResourceType.CHAT_MODEL_CONNECTION,
                        ResourceDescriptor.Builder.newBuilder(
                                        OllamaChatModelConnection.class.getName())
                                .addInitialArgument("endpoint", "http://localhost:11434")
                                .build())
                .addResource(
                        "add",
                        ResourceType.TOOL,
                        Tool.fromMethod(
                                ReActAgentExample.class.getMethod(
                                        "add", Double.class, Double.class)))
                .addResource(
                        "multiply",
                        ResourceType.TOOL,
                        Tool.fromMethod(
                                ReActAgentExample.class.getMethod(
                                        "multiply", Double.class, Double.class)));

        agentsEnv
                .getConfig()
                .set(
                        ReActAgentConfigOptions.ERROR_HANDLING_STRATEGY,
                        ReActAgent.ErrorHandlingStrategy.IGNORE);

        // Declare the ReAct agent.
        Agent agent = getAgent();

        // Create input table from sample data
        Table inputTable =
                tableEnv.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("a", DataTypes.DOUBLE()),
                                DataTypes.FIELD("b", DataTypes.DOUBLE()),
                                DataTypes.FIELD("c", DataTypes.DOUBLE())),
                        Row.of(1, 2, 3));

        // Define output schema
        Schema outputSchema =
                Schema.newBuilder()
                        .column("f0", DataTypes.ROW(DataTypes.FIELD("result", DataTypes.DOUBLE())))
                        .build();

        // Apply agent to the Table
        Table outputTable =
                agentsEnv
                        .fromTable(
                                inputTable,
                                (KeySelector<Object, Double>)
                                        value -> (Double) ((Row) value).getField("a"))
                        .apply(agent)
                        .toTable(outputSchema);

        // Print the results to fully display the data
        tableEnv.toDataStream(outputTable)
                .map((MapFunction<Row, Row>) x -> (Row) x.getField("f0"))
                .print();
        env.execute();
    }

    // create ReAct agent.
    private static Agent getAgent() {
        ResourceDescriptor chatModelDescriptor =
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                        .addInitialArgument("connection", "ollama")
                        .addInitialArgument("model", "qwen3:8b")
                        .addInitialArgument("tools", List.of("add", "multiply"))
                        .addInitialArgument("extract_reasoning", "true")
                        .build();

        Prompt prompt =
                new Prompt(
                        List.of(
                                new ChatMessage(
                                        MessageRole.SYSTEM,
                                        "An example of output is {\"result\": 30.32}"),
                                new ChatMessage(MessageRole.USER, "What is ({a} + {b}) * {c}")));
        RowTypeInfo outputTypeInfo =
                new RowTypeInfo(
                        new TypeInformation[] {BasicTypeInfo.DOUBLE_TYPE_INFO},
                        new String[] {"result"});
        return new ReActAgent(chatModelDescriptor, prompt, outputTypeInfo);
    }
}
