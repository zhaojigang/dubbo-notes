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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AbstractLoadBalance
 */
public abstract class AbstractLoadBalance implements LoadBalance {
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (invokers == null || invokers.isEmpty()) {
            return null;
        }
        // 1. 如果只有一个 Invoker，直接返回
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        // 2. 调用子类进行选择
        return doSelect(invokers, url, invocation);
    }

    /**
     * 子类重写的方法：真正选择 Invoker(filtered) 的方法
     */
    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);

    /**
     * 获取一个 Invoker(filtered) 的权重
     * 1、获取当前Invoker设置的权重weight和预热时间warmup，并且计算启动至今时间uptime
     * 2、如果uptime<warmup，则重新计算当前Invoker的weight（uptime/warmup*weight），否则直接返回设置的weight
     */
    protected int getWeight(Invoker<?> invoker, Invocation invocation) {
        // 1. 获取当前Invoker设置的权重:weight=100（该值配置在provider端）
        //     全局：<dubbo:provider warmup="100000" weight="10"/>
        //     单个服务：<dubbo:service interface="..." warmup="6000000" weight="10"/>
        int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT);
        if (weight > 0) {
            // 2. 获取启动的时间点，该值服务启动时会存储在注册的 URL 上，（timestamp）：dubbo://10.213.11.98:20880/com.alibaba.dubbo.demo.DemoService?...&timestamp=1565775720703&warmup=10000&weight=10
            long timestamp = invoker.getUrl().getParameter(Constants.REMOTE_TIMESTAMP_KEY, 0L);
            if (timestamp > 0L) {
                // 3. 计算启动至今时间
                int uptime = (int) (System.currentTimeMillis() - timestamp);
                // 4. 获取预热时间
                int warmup = invoker.getUrl().getParameter(Constants.WARMUP_KEY, Constants.DEFAULT_WARMUP); // warmup=10*60*1000=10min，获取设置的总预热时间
                // 5. 如果没有过完预热时间，则计算预热权重
                if (uptime > 0 && uptime < warmup) {
                    weight = calculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }
        return weight;
    }

    /**
     * 计算预热权重
     * 预热公式：uptime/warmup*weight => 启动至今时间/设置的预热总时间*权重
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        /**
         * eg. 设置的权重是 100， 预热时间 10min,
         * 第一分钟的时候：权重变为 (1/10)*100=10, 也就是承担 10/100 = 10% 的流量；
         * 第二分钟的时候：权重变为 (2/10)*100=20, 也就是承担 20/100 = 20% 的流量；
         * 第十分钟的时候：权重变为 (10/10)*100=100, 也就是承担 100/100 = 100% 的流量；
         */
        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));
        return ww < 1 ? 1 : (ww > weight ? weight : ww);
    }
}
