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

package org.apache.flink.agents.api.chat.model.routing;

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An "LLM-as-router" selection strategy: a small, cheap chat model is asked to choose which
 * candidate model should serve the request (the approach popularized by RouteLLM).
 *
 * <p>The strategy builds a classification prompt from each candidate's {@code name} and {@code
 * description}, asks the configured judge model to reply with a single model name, and parses that
 * reply back to a candidate (keyed on {@link RoutingContext#firstUserMessage()} for stickiness).
 *
 * <p>On a <b>parse miss</b> (the judge answered but named no candidate) it returns the configured
 * {@code default} (or the first candidate) — a deterministic outcome that is safe to memoize. On a
 * <b>transient judge failure</b> (resolve/call error) it instead <b>abstains</b> by returning
 * {@code null}, so the router degrades to its own default and a wrapping {@link CachingStrategy}
 * does not pin the conversation to a fallback; the judge is retried on the next round. It does not
 * cache — wrap it in {@link CachingStrategy} (the router does this by default) so the judge runs
 * once per conversation.
 *
 * <p><b>Security note.</b> The user's message is sent to the judge model, so this routing decision
 * is susceptible to prompt injection (a crafted message could steer the choice). Do not gate cost,
 * privilege, or safety solely on the LLM router's decision; treat it as a best-effort hint and keep
 * authoritative controls elsewhere. Parsing prefers an exact model-name reply.
 *
 * <h2>Configuration ({@link ResourceDescriptor} arguments)</h2>
 *
 * <ul>
 *   <li>{@code judge_model} (required) — name of a registered {@code CHAT_MODEL} resource
 *       (typically small/cheap) that performs the routing decision.
 *   <li>{@code default} (optional) — candidate name used when the judge's answer cannot be mapped.
 *       Defaults to the first configured candidate.
 *   <li>{@code instruction} (optional) — extra guidance appended to the system prompt.
 * </ul>
 */
public class LlmRoutingStrategy extends AbstractRoutingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(LlmRoutingStrategy.class);

    public static final String ARG_JUDGE_MODEL = "judge_model";
    public static final String ARG_DEFAULT = "default";
    public static final String ARG_INSTRUCTION = "instruction";

    private final String judgeModel;
    private final String defaultModel;
    private final String instruction;

    public LlmRoutingStrategy(ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);
        this.judgeModel = arg(ARG_JUDGE_MODEL);
        if (judgeModel == null || judgeModel.isEmpty()) {
            throw new IllegalArgumentException(
                    "LlmRoutingStrategy requires a 'judge_model' (a registered CHAT_MODEL name).");
        }
        this.defaultModel = arg(ARG_DEFAULT);
        this.instruction = arg(ARG_INSTRUCTION, "");
    }

    @Override
    public String route(RoutingContext context) {
        List<RoutingCandidate> candidates = context.getCandidates();
        String defaultName = resolveDefault(candidates);
        if (defaultName == null) {
            throw new IllegalStateException("LlmRoutingStrategy has no candidates to route to.");
        }
        if (resourceContext == null) {
            // No way to consult the judge: abstain so the router degrades. Return null (not the
            // default) so this non-deterministic miss is not memoized by a wrapping cache.
            LOG.warn("No ResourceContext available; abstaining so the router can degrade.");
            return null;
        }
        try {
            Object resource = resourceContext.getResource(judgeModel, ResourceType.CHAT_MODEL);
            if (!(resource instanceof BaseChatModelSetup)) {
                throw new IllegalStateException(
                        "Judge model '" + judgeModel + "' is not a CHAT_MODEL setup.");
            }
            BaseChatModelSetup judge = (BaseChatModelSetup) resource;

            List<ChatMessage> messages =
                    Arrays.asList(
                            new ChatMessage(MessageRole.SYSTEM, buildSystemPrompt(candidates)),
                            new ChatMessage(MessageRole.USER, context.firstUserMessage()));

            ChatMessage response =
                    judge.chat(messages, Collections.emptyMap(), Collections.emptyMap());
            String chosen = parseChoice(response.getContent(), candidates);
            if (chosen != null) {
                return chosen;
            }
            // The judge answered, but named nothing recognizable. This is a deterministic outcome
            // for this request, so it is safe to return (and cache) the default.
            LOG.warn(
                    "Judge model '{}' returned an unrecognized choice; using default '{}'.",
                    judgeModel,
                    defaultName);
            return defaultName;
        } catch (Exception e) {
            // Transient judge failure (resolve/call error): abstain with null so the router
            // degrades and a wrapping cache does NOT pin this conversation to a fallback — the
            // judge is retried on the next round.
            LOG.warn(
                    "LLM routing via judge '{}' failed; abstaining. Cause: {}",
                    judgeModel,
                    e.toString());
            return null;
        }
    }

    private String resolveDefault(List<RoutingCandidate> candidates) {
        if (defaultModel != null && !defaultModel.isEmpty()) {
            return defaultModel;
        }
        return candidates.isEmpty() ? null : candidates.get(0).getName();
    }

    private String buildSystemPrompt(List<RoutingCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                        "You are a model router. Choose the single best model to answer the user's request. ")
                .append("Reply with ONLY the model name, exactly as listed, and nothing else.\n\n")
                .append("Available models:\n");
        for (RoutingCandidate candidate : candidates) {
            sb.append("- ").append(candidate.getName());
            if (!candidate.getDescription().isEmpty()) {
                sb.append(": ").append(candidate.getDescription());
            }
            sb.append('\n');
        }
        if (instruction != null && !instruction.isEmpty()) {
            sb.append('\n').append(instruction).append('\n');
        }
        return sb.toString();
    }

    /**
     * Map the judge's free-text answer back to a candidate name. Prefers an exact (trimmed,
     * case-insensitive) match; otherwise the longest candidate name that appears as a whole token
     * (bounded by non-identifier characters) so that e.g. a "gpt-4o-mini" reply does not match a
     * configured "gpt-4".
     */
    private static String parseChoice(String answer, List<RoutingCandidate> candidates) {
        if (answer == null) {
            return null;
        }
        String normalized = answer.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }

        for (RoutingCandidate candidate : candidates) {
            if (normalized.equals(candidate.getName().toLowerCase(Locale.ROOT))) {
                return candidate.getName();
            }
        }

        List<RoutingCandidate> byLengthDesc = new ArrayList<>(candidates);
        byLengthDesc.sort((a, b) -> b.getName().length() - a.getName().length());
        for (RoutingCandidate candidate : byLengthDesc) {
            // Whole-token match: the name must not be flanked by a word char or '-', so "gpt-4"
            // won't match inside "gpt-4o" or "gpt-4-mini". '.' is treated as a boundary so a model
            // name ending a sentence (e.g. "...use big.") still matches.
            Pattern p =
                    Pattern.compile(
                            "(?<![\\w-])"
                                    + Pattern.quote(candidate.getName().toLowerCase(Locale.ROOT))
                                    + "(?![\\w-])");
            if (p.matcher(normalized).find()) {
                return candidate.getName();
            }
        }
        return null;
    }

    /** Visible for configuration/testing: the judge model resource name. */
    public String getJudgeModel() {
        return judgeModel;
    }
}
