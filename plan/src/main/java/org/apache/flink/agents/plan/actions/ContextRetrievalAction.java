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
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.Document;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.apache.flink.agents.api.vectorstores.python.PythonVectorStore;
import org.apache.flink.agents.plan.JavaFunction;

import java.util.List;

import static org.apache.flink.agents.plan.actions.Utils.supportAsync;

/** Built-in action for processing context retrieval requests. */
public class ContextRetrievalAction {

    public static Action getContextRetrievalAction() throws Exception {
        return new Action(
                "context_retrieval_action",
                new JavaFunction(
                        ContextRetrievalAction.class,
                        "processContextRetrievalRequest",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(ContextRetrievalRequestEvent.EVENT_TYPE));
    }

    public static void processContextRetrievalRequest(Event event, RunnerContext ctx)
            throws Exception {
        if (ContextRetrievalRequestEvent.EVENT_TYPE.equals(event.getType())) {
            boolean ragAsync = ctx.getConfig().get(AgentExecutionOptions.RAG_ASYNC);

            final ContextRetrievalRequestEvent contextRetrievalRequestEvent =
                    ContextRetrievalRequestEvent.fromEvent(event);

            final BaseVectorStore vectorStore =
                    (BaseVectorStore)
                            ctx.getResource(
                                    contextRetrievalRequestEvent.getVectorStore(),
                                    ResourceType.VECTOR_STORE);

            if ((vectorStore instanceof PythonVectorStore) && !supportAsync()) {
                ragAsync = false;
            }

            final VectorStoreQuery vectorStoreQuery =
                    new VectorStoreQuery(
                            contextRetrievalRequestEvent.getQuery(),
                            contextRetrievalRequestEvent.getMaxResults());

            final VectorStoreQueryResult result;
            if (ragAsync && vectorStore instanceof PythonVectorStore) {
                // A Python store's query path runs numpy, which can stall on the async pool
                // (pemja keeps a single PyThreadState; numpy releasing/re-acquiring the GIL on a
                // worker thread hangs intermittently — seen in CI, fine locally with spare cores).
                // Keep only that numpy step on the mailbox thread; embed and query stay async.
                result = queryPythonAsync((PythonVectorStore) vectorStore, vectorStoreQuery, ctx);
            } else {
                DurableCallable<VectorStoreQueryResult> callable =
                        new DurableCallable<VectorStoreQueryResult>() {
                            @Override
                            public String getId() {
                                return "rag-async";
                            }

                            @Override
                            public Class<VectorStoreQueryResult> getResultClass() {
                                return VectorStoreQueryResult.class;
                            }

                            @Override
                            public VectorStoreQueryResult call() throws Exception {
                                return vectorStore.query(vectorStoreQuery);
                            }
                        };
                result =
                        ragAsync ? ctx.durableExecuteAsync(callable) : ctx.durableExecute(callable);
            }

            ctx.sendEvent(
                    new ContextRetrievalResponseEvent(
                            contextRetrievalRequestEvent.getId(),
                            contextRetrievalRequestEvent.getQuery(),
                            result.getDocuments()));
        }
    }

    /**
     * Run a Python vector-store RAG query while keeping numpy off the async pool: embed async,
     * normalize the embedding synchronously on the mailbox thread (numpy on a worker thread can
     * stall under pemja's single PyThreadState), then query async with the pre-normalized vector.
     * See https://github.com/apache/flink-agents/issues/844.
     */
    private static VectorStoreQueryResult queryPythonAsync(
            PythonVectorStore store, VectorStoreQuery query, RunnerContext ctx) throws Exception {
        final float[] embedding =
                ctx.durableExecuteAsync(
                        new DurableCallable<float[]>() {
                            @Override
                            public String getId() {
                                return "rag-embed";
                            }

                            @Override
                            public Class<float[]> getResultClass() {
                                return float[].class;
                            }

                            @Override
                            public float[] call() {
                                return store.embedQuery(query.getQueryText());
                            }
                        });

        final Object normalized = store.normalizeEmbedding(embedding);

        final List<Document> documents =
                ctx.durableExecuteAsync(
                        new DurableCallable<List<Document>>() {
                            @Override
                            public String getId() {
                                return "rag-query";
                            }

                            @SuppressWarnings("unchecked")
                            @Override
                            public Class<List<Document>> getResultClass() {
                                return (Class<List<Document>>) (Class<?>) List.class;
                            }

                            @Override
                            public List<Document> call() {
                                return store.queryNormalized(
                                        normalized,
                                        query.getLimit(),
                                        query.getCollection(),
                                        query.getFilters(),
                                        store.getStoreKwargs());
                            }
                        });

        return new VectorStoreQueryResult(documents);
    }
}
