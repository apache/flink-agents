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
import org.apache.flink.agents.plan.routing.ModelRoutingResolver;
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
 * sees every attempt uniformly.
 */
public final class ChatModelInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModelInvoker.class);

    private ChatModelInvoker() {}

    /** JSON-serializable result of provider execution, including ordinary failures. */
    public static final class InvocationOutcome {
        public ChatMessage response;
        public String error;

        public InvocationOutcome() {}

        InvocationOutcome(ChatMessage response, String error) {
            this.response = response;
            this.error = error;
        }
    }

    /** Text already formatted at the provider boundary; do not add a wrapper type to it. */
    public static final class InvocationFailure extends Exception {
        public InvocationFailure(String error) {
            super(error);
        }
    }

    public static String errorText(Exception error) {
        while ((error instanceof java.lang.reflect.InvocationTargetException
                        || error instanceof java.util.concurrent.ExecutionException
                        || error instanceof java.util.concurrent.CompletionException)
                && error.getCause() instanceof Exception) {
            error = (Exception) error.getCause();
        }
        return error instanceof InvocationFailure
                        || error instanceof ModelRoutingResolver.RoutingFailure
                ? error.getMessage()
                : error.getClass().getName() + ": " + String.valueOf(error.getMessage());
    }

    public static final class ChatAttemptResult {
        public final String model;
        public final BaseChatModelSetup chatModel;
        public final ChatMessage response;
        public final int retryCount;
        public final int totalRetryWaitSec;

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

    public static final class ChatAttemptFailed extends Exception {
        public final String model;
        public final BaseChatModelSetup chatModel;
        public final Exception error;
        public final int retryCount;
        public final int totalRetryWaitSec;

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

    /** The request-level retry budget. */
    public static int configuredRetries(RunnerContext ctx) {
        return Math.max(ctx.getConfig().get(AgentExecutionOptions.MAX_RETRIES), 0);
    }

    /** The request-level retry backoff. */
    public static int configuredRetryWaitSec(RunnerContext ctx) {
        return Math.max(ctx.getConfig().get(AgentExecutionOptions.RETRY_WAIT_INTERVAL), 0);
    }

    public static ChatAttemptResult chatWithRetries(
            UUID initialRequestId,
            String model,
            String durableCallId,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            RunnerContext ctx,
            int numRetries,
            int retryWaitIntervalSec)
            throws ChatAttemptFailed, Exception {
        BaseChatModelSetup chatModel;
        try {
            chatModel = (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);
        } catch (Exception e) {
            if (ModelRoutingResolver.isCancellation(e)) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }
            // An unresolvable candidate (e.g. a typo in the router's candidate list) counts as
            // that candidate failing, so the fallback loop sees
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

        DurableCallable<InvocationOutcome> callable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return durableCallId;
                    }

                    @Override
                    public Class<InvocationOutcome> getResultClass() {
                        return InvocationOutcome.class;
                    }

                    @Override
                    public InvocationOutcome call() throws Exception {
                        try {
                            return new InvocationOutcome(
                                    chatModel.chat(messages, promptArgs, Map.of()), null);
                        } catch (Exception e) {
                            if (ModelRoutingResolver.isCancellation(e)) {
                                if (e instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                                throw e;
                            }
                            LOG.debug("Chat provider failed", e);
                            return new InvocationOutcome(null, errorText(e));
                        }
                    }
                };
        Map<String, Object> llmMetadata =
                chatModel.getModel() == null
                        ? Map.of()
                        : Map.of(LLMExecutionMetadataKeys.MODEL, chatModel.getModel());

        for (int attempt = 0; attempt < numRetries + 1; attempt++) {
            ExecutionReporters.started(ctx, ExecutionReporter.EntityTypes.LLM, model, llmMetadata);
            // Keep persistence and recovery failures outside the request-failure catch.
            InvocationOutcome outcome;
            try {
                outcome =
                        chatAsync
                                ? ctx.durableExecuteAsync(callable).await()
                                : ctx.durableExecute(callable);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            try {
                try {
                    if (outcome.error != null) {
                        throw new InvocationFailure(outcome.error);
                    }
                    response = outcome.response;
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
                // extra model call, regardless of the configured retry budget.
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Chat execution interrupted");
                }
                if (ModelRoutingResolver.isCancellation(e)) {
                    throw e;
                }
                if (attempt < numRetries) {
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
