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

package org.apache.flink.agents.plan.resourceprovider;

import org.apache.flink.agents.api.resource.Resource;
import org.apache.flink.agents.api.resource.ResourceType;

import java.util.concurrent.Callable;

/** Java Resource provider that carries resource instance to be used at runtime. */
public class JavaResourceProvider extends ResourceProvider {

    public JavaResourceProvider(String name, ResourceType type) {
        super(name, type);
    }

    @Override
    public Resource provide(Callable<Resource> getResource) throws Exception {
        // This provider is expected to be used with a Callable that returns a Resource instance.
        // The actual resource creation logic should be implemented in the Callable.
        return getResource.call();
    }
}
