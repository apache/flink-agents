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
package org.apache.flink.agents.examples.openai;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.EventType;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.agents.AgentExecutionOptions;
import org.apache.flink.agents.api.annotation.Action;
import org.apache.flink.agents.api.annotation.Tool;
import org.apache.flink.agents.api.annotation.ToolParam;
import org.apache.flink.agents.api.chat.contextmanagement.ContextManagementPolicy;
import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.TokenUsage;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.event.ChatRequestEvent;
import org.apache.flink.agents.api.event.ChatResponseEvent;
import org.apache.flink.agents.api.event.ContextManagementEvent;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceName;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.List;
import java.util.Map;

/**
 * Context management against OpenAI, runnable as a local Flink job.
 *
 * <p>The agent follows a chain of account records. Every record names the next account, so the
 * model looks up one account in each round of its tool loop, and every record is long, so the
 * conversation grows with every round. The job sends the same request twice. The first one runs
 * under the job's policy, which cuts tool results and replaces older rounds with a summary once the
 * input passes a small budget. The second one carries a disabled policy. For each request the job
 * prints every context management step as it is taken, and then the answer with the input tokens of
 * the last model call.
 *
 * <p>Run with {@code OPENAI_API_KEY} set in the environment.
 *
 * <p>Lives in the {@code openai} subpackage (not directly under {@code examples}) so the
 * submit-examples E2E job, which auto-submits every top-level example against a keyless local
 * cluster, does not pick it up.
 */
public class OpenAiContextManagementExample {

    /** A request that uses the job's policy. */
    public static final String MANAGED = "managed";

    /** A request that switches context management off. */
    public static final String UNMANAGED = "unmanaged";

    /** Follows the chain of accounts and reports how the request went. */
    public static class LedgerAgent extends Agent {
        private static final String INSTRUCTIONS =
                "You audit a chain of accounts. Call the lookup tool for one account at a time."
                        + " Each record names the next account to look up, or none when the chain"
                        + " ends. When the chain ends, reply with one line: TOTAL=<the sum of the"
                        + " amounts of all accounts>.";

        /** The account each account's record points to; 0 ends the chain. */
        private static final Map<Integer, Integer> NEXT =
                Map.of(1, 4, 4, 2, 2, 6, 6, 3, 3, 5, 5, 0);

        private static final String MODE = "mode";

        @Tool(
                description =
                        "Returns the record of one account: its amount, the next account to look"
                                + " up, and its history.")
        public static String lookup(
                @ToolParam(name = "account", description = "The account number") Integer account) {
            Integer next = NEXT.get(account);
            if (next == null) {
                return "There is no account " + account + ".";
            }
            StringBuilder record =
                    new StringBuilder("account=")
                            .append(account)
                            .append(" amount=")
                            .append(account * 10)
                            .append(" next=")
                            .append(next == 0 ? "none" : String.valueOf(next))
                            .append(" history:");
            for (int entry = 1; entry <= 100; entry++) {
                record.append(" entry ")
                        .append(entry)
                        .append(" verified by clerk ")
                        .append((entry + account) % 7)
                        .append(';');
            }
            return record.toString();
        }

        @Action(EventType.InputEvent)
        public static void ask(InputEvent event, RunnerContext ctx) throws Exception {
            String mode = String.valueOf(event.getInput());
            ctx.getShortTermMemory().set(MODE, mode);
            ChatRequestEvent request =
                    new ChatRequestEvent(
                            "ledgerModel",
                            List.of(
                                    ChatMessage.system(INSTRUCTIONS),
                                    ChatMessage.user("Start at account 1.")));
            if (UNMANAGED.equals(mode)) {
                // A request can carry its own policy; a disabled one switches the job's off.
                request.withContextManagement(ContextManagementPolicy.disabled());
            }
            ctx.sendEvent(request);
        }

