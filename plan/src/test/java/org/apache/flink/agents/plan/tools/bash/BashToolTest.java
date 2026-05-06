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

package org.apache.flink.agents.plan.tools.bash;

import org.apache.flink.agents.api.resource.ResourceContext;
import org.apache.flink.agents.api.resource.ResourceDescriptor;
import org.apache.flink.agents.api.tools.ToolParameters;
import org.apache.flink.agents.api.tools.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolTest {

    private static BashTool tool() {
        return new BashTool(
                new ResourceDescriptor(BashTool.class.getName(), Map.of()),
                ResourceContext.fromGetResource((n, t) -> null));
    }

    private static ToolParameters args(
            String command, List<String> allowedCommands, List<String> allowedScriptDirs) {
        Map<String, Object> m = new HashMap<>();
        m.put("command", command);
        m.put("allowed_commands", allowedCommands);
        m.put("allowed_script_dirs", allowedScriptDirs);
        return new ToolParameters(m);
    }

    @Test
    void allowedSimpleCommandRuns() {
        ToolResponse r = tool().call(args("echo hello", List.of("echo"), List.of()));
        assertEquals("hello", r.getResult());
    }

    @Test
    void disallowedCommandRejected() {
        ToolResponse r = tool().call(args("rm -rf /", List.of("echo"), List.of()));
        String out = (String) r.getResult();
        assertTrue(out.startsWith("Command rejected:"));
        assertTrue(out.contains("'rm' is not allowed"));
    }

    @Test
    void controlFlowRejected() {
        ToolResponse r =
                tool().call(args("for i in 1 2 3; do echo $i; done", List.of("echo"), List.of()));
        String out = (String) r.getResult();
        assertTrue(out.startsWith("Command rejected:"));
    }

    @Test
    void successfulCommandWithEmptyOutput() {
        ToolResponse r = tool().call(args("true", List.of("true"), List.of()));
        assertEquals("Success", r.getResult());
    }

    @Test
    void timeoutEnforced() {
        Map<String, Object> m = new HashMap<>();
        m.put("command", "sleep 5");
        m.put("allowed_commands", List.of("sleep"));
        m.put("allowed_script_dirs", List.of());
        m.put("timeout", 1);
        ToolResponse r = tool().call(new ToolParameters(m));
        String out = (String) r.getResult();
        assertTrue(out.startsWith("Error: Command timed out"));
    }
}
