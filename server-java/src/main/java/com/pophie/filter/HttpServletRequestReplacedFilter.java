package com.pophie.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * 将原始 HttpServletRequest 替换为可重复读取 body 的 wrapper，使得拦截器/过滤器/Controller 可以多次读取请求体。
 * 用于请求日志中间件读取 body 后业务仍可再读。
 */
public class HttpServletRequestReplacedFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ServletRequest wrapped = request;
        if (request instanceof HttpServletRequest httpRequest) {
            wrapped = new RequestReaderHttpServletRequestWrapper(httpRequest);
        }
        chain.doFilter(wrapped, response);
    }

    @Override
    public void destroy() {
        // no-op
    }
}
