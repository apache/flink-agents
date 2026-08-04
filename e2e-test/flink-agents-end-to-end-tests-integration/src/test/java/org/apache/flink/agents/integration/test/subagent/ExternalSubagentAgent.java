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

package org.apache.flink.agents.integration.test.subagent;

import org.apache.flink.agents.api.Event;
import org.apache.flink.agents.api.InputEvent;
import org.apache.flink.agents.api.OutputEvent;
import org.apache.flink.agents.api.agents.Agent;
import org.apache.flink.agents.api.context.RunnerContext;
import org.apache.flink.agents.api.resource.ResourceType;
import org.apache.flink.agents.api.subagent.Result;
import org.apache.flink.agents.api.subagent.SubagentSetup;
import org.apache.flink.api.java.functions.KeySelector;

import java.util.List;

/** Agent whose input action delegates to the {@code ext-agent} external sub-agent. */
public class ExternalSubagentAgent extends Agent {

    public static final String SUBAGENT_NAME = "ext-agent";

    public ExternalSubagentAgent(SubagentSetup setup) throws Exception {
        addResource(SUBAGENT_NAME, ResourceType.AGENT, setup);
        addAction(
                new String[] {InputEvent.EVENT_TYPE},
                ExternalSubagentAgent.class.getMethod(
                        "callExternal", Event.class, RunnerContext.class));
    }

    /** Calls the sub-agent with an explicit session id and emits its result (or failure). */
    public static void callExternal(Event event, RunnerContext ctx) throws Exception {
        SubagentSetup setup = (SubagentSetup) ctx.getResource(SUBAGENT_NAME, ResourceType.AGENT);
        Object prompt = InputEvent.fromEvent(event).getInput();
        Result result = setup.call(ctx, prompt, "session-" + prompt);
        if (result.isSuccess()) {
            ctx.sendEvent(new OutputEvent(((List<?>) result.getResult()).get(0)));
        } else {
            ctx.sendEvent(new OutputEvent("error:" + result.getErrorMessage()));
        }
    }

    /** Keys every element by itself. */
    public static class LongKeySelector implements KeySelector<Long, Long> {
        @Override
        public Long getKey(Long value) {
            return value;
        }
    }
}