        /** Every step that changes what the model is sent is an event an action can listen to. */
        @Action(EventType.ContextManagementEvent)
        public static void reportStep(Event event, RunnerContext ctx) throws Exception {
            ContextManagementEvent step = ContextManagementEvent.fromEvent(event);
            Map<String, Object> details = step.getDetails();
            String what;
            if (!ContextManagementEvent.STEP_SUMMARIZE.equals(step.getStep())) {
                what = ((List<?>) details.get("results")).size() + " tool result(s)";
            } else if (details.containsKey("error")) {
                what = "failed: " + details.get("error");
            } else {
                what =
                        String.format(
                                "%s round(s) added to notes of %s characters, estimated input"
                                        + " %s -> %s tokens",
                                details.get("rounds"),
                                details.get("summary_chars"),
                                details.get("estimated_input_tokens_before"),
                                details.get("estimated_input_tokens_after"));
            }
            ctx.sendEvent(
                    new OutputEvent(
                            String.format("[%s] step %s: %s", mode(ctx), step.getStep(), what)));
        }

        @Action(EventType.ChatResponseEvent)
        public static void report(ChatResponseEvent event, RunnerContext ctx) throws Exception {
            if (event.isFailed()) {
                ctx.sendEvent(new OutputEvent("[" + mode(ctx) + "] failed: " + event.getError()));
                return;
            }
            TokenUsage usage = event.getResponse().getUsage();
            ctx.sendEvent(
                    new OutputEvent(
                            String.format(
                                    "[%s] answer: %s | input tokens of the last model call: %s",
                                    mode(ctx),
                                    event.getResponse().getText().trim(),
                                    usage == null ? "unknown" : usage.getPromptTokens())));
        }

        private static String mode(RunnerContext ctx) throws Exception {
            return String.valueOf(ctx.getShortTermMemory().get(MODE).getValue());
        }
    }

    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set OPENAI_API_KEY in the environment to run.");
        }

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);
        agentsEnv.getConfig().set(AgentExecutionOptions.NUM_ASYNC_THREADS, 2);

        // The default policy of every chat request of this job. A record has about 3000
        // characters; the model is sent 600 of each. Above 80% of the budget, the rounds before
        // the last one are replaced with a summary.
        agentsEnv
                .getConfig()
                .set(AgentExecutionOptions.CONTEXT_MANAGEMENT_TOOL_RESULTS_MAX_CHARS, 600);
        agentsEnv.getConfig().set(AgentExecutionOptions.CONTEXT_MANAGEMENT_MAX_INPUT_TOKENS, 800);
        agentsEnv
                .getConfig()
                .set(AgentExecutionOptions.CONTEXT_MANAGEMENT_SUMMARIZATION_KEEP_LAST_ROUNDS, 1);

        agentsEnv.addResource(
                "openaiConnection",
                ResourceType.CHAT_MODEL_CONNECTION,
                ResourceDescriptor.Builder.newBuilder(
                                ResourceName.ChatModel.OPENAI_COMPLETIONS_CONNECTION)
                        .addInitialArgument("api_key", apiKey)
                        .build());
        agentsEnv.addResource(
                "ledgerModel",
                ResourceType.CHAT_MODEL,
                ResourceDescriptor.Builder.newBuilder(
                                ResourceName.ChatModel.OPENAI_COMPLETIONS_SETUP)
                        .addInitialArgument("connection", "openaiConnection")
                        .addInitialArgument("model", "gpt-4o-mini")
                        .addInitialArgument("temperature", 0.0d)
                        .addInitialArgument("tools", List.of("lookup"))
                        .build());

        DataStream<String> requestStream = env.fromData(MANAGED, UNMANAGED);

        DataStream<Object> resultStream =
                agentsEnv
                        .fromDataStream(requestStream, value -> value)
                        .apply(new LedgerAgent())
                        .toDataStream();

        resultStream.print();

        agentsEnv.execute("OpenAI Context Management Example");
    }
}
