package com.liming.rag;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 打开 Sa-Token 注解校验。接口是否需要登录、需要什么角色，只看控制器上的 {@code @SaCheckLogin} / {@code @SaCheckRole}。
 */
@Configuration
class SaTokenConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/api/**");
    }
}
