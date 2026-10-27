package com.liming.rag;

import cn.dev33.satoken.interceptor.SaInterceptor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
        registry.addInterceptor(new SaInterceptor() {
            /**
             * 流式问答结束时 Spring 会做一次 ASYNC 再分派，那时 Sa-Token 上下文没有初始化；
             * 登录和角色在最初的请求里已经校验过，这里直接放行。
             */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                return request.getDispatcherType() == DispatcherType.ASYNC
                        || super.preHandle(request, response, handler);
            }
        }).addPathPatterns("/api/**");
    }
}
