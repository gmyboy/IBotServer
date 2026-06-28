package com.pophie.configuration;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * sa-token 拦截器注册。
 * 与原 Python 行为对齐：业务接口全部公开，仅管理后台 /api/admin/** 需要登录。
 * 放行：
 *   /api/admin/login   管理员登录入口（校验 server.admin_token 后签发 token）
 *   其余所有路径（业务接口 /api/**、静态前端 /、/admin、/static/**、/error、/actuator/**）
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter
                        .match("/api/admin/**")
                        .notMatch("/api/admin/login")
                        .check(r -> StpUtil.checkLogin())))
                .addPathPatterns("/**");
    }
}
