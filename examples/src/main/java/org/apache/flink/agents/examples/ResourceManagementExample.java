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

package org.apache.flink.agents.examples;

import org.apache.flink.agents.api.*;
import org.apache.flink.agents.api.context.ResourceContext;

/**
 * 资源管理示例Agent
 * 
 * <p>这个示例演示了如何使用资源管理功能，包括：
 * - 在Agent类级别声明资源
 * - 在Action方法级别声明资源
 * - 使用ResourceContext获取和管理资源
 * - 资源的自动生命周期管理
 * 
 * <p>示例用法：
 * <pre>{@code
 * ResourceManagementExample agent = new ResourceManagementExample();
 * AgentPlan plan = new AgentPlan(agent);
 * 
 * // 创建资源上下文
 * ResourceContext context = new ResourceContextImpl();
 * 
 * // 执行Action，资源会自动绑定和管理
 * InputEvent event = new InputEvent("test data");
 * plan.getActionsTriggeredBy(InputEvent.class.getName())
 *     .forEach(action -> action.exec(event, context));
 * }</pre>
 */
@ResourceDeclaration(
    type = ResourceType.PYTHON_ENVIRONMENT,
    config = @ResourceConfigAnnotation(
        properties = {
            "pythonExec", "python3",
            "pythonPath", "/usr/local/lib/python3.8/site-packages"
        }
    ),
    scope = ResourceScope.AGENT,
    name = "python-env",
    required = true
)
public class ResourceManagementExample extends Agent {
    
    /**
     * 处理输入事件的Action
     * 
     * <p>这个Action演示了如何在方法级别声明和使用资源。
     * 它会获取一个HTTP客户端资源，然后使用它来调用外部API。
     */
    @Action(listenEvents = {InputEvent.class})
    @ResourceDeclaration(
        type = ResourceType.HTTP_CLIENT,
        config = @ResourceConfigAnnotation(
            properties = {
                "baseUrl", "https://api.example.com",
                "timeout", "5000",
                "maxConnections", "10"
            },
            timeout = 10000,
            maxRetries = 3
        ),
        scope = ResourceScope.ACTION,
        name = "http-client",
        required = true
    )
    public void processInput(InputEvent event, ResourceContext context) {
        try {
            // 获取Python环境资源（Agent级别）
            Resource pythonResource = context.getResource("python-env");
            if (pythonResource == null) {
                // 如果资源不存在，尝试获取
                pythonResource = context.acquireResource(ResourceType.PYTHON_ENVIRONMENT);
            }
            
            // 获取HTTP客户端资源（Action级别）
            Resource httpResource = context.acquireResource(ResourceType.HTTP_CLIENT);
            
            // 使用Python环境
            System.out.println("Using Python environment: " + pythonResource.getId());
            System.out.println("Python executable: " + pythonResource.getProperties().get("pythonExec"));
            
            // 使用HTTP客户端
            System.out.println("Using HTTP client: " + httpResource.getId());
            System.out.println("Base URL: " + httpResource.getProperties().get("baseUrl"));
            
            // 处理输入数据
            String inputData = event.getInput().toString();
            System.out.println("Processing input: " + inputData);
            
            // 模拟API调用
            String result = callExternalApi(inputData, httpResource);
            
            // 发送输出事件
            context.sendEvent(new OutputEvent(result));
            
            // 注意：资源会在Action执行完毕后自动释放
            // 或者可以通过context.releaseResource()手动释放
            
        } catch (Exception e) {
            System.err.println("Error processing input: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理数据库操作的Action
     * 
     * <p>这个Action演示了如何声明和使用数据库连接资源。
     */
    @Action(listenEvents = {DatabaseEvent.class})
    @ResourceDeclaration(
        type = ResourceType.DATABASE_CONNECTION,
        config = @ResourceConfigAnnotation(
            properties = {
                "url", "jdbc:mysql://localhost:3306/test",
                "username", "root",
                "password", "password",
                "maxConnections", "5"
            },
            timeout = 15000,
            maxRetries = 2
        ),
        scope = ResourceScope.ACTION,
        name = "db-connection",
        required = true
    )
    public void processDatabase(DatabaseEvent event, ResourceContext context) {
        try {
            // 获取数据库连接资源
            Resource dbResource = context.acquireResource(ResourceType.DATABASE_CONNECTION);
            
            System.out.println("Using database connection: " + dbResource.getId());
            System.out.println("Database URL: " + dbResource.getProperties().get("url"));
            
            // 模拟数据库操作
            String query = event.getQuery();
            System.out.println("Executing query: " + query);
            
            // 这里可以执行实际的数据库操作
            String result = executeQuery(query, dbResource);
            
            // 发送结果事件
            context.sendEvent(new DatabaseResultEvent(result));
            
        } catch (Exception e) {
            System.err.println("Error processing database operation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 模拟外部API调用
     * 
     * @param data 输入数据
     * @param httpResource HTTP客户端资源
     * @return API响应
     */
    private String callExternalApi(String data, Resource httpResource) {
        // 这里应该是实际的HTTP调用
        // 为了演示，我们返回一个模拟的响应
        return "API response for: " + data + " (via " + httpResource.getId() + ")";
    }
    
    /**
     * 模拟数据库查询执行
     * 
     * @param query SQL查询
     * @param dbResource 数据库连接资源
     * @return 查询结果
     */
    private String executeQuery(String query, Resource dbResource) {
        // 这里应该是实际的数据库查询
        // 为了演示，我们返回一个模拟的结果
        return "Query result for: " + query + " (via " + dbResource.getId() + ")";
    }
    
    /**
     * 数据库事件
     */
    public static class DatabaseEvent extends Event {
        private final String query;
        
        public DatabaseEvent(String query) {
            this.query = query;
        }
        
        public String getQuery() {
            return query;
        }
    }
    
    /**
     * 数据库结果事件
     */
    public static class DatabaseResultEvent extends Event {
        private final String result;
        
        public DatabaseResultEvent(String result) {
            this.result = result;
        }
        
        public String getResult() {
            return result;
        }
    }
} 