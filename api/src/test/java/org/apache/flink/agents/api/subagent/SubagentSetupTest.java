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

package org.apache.flink.agents.api.subagent;

import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the construction contract of {@link SubagentSetup}: every setup carries the descriptor a
 * remote task rebuilds it from, and that descriptor names the setup's own type, so a registered
 * sub-agent is always rebuildable into the right class.
 */
public class SubagentSetupTest {

    @Test
    void nullDescriptorIsRejectedAtConstruction() {
        // The descriptor is what a remote task rebuilds the setup from, so construction requires
        // one and reports a missing descriptor at the point of the mistake.
        assertThatThrownBy(() -> new TestSubagentSetup((ResourceDescriptor) null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must carry a ResourceDescriptor");
    }

    @Test
    void mismatchedDescriptorClazzIsRejectedAtConstruction() {
        // The descriptor names the class a remote task reflects over to rebuild the setup, so it
        // must name this setup's own type; a copy-paste or aliasing mistake is caught here rather
        // than rebuilding the wrong class on a far task.
        ResourceDescriptor mismatched =
                ResourceDescriptor.Builder.newBuilder("com.example.SomeOtherSubagent").build();
        assertThatThrownBy(() -> new TestSubagentSetup(mismatched, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry a descriptor naming its own type");
    }
}
