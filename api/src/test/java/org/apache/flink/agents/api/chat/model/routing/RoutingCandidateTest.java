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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests for {@link RoutingCandidate} construction guards. */
class RoutingCandidateTest {

    @Test
    @DisplayName("a null candidate name is rejected")
    void testRejectsNullName() {
        assertThrows(NullPointerException.class, () -> new RoutingCandidate(null));
    }

    @Test
    @DisplayName("an empty candidate name is rejected (would over-match in whole-token parsing)")
    void testRejectsEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingCandidate(""));
    }

    @Test
    @DisplayName("a non-empty name is accepted")
    void testAcceptsName() {
        assertEquals("gpt-4o", new RoutingCandidate("gpt-4o").getName());
    }
}
