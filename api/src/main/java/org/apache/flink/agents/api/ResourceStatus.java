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
 * 资源状态枚举
 * 
 * <p>定义了资源在其生命周期中可能处于的各种状态。
 * 资源状态的变化反映了资源的创建、初始化、使用和销毁过程。
 */
public enum ResourceStatus {
    
    /**
     * 已创建
     * 
     * <p>资源对象已创建，但尚未初始化。此时资源还不能被使用。
     */
    CREATED("created"),
    
    /**
     * 初始化中
     * 
     * <p>资源正在初始化过程中，例如建立连接、加载配置等。
     * 此时资源还不能被使用。
     */
    INITIALIZING("initializing"),
    
    /**
     * 准备就绪
     * 
     * <p>资源已成功初始化，可以正常使用。
     * 这是资源可以被访问和使用的状态。
     */
    READY("ready"),
    
    /**
     * 使用中
     * 
     * <p>资源正在被使用，可能有并发访问。
     * 此时资源仍然可以被其他请求使用（如果支持并发）。
     */
    IN_USE("in_use"),
    
    /**
     * 错误状态
     * 
     * <p>资源在初始化或使用过程中发生了错误。
     * 此时资源不能被使用，需要重新初始化或释放。
     */
    ERROR("error"),
    
    /**
     * 已销毁
     * 
     * <p>资源已被销毁，不能再被使用。
     * 这是资源的最终状态。
     */
    DESTROYED("destroyed");
    
    private final String value;
    
    ResourceStatus(String value) {
        this.value = value;
    }
    
    /**
     * 获取状态值的字符串表示
     * 
     * @return 状态的字符串值
     */
    public String getValue() {
        return value;
    }
    
    /**
     * 根据字符串值获取ResourceStatus
     * 
     * @param value 字符串值
     * @return 对应的ResourceStatus，如果不存在则返回ERROR
     */
    public static ResourceStatus fromValue(String value) {
        for (ResourceStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return ERROR;
    }
    
    /**
     * 检查资源是否可以被使用
     * 
     * @return 如果资源可以被使用返回true，否则返回false
     */
    public boolean isUsable() {
        return this == READY || this == IN_USE;
    }
    
    /**
     * 检查资源是否处于错误状态
     * 
     * @return 如果资源处于错误状态返回true，否则返回false
     */
    public boolean isError() {
        return this == ERROR;
    }
    
    /**
     * 检查资源是否已被销毁
     * 
     * @return 如果资源已被销毁返回true，否则返回false
     */
    public boolean isDestroyed() {
        return this == DESTROYED;
    }
    
    @Override
    public String toString() {
        return value;
    }
} 