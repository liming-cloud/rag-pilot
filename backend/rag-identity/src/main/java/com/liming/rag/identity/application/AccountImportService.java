package com.liming.rag.identity.application;

import cn.dev33.satoken.secure.BCrypt;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号批量导入（#124、D-046）：先预检全部行、逐行列出问题，有任何一行有问题就一条都不导；
 * 全部通过时用统一初始密码建号，所有导入的账号首次登录必须先改密码（D-038）。已存在的用户名只报错、不更新。
 * 角色按名字匹配，同样执行防越权分配（{@link AssignmentRules}）。
 */
@Service
public class AccountImportService {

    /** 一次最多导入的行数。 */
    public static final int MAX_ROWS = 500;

    private final Accounts accounts;
    private final Roles roles;
    private final AccessService access;

    AccountImportService(Accounts accounts, Roles roles, AccessService access) {
        this.accounts = accounts;
        this.roles = roles;
        this.access = access;
    }

    /** 文件里的一行；line 是文件里的行号（表头是第 1 行），用于报错定位。 */
    public record Row(int line, String username, String displayName, List<String> roleNames) {

        public Row {
            username = username == null ? "" : username.strip();
            displayName = displayName == null ? "" : displayName.strip();
            roleNames = roleNames == null ? List.of() : roleNames.stream().map(String::strip)
                    .filter(name -> !name.isEmpty()).distinct().toList();
        }
    }

    public record Problem(int line, String message) {
    }

    /** 预检：返回全部问题，不写入任何数据。 */
    @Transactional(readOnly = true)
    public List<Problem> check(TenantId tenantId, UserId actor, List<Row> rows) {
        checkSize(rows);
        return problems(tenantId, actor, rows, null);
    }

    /**
     * 导入：重新预检（含初始密码与各用户名的规则），有问题时一条都不写入。同一批账号的初始密码相同，
     * 只算一次哈希（BCrypt 每次几十毫秒，500 行逐个算要半分钟）；首次登录必须改密码，改后各自不同。
     *
     * @return 导入的账号数
     */
    @Transactional
    public int importAll(TenantId tenantId, UserId actor, List<Row> rows, String password) {
        checkSize(rows);
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw invalid("初始密码必须是 8 到 64 个字符");
        }
        var problems = problems(tenantId, actor, rows, password);
        if (!problems.isEmpty()) {
            throw invalid("导入文件有 " + problems.stream().map(Problem::line).distinct().count()
                    + " 行有问题，请重新检查后再导入");
        }
        var byName = rolesByName(tenantId);
        var hash = BCrypt.hashpw(password, BCrypt.gensalt());
        try {
            for (var row : rows) {
                var created = accounts.insert(new Account(null, tenantId, row.username(), row.displayName(), hash,
                        true, true, 0, null));
                roles.setUserRoles(created.id(), row.roleNames().stream().map(name -> byName.get(name).id()).toList());
            }
        } catch (DuplicateKeyException e) {
            // 预检之后、导入之前别人建了同名账号：整批回滚。
            throw new IdentityException(IdentityException.Reason.CONFLICT, "有用户名刚被别人使用，请重新检查后再导入");
        }
        return rows.size();
    }

    private List<Problem> problems(TenantId tenantId, UserId actor, List<Row> rows, String password) {
        var rules = rulesFor(actor);
        var byName = rolesByName(tenantId);
        var existing = accounts.existingUsernames(rows.stream().map(Row::username).toList());
        var firstLine = new HashMap<String, Integer>();
        var problems = new ArrayList<Problem>();
        for (var row : rows) {
            var messages = new ArrayList<String>();
            var earlier = firstLine.putIfAbsent(row.username(), row.line());
            if (!AccountAdminService.USERNAME.matcher(row.username()).matches()) {
                messages.add("用户名必须是 3 到 50 个字母、数字或 _ . -");
            } else if (earlier != null) {
                messages.add("与第 " + earlier + " 行的用户名重复");
            } else if (existing.contains(row.username())) {
                messages.add("用户名已存在");
            }
            if (row.displayName().isEmpty() || row.displayName().length() > 50) {
                messages.add("显示名必须是 1 到 50 个字符");
            }
            if (row.roleNames().isEmpty()) {
                messages.add("至少要有一个角色");
            }
            for (var name : row.roleNames()) {
                var role = byName.get(name);
                var problem = role == null ? "没有叫“" + name + "”的角色" : rules.assignProblem(role);
                if (problem != null) {
                    messages.add(problem);
                }
            }
            if (password != null && password.equalsIgnoreCase(row.username())) {
                messages.add("初始密码不能与用户名相同");
            }
            messages.forEach(message -> problems.add(new Problem(row.line(), message)));
        }
        return problems;
    }

    private Map<String, Role> rolesByName(TenantId tenantId) {
        return roles.list(tenantId).stream()
                .collect(Collectors.toMap(Role::name, Function.identity(), (first, second) -> first));
    }

    private AssignmentRules rulesFor(UserId actor) {
        var account = accounts.find(actor.value())
                .orElseThrow(() -> new IdentityException(IdentityException.Reason.NOT_FOUND, "账号不存在"));
        return new AssignmentRules(new LinkedHashSet<>(access.permissionsOf(account)),
                access.superAdmin(account.id()));
    }

    private static void checkSize(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            throw invalid("没有要导入的账号");
        }
        if (rows.size() > MAX_ROWS) {
            throw invalid("一次最多导入 " + MAX_ROWS + " 个账号");
        }
    }

    private static IdentityException invalid(String message) {
        return new IdentityException(IdentityException.Reason.INVALID, message);
    }
}
