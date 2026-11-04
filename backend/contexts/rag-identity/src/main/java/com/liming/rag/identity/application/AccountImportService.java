package com.liming.rag.identity.application;

import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
    private final PasswordHasher passwords;
    private final AuditTrail audit;

    AccountImportService(Accounts accounts, Roles roles, AccessService access, PasswordHasher passwords, AuditTrail audit) {
        this.audit = audit;
        this.accounts = accounts;
        this.roles = roles;
        this.access = access;
        this.passwords = passwords;
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
     * 只算一次摘要（BCrypt 每次几十毫秒，500 行逐个算要半分钟）；首次登录必须改密码，改后各自不同。
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
        var hash = passwords.hash(password);
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
        var names = rows.stream().map(Row::username).limit(20).toList();
        audit.record(tenantId, actor, "ACCOUNTS_IMPORTED", "ACCOUNT", "-", "批量导入 " + rows.size() + " 个账号："
                + String.join("、", names) + (rows.size() > names.size() ? " 等" : ""));
        return rows.size();
    }

    private List<Problem> problems(TenantId tenantId, UserId actor, List<Row> rows, String password) {
        var rules = access.rulesFor(actor);
        var byName = rolesByName(tenantId);
        var existing = accounts.existingUsernames(rows.stream().map(Row::username).toList());
        var firstLine = new HashMap<String, Integer>();
        var problems = new ArrayList<Problem>();
        for (var row : rows) {
            var messages = new ArrayList<String>();
            var earlier = firstLine.putIfAbsent(row.username(), row.line());
            addIfPresent(messages, usernameProblem(row.username(), earlier, existing));
            addIfPresent(messages, AccountRules.displayNameProblem(row.displayName()));
            messages.addAll(roleProblems(row.roleNames(), byName, rules));
            if (password != null && password.equalsIgnoreCase(row.username())) {
                messages.add("初始密码不能与用户名相同");
            }
            messages.forEach(message -> problems.add(new Problem(row.line(), message)));
        }
        return problems;
    }

    /** 用户名本身的规则优先；合规时再看是否与前面的行重复、是否已有同名账号。 */
    private static String usernameProblem(String username, @Nullable Integer earlierLine, Set<String> existing) {
        var problem = AccountRules.usernameProblem(username);
        if (problem != null) {
            return problem;
        }
        if (earlierLine != null) {
            return "与第 " + earlierLine + " 行的用户名重复";
        }
        return existing.contains(username) ? "用户名已存在" : null;
    }

    private static List<String> roleProblems(List<String> roleNames, Map<String, Role> byName,
            AssignmentRules rules) {
        if (roleNames.isEmpty()) {
            return List.of("至少要有一个角色");
        }
        var problems = new ArrayList<String>();
        for (var name : roleNames) {
            var role = byName.get(name);
            addIfPresent(problems, role == null ? "没有叫“" + name + "”的角色" : rules.assignProblem(role));
        }
        return problems;
    }

    private static void addIfPresent(List<String> messages, String message) {
        if (message != null) {
            messages.add(message);
        }
    }

    private Map<String, Role> rolesByName(TenantId tenantId) {
        return roles.list(tenantId).stream()
                .collect(Collectors.toMap(Role::name, Function.identity(), (first, second) -> first));
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
