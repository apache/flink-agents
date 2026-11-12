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
package org.apache.flink.agents.api.context;

/** Controls how a delayed Event is executed relative to the current agent run. */
public enum TimerMode {
    /**
     * Delivers the Event through the same-key input queue to start a new run with fresh sensory
     * memory. The current run can finish without waiting for the timer, and subsequent inputs with
     * the same key can then be processed. The timer does not preempt an active run.
     */
    TRIGGER_AFTER_CURRENT_RUN
}
