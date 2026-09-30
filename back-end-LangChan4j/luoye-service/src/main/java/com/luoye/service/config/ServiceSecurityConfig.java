package com.luoye.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 服务层安全相关 Bean。
 *
 * <p>密码编码器定义在 service 层，供认证校验与首启建号共用；
 * 避免 service 反向依赖 api 模块。
 */
@Configuration
public class ServiceSecurityConfig {

    /** @return BCrypt 密码编码器，哈希内含随机盐 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
