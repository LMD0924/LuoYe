package com.luoye.service.auth.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.luoye.common.exception.BusinessException;
import com.luoye.common.response.ResultCode;
import com.luoye.dao.entity.User;
import com.luoye.dao.mapper.UserMapper;
import com.luoye.service.auth.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User login(String username, String password) {
        // 软删除账号不参与认证；用户名与删除时间由参数绑定，不拼接 SQL。
        User user = userMapper.selectOne(Wrappers.lambdaQuery(User.class)
                .eq(User::getUsername, username)
                .isNull(User::getDeletedAt));
        // 账号不存在、未设置密码或密码不匹配，统一返回 401 与相同提示，避免暴露账号是否存在。
        if (user == null
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }
}
