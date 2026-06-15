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

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A deterministic, no-extra-call routing strategy that maps a request to a model using ordered
 * rules.
 *
 * <p>This is the "pluggable rule logic" baseline: predictable, free, and side-effect-free. Rules
 * are evaluated in order against the request text (by default {@link
 * RoutingContext#firstUserMessage()}, which keeps routing sticky across tool-calling rounds), and
 * the first matching rule's model wins. If no rule matches, the {@code default} model is used.
 *
 * <h2>Configuration ({@link ResourceDescriptor} arguments)</h2>
 *
 * <ul>
 *   <li>{@code rules} (required) — an ordered list of rule maps. Each rule has:
 *       <ul>
 *         <li>{@code model} (required) — the candidate model name to route to.
 *         <li>{@code keywords} — a {@link String} or list of strings; matches when any keyword is a
 *             case-insensitive substring of the request text.
 *         <li>{@code regex} — a regular expression matched (find) against the request text.
 *         <li>{@code prompt_arg} + {@code equals} — matches when the named prompt arg equals the
 *             given value (string comparison).
 *       </ul>
 *       A rule matches when <b>any</b> of its present predicates match (OR semantics).
 *   <li>{@code default} (required) — the fallback model name when no rule matches.
 *   <li>{@code case_sensitive} (optional, default {@code false}) — keyword/text comparison mode.
 * </ul>
 */
public class RuleBasedRoutingStrategy extends AbstractRoutingStrategy {

    public static final String ARG_RULES = "rules";
    public static final String ARG_DEFAULT = "default";
    public static final String ARG_CASE_SENSITIVE = "case_sensitive";

    private final List<Rule> rules;
    private final String defaultModel;
    private final boolean caseSensitive;

    @SuppressWarnings("unchecked")
    public RuleBasedRoutingStrategy(
            ResourceDescriptor descriptor, ResourceContext resourceContext) {
        super(descriptor, resourceContext);

        this.defaultModel = arg(ARG_DEFAULT);
        if (defaultModel == null || defaultModel.isEmpty()) {
            throw new IllegalArgumentException(
                    "RuleBasedRoutingStrategy requires a 'default' model name.");
        }
        this.caseSensitive = arg(ARG_CASE_SENSITIVE, Boolean.FALSE);

        List<Object> rawRules = arg(ARG_RULES);
        if (rawRules == null) {
            rawRules = Collections.emptyList();
        }
        List<Rule> parsed = new ArrayList<>(rawRules.size());
        for (Object raw : rawRules) {
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException(
                        "Each rule must be a Map, but got: "
                                + (raw == null ? "null" : raw.getClass().getName()));
            }
            parsed.add(Rule.from((Map<String, Object>) raw, caseSensitive));
        }
        this.rules = parsed;
    }

    @Override
    public String route(RoutingContext context) {
        String text = context.firstUserMessage();
        String haystack = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (rule.matches(haystack, context.getPromptArgs())) {
                return rule.model;
            }
        }
        return defaultModel;
    }

    /** A single compiled rule. */
    private static final class Rule {
        final String model;
        final List<String> keywords; // already case-normalized to match the haystack
        final Pattern regex;
        final String promptArg;
        final String promptArgEquals;

        Rule(
                String model,
                List<String> keywords,
                Pattern regex,
                String promptArg,
                String promptArgEquals) {
            this.model = model;
            this.keywords = keywords;
            this.regex = regex;
            this.promptArg = promptArg;
            this.promptArgEquals = promptArgEquals;
        }

        @SuppressWarnings("unchecked")
        static Rule from(Map<String, Object> map, boolean caseSensitive) {
            Object model = map.get("model");
            if (model == null) {
                throw new IllegalArgumentException("Rule is missing a 'model': " + map);
            }

            List<String> keywords = new ArrayList<>();
            Object kw = map.get("keywords");
            if (kw instanceof CharSequence) {
                keywords.add(normalize(kw.toString(), caseSensitive));
            } else if (kw instanceof List) {
                for (Object item : (List<Object>) kw) {
                    if (item != null) {
                        keywords.add(normalize(item.toString(), caseSensitive));
                    }
                }
            }

            Pattern regex = null;
            Object re = map.get("regex");
            if (re instanceof CharSequence) {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                regex = Pattern.compile(re.toString(), flags);
            }

            Object promptArg = map.get("prompt_arg");
            Object equals = map.get("equals");

            return new Rule(
                    model.toString(),
                    keywords,
                    regex,
                    promptArg != null ? promptArg.toString() : null,
                    equals != null ? equals.toString() : null);
        }

        private static String normalize(String s, boolean caseSensitive) {
            return caseSensitive ? s : s.toLowerCase(Locale.ROOT);
        }

        boolean matches(String haystack, Map<String, Object> promptArgs) {
            for (String keyword : keywords) {
                if (!keyword.isEmpty() && haystack.contains(keyword)) {
                    return true;
                }
            }
            if (regex != null && regex.matcher(haystack).find()) {
                return true;
            }
            if (promptArg != null && promptArgs != null) {
                Object value = promptArgs.get(promptArg);
                if (value != null
                        && (promptArgEquals == null || promptArgEquals.equals(value.toString()))) {
                    return true;
                }
            }
            return false;
        }
    }
}
