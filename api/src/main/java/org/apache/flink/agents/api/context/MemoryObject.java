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

import java.util.List;
import java.util.Map;

/**
 * A representation of an object in the short-term memory. It is responsible for accessing and
 * manipulating (direct or indirect) fields within the memory structure. A direct field is a field
 * which stores primitive data directly, while an indirect filed is a field which represents a
 * nested object.Fields can be accessed using an absolute or relative path.
 */
public interface MemoryObject {
    /**
     * Returns a MemoryObject that represents the given path.
     *
     * @param path relative path from the current object to the target field
     * @return a MemoryObject instance pointing to the field. If the field is a primitive value
     *     type, the value of the returned MemoryObject can be exposed via {@link #getValue()}.If
     *     the field is a nested object, the subfields of the returned MemoryObject can be exposed
     *     via {@link #getFields()}.
     * @throws Exception if the field does not exist
     */
    MemoryObject get(String path) throws Exception;

    /**
     * Returns a MemoryObject that represents the path of the given reference.
     *
     * @param ref a reference to a data item in the short-term memory
     * @return a MemoryObject instance pointing to the referenced field. Returns null if the path of
     *     the reference does not exist.
     * @throws Exception if an error occurs during state access
     */
    MemoryObject get(MemoryRef ref) throws Exception;

    /**
     * Sets the value of a direct field in the current object; any missing intermediate objects will
     * be created automatically.
     *
     * @param path relative path from the current object to the target field
     * @param value new value of the field
     * @return a {@link MemoryRef} instance pointing to the data just set
     * @throws Exception if trying to overwrite a nested object with a primitive value or set a
     *     MemoryObject directly
     */
    MemoryRef set(String path, Object value) throws Exception;

    /**
     * Creates a new MemoryObject as an indirect field in the current object.
     *
     * @param path relative path from the current object to the target field
     * @param overwrite whether to overwrite existing field if it's not a nested object
     * @return the created object
     * @throws Exception if field exists but is not a nested object and overwrite is false
     */
    MemoryObject newObject(String path, boolean overwrite) throws Exception;

    /**
     * Checks whether a (direct or indirect) field exists in the current object.
     *
     * @param path relative path from the current object to the target field
     * @return true if the field exists, false otherwise
     */
    boolean isExist(String path);

    /**
     * Gets names of all the top-level fields of the current object.
     *
     * @return list of top-level field names
     * @throws Exception state-backend failure
     */
    List<String> getFieldNames() throws Exception;

    /**
     * Gets all the top-level fields of the current object.
     *
     * @return map of top-level fields
     * @throws Exception state-backend failure
     */
    Map<String, Object> getFields() throws Exception;

    /**
     * Gets the primitive value stored at the current path.
     *
     * @return the primitive value if this MemoryObject stores primitive value directly, or null if
     *     it represents a nested object
     * @throws Exception state-backend failure
     */
    Object getValue() throws Exception;

    /**
     * Checks whether the current object is a nested object.
     *
     * @return true if this MemoryObject is a nested object, false if it stores primitive value
     *     directly
     * @throws Exception state-backend failure
     */
    boolean isNestedObject() throws Exception;

    /**
     * Gets all the updates made to this MemoryObject since it was created or the last time this
     * method was called.
     *
     * @return list of memory updates
     */
    List<MemoryUpdate> getAllUpdates();
}
