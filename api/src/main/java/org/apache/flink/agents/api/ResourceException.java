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
 * 资源操作异常
 * 
 * <p>ResourceException用于表示在资源创建、初始化、使用或释放过程中发生的异常。
 * 这个异常类提供了详细的错误信息，帮助诊断和解决资源相关的问题。
 * 
 * <p>示例用法：
 * <pre>{@code
 * try {
 *     Resource resource = provider.createResource(config);
 *     resource.initialize();
 * } catch (ResourceException e) {
 *     logger.error("Failed to create resource: " + e.getMessage(), e);
 *     // 处理异常
 * }
 * }</pre>
 */
public class ResourceException extends Exception {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 资源ID，用于标识发生异常的资源
     */
    private final String resourceId;
    
    /**
     * 资源类型，用于标识发生异常的资源类型
     */
    private final ResourceType resourceType;
    
    /**
     * 操作类型，用于标识发生异常的操作
     */
    private final String operation;
    
    /**
     * 构造一个ResourceException
     * 
     * @param message 异常消息
     */
    public ResourceException(String message) {
        super(message);
        this.resourceId = null;
        this.resourceType = null;
        this.operation = null;
    }
    
    /**
     * 构造一个ResourceException
     * 
     * @param message 异常消息
     * @param cause 原因异常
     */
    public ResourceException(String message, Throwable cause) {
        super(message, cause);
        this.resourceId = null;
        this.resourceType = null;
        this.operation = null;
    }
    
    /**
     * 构造一个ResourceException
     * 
     * @param message 异常消息
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     * @param operation 操作类型
     */
    public ResourceException(String message, String resourceId, ResourceType resourceType, String operation) {
        super(message);
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.operation = operation;
    }
    
    /**
     * 构造一个ResourceException
     * 
     * @param message 异常消息
     * @param cause 原因异常
     * @param resourceId 资源ID
     * @param resourceType 资源类型
     * @param operation 操作类型
     */
    public ResourceException(String message, Throwable cause, String resourceId, ResourceType resourceType, String operation) {
        super(message, cause);
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.operation = operation;
    }
    
    /**
     * 获取资源ID
     * 
     * @return 资源ID，如果未设置则返回null
     */
    public String getResourceId() {
        return resourceId;
    }
    
    /**
     * 获取资源类型
     * 
     * @return 资源类型，如果未设置则返回null
     */
    public ResourceType getResourceType() {
        return resourceType;
    }
    
    /**
     * 获取操作类型
     * 
     * @return 操作类型，如果未设置则返回null
     */
    public String getOperation() {
        return operation;
    }
    
    /**
     * 获取详细的异常信息
     * 
     * @return 包含所有异常信息的字符串
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("ResourceException: ").append(getMessage());
        
        if (resourceId != null) {
            sb.append(" [Resource ID: ").append(resourceId).append("]");
        }
        
        if (resourceType != null) {
            sb.append(" [Resource Type: ").append(resourceType).append("]");
        }
        
        if (operation != null) {
            sb.append(" [Operation: ").append(operation).append("]");
        }
        
        return sb.toString();
    }
} 