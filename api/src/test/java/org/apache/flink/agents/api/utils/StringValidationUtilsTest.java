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

package org.apache.flink.agents.api.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link StringValidationUtils}. */
class StringValidationUtilsTest {
    @Test
    void requireNonBlankValidatesStrings() {
        assertThat(StringValidationUtils.requireNonBlank("gpt-img", "model")).isEqualTo("gpt-img");
        assertThat(StringValidationUtils.requireNonBlank(" a ", "model")).isEqualTo(" a ");

        for (Object invalid : new Object[] {null, "", "   ", 123}) {
            assertThatThrownBy(() -> StringValidationUtils.requireNonBlank(invalid, "model"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'model' must be a non-blank string.");
        }
    }

    @Test
    void optionalNonBlankAllowsNullAndDelegatesOtherwise() {
        assertThat(StringValidationUtils.optionalNonBlank(null, "voice")).isNull();
        assertThat(StringValidationUtils.optionalNonBlank("Cherry", "voice")).isEqualTo("Cherry");

        for (Object invalid : new Object[] {"", "   ", 42}) {
            assertThatThrownBy(() -> StringValidationUtils.optionalNonBlank(invalid, "voice"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'voice' must be a non-blank string.");
        }
    }
}
