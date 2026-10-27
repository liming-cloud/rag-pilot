package com.liming.rag.identity.web;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Roles;
import com.liming.rag.identity.application.AccountService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 账号管理，只有管理员能用；新账号与管理员同租户。 */
@RestController
@RequestMapping("/api/users")
@SaCheckRole(Roles.ADMIN)
class UserController {

    record CreateUserRequest(String username, String displayName, String password, String role) {
    }

    private final AccountService accounts;
    private final CurrentUser currentUser;

    UserController(AccountService accounts, CurrentUser currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<UserView> list() {
        return accounts.list(currentUser.tenantId()).stream().map(UserView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@RequestBody CreateUserRequest request) {
        return UserView.of(accounts.create(currentUser.tenantId(), request.username(), request.displayName(),
                request.password(), request.role()));
    }
}
