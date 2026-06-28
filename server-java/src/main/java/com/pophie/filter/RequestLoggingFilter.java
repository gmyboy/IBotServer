package com.pophie.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求日志中间件，对应 main.py 的 log_requests。
 * 记录 --> METHOD path client qs headers body(截断4000) 与 <-- status in Xms；跳过 / 与 /static。
 * 依赖 HttpServletRequestReplacedFilter(order=1) 先包裹请求体使其可重复读取。
 */
@Component
@Order(2)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("pophie");
    private static final int MAX_BODY_LOG = 4000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/".equals(path) || path.startsWith("/static")) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        String client = request.getRemoteAddr() + ":" + request.getRemotePort();
        String qs = request.getQueryString();

        String bodyText = "";
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            bodyText = sb.toString();
        } catch (Exception ignored) {
        }

        log.info("--> {} {} client={} qs={} headers={ct={}, len={}}\n  body={}",
                request.getMethod(), path, client, qs == null ? "{}" : qs,
                request.getContentType(), request.getHeader("content-length"),
                bodyText.isEmpty() ? "<empty>" : truncate(bodyText));

        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            log.error("XX {} {} 异常: {}", request.getMethod(), path, e.getMessage());
            throw e;
        }
        long dur = System.currentTimeMillis() - start;
        log.info("<-- {} {} status={} in {}ms", request.getMethod(), path, response.getStatus(), dur);
    }

    private String truncate(String s) {
        if (s.length() <= MAX_BODY_LOG) return s;
        return s.substring(0, MAX_BODY_LOG) + "... <截断, 共 " + s.length() + " 字符>";
    }
}
