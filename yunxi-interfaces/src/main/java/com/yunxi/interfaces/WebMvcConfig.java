package com.yunxi.interfaces;


import com.yunxi.interfaces.security.JwtInterceptor;
import com.yunxi.interfaces.security.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 JWT 拦截器，排除登录接口。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    public WebMvcConfig(JwtUtil jwtUtil, StringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtInterceptor(jwtUtil, redisTemplate))
                .addPathPatterns("/api/**")              // 拦截所有 API
                .excludePathPatterns(
                        "/api/auth/**",                  // 登录/登出不需要 Token
                        "/swagger-ui/**",                // Knife4j 文档不需要
                        "/v3/api-docs/**",               // API 文档不需要
                        "/doc.html"                      // Knife4j 页面不需要
                );
    }
}
