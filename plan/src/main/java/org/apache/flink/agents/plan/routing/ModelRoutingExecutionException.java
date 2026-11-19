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

package org.apache.flink.agents.plan.routing;

/**
 * A routed round produced no accepted answer: every reachable candidate failed, a quality signal
 * could not judge the round, or every tier answered and was rejected. {@link #error} is what the
 * request fails with.
 */
public final class ModelRoutingExecutionException extends Exception {

    public final Exception error;

    ModelRoutingExecutionException(Exception error) {
        super(error.getMessage(), error);
        this.error = error;
    }
}
