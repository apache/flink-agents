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

package org.apache.flink.agents.api.metrics;

import org.apache.flink.metrics.Gauge;

/**
 * StringGauge interface is designed to measure and update string values. It extends the Gauge
 * interface with a generic type of String. This interface is used to define standard operations for
 * components that need to monitor and update string information.
 */
public interface StringGauge extends Gauge<String> {
    /**
     * Updates the value of the StringGauge.
     *
     * @param value The new string value to be set, should not be null.
     */
    public void update(String value);
}
