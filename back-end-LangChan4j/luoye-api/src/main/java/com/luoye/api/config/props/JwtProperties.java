package com.luoye.api.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（§9 单用户认证最小方案）。
 * secret 由环境变量提供，不会自动生成；JwtService 当前通过 @Value 独立读取同名配置。
 * record 自动提供 secret()、expHours() 只读访问方法，组件由入口类的属性扫描注册。
 */
@ConfigurationProperties(prefix = "luoye.security.jwt")
public record JwtProperties(String secret, Integer expHours) {

    /** record 的紧凑构造器：绑定时未提供 expHours 则使用一小时，不在此校验密钥。 */
    public JwtProperties {
        if (expHours == null) {
            expHours = 1;
        }
    }
}