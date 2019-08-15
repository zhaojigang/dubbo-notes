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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.Random;

/**
 * LeastActiveLoadBalance
 *
 * 需要与ActiveLimitFilter配合使用（ActiveLimitFilter用于记录当前的Invoker的当前方法的活跃数active）
 * @see com.alibaba.dubbo.rpc.filter.ActiveLimitFilter
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "leastactive";
    private final Random random = new Random();

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // Number of invokers
        int leastActive = -1; // 所有Invoker的最小活跃数
        int leastCount = 0; // 有最小活跃数leastActive的Invoker个数（leastCount=2，表示有两个Invoker其leastActive相同）
        int[] leastIndexs = new int[length]; // 存储leastActive的Invoker在List<Invoker<T>> invokers列表中的索引值
        int totalWeight = 0; // 总权重
        int firstWeight = 0; // 第一个被遍历的Invoker的权重，用于比较来计算是否所有的Invoker都有相同的权重
        boolean sameWeight = true; // 是否所有的Invoker都有相同的权重
        /**
         * 1、初始化最小活跃数的Invoker列表：leastIndexs[]
         *   遍历所有的Invoker，
         *   a) 获取每一个方法的活跃数active及其权重；
         *   b) 如果遍历到的Invoker是第一个遍历的Invoker或者有更小的活跃数的Invoker，所有的计数清空，重新进行初始化；
         *   c) 如果遍历到的Invoker的活跃数active与之前记录的leastActive相同，则将当前的Invoker记录到 leastIndexs[] 中
         *   判断所有的Invoker是否都有相同的权重。
         * 2、如果leastIndexs[]中只有一个值，则直接获取对应索引的Invoker；否则按照 RandomLoadBalance 的逻辑进行选择
         */
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // 获取当前Invoker当前方法的活跃数，该活跃数由 ActiveLimitFilter 进行记录
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive(); // Active number
            int afterWarmup = getWeight(invoker, invocation); // Weight
            if (leastActive == -1 || active < leastActive) { // 如果遍历的是第一个Invoker或者有更小的活跃数，所有的计数清空，重新进行初始化
                leastActive = active; // 记录最小活跃数
                leastCount = 1; // Reset leastCount, count again based on current leastCount
                leastIndexs[0] = i; // Reset
                totalWeight = afterWarmup; // Reset
                firstWeight = afterWarmup; // 记录第一个被遍历的Invoker的权重
                sameWeight = true; // Reset, every invoker has the same weight value?
            } else if (active == leastActive) { // 如果遍历到的Invoker的活跃数active与之前记录的leastActive相同
                leastIndexs[leastCount++] = i; // 则将当前的Invoker记录到 leastIndexs[] 中
                totalWeight += afterWarmup; // Add this invoker's weight to totalWeight.
                // 判断所有的Invoker是否都有相同的权重?
                if (sameWeight && i > 0 && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        if (leastCount == 1) {
            return invokers.get(leastIndexs[0]);
        }
        // 后续的逻辑与 RandomLoadBalance 相同
        if (!sameWeight && totalWeight > 0) {
            int offsetWeight = random.nextInt(totalWeight) + 1;
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexs[i];
                offsetWeight -= getWeight(invokers.get(leastIndex), invocation);
                if (offsetWeight <= 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        return invokers.get(leastIndexs[random.nextInt(leastCount)]);
    }
}
