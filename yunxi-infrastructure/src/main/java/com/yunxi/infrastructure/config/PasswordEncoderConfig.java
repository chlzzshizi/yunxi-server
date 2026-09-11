package com.yunxi.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码编码器。
 *
 * 做成 Bean 而不是在每个用到处 new：BCrypt 的强度参数（cost）和实例复用
 * 应该是全应用一致的，散着 new 就等于把它们交给"谁还记得当初怎么写的"。
 *
 * 放在 infrastructure：BCrypt 是实现细节，application 只按类型注入使用。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
