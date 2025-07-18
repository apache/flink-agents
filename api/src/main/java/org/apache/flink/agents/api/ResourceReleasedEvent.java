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
 * 资源释放事件
 * 
 * <p>ResourceReleasedEvent表示资源已成功释放的事件。
 * 当ResourceManager成功释放一个资源时，会发送此事件。
 * 
 * <p>此事件可以用于：
 * - 监控资源释放情况
 * - 清理相关状态
 * - 记录资源使用统计
 * 
 * <p>示例用法：
 * <pre>{@code
 * @Action(listenEvents = {ResourceReleasedEvent.class})
 * public void handleResourceReleased(ResourceReleasedEvent event, RunnerContext context) {
 *     // 处理资源释放事件
 *     String resourceId = event.getResourceId();
 *     ResourceType resourceType = event.getResourceType();
 *     // 执行清理逻辑...
 * }
 * }</pre>
 */
public class ResourceReleasedEvent extends ResourceEvent {
    
    /**
     * 资源使用时长（毫秒）
     */
    private final long usageDurationMs;
    
    /**
     * 释放原因
     */
    private final String releaseReason;
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     */
    public ResourceReleasedEvent(String resourceId, ResourceType resourceType) {
        this(resourceId, resourceType, 0, "normal");
    }
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     * @param usageDurationMs 使用时长
     * @param releaseReason 释放原因
     */
    public ResourceReleasedEvent(String resourceId, ResourceType resourceType, long usageDurationMs, String releaseReason) {
        super(resourceId, resourceType);
        this.usageDurationMs = usageDurationMs;
        this.releaseReason = releaseReason;
    }
    
    /**
     * 获取资源使用时长
     * 
     * @return 使用时长（毫秒）
     */
    public long getUsageDurationMs() {
        return usageDurationMs;
    }
    
    /**
     * 获取释放原因
     * 
     * @return 释放原因
     */
    public String getReleaseReason() {
        return releaseReason;
    }
    
    @Override
    public String getEventType() {
        return "ResourceReleased";
    }
    
    @Override
    public String toString() {
        return "ResourceReleasedEvent{" +
                "resourceId='" + getResourceId() + '\'' +
                ", resourceType=" + getResourceType() +
                ", usageDurationMs=" + usageDurationMs +
                ", releaseReason='" + releaseReason + '\'' +
                ", timestamp=" + getTimestamp() +
                '}';
    }
} 