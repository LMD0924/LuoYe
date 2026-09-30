package com.luoye.api.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKey;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与验证服务，依赖 JJWT 和环境变量提供的对称密钥。
 *
 * <p>令牌 subject 保存用户 UUID。密钥缺失时启动失败，不在本类中自动生成密钥。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationHours;

    /**
     * 加载 JWT 签名配置。
     *
     * @param secret 至少 32 字符的签名密钥
     * @param expirationHours 有效期小时数
     * @throws IllegalStateException 密钥为空或字符数不足
     */
    public JwtService(
            @Value("\u0024{luoye.security.jwt.secret:}") String secret,
            @Value("\u0024{luoye.security.jwt.exp-hours:1}") long expirationHours) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("LUOYE_JWT_SECRET must be at least 32 characters");
        }
        // 将原始密钥按 UTF-8 编码为 HMAC 密钥；不是对配置值做 Base64 解码。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationHours = expirationHours;
    }

    /**
     * 为已通过密码校验的用户签发令牌。
     *
     * @param userId 用户 UUID
     * @param username 登录名，附在自定义 claim 中
     * @return 带签名和有效期的 JWT
     */
    public String issue(UUID userId, String username) {
        // 同一时间点用于签发时间和到期时间，配置中的小时数转换成秒。
        Instant now = Instant.now();
        // subject 是后续权限校验使用的用户 ID；username 只是附加信息，JWT 签名不等于加密。
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationHours * 3600)))
                .signWith(key)
                .compact();
    }

    /**
     * 验证签名及有效期后读取用户 UUID。
     *
     * @param token 不含 Bearer 前缀的 JWT
     * @return 令牌中的用户 UUID
     * @throws JwtException 令牌过期、签名不匹配或结构无效
     * @throws IllegalArgumentException subject 不是 UUID 或输入非法
     */
    public UUID subject(String token) {
        // 先校验整个已签名令牌再读取 subject，不能直接信任客户端提供的载荷。
        return UUID.fromString(Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject());
    }
}