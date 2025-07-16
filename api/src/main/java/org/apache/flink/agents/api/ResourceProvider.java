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

package org.apache.flink.agents.api;

/**
 * 资源提供者接口
 * 
 * <p>ResourceProvider负责创建、配置和管理特定类型的资源，类似于现有的PythonEnvironmentManager。
 * 每个ResourceProvider只负责一种特定类型的资源。
 * 
 * <p>ResourceProvider使用泛型来确保类型安全，T必须是Resource的子类型。
 * 
 * <p>示例用法：
 * <pre>{@code
 * ResourceProvider<DatabaseResource> dbProvider = new DatabaseResourceProvider();
 * ResourceConfig config = new ResourceConfig();
 * config.setProperty("url", "jdbc:mysql://localhost:3306/test");
 * config.setProperty("username", "user");
 * config.setProperty("password", "pass");
 * 
 * DatabaseResource resource = dbProvider.createResource(config);
 * resource.initialize();
 * }</pre>
 * 
 * @param <T> 该提供者创建的Resource类型
 */
public interface ResourceProvider<T extends Resource> {
    
    /**
     * 获取提供者支持的资源类型
     * 
     * @return 支持的资源类型
     */
    ResourceType getSupportedType();
    
    /**
     * 创建资源
     * 
     * <p>根据提供的配置创建新的资源实例。创建的资源需要调用initialize()方法进行初始化。
     * 
     * @param config 资源配置信息
     * @return 创建的资源实例
     * @throws ResourceException 如果创建资源失败
     */
    T createResource(ResourceConfig config) throws ResourceException;
    
    /**
     * 释放资源
     * 
     * <p>释放指定的资源，确保资源被正确清理。
     * 
     * @param resource 要释放的资源
     * @throws ResourceException 如果释放资源失败
     */
    void releaseResource(T resource) throws ResourceException;
    
    /**
     * 检查提供者是否健康
     * 
     * <p>检查提供者是否能够正常创建和管理资源。
     * 
     * @return 如果提供者健康返回true，否则返回false
     */
    boolean isHealthy();
    
    /**
     * 检查是否支持指定的资源配置
     * 
     * <p>在尝试创建资源之前，可以调用此方法检查配置是否有效。
     * 
     * @param config 要检查的资源配置
     * @return 如果支持该配置返回true，否则返回false
     */
    default boolean supports(ResourceConfig config) {
        return config != null && getSupportedType().equals(config.getType());
    }
} 