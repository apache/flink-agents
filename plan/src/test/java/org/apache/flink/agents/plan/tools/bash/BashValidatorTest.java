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

package org.apache.flink.agents.plan.tools.bash;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashValidatorTest {

    @Test
    void emptyCommandRejected() {
        assertEquals(
                Optional.of("Empty command."),
                BashValidator.validate("", List.of("echo"), List.of(), null));
        assertEquals(
                Optional.of("Empty command."),
                BashValidator.validate("   ", List.of("echo"), List.of(), null));
    }

    @Test
    void simpleAllowedCommandPasses() {
        assertEquals(
                Optional.empty(),
                BashValidator.validate("echo hello", List.of("echo"), List.of(), null));
    }

    @Test
    void unknownCommandRejected() {
        Optional<String> r = BashValidator.validate("rm -rf /", List.of("echo"), List.of(), null);
        assertTrue(r.isPresent());
        assertTrue(r.get().contains("'rm' is not allowed"));
    }

    @Test
    void pipelineAllowedWhenAllPartsAllowed() {
        assertEquals(
                Optional.empty(),
                BashValidator.validate(
                        "echo hi | tr a-z A-Z", List.of("echo", "tr"), List.of(), null));
    }

    @Test
    void pipelineRejectedWhenAnyPartUnknown() {
        Optional<String> r =
                BashValidator.validate("echo hi | grep h", List.of("echo"), List.of(), null);
        assertTrue(r.isPresent());
        assertTrue(r.get().contains("'grep'"));
    }

    @Test
    void variableExpansionAllowed() {
        assertEquals(
                Optional.empty(),
                BashValidator.validate("echo $HOME", List.of("echo"), List.of(), null));
    }

    @Test
    void arithmeticExpansionAllowed() {
        assertEquals(
                Optional.empty(),
                BashValidator.validate("echo $((1+2))", List.of("echo"), List.of(), null));
    }

    @Test
    void commandSubstitutionRejected() {
        Optional<String> r =
                BashValidator.validate("echo $(rm /etc/passwd)", List.of("echo"), List.of(), null);
        assertTrue(r.isPresent());
        assertTrue(r.get().contains("command_substitution"));
    }

    @Test
    void backticksRejected() {
        Optional<String> r =
                BashValidator.validate("echo `whoami`", List.of("echo"), List.of(), null);
        assertTrue(r.isPresent());
    }

    @Test
    void controlFlowRejected() {
        Optional<String> r =
                BashValidator.validate(
                        "for i in 1 2 3; do echo $i; done", List.of("echo"), List.of(), null);
        assertTrue(r.isPresent());
        assertTrue(r.get().contains("for_statement"));
    }

    @Test
    void redirectAllowed() {
        // basic redirect of allowed command should pass
        Optional<String> r =
                BashValidator.validate("echo hi > /tmp/x", List.of("echo"), List.of(), null);
        assertEquals(Optional.empty(), r);
    }
}
