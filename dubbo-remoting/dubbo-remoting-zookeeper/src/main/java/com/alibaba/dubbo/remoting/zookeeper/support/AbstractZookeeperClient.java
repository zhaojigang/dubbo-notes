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
package com.alibaba.dubbo.remoting.zookeeper.support;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;


public abstract class AbstractZookeeperClient<TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    /**
     * 注册的URL
     */
    private final URL url;
    /**
     * 状态监听器
     */
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();
    /**
     * 子节点监听器
     */
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();
    /**
     * 会话是否关闭
     */
    private volatile boolean closed = false;

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void create(String path, boolean ephemeral) {
        /**
         * 优化：
         * 如果是持久节点，检测是否节点已经存在，如果已经存在，则直接返回
         */
        if (!ephemeral) {
            if (checkExists(path)) {
                return;
            }
        }

        /**
         * 递归创建节点路径。
         * 实际上，如果使用了curator的话，可以直接使用递归创建节点即可（结合zk的特性，只有最后一个字节点可以是临时节点，父节点一定是持久化节点），
         * 这里这样的写法是兼容不能递归创建节点的 ZkClient 客户端
         *
         * eg. /dubbo/com.alibaba.dubbo.demo.DemoService/providers/dubbo...
         * 需要先创建/dubbo，
         * 再创建/dubbo/com.alibaba.dubbo.demo.DemoService，
         * 再创建/dubbo/com.alibaba.dubbo.demo.DemoService/providers这三个节点
         */
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        /**
         * 创建节点
         */
        if (ephemeral) {
            // 创建临时节点
            createEphemeral(path);
        } else {
            // 创建持久节点
            createPersistent(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    /**
     * 添加子节点监听器
     * @param path 指定节点
     * @param listener 子节点监听器
     * @return
     */
    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            listeners = childListeners.get(path);
        }
        TargetChildListener targetListener = listeners.get(listener);
        if (targetListener == null) {
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            targetListener = listeners.get(listener);
        }
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    protected abstract void doClose();

    /**
     * 创建持久化节点
     */
    protected abstract void createPersistent(String path);

    /**
     * 创建临时节点
     */
    protected abstract void createEphemeral(String path);

    /**
     * 检测节点是否存在
     */
    protected abstract boolean checkExists(String path);

    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

}
