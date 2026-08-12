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

package org.apache.flink.agents.integrations.chatmodels.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/** Test helpers for inspecting the timeout configured in openai-java. */
final class OpenAIClientTestUtils {

    private OpenAIClientTestUtils() {}

    static void assertNoTimeoutConfigured(Object connection) {
        OpenAIClient client = readField(connection, "client", OpenAIClient.class);
        ClientOptions options = readField(client, "clientOptions", ClientOptions.class);

        // A zero duration maps to an unlimited timeout in each OkHttp timeout component.
        assertThat(options.timeout().connect()).isZero();
        assertThat(options.timeout().read()).isZero();
        assertThat(options.timeout().write()).isZero();
        assertThat(options.timeout().request()).isZero();
    }

    private static <T> T readField(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to inspect openai-java client configuration", e);
        }
    }
}
