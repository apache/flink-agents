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
package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.ChatModelConnection;
import org.apache.flink.agents.api.annotation.ChatModelSetup;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelConnection;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.Tool;
import org.apache.flink.agents.api.tools.ToolMetadata;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.apache.flink.agents.api.tools.ToolType;
import org.apache.flink.api.java.functions.KeySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent definition for testing async execution functionality.
 *
 * <p>This agent demonstrates the usage of {@code durableExecuteAsync} for performing long-running
 * operations without blocking the mailbox thread.
 */
public class AsyncExecutionAgent {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncExecutionAgent.class);

    /** Simple request data class. */
    public static class AsyncRequest {
        public final int id;
        public final String data;
        public final int sleepTimeMs;

        public AsyncRequest(int id, String data) {
            this(id, data, 100); // Default sleep time
        }

        public AsyncRequest(int id, String data, int sleepTimeMs) {
            this.id = id;
            this.data = data;
            this.sleepTimeMs = sleepTimeMs;
        }

        @Override
        public String toString() {
            return String.format(
                    "AsyncRequest{id=%d, data='%s', sleepTimeMs=%d}", id, data, sleepTimeMs);
        }
    }

    /** Key selector for extracting keys from AsyncRequest. */
    public static class AsyncRequestKeySelector implements KeySelector<AsyncRequest, Integer> {
        @Override
        public Integer getKey(AsyncRequest request) {
            return request.id;
        }
    }

    /** Chat connection that emits one tool request containing multiple slow tool calls. */
    public static class ToolBatchChatConnection extends BaseChatModelConnection {
        public ToolBatchChatConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            if (lastMessage.getRole() == MessageRole.TOOL) {
                StringBuilder aggregated = new StringBuilder();
                for (ChatMessage message : messages) {
                    if (message.getRole() == MessageRole.TOOL) {
                        if (aggregated.length() > 0) {
                            aggregated.append('|');
                        }
                        aggregated.append(message.getContent());
                    }
                }
                return new ChatMessage(MessageRole.ASSISTANT, aggregated.toString());
            }

            String requestId = lastMessage.getContent();
            return new ChatMessage(
                    MessageRole.ASSISTANT,
                    "",
                    List.of(
                            toolCall("call-1", requestId, 1),
                            toolCall("call-2", requestId, 2),
                            toolCall("call-3", requestId, 3)));
        }
    }

    /** Chat model setup bound to the timed tool. */
    public static class ToolBatchChatModel extends BaseChatModelSetup {
        public ToolBatchChatModel(ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    /** Chat model setup for batch timeout e2e tests. */
    public static class ToolBatchTimeoutChatModel extends BaseChatModelSetup {
        public ToolBatchTimeoutChatModel(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    private static Map<String, Object> toolCall(String id, String requestId, int index) {
        return toolCallWithSleep(id, requestId, index, 500);
    }

    private static Map<String, Object> toolCallWithSleep(
            String id, String requestId, int index, int sleepMs) {
        return Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of(
                        "name",
                        "timed_tool",
                        "arguments",
                        Map.of(
                                "request_id",
                                requestId,
                                "call_index",
                                String.valueOf(index),
                                "sleep_ms",
                                sleepMs)));
    }

    private static Map<String, Object> timeoutToolCallWithSleep(
            String id, String requestId, int index, int sleepMs) {
        return Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of(
                        "name",
                        "timed_tool_with_sleep",
                        "arguments",
                        Map.of(
                                "request_id",
                                requestId,
                                "call_index",
                                String.valueOf(index),
                                "sleep_ms",
                                sleepMs)));
    }

    /**
     * Chat connection that issues one fast and one slow tool call, then aggregates all tool
     * responses into the assistant reply so timeout e2e tests can observe partial batch outcomes.
     */
    public static class ToolBatchTimeoutChatConnection extends BaseChatModelConnection {
        public ToolBatchTimeoutChatConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            if (lastMessage.getRole() == MessageRole.TOOL) {
                StringBuilder aggregated = new StringBuilder();
                for (ChatMessage message : messages) {
                    if (message.getRole() == MessageRole.TOOL) {
                        if (aggregated.length() > 0) {
                            aggregated.append('|');
                        }
                        aggregated.append(message.getContent());
                    }
                }
                return new ChatMessage(MessageRole.ASSISTANT, aggregated.toString());
            }

            String requestId = lastMessage.getContent();
            return new ChatMessage(
                    MessageRole.ASSISTANT,
                    "",
                    List.of(
                            timeoutToolCallWithSleep("call-1", requestId, 1, 0),
                            timeoutToolCallWithSleep("call-2", requestId, 2, 150)));
        }
    }

    /** Agent that drives a six-tool batch used to verify max-parallelism in-flight caps. */
    public static class ToolBatchMaxParallelismAgent extends Agent {
        public static final int TOOL_COUNT = 6;
        public static final int SLEEP_MS = 400;

        @ChatModelConnection
        public static ResourceDescriptor toolBatchMaxParallelismChatConnection() {
            return ResourceDescriptor.Builder.newBuilder(
                            ToolBatchMaxParallelismChatConnection.class.getName())
                    .build();
        }

        @ChatModelSetup
        public static ResourceDescriptor toolBatchMaxParallelismChatModel() {
            return ResourceDescriptor.Builder.newBuilder(
                            ToolBatchMaxParallelismChatModel.class.getName())
                    .addInitialArgument("connection", "toolBatchMaxParallelismChatConnection")
                    .addInitialArgument("model", "test-model")
                    .addInitialArgument("tools", List.of("timed_tool_with_sleep"))
                    .build();
        }

        @org.apache.flink.agents.api.annotation.Tool(
                description = "Records timing for a tool call with configurable sleep.")
        public static String timed_tool_with_sleep(
                @ToolParam(name = "request_id") String requestId,
                @ToolParam(name = "call_index") String callIndex,
                @ToolParam(name = "sleep_ms") Integer sleepMs) {
            long start = System.currentTimeMillis();
            int sleepMillis = sleepMs != null ? sleepMs : 0;
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long end = System.currentTimeMillis();
            return String.format(
                    "request=%s,call=%s,sleep_ms=%d,start=%d,end=%d",
                    requestId, callIndex, sleepMillis, start, end);
        }

        @Action(EventType.InputEvent)
        public static void requestTools(Event event, RunnerContext ctx) {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();
            ctx.sendEvent(
                    new ChatRequestEvent(
                            "toolBatchMaxParallelismChatModel",
                            List.of(
                                    new ChatMessage(
                                            MessageRole.USER, String.valueOf(request.id)))));
        }

        @Action(EventType.ChatResponseEvent)
        public static void emitToolTimings(Event event, RunnerContext ctx) {
            ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(event);
            ctx.sendEvent(new OutputEvent(responseEvent.getResponse().getContent()));
        }
    }

    /** Chat connection that emits six slow tool calls for max-parallelism verification. */
    public static class ToolBatchMaxParallelismChatConnection extends BaseChatModelConnection {
        public ToolBatchMaxParallelismChatConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            if (lastMessage.getRole() == MessageRole.TOOL) {
                StringBuilder aggregated = new StringBuilder();
                for (ChatMessage message : messages) {
                    if (message.getRole() == MessageRole.TOOL) {
                        if (aggregated.length() > 0) {
                            aggregated.append('|');
                        }
                        aggregated.append(message.getContent());
                    }
                }
                return new ChatMessage(MessageRole.ASSISTANT, aggregated.toString());
            }

            String requestId = lastMessage.getContent();
            List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
            for (int i = 1; i <= ToolBatchMaxParallelismAgent.TOOL_COUNT; i++) {
                toolCalls.add(
                        timeoutToolCallWithSleep(
                                "call-" + i, requestId, i, ToolBatchMaxParallelismAgent.SLEEP_MS));
            }
            return new ChatMessage(MessageRole.ASSISTANT, "", toolCalls);
        }
    }

    /** Chat model setup for max-parallelism in-flight e2e tests. */
    public static class ToolBatchMaxParallelismChatModel extends BaseChatModelSetup {
        public ToolBatchMaxParallelismChatModel(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    /**
     * Chat connection that issues two slow tool calls, so a pool smaller than the batch parallelism
     * keeps the second slot queued while the first holds the only worker past the batch deadline.
     */
    public static class ToolBatchQueuedSlotChatConnection extends BaseChatModelConnection {
        public ToolBatchQueuedSlotChatConnection(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public ChatMessage chat(
                List<ChatMessage> messages, List<Tool> tools, Map<String, Object> modelParams) {
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            if (lastMessage.getRole() == MessageRole.TOOL) {
                StringBuilder aggregated = new StringBuilder();
                for (ChatMessage message : messages) {
                    if (message.getRole() == MessageRole.TOOL) {
                        if (aggregated.length() > 0) {
                            aggregated.append('|');
                        }
                        aggregated.append(message.getContent());
                    }
                }
                return new ChatMessage(MessageRole.ASSISTANT, aggregated.toString());
            }

            String requestId = lastMessage.getContent();
            return new ChatMessage(
                    MessageRole.ASSISTANT,
                    "",
                    List.of(
                            timeoutToolCallWithSleep("call-1", requestId, 1, 150),
                            timeoutToolCallWithSleep("call-2", requestId, 2, 150)));
        }
    }

    /**
     * Agent that drives a two-tool batch against a pool with fewer threads than the parallelism
     * budget, so one slot starts while the other stays queued when the batch deadline elapses.
     */
    public static class ToolBatchQueuedSlotAgent extends Agent {
        @ChatModelConnection
        public static ResourceDescriptor toolBatchQueuedSlotChatConnection() {
            return ResourceDescriptor.Builder.newBuilder(
                            ToolBatchQueuedSlotChatConnection.class.getName())
                    .build();
        }

        @ChatModelSetup
        public static ResourceDescriptor toolBatchQueuedSlotChatModel() {
            return ResourceDescriptor.Builder.newBuilder(
                            ToolBatchQueuedSlotChatModel.class.getName())
                    .addInitialArgument("connection", "toolBatchQueuedSlotChatConnection")
                    .addInitialArgument("model", "test-model")
                    .addInitialArgument("tools", List.of("timed_tool_with_sleep"))
                    .build();
        }

        @org.apache.flink.agents.api.annotation.Tool(
                description = "Records timing for a tool call with configurable sleep.")
        public static String timed_tool_with_sleep(
                @ToolParam(name = "request_id") String requestId,
                @ToolParam(name = "call_index") String callIndex,
                @ToolParam(name = "sleep_ms") Integer sleepMs) {
            long start = System.currentTimeMillis();
            int sleepMillis = sleepMs != null ? sleepMs : 0;
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long end = System.currentTimeMillis();
            return String.format(
                    "request=%s,call=%s,sleep_ms=%d,start=%d,end=%d",
                    requestId, callIndex, sleepMillis, start, end);
        }

        @Action(EventType.InputEvent)
        public static void requestTools(Event event, RunnerContext ctx) {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();
            ctx.sendEvent(
                    new ChatRequestEvent(
                            "toolBatchQueuedSlotChatModel",
                            List.of(
                                    new ChatMessage(
                                            MessageRole.USER, String.valueOf(request.id)))));
        }

        @Action(EventType.ChatResponseEvent)
        public static void emitToolTimings(Event event, RunnerContext ctx) {
            ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(event);
            ctx.sendEvent(new OutputEvent(responseEvent.getResponse().getContent()));
        }
    }

    /** Chat model setup for the queued-slot batch timeout e2e test. */
    public static class ToolBatchQueuedSlotChatModel extends BaseChatModelSetup {
        public ToolBatchQueuedSlotChatModel(
                ResourceDescriptor descriptor, ResourceContext resourceContext) {
            super(descriptor, resourceContext);
        }

        @Override
        public Map<String, Object> getParameters() {
            return new HashMap<>();
        }
    }

    /** Agent that drives a two-tool batch used to exercise batch timeout behavior. */
    public static class ToolBatchTimeoutAgent extends Agent {
        @ChatModelConnection
        public static ResourceDescriptor toolBatchTimeoutChatConnection() {
            return ResourceDescriptor.Builder.newBuilder(
                            ToolBatchTimeoutChatConnection.class.getName())
                    .build();
        }

        @ChatModelSetup
        public static ResourceDescriptor toolBatchTimeoutChatModel() {
            return ResourceDescriptor.Builder.newBuilder(ToolBatchTimeoutChatModel.class.getName())
                    .addInitialArgument("connection", "toolBatchTimeoutChatConnection")
                    .addInitialArgument("model", "test-model")
                    .addInitialArgument("tools", List.of("timed_tool_with_sleep"))
                    .build();
        }

        @org.apache.flink.agents.api.annotation.Tool(
                description = "Records timing for a tool call with configurable sleep.")
        public static String timed_tool_with_sleep(
                @ToolParam(name = "request_id") String requestId,
                @ToolParam(name = "call_index") String callIndex,
                @ToolParam(name = "sleep_ms") Integer sleepMs) {
            long start = System.currentTimeMillis();
            int sleepMillis = sleepMs != null ? sleepMs : 0;
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long end = System.currentTimeMillis();
            return String.format(
                    "request=%s,call=%s,sleep_ms=%d,start=%d,end=%d",
                    requestId, callIndex, sleepMillis, start, end);
        }

        @Action(EventType.InputEvent)
        public static void requestTools(Event event, RunnerContext ctx) {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();
            ctx.sendEvent(
                    new ChatRequestEvent(
                            "toolBatchTimeoutChatModel",
                            List.of(
                                    new ChatMessage(
                                            MessageRole.USER, String.valueOf(request.id)))));
        }

        @Action(EventType.ChatResponseEvent)
        public static void emitToolTimings(Event event, RunnerContext ctx) {
            ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(event);
            ctx.sendEvent(new OutputEvent(responseEvent.getResponse().getContent()));
        }
    }

    /** Agent that requests one chat turn that produces multiple slow tool calls. */
    public static class ToolBatchAgent extends Agent {
        public ToolBatchAgent(int sleepTimeMs) {}

        @ChatModelConnection
        public static ResourceDescriptor toolBatchChatConnection() {
            return ResourceDescriptor.Builder.newBuilder(ToolBatchChatConnection.class.getName())
                    .build();
        }

        @ChatModelSetup
        public static ResourceDescriptor toolBatchChatModel() {
            return ResourceDescriptor.Builder.newBuilder(ToolBatchChatModel.class.getName())
                    .addInitialArgument("connection", "toolBatchChatConnection")
                    .addInitialArgument("model", "test-model")
                    .addInitialArgument("tools", List.of("timed_tool"))
                    .build();
        }

        @org.apache.flink.agents.api.annotation.Tool(
                description = "Records timing for a slow tool call.")
        public static String timed_tool(
                @ToolParam(name = "request_id") String requestId,
                @ToolParam(name = "call_index") String callIndex) {
            long start = System.currentTimeMillis();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long end = System.currentTimeMillis();
            return String.format(
                    "request=%s,call=%s,start=%d,end=%d", requestId, callIndex, start, end);
        }

        @Action(EventType.InputEvent)
        public static void requestTools(Event event, RunnerContext ctx) {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();
            ctx.sendEvent(
                    new ChatRequestEvent(
                            "toolBatchChatModel",
                            List.of(
                                    new ChatMessage(
                                            MessageRole.USER, String.valueOf(request.id)))));
        }

        @Action(EventType.ChatResponseEvent)
        public static void emitToolTimings(Event event, RunnerContext ctx) {
            ChatResponseEvent responseEvent = ChatResponseEvent.fromEvent(event);
            ctx.sendEvent(new OutputEvent(responseEvent.getResponse().getContent()));
        }
    }

    /** Tool that records its execution time range in the response result. */
    public static class TimedTool extends Tool {
        private final int sleepTimeMs;

        public TimedTool(int sleepTimeMs) {
            super(new ToolMetadata("timed_tool", "Records timing for a slow tool call.", "{}"));
            this.sleepTimeMs = sleepTimeMs;
        }

        @Override
        public ToolType getToolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public ToolResponse call(ToolParameters parameters) {
            int callIndex = parameters.getParameter("call_index", Integer.class);
            long start = System.currentTimeMillis();
            try {
                Thread.sleep(sleepTimeMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long end = System.currentTimeMillis();
            return ToolResponse.success(
                    String.format("call=%d,start=%d,end=%d", callIndex, start, end));
        }
    }

    /** Custom event type for internal agent communication. */
    public static class AsyncProcessedEvent extends Event {
        public static final String EVENT_TYPE = "AsyncProcessedEvent";

        private final String processedResult;

        public AsyncProcessedEvent(String processedResult) {
            super(EVENT_TYPE);
            this.processedResult = processedResult;
        }

        public String getProcessedResult() {
            return processedResult;
        }
    }

    /**
     * Agent that uses durableExecuteAsync for simulating slow operations.
     *
     * <p>On JDK 21+, this uses Continuation API for true async execution. On JDK &lt; 21, this
     * falls back to synchronous execution.
     */
    public static class SimpleAsyncAgent extends Agent {

        @Action(EventType.InputEvent)
        public static void processInput(Event event, RunnerContext ctx) throws Exception {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();

            String result =
                    ctx.durableExecuteAsync(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return "simple-async-process";
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "Processed: " + request.data.toUpperCase();
                                }
                            });

            MemoryObject stm = ctx.getShortTermMemory();
            stm.set("lastResult", result);

            ctx.sendEvent(new AsyncProcessedEvent(result));
        }

        /**
         * Action that handles processed events and generates output.
         *
         * @param event The processed event
         * @param ctx The runner context for sending events
         */
        @Action(AsyncProcessedEvent.EVENT_TYPE)
        public static void generateOutput(Event event, RunnerContext ctx) throws Exception {
            AsyncProcessedEvent processedEvent = (AsyncProcessedEvent) event;

            MemoryObject stm = ctx.getShortTermMemory();
            String lastResult = (String) stm.get("lastResult").getValue();

            String output =
                    String.format(
                            "AsyncResult: %s | MemoryCheck: %s",
                            processedEvent.getProcessedResult(), lastResult);
            ctx.sendEvent(new OutputEvent(output));
        }
    }

    /** Agent that chains multiple durableExecuteAsync calls. */
    public static class MultiAsyncAgent extends Agent {

        @Action(EventType.InputEvent)
        public static void processWithMultipleAsync(Event event, RunnerContext ctx)
                throws Exception {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();

            String step1Result =
                    ctx.durableExecuteAsync(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return "multi-async-step1";
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "Step1:" + request.data;
                                }
                            });

            String step2Result =
                    ctx.durableExecuteAsync(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return "multi-async-step2";
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return step1Result + "|Step2:processed";
                                }
                            });

            String finalResult =
                    ctx.durableExecuteAsync(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return "multi-async-step3";
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return step2Result + "|Step3:done";
                                }
                            });

            MemoryObject stm = ctx.getShortTermMemory();
            stm.set("chainedResult", finalResult);

            ctx.sendEvent(new OutputEvent("MultiAsync[" + finalResult + "]"));
        }
    }

    /** Agent that uses durableExecuteAsync with configurable sleep time. */
    public static class TimedAsyncAgent extends Agent {

        private final int sleepTimeMs;
        private final String timestampDir;

        public TimedAsyncAgent(int sleepTimeMs) {
            this(sleepTimeMs, null);
        }

        public TimedAsyncAgent(int sleepTimeMs, String timestampDir) {
            this.sleepTimeMs = sleepTimeMs;
            this.timestampDir = timestampDir;
        }

        public int getSleepTimeMs() {
            return sleepTimeMs;
        }

        public String getTimestampDir() {
            return timestampDir;
        }

        @Action(EventType.InputEvent)
        public static void processWithTiming(Event event, RunnerContext ctx) throws Exception {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();

            String result =
                    ctx.durableExecuteAsync(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return "timed-async-" + request.id;
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() {
                                    long asyncStartTime = System.currentTimeMillis();
                                    LOG.info("{} Async call start {}", request.id, asyncStartTime);
                                    try {
                                        Thread.sleep(request.sleepTimeMs);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    long asyncEndTime = System.currentTimeMillis();
                                    LOG.info("{} Async call end {}", request.id, asyncEndTime);
                                    return String.format(
                                            "key=%d,start=%d,end=%d",
                                            request.id, asyncStartTime, asyncEndTime);
                                }
                            });

            ctx.sendEvent(new OutputEvent("TimedAsync[" + result + "]"));
        }
    }

    /** Agent that uses durableExecute (sync) for simulating slow operations. */
    public static class SyncDurableAgent extends Agent {

        @Action(EventType.InputEvent)
        public static void processInputSync(Event event, RunnerContext ctx) throws Exception {
            InputEvent inputEvent = InputEvent.fromEvent(event);
            AsyncRequest request = (AsyncRequest) inputEvent.getInput();

            String result =
                    ctx.durableExecute(
                            new DurableCallable<String>() {
                                @Override
                                public String getId() {
                                    return "sync-durable-process";
                                }

                                @Override
                                public Class<String> getResultClass() {
                                    return String.class;
                                }

                                @Override
                                public String call() {
                                    try {
                                        Thread.sleep(50);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "SyncProcessed: " + request.data.toUpperCase();
                                }
                            });

            MemoryObject stm = ctx.getShortTermMemory();
            stm.set("syncResult", result);

            ctx.sendEvent(new OutputEvent("SyncDurable[" + result + "]"));
        }
    }
}
