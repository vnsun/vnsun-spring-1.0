package com.vnsun.mvcframework.v3.servlet;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class VNDispatcherServlet extends HttpServlet {

	private static final String LOCATION = "contextConfigLocation";
	//存储aplication.properties的配置内容
	private Properties contextConfig = new Properties();
	//存储所有扫描到的类
	private List<String> classNames = new ArrayList<String>();
	//IOC容器，保存所有实例化对象
	//注册式单例模式
	private Map<String,Object> ioc = new HashMap<String,Object>();
	//保存所有的Url和方法的映射关系
	private List<Handler> handlerMapping = new ArrayList<Handler>();

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
		Handler handler = getHandler(req);
		if(handler == null){
			//如果没有匹配上，返回404错误
			resp.getWriter().write("404 Not Found");
			return;
		}
		//获取方法的参数列表
		Class<?> [] paramTypes = handler.method.getParameterTypes();

		//保存所有需要自动赋值的参数值
		Object [] paramValues = new Object[paramTypes.length];


		Map<String,String[]> params = req.getParameterMap();
		for (Map.Entry<String, String[]> param : params.entrySet()) {
			String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");

			//如果找到匹配的对象，则开始填充参数值
			if(!handler.paramIndexMapping.containsKey(param.getKey())){continue;}
			int index = handler.paramIndexMapping.get(param.getKey());
			paramValues[index] = convert(paramTypes[index],value);
		}


		//设置方法中的request和response对象
		int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
		paramValues[reqIndex] = req;
		int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
		paramValues[respIndex] = resp;

		handler.method.invoke(handler.controller, paramValues);
	}

	private Handler getHandler(HttpServletRequest req) throws Exception{
		if(handlerMapping.isEmpty()){ return null; }

		String url = req.getRequestURI();
		String contextPath = req.getContextPath();
		url = url.replace(contextPath, "").replaceAll("/+", "/");

		for (Handler handler : handlerMapping) {
			try{
				Matcher matcher = handler.pattern.matcher(url);
				//如果没有匹配上继续下一个匹配
				if(!matcher.matches()){ continue; }

				return handler;
			}catch(Exception e){
				throw e;
			}
		}
		return null;
	}

	//url传过来的参数都是String类型的，HTTP是基于字符串协议
	//只需要把String转换为任意类型就好
	private Object convert(Class<?> type,String value){
		if(Integer.class == type){
			return Integer.valueOf(value);
		}
		//如果还有double或者其他类型，继续加if
		//这时候，我们应该想到策略模式了
		//在这里暂时不实现，希望小伙伴自己来实现
		return value;
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
				Pattern pattern = Pattern.compile(url);
				handlerMapping.add(new Handler(pattern,entry.getValue(),method));
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

	/**
	 * Handler记录Controller中的RequestMapping和Method的对应关系
	 * @author Tom
	 * 内部类
	 */
	private class Handler{

		protected Object controller;	//保存方法对应的实例
		protected Method method;		//保存映射的方法
		protected Pattern pattern;
		protected Map<String,Integer> paramIndexMapping;	//参数顺序

		/**
		 * 构造一个Handler基本的参数
		 * @param controller
		 * @param method
		 */
		protected Handler(Pattern pattern,Object controller,Method method){
			this.controller = controller;
			this.method = method;
			this.pattern = pattern;

			paramIndexMapping = new HashMap<String,Integer>();
			putParamIndexMapping(method);
		}

		private void putParamIndexMapping(Method method){
			//提取方法中加了注解的参数
			Annotation [] [] pa = method.getParameterAnnotations();
			for (int i = 0; i < pa.length ; i ++) {
				for(Annotation a : pa[i]){
					if(a instanceof VNRequestParam){
						String paramName = ((VNRequestParam) a).value();
						if(!"".equals(paramName.trim())){
							paramIndexMapping.put(paramName, i);
						}
					}
				}
			}

			//提取方法中的request和response参数
			Class<?> [] paramsTypes = method.getParameterTypes();
			for (int i = 0; i < paramsTypes.length ; i ++) {
				Class<?> type = paramsTypes[i];
				if(type == HttpServletRequest.class ||
						type == HttpServletResponse.class){
					paramIndexMapping.put(type.getName(),i);
				}
			}
		}
	}

}
