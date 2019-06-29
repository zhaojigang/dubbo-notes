package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Protocol;

/**
 * @author zhaojigang
 * @date 2019/6/28
 */
public class TestMain {
    public static void main(String[] args) {
        ExtensionLoader<Protocol> loader = ExtensionLoader.getExtensionLoader(Protocol.class);
        Protocol protocol = loader.getExtension("dubbo");
        Protocol protocol1 = loader.getAdaptiveExtension();
        System.out.println(protocol + "/" + protocol1);
    }
}
