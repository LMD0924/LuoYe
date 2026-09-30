package com.luoye.api.controller;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.luoye.api.config.JwtService;
import com.luoye.dao.repository.ChatRepository;

/**
 * 单用户登录端点，依赖用户仓储、密码编码器和 JWT 服务。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 登录请求；password 为本次待校验密码，不在此处存储明文。 */
    public record LoginRequest(String username, String password) {}

    private final ChatRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    /**
     * @param repo 用户仓储
     * @param encoder 与账号初始化一致的密码哈希编码器
     * @param jwt 令牌签发服务
     */
    public AuthController(ChatRepository repo, PasswordEncoder encoder, JwtService jwt) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /**
     * 校验密码后返回令牌。
     *
     * @param request 登录名与密码
     * @return token 及 expiresIn 字段
     * @throws org.springframework.web.server.ResponseStatusException 账号或密码不匹配
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        var account = repo.account(request.username()).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (account.passwordHash() == null
                || !encoder.matches(request.password(), account.passwordHash())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        // 已知限制：响应固定报告 3600 秒，令牌实际过期时间由 JwtService 配置决定。
        return Map.of("token", jwt.issue(account.id(), account.username()), "expiresIn", 3600);
    }
}