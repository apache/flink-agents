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
package org.apache.flink.agents.api.chat.model;

/**
 * The number of input tokens a chat request is expected to use, and whether that number was counted
 * by the provider's own rules or approximated.
 *
 * @see BaseChatModelConnection#estimateInputTokens
 */
public final class TokenEstimate {

    private final long tokens;
    private final boolean approximate;

    private TokenEstimate(long tokens, boolean approximate) {
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens must not be negative, got " + tokens);
        }
        this.tokens = tokens;
        this.approximate = approximate;
    }

    /** A count made with the provider's own tokenization of the request. */
    public static TokenEstimate exact(long tokens) {
        return new TokenEstimate(tokens, false);
    }

    /** A count derived from the size of the request, not from the provider's tokenization. */
    public static TokenEstimate approximate(long tokens) {
        return new TokenEstimate(tokens, true);
    }

    public long getTokens() {
        return tokens;
    }

    /** Whether the count is an approximation, as opposed to the provider's own count. */
    public boolean isApproximate() {
        return approximate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TokenEstimate)) {
            return false;
        }
        TokenEstimate that = (TokenEstimate) o;
        return tokens == that.tokens && approximate == that.approximate;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(tokens) + Boolean.hashCode(approximate);
    }

    @Override
    public String toString() {
        return (approximate ? "~" : "") + tokens + " tokens";
    }
}
