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

package org.apache.flink.agents.runtime.resource;

import org.apache.flink.agents.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资源管理器
 * 
 * <p>ResourceManager负责管理所有资源的生命周期，包括资源的创建、获取、释放和监控。
 * 它是整个资源管理系统的核心组件。
 */
public class ResourceManager implements AutoCloseable {
    
    private static final Logger LOG = LoggerFactory.getLogger(ResourceManager.class);
    
    /**
     * 资源提供者映射，按资源类型索引
     */
    private final Map<ResourceType, ResourceProvider<?>> providers;
    
    /**
     * 活跃资源映射，按资源ID索引
     */
    private final Map<String, Resource> activeResources;
    
    /**
     * 全局资源配置
     */
    private final ResourceConfig globalConfig;
    
    /**
     * 资源计数器
     */
    private final AtomicLong resourceCounter;
    
    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;
    
    /**
     * 默认构造函数
     */
    public ResourceManager() {
        this(new ResourceConfig());
    }
    
    /**
     * 构造函数
     * 
     * @param globalConfig 全局资源配置
     */
    public ResourceManager(ResourceConfig globalConfig) {
        this.providers = new ConcurrentHashMap<>();
        this.activeResources = new ConcurrentHashMap<>();
        this.globalConfig = globalConfig;
        this.resourceCounter = new AtomicLong(0);
    }
    
    /**
     * 注册资源提供者
     * 
     * @param provider 资源提供者
     * @throws ResourceException 如果注册失败
     */
    public void registerProvider(ResourceProvider<?> provider) throws ResourceException {
        if (closed) {
            throw new ResourceException("ResourceManager is closed");
        }
        
        if (provider == null) {
            throw new ResourceException("Provider cannot be null");
        }
        
        ResourceType type = provider.getSupportedType();
        if (type == null) {
            throw new ResourceException("Provider must support a valid resource type");
        }
        
        providers.put(type, provider);
        LOG.info("Registered resource provider for type: {}", type);
    }
    
    /**
     * 获取资源
     * 
     * @param type 资源类型
     * @param config 资源配置
     * @return 资源实例
     * @throws ResourceException 如果获取资源失败
     */
    @SuppressWarnings("unchecked")
    public <T extends Resource> T acquireResource(ResourceType type, ResourceConfig config) throws ResourceException {
        if (closed) {
            throw new ResourceException("ResourceManager is closed");
        }
        
        if (type == null) {
            throw new ResourceException("Resource type cannot be null");
        }
        
        if (config == null) {
            config = new ResourceConfig(type);
        }
        
        // 获取对应的资源提供者
        ResourceProvider<?> provider = providers.get(type);
        if (provider == null) {
            throw new ResourceException("No provider registered for resource type: " + type);
        }
        
        // 检查提供者是否健康
        if (!provider.isHealthy()) {
            throw new ResourceException("Provider is not healthy for resource type: " + type);
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 创建资源
            Resource resource = provider.createResource(config);
            
            // 初始化资源
            resource.initialize();
            
            // 检查资源是否准备就绪
            if (!resource.isReady()) {
                throw new ResourceException("Resource is not ready after initialization");
            }
            
            // 生成资源ID（如果未设置）
            String resourceId = resource.getId();
            if (resourceId == null || resourceId.isEmpty()) {
                resourceId = generateResourceId(type);
            }
            
            // 注册活跃资源
            activeResources.put(resourceId, resource);
            
            long acquisitionTime = System.currentTimeMillis() - startTime;
            
            LOG.info("Successfully acquired resource: {} (type: {}) in {}ms", resourceId, type, acquisitionTime);
            
            return (T) resource;
            
        } catch (Exception e) {
            long acquisitionTime = System.currentTimeMillis() - startTime;
            String errorMsg = String.format("Failed to acquire resource (type: %s) after %dms", type, acquisitionTime);
            LOG.error(errorMsg, e);
            throw new ResourceException(errorMsg, e);
        }
    }
    
    /**
     * 释放资源
     * 
     * @param resourceId 资源ID
     * @throws ResourceException 如果释放资源失败
     */
    public void releaseResource(String resourceId) throws ResourceException {
        if (closed) {
            throw new ResourceException("ResourceManager is closed");
        }
        
        if (resourceId == null || resourceId.isEmpty()) {
            throw new ResourceException("Resource ID cannot be null or empty");
        }
        
        Resource resource = activeResources.remove(resourceId);
        if (resource == null) {
            LOG.warn("Resource not found for release: {}", resourceId);
            return;
        }
        
        try {
            // 获取对应的提供者
            ResourceProvider<?> provider = providers.get(resource.getType());
            if (provider != null) {
                // 使用提供者释放资源
                provider.releaseResource(resource);
            } else {
                // 直接关闭资源
                resource.close();
            }
            
            LOG.info("Successfully released resource: {}", resourceId);
            
        } catch (Exception e) {
            String errorMsg = "Failed to release resource: " + resourceId;
            LOG.error(errorMsg, e);
            throw new ResourceException(errorMsg, e);
        }
    }
    
    /**
     * 获取资源
     * 
     * @param resourceId 资源ID
     * @return 资源实例，如果不存在则返回null
     */
    public Resource getResource(String resourceId) {
        return activeResources.get(resourceId);
    }
    
    /**
     * 获取资源指标
     * 
     * @return 资源指标
     */
    public ResourceMetrics getResourceMetrics() {
        return new ResourceMetrics(activeResources.size(), providers.size());
    }
    
    /**
     * 检查资源管理器是否健康
     * 
     * @return 如果健康返回true，否则返回false
     */
    public boolean isHealthy() {
        if (closed) {
            return false;
        }
        
        // 检查所有提供者是否健康
        for (ResourceProvider<?> provider : providers.values()) {
            if (!provider.isHealthy()) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 生成资源ID
     * 
     * @param type 资源类型
     * @return 生成的资源ID
     */
    private String generateResourceId(ResourceType type) {
        return type.getValue() + "-" + resourceCounter.incrementAndGet();
    }
    
    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        
        closed = true;
        LOG.info("Closing ResourceManager...");
        
        // 释放所有活跃资源
        for (String resourceId : activeResources.keySet()) {
            try {
                releaseResource(resourceId);
            } catch (Exception e) {
                LOG.error("Error releasing resource during shutdown: {}", resourceId, e);
            }
        }
        
        activeResources.clear();
        providers.clear();
        
        LOG.info("ResourceManager closed successfully");
    }
} 