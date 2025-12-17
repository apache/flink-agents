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
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ContextRetrievalRequestEvent;
import org.apache.flink.agents.api.event.ContextRetrievalResponseEvent;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.vectorstores.BaseVectorStore;
import org.apache.flink.agents.api.vectorstores.VectorStoreQuery;
import org.apache.flink.agents.api.vectorstores.VectorStoreQueryResult;
import org.apache.flink.agents.plan.JavaFunction;

import java.util.List;

/** Built-in action for processing context retrieval requests. */
public class ContextRetrievalAction {

    public static Action getContextRetrievalAction() throws Exception {
        return new Action(
                "context_retrieval_action",
                new JavaFunction(
                        ContextRetrievalAction.class,
                        "processContextRetrievalRequest",
                        new Class[] {Event.class, RunnerContext.class}),
                List.of(ContextRetrievalRequestEvent.class.getName()));
    }

    public static void processContextRetrievalRequest(Event event, RunnerContext ctx)
            throws Exception {
        if (event instanceof ContextRetrievalRequestEvent) {
            final ContextRetrievalRequestEvent contextRetrievalRequestEvent =
                    (ContextRetrievalRequestEvent) event;

            final BaseVectorStore vectorStore =
                    (BaseVectorStore)
                            ctx.getResource(
                                    contextRetrievalRequestEvent.getVectorStore(),
                                    ResourceType.VECTOR_STORE);

            final VectorStoreQuery vectorStoreQuery =
                    new VectorStoreQuery(
                            contextRetrievalRequestEvent.getQuery(),
                            contextRetrievalRequestEvent.getMaxResults());

            final VectorStoreQueryResult result = vectorStore.query(vectorStoreQuery);

            ctx.sendEvent(
                    new ContextRetrievalResponseEvent(
                            contextRetrievalRequestEvent.getId(),
                            contextRetrievalRequestEvent.getQuery(),
                            result.getDocuments()));
        }
    }
}
