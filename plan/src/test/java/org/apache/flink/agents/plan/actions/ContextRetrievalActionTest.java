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

package org.apache.flink.agents.plan.actions;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.configuration.ReadableConfiguration;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Context retrieval copies optional request context onto the response only. */
class ContextRetrievalActionTest {

    @Test
    void successEchoesContextAndLeavesTheQueryUnchanged() throws Exception {
        Harness harness = new Harness();
        when(harness.store.query(any()))
                .thenReturn(
                        new VectorStoreQueryResult(List.of(new Document("body", Map.of(), "d1"))));
        Map<String, Object> context = Map.of("task", "retrieve", "item_id", "42");
        ContextRetrievalRequestEvent request =
                new ContextRetrievalRequestEvent("what is flink", "store", 2, context);
        request.setAttachment("trace", "keep-on-request");

        ContextRetrievalAction.processContextRetrievalRequest(request, harness.ctx);

        ContextRetrievalResponseEvent response =
                ContextRetrievalResponseEvent.fromEvent(harness.sent.get(0));
        assertThat(response.getRequestId()).isEqualTo(request.getId());
        assertThat(response.getQuery()).isEqualTo("what is flink");
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getContext()).isEqualTo(context);
        assertThat(response.getAttachments()).isEmpty();
        verify(harness.store)
                .query(
                        org.mockito.ArgumentMatchers.argThat(
                                (VectorStoreQuery query) ->
                                        "what is flink".equals(query.getQueryText())
                                                && Integer.valueOf(2).equals(query.getLimit())));
    }

    @Test
    void omittedContextStaysAbsent() throws Exception {
        Harness harness = new Harness();
        when(harness.store.query(any())).thenReturn(new VectorStoreQueryResult(List.of()));

        ContextRetrievalAction.processContextRetrievalRequest(
                new ContextRetrievalRequestEvent("q", "store"), harness.ctx);

        ContextRetrievalResponseEvent response =
                ContextRetrievalResponseEvent.fromEvent(harness.sent.get(0));
        assertThat(response.getContext()).isNull();
        assertThat(response.getAttributes()).doesNotContainKey("context");
    }

    @Test
    void queryFailurePropagatesWithoutAResponseAndKeepsTheRequestContext() throws Exception {
        Harness harness = new Harness();
        when(harness.store.query(any())).thenThrow(new IllegalStateException("store down"));
        Map<String, Object> context = Map.of("task", "retrieve");
        ContextRetrievalRequestEvent request =
                new ContextRetrievalRequestEvent("q", "store", 3, context);

        assertThatThrownBy(
                        () ->
                                ContextRetrievalAction.processContextRetrievalRequest(
                                        request, harness.ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("store down");
        assertThat(harness.sent).isEmpty();
        assertThat(request.getContext()).isEqualTo(context);
    }

    private static final class Harness {
        private final RunnerContext ctx = mock(RunnerContext.class);
        private final BaseVectorStore store = mock(BaseVectorStore.class);
        private final List<Event> sent = new ArrayList<>();

        private Harness() throws Exception {
            ReadableConfiguration config = mock(ReadableConfiguration.class);
            when(config.get(AgentExecutionOptions.RAG_ASYNC)).thenReturn(false);
            when(ctx.getConfig()).thenReturn(config);
            when(ctx.getResource(eq("store"), eq(ResourceType.VECTOR_STORE))).thenReturn(store);
            when(ctx.durableExecute(any()))
                    .thenAnswer(
                            invocation ->
                                    invocation
                                            .<DurableCallable<VectorStoreQueryResult>>getArgument(0)
                                            .call());
            doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(ctx).sendEvent(any());
        }
    }
}
