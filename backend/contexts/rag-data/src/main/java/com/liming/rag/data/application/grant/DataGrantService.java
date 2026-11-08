package com.liming.rag.data.application.grant;

import com.liming.rag.data.application.DataException;
import com.liming.rag.data.application.DataGrantStore;
import com.liming.rag.data.application.DataProperties;
import com.liming.rag.data.application.Grants;
import com.liming.rag.data.application.GranteeType;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.data.application.UnitMaster;
import com.liming.rag.identity.api.AuditTrail;
import com.liming.rag.identity.api.RoleDeleted;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.Enums;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具与数据范围授权（D-056）：给用户或角色整体设置可调用的工具、可查看的站点和区域；增加和收回分别记审计，
 * 和授权同一个事务。管理员也只能授权，不会因此自动获得数据范围。
 */
@Service
public class DataGrantService {

    private static final int MAX_VALUE_LENGTH = 64;
    private static final int MAX_ITEMS = 1000;
    /** 审计说明里最多逐个列出的数量，其余只写总数。 */
    private static final int MAX_LISTED = 20;

    private final DataGrantStore store;
    private final UserDirectory users;
    private final UnitMaster master;
    private final DataProperties properties;
    private final AuditTrail audit;

    public DataGrantService(DataGrantStore store, UserDirectory users, UnitMaster master, DataProperties properties,
            AuditTrail audit) {
        this.store = store;
        this.users = users;
        this.master = master;
        this.properties = properties;
        this.audit = audit;
    }

    public List<DataProperties.Tool> tools() {
        return properties.tools();
    }

    /** 站点主数据，取不到时抛 {@link DataException}（UNAVAILABLE），页面提示稍后再试。 */
    public List<ScopeUnit> units(TenantId tenantId) {
        return master.units(tenantId);
    }

    @Transactional(readOnly = true)
    public Grants find(TenantId tenantId, @Nullable String granteeType, long granteeId) {
        var type = parseType(granteeType);
        requireGrantee(tenantId, type, granteeId);
        return store.find(tenantId, type, granteeId);
    }

    @Transactional
    public Grants replace(TenantId tenantId, UserId actor, @Nullable String granteeType, long granteeId,
            Collection<String> tools, Collection<String> units, Collection<String> regions) {
        var type = parseType(granteeType);
        requireGrantee(tenantId, type, granteeId);
        var wanted = new Grants(Set.copyOf(tools), Set.copyOf(units), Set.copyOf(regions));
        validate(wanted);
        var before = store.find(tenantId, type, granteeId);
        store.replace(tenantId, type, granteeId, wanted);
        var who = (type == GranteeType.USER ? "账号 #" : "角色 #") + granteeId;
        var added = describe(wanted, before);
        if (!added.isEmpty()) {
            audit.record(tenantId, actor, "DATA_GRANTED", "DATA_GRANT", type + ":" + granteeId, who + " 新增授权：" + added);
        }
        var removed = describe(before, wanted);
        if (!removed.isEmpty()) {
            audit.record(tenantId, actor, "DATA_REVOKED", "DATA_GRANT", type + ":" + granteeId, who + " 收回授权：" + removed);
        }
        return wanted;
    }

    /** 角色被删除时一并收回授权给它的内容，在删除角色的事务里执行。 */
    @EventListener
    void on(RoleDeleted event) {
        store.removeRole(event.tenantId(), event.roleId());
    }

    private GranteeType parseType(@Nullable String value) {
        return Enums.parse(GranteeType.class, value, () -> new DataException(DataException.Reason.INVALID, "授权对象类型不对"));
    }

    private void requireGrantee(TenantId tenantId, GranteeType type, long granteeId) {
        var exists = type == GranteeType.USER ? users.exists(tenantId, new UserId(granteeId))
                : users.roleExists(tenantId, granteeId);
        if (!exists) {
            throw new DataException(DataException.Reason.NOT_FOUND, type == GranteeType.USER ? "账号不存在" : "角色不存在");
        }
    }

    private void validate(Grants grants) {
        var known = properties.tools().stream().map(DataProperties.Tool::name).collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(grants.tools())) {
            throw new DataException(DataException.Reason.INVALID, "包含不能授权的工具");
        }
        if (grants.units().size() > MAX_ITEMS || grants.regions().size() > MAX_ITEMS) {
            throw new DataException(DataException.Reason.INVALID, "一次授权的站点或区域太多");
        }
        for (var value : java.util.stream.Stream.concat(grants.units().stream(), grants.regions().stream()).toList()) {
            if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
                throw new DataException(DataException.Reason.INVALID, "站点或区域编号不能为空，且不超过 " + MAX_VALUE_LENGTH + " 个字符");
            }
        }
    }

    /** {@code a} 比 {@code b} 多出来的内容，如“工具 x；站点 S1、S2”。 */
    private static String describe(Grants a, Grants b) {
        var parts = new java.util.ArrayList<String>();
        part(parts, "工具", a.tools(), b.tools());
        part(parts, "站点", a.units(), b.units());
        part(parts, "区域", a.regions(), b.regions());
        return String.join("；", parts);
    }

    private static void part(List<String> parts, String label, Set<String> a, Set<String> b) {
        var diff = new TreeSet<>(a);
        diff.removeAll(b);
        if (!diff.isEmpty()) {
            var shown = diff.stream().limit(MAX_LISTED).toList();
            parts.add(label + " " + String.join("、", shown) + (diff.size() > shown.size() ? "等共 " + diff.size() + " 项" : ""));
        }
    }
}
