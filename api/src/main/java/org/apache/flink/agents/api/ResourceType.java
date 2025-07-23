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
 * 资源类型枚举
 * 
 * <p>定义了系统中支持的各种资源类型，包括计算资源、存储资源、网络资源等。
 * 每种资源类型都有对应的ResourceProvider来管理。
 */
public enum ResourceType {
    
    /**
     * Python环境资源
     * 
     * <p>用于管理Python解释器环境，包括Python版本、依赖包、环境变量等。
     * 对应现有的PythonEnvironmentManager功能。
     */
    PYTHON_ENVIRONMENT("python_environment"),
    
    /**
     * 数据库连接资源
     * 
     * <p>用于管理数据库连接，包括MySQL、PostgreSQL、Oracle等数据库的连接池。
     */
    DATABASE_CONNECTION("database_connection"),
    
    /**
     * HTTP客户端资源
     * 
     * <p>用于管理HTTP客户端连接，包括REST API客户端、Web服务客户端等。
     */
    HTTP_CLIENT("http_client"),
    
    /**
     * 文件系统资源
     * 
     * <p>用于管理文件系统访问，包括本地文件系统、分布式文件系统等。
     */
    FILE_SYSTEM("file_system"),
    
    /**
     * 内存缓存资源
     * 
     * <p>用于管理内存缓存，包括Redis、Memcached等缓存系统。
     */
    MEMORY_CACHE("memory_cache"),
    
    /**
     * 消息队列资源
     * 
     * <p>用于管理消息队列连接，包括Kafka、RabbitMQ、ActiveMQ等。
     */
    MESSAGE_QUEUE("message_queue"),
    
    /**
     * 机器学习模型资源
     * 
     * <p>用于管理机器学习模型，包括模型加载、版本管理等。
     */
    ML_MODEL("ml_model"),
    
    /**
     * 自定义资源
     * 
     * <p>用于支持用户自定义的资源类型。
     */
    CUSTOM("custom");
    
    private final String value;
    
    ResourceType(String value) {
        this.value = value;
    }
    
    /**
     * 获取资源类型的字符串值
     * 
     * @return 资源类型的字符串表示
     */
    public String getValue() {
        return value;
    }
    
    /**
     * 根据字符串值获取ResourceType
     * 
     * @param value 字符串值
     * @return 对应的ResourceType，如果不存在则返回CUSTOM
     */
    public static ResourceType fromValue(String value) {
        for (ResourceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return CUSTOM;
    }
    
    @Override
    public String toString() {
        return value;
    }
} 