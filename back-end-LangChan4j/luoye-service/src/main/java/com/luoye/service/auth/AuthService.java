package com.luoye.service.auth;

import com.luoye.dao.entity.User;

/**
 * 认证服务。
 *
 * <p>负责账号查询与密码校验；JWT 签发由 API 层完成（依赖方向约束）。
 */
public interface AuthService {

    /**
     * 校验用户名与密码。
     *
     * @param username 登录名
     * @param password 明文密码
     * @return 校验通过的用户实体
     * @throws com.luoye.common.exception.BusinessException 账号不存在或密码错误（401）
     */
    User login(String username, String password);
}
