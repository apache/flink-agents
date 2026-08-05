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

package org.apache.flink.agents.api.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the payload-typing behavior of {@link Result} across a durable-style JSON round-trip. */
public class ResultTest {

    /** Plain mapper with no polymorphic typing, as used to re-bind durable results on recovery. */
    private static final ObjectMapper RECOVERY_MAPPER = new ObjectMapper();

    /** A POJO payload, standing in for a record returned by an external sub-agent. */
    public static class Review {
        public String verdict;
        public int score;

        public Review() {}

        Review(String verdict, int score) {
            this.verdict = verdict;
            this.score = score;
        }
    }

    @Test
    void typedAccessorConvertsThePayloadOnFirstExecution() {
        Result result = Result.ok(new Review("approve", 7));

        Review review = result.getResult(Review.class);

        assertThat(review.verdict).isEqualTo("approve");
        assertThat(review.score).isEqualTo(7);
    }

    @Test
    void typedAccessorRecoversThePayloadTypeAfterAJsonRoundTrip() throws Exception {
        Result original = Result.ok(new Review("approve", 7));
        String serialized = RECOVERY_MAPPER.writeValueAsString(original);

        // Recovery re-binds through a plain mapper: the payload degrades to a LinkedHashMap.
        Result replayed = RECOVERY_MAPPER.readValue(serialized, Result.class);

        assertThat(replayed.getResult()).isInstanceOf(Map.class);

        Review review = replayed.getResult(Review.class);

        assertThat(review.verdict).isEqualTo("approve");
        assertThat(review.score).isEqualTo(7);
    }
}
