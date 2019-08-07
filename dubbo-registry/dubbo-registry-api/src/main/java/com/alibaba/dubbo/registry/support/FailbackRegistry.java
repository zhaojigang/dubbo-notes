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
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.registry.NotifyListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * FailbackRegistry. (SPI, Prototype, ThreadSafe)
 *
 * 作用：
 * FailbackRegistry主要用来做失败重试操作（包括：注册失败／反注册失败／订阅失败／反订阅失败／通知失败的重试）；
 * 也提供了供ZookeeperRegistry使用的zk重连后的恢复工作的方法
 */
public abstract class FailbackRegistry extends AbstractRegistry {
    /**
     * 重试线程池
     */
    private final ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryFailedRetryTimer", true));

    /**
     * 用于在销毁注册中心时，取消失败重试任务
     */
    private final ScheduledFuture<?> retryFuture;

    /**
     * 重试间隔：默认是5000，即5s
     * The time in milliseconds the retryExecutor will wait
     */
    private final int retryPeriod;

    /**
     * 注册失败的 URL
     */
    private final Set<URL> failedRegistered = new ConcurrentHashSet<URL>();

    /**
     * 取消注册失败的 URL
     */
    private final Set<URL> failedUnregistered = new ConcurrentHashSet<URL>();

    /**
     * 订阅失败的 URL
     */
    private final ConcurrentMap<URL, Set<NotifyListener>> failedSubscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();

    /**
     * 取消订阅失败的 URL
     */
    private final ConcurrentMap<URL, Set<NotifyListener>> failedUnsubscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();

    /**
     * 通知失败的 URL
     */
    private final ConcurrentMap<URL, Map<NotifyListener, List<URL>>> failedNotified = new ConcurrentHashMap<URL, Map<NotifyListener, List<URL>>>();

