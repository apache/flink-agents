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

import org.apache.flink.agents.api.chat.messages.ChatMessage;
import org.apache.flink.agents.api.chat.messages.MessageRole;
import org.apache.flink.agents.api.chat.model.BaseChatModelSetup;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Tests for {@link ChatModelAction}. */
class ChatModelActionTest {

    private static ChatMessage responseWith(Map<String, Object> extraArgs) {
        return new ChatMessage(MessageRole.ASSISTANT, "response", extraArgs);
    }

    @Test
    void testRecordChatTokenMetricsRecordsWhenAllKeysPresent() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", 100L);
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup).recordTokenMetrics("m", 100L, 50L);
    }

    @Test
    void testRecordChatTokenMetricsHandlesIntegerTokenValues() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", 100);
        extraArgs.put("completionTokens", 50);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup).recordTokenMetrics("m", 100L, 50L);
    }

    @Test
    void testRecordChatTokenMetricsSkipsWhenTokenValueNonNumeric() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("promptTokens", "100");
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup, never()).recordTokenMetrics(anyString(), anyLong(), anyLong());
    }

    @Test
    void testRecordChatTokenMetricsSkipsWhenKeyMissing() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);
        Map<String, Object> extraArgs = new HashMap<>();
        extraArgs.put("model_name", "m");
        extraArgs.put("completionTokens", 50L);

        ChatModelAction.recordChatTokenMetrics(setup, responseWith(extraArgs));

        verify(setup, never()).recordTokenMetrics(anyString(), anyLong(), anyLong());
    }

    @Test
    void testRecordChatTokenMetricsSkipsZeroTokensOrEmptyModel() {
        BaseChatModelSetup setup = mock(BaseChatModelSetup.class);

        Map<String, Object> zeroPrompt = new HashMap<>();
        zeroPrompt.put("model_name", "m");
        zeroPrompt.put("promptTokens", 0L);
        zeroPrompt.put("completionTokens", 50L);
        ChatModelAction.recordChatTokenMetrics(setup, responseWith(zeroPrompt));

        Map<String, Object> emptyModel = new HashMap<>();
        emptyModel.put("model_name", "");
        emptyModel.put("promptTokens", 100L);
        emptyModel.put("completionTokens", 50L);
        ChatModelAction.recordChatTokenMetrics(setup, responseWith(emptyModel));

        verify(setup, never()).recordTokenMetrics(anyString(), anyLong(), anyLong());
    }
}
