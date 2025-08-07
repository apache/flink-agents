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

package org.apache.flink.agents.plan;

import org.apache.flink.agents.api.*;
import org.apache.flink.agents.api.context.ResourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 资源绑定管理器
 * 
 * <p>ResourceBindingManager负责管理Agent的资源声明和绑定。
 * 它从Agent类和Action方法中提取资源声明，并在运行时自动绑定资源。
 * 
 * <p>这个管理器实现了以下功能：
 * - 解析Agent类和Action方法的资源声明
 * - 管理资源的生命周期
 * - 自动绑定和解绑资源
 * - 支持不同作用域的资源管理
 */
public class ResourceBindingManager {
    
    private static final Logger LOG = LoggerFactory.getLogger(ResourceBindingManager.class);
    
    /**
     * Agent级别的资源声明
     */
    private final List<ResourceDeclaration> agentResourceDeclarations;
    
    /**
     * Action级别的资源声明映射
     */
    private final Map<String, List<ResourceDeclaration>> actionResourceDeclarations;
    
    /**
     * 构造函数
     */
    public ResourceBindingManager() {
        this.agentResourceDeclarations = new ArrayList<>();
        this.actionResourceDeclarations = new HashMap<>();
    }
    
    /**
     * 从Agent类中提取资源声明
     * 
     * @param agentClass Agent类
     */
    public void extractResourceDeclarations(Class<?> agentClass) {
        // 提取类级别的资源声明
        if (agentClass.isAnnotationPresent(ResourceDeclaration.class)) {
            ResourceDeclaration[] declarations = agentClass.getAnnotationsByType(ResourceDeclaration.class);
            for (ResourceDeclaration declaration : declarations) {
                agentResourceDeclarations.add(declaration);
                LOG.info("Extracted agent-level resource declaration: {} (type: {})", 
                        declaration.name(), declaration.type());
            }
        }
        
        // 提取方法级别的资源声明
        for (Method method : agentClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(ResourceDeclaration.class)) {
                ResourceDeclaration[] declarations = method.getAnnotationsByType(ResourceDeclaration.class);
                String methodName = method.getName();
                
                List<ResourceDeclaration> methodDeclarations = actionResourceDeclarations
                        .computeIfAbsent(methodName, k -> new ArrayList<>());
                
                for (ResourceDeclaration declaration : declarations) {
                    methodDeclarations.add(declaration);
                    LOG.info("Extracted action-level resource declaration: {} (type: {}) for method: {}", 
                            declaration.name(), declaration.type(), methodName);
                }
            }
        }
    }
    
    /**
     * 为Action绑定资源
     * 
     * @param actionName Action名称
     * @param context 资源上下文
     * @throws ResourceException 如果绑定失败
     */
    public void bindResourcesForAction(String actionName, ResourceContext context) throws ResourceException {
        // 绑定Agent级别的资源
        for (ResourceDeclaration declaration : agentResourceDeclarations) {
            if (declaration.scope() == ResourceScope.AGENT) {
                bindResource(declaration, context);
            }
        }
        
        // 绑定Action级别的资源
        List<ResourceDeclaration> actionDeclarations = actionResourceDeclarations.get(actionName);
        if (actionDeclarations != null) {
            for (ResourceDeclaration declaration : actionDeclarations) {
                bindResource(declaration, context);
            }
        }
    }
    
    /**
     * 绑定单个资源
     * 
     * @param declaration 资源声明
     * @param context 资源上下文
     * @throws ResourceException 如果绑定失败
     */
    private void bindResource(ResourceDeclaration declaration, ResourceContext context) throws ResourceException {
        try {
            // 从注解创建资源配置
            ResourceConfig config = ResourceConfig.fromAnnotation(declaration.config());
            config.setType(declaration.type());
            config.setScope(declaration.scope());
            
            // 获取资源
            Resource resource = context.acquireResource(declaration.type(), config);
            
            LOG.info("Successfully bound resource: {} (type: {}) for scope: {}", 
                    resource.getId(), declaration.type(), declaration.scope());
            
        } catch (Exception e) {
            if (declaration.required()) {
                throw new ResourceException("Failed to bind required resource: " + declaration.type(), e);
            } else {
                LOG.warn("Failed to bind optional resource: {} (type: {})", 
                        declaration.name(), declaration.type(), e);
            }
        }
    }
    
    /**
     * 解绑Action的资源
     * 
     * @param actionName Action名称
     * @param context 资源上下文
     */
    public void unbindResourcesForAction(String actionName, ResourceContext context) {
        try {
            // 解绑Action级别的资源
            List<ResourceDeclaration> actionDeclarations = actionResourceDeclarations.get(actionName);
            if (actionDeclarations != null) {
                for (ResourceDeclaration declaration : actionDeclarations) {
                    if (declaration.scope() == ResourceScope.ACTION) {
                        // 这里需要根据资源名称或类型来解绑
                        // 由于资源ID是动态生成的，我们需要其他方式来标识资源
                        LOG.debug("Unbinding action-level resource: {} (type: {})", 
                                declaration.name(), declaration.type());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error unbinding resources for action: {}", actionName, e);
        }
    }
    
    /**
     * 清理所有资源
     * 
     * @param context 资源上下文
     */
    public void cleanupAllResources(ResourceContext context) {
        try {
            context.cleanupResources();
            LOG.info("Cleaned up all resources");
        } catch (Exception e) {
            LOG.error("Error cleaning up resources", e);
        }
    }
    
    /**
     * 获取Agent级别的资源声明
     * 
     * @return Agent级别的资源声明列表
     */
    public List<ResourceDeclaration> getAgentResourceDeclarations() {
        return new ArrayList<>(agentResourceDeclarations);
    }
    
    /**
     * 获取Action级别的资源声明
     * 
     * @param actionName Action名称
     * @return Action级别的资源声明列表
     */
    public List<ResourceDeclaration> getActionResourceDeclarations(String actionName) {
        List<ResourceDeclaration> declarations = actionResourceDeclarations.get(actionName);
        return declarations != null ? new ArrayList<>(declarations) : new ArrayList<>();
    }
    
    /**
     * 检查是否有资源声明
     * 
     * @return 如果有资源声明返回true，否则返回false
     */
    public boolean hasResourceDeclarations() {
        return !agentResourceDeclarations.isEmpty() || !actionResourceDeclarations.isEmpty();
    }
    
    /**
     * 获取所有资源类型
     * 
     * @return 资源类型集合
     */
    public Set<ResourceType> getAllResourceTypes() {
        Set<ResourceType> types = new HashSet<>();
        
        // 添加Agent级别的资源类型
        for (ResourceDeclaration declaration : agentResourceDeclarations) {
            types.add(declaration.type());
        }
        
        // 添加Action级别的资源类型
        for (List<ResourceDeclaration> declarations : actionResourceDeclarations.values()) {
            for (ResourceDeclaration declaration : declarations) {
                types.add(declaration.type());
            }
        }
        
        return types;
    }
    
    @Override
    public String toString() {
        return "ResourceBindingManager{" +
                "agentResourceDeclarations=" + agentResourceDeclarations +
                ", actionResourceDeclarations=" + actionResourceDeclarations +
                '}';
    }
} 