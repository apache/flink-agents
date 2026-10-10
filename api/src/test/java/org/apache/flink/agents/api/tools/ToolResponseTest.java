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

package org.apache.flink.agents.api.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.ImageBlock;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.apache.flink.agents.api.chat.messages.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void onlyContentBlocksAreProjectedIntoTheModelMessage() throws Exception {
        ToolResponse response =
                ToolResponse.success(
                                List.of(
                                        new TextBlock("before"),
                                        ImageBlock.fromBytes("image/png", new byte[] {1}),
                                        new TextBlock("after")),
                                5,
                                "tool")
                        .withMetadata(Map.of("private", "internal"));
        ToolResultBlock result = response.toResultBlock("call");
        assertThat(result.getBlocks()).containsExactlyElementsOf(response.getBlocks());
        assertThat(result.getMetadata()).isEmpty();
        assertThat(response.getText()).isEqualTo("beforeafter");
        assertThat(mapper.writeValueAsString(result)).doesNotContain("internal");
        JsonNode wire = mapper.valueToTree(response);
        assertThat(wire.has("result")).isFalse();
        assertThat(wire.has("text")).isFalse();
        assertThat(wire.size()).isEqualTo(6);
        assertThat(mapper.treeToValue(wire, ToolResponse.class)).isEqualTo(response);
    }

    @Test
    void emptyContentDoesNotFallBackToNullText() {
        ToolResponse response = ToolResponse.success(List.of());
        assertThat(response.getText()).isEmpty();
        assertThat(response.toResultBlock("call").getBlocks()).isEmpty();
    }

    @Test
    void errorsProduceModelFacingTextWithoutApplicationMetadata() {
        ToolResponse response =
                ToolResponse.error("failed")
                        .withMetadata(Map.of("binary", new byte[] {(byte) 0xff}));
        ToolResultBlock result = response.toResultBlock("call");
        assertThat(result.isError()).isTrue();
        assertThat(result.getText()).isEqualTo("failed");
        assertThat(result.getMetadata()).isEmpty();
    }
}
