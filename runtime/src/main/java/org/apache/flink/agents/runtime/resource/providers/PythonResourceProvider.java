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

package org.apache.flink.agents.runtime.resource.providers;

import org.apache.flink.agents.api.*;
import org.apache.flink.api.common.JobID;
import org.apache.flink.python.env.PythonDependencyInfo;
import org.apache.flink.agents.runtime.env.PythonEnvironmentManager;
import org.apache.flink.agents.runtime.resource.PythonResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Python资源提供者
 * 
 * <p>PythonResourceProvider将现有的PythonEnvironmentManager适配为ResourceProvider接口。
 * 它负责创建和管理Python环境资源，包括Python解释器、依赖包、环境变量等。
 * 
 * <p>这个提供者实现了渐进式迁移策略，保持与现有PythonEnvironmentManager的兼容性。
 * 
 * <p>示例用法：
 * <pre>{@code
 * PythonResourceProvider provider = new PythonResourceProvider();
 * ResourceConfig config = new ResourceConfig(ResourceType.PYTHON_ENVIRONMENT);
 * config.setProperty("pythonExec", "/usr/bin/python3");
 * config.setProperty("pythonPath", "/path/to/python/packages");
 * 
 * PythonResource resource = provider.createResource(config);
 * resource.initialize();
 * }</pre>
 */
public class PythonResourceProvider implements ResourceProvider<PythonResource> {
    
    private static final Logger LOG = LoggerFactory.getLogger(PythonResourceProvider.class);
    
    /**
     * 默认Python可执行文件
     */
    private static final String DEFAULT_PYTHON_EXEC = "python";
    
    /**
     * 默认Python路径
     */
    private static final String DEFAULT_PYTHON_PATH = "";
    
    /**
     * 默认临时目录
     */
    private static final String[] DEFAULT_TMP_DIRECTORIES = {"/tmp"};
    
    /**
     * 默认JobID
     */
    private static final JobID DEFAULT_JOB_ID = new JobID();
    
    @Override
    public ResourceType getSupportedType() {
        return ResourceType.PYTHON_ENVIRONMENT;
    }
    
    @Override
    public PythonResource createResource(ResourceConfig config) throws ResourceException {
        if (config == null) {
            throw new ResourceException("Configuration cannot be null");
        }
        
        if (!supports(config)) {
            throw new ResourceException("Configuration not supported: " + config);
        }
        
        try {
            // 从配置中提取Python环境参数
            String pythonExec = config.getStringProperty("pythonExec");
            if (pythonExec == null) {
                pythonExec = DEFAULT_PYTHON_EXEC;
            }
            
            String pythonPath = config.getStringProperty("pythonPath");
            if (pythonPath == null) {
                pythonPath = DEFAULT_PYTHON_PATH;
            }
            
            // 创建Python环境管理器
            PythonEnvironmentManager environmentManager = createEnvironmentManager(config);
            
            // 创建Python资源
            String resourceId = generateResourceId();
            PythonResource resource = new PythonResource(resourceId, environmentManager);
            
            // 设置资源属性
            resource.setProperty("pythonExec", pythonExec);
            resource.setProperty("pythonPath", pythonPath);
            
            LOG.info("Created Python resource: {} with exec: {}", resourceId, pythonExec);
            
            return resource;
            
        } catch (Exception e) {
            throw new ResourceException("Failed to create Python resource", e);
        }
    }
    
    @Override
    public void releaseResource(PythonResource resource) throws ResourceException {
        if (resource == null) {
            return;
        }
        
        try {
            resource.close();
            LOG.info("Released Python resource: {}", resource.getId());
        } catch (Exception e) {
            throw new ResourceException("Failed to release Python resource: " + resource.getId(), e);
        }
    }
    
    @Override
    public boolean isHealthy() {
        // 检查Python环境是否可用
        try {
            // 这里可以添加更详细的健康检查逻辑
            // 例如检查Python解释器是否可用
            return true;
        } catch (Exception e) {
            LOG.warn("Python resource provider health check failed", e);
            return false;
        }
    }
    
    /**
     * 创建Python环境管理器
     * 
     * @param config 资源配置
     * @return Python环境管理器
     * @throws Exception 如果创建失败
     */
    private PythonEnvironmentManager createEnvironmentManager(ResourceConfig config) throws Exception {
        // 从配置中提取参数
        String pythonExec = config.getStringProperty("pythonExec");
        if (pythonExec == null) {
            pythonExec = DEFAULT_PYTHON_EXEC;
        }
        
        // 获取Python文件映射
        @SuppressWarnings("unchecked")
        Map<String, String> pythonFiles = (Map<String, String>) config.getProperty("pythonFiles");
        if (pythonFiles == null) {
            pythonFiles = new HashMap<>();
        }
        
        // 获取Python依赖信息
        @SuppressWarnings("unchecked")
        Map<String, String> requirements = (Map<String, String>) config.getProperty("requirements");
        if (requirements == null) {
            requirements = new HashMap<>();
        }
        
        // 获取Python归档文件
        @SuppressWarnings("unchecked")
        Map<String, String> archives = (Map<String, String>) config.getProperty("archives");
        if (archives == null) {
            archives = new HashMap<>();
        }
        
        // 获取临时目录
        String[] tmpDirectories = (String[]) config.getProperty("tmpDirectories");
        if (tmpDirectories == null) {
            tmpDirectories = DEFAULT_TMP_DIRECTORIES;
        }
        
        // 获取系统环境变量
        @SuppressWarnings("unchecked")
        Map<String, String> systemEnv = (Map<String, String>) config.getProperty("systemEnv");
        if (systemEnv == null) {
            systemEnv = new HashMap<>(System.getenv());
        }
        
        // 获取JobID
        JobID jobId = (JobID) config.getProperty("jobId");
        if (jobId == null) {
            jobId = DEFAULT_JOB_ID;
        }
        
        // 创建Python依赖信息
        PythonDependencyInfo dependencyInfo = new PythonDependencyInfo(
                pythonFiles,
                null, // requirementsFile
                null, // requirementsCacheDir
                archives,
                pythonExec
        );
        
        // 创建Python环境管理器
        return new PythonEnvironmentManager(dependencyInfo, tmpDirectories, systemEnv, jobId);
    }
    
    /**
     * 生成资源ID
     * 
     * @return 生成的资源ID
     */
    private String generateResourceId() {
        return "python-env-" + System.currentTimeMillis();
    }
}