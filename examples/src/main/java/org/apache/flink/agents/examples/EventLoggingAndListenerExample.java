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

package org.apache.flink.agents.examples;

import org.apache.flink.agents.api.Agent;
import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventContext;
import org.apache.flink.agents.api.EventFilter;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.listener.EventListener;
import org.apache.flink.agents.runtime.logger.FileEventLoggerConfig;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating EventLogger and EventListener usage with Flink Agents.
 *
 * <p>This example shows how to:
 *
 * <ul>
 *   <li>Configure file-based event logging with filtering
 *   <li>Register custom event listeners for monitoring and metrics
 *   <li>Process streaming data through agents
 *   <li>Track event processing statistics
 *   <li>Log events to files for debugging and analysis
 * </ul>
 *
 * <p>The example processes a stream of order data, applies business logic through an agent, and
 * demonstrates comprehensive event monitoring and logging capabilities.
 */
public class EventLoggingAndListenerExample {

    /** Order data class for the example. */
    public static class OrderData {
        public final int orderId;
        public final String customerId;
        public final double amount;
        public final String status;

        public OrderData(int orderId, String customerId, double amount, String status) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.status = status;
        }

        @Override
        public String toString() {
            return String.format(
                    "OrderData{orderId=%d, customerId='%s', amount=%.2f, status='%s'}",
                    orderId, customerId, amount, status);
        }
    }

    /** Key selector for extracting customer ID from OrderData. */
    public static class OrderKeySelector implements KeySelector<OrderData, String> {
        @Override
        public String getKey(OrderData order) {
            return order.customerId;
        }
    }

    /** Custom event for order processing results. */
    public static class OrderProcessedEvent extends Event {
        private final String orderId;
        private final String result;
        private final double processedAmount;

        public OrderProcessedEvent(String orderId, String result, double processedAmount) {
            this.orderId = orderId;
            this.result = result;
            this.processedAmount = processedAmount;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getResult() {
            return result;
        }

        public double getProcessedAmount() {
            return processedAmount;
        }

        @Override
        public String toString() {
            return String.format(
                    "OrderProcessedEvent{orderId='%s', result='%s', processedAmount=%.2f}",
                    orderId, result, processedAmount);
        }
    }

    /** Agent that processes orders with business logic. */
    public static class OrderProcessingAgent extends Agent {

        /**
         * Action that processes incoming order events.
         *
         * @param event The input event containing order data
         * @param ctx The runner context for sending events
         */
        @Action(listenEvents = {InputEvent.class})
        public void processOrder(Event event, RunnerContext ctx) {
            InputEvent inputEvent = (InputEvent) event;
            OrderData order = (OrderData) inputEvent.getInput();

            // Business logic: validate and process the order
            String result;
            double processedAmount = order.amount;

            if (order.amount > 1000.0) {
                result = "HIGH_VALUE_ORDER";
                processedAmount = order.amount * 0.95; // 5% discount for high-value orders
            } else if (order.amount > 100.0) {
                result = "STANDARD_ORDER";
                processedAmount = order.amount * 0.98; // 2% discount for standard orders
            } else {
                result = "SMALL_ORDER";
                // No discount for small orders
            }

            // Send processed event for further handling
            ctx.sendEvent(
                    new OrderProcessedEvent(
                            String.valueOf(order.orderId), result, processedAmount));
        }

        /**
         * Action that handles processed orders and generates final output.
         *
         * @param event The processed order event
         * @param ctx The runner context for sending events
         */
        @Action(listenEvents = {OrderProcessedEvent.class})
        public void generateOrderOutput(Event event, RunnerContext ctx) {
            OrderProcessedEvent processedEvent = (OrderProcessedEvent) event;

            // Generate final output with order summary
            String output =
                    String.format(
                            "Order %s processed: %s - Final amount: $%.2f",
                            processedEvent.getOrderId(),
                            processedEvent.getResult(),
                            processedEvent.getProcessedAmount());

            ctx.sendEvent(new OutputEvent(output));
        }
    }

    /** Custom event listener for monitoring and metrics collection. */
    public static class OrderMetricsListener implements EventListener {
        private final AtomicInteger totalEvents = new AtomicInteger(0);
        private final AtomicInteger inputEvents = new AtomicInteger(0);
        private final AtomicInteger processedEvents = new AtomicInteger(0);
        private final AtomicInteger outputEvents = new AtomicInteger(0);

        @Override
        public void onEventProcessed(EventContext context, Event event) {
            totalEvents.incrementAndGet();

            if (event instanceof InputEvent) {
                inputEvents.incrementAndGet();
                System.out.printf(
                        "[METRICS] Input Event processed: %s (Total inputs: %d)%n",
                        event, inputEvents.get());
            } else if (event instanceof OrderProcessedEvent) {
                processedEvents.incrementAndGet();
                OrderProcessedEvent orderEvent = (OrderProcessedEvent) event;
                System.out.printf(
                        "[METRICS] Order processed: %s with result %s (Total processed: %d)%n",
                        orderEvent.getOrderId(), orderEvent.getResult(), processedEvents.get());
            } else if (event instanceof OutputEvent) {
                outputEvents.incrementAndGet();
                System.out.printf(
                        "[METRICS] Output Event generated: %s (Total outputs: %d)%n",
                        event, outputEvents.get());
            }

            // Print summary every 5 events
            if (totalEvents.get() % 5 == 0) {
                printSummary();
            }
        }

        private void printSummary() {
            System.out.printf(
                    "[METRICS SUMMARY] Total: %d | Inputs: %d | Processed: %d | Outputs: %d%n",
                    totalEvents.get(),
                    inputEvents.get(),
                    processedEvents.get(),
                    outputEvents.get());
        }
    }

    /** Event listener for debugging and detailed event tracking. */
    public static class DebugEventListener implements EventListener {
        @Override
        public void onEventProcessed(EventContext context, Event event) {
            System.out.printf(
                    "[DEBUG] Event processed at %s: %s (Type: %s, ID: %s)%n",
                    context.getTimestamp(), event, event.getClass().getSimpleName(), event.getId());

            // Log context information
            System.out.printf("[DEBUG] Event context type: %s%n", context.getEventType());
        }
    }

    public static void main(String[] args) throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Create input DataStream with sample order data
        DataStream<OrderData> inputStream =
                env.fromElements(
                        new OrderData(1001, "customer_A", 150.0, "pending"),
                        new OrderData(1002, "customer_B", 75.0, "pending"),
                        new OrderData(1003, "customer_A", 1200.0, "pending"),
                        new OrderData(1004, "customer_C", 300.0, "pending"),
                        new OrderData(1005, "customer_B", 50.0, "pending"),
                        new OrderData(1006, "customer_A", 800.0, "pending"),
                        new OrderData(1007, "customer_D", 2000.0, "pending"));

        // Configure file-based event logging with filtering
        FileEventLoggerConfig loggerConfig =
                FileEventLoggerConfig.builder()
                        .baseEventLogDir("/tmp/flink-agents-logs")
                        .eventFilter(
                                EventFilter.byEventType(
                                        InputEvent.class,
                                        OrderProcessedEvent.class,
                                        OutputEvent.class))
                        .build();

        // Create custom event listeners
        OrderMetricsListener metricsListener = new OrderMetricsListener();
        DebugEventListener debugListener = new DebugEventListener();

        // Create agents execution environment with logging and listeners
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env)
                        .enableEventLogger(loggerConfig)
                        .registerEventListener(metricsListener)
                        .registerEventListener(debugListener);

        System.out.println("=== Flink Agents EventLogger and EventListener Example ===");
        System.out.println("Event logs will be written to: /tmp/flink-agents-logs");
        System.out.println("Processing order data through OrderProcessingAgent...");

        // Apply agent to the DataStream
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, new OrderKeySelector())
                        .apply(new OrderProcessingAgent())
                        .toDataStream();

        // Print the final results
        outputStream.print("FINAL_OUTPUT");

        // Execute the pipeline
        agentsEnv.execute();

        System.out.println();
        System.out.println("=== Execution Complete ===");
        System.out.println("Check /tmp/flink-agents-logs for event log files");
        System.out.println("Each subtask creates its own log file with filtered events");
    }
}
