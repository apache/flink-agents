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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源声明注解
 * 
 * <p>@ResourceDeclaration用于在Agent类或Action方法上声明资源需求。
 * 它允许Agent在编译时声明所需的资源，运行时系统会根据这些声明自动管理资源。
 * 
 * <p>示例用法：
 * <pre>{@code
 * @ResourceDeclaration(
 *     type = ResourceType.DATABASE_CONNECTION,
 *     config = @ResourceConfigAnnotation(
 *         properties = {
 *             "url", "jdbc:mysql://localhost:3306/test",
 *             "username", "root",
 *             "password", "password"
 *         }
 *     ),
 *     scope = ResourceScope.ACTION
 * )
 * public class MyAgent extends Agent {
 *     // Agent实现...
 * }
 * }</pre>
 * 
 * <p>或者在Action方法上：
 * <pre>{@code
 * @Action(listenEvents = {InputEvent.class})
 * @ResourceDeclaration(
 *     type = ResourceType.HTTP_CLIENT,
 *     scope = ResourceScope.ACTION
 * )
 * public void processData(InputEvent event, ResourceContext context) {
 *     // 使用HTTP客户端资源...
 * }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceDeclaration {
    
    /**
     * 资源类型
     * 
     * @return 资源类型
     */
    ResourceType type();
    
    /**
     * 资源配置
     * 
     * @return 资源配置
     */
    ResourceConfigAnnotation config() default @ResourceConfigAnnotation();
    
    /**
     * 资源作用域
     * 
     * @return 资源作用域
     */
    ResourceScope scope() default ResourceScope.ACTION;
    
    /**
     * 资源名称（可选）
     * 
     * @return 资源名称
     */
    String name() default "";
    
    /**
     * 是否必需
     * 
     * @return 如果必需返回true，否则返回false
     */
    boolean required() default true;
} 