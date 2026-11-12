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

/** The polling budget for wait elapsed before a terminal status was observed. */
public final class MediaJobTimeoutException extends MediaJobException {
    private final double timeout;

    public MediaJobTimeoutException(
            MediaJob job, MediaJobStatus status, double timeout, String message) {
        super(job, status, null, message);
        this.timeout = timeout;
    }

    public double getTimeout() {
        return timeout;
    }
}
