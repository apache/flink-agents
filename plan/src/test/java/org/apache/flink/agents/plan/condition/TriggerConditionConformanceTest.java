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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.plan.condition.TriggerCondition.EventTypeCondition;
import org.apache.flink.agents.plan.condition.TriggerCondition.ExpressionCondition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Plan-layer consumer of the shared trigger-condition behavior matrix. */
class TriggerConditionConformanceTest {

    private static final String FIXTURE_PATH =
            "e2e-test/cross-language-trigger-condition-fixtures/trigger_condition_conformance.json";
    private static final JsonNode FIXTURE = loadFixture();

    static Stream<Arguments> fixtureCases() {
        List<Arguments> cases = new ArrayList<>();
        for (JsonNode testCase : FIXTURE.withArray("cases")) {
            cases.add(
                    Arguments.of(
                            testCase.get("name").asText(),
                            testCase.get("entry").asText(),
                            testCase.get("classification").asText(),
                            testCase.get("plan_validation").asText(),
                            testCase.has("error_category")
                                    ? testCase.get("error_category").asText()
                                    : null));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureCases")
    void validatesSharedCases(
            String name,
            String source,
            String expectedClassification,
            String expectedValidation,
            String expectedErrorCategory) {
        TriggerCondition condition = TriggerCondition.classify(source);
        if ("event_type".equals(expectedClassification)) {
            assertThat(condition).isInstanceOf(EventTypeCondition.class);
            assertThat(expectedValidation).isEqualTo("pass");
            return;
        }

        assertThat(condition).isInstanceOf(ExpressionCondition.class);
        ExpressionCondition expression = (ExpressionCondition) condition;
        if ("pass".equals(expectedValidation)) {
            assertThatCode(() -> ConditionExpressionValidator.validate(expression))
                    .doesNotThrowAnyException();
            return;
        }

        ConditionExpressionValidator.ValidationException error =
                catchThrowableOfType(
                        () -> ConditionExpressionValidator.validate(expression),
                        ConditionExpressionValidator.ValidationException.class);
        assertThat(error).isNotNull();
        assertThat(error.category().name().toLowerCase()).isEqualTo(expectedErrorCategory);
    }

    private static JsonNode loadFixture() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(FIXTURE_PATH);
            if (Files.isRegularFile(candidate)) {
                try {
                    return new ObjectMapper().readTree(candidate.toFile());
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to read trigger-condition fixture " + candidate, e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not find trigger-condition fixture " + FIXTURE_PATH);
    }
}