    public FailbackRegistry(URL url) {
        super(url);
        this.retryPeriod = url.getParameter(Constants.REGISTRY_RETRY_PERIOD_KEY, Constants.DEFAULT_REGISTRY_RETRY_PERIOD);
        this.retryFuture = retryExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                // Check and connect to the registry
                try {
                    retry();
                } catch (Throwable t) { // Defensive fault tolerance
                    logger.error("Unexpected error occur at failed retry, cause: " + t.getMessage(), t);
                }
            }
        }, retryPeriod, retryPeriod, TimeUnit.MILLISECONDS);
    }

    /**
     * 添加URL和监听器到Map<URL, Set<NotifyListener>> failedSubscribed中
     */
    private void addFailedSubscribed(URL url, NotifyListener listener) {
        Set<NotifyListener> listeners = failedSubscribed.get(url);
        if (listeners == null) {
            failedSubscribed.putIfAbsent(url, new ConcurrentHashSet<NotifyListener>());
            listeners = failedSubscribed.get(url);
        }
        listeners.add(listener);
    }

    private void removeFailedSubscribed(URL url, NotifyListener listener) {
        /**
         * 1. 从订阅失败列表 Map<URL, Set<NotifyListener>> failedSubscribed 移除
         */
        Set<NotifyListener> listeners = failedSubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
        /**
         * 2. 从取消订阅列表 Map<URL, Set<NotifyListener>> failedUnsubscribed 移除
         */
        listeners = failedUnsubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
        /**
         * 3. 从通知失败列表 Map<URL, Map<NotifyListener, List<URL>>> failedNotified 移除
         */
        Map<NotifyListener, List<URL>> notified = failedNotified.get(url);
        if (notified != null) {
            notified.remove(listener);
        }
    }

    @Override
    public void register(URL url) {
        /**
         * 1. 数据处理
         * (1) 存储 URL 到已注册列表 Set<URL> registered 中
         * (2) 从 failedRegistered 和 failedUnregistered 移除当前注册的 URL
         */
        super.register(url);
        failedRegistered.remove(url);
        failedUnregistered.remove(url);
        try {
            /**
             * 2. 发起注册请求，由子类实现
             */
            doRegister(url);
        } catch (Exception e) {
            Throwable t = e;

            /**
             * 3. 如果配置了 check=true && url.getProtocol!=consumer，注册出错，则直接抛异常；
             * 否则，记录错误日志，并添加 URL 到 Set<URL> failedRegistered 中（等待后续重试）
             */
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !Constants.CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to register " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to register " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            failedRegistered.add(url);
        }
    }

    @Override
    public void unregister(URL url) {
        /**
         * 1. 数据处理
         * (1) 将 URL 从已注册列表 Set<URL> registered 中删除
         * (2) 从 failedRegistered 和 failedUnregistered 移除当前注册的 URL
         */
        super.unregister(url);
        failedRegistered.remove(url);
        failedUnregistered.remove(url);
        try {
            /**
             * 2. 发起取消注册请求，由子类实现
             */
            doUnregister(url);
        } catch (Exception e) {
            Throwable t = e;

            /**
             * 3. 如果配置了 check=true && url.getProtocol!=consumer，取消注册出错，则直接抛异常；
             * 否则，记录错误日志，并添加 URL 到 Set<URL> failedUnregistered 中（等待后续重试）
             */
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !Constants.CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unregister " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to uregister " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            failedUnregistered.add(url);
        }
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        /**
         * 1. 数据处理
         * (1) 将URL和其监听器NotifyListener添加到已订阅列表Map<URL, Set<NotifyListener>> subscribed中
         * (2) 从failedSubscribed、failedUnsubscribed、failedNotified移除当前注册的URL
         */
        super.subscribe(url, listener);
        removeFailedSubscribed(url, listener);
        try {
            /**
             * 2. 发起订阅请求，由子类实现
             */
            doSubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;
            /**
             * 3. 获取磁盘缓存的 URL，如果有 url，则进行通知操作；否则如果开启了check=false，直接抛错，否则，记录错误日志，并添加URL和NotifyListener到failedSubscribed中（等待后续重试）
             */
            List<URL> urls = getCacheUrls(url);
            if (urls != null && !urls.isEmpty()) {
                notify(url, listener, urls);
                logger.error("Failed to subscribe " + url + ", Using cached list: " + urls + " from cache file: " + getUrl().getParameter(Constants.FILE_KEY, System.getProperty("user.home") + "/dubbo-registry-" + url.getHost() + ".cache") + ", cause: " + t.getMessage(), t);
            } else {
                // If the startup detection is opened, the Exception is thrown directly.
                boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                        && url.getParameter(Constants.CHECK_KEY, true);
                boolean skipFailback = t instanceof SkipFailbackWrapperException;
                if (check || skipFailback) {
                    if (skipFailback) {
                        t = t.getCause();
                    }
                    throw new IllegalStateException("Failed to subscribe " + url + ", cause: " + t.getMessage(), t);
                } else {
                    logger.error("Failed to subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
                }
            }

            // Record a failed registration request to a failed list, retry regularly
            addFailedSubscribed(url, listener);
        }
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        /**
         * 1. 数据处理
         * (1) 将URL和其监听器NotifyListener从已订阅列表中移除
         * (2) 从failedSubscribed、failedUnsubscribed、failedNotified移除当前注册的URL
         */
        super.unsubscribe(url, listener);
        removeFailedSubscribed(url, listener);
        try {
            /**
             * 2. 发起取消订阅请求，由子类实现
             */
            doUnsubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;
            /**
             * 3.如果开启了check=false，直接抛错，否则，记录错误日志，并添加URL和NotifyListener到failedUnsubscribed中（等待后续重试）
             */
            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true);
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unsubscribe " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to unsubscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            Set<NotifyListener> listeners = failedUnsubscribed.get(url);
            if (listeners == null) {
                failedUnsubscribed.putIfAbsent(url, new ConcurrentHashSet<NotifyListener>());
                listeners = failedUnsubscribed.get(url);
            }
            listeners.add(listener);
        }
    }

    @Override
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        try {
            /**
             * 1. 进行通知
             */
            doNotify(url, listener, urls);
        } catch (Exception t) {
            /**
             * 2. 如果通知失败，添加URL和NotifyListener到failedNotified中（等待后续重试）
             */
            // Record a failed registration request to a failed list, retry regularly
            Map<NotifyListener, List<URL>> listeners = failedNotified.get(url);
            if (listeners == null) {
                failedNotified.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, List<URL>>());
                listeners = failedNotified.get(url);
            }
            listeners.put(listener, urls);
            logger.error("Failed to notify for subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
        }
    }

    private void doNotify(URL url, NotifyListener listener, List<URL> urls) {
        super.notify(url, listener, urls);
    }

    @Override
    protected void recover() throws Exception {
        /**
         * 将已注册列表中的所有URL加入到注册失败列表中（后续失败重试任务进行重试）
         */
        Set<URL> recoverRegistered = new HashSet<URL>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                failedRegistered.add(url);
            }
        }
        /**
         * 将已订阅列表中的所有URL加入到订阅失败列表中（后续失败重试任务进行重试）
         */
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    /**
     * 重试：
     * 1. failedRegistered
     * 2. failedUnregistered
     * 3. failedSubscribed
     * 4. failedUnsubscribed
     * 5. failedNotified
     */
    protected void retry() {
        /**
         * 1. 处理注册失败列表
         * 重新注册失败列表中的所有URL，注册成功后，从注册失败列表移除成功的URL
         */
        if (!failedRegistered.isEmpty()) {
            Set<URL> failed = new HashSet<URL>(failedRegistered);
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry register " + failed);
                }
                try {
                    for (URL url : failed) {
                        try {
                            doRegister(url);
                            failedRegistered.remove(url);
                        } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                            logger.warn("Failed to retry register " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                        }
                    }
                } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                    logger.warn("Failed to retry register " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                }
            }
        }
        /**
         * 2. 处理取消注册失败列表
         * 重新取消注册失败列表中的所有URL，取消注册成功后，从取消注册失败列表移除成功的URL
         */
        if (!failedUnregistered.isEmpty()) {
            Set<URL> failed = new HashSet<URL>(failedUnregistered);
            if (!failed.isEmpty()) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry unregister " + failed);
                }
                try {
                    for (URL url : failed) {
                        try {
                            doUnregister(url);
                            failedUnregistered.remove(url);
                        } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                            logger.warn("Failed to retry unregister  " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                        }
                    }
                } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                    logger.warn("Failed to retry unregister  " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                }
            }
        }
        /**
         * 3. 处理订阅失败列表
         * 重试订阅失败列表中的所有URL，订阅成功后，从订阅注册失败列表移除成功的URL的通知成功的NotifyListener
         */
        if (!failedSubscribed.isEmpty()) {
            Map<URL, Set<NotifyListener>> failed = new HashMap<URL, Set<NotifyListener>>(failedSubscribed);
            // 数据预处理：去掉所有没有 NotifyListener 的 url
            for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<URL, Set<NotifyListener>>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() == 0) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry subscribe " + failed);
                }
                try {
                    for (Map.Entry<URL, Set<NotifyListener>> entry : failed.entrySet()) {
                        URL url = entry.getKey();
                        Set<NotifyListener> listeners = entry.getValue();
                        for (NotifyListener listener : listeners) {
                            try {
                                doSubscribe(url, listener);
                                listeners.remove(listener);
                            } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                                logger.warn("Failed to retry subscribe " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                            }
                        }
                    }
                } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                    logger.warn("Failed to retry subscribe " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                }
            }
        }
        /**
         * 4. 处理取消订阅失败列表
         * 重试取消订阅失败列表中的所有URL，取消订阅成功后，从取消订阅失败列表移除成功的URL的通知成功的NotifyListener
         */
        if (!failedUnsubscribed.isEmpty()) {
            Map<URL, Set<NotifyListener>> failed = new HashMap<URL, Set<NotifyListener>>(failedUnsubscribed);
            for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<URL, Set<NotifyListener>>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry unsubscribe " + failed);
                }
                try {
                    for (Map.Entry<URL, Set<NotifyListener>> entry : failed.entrySet()) {
                        URL url = entry.getKey();
                        Set<NotifyListener> listeners = entry.getValue();
                        for (NotifyListener listener : listeners) {
                            try {
                                doUnsubscribe(url, listener);
                                listeners.remove(listener);
                            } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                                logger.warn("Failed to retry unsubscribe " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                            }
                        }
                    }
                } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                    logger.warn("Failed to retry unsubscribe " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                }
            }
        }
        /**
         * 5. 处理通知失败列表
         */
        if (!failedNotified.isEmpty()) {
            Map<URL, Map<NotifyListener, List<URL>>> failed = new HashMap<URL, Map<NotifyListener, List<URL>>>(failedNotified);
            for (Map.Entry<URL, Map<NotifyListener, List<URL>>> entry : new HashMap<URL, Map<NotifyListener, List<URL>>>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() == 0) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry notify " + failed);
                }
                try {
                    for (Map<NotifyListener, List<URL>> values : failed.values()) {
                        for (Map.Entry<NotifyListener, List<URL>> entry : values.entrySet()) {
                            try {
                                // 重试NotifyListener通知所有其需要通知的URL
                                NotifyListener listener = entry.getKey();
                                List<URL> urls = entry.getValue();
                                listener.notify(urls);
                                values.remove(listener);
                            } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                                logger.warn("Failed to retry notify " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                            }
                        }
                    }
                } catch (Throwable t) { // Ignore all the exceptions and wait for the next retry
                    logger.warn("Failed to retry notify " + failed + ", waiting for again, cause: " + t.getMessage(), t);
                }
            }
        }
    }

    @Override
    public void destroy() {
        // 1. 从已注册列表和已订阅列表中移除所有数据
        super.destroy();
        try {
            // 2. 中断重试任务
            retryFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        // 3. 优雅的销毁重试线程池（等待正在执行的任务执行完，等到retryPeriod后，直接关闭线程池）
        ExecutorUtil.gracefulShutdown(retryExecutor, retryPeriod);
    }

    // ==== Template method ====

    protected abstract void doRegister(URL url);

    protected abstract void doUnregister(URL url);

    protected abstract void doSubscribe(URL url, NotifyListener listener);

    protected abstract void doUnsubscribe(URL url, NotifyListener listener);


    public Future<?> getRetryFuture() {
        return retryFuture;
    }

    public Set<URL> getFailedRegistered() {
        return failedRegistered;
    }

    public Set<URL> getFailedUnregistered() {
        return failedUnregistered;
    }

    public Map<URL, Set<NotifyListener>> getFailedSubscribed() {
        return failedSubscribed;
    }

    public Map<URL, Set<NotifyListener>> getFailedUnsubscribed() {
        return failedUnsubscribed;
    }

    public Map<URL, Map<NotifyListener, List<URL>>> getFailedNotified() {
        return failedNotified;
    }
}
