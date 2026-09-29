package com.luoye.api.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（§9 单用户认证最小方案）。
 * secret 支持环境变量显式指定；未指定时在密钥管理组件中首次启动自动生成并持久化（M1 实现）。
 */
@ConfigurationProperties(prefix = "luoye.security.jwt")
public record JwtProperties(String secret, Integer expHours) {

    public JwtProperties {
        if (expHours == null) {
            expHours = 1;
        }
    }
}