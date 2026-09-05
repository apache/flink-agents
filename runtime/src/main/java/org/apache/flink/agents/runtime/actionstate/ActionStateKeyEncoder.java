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
package org.apache.flink.agents.runtime.actionstate;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.plan.actions.Action;
import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.function.IntPredicate;

/**
 * Encodes and validates versioned action-state keys using an operator's keyed-state serializer.
 *
 * <p>Every key carries a fingerprint of the serializer snapshot. Recovery requires an identical
 * snapshot even when Flink accepts the new serializer: reading old bytes successfully does not
 * guarantee that serializing the same key produces the same digest. Custom key serializers must
 * produce deterministic bytes and describe encoding changes in their snapshots.
 */
@Internal
public final class ActionStateKeyEncoder {

    private final int maxParallelism;
    private final TypeSerializer<Object> keySerializer;
    private final String serializerFingerprint;

    public ActionStateKeyEncoder(int maxParallelism, TypeSerializer<?> keySerializer) {
        Preconditions.checkArgument(
                maxParallelism > 0,
                "maxParallelism must be positive but was %s; it must match the operator's maximum parallelism.",
                maxParallelism);
        this.maxParallelism = maxParallelism;
        this.keySerializer = duplicateKeySerializer(keySerializer);
        this.serializerFingerprint =
                ActionStateUtil.generateSerializerFingerprint(this.keySerializer);
    }

    public String generateKey(Object key, long seqNum, Action action, Event event)
            throws IOException {
        return ActionStateUtil.generateKey(
                key, seqNum, action, event, maxParallelism, keySerializer, serializerFingerprint);
    }

    public String generateBusinessKeyIdentity(Object key) {
        return ActionStateUtil.generateBusinessKeyIdentity(key, keySerializer);
    }

    public boolean isKeyRetained(@Nullable IntPredicate ownershipFilter, String stateKey) {
        return ActionStateUtil.isKeyRetained(
                ownershipFilter, stateKey, maxParallelism, serializerFingerprint);
    }

    @VisibleForTesting
    String getSerializerFingerprint() {
        return serializerFingerprint;
    }

    @SuppressWarnings("unchecked")
    private static TypeSerializer<Object> duplicateKeySerializer(TypeSerializer<?> keySerializer) {
        return (TypeSerializer<Object>)
                Preconditions.checkNotNull(keySerializer, "keySerializer cannot be null")
                        .duplicate();
    }
}
