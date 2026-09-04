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

import org.apache.flink.annotation.Internal;
import org.apache.flink.util.MathUtils;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;

import java.util.Map;

/** Partitions versioned action-state records by their encoded business-key identity. */
@Internal
public class ActionStateKeyPartitioner implements Partitioner {

    @Override
    public int partition(
            String topic,
            Object key,
            byte[] keyBytes,
            Object value,
            byte[] valueBytes,
            Cluster cluster) {
        int numPartitions = cluster.partitionsForTopic(topic).size();
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (!(key instanceof String)) {
            throw new IllegalArgumentException("Key must be a String");
        }

        String businessKeyIdentity = ActionStateUtil.businessKeyIdentityOf((String) key);
        if (businessKeyIdentity == null) {
            throw new IllegalArgumentException("Key format is invalid");
        }
        if (businessKeyIdentity.isEmpty()) {
            throw new IllegalArgumentException("Business key identity cannot be empty");
        }

        return MathUtils.murmurHash(businessKeyIdentity.hashCode()) % numPartitions;
    }

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> configs) {}
}
