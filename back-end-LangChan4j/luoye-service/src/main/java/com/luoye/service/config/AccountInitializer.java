package com.luoye.service.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.luoye.dao.entity.User;
import com.luoye.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 单用户账号初始化器。
 *
 * <p>仅在配置了初始密码且账号不存在时创建账号，不覆盖已有密码。
 * 从 API 层 AuthConfig 迁移到 service 层，数据访问不跨出 service。
 */
@Component
@RequiredArgsConstructor
public class AccountInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${luoye.auth.username:admin}")
    private String username;

    @Value("${luoye.auth.password:}")
    private String password;

    @Override
    public void run(String... args) {
        if (password == null || password.isBlank()) {
            return;
        }
        // 已有同名（含未软删）账号则跳过，重启不覆盖密码。
        Long count = userMapper.selectCount(Wrappers.lambdaQuery(User.class)
                .eq(User::getUsername, username)
                .isNull(User::getDeletedAt));
        if (count != null && count > 0) {
            return;
        }
        // BCrypt 生成含随机盐的哈希后入库；主键由 MP 分配，created_at 由 DB 默认。
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        userMapper.insert(user);
    }
}
