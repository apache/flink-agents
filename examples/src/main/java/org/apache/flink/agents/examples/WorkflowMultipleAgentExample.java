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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.examples.agents.CustomTypesAndResources;
import org.apache.flink.agents.examples.agents.ProductSuggestionAgent;
import org.apache.flink.agents.examples.agents.ReviewAnalysisAgent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.agents.examples.WorkflowSingleAgentExample.copyResource;
import static org.apache.flink.agents.examples.agents.CustomTypesAndResources.ProductReviewAnalysisRes;
import static org.apache.flink.agents.examples.agents.CustomTypesAndResources.ProductReviewSummary;

/**
 * Java example demonstrating multiple workflow agents for product improvement suggestion.
 *
 * <p>This example demonstrates a multi-stage streaming pipeline using Flink Agents:
 *
 * <ol>
 *   <li>Reads product reviews from a source as a streaming source.
 *   <li>Uses an LLM agent to analyze each review and extract score and unsatisfied reasons.
 *   <li>Aggregates the analysis results in 1-minute tumbling windows, producing score distributions
 *       and collecting all unsatisfied reasons.
 *   <li>Uses another LLM agent to generate product improvement suggestions based on the aggregated
 *       analysis.
 *   <li>Prints the final suggestions to stdout.
 * </ol>
 */
public class WorkflowMultipleAgentExample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * ProcessWindowFunction to aggregate score distribution and dislike reasons.
     *
     * <p>This class aggregates multiple ProductReviewAnalysisRes elements within a window,
     * calculating score distribution percentages and collecting all unsatisfied reasons.
     */
    static class AggregateScoreDistributionAndDislikeReasons
            extends ProcessWindowFunction<ProductReviewAnalysisRes, String, String, TimeWindow> {

        @Override
        public void process(
                String key,
                Context context,
                Iterable<ProductReviewAnalysisRes> elements,
                Collector<String> out)
                throws JsonProcessingException {

            // Initialize rating counts for scores 1-5
            int[] ratingCounts = new int[5];
            List<String> reasonList = new ArrayList<>();

            // Process each element in the window
            for (CustomTypesAndResources.ProductReviewAnalysisRes element : elements) {
                int rating = element.getScore();
                if (rating >= 1 && rating <= 5) {
                    ratingCounts[rating - 1]++;
                }
                reasonList.addAll(element.getReasons());
            }

            // Calculate total and percentages
            int total = 0;
            for (int count : ratingCounts) {
                total += count;
            }

            // Calculate percentages and format them
            List<String> formattedPercentages = new ArrayList<>();
            if (total > 0) {
                for (int count : ratingCounts) {
                    double percentage = Math.round((count * 100.0 / total) * 10.0) / 10.0;
                    formattedPercentages.add(String.format("%.1f%%", percentage));
                }
            } else {
                // If no ratings, set all to 0%
                for (int i = 0; i < 5; i++) {
                    formattedPercentages.add("0.0%");
                }
            }

            // Create and emit the aggregated result
            ProductReviewSummary summary =
                    new ProductReviewSummary(key, formattedPercentages, reasonList);
            out.collect(MAPPER.writeValueAsString(summary));
        }
    }

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Set up the Flink streaming environment and the Agents execution environment.
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Add Ollama chat model connection to be used by the ReviewAnalysisAgent
        // and ProductSuggestionAgent.
        agentsEnv.addResource(
                "ollamaChatModelConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                CustomTypesAndResources.OLLAMA_SERVER_DESCRIPTOR);

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
                        "streaming-agent-example");

        // Use the ReviewAnalysisAgent (LLM) to analyze each review.
        // The agent extracts the review score and unsatisfied reasons.
        DataStream<Object> reviewAnalysisResStream =
                agentsEnv
                        .fromDataStream(productReviewStream)
                        .apply(new ReviewAnalysisAgent())
                        .toDataStream();

        // Aggregate the analysis results in 1-minute tumbling windows.
        // This produces a score distribution and collects all unsatisfied reasons for
        // each
        // product.
        DataStream<String> aggregatedAnalysisResStream =
                reviewAnalysisResStream
                        .map(element -> (ProductReviewAnalysisRes) element)
                        .keyBy(ProductReviewAnalysisRes::getId)
                        .window(TumblingProcessingTimeWindows.of(Duration.ofMinutes(1)))
                        .process(new AggregateScoreDistributionAndDislikeReasons());

        // Use the ProductSuggestionAgent (LLM) to generate product improvement
        // suggestions
        // based on the aggregated analysis results.
        DataStream<Object> productSuggestionResStream =
                agentsEnv
                        .fromDataStream(aggregatedAnalysisResStream)
                        .apply(new ProductSuggestionAgent())
                        .toDataStream();

        // Print the final product improvement suggestions to stdout.
        productSuggestionResStream.print();

        // Execute the pipeline.
        agentsEnv.execute();
    }
}
