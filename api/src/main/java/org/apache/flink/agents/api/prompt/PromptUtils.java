/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.agents.api.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatter utility for prompt template substitution, */
public class PromptUtils {

    private static final Pattern BRACE_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    /** Format template string with keyword arguments */
    public static String format(String template, Map<String, String> kwargs) {
        if (template == null) {
            return "";
        }

        String result = template;
        for (Map.Entry<String, String> entry : kwargs.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /** Extract variable names from template */
    public static java.util.Set<String> extractVariables(String template) {
        java.util.Set<String> variables = new java.util.HashSet<>();
        Matcher matcher = BRACE_PATTERN.matcher(template);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    /** Validate that all required variables are provided */
    public static void validateTemplate(String template, Map<String, String> kwargs) {
        java.util.Set<String> required = extractVariables(template);
        java.util.Set<String> missing = new java.util.HashSet<>(required);
        missing.removeAll(kwargs.keySet());

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required variables: " + missing);
        }
    }
}
