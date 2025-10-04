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
package org.apache.flink.agents.examples.agents;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.prompt.Prompt;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.integrations.chatmodels.ollama.OllamaChatModelConnection;

import java.util.Arrays;
import java.util.List;

/** Custom types and resources for the quickstart agents. */
public class CustomTypesAndResources {

    // Prompt for review analysis agent
    public static final String REVIEW_ANALYSIS_SYSTEM_PROMPT_STR =
            "Analyze the user review and product information to determine a "
                    + "satisfaction score (1-5) and potential reasons for dissatisfaction.\n\n"
                    + "Example input format:\n"
                    + "{\n"
                    + "    \"id\": \"12345\",\n"
                    + "    \"review\": \"The headphones broke after one week of use. Very poor quality.\"\n"
                    + "}\n\n"
                    + "Ensure your response can be parsed by Java JSON, using this format as an example:\n"
                    + "{\n"
                    + " \"id\": \"12345\",\n"
                    + " \"score\": 1,\n"
                    + " \"reasons\": [\n"
                    + "   \"poor quality\"\n"
                    + "   ]\n"
                    + "}\n\n"
                    + "Please note that if a product review includes dissatisfaction with the shipping process,\n"
                    + "you should first notify the shipping manager using the appropriate tools. After executing\n"
                    + "the tools, strictly follow the example above to provide your score and reason — there is\n"
                    + "no need to disclose whether the tool was used.";

    public static final Prompt REVIEW_ANALYSIS_PROMPT =
            new Prompt(
                    Arrays.asList(
                            new ChatMessage(MessageRole.SYSTEM, REVIEW_ANALYSIS_SYSTEM_PROMPT_STR),
                            new ChatMessage(MessageRole.USER, "\"input\":\n" + "{input}")));

    // Prompt for review analysis react agent
    public static final Prompt REVIEW_ANALYSIS_REACT_PROMPT =
            new Prompt(
                    Arrays.asList(
                            new ChatMessage(MessageRole.SYSTEM, REVIEW_ANALYSIS_SYSTEM_PROMPT_STR),
                            new ChatMessage(
                                    MessageRole.USER,
                                    "{\"id\": \"{id}\",\n" + "\"review\": \"{review}\"}")));

    // Prompt for product suggestion agent
    public static final String PRODUCT_SUGGESTION_PROMPT_STR =
            "Based on the rating distribution and user dissatisfaction reasons, generate three actionable suggestions for product improvement.\n\n"
                    + "Input format:\n"
                    + "{\n"
                    + "    \"id\": \"1\",\n"
                    + "    \"score_histogram\": [\"10%\", \"20%\", \"10%\", \"15%\", \"45%\"],\n"
                    + "    \"unsatisfied_reasons\": [\"reason1\", \"reason2\", \"reason3\"]\n"
                    + "}\n\n"
                    + "Ensure that your response can be parsed by Java JSON, use the following format as an example:\n"
                    + "{\n"
                    + "    \"suggestion_list\": [\n"
                    + "        \"suggestion1\",\n"
                    + "        \"suggestion2\",\n"
                    + "        \"suggestion3\"\n"
                    + "    ]\n"
                    + "}\n\n"
                    + "input:\n"
                    + "{input}";

    public static final Prompt PRODUCT_SUGGESTION_PROMPT =
            new Prompt(PRODUCT_SUGGESTION_PROMPT_STR);

    /**
     * Tool for notifying the shipping manager when product received a negative review due to
     * shipping damage.
     *
     * @param id The id of the product that received a negative review due to shipping damage
     * @param review The negative review content
     */
    public static String notifyShippingManager(String id, String review) {
        String content =
                String.format(
                        "Transportation issue for product [%s], the customer feedback: %s",
                        id, review);
        System.out.println(content);
        return content;
    }

    // Ollama chat model connection descriptor
    public static final ResourceDescriptor OLLAMA_SERVER_DESCRIPTOR =
            ResourceDescriptor.Builder.newBuilder(OllamaChatModelConnection.class.getName())
                    .addInitialArgument("requestTimeout", 120)
                    .addInitialArgument("endpoint", "http://localhost:11434")
                    .build();

    /** Data model representing a product review. */
    @JsonSerialize
    @JsonDeserialize
    public static class ProductReview {
        private final String id;
        private final String review;

        @JsonCreator
        public ProductReview(@JsonProperty("id") String id, @JsonProperty("review") String review) {
            this.id = id;
            this.review = review;
        }

        public String getId() {
            return id;
        }

        public String getReview() {
            return review;
        }

        @Override
        public String toString() {
            return String.format("ProductReview{id='%s', review='%s'}", id, review);
        }
    }

    /** Data model representing analysis result of a product review. */
    @JsonSerialize
    @JsonDeserialize
    public static class ProductReviewAnalysisRes {
        private final String id;
        private final int score;
        private final List<String> reasons;

        @JsonCreator
        public ProductReviewAnalysisRes(
                @JsonProperty("id") String id,
                @JsonProperty("score") int score,
                @JsonProperty("reasons") List<String> reasons) {
            this.id = id;
            this.score = score;
            this.reasons = reasons;
        }

        public ProductReviewAnalysisRes() {
            id = null;
            score = 0;
            reasons = List.of();
        }

        public String getId() {
            return id;
        }

        public int getScore() {
            return score;
        }

        public List<String> getReasons() {
            return reasons;
        }

        @Override
        public String toString() {
            return String.format(
                    "ProductReviewAnalysisRes{id='%s', score=%d, reasons=%s}", id, score, reasons);
        }
    }

    /** Aggregates multiple reviews and insights using LLM for a product. */
    @JsonSerialize
    @JsonDeserialize
    public static class ProductReviewSummary {
        private final String id;
        private final List<String> scoreHist;
        private final List<String> unsatisfiedReasons;

        @JsonCreator
        public ProductReviewSummary(
                @JsonProperty("id") String id,
                @JsonProperty("scoreHist") List<String> scoreHist,
                @JsonProperty("unsatisfiedReasons") List<String> unsatisfiedReasons) {
            this.id = id;
            this.scoreHist = scoreHist;
            this.unsatisfiedReasons = unsatisfiedReasons;
        }

        public String getId() {
            return id;
        }

        public List<String> getScoreHist() {
            return scoreHist;
        }

        public List<String> getUnsatisfiedReasons() {
            return unsatisfiedReasons;
        }

        @Override
        public String toString() {
            return String.format(
                    "ProductReviewSummary{id='%s', scoreHist=%s, unsatisfiedReasons=%s}",
                    id, scoreHist, unsatisfiedReasons);
        }
    }

    /** Provides a summary of review data including suggestions for improvement. */
    @JsonSerialize
    @JsonDeserialize
    public static class ProductSuggestion {
        private final String id;
        private final List<String> scoreHist;
        private final List<String> suggestions;

        @JsonCreator
        public ProductSuggestion(
                @JsonProperty("id") String id,
                @JsonProperty("scoreHist") List<String> scoreHist,
                @JsonProperty("suggestions") List<String> suggestions) {
            this.id = id;
            this.scoreHist = scoreHist;
            this.suggestions = suggestions;
        }

        public String getId() {
            return id;
        }

        public List<String> getScoreHist() {
            return scoreHist;
        }

        public List<String> getSuggestions() {
            return suggestions;
        }

        @Override
        public String toString() {
            return String.format(
                    "ProductSuggestion{id='%s', scoreHist=%s, suggestions=%s}",
                    id, scoreHist, suggestions);
        }
    }
}
