package com.luoye.api.vo;

/**
 * 登录响应 VO。
 *
 * @param token JWT 令牌
 * @param expiresIn 有效期（秒）
 */
public record LoginVO(String token, long expiresIn) {
}
