package com.vnsun.mvcframework.v2.servlet;

import com.vnsun.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;


public class VNDispatcherServlet extends HttpServlet {
	//存储aplication.properties的配置内容
	private Properties contextConfig = new Properties();
	//存储所有扫描到的类
	private List<String> classNames = new ArrayList<String>();
	//IOC容器，保存所有实例化对象
	//注册式单例模式
	private Map<String,Object> ioc = new HashMap<String,Object>();
	//保存Contrller中所有Mapping的对应关系
	private Map<String,Method> handlerMapping = new HashMap<String,Method>();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doPost(req, resp);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// 派遣，分发
		try {
			// 委派模式
			doDispatch(req, resp);
		} catch (Exception e) {
			resp.getWriter().write("500 Exception " + Arrays.toString(e.getStackTrace()));
		}
	}

	private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String url = req.getRequestURI();
		String contextPath = req.getContextPath();
		url = url.replaceAll(contextPath,"").replaceAll("/+","/");
		if(!this.handlerMapping.containsKey(url)){
			resp.getWriter().write("404 Not Found!!");
			return;
		}
		Method method = this.handlerMapping.get(url);
		//第一个参数：方法所在的实例
		//第二个参数：调用时所需要的实参
		Map<String,String[]> params = req.getParameterMap();
		//获取方法的形参列表
		Class<?> [] parameterTypes = method.getParameterTypes();
		//保存请求的url参数列表
		Map<String,String[]> parameterMap = req.getParameterMap();
		//保存赋值参数的位置
		Object [] paramValues = new Object[parameterTypes.length];
		//按根据参数位置动态赋值
		for (int i = 0; i < parameterTypes.length; i ++){
			Class parameterType = parameterTypes[i];
			if(parameterType == HttpServletRequest.class){
				paramValues[i] = req;
				continue;
			}else if(parameterType == HttpServletResponse.class){
				paramValues[i] = resp;
				continue;
			}else if(parameterType == String.class){

				//提取方法中加了注解的参数
				Annotation[] [] pa = method.getParameterAnnotations();
				for (int j = 0; j < pa.length ; j ++) {
					for(Annotation a : pa[i]){
						if(a instanceof VNRequestParam){
							String paramName = ((VNRequestParam) a).value();
							if(!"".equals(paramName.trim())){
								String value = Arrays.toString(parameterMap.get(paramName))
										.replaceAll("\\[|\\]","")
										.replaceAll("\\s",",");
								paramValues[i] = value;
							}
						}
					}
				}

			}
		}
		//投机取巧的方式
		//通过反射拿到method所在class，拿到class之后还是拿到class的名称
		//再调用toLowerFirstCase获得beanName
		String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
		method.invoke(ioc.get(beanName), paramValues);
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		// 模板模式
		// 1. 加载配置
		doLoadConfig(config.getInitParameter("contextConfigLocation"));
		// 2. 扫描包下面的类
		doScanner(contextConfig.getProperty("scanPackage"));
		// 3. 由spring初始化相关的类，并注入进IOC容器
		doInstance();
		// 4. 完成依赖注入
		doAutowired();
		// 5. 初始化HandlerMapping
		initHandlerMapping();
		System.out.print("GP MVC Framework is init");
	}

	private void initHandlerMapping() {
		if(ioc.isEmpty()){ return; }
		for (Map.Entry<String, Object> entry : ioc.entrySet()) {
			Class<?> clazz = entry.getValue().getClass();
			if (!clazz.isAnnotationPresent(VNController.class)) {
				continue;
			}
			String baseUrl = "";
			if (clazz.isAnnotationPresent(VNRequestMapping.class)) {
				baseUrl = clazz.getAnnotation(VNRequestMapping.class).value();
			}
			Method[] methods = clazz.getMethods();
			for (Method method : methods) {
				if (!method.isAnnotationPresent(VNRequestMapping.class)) {
					continue;
				}
				String url = ("/" + baseUrl + "/" + method.getAnnotation(VNRequestMapping.class).value())
						.replaceAll("/+", "/");
				handlerMapping.put(url, method);
				System.out.println("Mapped " + url + "," + method);
			}
		}
	}

	private void doAutowired() {
		if(ioc.isEmpty()){ return; }
		for (Map.Entry<String, Object> entry : ioc.entrySet()) {
			// 循环拿到所有类的所有属性，进行属性赋值注入操作
			Field[] fields = entry.getValue().getClass().getDeclaredFields();
			for (Field field : fields) {
				if(!field.isAnnotationPresent(VNAutowired.class)){ continue; }
				VNAutowired autowired = field.getAnnotation(VNAutowired.class);
				String beanName = autowired.value().trim();
				if("".equals(beanName)){
					beanName = field.getType().getName();
				}
				//不管你愿不愿意，强吻
				field.setAccessible(true); //设置私有属性的访问权限
				try {
					//执行注入动作
					field.set(entry.getValue(), ioc.get(beanName));
				} catch (Exception e) {
					e.printStackTrace();
					continue ;
				}
			}
		}
	}

	// 控制反转， 控制权反转给spring控制
	// 工厂模式实现
	private void doInstance() {
		if (classNames.isEmpty()) {
			return;
		}
		try {
			for (String className : classNames) {
				Class<?> clazz = Class.forName(className);
				if (clazz.isAnnotationPresent(VNController.class)) {
					Object instance = clazz.newInstance();
					String beanName = toLowerFirstCase(clazz.getSimpleName());
					ioc.put(beanName, instance);
				} else if (clazz.isAnnotationPresent(VNService.class)) {
					//1、默认的类名首字母小写
					String beanName = toLowerFirstCase(clazz.getSimpleName());
					//2、自定义命名
					VNService service = clazz.getAnnotation(VNService.class);
					if(!"".equals(service.value())){
						beanName = service.value();
					}
					Object instance = clazz.newInstance();
					ioc.put(beanName, instance);
					//3、根据类型注入实现类，投机取巧的方式,注册所有的接口
					for (Class<?> i : clazz.getInterfaces()) {
						if(ioc.containsKey(i.getName())){
							throw new Exception("The beanName is exists!!");
						}
						ioc.put(i.getName(),instance);
					}
				} else {
					continue;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
		}
	}

	/**
	 * 首字母大写
	 * @param simpleName
	 * @return
	 */
	private String toLowerFirstCase(String simpleName) {
		char [] chars = simpleName.toCharArray();
		chars[0] += 32;
		return  String.valueOf(chars);
	}

	private void doScanner(String scanPackage) {
		URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
		File file = new File(url.getFile());

		for (File f : file.listFiles() ) {
			// 是文件夹 递归
			if (f.isDirectory()) {
				doScanner(scanPackage + "." + f.getName());
			} else {
				// 文件只加载class，并放入map中
				if (f.getName().endsWith(".class")) {
					classNames.add(scanPackage + "." + f.getName().replace(".class", ""));
				}
			}
		}
	}

	// 获取配置
	private void doLoadConfig(String contextConfigLocation) {
		InputStream fis = null;
		try {
			fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
			//1、加载至Properties配置类中
			contextConfig.load(fis);
		}catch(Exception e){
			e.printStackTrace();
		}finally{
			try {
				if(null != fis){fis.close();}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}
