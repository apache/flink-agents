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

package org.apache.flink.agents.runtime.operator;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.serialization.SerializerConfig;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.base.LongSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OperatorStateManagerProcessingKeysTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void registersProcessingKeysWithTheSelectedSerializer(boolean disableGenericTypes)
            throws Exception {
        ExecutionConfig executionConfig = new ExecutionConfig();
        Configuration configuration = new Configuration();
        configuration.set(PipelineOptions.GENERIC_TYPES, !disableGenericTypes);
        executionConfig.configure(configuration, getClass().getClassLoader());
        SerializerConfig serializerConfig = executionConfig.getSerializerConfig();
        TypeSerializer<Object> keySerializer =
                (TypeSerializer<Object>) (TypeSerializer<?>) LongSerializer.INSTANCE;
        TypeSerializer<?> expectedSerializer =
                disableGenericTypes
                        ? keySerializer
                        : TypeInformation.of(Object.class).createSerializer(serializerConfig);
        OperatorStateBackend backend = mock(OperatorStateBackend.class);
        doAnswer(
                        invocation -> {
                            ListStateDescriptor<?> descriptor = invocation.getArgument(0);
                            assertThat(descriptor.getName()).isEqualTo("currentProcessingKeys");
                            descriptor.initializeSerializerUnlessSet(executionConfig);
                            assertThat(descriptor.getElementSerializer())
                                    .isEqualTo(expectedSerializer);
                            return null;
                        })
                .when(backend)
                .getUnionListState(ArgumentMatchers.<ListStateDescriptor<Object>>any());

        new OperatorStateManager()
                .initializeOperatorStates(backend, serializerConfig, keySerializer);

        verify(backend).getUnionListState(ArgumentMatchers.<ListStateDescriptor<Object>>any());
    }
}
