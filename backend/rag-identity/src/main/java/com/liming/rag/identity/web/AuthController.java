package com.liming.rag.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.liming.rag.identity.application.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 登录、退出、当前用户。令牌放在请求头 {@code Authorization: Bearer <token>}。 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    record LoginRequest(String username, String password) {
    }

    record LoginResponse(String token, UserView user) {
    }

    private final AccountService accounts;

    AuthController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/login")
    LoginResponse login(@RequestBody LoginRequest request) {
        var signIn = accounts.login(request.username(), request.password());
        return new LoginResponse(signIn.token(), UserView.of(signIn.account()));
    }

    @SaCheckLogin
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout() {
        accounts.logout();
    }

    @SaCheckLogin
    @GetMapping("/me")
    UserView me() {
        return UserView.of(accounts.me());
    }
}
