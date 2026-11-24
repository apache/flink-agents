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
package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.context.Outcome;
import org.apache.flink.agents.runtime.actionstate.CallResult;

/**
 * A reserved, indexed call result. Preparing never launches work; completion never moves the
 * cursor.
 */
public final class DurableCallSlot<T> {
    private final RunnerContextImpl owner;
    private final String id;
    private final Class<T> type;
    private int index;
    private boolean recovering;

    public DurableCallSlot(RunnerContextImpl owner, String id, Class<T> type) {
        this.owner = owner;
        this.id = id;
        this.type = type;
    }

    public Outcome<T> prepare() throws Exception {
        index = owner.getCurrentCallIndex();
        CallResult saved = owner.getCallResultAt(index);
        if (saved != null && !saved.matches(id)) {
            owner.clearCallResultsFromAndPersist(index);
            saved = null;
        }
        Outcome<T> result = null;
        if (saved == null) {
            owner.appendPendingCall(id);
        } else if (saved.isPending()) {
            recovering = true;
        } else {
            result = owner.readTerminalOutcomeAt(index, id, type);
        }
        owner.advanceCallIndexBy(1);
        return result;
    }

    public boolean isRecovering() {
        return recovering;
    }

    public void finish(Outcome<T> result) throws Exception {
        if (result.isFailure()) {
            AsyncFutureImpl.rethrowFatal(result.getError());
        }
        owner.finalizeCallAt(
                index,
                id,
                owner.serializeDurableResult(result.getValue()),
                owner.serializeDurableException(result.getError()));
    }
}
