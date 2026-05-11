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
package org.apache.flink.agents.plan.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for {@link ChatModelAction}. */
class ChatModelActionTest {

    @Test
    void testCleanLlmResponseWithJsonBlock() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithGenericCodeBlock() {
        String input = "```\n{\"key\": \"value\"}\n```";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithWhitespace() {
        String input = "  ```json\n{\"key\": \"value\"}\n```  ";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithoutBlock() {
        String input = "{\"key\": \"value\"}";
        String expected = "{\"key\": \"value\"}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithTextAround() {
        // Current implementation uses replaceAll with ^ and $ anchors,
        // so it only matches if the whole (trimmed) string is a code block.
        String input = "Here is the json: ```json {\"key\": \"value\"} ```";
        String expected = "Here is the json: ```json {\"key\": \"value\"} ```";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }

    @Test
    void testCleanLlmResponseWithMultipleLinesInBlock() {
        String input = "```json\n{\n  \"key\": \"value\"\n}\n```";
        String expected = "{\n  \"key\": \"value\"\n}";
        assertEquals(expected, ChatModelAction.cleanLlmResponse(input));
    }
}
