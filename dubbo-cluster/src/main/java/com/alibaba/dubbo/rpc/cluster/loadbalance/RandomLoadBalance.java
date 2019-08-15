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

import java.util.List;
import java.util.Random;

/**
 * random load balance.
 *
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    private final Random random = new Random();

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // Number of invokers
        int totalWeight = 0; // The sum of weights
        boolean sameWeight = true; // Every invoker has the same weight?
        for (int i = 0; i < length; i++) {
            // 计算每一个Invoker的权重
            int weight = getWeight(invokers.get(i), invocation);
            // 计算总权重
            totalWeight += weight; // Sum
            // 计算所有Invoker的权重是否相同
            // 判断方法：每次遍历一个Invoker，都与其前一个Invoker的权重作比较，如果不相等，则设置sameWeight=false，一旦sameWeight=false后，后续的遍历就不必再进行判断了
            if (sameWeight && i > 0 && weight != getWeight(invokers.get(i - 1), invocation)) {
                sameWeight = false;
            }
        }

        // 如果总权重>0&&不是所有的Invoker都有相同的权重，则根据权重进行随机获取
        // eg. 4个Invoker，权重分别是1,2,3,4，则总权重是1+2+3+4=10，说明每个Invoker被选中的概率为1/10,2/10,3/10,4/10。此时有两种算法可以实现带概率的选择：
        // 1. 想象有这样的一个数组 [1,2,2,3,3,3,4,4,4,4], 先随机生成一个[0,10)的值，比如5，该值作为数组的index，此时获取到的是3，即使用第三个Invoker
        // 2. 先随机生成一个[0,10)的值，比如5，从前向后让索引递减权重，直到差值<0，那么最后那个使差值<0的Invoker就是当前选择的Invoker，5-1-2-3<0，那么最终获取的就是第三个Invoker
        // 也可以这样获取Invoker：首先 i=0,5-1=4>0,i++; i=1,4-2=2>0,i++; i=2,2-2=0,i++; i=3, i
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = random.nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < length; i++) {
                offset -= getWeight(invokers.get(i), invocation);
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // 如果所有的Invokers都有相同的权重 or 总权重=0，则直接随机获取
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(random.nextInt(length));
    }

}
