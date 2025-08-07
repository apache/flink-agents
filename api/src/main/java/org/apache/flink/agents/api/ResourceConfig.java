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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 资源配置类
 * 
 * <p>ResourceConfig用于配置资源的创建和管理参数，包括资源类型、属性、超时时间、重试次数等。
 * 这个类提供了灵活的配置机制，支持不同类型的资源配置需求。
 * 
 * <p>示例用法：
 * <pre>{@code
 * ResourceConfig config = new ResourceConfig();
 * config.setType(ResourceType.DATABASE_CONNECTION);
 * config.setProperty("url", "jdbc:mysql://localhost:3306/test");
 * config.setProperty("username", "user");
 * config.setProperty("password", "pass");
 * config.setTimeout(Duration.ofSeconds(30));
 * config.setRetryCount(3);
 * config.setScope(ResourceScope.WORKFLOW);
 * }</pre>
 */
public class ResourceConfig {
    
    /**
     * 资源类型
     */
    private ResourceType type;
    
    /**
     * 资源属性映射
     */
    private final Map<String, Object> properties;
    
    /**
     * 超时时间
     */
    private Duration timeout;
    
    /**
     * 重试次数
     */
    private int retryCount;
    
    /**
     * 资源作用域
     */
    private ResourceScope scope;
    
    /**
     * 默认构造函数
     */
    public ResourceConfig() {
        this.properties = new HashMap<>();
        this.timeout = Duration.ofSeconds(30); // 默认30秒超时
        this.retryCount = 3; // 默认重试3次
        this.scope = ResourceScope.ACTION; // 默认ACTION作用域
    }
    
    /**
     * 构造函数
     * 
     * @param type 资源类型
     */
    public ResourceConfig(ResourceType type) {
        this();
        this.type = type;
    }
    
    /**
     * 从注解创建资源配置
     * 
     * @param annotation 资源配置注解
     * @return 资源配置实例
     */
    public static ResourceConfig fromAnnotation(ResourceConfigAnnotation annotation) {
        ResourceConfig config = new ResourceConfig();
        
        // 设置属性
        String[] properties = annotation.properties();
        for (int i = 0; i < properties.length; i += 2) {
            if (i + 1 < properties.length) {
                config.setProperty(properties[i], properties[i + 1]);
            }
        }
        
        // 设置其他属性
        config.setTimeout(Duration.ofMillis(annotation.timeout()));
        config.setRetryCount(annotation.maxRetries());
        
        return config;
    }
    
    /**
     * 获取资源类型
     * 
     * @return 资源类型
     */
    public ResourceType getType() {
        return type;
    }
    
    /**
     * 设置资源类型
     * 
     * @param type 资源类型
     * @return 当前配置对象，支持链式调用
     */
    public ResourceConfig setType(ResourceType type) {
        this.type = type;
        return this;
    }
    
    /**
     * 获取所有属性
     * 
     * @return 属性映射的副本
     */
    public Map<String, Object> getProperties() {
        return new HashMap<>(properties);
    }
    
    /**
     * 设置属性
     * 
     * @param key 属性键
     * @param value 属性值
     * @return 当前配置对象，支持链式调用
     */
    public ResourceConfig setProperty(String key, Object value) {
        this.properties.put(key, value);
        return this;
    }
    
    /**
     * 获取属性值
     * 
     * @param key 属性键
     * @return 属性值，如果不存在则返回null
     */
    public Object getProperty(String key) {
        return properties.get(key);
    }
    
    /**
     * 获取字符串属性值
     * 
     * @param key 属性键
     * @return 字符串属性值，如果不存在或不是字符串则返回null
     */
    public String getStringProperty(String key) {
        Object value = properties.get(key);
        return value instanceof String ? (String) value : null;
    }
    
    /**
     * 获取整数属性值
     * 
     * @param key 属性键
     * @return 整数属性值，如果不存在或不是整数则返回null
     */
    public Integer getIntProperty(String key) {
        Object value = properties.get(key);
        return value instanceof Integer ? (Integer) value : null;
    }
    
    /**
     * 获取布尔属性值
     * 
     * @param key 属性键
     * @return 布尔属性值，如果不存在或不是布尔值则返回null
     */
    public Boolean getBooleanProperty(String key) {
        Object value = properties.get(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }
    
    /**
     * 获取超时时间
     * 
     * @return 超时时间
     */
    public Duration getTimeout() {
        return timeout;
    }
    
    /**
     * 设置超时时间
     * 
     * @param timeout 超时时间
     * @return 当前配置对象，支持链式调用
     */
    public ResourceConfig setTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }
    
    /**
     * 获取重试次数
     * 
     * @return 重试次数
     */
    public int getRetryCount() {
        return retryCount;
    }
    
    /**
     * 设置重试次数
     * 
     * @param retryCount 重试次数
     * @return 当前配置对象，支持链式调用
     */
    public ResourceConfig setRetryCount(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }
    
    /**
     * 获取资源作用域
     * 
     * @return 资源作用域
     */
    public ResourceScope getScope() {
        return scope;
    }
    
    /**
     * 设置资源作用域
     * 
     * @param scope 资源作用域
     * @return 当前配置对象，支持链式调用
     */
    public ResourceConfig setScope(ResourceScope scope) {
        this.scope = scope;
        return this;
    }
    
    /**
     * 检查配置是否有效
     * 
     * @return 如果配置有效返回true，否则返回false
     */
    public boolean isValid() {
        return type != null && timeout != null && retryCount >= 0 && scope != null;
    }
    
    @Override
    public String toString() {
        return "ResourceConfig{" +
                "type=" + type +
                ", properties=" + properties +
                ", timeout=" + timeout +
                ", retryCount=" + retryCount +
                ", scope=" + scope +
                '}';
    }
} 