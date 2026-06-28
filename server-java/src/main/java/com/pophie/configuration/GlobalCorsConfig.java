package com.pophie.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class GlobalCorsConfig {

    private CorsConfiguration buildCorsConfig() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 与原 FastAPI CORSMiddleware(allow_origins=["*"]) 一致
        cfg.addAllowedOrigin("*");
        cfg.addAllowedHeader("*");
        cfg.addAllowedMethod("*");
        cfg.setMaxAge(3600L);
        return cfg;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfig());
        return new CorsFilter(source);
    }
}
