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

package org.apache.flink.agents.plan.condition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.flink.agents.plan.condition.ActionSelector.ConditionExpression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ConditionExpressionValidatorTest {

    private static final String MACRO_FIXTURE_RESOURCE = "/disallowed_macros.yaml";

    @Test
    void rejectsSyntaxErrors() {
        ConditionExpressionValidator.ValidationException error =
                catchThrowableOfType(
                        () -> validateExpression("type =="),
                        ConditionExpressionValidator.ValidationException.class);

        assertThat(error.category()).isEqualTo(ConditionExpressionValidator.ErrorCategory.SYNTAX);
        assertThat(error).hasMessageContaining("type ==");
    }

    static Stream<Arguments> rejectedMacroFixtureCases() throws IOException {
        return loadMacroFixture().get("reject").stream().map(Arguments::of);
    }

    static Stream<Arguments> acceptedMacroFixtureCases() throws IOException {
        return loadMacroFixture().get("accept").stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "reject unsupported macro: {0}")
    @MethodSource("rejectedMacroFixtureCases")
    void rejectsUnsupportedMacros(String source) {
        ConditionExpressionValidator.ValidationException error =
                catchThrowableOfType(
                        () -> validateExpression(source),
                        ConditionExpressionValidator.ValidationException.class);

        assertThat(error).isNotNull();
        assertThat(error.category())
                .isEqualTo(ConditionExpressionValidator.ErrorCategory.UNSUPPORTED_CONSTRUCT);
    }

    @ParameterizedTest(name = "accept supported expression: {0}")
    @MethodSource("acceptedMacroFixtureCases")
    void acceptsSupportedExpressions(String source) {
        assertThatCode(() -> validateExpression(source)).doesNotThrowAnyException();
    }

    @Test
    void defersNonMacroCallsToRuntime() {
        for (String source : new String[] {"all(1)", "map(1)", "foo.map()"}) {
            assertThatCode(() -> validateExpression(source)).doesNotThrowAnyException();
        }
    }

    @Test
    void validatesEventTypeReferences() {
        assertThatCode(() -> validateExpression("type == EventType.InputEvent"))
                .doesNotThrowAnyException();

        ConditionExpressionValidator.ValidationException error =
                catchThrowableOfType(
                        () -> validateExpression("type == EventType.NotARealEvent"),
                        ConditionExpressionValidator.ValidationException.class);
        assertThat(error.category())
                .isEqualTo(ConditionExpressionValidator.ErrorCategory.UNKNOWN_EVENT_TYPE);
        assertThat(error)
                .hasMessageContaining("Unknown EventType constant")
                .hasMessageContaining("EventType.NotARealEvent");
    }

    @Test
    void defersResultTypeValidationToRuntime() {
        for (String source :
                new String[] {
                    "true",
                    "false",
                    "null",
                    "42",
                    "'not a routable name'",
                    "[1, 2]",
                    "{'key': 1}",
                    "1 + 2",
                    "size(attributes)",
                    "attributes['key']",
                    "noSuchFn(1)",
                    "order-created"
                }) {
            assertThatCode(() -> validateExpression(source)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsStandalonePaths() {
        for (String source :
                new String[] {
                    "(a.b.c)", "a . b . c", "a . b. c", "(score)", "EventType.InputEvent"
                }) {
            ConditionExpressionValidator.ValidationException error =
                    catchThrowableOfType(
                            () -> validateExpression(source),
                            ConditionExpressionValidator.ValidationException.class);

            assertThat(error).as(source).isNotNull();
            assertThat(error.category())
                    .as(source)
                    .isEqualTo(ConditionExpressionValidator.ErrorCategory.UNSUPPORTED_CONSTRUCT);
            assertThat(error).as(source).hasMessageContaining("explicit comparison");
        }
    }

    @Test
    void allowsBooleanPathConditions() {
        for (String source : new String[] {"a.b.c == true", "has(a.b.c)"}) {
            assertThatCode(() -> validateExpression(source)).as(source).doesNotThrowAnyException();
        }
    }

    @Test
    void enforcesSyntaxResourceLimits() {
        String oversized = "true" + " || true".repeat(2_000);
        String tooDeep = "(".repeat(40) + "true" + ")".repeat(40);

        for (String source : new String[] {oversized, tooDeep}) {
            ConditionExpressionValidator.ValidationException error =
                    catchThrowableOfType(
                            () -> validateExpression(source),
                            ConditionExpressionValidator.ValidationException.class);
            assertThat(error.category())
                    .isEqualTo(ConditionExpressionValidator.ErrorCategory.SYNTAX);
        }
    }

    private static void validateExpression(String source) {
        ActionSelector selector = ActionSelector.classify(source);
        assertThat(selector).isInstanceOf(ConditionExpression.class);
        ConditionExpressionValidator.validate((ConditionExpression) selector);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> loadMacroFixture() throws IOException {
        InputStream input =
                ConditionExpressionValidatorTest.class.getResourceAsStream(MACRO_FIXTURE_RESOURCE);
        assertThat(input)
                .as("Shared macro fixture must exist: %s", MACRO_FIXTURE_RESOURCE)
                .isNotNull();
        return new ObjectMapper(new YAMLFactory()).readValue(input, Map.class);
    }
}
