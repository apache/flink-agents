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

import org.apache.flink.agents.api.ResourceType;

import java.time.Duration;
import java.time.Instant;

/**
 * 资源使用报告
 * 
 * <p>ResourceUsageReport用于记录单个资源的使用情况，包括使用时间、状态变化、错误信息等。
 * 这些报告可以用于性能分析、问题诊断和资源优化。
 * 
 * <p>示例用法：
 * <pre>{@code
 * ResourceUsageReport report = new ResourceUsageReport("db-connection-1", ResourceType.DATABASE_CONNECTION);
 * report.setAcquisitionTime(Instant.now());
 * report.setReleaseTime(Instant.now().plusSeconds(30));
 * report.setUsageDuration(Duration.ofSeconds(30));
 * report.setStatus("success");
 * }</pre>
 */
public class ResourceUsageReport {
    
    /**
     * 资源ID
     */
    private final String resourceId;
    
    /**
     * 资源类型
     */
    private final ResourceType resourceType;
    
    /**
     * 获取时间
     */
    private Instant acquisitionTime;
    
    /**
     * 释放时间
     */
    private Instant releaseTime;
    
    /**
     * 使用时长
     */
    private Duration usageDuration;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 错误消息
     */
    private String errorMessage;
    
    /**
     * 获取时间（毫秒）
     */
    private long acquisitionTimeMs;
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     */
    public ResourceUsageReport(String resourceId, ResourceType resourceType) {
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.status = "unknown";
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
     * 获取获取时间
     * 
     * @return 获取时间
     */
    public Instant getAcquisitionTime() {
        return acquisitionTime;
    }
    
    /**
     * 设置获取时间
     * 
     * @param acquisitionTime 获取时间
     */
    public void setAcquisitionTime(Instant acquisitionTime) {
        this.acquisitionTime = acquisitionTime;
    }
    
    /**
     * 获取释放时间
     * 
     * @return 释放时间
     */
    public Instant getReleaseTime() {
        return releaseTime;
    }
    
    /**
     * 设置释放时间
     * 
     * @param releaseTime 释放时间
     */
    public void setReleaseTime(Instant releaseTime) {
        this.releaseTime = releaseTime;
        if (this.acquisitionTime != null) {
            this.usageDuration = Duration.between(this.acquisitionTime, releaseTime);
        }
    }
    
    /**
     * 获取使用时长
     * 
     * @return 使用时长
     */
    public Duration getUsageDuration() {
        return usageDuration;
    }
    
    /**
     * 设置使用时长
     * 
     * @param usageDuration 使用时长
     */
    public void setUsageDuration(Duration usageDuration) {
        this.usageDuration = usageDuration;
    }
    
    /**
     * 获取状态
     * 
     * @return 状态
     */
    public String getStatus() {
        return status;
    }
    
    /**
     * 设置状态
     * 
     * @param status 状态
     */
    public void setStatus(String status) {
        this.status = status;
    }
    
    /**
     * 获取错误消息
     * 
     * @return 错误消息
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * 设置错误消息
     * 
     * @param errorMessage 错误消息
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    /**
     * 获取获取时间（毫秒）
     * 
     * @return 获取时间（毫秒）
     */
    public long getAcquisitionTimeMs() {
        return acquisitionTimeMs;
    }
    
    /**
     * 设置获取时间（毫秒）
     * 
     * @param acquisitionTimeMs 获取时间（毫秒）
     */
    public void setAcquisitionTimeMs(long acquisitionTimeMs) {
        this.acquisitionTimeMs = acquisitionTimeMs;
    }
    
    /**
     * 检查是否成功
     * 
     * @return 如果成功返回true，否则返回false
     */
    public boolean isSuccess() {
        return "success".equals(status);
    }
    
    /**
     * 检查是否有错误
     * 
     * @return 如果有错误返回true，否则返回false
     */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }
    
    @Override
    public String toString() {
        return "ResourceUsageReport{" +
                "resourceId='" + resourceId + '\'' +
                ", resourceType=" + resourceType +
                ", acquisitionTime=" + acquisitionTime +
                ", releaseTime=" + releaseTime +
                ", usageDuration=" + usageDuration +
                ", status='" + status + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", acquisitionTimeMs=" + acquisitionTimeMs +
                '}';
    }
} 