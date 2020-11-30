package com.vnsun.mvcframework.v1.servlet;

import com.vnsun.mvcframework.annotation.VNAutowired;
import com.vnsun.mvcframework.annotation.VNController;
import com.vnsun.mvcframework.annotation.VNRequestMapping;
import com.vnsun.mvcframework.annotation.VNService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


public class VNDispatcherServlet extends HttpServlet {

	private Map<String,Object> mapping = new HashMap();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doPost(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			doDispatch(req, resp);
		} catch (Exception e) {
			resp.getWriter().write("500 Exception " + Arrays.toString(e.getStackTrace()));
		}
	}

	private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		// 获取请求URL
		String url = req.getRequestURI();
		// 获取上下文路径
		String contextPath = req.getContextPath();
		// 拿到真实地址
		url = url.replace(contextPath, "").replaceAll("/+", "/");
		if (!mapping.containsKey(url)) {
			resp.getWriter().write("404");
			return;
		}
		Method method = (Method) this.mapping.get(url);
		Map<String,String[]> params = req.getParameterMap();
		method.invoke(this.mapping.get(method.getDeclaringClass().getName()),new Object[]{req,resp,params.get("name")[0]});
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		InputStream is = null;
		// 获取扫描类
		Properties configContext = new Properties();
		is = this.getClass().getClassLoader().getResourceAsStream(config.getInitParameter("contextConfigLocation"));
		try {
			// 扫描到的类添加至IOC容器中
			configContext.load(is);
			String scanPackage = configContext.getProperty("scanPackage");
			doScanner(scanPackage);
			for (String className : mapping.keySet()) {
				if (!className.contains(".")) {
					continue;
				}

				Class<?> clazz = Class.forName(className);
				// 判断扫描到的类的类型
				if (clazz.isAnnotationPresent(VNController.class)) {
					mapping.put(className, clazz.newInstance());
					String baseUrl = "";
					if (clazz.isAnnotationPresent(VNRequestMapping.class)){
						VNRequestMapping requestMapping = clazz.getAnnotation(VNRequestMapping.class);
						baseUrl = requestMapping.value();
					}
					Method[] methods = clazz.getMethods();
					for (Method method : methods) {
						if (!method.isAnnotationPresent(VNRequestMapping.class) ) {continue;}
						VNRequestMapping requestMapping = method.getAnnotation(VNRequestMapping.class);
						String url = (baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
						mapping.put(url, method);
						System.out.println("Mapped " + url + "," + method);
					}
				} else if (clazz.isAnnotationPresent(VNService.class)) {
					VNService service = clazz.getAnnotation(VNService.class);
					String beanName = service.value();
					if("".equals(beanName)){beanName = clazz.getName();}
					Object instance = clazz.newInstance();
					mapping.put(className, instance);
					for (Class<?> i : clazz.getInterfaces()) {
						mapping.put(i.getName(),instance);
					}
				} else {
					continue;
				}
			}
			for (Object object : mapping.values()) {
				if(object == null){continue;}
				Class<?> clazz = object.getClass();
				if (clazz.isAnnotationPresent(VNController.class)) {
					Field[] fields = clazz.getDeclaredFields();
					for (Field field : fields) {
						if (field.isAnnotationPresent(VNAutowired.class)) {
							VNAutowired vnAutowired = field.getAnnotation(VNAutowired.class);
							String beanName = vnAutowired.value();
							if ("".equals(beanName)) {
								beanName = field.getType().getName();
							}
							field.setAccessible(true);
							field.set(mapping.get(clazz.getName()),mapping.get(beanName));
						}
					}
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (is !=null) {is.close();}
			} catch(Exception e) {
			}
		}
		System.out.print("GP MVC Framework is init");
	}

	private void doScanner(String scanPackage) throws Exception {
		URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.","/"));
		File classDir = new File(url.getFile());
		for (File file : classDir.listFiles()) {
			if (file.isDirectory()) {
				doScanner(scanPackage + "." + file.getName());
			} else {
				if(!file.getName().endsWith(".class")){continue;}
				String clazzName = (scanPackage + "." + file.getName().replace(".class",""));
				mapping.put(clazzName,null);
			}
		}
	}
}
