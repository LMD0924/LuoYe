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
        // 使用请求头 Bearer 令牌认证，关闭 CSRF 校验；STATELESS 表示不在 HTTP Session 保存身份。
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 仅这些路径允许匿名请求；其他请求必须先被 JWT 过滤器设置为已认证。
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/actuator/health", "/error")
                        .permitAll().anyRequest().authenticated())
                // 先解析 JWT，再进入默认用户名/密码过滤器所在的位置及后续访问控制。
                .addFilterBefore(jwtFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}