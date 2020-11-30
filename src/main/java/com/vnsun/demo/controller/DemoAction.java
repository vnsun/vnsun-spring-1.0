package com.vnsun.demo.controller;


import com.vnsun.demo.service.IDemoService;
import com.vnsun.mvcframework.annotation.VNAutowired;
import com.vnsun.mvcframework.annotation.VNController;
import com.vnsun.mvcframework.annotation.VNRequestMapping;
import com.vnsun.mvcframework.annotation.VNRequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@VNController
@VNRequestMapping("/demo")
public class DemoAction {

  	@VNAutowired
	private IDemoService demoService;

	@VNRequestMapping("/query")
	public void query(HttpServletRequest req, HttpServletResponse resp,
					  @VNRequestParam("name") String name){
		String result = demoService.get(name);
//		String result = "My name is " + name;
		try {
			resp.getWriter().write(result);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@VNRequestMapping("/add")
	public void add(HttpServletRequest req, HttpServletResponse resp,
					@VNRequestParam("a") Integer a, @VNRequestParam("b") Integer b){
		try {
			resp.getWriter().write(a + "+" + b + "=" + (a + b));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@VNRequestMapping("/remove")
	public void remove(HttpServletRequest req,HttpServletResponse resp,
					   @VNRequestParam("id") Integer id){
	}

}
