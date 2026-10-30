package com.liming.rag.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.liming.rag.identity.application.AuthService;
import com.liming.rag.identity.web.UserView.MeView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录、退出、当前用户、改自己的密码。令牌放在请求头 {@code Authorization: Bearer <token>}。
 * 这几个接口只要求登录，不要求权限点：必须先改密码的账号也要能用。
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    record LoginRequest(String username, String password) {
    }

    record LoginResponse(String token, MeView user) {
    }

    record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    LoginResponse login(@RequestBody LoginRequest request) {
        var signIn = auth.login(request.username(), request.password());
        return new LoginResponse(signIn.token(), MeView.of(signIn.user()));
    }

    @SaCheckLogin
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout() {
        auth.logout();
    }

    @SaCheckLogin
    @GetMapping("/me")
    MeView me() {
        return MeView.of(auth.me());
    }

    @SaCheckLogin
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@RequestBody ChangePasswordRequest request) {
        auth.changePassword(request.oldPassword(), request.newPassword());
    }
}
