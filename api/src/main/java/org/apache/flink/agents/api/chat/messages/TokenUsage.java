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

package org.apache.flink.agents.api.chat.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Per-invocation token counts. Null means unknown; zero is a valid measurement. */
public final class TokenUsage {
    private static final String PROMPT_TOKENS_FIELD = "prompt_tokens";
    private static final String COMPLETION_TOKENS_FIELD = "completion_tokens";
    private static final String PROMPT_TOKEN_DETAILS_FIELD = "prompt_token_details";
    private static final String COMPLETION_TOKEN_DETAILS_FIELD = "completion_token_details";

    private final Long promptTokens;
    private final Long completionTokens;
    private final Map<String, Long> promptTokenDetails;
    private final Map<String, Long> completionTokenDetails;

    @JsonCreator
    public TokenUsage(
            @JsonProperty(PROMPT_TOKENS_FIELD)
                    @JsonDeserialize(using = TokenCountDeserializer.class)
                    Long promptTokens,
            @JsonProperty(COMPLETION_TOKENS_FIELD)
                    @JsonDeserialize(using = TokenCountDeserializer.class)
                    Long completionTokens,
            @JsonProperty(PROMPT_TOKEN_DETAILS_FIELD)
                    @JsonDeserialize(contentUsing = TokenCountDeserializer.class)
                    Map<String, Long> promptTokenDetails,
            @JsonProperty(COMPLETION_TOKEN_DETAILS_FIELD)
                    @JsonDeserialize(contentUsing = TokenCountDeserializer.class)
                    Map<String, Long> completionTokenDetails) {
        this.promptTokens = check(promptTokens);
        this.completionTokens = check(completionTokens);
        this.promptTokenDetails = validateDetails(promptTokenDetails);
        this.completionTokenDetails = validateDetails(completionTokenDetails);
    }

    public TokenUsage(Long promptTokens, Long completionTokens) {
        this(promptTokens, completionTokens, null, null);
    }

    private static Long check(Long n) {
        if (n != null && n < 0) {
            throw new IllegalArgumentException("Token counts must be nonnegative");
        }
        return n;
    }

    private static Map<String, Long> validateDetails(Map<String, Long> details) {
        if (details == null) {
            return new HashMap<>();
        }
        details.forEach(
                (key, value) -> {
                    Objects.requireNonNull(key, "Token detail key");
                    check(Objects.requireNonNull(value, "Token detail count"));
                });
        return details;
    }

    @JsonProperty(PROMPT_TOKENS_FIELD)
    public Long getPromptTokens() {
        return promptTokens;
    }

    @JsonProperty(COMPLETION_TOKENS_FIELD)
    public Long getCompletionTokens() {
        return completionTokens;
    }

    @JsonProperty(PROMPT_TOKEN_DETAILS_FIELD)
    public Map<String, Long> getPromptTokenDetails() {
        return promptTokenDetails;
    }

    @JsonProperty(COMPLETION_TOKEN_DETAILS_FIELD)
    public Map<String, Long> getCompletionTokenDetails() {
        return completionTokenDetails;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TokenUsage)) {
            return false;
        }
        TokenUsage that = (TokenUsage) other;
        return Objects.equals(promptTokens, that.promptTokens)
                && Objects.equals(completionTokens, that.completionTokens)
                && Objects.equals(promptTokenDetails, that.promptTokenDetails)
                && Objects.equals(completionTokenDetails, that.completionTokenDetails);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                promptTokens, completionTokens, promptTokenDetails, completionTokenDetails);
    }

    /** Strict wire token count, aligned with Python's signed 64-bit count. */
    static final class TokenCountDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(
                        Long.class, "Token count must be a JSON integer");
            }
            long count = parser.getLongValue();
            if (count < 0) {
                return context.reportInputMismatch(Long.class, "Token count must be nonnegative");
            }
            return count;
        }
    }
}
