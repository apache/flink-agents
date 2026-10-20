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
package org.apache.flink.agents.runtime.chat;

import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.runtime.context.JavaRunnerContextImpl;
import org.apache.flink.agents.runtime.subgraph.SubgraphExecution;

/** Durable chat future for use within its creating action execution. */
public final class ChatDurableFuture implements DurableFuture<ChatResult> {
    private final JavaRunnerContextImpl context;
    private final ChatRequestEvent request;
    private ChatResponseEvent response;

    public ChatDurableFuture(JavaRunnerContextImpl context, ChatRequestEvent request) {
        context.checkMailboxThread();
        this.context = context;
        this.request = request;
    }

    @Override
    public ChatResult await() throws Exception {
        context.checkMailboxThread();
        if (response == null) {
            // Its own statement ahead of building the invocation: folded into one expression,
            // invocationFor(context) would evaluate first and fail on an uninitialized context.
            SubgraphExecution.requireReleasableOperator(context);
            String id =
                    SubgraphExecution.prepare(
                            context, ChatSubgraphDefinition.invocationFor(context), request);
            SubgraphExecution.await(context, id);
            response = SubgraphExecution.finish(context, id);
        }
        return response.getResponse();
    }
}
