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

package org.apache.flink.agents.api.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.DataContentBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link RerankHit}, {@link RerankResult}, and {@link BaseRerankModel}. */
class RerankModelTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rerankResultAndHitRoundTripAndPreserveImmutability() throws Exception {
        RerankHit withDoc = new RerankHit(0, 0.92, TextBlock.of("candidate"));
        RerankHit withoutDoc = new RerankHit(1, 0.45, null);
        Map<String, Object> usageWithNull = Collections.singletonMap("cached_tokens", null);
        RerankResult result =
                new RerankResult(List.of(withDoc, withoutDoc), usageWithNull, "req-1");

        String json = MAPPER.writeValueAsString(result);
        RerankResult restored = MAPPER.readValue(json, RerankResult.class);

        assertThat(restored).isEqualTo(result).hasSameHashCodeAs(result);
        assertThat(restored.toString()).isEqualTo(result.toString());
        assertThat(restored.getResults()).containsExactly(withDoc, withoutDoc);
        assertThat(restored.getResults().get(0).getDocument()).isEqualTo(TextBlock.of("candidate"));
        assertThat(restored.getResults().get(1).getDocument()).isNull();
        assertThat(restored.getUsage()).containsEntry("cached_tokens", null);
        assertThat(restored.getRequestId()).isEqualTo("req-1");

        assertThatThrownBy(() -> restored.getResults().add(withDoc))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> restored.getUsage().put("extra", 1))
                .isInstanceOf(UnsupportedOperationException.class);

        RerankResult nullUsage = new RerankResult(List.of(), null, null);
        assertThat(nullUsage.getUsage()).isEmpty();
        assertThat(nullUsage.getResults()).isEmpty();

        assertThatThrownBy(() -> new RerankResult(null, Map.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("results");
    }

    @Test
    void baseRerankModelOverloadsDelegateWithDefaults() {
        final Object[] lastCall = new Object[4];
        RerankResult expected = new RerankResult(List.of(), Map.of(), "req-delegated");
        BaseRerankModel model =
                new BaseRerankModel(new ResourceDescriptor("dummy", Map.of()), null) {
                    @Override
                    public RerankResult rerank(
                            DataContentBlock query,
                            List<? extends DataContentBlock> documents,
                            @Nullable Map<String, Object> modelParameters,
                            @Nullable Duration timeout) {
                        lastCall[0] = query;
                        lastCall[1] = documents;
                        lastCall[2] = modelParameters;
                        lastCall[3] = timeout;
                        return expected;
                    }
                };

        assertThat(model.getResourceType()).isEqualTo(ResourceType.RERANK_MODEL);

        TextBlock query = TextBlock.of("q");
        List<TextBlock> docs = List.of(TextBlock.of("d1"));
        assertThat(model.rerank(query, docs)).isSameAs(expected);
        assertThat(lastCall[0]).isEqualTo(query);
        assertThat(lastCall[1]).isEqualTo(docs);
        assertThat(lastCall[2]).isEqualTo(Map.of());
        assertThat(lastCall[3]).isNull();

        assertThat(model.rerank(query, docs, (Map<String, Object>) null)).isSameAs(expected);
        assertThat(lastCall[2]).isNull();
        assertThat(lastCall[3]).isNull();
    }
}
