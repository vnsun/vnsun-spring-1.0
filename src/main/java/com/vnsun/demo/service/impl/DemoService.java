package com.vnsun.demo.service.impl;

import com.vnsun.demo.service.IDemoService;
import com.vnsun.mvcframework.annotation.VNService;

/**
 * 核心业务逻辑
 */
@VNService
public class DemoService implements IDemoService {

	public String get(String name) {
		return "My name is " + name;
	}

}
