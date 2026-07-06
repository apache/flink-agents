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

package org.apache.flink.agents.api.yaml;

import org.apache.flink.agents.api.annotation.ToolParam;

/** Static targets referenced by YAML fixtures. */
public final class LoaderTargets {
    private LoaderTargets() {}

    public static void increment(Object event, Object ctx) {}

    public static void decrement(Object event, Object ctx) {}

    public static String notify(String id, String message) {
        return "notified " + id + ": " + message;
    }

    public static String queryOrder(
            @ToolParam(name = "order_id") String orderId,
            @ToolParam(name = "tenant_id") String tenantId) {
        return tenantId + ":" + orderId;
    }

    public static final class Counter {
        private Counter() {}

        public static void bump(Object event, Object ctx) {}
    }
}
