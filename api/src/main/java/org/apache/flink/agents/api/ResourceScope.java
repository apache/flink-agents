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
 * 资源作用域枚举
 * 
 * <p>定义了资源的作用域，决定了资源的生命周期和可见性。
 * 不同的作用域对应不同的资源管理策略。
 */
public enum ResourceScope {
    
    /**
     * 全局作用域
     * 
     * <p>全局资源在整个应用程序生命周期内有效，可以被所有Agent和Action共享。
     * 全局资源通常在应用程序启动时创建，在应用程序关闭时释放。
     * 
     * <p>适用场景：
     * - 数据库连接池
     * - 缓存服务
     * - 配置服务
     */
    GLOBAL("global"),
    
    /**
     * 工作流作用域
     * 
     * <p>工作流资源在整个工作流执行期间有效，可以被工作流中的所有Agent共享。
     * 工作流资源在工作流开始时创建，在工作流结束时释放。
     * 
     * <p>适用场景：
     * - 工作流级别的配置
     * - 工作流共享的数据源
     * - 工作流级别的缓存
     */
    WORKFLOW("workflow"),
    
    /**
     * Action作用域
     * 
     * <p>Action资源只在单个Action执行期间有效，不能被其他Action共享。
     * Action资源在Action开始时创建，在Action结束时释放。
     * 
     * <p>适用场景：
     * - 临时文件
     * - 单次请求的HTTP客户端
     * - Action特定的计算资源
     */
    ACTION("action");
    
    private final String value;
    
    ResourceScope(String value) {
        this.value = value;
    }
    
    /**
     * 获取作用域的字符串值
     * 
     * @return 作用域的字符串表示
     */
    public String getValue() {
        return value;
    }
    
    /**
     * 根据字符串值获取ResourceScope
     * 
     * @param value 字符串值
     * @return 对应的ResourceScope，如果不存在则返回ACTION
     */
    public static ResourceScope fromValue(String value) {
        for (ResourceScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        return ACTION; // 默认返回ACTION作用域
    }
    
    /**
     * 检查是否为全局作用域
     * 
     * @return 如果是全局作用域返回true，否则返回false
     */
    public boolean isGlobal() {
        return this == GLOBAL;
    }
    
    /**
     * 检查是否为工作流作用域
     * 
     * @return 如果是工作流作用域返回true，否则返回false
     */
    public boolean isWorkflow() {
        return this == WORKFLOW;
    }
    
    /**
     * 检查是否为Action作用域
     * 
     * @return 如果是Action作用域返回true，否则返回false
     */
    public boolean isAction() {
        return this == ACTION;
    }
    
    /**
     * 获取作用域的持久性级别
     * 
     * @return 持久性级别（数字越大表示越持久）
     */
    public int getPersistenceLevel() {
        switch (this) {
            case GLOBAL:
                return 3;
            case WORKFLOW:
                return 2;
            case ACTION:
                return 1;
            default:
                return 1;
        }
    }
    
    @Override
    public String toString() {
        return value;
    }
} 