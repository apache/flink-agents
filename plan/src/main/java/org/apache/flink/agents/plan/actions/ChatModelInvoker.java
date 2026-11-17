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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.ChatResult;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.metrics.FlinkAgentsMetricGroup;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.trace.ExecutionReporter;
import org.apache.flink.agents.api.trace.ExecutionReporters;
import org.apache.flink.agents.api.trace.LLMExecutionMetadataKeys;
import org.apache.flink.agents.plan.resource.python.PythonChatModelSetup;
import org.apache.flink.agents.plan.routing.ModelRoutingResolver;
import org.apache.flink.agents.plan.utils.CancellationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Leads the schema-carrying call's only message, followed by the answer to convert. The schema
     * travels in the request itself, so this says what to do with the answer rather than restating
     * the shape.
     */
    private static final String FINALIZE_DIRECTIVE =
            "Convert the response below into the required structured output format."
                    + " Preserve its meaning and do not add or infer any new information.\n\n"
                    + "Response:\n";

    private ChatModelInvoker() {}

    /** JSON-serializable result of provider execution, including ordinary failures. */
    public static final class InvocationOutcome {
        public ChatResult response;
        public String error;

        public InvocationOutcome() {}

        InvocationOutcome(ChatResult response, String error) {
            this.response = response;
            this.error = error;
        }
    }

    /**
     * Carries a durable-execution failure (persistence or recovery) past the attempt's retry
     * handling, unconverted: never a request failure.
     */
    public static final class DurableExecutionFailure extends Exception {
        public final Exception failure;

        DurableExecutionFailure(Exception failure) {
            super(failure);
            this.failure = failure;
        }
    }

    /**
     * The answer could not be shaped into the request's output schema: its text does not parse
     * against the schema, or the finalizing call for a native schema failed or came back
     * incomplete. {@link #finalized} is the finalizing call's response when it answered, for token
     * accounting; {@link #cause} is the failure as the direct path reports it.
     */
    public static final class OutputSchemaFailure extends Exception {
        public final Exception cause;
        @Nullable public final ChatResult finalized;

        OutputSchemaFailure(Exception cause, @Nullable ChatResult finalized) {
            super(cause);
            this.cause = cause;
            this.finalized = finalized;
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
        String message =
                error instanceof InvocationFailure
                                || error instanceof ModelRoutingResolver.RoutingFailure
                        ? error.getMessage()
                        : error.getClass().getName() + ": " + String.valueOf(error.getMessage());
        // Traverse the full cause chain so that deeply nested exceptions — such as
        // a RuntimeException wrapping an SDK transport error wrapping a
        // SocketTimeoutException — carry every layer's type and message. Once
        // persisted as text in an InvocationOutcome the original exception object
        // is no longer recoverable, so the chain must be captured here.
        Throwable cause = error.getCause();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(error);
        while (cause != null && visited.add(cause)) {
            String causeMessage =
                    cause instanceof InvocationFailure
                                    || cause instanceof ModelRoutingResolver.RoutingFailure
                            ? cause.getMessage()
                            : cause.getClass().getName()
                                    + ": "
                                    + String.valueOf(cause.getMessage());
            if (!causeMessage.isEmpty() && !message.contains(causeMessage)) {
                message += " [caused by: " + causeMessage + "]";
            }
            cause = cause.getCause();
        }
        return message;
    }

    public static final class ChatAttemptResult {
        public final String model;
        public final BaseChatModelSetup chatModel;
        public final ChatResult response;
        public Object structuredOutput;
        public final int retryCount;
        public final long totalRetryWaitMs;
        /** Whether the model applies the request's output schema natively; settled per attempt. */
        public final boolean nativeSchema;

        ChatAttemptResult(
                String model,
                BaseChatModelSetup chatModel,
                ChatResult response,
                int retryCount,
                long totalRetryWaitMs) {
            this(model, chatModel, response, retryCount, totalRetryWaitMs, false);
        }

        ChatAttemptResult(
                String model,
                BaseChatModelSetup chatModel,
                ChatResult response,
                int retryCount,
                long totalRetryWaitMs,
                boolean nativeSchema) {
            this.model = model;
            this.chatModel = chatModel;
            this.response = response;
            this.retryCount = retryCount;
            this.totalRetryWaitMs = totalRetryWaitMs;
            this.nativeSchema = nativeSchema;
        }
    }

    public static final class ChatAttemptFailed extends Exception {
        public final String model;
        public final BaseChatModelSetup chatModel;
        public final Exception error;
        public final int retryCount;
        public final long totalRetryWaitMs;

        ChatAttemptFailed(
                String model,
                BaseChatModelSetup chatModel,
                Exception error,
                int retryCount,
                long totalRetryWaitMs) {
            super(error);
            this.model = model;
            this.chatModel = chatModel;
            this.error = error;
            this.retryCount = retryCount;
            this.totalRetryWaitMs = totalRetryWaitMs;
        }
    }

    /** The request-level retry budget. */
    public static int configuredRetries(RunnerContext ctx) {
        return Math.max(ctx.getConfig().get(AgentExecutionOptions.MAX_RETRIES), 0);
    }

    /**
     * The request-level retry backoff in whole milliseconds, with negative values treated as zero.
     */
    public static long configuredRetryWaitMillis(RunnerContext ctx) {
        Duration interval = ctx.getConfig().get(AgentExecutionOptions.RETRY_WAIT_INTERVAL);
        return interval.isNegative() ? 0L : interval.toMillis();
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
            long retryWaitMillis)
            throws ChatAttemptFailed, Exception {
        return chatWithRetries(
                initialRequestId,
                model,
                durableCallId,
                messages,
                promptArgs,
                outputSchema,
                false,
                ctx,
                numRetries,
                retryWaitMillis);
    }

    /**
     * As {@link #chatWithRetries(UUID, String, String, List, Map, Object, RunnerContext, int,
     * int)}; with {@code deferOutputSchema} the schema's feasibility is still settled before the
     * loop call (a schema the candidate can never carry is the candidate failing, as before), but
     * the schema step itself is left to {@link #applyOutputSchema}, so a quality gate can judge the
     * answer first.
     */
    public static ChatAttemptResult chatWithRetries(
            UUID initialRequestId,
            String model,
            String durableCallId,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            boolean deferOutputSchema,
            RunnerContext ctx,
            int numRetries,
            long retryWaitMillis)
            throws ChatAttemptFailed, Exception {
        return chatWithRetries(
                initialRequestId,
                model,
                durableCallId,
                messages,
                promptArgs,
                outputSchema,
                deferOutputSchema,
                ctx,
                numRetries,
                retryWaitMillis,
                false);
    }

    public static ChatAttemptResult chatWithRetries(
            UUID initialRequestId,
            String model,
            String durableCallId,
            List<ChatMessage> messages,
            Map<String, Object> promptArgs,
            @Nullable Object outputSchema,
            boolean deferOutputSchema,
            RunnerContext ctx,
            int numRetries,
            long retryWaitMillis,
            boolean closing)
            throws Exception {
        BaseChatModelSetup chatModel;
        try {
            chatModel = (BaseChatModelSetup) ctx.getResource(model, ResourceType.CHAT_MODEL);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || CancellationUtils.isCancellation(e)) {
                if (CancellationUtils.isInterruption(e)) {
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
        // The answer depends only on this chat model and this schema, so it is settled once
        // rather than per attempt; it is then also evaluated for a request that ends in tool
        // calls, which costs no model call.
        boolean nativeSchema;
        try {
            nativeSchema =
                    outputSchema != null && chatModel.willApplyNativeStructuredOutput(outputSchema);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() || CancellationUtils.isCancellation(e)) {
                if (CancellationUtils.isInterruption(e)) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }
            // A schema this candidate can never carry is the candidate failing, so the fallback
            // loop sees it like any other attempt failure.
            throw new ChatAttemptFailed(model, chatModel, e, 0, 0);
        }
        FlinkAgentsMetricGroup requestMetricGroup = ctx.getActionMetricGroup();

        boolean chatAsync = chatAsync(ctx, chatModel);

        int actualRetryCount = 0;
        long totalWaitTimeMs = 0;
        ChatResult response;

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
                                    closing
                                            ? chatModel.chatWithoutTools(
                                                    messages, promptArgs, Map.of())
                                            : chatModel.chat(messages, promptArgs, Map.of()),
                                    null);
                        } catch (Exception e) {
                            if (Thread.currentThread().isInterrupted()
                                    || CancellationUtils.isCancellation(e)) {
                                if (CancellationUtils.isInterruption(e)) {
                                    Thread.currentThread().interrupt();
                                }
                                throw e;
                            }
                            LOG.debug("Chat provider failed", e);
                            return new InvocationOutcome(null, errorText(e));
                        }
                    }
                };
        Map<String, Object> llmMetadata = llmMetadata(chatModel);

        for (int attempt = 0; attempt < numRetries + 1; attempt++) {
            String executionId =
                    ExecutionReporters.started(
                            ctx, ExecutionReporter.EntityTypes.LLM, model, llmMetadata);
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
            ReActLoopBudgetUtils.updateTokenBudgetState(
                    ctx.getSensoryMemory(), initialRequestId, outcome.response);
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
                            executionId,
                            ExecutionReporter.EntityTypes.LLM,
                            model,
                            llmMetadata,
                            modelError,
                            ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
                }
                reportSuccessfulResponse(
                        ctx,
                        executionId,
                        model,
                        llmMetadata,
                        chatModel,
                        response,
                        requestMetricGroup);
                // A truncated response consumed its full token budget, so the token metrics
                // above are recorded before this rejects and abandons the response.
                ChatModelAction.rejectIncompleteResponse(response);
                ChatAttemptResult result =
                        new ChatAttemptResult(
                                model,
                                chatModel,
                                response,
                                actualRetryCount,
                                totalWaitTimeMs,
                                nativeSchema);
                if (outputSchema != null && !deferOutputSchema) {
                    result =
                            withOutputSchema(
                                    result,
                                    outputSchema,
                                    durableCallId,
                                    llmMetadata,
                                    ctx,
                                    chatAsync,
                                    requestMetricGroup,
                                    initialRequestId);
                }
                return result;
            } catch (DurableExecutionFailure e) {
                throw e.failure;
            } catch (InterruptedException e) {
                // A cancellation signal, not a model failure: restore the interrupt status and
                // propagate immediately so task shutdown isn't delayed by retry backoff or an
                // extra model call, regardless of the configured retry budget.
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception caught) {
                // The schema step wraps an answer it could not shape; here the cause is retried
                // and reported as it always was.
                Exception e =
                        caught instanceof OutputSchemaFailure
                                ? ((OutputSchemaFailure) caught).cause
                                : caught;
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Chat execution interrupted");
                }
                if (CancellationUtils.isCancellation(e)) {
                    if (CancellationUtils.isInterruption(e)) {
                        Thread.currentThread().interrupt();
                    }
                    throw e;
                }
                if (attempt < numRetries) {
                    actualRetryCount = attempt + 1;
                    long waitMillis = Math.multiplyExact(retryWaitMillis, 1L << attempt);
                    LOG.warn(
                            "Chat request {} failed with error: {}, retrying {} / {}, waiting {} ms.",
                            initialRequestId,
                            e,
                            actualRetryCount,
                            numRetries,
                            waitMillis);
                    if (waitMillis > 0) {
                        try {
                            Thread.sleep(waitMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw ie;
                        }
                        totalWaitTimeMs += waitMillis;
                    }
                    continue;
                }
                throw new ChatAttemptFailed(model, chatModel, e, actualRetryCount, totalWaitTimeMs);
            }
        }
        throw new IllegalStateException("Unreachable chat retry state.");
    }

    /**
     * Applies the request's output schema to an answer accepted after its chat call: the finalizing
     * call when the model applies the schema natively, then the parse. A tiered router uses it for
     * a tier whose answer can still be rejected, so the quality gate judges the answer the model
     * wrote, and an answer the tier cannot shape is a rejection rather than a failed attempt. A
     * direct request, and the last tier, shape the answer inside {@link #chatWithRetries}, with its
     * retries. The attempt comes from {@code chatWithRetries} with the schema deferred, which has
     * settled the schema's feasibility. An answer that carries tool calls is returned as is.
     *
     * @throws OutputSchemaFailure when the answer could not be shaped: the finalizing call failed
     *     or came back incomplete, or the text does not parse against the schema
     * @throws DurableExecutionFailure when the finalizing call could not be persisted or replayed
     * @throws Exception any other failure, which is not the answer's fault (an unsupported schema
     *     type, a reporting failure)
     */
    public static ChatAttemptResult applyOutputSchema(
            ChatAttemptResult attempt,
            Object outputSchema,
            String durableCallId,
            RunnerContext ctx,
            UUID requestId)
            throws Exception {
        BaseChatModelSetup chatModel = Objects.requireNonNull(attempt.chatModel, "chatModel");
        return withOutputSchema(
                attempt,
                outputSchema,
                durableCallId,
                llmMetadata(chatModel),
                ctx,
                chatAsync(ctx, chatModel),
                ctx.getActionMetricGroup(),
                requestId);
    }

    /** The schema step of a final answer; an answer that carries tool calls is returned as is. */
    private static ChatAttemptResult withOutputSchema(
            ChatAttemptResult attempt,
            Object outputSchema,
            String durableCallId,
            Map<String, Object> llmMetadata,
            RunnerContext ctx,
            boolean chatAsync,
            @Nullable FlinkAgentsMetricGroup requestMetricGroup,
            UUID requestId)
            throws Exception {
        ChatResult response = attempt.response;
        if (!response.getToolCalls().isEmpty()) {
            return attempt;
        }
        ChatResult finalized = null;
        if (attempt.nativeSchema) {
            try {
                finalized =
                        finalizeWithNativeSchema(
                                attempt.chatModel,
                                attempt.model,
                                durableCallId,
                                response,
                                outputSchema,
                                llmMetadata,
                                ctx,
                                chatAsync,
                                requestMetricGroup,
                                requestId);
                // A truncated conversion consumed its token budget; its metrics are recorded
                // before this rejects it.
                ChatModelAction.rejectIncompleteResponse(finalized);
            } catch (InvocationFailure | IllegalStateException e) {
                // The finalizing call failed, answered nothing, or came back incomplete: the
                // answer was not shaped. Anything else (a durable, reporting or cancellation
                // failure) propagates as the caller has always handled it.
                throw new OutputSchemaFailure(e, finalized);
            }
            response = finalized;
        }
        ChatAttemptResult result =
                new ChatAttemptResult(
                        attempt.model,
                        attempt.chatModel,
                        response,
                        attempt.retryCount,
                        attempt.totalRetryWaitMs,
                        attempt.nativeSchema);
        try {
            result.structuredOutput =
                    ChatModelAction.generateStructuredOutputWithReport(ctx, response, outputSchema);
        } catch (InvalidDefinitionException e) {
            // The schema itself cannot be deserialized into: not the answer's fault.
            throw e;
        } catch (JsonProcessingException e) {
            // The text does not parse against the schema: the answer was not shaped. Any other
            // failure (an unsupported schema type, a reporting failure) is not the answer's fault.
            throw new OutputSchemaFailure(e, finalized);
        }
        return result;
    }

    /** Chat calls run asynchronously when configured, except for a Python setup that cannot. */
    private static boolean chatAsync(RunnerContext ctx, BaseChatModelSetup chatModel) {
        boolean chatAsync = ctx.getConfig().get(AgentExecutionOptions.CHAT_ASYNC);
        if ((chatModel instanceof PythonChatModelSetup) && !supportAsync()) {
            return false;
        }
        return chatAsync;
    }

    private static Map<String, Object> llmMetadata(BaseChatModelSetup chatModel) {
        return chatModel.getModel() == null
                ? Map.of()
                : Map.of(LLMExecutionMetadataKeys.MODEL, chatModel.getModel());
    }

    /**
     * Issues the tool-free call that carries {@code outputSchema}: the loop call binds tools, and a
     * provider may drop a native schema from a request that also binds tools. It sends one user
     * message holding an instruction to convert the answer followed by the loop's final answer
     * text, and returns the response, which the caller checks for completeness and parses.
     *
     * <p>It holds its own durable slot after the loop call's, and reports its own LLM span.
     */
    private static ChatResult finalizeWithNativeSchema(
            BaseChatModelSetup chatModel,
            String model,
            String durableCallId,
            ChatResult loopResponse,
            Object outputSchema,
            Map<String, Object> llmMetadata,
            RunnerContext ctx,
            boolean chatAsync,
            @Nullable FlinkAgentsMetricGroup requestMetricGroup,
            UUID requestId)
            throws Exception {
        // A fresh user message rather than resending the response object: it carries no
        // provider-specific fields of the loop response, and a single user turn avoids provider
        // rules on turn order and on empty assistant content.
        List<ChatMessage> finalizeMessages =
                List.of(ChatMessage.user(FINALIZE_DIRECTIVE + loopResponse.getText()));

        DurableCallable<InvocationOutcome> callable =
                new DurableCallable<>() {
                    @Override
                    public String getId() {
                        return durableCallId + ":final";
                    }

                    @Override
                    public Class<InvocationOutcome> getResultClass() {
                        return InvocationOutcome.class;
                    }

                    @Override
                    public InvocationOutcome call() throws Exception {
                        try {
                            return new InvocationOutcome(
                                    chatModel.chat(
                                            finalizeMessages, List.of(), Map.of(), outputSchema),
                                    null);
                        } catch (Exception e) {
                            if (Thread.currentThread().isInterrupted()
                                    || CancellationUtils.isCancellation(e)) {
                                if (CancellationUtils.isInterruption(e)) {
                                    Thread.currentThread().interrupt();
                                }
                                throw e;
                            }
                            LOG.debug("Structured output provider call failed", e);
                            return new InvocationOutcome(null, errorText(e));
                        }
                    }
                };

        String executionId =
                ExecutionReporters.started(
                        ctx, ExecutionReporter.EntityTypes.LLM, model, llmMetadata);
        InvocationOutcome outcome;
        try {
            outcome =
                    chatAsync
                            ? ctx.durableExecuteAsync(callable).await()
                            : ctx.durableExecute(callable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            // Persistence and recovery failures are not request failures, as for the loop call.
            throw new DurableExecutionFailure(e);
        }
        ReActLoopBudgetUtils.updateTokenBudgetState(
                ctx.getSensoryMemory(), requestId, outcome.response);
        ChatResult response;
        try {
            if (outcome.error != null) {
                throw new InvocationFailure(outcome.error);
            }
            if (outcome.response == null) {
                throw new InvocationFailure("ChatModel returned a null response.");
            }
            response = outcome.response;
        } catch (Throwable modelError) {
            throw ChatModelAction.reportFailedAndPropagate(
                    ctx,
                    executionId,
                    ExecutionReporter.EntityTypes.LLM,
                    model,
                    llmMetadata,
                    modelError,
                    ExecutionReporter.ProblemCategories.MODEL_CALL_FAILED);
        }
        reportSuccessfulResponse(
                ctx, executionId, model, llmMetadata, chatModel, response, requestMetricGroup);
        return response;
    }

    private static void reportSuccessfulResponse(
            RunnerContext ctx,
            String executionId,
            String model,
            Map<String, Object> llmMetadata,
            BaseChatModelSetup chatModel,
            ChatResult response,
            @Nullable FlinkAgentsMetricGroup requestMetricGroup) {
        TokenUsage responseUsage = response.getUsage();
        Long prompt = responseUsage == null ? null : responseUsage.getPromptTokens();
        Long completion = responseUsage == null ? null : responseUsage.getCompletionTokens();
        Map<String, Object> usage = new HashMap<>();
        if (prompt != null) {
            usage.put("promptTokens", prompt);
        }
        if (completion != null) {
            usage.put("completionTokens", completion);
        }
        Map<String, Object> metadata = new HashMap<>(llmMetadata);
        if (!usage.isEmpty()) {
            metadata.put(LLMExecutionMetadataKeys.USAGE, usage);
        }
        ExecutionReporters.succeeded(
                ctx, executionId, ExecutionReporter.EntityTypes.LLM, model, metadata);
        if (requestMetricGroup != null
                && response.getModel() != null
                && prompt != null
                && completion != null) {
            chatModel.recordTokenMetrics(
                    requestMetricGroup, response.getModel(), prompt, completion);
        }
    }
}
