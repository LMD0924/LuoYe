package com.luoye.api.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;

/**
 * HTTP 访问控制配置，依赖 JWT 过滤器恢复身份。
 *
 * <p>登录和预留的健康检查路径允许匿名访问，其他路径要求认证；不创建 HTTP Session。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** @param jwt 令牌校验服务 @return Bearer 认证过滤器 */
    @Bean
    JwtAuthFilter jwtAuthFilter(JwtService jwt) {
        return new JwtAuthFilter(jwt);
    }

    /**
     * 构建无状态认证过滤链；路径放行规则本身不创建健康检查端点。
     *
     * @param http Spring Security 构建器
     * @param jwtFilter 从 Authorization 请求头解析用户身份的过滤器
     * @return 安全过滤链
     * @throws Exception 过滤链配置失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtFilter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/actuator/health", "/error")
                        .permitAll().anyRequest().authenticated())
                .addFilterBefore(jwtFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}