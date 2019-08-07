package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.demo.Address;
import com.alibaba.dubbo.demo.TestGenericService;
import com.alibaba.dubbo.demo.User;

public class TestGenericServiceImpl implements TestGenericService {
    @Override
    public User getByUser(User user) {
        User u = new User();
        u.setName("小白");
        u.setAge(18);
        Address address = new Address();
        address.setLocation("杭州");
        u.setAddress(address);
        return u;
    }
}
