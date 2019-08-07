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
package com.alibaba.dubbo.registry.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AbstractRegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see com.alibaba.dubbo.registry.RegistryFactory
 */
public abstract class AbstractRegistryFactory implements RegistryFactory {

    // Log output
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    /**
     * 创建注册中心加锁：保证注册中心 Registry 单例
     * 销毁注册中心加锁：防止重复销毁
     */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /**
     * 注册中心缓存：
     * key=RegistryAddress，eg. zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService
     * value=具体的Registry
     * <p>
     * 作用：用于销毁和状态检查操作
     */
    private static final Map<String, Registry> REGISTRIES = new ConcurrentHashMap<String, Registry>();

    /**
     * Get all registries
     *
     * @return all registries
     */
    public static Collection<Registry> getRegistries() {
        return Collections.unmodifiableCollection(REGISTRIES.values());
    }

    /**
     * Close all created registries
     */
    // TODO: 2017/8/30 to move somewhere else better
    public static void destroyAll() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Close all registries " + getRegistries());
        }
        // Lock up the registry shutdown process
        LOCK.lock();
        try {
            /**
             * 1. 销毁所有的 Registry
             */
            for (Registry registry : getRegistries()) {
                try {
                    registry.destroy();
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            /**
             * 2. 清空缓存
             */
            REGISTRIES.clear();
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    @Override
    public Registry getRegistry(URL url) {
        /**
         * 1. 创建注册 URL
         */
        url = url.setPath(RegistryService.class.getName())
                .addParameter(Constants.INTERFACE_KEY, RegistryService.class.getName())
                .removeParameters(Constants.EXPORT_KEY, Constants.REFER_KEY);
        // eg. key = zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService
        String key = url.toServiceString();
        /**
         * 2. 从缓存获取 Registry，如果有，直接返回；如果没有，则创建注册中心，最后塞入缓存
         */
        Registry registry = REGISTRIES.get(key);
        if (registry != null) {
            return registry;
        }
        registry = createRegistry(url);
        if (registry == null) {
            throw new IllegalStateException("Can not create registry " + url);
        }
        REGISTRIES.put(key, registry);
        return registry;
    }

    /**
     * 创建注册中心
     */
    protected abstract Registry createRegistry(URL url);

}
