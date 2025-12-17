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

package org.apache.flink.agents.api.vectorstores;

import java.util.Map;

/**
 * A document retrieved from vector store search.
 *
 * <p>Represents a single piece of content with associated metadata. This class is generic to
 * support different content types while maintaining consistent metadata and identification
 * structure.
 */
public class Document {

    /** Unique identifier of the document (if available). */
    private final String id;

    /** The actual content of the document. */
    private final String content;

    /** Document metadata such as source, author, timestamp, etc. */
    private final Map<String, Object> metadata;

    public Document(String content, Map<String, Object> metadata, String id) {
        this.content = content;
        this.metadata = metadata;
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getId() {
        return id;
    }
}
