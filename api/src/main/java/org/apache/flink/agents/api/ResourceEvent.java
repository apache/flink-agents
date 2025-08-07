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
 * 资源事件抽象基类
 * 
 * <p>ResourceEvent是基于现有Event系统的资源相关事件基类。
 * 所有与资源操作相关的事件都应该继承此类。
 * 
 * <p>ResourceEvent包含了资源的基本信息，如资源ID和资源类型，
 * 这些信息对于事件处理和资源管理非常重要。
 * 
 * <p>示例用法：
 * <pre>{@code
 * // 创建资源获取事件
 * ResourceAcquiredEvent event = new ResourceAcquiredEvent("db-connection-1", ResourceType.DATABASE_CONNECTION);
 * context.sendEvent(event);
 * }</pre>
 */
public abstract class ResourceEvent extends Event {
    
    /**
     * 资源ID
     */
    private final String resourceId;
    
    /**
     * 资源类型
     */
    private final ResourceType resourceType;
    
    /**
     * 事件时间戳
     */
    private final long timestamp;
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     */
    protected ResourceEvent(String resourceId, ResourceType resourceType) {
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * 获取资源ID
     * 
     * @return 资源ID
     */
    public String getResourceId() {
        return resourceId;
    }
    
    /**
     * 获取资源类型
     * 
     * @return 资源类型
     */
    public ResourceType getResourceType() {
        return resourceType;
    }
    
    /**
     * 获取事件时间戳
     * 
     * @return 事件时间戳（毫秒）
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 获取事件类型
     * 
     * @return 事件类型的字符串表示
     */
    public abstract String getEventType();
    
    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "resourceId='" + resourceId + '\'' +
                ", resourceType=" + resourceType +
                ", timestamp=" + timestamp +
                '}';
    }
} 