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

package org.apache.flink.agents.integrations.vectorstores.elasticsearch;

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the {@code k} / {@code num_candidates} constructor validation of {@link
 * ElasticsearchVectorStore}.
 *
 * <p>Elasticsearch requires {@code num_candidates >= k} for a KNN search, so that combination is
 * the one the constructor must accept.
 */
class ElasticsearchVectorStoreKNumCandidatesTest {

    private static ElasticsearchVectorStore store(int k, int numCandidates) {
        ResourceDescriptor descriptor =
                ResourceDescriptor.Builder.newBuilder(ElasticsearchVectorStore.class.getName())
                        .addInitialArgument("embedding_model", "emb")
                        .addInitialArgument("index", "idx")
                        .addInitialArgument("dims", 5)
                        .addInitialArgument("k", k)
                        .addInitialArgument("num_candidates", numCandidates)
                        .build();
        return new ElasticsearchVectorStore(
                descriptor, ResourceContext.fromGetResource((name, type) -> null));
    }

    @Test
    void acceptsNumCandidatesGreaterThanK() {
        assertThatCode(() -> store(10, 20)).doesNotThrowAnyException();
    }

    @Test
    void acceptsNumCandidatesEqualToK() {
        assertThatCode(() -> store(10, 10)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNumCandidatesLessThanK() {
        assertThatThrownBy(() -> store(20, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("num_candidates");
    }

    @Test
    void exposesStoreLevelKUnderTheKeyQueryReads() {
        assertThat(store(7, 20).getStoreKwargs()).containsEntry("k", 7);
    }
}
