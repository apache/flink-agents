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
package org.apache.flink.agents.runtime.utils;

import org.apache.flink.api.common.state.ListState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Some utilities related to Flink state. */
public class StateUtil {

    /**
     * Checks whether the provided ListState contains any elements.
     *
     * @param listState the ListState instance to check
     * @return true if the state is not empty; false otherwise
     * @throws Exception if an I/O error occurs while reading state
     */
    public static boolean listStateNotEmpty(ListState<?> listState) throws Exception {
        return listState.get() != null && listState.get().iterator().hasNext();
    }

    /**
     * Removes all occurrences of the specified element from the given ListState.
     *
     * @param listState the ListState instance to modify
     * @param element the element to remove
     * @return the number of elements removed
     * @throws Exception if an I/O error occurs while reading/writing state
     */
    public static <T> int removeFromListState(ListState<T> listState, T element) throws Exception {
        Iterator<T> listStateIterator = listState.get().iterator();
        if (!listStateIterator.hasNext()) {
            return 0;
        }

        int removedElementCount = 0;
        List<T> remaining = new ArrayList<>();
        while (listStateIterator.hasNext()) {
            T next = listStateIterator.next();
            if (next.equals(element)) {
                removedElementCount++;
                continue;
            }
            remaining.add(next);
        }
        listState.clear();
        listState.update(remaining);
        return removedElementCount;
    }

    /**
     * Removes and returns the first element from the ListState.
     *
     * @param listState the ListState instance to poll from
     * @return the first element of the list, or null if the list is empty
     * @throws Exception if an I/O error occurs while reading/writing state
     */
    public static <T> T pollFromListState(ListState<T> listState) throws Exception {
        Iterator<T> listStateIterator = listState.get().iterator();
        if (!listStateIterator.hasNext()) {
            return null;
        }

        T polled = listStateIterator.next();
        List<T> remaining = new ArrayList<>();
        while (listStateIterator.hasNext()) {
            remaining.add(listStateIterator.next());
        }
        listState.clear();
        listState.update(remaining);
        return polled;
    }
}
