package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Protocol;

/**
 * @author zhaojigang
 * @date 2019/6/28
 */
public class TestMain {
    public static void main(String[] args) {
        // 1. 获取 Protocol 的扩展加载器 ExtensionLoader
        ExtensionLoader<Protocol> loader = ExtensionLoader.getExtensionLoader(Protocol.class);
        // 2. 获取 DubboProtocol 实例
        Protocol dubboProtocol = loader.getExtension("dubbo");
        // 3. 获取扩展适配类 Protocol$Adaptive
        Protocol adaptiveProtocol = loader.getAdaptiveExtension();
    }
}
