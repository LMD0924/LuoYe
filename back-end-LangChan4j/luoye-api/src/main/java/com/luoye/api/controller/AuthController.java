package com.luoye.api.controller;

import com.luoye.api.dto.LoginDTO;
import com.luoye.api.vo.LoginVO;
import com.luoye.common.response.Result;
import com.luoye.dao.entity.User;
import com.luoye.api.config.JwtService;
import com.luoye.service.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点：登录并签发 JWT。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwt;

    /** JWT 有效期小时数，与令牌实际过期时间保持一致。 */
    @Value("${luoye.security.jwt.exp-hours:1}")
    private long expHours;

    public AuthController(AuthService authService, JwtService jwt) {
        this.authService = authService;
        this.jwt = jwt;
    }

    /**
     * 校验账号密码后返回令牌。
     *
     * @param dto 登录名与密码
     * @return token 及有效期
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        // 密码校验在 service 层完成；通过后由 api 层签发 JWT。
        User user = authService.login(dto.getUsername(), dto.getPassword());
        String token = jwt.issue(user.getId(), user.getUsername());
        return Result.ok(new LoginVO(token, expHours * 3600));
    }
}
