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
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Python环境资源
 * 
 * <p>PythonResource表示一个Python环境资源，封装了PythonEnvironmentManager的功能。
 * 它提供了Python解释器环境的管理，包括Python版本、依赖包、环境变量等。
 * 
 * <p>这个类实现了渐进式迁移策略，保持与现有PythonEnvironmentManager的兼容性。
 * 
 * <p>示例用法：
 * <pre>{@code
 * PythonResource resource = new PythonResource("python-env-1", environmentManager);
 * resource.initialize();
 * 
 * if (resource.isReady()) {
 *     PythonEnvironmentManager envManager = (PythonEnvironmentManager) resource.getContent();
 *     // 使用Python环境管理器...
 * }
 * }</pre>
 */
public class PythonResource extends AbstractResource {
    
    private static final Logger LOG = LoggerFactory.getLogger(PythonResource.class);
    
    /**
     * Python环境管理器
     */
    private final PythonEnvironmentManager environmentManager;
    
    /**
     * 构造函数
     * 
     * @param id 资源ID
     * @param environmentManager Python环境管理器
     */
    public PythonResource(String id, PythonEnvironmentManager environmentManager) {
        super(id, ResourceType.PYTHON_ENVIRONMENT);
        this.environmentManager = environmentManager;
        
        // 设置资源属性
        if (environmentManager != null) {
            setProperty("pythonExec", environmentManager.getPythonEnv().get("python"));
            setProperty("pythonPath", environmentManager.getPythonEnv().get("PYTHONPATH"));
        }
    }
    
    @Override
    protected void doInitialize() throws ResourceException {
        if (environmentManager == null) {
            throw new ResourceException("PythonEnvironmentManager is null");
        }
        
        try {
            // 打开Python环境管理器
            environmentManager.open();
            
            // 创建Python环境
            environmentManager.createEnvironment();
            
            LOG.info("Initialized Python resource: {}", getId());
            
        } catch (Exception e) {
            throw new ResourceException("Failed to initialize Python resource: " + getId(), e);
        }
    }
    
    @Override
    protected void doClose() throws Exception {
        if (environmentManager != null) {
            try {
                environmentManager.close();
                LOG.info("Closed Python resource: {}", getId());
            } catch (Exception e) {
                LOG.error("Error closing Python resource: {}", getId(), e);
                throw e;
            }
        }
    }
    
    @Override
    protected Object doGetContent() throws ResourceException {
        if (environmentManager == null) {
            throw new ResourceException("PythonEnvironmentManager is null");
        }
        return environmentManager;
    }
    
    /**
     * 获取Python环境管理器
     * 
     * @return Python环境管理器
     */
    public PythonEnvironmentManager getEnvironmentManager() {
        return environmentManager;
    }
    
    /**
     * 获取Python可执行文件路径
     * 
     * @return Python可执行文件路径
     */
    public String getPythonExec() {
        return getStringProperty("pythonExec");
    }
    
    /**
     * 获取Python路径
     * 
     * @return Python路径
     */
    public String getPythonPath() {
        return getStringProperty("pythonPath");
    }
    
    /**
     * 检查Python环境是否可用
     * 
     * @return 如果可用返回true，否则返回false
     */
    public boolean isEnvironmentAvailable() {
        return environmentManager != null && isReady();
    }
}