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

package org.apache.flink.agents.runtime.context;

import org.apache.flink.agents.api.*;
import org.apache.flink.agents.api.context.ResourceContext;
import org.apache.flink.agents.runtime.resource.ResourceManager;
import org.apache.flink.agents.runtime.resource.ResourceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ResourceContext的实现类
 * 
 * <p>ResourceContextImpl提供了资源管理的具体实现，包括：
 * - 资源获取和释放
 * - 资源生命周期管理
 * - 资源绑定和解绑
 * - 资源事件发送
 * 
 * <p>这个实现类与现有的RunnerContextImpl兼容，可以无缝集成到现有系统中。
 */
public class ResourceContextImpl extends RunnerContextImpl implements ResourceContext {
    
    private static final Logger LOG = LoggerFactory.getLogger(ResourceContextImpl.class);
    
    /**
     * 资源管理器
     */
    private final ResourceManager resourceManager;
    
    /**
     * 绑定的资源映射
     */
    private final Map<String, Resource> boundResources;
    
    /**
     * 资源指标
     */
    private final ResourceMetrics resourceMetrics;
    
    /**
     * 构造函数
     * 
     * @param resourceManager 资源管理器
     */
    public ResourceContextImpl(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        this.boundResources = new ConcurrentHashMap<>();
        this.resourceMetrics = new ResourceMetrics(0, 0);
    }
    
    /**
     * 构造函数（使用默认资源管理器）
     */
    public ResourceContextImpl() {
        this(new ResourceManager());
    }
    
    @Override
    public Resource acquireResource(ResourceType type) throws ResourceException {
        return acquireResource(type, new ResourceConfig(type));
    }
    
    @Override
    public Resource acquireResource(ResourceType type, ResourceConfig config) throws ResourceException {
        if (type == null) {
            throw new ResourceException("Resource type cannot be null");
        }
        
        if (config == null) {
            config = new ResourceConfig(type);
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 通过资源管理器获取资源
            Resource resource = resourceManager.acquireResource(type, config);
            
            // 绑定资源到上下文
            bindResource(resource);
            
            // 记录获取时间
            long acquisitionTime = System.currentTimeMillis() - startTime;
            resourceMetrics.recordResourceAcquisition(type, acquisitionTime);
            
            // 发送资源获取事件
            sendEvent(new ResourceAcquiredEvent(resource.getId(), type, resource.getStatus(), acquisitionTime));
            
            LOG.info("Acquired resource: {} (type: {}) in {}ms", resource.getId(), type, acquisitionTime);
            
            return resource;
            
        } catch (Exception e) {
            long acquisitionTime = System.currentTimeMillis() - startTime;
            String errorMsg = String.format("Failed to acquire resource (type: %s) after %dms", type, acquisitionTime);
            LOG.error(errorMsg, e);
            
            // 发送资源错误事件
            sendEvent(new ResourceErrorEvent("unknown", type, errorMsg, "acquire", "ACQUISITION_FAILED", false));
            
            throw new ResourceException(errorMsg, e);
        }
    }
    
    @Override
    public void releaseResource(String resourceId) throws ResourceException {
        if (resourceId == null || resourceId.isEmpty()) {
            throw new ResourceException("Resource ID cannot be null or empty");
        }
        
        Resource resource = boundResources.get(resourceId);
        if (resource == null) {
            LOG.warn("Resource not found for release: {}", resourceId);
            return;
        }
        
        long usageStartTime = System.currentTimeMillis();
        
        try {
            // 解绑资源
            unbindResource(resourceId);
            
            // 通过资源管理器释放资源
            resourceManager.releaseResource(resourceId);
            
            // 记录释放
            resourceMetrics.recordResourceRelease(resource.getType());
            
            // 计算使用时长
            long usageDuration = System.currentTimeMillis() - usageStartTime;
            
            // 发送资源释放事件
            sendEvent(new ResourceReleasedEvent(resourceId, resource.getType(), usageDuration, "normal"));
            
            LOG.info("Released resource: {} (type: {})", resourceId, resource.getType());
            
        } catch (Exception e) {
            String errorMsg = "Failed to release resource: " + resourceId;
            LOG.error(errorMsg, e);
            
            // 发送资源错误事件
            sendEvent(new ResourceErrorEvent(resourceId, resource.getType(), errorMsg, "release", "RELEASE_FAILED", false));
            
            throw new ResourceException(errorMsg, e);
        }
    }
    
    @Override
    public Resource getResource(String resourceId) {
        return boundResources.get(resourceId);
    }
    
    @Override
    public boolean hasResource(String resourceId) {
        return boundResources.containsKey(resourceId);
    }
    
    @Override
    public ResourceManager getResourceManager() {
        return resourceManager;
    }
    
    @Override
    public ResourceMetrics getResourceMetrics() {
        return resourceMetrics;
    }
    
    @Override
    public void bindResource(Resource resource) throws ResourceException {
        if (resource == null) {
            throw new ResourceException("Resource cannot be null");
        }
        
        String resourceId = resource.getId();
        if (resourceId == null || resourceId.isEmpty()) {
            throw new ResourceException("Resource ID cannot be null or empty");
        }
        
        if (boundResources.containsKey(resourceId)) {
            throw new ResourceException("Resource already bound: " + resourceId);
        }
        
        boundResources.put(resourceId, resource);
        LOG.debug("Bound resource: {} (type: {})", resourceId, resource.getType());
    }
    
    @Override
    public void unbindResource(String resourceId) throws ResourceException {
        if (resourceId == null || resourceId.isEmpty()) {
            throw new ResourceException("Resource ID cannot be null or empty");
        }
        
        Resource resource = boundResources.remove(resourceId);
        if (resource == null) {
            throw new ResourceException("Resource not bound: " + resourceId);
        }
        
        LOG.debug("Unbound resource: {} (type: {})", resourceId, resource.getType());
    }
    
    @Override
    public Map<String, Resource> getBoundResources() {
        return new HashMap<>(boundResources);
    }
    
    @Override
    public void cleanupResources() throws ResourceException {
        LOG.info("Cleaning up {} bound resources", boundResources.size());
        
        // 释放所有绑定的资源
        for (String resourceId : boundResources.keySet()) {
            try {
                releaseResource(resourceId);
            } catch (Exception e) {
                LOG.error("Error releasing resource during cleanup: {}", resourceId, e);
                // 继续清理其他资源
            }
        }
        
        boundResources.clear();
        LOG.info("Resource cleanup completed");
    }
    
    /**
     * 获取绑定的资源数量
     * 
     * @return 绑定的资源数量
     */
    public int getBoundResourceCount() {
        return boundResources.size();
    }
    
    /**
     * 检查是否有绑定的资源
     * 
     * @return 如果有绑定的资源返回true，否则返回false
     */
    public boolean hasBoundResources() {
        return !boundResources.isEmpty();
    }
    
    @Override
    public void close() throws Exception {
        try {
            cleanupResources();
        } finally {
            if (resourceManager != null) {
                resourceManager.close();
            }
        }
    }
} 