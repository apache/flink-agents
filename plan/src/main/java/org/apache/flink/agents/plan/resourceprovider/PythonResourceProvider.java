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

/**
 * Python Resource provider that carries resource metadata to create Resource objects at runtime.
 *
 * <p>This provider is used for creating Python-based resources by carrying the necessary module,
 * class, and initialization arguments.
 */
public class PythonResourceProvider extends ResourceProvider {

    public PythonResourceProvider(String name, ResourceType type) {
        super(name, type);
    }

    @Override
    public Resource provide(Callable<Resource> getResource) throws Exception {
        // TODO: Implement Python resource creation logic
        // This would typically involve calling into Python runtime to create the
        // resource
        throw new UnsupportedOperationException(
                "Python resource creation not yet implemented in Java runtime");
    }
}
