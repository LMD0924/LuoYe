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
        // 只读取 Authorization 请求头；这里不从 Cookie 或 URL 参数接收令牌。
        String value = request.getHeader("Authorization");
        if (value != null && value.startsWith("Bearer ")) {
            try {
                // 去掉固定的七字符 Bearer 前缀，再验证签名、过期时间及 subject 格式。
                var id = jwt.subject(value.substring(7));
                // 三参数构造器生成已认证身份；principal 是用户 UUID 字符串，不附加角色权限。
                var auth = new UsernamePasswordAuthenticationToken(
                        id.toString(), null, AuthorityUtils.NO_AUTHORITIES);
                // 写入当前请求的安全上下文，控制器的 Authentication 参数由此获得身份。
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(auth);
            } catch (RuntimeException ignored) {
                // 验证失败不写入身份，继续由安全过滤链执行访问控制。
            }
        }
        // 有无合法令牌都会进入后续过滤器；受保护路径由 SecurityConfig 决定是否放行。
        chain.doFilter(request, response);
    }
}