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
 * 资源配置注解
 * 
 * <p>@ResourceConfigAnnotation用于在@ResourceDeclaration中配置资源参数。
 * 它允许用户以键值对的形式配置资源的各种属性。
 * 
 * <p>示例用法：
 * <pre>{@code
 * @ResourceDeclaration(
 *     type = ResourceType.DATABASE_CONNECTION,
 *     config = @ResourceConfigAnnotation(
 *         properties = {
 *             "url", "jdbc:mysql://localhost:3306/test",
 *             "username", "root",
 *             "password", "password",
 *             "maxConnections", "10"
 *         }
 *     )
 * )
 * public class MyAgent extends Agent {
 *     // Agent实现...
 * }
 * }</pre>
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceConfigAnnotation {
    
    /**
     * 资源属性键值对
     * 
     * <p>属性以键值对的形式提供，偶数索引为键，奇数索引为值。
     * 例如：{"url", "jdbc:mysql://localhost:3306/test", "username", "root"}
     * 
     * @return 资源属性键值对
     */
    String[] properties() default {};
    
    /**
     * 资源超时时间（毫秒）
     * 
     * @return 超时时间
     */
    long timeout() default 30000;
    
    /**
     * 最大重试次数
     * 
     * @return 最大重试次数
     */
    int maxRetries() default 3;
    
    /**
     * 是否启用缓存
     * 
     * @return 如果启用缓存返回true，否则返回false
     */
    boolean enableCache() default false;
} 