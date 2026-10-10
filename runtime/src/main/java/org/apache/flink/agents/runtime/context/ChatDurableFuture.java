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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.context.DurableFuture;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.runtime.async.ContinuationActionExecutor;
import org.apache.flink.agents.runtime.chat.ChatCallManager;
import org.apache.flink.agents.runtime.chat.ChatInvocation;

/** Durable chat future for use within its creating action execution. */
final class ChatDurableFuture implements DurableFuture<ChatMessage> {
    private final JavaRunnerContextImpl context;
    private final ChatCallManager calls;
    private final String requestJson;
    private ChatResponseEvent response;

    ChatDurableFuture(JavaRunnerContextImpl context, ChatRequestEvent request) {
        context.mailboxThreadChecker.run();
        this.context = context;
        this.calls = context.getChatContext().getManager();
        try {
            this.requestJson = RunnerContextImpl.OBJECT_MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalArgumentException("Chat request is not serializable", e);
        }
    }

    @Override
    public ChatMessage await() throws Exception {
        context.mailboxThreadChecker.run();
        if (response == null) {
            if (!ContinuationActionExecutor.isContinuationSupported()
                    || context.getContinuationContext() == null
                    || context.getContinuationExecutor() == null) {
                throw new UnsupportedOperationException(
                        "Java chat calls require the JDK 21 continuation runtime");
            }
            String id = calls.prepareCall(context, requestJson);
            // Capture the invocation on the action thread: the async worker must not read the
            // shared runner context or invocation table while another action is using them.
            ChatInvocation pending = calls.get(id);
            if (!pending.isDone()) {
                // Like internal sub-agents, wait on an async worker while the action yields.
                // ChatCallManager owns replay and persistence; do not add a second durable slot.
                context.resolveAsync(pending::awaitResponse);
            }
            response = (ChatResponseEvent) Event.fromJson(calls.tryCompleteCall(context, id));
        }
        return response.getResponse();
    }
}
