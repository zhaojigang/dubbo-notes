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
package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.DemoService;
import com.alibaba.dubbo.rpc.service.GenericService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.HashMap;
import java.util.Map;

public class Consumer {
    public static void main(String[] args) {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-consumer.xml"});
        context.start();

        normalInvoke(context);
//        genericInvoke(context);
    }

    /**
     * 通常姿势：需要引入 api jar 包
     */
    private static void normalInvoke(ClassPathXmlApplicationContext context) {
        DemoService demoService = (DemoService) context.getBean("demoService"); // get remote service proxy

        while (true) {
            try {
                Thread.sleep(1000);
                String hello = demoService.sayHello("world"); // call remote method
                System.out.println(hello); // get result
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    /**
     * 泛化姿势：不需要引入 api jar 包
     */
    private static void genericInvoke(ClassPathXmlApplicationContext context) {
        genericInvokePOJO(context);
    }

    /**
     * 基本类型以及 Date,List,Map 等不需要转换，直接调用
     */
    private static void genericInvokeBasic(ClassPathXmlApplicationContext context){
        GenericService demoService = (GenericService) context.getBean("demoService"); // get remote service proxy

        while (true) {
            try {
                Thread.sleep(1000);
                String hello = (String) demoService.$invoke("sayHello", new String[]{"java.lang.String"}, new Object[]{"world"});
                System.out.println(hello); // get result
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    /**
     * 用 Map 表示 POJO 参数，如果返回值为 POJO 也将自动转成 Map
     */
    private static void genericInvokePOJO(ClassPathXmlApplicationContext context){
        GenericService testGenericService = (GenericService) context.getBean("testGenericService"); // get remote service proxy

        while (true) {
            try {
                Thread.sleep(1000);

                String[] parameterTypes = new String[]{"com.alibaba.dubbo.demo.User"};
                Map<String, Object> params = new HashMap<String, Object>();
                params.put("name", "xiaohei");
                params.put("age", 17);

                Map<String, Object> addressParam = new HashMap<String, Object>();
                addressParam.put("location", "xxx");
                params.put("address", addressParam);

                // 如果返回POJO将自动转成Map
                Object userObj = testGenericService.$invoke("getByUser", parameterTypes, new Object[]{params});
                System.out.println(userObj); // get result
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }
}
