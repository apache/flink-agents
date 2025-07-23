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

import org.apache.flink.agents.api.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 抽象资源基类
 * 
 * <p>AbstractResource提供了Resource接口的基础实现，包括通用的状态管理、属性存储等。
 * 具体的资源类型应该继承此类并实现特定的功能。
 * 
 * <p>示例用法：
 * <pre>{@code
 * public class DatabaseResource extends AbstractResource {
 *     private Connection connection;
 *     
 *     public DatabaseResource(String id, ResourceType type) {
 *         super(id, type);
 *     }
 *     
 *     @Override
 *     protected void doInitialize() throws ResourceException {
 *         // 实现数据库连接初始化逻辑
 *         connection = DriverManager.getConnection(getStringProperty("url"));
 *     }
 *     
 *     @Override
 *     protected void doClose() throws Exception {
 *         if (connection != null) {
 *             connection.close();
 *         }
 *     }
 *     
 *     @Override
 *     public Object getContent() throws ResourceException {
 *         return connection;
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractResource implements Resource {
    
    /**
     * 资源ID
     */
    private final String id;
    
    /**
     * 资源类型
     */
    private final ResourceType type;
    
    /**
     * 资源状态
     */
    private volatile ResourceStatus status;
    
    /**
     * 资源属性
     */
    private final Map<String, Object> properties;
    
    /**
     * 资源内容
     */
    private Object content;
    
    /**
     * 构造函数
     * 
     * @param id 资源ID
     * @param type 资源类型
     */
    protected AbstractResource(String id, ResourceType type) {
        this.id = id;
        this.type = type;
        this.status = ResourceStatus.CREATED;
        this.properties = new HashMap<>();
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public ResourceType getType() {
        return type;
    }
    
    @Override
    public ResourceStatus getStatus() {
        return status;
    }
    
    @Override
    public Map<String, Object> getProperties() {
        return new HashMap<>(properties);
    }
    
    @Override
    public void initialize() throws ResourceException {
        if (status == ResourceStatus.INITIALIZING || status == ResourceStatus.READY) {
            return; // 已经初始化或正在初始化
        }
        
        if (status == ResourceStatus.DESTROYED) {
            throw new ResourceException("Cannot initialize destroyed resource: " + id);
        }
        
        status = ResourceStatus.INITIALIZING;
        
        try {
            doInitialize();
            status = ResourceStatus.READY;
        } catch (Exception e) {
            status = ResourceStatus.ERROR;
            throw new ResourceException("Failed to initialize resource: " + id, e, id, type, "initialize");
        }
    }
    
    @Override
    public boolean isReady() {
        return status == ResourceStatus.READY || status == ResourceStatus.IN_USE;
    }
    
    @Override
    public Object getContent() throws ResourceException {
        if (!isReady()) {
            throw new ResourceException("Resource is not ready: " + id, id, type, "getContent");
        }
        
        if (content == null) {
            content = doGetContent();
        }
        
        return content;
    }
    
    @Override
    public void close() throws Exception {
        if (status == ResourceStatus.DESTROYED) {
            return; // 已经销毁
        }
        
        try {
            doClose();
        } finally {
            status = ResourceStatus.DESTROYED;
            content = null;
        }
    }
    
    /**
     * 设置属性
     * 
     * @param key 属性键
     * @param value 属性值
     */
    protected void setProperty(String key, Object value) {
        properties.put(key, value);
    }
    
    /**
     * 获取属性值
     * 
     * @param key 属性键
     * @return 属性值
     */
    protected Object getProperty(String key) {
        return properties.get(key);
    }
    
    /**
     * 获取字符串属性值
     * 
     * @param key 属性键
     * @return 字符串属性值
     */
    protected String getStringProperty(String key) {
        Object value = properties.get(key);
        return value instanceof String ? (String) value : null;
    }
    
    /**
     * 获取整数属性值
     * 
     * @param key 属性键
     * @return 整数属性值
     */
    protected Integer getIntProperty(String key) {
        Object value = properties.get(key);
        return value instanceof Integer ? (Integer) value : null;
    }
    
    /**
     * 获取布尔属性值
     * 
     * @param key 属性键
     * @return 布尔属性值
     */
    protected Boolean getBooleanProperty(String key) {
        Object value = properties.get(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }
    
    /**
     * 设置资源内容
     * 
     * @param content 资源内容
     */
    protected void setContent(Object content) {
        this.content = content;
    }
    
    /**
     * 设置状态
     * 
     * @param status 新状态
     */
    protected void setStatus(ResourceStatus status) {
        this.status = status;
    }
    
    /**
     * 执行初始化逻辑
     * 
     * <p>子类应该重写此方法来实现具体的初始化逻辑。
     * 
     * @throws ResourceException 如果初始化失败
     */
    protected abstract void doInitialize() throws ResourceException;
    
    /**
     * 执行关闭逻辑
     * 
     * <p>子类应该重写此方法来实现具体的关闭逻辑。
     * 
     * @throws Exception 如果关闭失败
     */
    protected abstract void doClose() throws Exception;
    
    /**
     * 获取资源内容
     * 
     * <p>子类应该重写此方法来实现具体的内容获取逻辑。
     * 
     * @return 资源内容
     * @throws ResourceException 如果获取内容失败
     */
    protected Object doGetContent() throws ResourceException {
        return content;
    }
    
    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", status=" + status +
                ", properties=" + properties +
                '}';
    }
} 