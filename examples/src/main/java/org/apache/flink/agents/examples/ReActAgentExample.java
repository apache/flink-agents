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
package org.apache.flink.agents.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.agents.ReActAgent;
import org.apache.flink.agents.api.annotation.Prompt;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelSetup;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.Row;

import java.io.File;
import java.time.Duration;
import java.util.Collections;

import static org.apache.flink.agents.examples.WorkflowSingleAgentExample.copyResource;

/**
 * Java example demonstrating the ReActAgent for product review analysis and shipping notification.
 *
 * <p>This example demonstrates how to use the Flink Agents to analyze product reviews and record
 * shipping questions in a streaming pipeline. The pipeline reads product reviews from a source,
 * deserializes each review, and uses an LLM agent to extract review scores and unsatisfied reasons.
 * If the unsatisfied reasons are related to shipping, the agent will notify the shipping manager.
 * This serves as a minimal, end-to-end example of integrating LLM-powered react agent with Flink
 * streaming jobs.
 */
public class ReActAgentExample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tool for notifying the shipping manager when product received a negative review due to
     * shipping damage.
     *
     * @param id The id of the product that received a negative review due to shipping damage
     * @param review The negative review content
     */
    @Tool(
            description =
                    "Notify the shipping manager when product received a negative review due to shipping damage.")
    public static void notifyShippingManager(
            @ToolParam(name = "id") String id, @ToolParam(name = "review") String review) {
        org.apache.flink.agents.examples.agents.CustomTypesAndResources.notifyShippingManager(
                id, review);
    }

    @Prompt
    public static org.apache.flink.agents.api.prompt.Prompt reviewAnalysisReactPrompt() {
        return CustomTypesAndResources.REVIEW_ANALYSIS_REACT_PROMPT;
    }

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Set up the Flink streaming environment and the Agents execution environment.
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Add Ollama chat model connection and record shipping question tool to be used
        // by the Agent.
        agentsEnv
                .addResource(
                        "ollamaChatModelConnection",
                        ResourceType.CHAT_MODEL_CONNECTION,
                        CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR)
                .addResource(
                        "notifyShippingManager",
                        ResourceType.TOOL,
                        org.apache.flink.agents.api.tools.Tool.fromMethod(
                                ReActAgentExample.class.getMethod(
                                        "notifyShippingManager", String.class, String.class)));

        // Read product reviews from input_data.txt file as a streaming source.
        // Each element represents a ProductReview.

        File inputDataFile = copyResource("input_data.txt");

        DataStream<Row> productReviewStream =
                env.fromSource(
                                FileSource.forRecordStreamFormat(
                                                new TextLineInputFormat(),
                                                new Path(inputDataFile.getAbsolutePath()))
                                        .monitorContinuously(Duration.ofMinutes(1))
                                        .build(),
                                WatermarkStrategy.noWatermarks(),
                                "react-agent-example")
                        .map(
                                inputStr -> {
                                    Row row = Row.withNames();
                                    CustomTypesAndResources.ProductReview productReview =
                                            MAPPER.readValue(
                                                    inputStr,
                                                    CustomTypesAndResources.ProductReview.class);
                                    row.setField("id", productReview.getId());
                                    row.setField("review", productReview.getReview());
                                    return row;
                                });

        // Create react agent
        ReActAgent reviewAnalysisReactAgent = getReActAgent();

        // Use the ReAct agent to analyze each product review and
        // record shipping question.
        DataStream<Object> reviewAnalysisResStream =
                agentsEnv
                        .fromDataStream(productReviewStream)
                        .apply(reviewAnalysisReactAgent)
                        .toDataStream();

        // Print the analysis results to stdout.
        reviewAnalysisResStream.print();

        // Execute the Flink pipeline.
        agentsEnv.execute();
    }

    // Create ReAct agent.
    private static ReActAgent getReActAgent() {
        return new ReActAgent(
                ResourceDescriptor.Builder.newBuilder(OllamaChatModelSetup.class.getName())
                        .addInitialArgument("connection", "ollamaChatModelConnection")
                        .addInitialArgument("model", "qwen3:8b")
                        .addInitialArgument(
                                "tools", Collections.singletonList("notifyShippingManager"))
                        .build(),
                reviewAnalysisReactPrompt(),
                CustomTypesAndResources.ProductReviewAnalysisRes.class);
    }
}
