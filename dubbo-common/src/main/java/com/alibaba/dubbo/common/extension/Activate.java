/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.URL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activate. This annotation is useful for automatically activate certain extensions with the given criteria,
 * for examples: <code>@Activate</code> can be used to load certain <code>Filter</code> extension when there are
 * multiple implementations.
 *
 * 激活点。该注解用于根据已有的条件来自动激活满足条件的多个扩展实现类。
 * eg. @Activate 可以被用于加载多个 Filter 扩展实现类。
 *
 * <ol>
 * <li>{@link Activate#group()} specifies group criteria. Framework SPI defines the valid group values. group()指定组条件
 * <li>{@link Activate#value()} specifies parameter key in {@link URL} criteria. value()指定参数条件
 * </ol>
 * SPI provider can call {@link ExtensionLoader#getActivateExtension(URL, String, String)} to find out all activated
 * extensions with the given criteria.
 * SPI 服务发现者可以通过调用 {@link ExtensionLoader#getActivateExtension(URL, String, String)} 来找出所有满足给定条件的的激活扩展实现类。
 *
 * @see SPI
 * @see URL
 * @see ExtensionLoader
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Activate {
    /**
     * 当 String[] group() 包含当前传入的 group，则该条件满足，进行其他条件的判断。
     * Activate the current extension when one of the groups matches. The group passed into
     * {@link ExtensionLoader#getActivateExtension(URL, String, String)} will be used for matching.
     *
     * @return group names to match
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     */
    String[] group() default {};

    /**
     * 当 String[] value() 中的任一值出现在当前传入的 URL#parameters() 中的一个参数名
     * Activate the current extension when the specified keys appear in the URL's parameters.
     * <p>
     * For example, given <code>@Activate("cache, validation")</code>, the current extension will be return only when
     * there's either <code>cache</code> or <code>validation</code> key appeared in the URL's parameters.
     * </p>
     *
     * @return URL parameter keys
     * @see ExtensionLoader#getActivateExtension(URL, String)
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     */
    String[] value() default {};

    /**
     * String[] before() 指定当前的 SPI 扩展实现类需要放在哪些扩展实现类之前
     * Relative ordering info, optional
     *
     * @return extension list which should be put before the current one
     */
    String[] before() default {};

    /**
     * String[] after() 指定当前的 SPI 扩展实现类需要放在哪些扩展实现类之后
     * Relative ordering info, optional
     *
     * @return extension list which should be put after the current one
     */
    String[] after() default {};

    /**
     * 指定绝对的顺序 order，order 越小，越靠前
     * Absolute ordering info, optional
     *
     * @return absolute ordering info
     */
    int order() default 0;
}