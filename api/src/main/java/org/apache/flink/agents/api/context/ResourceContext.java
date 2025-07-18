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

import org.apache.flink.agents.api.*;

/**
 * 资源上下文接口
 * 
 * <p>ResourceContext扩展了RunnerContext，提供了资源管理功能。
 * 它允许Action在执行过程中获取、使用和释放资源。
 * 
 * <p>ResourceContext提供了以下功能：
 * - 资源获取和释放
 * - 资源生命周期管理
 * - 资源依赖注入
 * - 资源事件发送
 * 
 * <p>示例用法：
 * <pre>{@code
 * @Action(listenEvents = {InputEvent.class})
 * public void processData(InputEvent event, ResourceContext context) {
 *     // 获取数据库连接资源
 *     Resource dbResource = context.acquireResource(ResourceType.DATABASE_CONNECTION);
 *     try {
 *         // 使用数据库连接
 *         Connection conn = (Connection) dbResource.getContent();
 *         // 执行数据库操作...
 *     } finally {
 *         // 释放资源
 *         context.releaseResource(dbResource.getId());
 *     }
 * }
 * }</pre>
 */
public interface ResourceContext extends RunnerContext {
    
    /**
     * 获取资源
     * 
     * @param type 资源类型
     * @return 资源实例
     * @throws ResourceException 如果获取资源失败
     */
    Resource acquireResource(ResourceType type) throws ResourceException;
    
    /**
     * 获取资源（带配置）
     * 
     * @param type 资源类型
     * @param config 资源配置
     * @return 资源实例
     * @throws ResourceException 如果获取资源失败
     */
    Resource acquireResource(ResourceType type, ResourceConfig config) throws ResourceException;
    
    /**
     * 释放资源
     * 
     * @param resourceId 资源ID
     * @throws ResourceException 如果释放资源失败
     */
    void releaseResource(String resourceId) throws ResourceException;
    
    /**
     * 获取资源
     * 
     * @param resourceId 资源ID
     * @return 资源实例，如果不存在则返回null
     */
    Resource getResource(String resourceId);
    
    /**
     * 检查资源是否存在
     * 
     * @param resourceId 资源ID
     * @return 如果存在返回true，否则返回false
     */
    boolean hasResource(String resourceId);
    
    /**
     * 获取资源管理器
     * 
     * @return 资源管理器实例
     */
    ResourceManager getResourceManager();
    
    /**
     * 获取资源指标
     * 
     * @return 资源指标
     */
    ResourceMetrics getResourceMetrics();
    
    /**
     * 绑定资源到上下文
     * 
     * @param resource 资源实例
     * @throws ResourceException 如果绑定失败
     */
    void bindResource(Resource resource) throws ResourceException;
    
    /**
     * 解绑资源
     * 
     * @param resourceId 资源ID
     * @throws ResourceException 如果解绑失败
     */
    void unbindResource(String resourceId) throws ResourceException;
    
    /**
     * 获取所有绑定的资源
     * 
     * @return 资源映射
     */
    java.util.Map<String, Resource> getBoundResources();
    
    /**
     * 清理所有资源
     * 
     * @throws ResourceException 如果清理失败
     */
    void cleanupResources() throws ResourceException;
} 