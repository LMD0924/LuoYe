package com.luoye.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.luoye.dao.repository.ChatRepository;

/**
 * 单用户账号初始化配置，依赖 ChatRepository 和 BCrypt 密码编码器。
 *
 * <p>仅在配置了初始密码且账号不存在时创建账号，不覆盖已有密码。
 *
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
@Configuration
public class AuthConfig {

    /** @return 用于首启密码保存和登录密码匹配的 BCrypt 编码器 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 构建启动时执行的账号初始化任务。
     *
     * @param repo 用户查询和写入仓储
     * @param encoder 密码哈希编码器
     * @param username 首启登录名
     * @param password 环境变量提供的首启密码；为空时跳过初始化
     * @return 启动后执行的初始化回调
     */
    @Bean
    CommandLineRunner bootstrapAccount(
            ChatRepository repo,
            PasswordEncoder encoder,
            @Value("\u0024{luoye.auth.username:admin}") String username,
            @Value("\u0024{luoye.auth.password:}") String password) {
        return args -> {
            if (password != null && !password.isBlank() && repo.account(username).isEmpty()) {
                repo.createAccount(username, encoder.encode(password));
            }
        };
    }
}