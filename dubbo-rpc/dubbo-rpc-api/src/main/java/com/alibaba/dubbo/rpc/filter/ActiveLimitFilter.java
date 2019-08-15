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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcStatus;

/**
 * 1. 仅用于 consumer 端
 * 2. 需要配置 actives 参数
 *    <0，只记录活跃数（并发度）
 *    >0, 记录活跃数（并发度）+ 限流（限制每个客户端的并发执行数）
 */
@Activate(group = Constants.CONSUMER, value = Constants.ACTIVES_KEY)
public class ActiveLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        // 获取最大并发数 actives=10
        int max = invoker.getUrl().getMethodParameter(methodName, Constants.ACTIVES_KEY, 0);
        // 获取当前调用方法的RpcStatus
        RpcStatus count = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        // 只有当配置的 actives>0，才会做并发度限流，否则只是简单的计数
        if (max > 0) {
            // 获取超时时间 timeout=1000
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, 0);
            long start = System.currentTimeMillis();
            long remain = timeout;
            // 获取当前被调用方法的活跃数
            int active = count.getActive();
            // 如果活跃数已经大于等于设置的最大并发数 actives=10
            if (active >= max) {
                synchronized (count) {
                    while ((active = count.getActive()) >= max) {
                        try {
                            // 当前线程阻塞，等待被其他线程唤醒（最多阻塞remain时间，第一次最多阻塞超时时间）
                            count.wait(remain);
                        } catch (InterruptedException e) {
                        }
                        // 计算剩余的时间 remain，如果 remain<0，表示超时，抛出异常；否则继续循环，进行下一次当前活跃数是否大约等待设置的最大并发数的判断
                        long elapsed = System.currentTimeMillis() - start;
                        remain = timeout - elapsed;
                        if (remain <= 0) {
                            throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  "
                                    + invoker.getInterface().getName() + ", method: "
                                    + invocation.getMethodName() + ", elapsed: " + elapsed
                                    + ", timeout: " + timeout + ". concurrent invokes: " + active
                                    + ". max concurrent invoke limit: " + max);
                        }
                    }
                }
            }
        }
        try {
            long begin = System.currentTimeMillis();
            // 当前活跃数 + 1
            RpcStatus.beginCount(url, methodName);
            try {
                // 真正调用
                Result result = invoker.invoke(invocation);
                // 正常结束：当前活跃数-1
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, true);
                return result;
            } catch (RuntimeException t) {
                // 发生异常：当前活跃数-1，抛出异常
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, false);
                throw t;
            }
        } finally {
            if (max > 0) {
                synchronized (count) {
                    count.notify(); // 执行结束后，唤醒等待的线程
                }
            }
        }
    }
}
