package com.luoye.api.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 Bearer 令牌恢复当前请求的用户身份，依赖 JwtService 验证签名与有效期。
 *
 * <p>无令牌或无效令牌时不设置身份，是否允许请求继续由安全规则判断。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    /** @param jwt 令牌签发与校验服务 */
    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    /**
     * 校验令牌并写入 Spring Security 的请求上下文。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain 后续过滤器链
     * @throws ServletException 后续过滤器处理异常
     * @throws IOException 请求或响应读写失败
     */
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = request.getHeader("Authorization");
        if (value != null && value.startsWith("Bearer ")) {
            try {
                var id = jwt.subject(value.substring(7));
                var auth = new UsernamePasswordAuthenticationToken(
                        id.toString(), null, AuthorityUtils.NO_AUTHORITIES);
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(auth);
            } catch (RuntimeException ignored) {
                // 验证失败不写入身份，继续由安全过滤链执行访问控制。
            }
        }
        chain.doFilter(request, response);
    }
}