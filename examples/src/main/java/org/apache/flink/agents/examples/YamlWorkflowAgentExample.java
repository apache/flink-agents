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
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.io.File;

import static org.apache.flink.agents.examples.WorkflowSingleAgentExample.copyResource;

/**
 * Java example demonstrating a YAML-declared workflow agent.
 *
 * <p>The agent (chat model, prompt, tool, and actions) is declared in {@code
 * yaml_review_analysis_agent.yaml} on the classpath. The YAML file's {@code function:} fields point
 * at the static methods of {@link org.apache.flink.agents.examples.agents.ReviewAnalysisAgent}
 * already defined for the code-only quickstart, so this example demonstrates the minimal delta
 * between a code-defined and a YAML-declared agent.
 *
 * <p>Pipeline:
 *
 * <pre>{@code
 * FileSource (input_data.txt) -> AgentsEnv.apply("review_analysis_agent") -> print
 * }</pre>
 */
public class YamlWorkflowAgentExample {

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Set up the Flink streaming environment and the Agents execution environment.
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // limit async request to avoid overwhelming ollama server
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        // Load the YAML — the declared agent and its chat-model connection,
        // chat-model setup, prompt, tool, and actions are all registered on
        // the environment in this single call.
        File yamlFile = copyResource("yaml/yaml_review_analysis_agent.yaml");
        agentsEnv.loadYaml(yamlFile.toPath());

        // Read product reviews from input_data.txt file as a streaming source.
        // Each element represents a ProductReview.
        File inputDataFile = copyResource("input_data.txt");
        DataStream<String> productReviewStream =
                env.fromSource(
                        FileSource.forRecordStreamFormat(
                                        new TextLineInputFormat(),
                                        new Path(inputDataFile.getAbsolutePath()))
                                .build(),
                        WatermarkStrategy.noWatermarks(),
                        "yaml-review-analysis-example");

        // Apply the YAML-declared agent BY NAME — ``apply`` accepts either an
        // Agent instance or the name of an agent registered on the environment.
        DataStream<Object> reviewAnalysisResStream =
                agentsEnv
                        .fromDataStream(productReviewStream)
                        .apply("review_analysis_agent")
                        .toDataStream();

        // Print the analysis results to stdout.
        reviewAnalysisResStream.print();

        // Execute the Flink pipeline with the Flink job name.
        agentsEnv.execute("YAML Agent Example Job");
    }
}
