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


package org.apache.flink.agents.runtime.utils;

import org.apache.flink.api.common.serialization.SerializerConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.api.java.typeutils.runtime.kryo.KryoSerializer;

/**
 * Type information for runtime-internal types that the agent operator serializes with Kryo, such
 * as {@code Event}, {@code ActionTask} and the memory items kept in state.
 *
 * <p>{@link TypeInformation#of(Class)} resolves these types to {@link GenericTypeInfo}, whose
 * serializer creation fails when the job sets {@code pipeline.generic-types} to {@code false}. That
 * option exists to stop user types from silently falling back to Kryo, but these types are owned by
 * the runtime and are always Kryo serialized. This class creates the same {@link KryoSerializer}
 * without consulting the option, so existing state stays readable and agents run with generic types
 * disabled.
 *
 * @param <T> the type to serialize.
 */
public class InternalKryoTypeInfo<T> extends GenericTypeInfo<T> {

    private static final long serialVersionUID = 1L;

    private InternalKryoTypeInfo(Class<T> typeClass) {
        super(typeClass);
    }

    /** Returns the type information that always serializes {@code typeClass} with Kryo. */
    public static <T> TypeInformation<T> of(Class<T> typeClass) {
        return new InternalKryoTypeInfo<>(typeClass);
    }

    @Override
    public TypeSerializer<T> createSerializer(SerializerConfig config) {
        return new KryoSerializer<>(getTypeClass(), config);
    }
}
