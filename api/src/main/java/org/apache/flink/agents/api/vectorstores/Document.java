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

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

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

    private @Nullable float[] embedding;

    /**
     * Similarity / distance score for this document against a query. Populated by vector search
     * results; {@code null} for documents returned from non-query operations (e.g. {@code get}) or
     * from stores that do not surface scores. Semantics (distance vs. similarity, metric) are
     * implementation-specific — each vector store documents its own.
     */
    private @Nullable Float score;

    public Document(String content, Map<String, Object> metadata, String id) {
        this(content, metadata, id, null, null);
    }

    public Document(
            String content, Map<String, Object> metadata, String id, @Nullable float[] embedding) {
        this(content, metadata, id, embedding, null);
    }

    public Document(
            String content,
            Map<String, Object> metadata,
            String id,
            @Nullable float[] embedding,
            @Nullable Float score) {
        this.content = content;
        this.metadata = metadata;
        this.id = id;
        this.embedding = embedding;
        this.score = score;
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

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    @Nullable
    public float[] getEmbedding() {
        return embedding;
    }

    @Nullable
    public Float getScore() {
        return score;
    }

    public void setScore(@Nullable Float score) {
        this.score = score;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Document document = (Document) o;
        return Objects.equals(id, document.id)
                && Objects.equals(content, document.content)
                && Objects.equals(metadata, document.metadata)
                && Arrays.equals(embedding, document.embedding)
                && Objects.equals(score, document.score);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, content, metadata, Arrays.hashCode(embedding), score);
    }

    @Override
    public String toString() {
        return "Document{"
                + "id='"
                + id
                + '\''
                + ", content='"
                + content
                + '\''
                + ", metadata="
                + metadata
                + ", embedding="
                + Arrays.toString(embedding)
                + ", score="
                + score
                + '}';
    }
}
