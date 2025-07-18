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

import java.util.Map;

/**
 * 抽象化的可管理资源接口
 * 
 * <p>Resource表示一个可管理的资源，包括计算资源、存储资源、网络资源、外部服务连接等。
 * 每个资源都有生命周期管理（创建、使用、释放）。
 * 
 * <p>Resource接口继承自AutoCloseable，确保资源能够被正确释放。
 * 
 * <p>示例用法：
 * <pre>{@code
 * try (Resource resource = resourceProvider.createResource(config)) {
 *     resource.initialize();
 *     if (resource.isReady()) {
 *         // 使用资源
 *         Object content = resource.getContent();
 *     }
 * }
 * }</pre>
 */
public interface Resource extends AutoCloseable {
    
    /**
     * 获取资源的唯一标识符
     * 
     * @return 资源ID
     */
    String getId();
    
    /**
     * 获取资源的类型
     * 
     * @return 资源类型
     */
    ResourceType getType();
    
    /**
     * 获取资源的当前状态
     * 
     * @return 资源状态
     */
    ResourceStatus getStatus();
    
    /**
     * 获取资源的配置属性
     * 
     * @return 资源属性映射
     */
    Map<String, Object> getProperties();
    
    /**
     * 初始化资源
     * 
     * <p>在资源可以被使用之前，必须调用此方法进行初始化。
     * 初始化过程可能包括连接建立、配置加载等操作。
     * 
     * @throws ResourceException 如果初始化失败
     */
    void initialize() throws ResourceException;
    
    /**
     * 检查资源是否准备就绪
     * 
     * <p>资源必须经过初始化并且状态为READY才能被使用。
     * 
     * @return 如果资源准备就绪返回true，否则返回false
     */
    boolean isReady();
    
    /**
     * 获取资源的实际内容
     * 
     * <p>返回资源的具体内容，类型取决于资源类型。
     * 例如，文件资源可能返回文件内容，数据库连接资源可能返回Connection对象。
     * 
     * @return 资源内容
     * @throws ResourceException 如果获取内容失败
     */
    Object getContent() throws ResourceException;
} 