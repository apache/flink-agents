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

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources;
import org.apache.flink.agents.examples.agents.ReviewAnalysisAgent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.Objects;

/**
 * Java example demonstrating a single workflow agent for product review analysis.
 *
 * <p>This example demonstrates how to use Flink Agents to analyze product reviews in a streaming
 * pipeline. The pipeline reads product reviews from a source, deserializes each review, and uses an
 * LLM agent to extract review scores and unsatisfied reasons. The results are printed to stdout.
 * This serves as a minimal, end-to-end example of integrating LLM-powered agents with Flink
 * streaming jobs.
 */
public class WorkflowSingleAgentExample {

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Set up the Flink streaming environment and the Agents execution environment.
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Add Ollama chat model connection to be used by the ReviewAnalysisAgent.
        agentsEnv.addResource(
                "ollamaChatModelConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);

        // Read product reviews from input_data.txt file as a streaming source.
        // Each element represents a ProductReview.
        DataStream<String> productReviewStream =
                env.fromSource(
                        FileSource.forRecordStreamFormat(
                                        new TextLineInputFormat(),
                                        new Path(
                                                Objects.requireNonNull(
                                                                WorkflowSingleAgentExample.class
                                                                        .getClassLoader()
                                                                        .getResource(
                                                                                "input_data.txt"))
                                                        .getPath()))
                                .build(),
                        WatermarkStrategy.noWatermarks(),
                        "streaming-agent-example");

        // Use the ReviewAnalysisAgent to analyze each product review.
        DataStream<Object> reviewAnalysisResStream =
                agentsEnv
                        .fromDataStream(productReviewStream)
                        .apply(new ReviewAnalysisAgent())
                        .toDataStream();

        // Print the analysis results to stdout.
        reviewAnalysisResStream.print();

        // Execute the Flink pipeline.
        agentsEnv.execute();
    }
}
