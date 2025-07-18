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
 * 资源获取事件
 * 
 * <p>ResourceAcquiredEvent表示资源已成功获取的事件。
 * 当ResourceManager成功创建或获取一个资源时，会发送此事件。
 * 
 * <p>此事件可以用于：
 * - 监控资源获取情况
 * - 触发依赖此资源的后续操作
 * - 记录资源使用统计
 * 
 * <p>示例用法：
 * <pre>{@code
 * @Action(listenEvents = {ResourceAcquiredEvent.class})
 * public void handleResourceAcquired(ResourceAcquiredEvent event, RunnerContext context) {
 *     // 处理资源获取事件
 *     String resourceId = event.getResourceId();
 *     ResourceType resourceType = event.getResourceType();
 *     // 执行相关逻辑...
 * }
 * }</pre>
 */
public class ResourceAcquiredEvent extends ResourceEvent {
    
    /**
     * 资源状态
     */
    private final ResourceStatus status;
    
    /**
     * 获取资源所花费的时间（毫秒）
     */
    private final long acquisitionTimeMs;
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     */
    public ResourceAcquiredEvent(String resourceId, ResourceType resourceType) {
        this(resourceId, resourceType, ResourceStatus.READY, 0);
    }
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     * @param status 资源状态
     * @param acquisitionTimeMs 获取时间
     */
    public ResourceAcquiredEvent(String resourceId, ResourceType resourceType, ResourceStatus status, long acquisitionTimeMs) {
        super(resourceId, resourceType);
        this.status = status;
        this.acquisitionTimeMs = acquisitionTimeMs;
    }
    
    /**
     * 获取资源状态
     * 
     * @return 资源状态
     */
    public ResourceStatus getStatus() {
        return status;
    }
    
    /**
     * 获取资源获取时间
     * 
     * @return 获取时间（毫秒）
     */
    public long getAcquisitionTimeMs() {
        return acquisitionTimeMs;
    }
    
    @Override
    public String getEventType() {
        return "ResourceAcquired";
    }
    
    @Override
    public String toString() {
        return "ResourceAcquiredEvent{" +
                "resourceId='" + getResourceId() + '\'' +
                ", resourceType=" + getResourceType() +
                ", status=" + status +
                ", acquisitionTimeMs=" + acquisitionTimeMs +
                ", timestamp=" + getTimestamp() +
                '}';
    }
} 