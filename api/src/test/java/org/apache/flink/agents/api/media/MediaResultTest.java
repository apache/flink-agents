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

package org.apache.flink.agents.api.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.agents.api.chat.messages.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Required content is distinguished from an explicitly empty result. */
class MediaResultTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rejectsNullContent() {
        assertThatThrownBy(() -> new MediaResult<TextBlock>(null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("content");
    }

    @Test
    void rejectsNullContentWhenRestoring() {
        assertThatThrownBy(
                        () ->
                                MAPPER.readValue(
                                        "{\"content\":null}",
                                        MAPPER.getTypeFactory()
                                                .constructParametricType(
                                                        MediaResult.class, TextBlock.class)))
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasRootCauseMessage("content");
    }

    @Test
    void permitsAnExplicitlyEmptyResult() throws Exception {
        MediaResult<TextBlock> result = new MediaResult<>(List.of(), null, null, null, null);

        MediaResult<TextBlock> restored =
                MAPPER.readValue(
                        MAPPER.writeValueAsString(result),
                        MAPPER.getTypeFactory()
                                .constructParametricType(MediaResult.class, TextBlock.class));

        assertThat(restored.getContent()).isEmpty();
    }
}
