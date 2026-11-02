package com.liming.rag.identity.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liming.rag.identity.api.CurrentUser;
import com.liming.rag.identity.api.Permissions;
import com.liming.rag.identity.application.AccountAdminService;
import com.liming.rag.identity.application.AccountImportService;
import com.liming.rag.shared.UserId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 账号管理，每个操作对应一个权限点（D-038）；新账号与操作人同租户。 */
@RestController
@RequestMapping("/api/users")
@SaCheckLogin
class UserController {

    record CreateUserRequest(String username, String displayName, String password, List<Long> roleIds) {
    }

    record EnabledRequest(boolean enabled) {
    }

    record RolesRequest(List<Long> roleIds) {
    }

    record ResetPasswordRequest(String password) {
    }

    record ImportCheckRequest(List<AccountImportService.Row> rows) {
    }

    record ImportCheckResponse(List<AccountImportService.Problem> problems) {
    }

    record ImportRequest(List<AccountImportService.Row> rows, String password) {
    }

    record ImportResponse(int imported) {
    }

    private final AccountAdminService accounts;
    private final AccountImportService imports;
    private final CurrentUser currentUser;

    UserController(AccountAdminService accounts, AccountImportService imports, CurrentUser currentUser) {
        this.accounts = accounts;
        this.imports = imports;
        this.currentUser = currentUser;
    }

    /** 按关键词、角色、状态（enabled、disabled、locked）筛选，分页（D-039）。 */
    @SaCheckPermission(Permissions.USER_VIEW)
    @GetMapping
    PageView<UserView> list(@RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "roleId", required = false) Long roleId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageView.of(accounts.page(currentUser.tenantId(), keyword, roleId, status, page, size), UserView::of);
    }

    @SaCheckPermission(Permissions.USER_CREATE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@RequestBody CreateUserRequest request) {
        return UserView.of(accounts.create(currentUser.tenantId(), currentUser.userId(), request.username(),
                request.displayName(), request.password(), request.roleIds()));
    }

    @SaCheckPermission(Permissions.USER_DISABLE)
    @PutMapping("/{id}/enabled")
    UserView setEnabled(@PathVariable long id, @RequestBody EnabledRequest request) {
        return UserView.of(accounts.setEnabled(currentUser.tenantId(), currentUser.userId(), new UserId(id),
                request.enabled()));
    }

    @SaCheckPermission(Permissions.USER_ASSIGN_ROLE)
    @PutMapping("/{id}/roles")
    UserView assignRoles(@PathVariable long id, @RequestBody RolesRequest request) {
        return UserView.of(accounts.assignRoles(currentUser.tenantId(), currentUser.userId(), new UserId(id),
                request.roleIds()));
    }

    @SaCheckPermission(Permissions.USER_RESET_PASSWORD)
    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@PathVariable long id, @RequestBody ResetPasswordRequest request) {
        accounts.resetPassword(currentUser.tenantId(), currentUser.userId(), new UserId(id), request.password());
    }

    @SaCheckPermission(Permissions.USER_UPDATE)
    @PostMapping("/{id}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlock(@PathVariable long id) {
        accounts.unlock(currentUser.tenantId(), new UserId(id));
    }

    /** 批量导入的预检：逐行列出问题，不写入（#124）。文件由前端解析成行，后端只收行。 */
    @SaCheckPermission(Permissions.USER_IMPORT)
    @PostMapping("/import/check")
    ImportCheckResponse checkImport(@RequestBody ImportCheckRequest request) {
        return new ImportCheckResponse(imports.check(currentUser.tenantId(), currentUser.userId(), request.rows()));
    }

    /** 批量导入：重新预检，有问题时一条都不写入；导入的账号首次登录必须先改密码。 */
    @SaCheckPermission(Permissions.USER_IMPORT)
    @PostMapping("/import")
    ImportResponse importUsers(@RequestBody ImportRequest request) {
        return new ImportResponse(imports.importAll(currentUser.tenantId(), currentUser.userId(), request.rows(),
                request.password()));
    }
}
