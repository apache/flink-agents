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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资源指标类
 * 
 * <p>ResourceMetrics用于收集和提供资源使用的各种指标，包括活跃资源数量、获取时间、使用报告等。
 * 这些指标可以用于监控、性能分析和资源优化。
 * 
 * <p>示例用法：
 * <pre>{@code
 * ResourceMetrics metrics = resourceManager.getResourceMetrics();
 * int activeCount = metrics.getActiveResourceCount(ResourceType.DATABASE_CONNECTION);
 * Duration avgTime = metrics.getAverageAcquisitionTime(ResourceType.DATABASE_CONNECTION);
 * List<ResourceUsageReport> reports = metrics.getUsageReports();
 * }</pre>
 */
public class ResourceMetrics {
    
    /**
     * 活跃资源数量
     */
    private final int activeResourceCount;
    
    /**
     * 注册的提供者数量
     */
    private final int providerCount;
    
    /**
     * 按类型统计的活跃资源数量
     */
    private final Map<ResourceType, AtomicLong> activeResourceCountByType;
    
    /**
     * 按类型统计的获取时间
     */
    private final Map<ResourceType, AtomicLong> totalAcquisitionTimeByType;
    
    /**
     * 按类型统计的获取次数
     */
    private final Map<ResourceType, AtomicLong> acquisitionCountByType;
    
    /**
     * 资源使用报告
     */
    private final List<ResourceUsageReport> usageReports;
    
    /**
     * 构造函数
     * 
     * @param activeResourceCount 活跃资源数量
     * @param providerCount 提供者数量
     */
    public ResourceMetrics(int activeResourceCount, int providerCount) {
        this.activeResourceCount = activeResourceCount;
        this.providerCount = providerCount;
        this.activeResourceCountByType = new ConcurrentHashMap<>();
        this.totalAcquisitionTimeByType = new ConcurrentHashMap<>();
        this.acquisitionCountByType = new ConcurrentHashMap<>();
        this.usageReports = new java.util.ArrayList<>();
    }
    
    /**
     * 获取活跃资源数量
     * 
     * @return 活跃资源数量
     */
    public int getActiveResourceCount() {
        return activeResourceCount;
    }
    
    /**
     * 获取提供者数量
     * 
     * @return 提供者数量
     */
    public int getProviderCount() {
        return providerCount;
    }
    
    /**
     * 获取指定类型的活跃资源数量
     * 
     * @param type 资源类型
     * @return 活跃资源数量
     */
    public int getActiveResourceCount(ResourceType type) {
        AtomicLong count = activeResourceCountByType.get(type);
        return count != null ? count.intValue() : 0;
    }
    
    /**
     * 获取指定类型的平均获取时间
     * 
     * @param type 资源类型
     * @return 平均获取时间
     */
    public Duration getAverageAcquisitionTime(ResourceType type) {
        AtomicLong totalTime = totalAcquisitionTimeByType.get(type);
        AtomicLong count = acquisitionCountByType.get(type);
        
        if (totalTime == null || count == null || count.get() == 0) {
            return Duration.ZERO;
        }
        
        long averageTimeMs = totalTime.get() / count.get();
        return Duration.ofMillis(averageTimeMs);
    }
    
    /**
     * 获取资源使用报告
     * 
     * @return 使用报告列表
     */
    public List<ResourceUsageReport> getUsageReports() {
        return new java.util.ArrayList<>(usageReports);
    }
    
    /**
     * 记录资源获取
     * 
     * @param type 资源类型
     * @param acquisitionTimeMs 获取时间（毫秒）
     */
    public void recordResourceAcquisition(ResourceType type, long acquisitionTimeMs) {
        activeResourceCountByType.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();
        totalAcquisitionTimeByType.computeIfAbsent(type, k -> new AtomicLong(0)).addAndGet(acquisitionTimeMs);
        acquisitionCountByType.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * 记录资源释放
     * 
     * @param type 资源类型
     */
    public void recordResourceRelease(ResourceType type) {
        AtomicLong count = activeResourceCountByType.get(type);
        if (count != null) {
            count.decrementAndGet();
        }
    }
    
    /**
     * 添加使用报告
     * 
     * @param report 使用报告
     */
    public void addUsageReport(ResourceUsageReport report) {
        usageReports.add(report);
    }
    
    /**
     * 获取所有指标的快照
     * 
     * @return 指标快照
     */
    public Map<String, Object> getSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("activeResourceCount", activeResourceCount);
        snapshot.put("providerCount", providerCount);
        
        Map<String, Integer> activeCountByType = new HashMap<>();
        for (Map.Entry<ResourceType, AtomicLong> entry : activeResourceCountByType.entrySet()) {
            activeCountByType.put(entry.getKey().getValue(), entry.getValue().intValue());
        }
        snapshot.put("activeResourceCountByType", activeCountByType);
        
        Map<String, Duration> avgAcquisitionTimeByType = new HashMap<>();
        for (ResourceType type : ResourceType.values()) {
            avgAcquisitionTimeByType.put(type.getValue(), getAverageAcquisitionTime(type));
        }
        snapshot.put("averageAcquisitionTimeByType", avgAcquisitionTimeByType);
        
        return snapshot;
    }
    
    @Override
    public String toString() {
        return "ResourceMetrics{" +
                "activeResourceCount=" + activeResourceCount +
                ", providerCount=" + providerCount +
                ", activeResourceCountByType=" + activeResourceCountByType +
                '}';
    }
} 