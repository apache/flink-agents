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

package org.apache.flink.agents.api.media;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The capability one media model instance serves. */
public enum MediaTask {
    IMAGE_GENERATION("image_generation"),
    VIDEO_GENERATION("video_generation"),
    TRANSCRIPTION("transcription"),
    SPEECH_SYNTHESIS("speech_synthesis");

    private final String value;

    MediaTask(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** Return the task for its wire value. */
    @JsonCreator
    public static MediaTask fromValue(String value) {
        for (MediaTask task : values()) {
            if (task.value.equals(value)) {
                return task;
            }
        }
        throw new IllegalArgumentException("Unknown media task: " + value);
    }
}
