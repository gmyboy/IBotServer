package com.pophie;

import com.pophie.filter.HttpServletRequestReplacedFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public FilterRegistrationBean<HttpServletRequestReplacedFilter> httpServletRequestReplacedRegistration() {
        FilterRegistrationBean<HttpServletRequestReplacedFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new HttpServletRequestReplacedFilter());
        reg.addUrlPatterns("/*");
        reg.setName("httpServletRequestReplacedFilter");
        reg.setOrder(1);
        return reg;
    }
}
