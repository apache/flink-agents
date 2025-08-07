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
 * 资源错误事件
 * 
 * <p>ResourceErrorEvent表示资源操作过程中发生错误的事件。
 * 当资源创建、初始化、使用或释放过程中发生异常时，会发送此事件。
 * 
 * <p>此事件可以用于：
 * - 错误监控和告警
 * - 错误恢复和重试
 * - 错误统计和分析
 * 
 * <p>示例用法：
 * <pre>{@code
 * @Action(listenEvents = {ResourceErrorEvent.class})
 * public void handleResourceError(ResourceErrorEvent event, RunnerContext context) {
 *     // 处理资源错误事件
 *     String resourceId = event.getResourceId();
 *     String errorMessage = event.getErrorMessage();
 *     String operation = event.getOperation();
 *     // 执行错误处理逻辑...
 * }
 * }</pre>
 */
public class ResourceErrorEvent extends ResourceEvent {
    
    /**
     * 错误消息
     */
    private final String errorMessage;
    
    /**
     * 发生错误的操作
     */
    private final String operation;
    
    /**
     * 错误代码
     */
    private final String errorCode;
    
    /**
     * 是否可重试
     */
    private final boolean retryable;
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     * @param errorMessage 错误消息
     * @param operation 操作类型
     */
    public ResourceErrorEvent(String resourceId, ResourceType resourceType, String errorMessage, String operation) {
        this(resourceId, resourceType, errorMessage, operation, "UNKNOWN", false);
    }
    
    /**
     * 构造函数
     * 
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     * @param errorMessage 错误消息
     * @param operation 操作类型
     * @param errorCode 错误代码
     * @param retryable 是否可重试
     */
    public ResourceErrorEvent(String resourceId, ResourceType resourceType, String errorMessage, String operation, String errorCode, boolean retryable) {
        super(resourceId, resourceType);
        this.errorMessage = errorMessage;
        this.operation = operation;
        this.errorCode = errorCode;
        this.retryable = retryable;
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
     * 获取发生错误的操作
     * 
     * @return 操作类型
     */
    public String getOperation() {
        return operation;
    }
    
    /**
     * 获取错误代码
     * 
     * @return 错误代码
     */
    public String getErrorCode() {
        return errorCode;
    }
    
    /**
     * 检查错误是否可重试
     * 
     * @return 如果可重试返回true，否则返回false
     */
    public boolean isRetryable() {
        return retryable;
    }
    
    @Override
    public String getEventType() {
        return "ResourceError";
    }
    
    @Override
    public String toString() {
        return "ResourceErrorEvent{" +
                "resourceId='" + getResourceId() + '\'' +
                ", resourceType=" + getResourceType() +
                ", errorMessage='" + errorMessage + '\'' +
                ", operation='" + operation + '\'' +
                ", errorCode='" + errorCode + '\'' +
                ", retryable=" + retryable +
                ", timestamp=" + getTimestamp() +
                '}';
    }
} 