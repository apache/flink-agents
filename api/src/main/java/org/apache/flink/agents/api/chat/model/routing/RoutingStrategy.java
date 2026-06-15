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

/**
 * The pluggable <b>selection</b> logic of a {@link ChatModelRouter}: given a request, pick which
 * candidate model should serve it. This is intentionally a <i>pure</i> concern — it returns a
 * single model name and does not deal with resilience or caching, which are layered separately
 * ({@link FallbackPolicy} for fallback, {@link CachingStrategy} for memoization).
 *
 * <p>Selection can be driven by any approach:
 *
 * <ul>
 *   <li><b>Rule-based</b> — deterministic keyword/regex/metadata rules ({@link
 *       RuleBasedRoutingStrategy}, built-in).
 *   <li><b>LLM-as-router</b> — a small judge model picks the candidate ({@link LlmRoutingStrategy},
 *       built-in).
 *   <li><b>ML / learned</b> — a trained classifier or learned scorer (e.g. RouteLLM-style, or
 *       embedding-similarity over per-route examples) chooses the candidate. This is supported as a
 *       <b>bring-your-own</b> strategy: implement {@code route(...)} to run your model and return
 *       the chosen candidate name. No built-in ML strategy ships yet (it carries a model
 *       training/serving lifecycle); it is a planned follow-up.
 *   <li><b>Bring-your-own</b> — any custom logic.
 * </ul>
 *
 * <p>Built-ins and custom (incl. ML) strategies are equally first-class: provide your own by
 * implementing this interface (typically via {@link AbstractRoutingStrategy}) and referencing the
 * class from the router's {@code strategy} {@link
 * org.apache.flink.agents.api.resource.ResourceDescriptor} — no framework change required.
 *
 * <p><b>Stickiness contract.</b> The built-in chat action re-invokes the router on every round of a
 * multi-turn tool-calling conversation, passing the accumulated messages. To keep the same model
 * across the whole conversation, a strategy must be deterministic with respect to the original
 * request. Built-in strategies key on {@link RoutingContext#firstUserMessage()}; custom strategies
 * are encouraged to do the same (and to wrap with {@link CachingStrategy} when the decision is
 * expensive, e.g. an LLM judge).
 */
@FunctionalInterface
public interface RoutingStrategy {

    /**
     * Choose the candidate model that should handle this request.
     *
     * <p>Return one of the configured candidate names to select it. Return {@code null} to
     * <b>abstain</b> ("no opinion" — e.g. a transient judge failure): the router then degrades to
     * its configured {@code default} candidate, and a wrapping {@link CachingStrategy} will not
     * memoize the abstention. A returned name that is not a configured candidate is treated by the
     * router as a routing miss (same degrade-to-default behaviour), not a hard failure.
     *
     * @param context the request messages, prompt args, candidates, and resource context
     * @return the chosen candidate model name, or {@code null} to abstain
     * @throws Exception if the decision could not be made
     */
    String route(RoutingContext context) throws Exception;
}
