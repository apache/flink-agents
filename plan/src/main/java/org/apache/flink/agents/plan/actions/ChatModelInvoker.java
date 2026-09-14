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

import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.chat.model.python.PythonChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.apache.flink.agents.plan.actions.Utils.supportAsync;

/**
 * Invokes one concrete chat model with the engine's durable-call and retry machinery. One call =
 * one candidate attempt: success returns a {@link ChatAttemptResult}; failure (including an
 * unresolvable model resource) surfaces as {@link ChatAttemptFailed} so the caller's fallback loop
 * and error-handling strategy see every attempt uniformly.
 */
final class ChatModelInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModelInvoker.class);

    private ChatModelInvoker() {}

    static final class ChatAttemptResult {
        final String model;
        final BaseChatModelSetup chatModel;
        final ChatMessage response;
        final int retryCount;
        final int totalRetryWaitSec;

        ChatAttemptResult(
                String model,
                BaseChatModelSetup chatModel,
                ChatMessage response,
                int retryCount,
                int totalRetryWaitSec) {
            this.model = model;
            this.chatModel = chatModel;
            this.response = response;
            this.retryCount = retryCount;
            this.totalRetryWaitSec = totalRetryWaitSec;
        }
    }

    static final class ChatAttemptFailed extends Exception {
        final String model;
        final BaseChatModelSetup chatModel;
        final Exception error;
        final int retryCount;
        final int totalRetryWaitSec;

        ChatAttemptFailed(
                String model,
                BaseChatModelSetup chatModel,
                Exception error,
                int retryCount,
                int totalRetryWaitSec) {
            super(error);
            this.model = model;
            this.chatModel = chatModel;
            this.error = error;
            this.retryCount = retryCount;
            this.totalRetryWaitSec = totalRetryWaitSec;
        }
    }

    static ChatAttemptResult chatWithRetries(
            UUID initialRequestId,
            String model,
            String durableCallId,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            RunnerContext ctx,
            Agent.ErrorHandlingStrategy strategy,
            int numRetries,
            int retryWaitIntervalSec)
            throws ChatAttemptFailed, Exception {
        BaseChatModelSetup chatModel;
        try {
            chatModel = (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);
        } catch (Exception e) {
            // An unresolvable candidate (e.g. a typo in the router's candidate list) counts as
            // that candidate failing, so the fallback loop and the error-handling strategy see
            // it like any other attempt failure instead of it escaping chat() raw and discarding
            // the previous candidate's real error.
            throw new ChatAttemptFailed(model, null, e, 0, 0);
        }
        FlinkAgentsMetricGroup requestMetricGroup = ctx.getActionMetricGroup();

        boolean chatAsync = ctx.getConfig().get(AgentExecutionOptions.CHAT_ASYNC);

        if ((chatModel instanceof PythonChatModelSetup) && !supportAsync()) {
            chatAsync = false;
        }

        int actualRetryCount = 0;
        int totalWaitTimeSec = 0;
        ChatMessage response;

        DurableCallable<ChatMessage> callable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return durableCallId;
                    }

                    @Override
                    public Class<ChatMessage> getResultClass() {
                        return ChatMessage.class;
                    }

                    @Override
                    public ChatMessage call() throws Exception {
                        return chatModel.chat(messages, promptArgs, Map.of());
                    }
                };
        Map<String, Object> llmMetadata =
                chatModel.getModel() == null
                        ? Map.of()
                        : Map.of(LLMExecutionMetadataKeys.MODEL, chatModel.getModel());

        for (int attempt = 0; attempt < numRetries + 1; attempt++) {
            try {
                ExecutionReporters.started(
                        ctx, ExecutionReporter.EntityTypes.LLM, model, llmMetadata);
                try {
                    response =
                            chatAsync
                                    ? ctx.durableExecuteAsync(callable)
                                    : ctx.durableExecute(callable);
                    Objects.requireNonNull(response, "ChatModel returned a null response.");
                } catch (Throwable modelError) {
                    throw ChatModelAction.reportFailedAndPropagate(
                            ctx,
                            ExecutionReporter.EntityTypes.LLM,
                            model,
                            llmMetadata,
                            modelError,
                            ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
                }
                ExecutionReporters.succeeded(
                        ctx, ExecutionReporter.EntityTypes.LLM, model, llmMetadata);
                ChatModelAction.recordChatTokenMetrics(chatModel, response, requestMetricGroup);
                // A truncated response consumed its full token budget, so the token metrics
                // above are recorded before this rejects and abandons the response.
                ChatModelAction.rejectIncompleteResponse(response);
                // only generate structured output for final response.
                if (outputSchema != null && response.getToolCalls().isEmpty()) {
                    response =
                            ChatModelAction.generateStructuredOutputWithReport(
                                    ctx, response, outputSchema);
                }
                return new ChatAttemptResult(
                        model, chatModel, response, actualRetryCount, totalWaitTimeSec);
            } catch (InterruptedException e) {
                // A cancellation signal, not a model failure: restore the interrupt status and
                // propagate immediately so task shutdown isn't delayed by retry backoff or an
                // extra model call, regardless of the configured error-handling strategy.
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (strategy == Agent.ErrorHandlingStrategy.RETRY && attempt < numRetries) {
                    actualRetryCount = attempt + 1;
                    int currentWaitSec = retryWaitIntervalSec * (1 << (actualRetryCount - 1));
                    LOG.warn(
                            "Chat request {} failed with error: {}, retrying {} / {}, waiting {} s.",
                            initialRequestId,
                            e,
                            actualRetryCount,
                            numRetries,
                            currentWaitSec);
                    if (currentWaitSec > 0) {
                        try {
                            Thread.sleep(currentWaitSec * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw ie;
                        }
                        totalWaitTimeSec += currentWaitSec;
                    }
                    continue;
                }
                throw new ChatAttemptFailed(
                        model, chatModel, e, actualRetryCount, totalWaitTimeSec);
            }
        }
        throw new IllegalStateException("Unreachable chat retry state.");
    }
}
