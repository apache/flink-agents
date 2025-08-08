/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.agents.runtime.metrics;

import org.apache.flink.agents.api.metrics.UpdatableGauge;

/**
 * Implementation of the UpdatableGauge interface, used to monitor and track values. This class
 * provides methods to set and retrieve the monitored value.
 *
 * @param <T> The type of value being monitored
 */
public class UpdatableGaugeImpl<T> implements UpdatableGauge<T> {
    private T value;

    @Override
    public void update(T value) {
        this.value = value;
    }

    @Override
    public T getValue() {
        return value;
    }
}
