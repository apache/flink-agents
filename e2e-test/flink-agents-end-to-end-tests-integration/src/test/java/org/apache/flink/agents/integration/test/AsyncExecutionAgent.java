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
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.DurableCallable;
import org.apache.flink.agents.api.context.MemoryObject;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.api.java.functions.KeySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** Custom event type for internal agent communication. */
    public static class AsyncProcessedEvent extends Event {
        private final String processedResult;

        public AsyncProcessedEvent(String processedResult) {
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

        @Action(listenEvents = {InputEvent.class})
        public static void processInput(Event event, RunnerContext ctx) throws Exception {
            InputEvent inputEvent = (InputEvent) event;
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
        @Action(listenEvents = {AsyncProcessedEvent.class})
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

        @Action(listenEvents = {InputEvent.class})
        public static void processWithMultipleAsync(Event event, RunnerContext ctx)
                throws Exception {
            InputEvent inputEvent = (InputEvent) event;
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

        @Action(listenEvents = {InputEvent.class})
        public static void processWithTiming(Event event, RunnerContext ctx) throws Exception {
            InputEvent inputEvent = (InputEvent) event;
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

        @Action(listenEvents = {InputEvent.class})
        public static void processInputSync(Event event, RunnerContext ctx) throws Exception {
            InputEvent inputEvent = (InputEvent) event;
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
