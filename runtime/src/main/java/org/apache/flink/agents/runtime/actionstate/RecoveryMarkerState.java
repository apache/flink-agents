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
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;

/** Union operator state shared by durable recovery and offline checkpoint readers. */
@Internal
public final class RecoveryMarkerState {

    public static final String NAME = "recoveryMarker";

    private RecoveryMarkerState() {}

    public static TypeInformation<Object> typeInformation() {
        return TypeInformation.of(Object.class);
    }

    public static ListStateDescriptor<Object> descriptor() {
        return new ListStateDescriptor<>(NAME, typeInformation());
    }
}
