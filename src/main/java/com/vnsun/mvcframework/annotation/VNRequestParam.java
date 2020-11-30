package com.vnsun.mvcframework.annotation;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface VNRequestParam {
    String value() default "";
}
