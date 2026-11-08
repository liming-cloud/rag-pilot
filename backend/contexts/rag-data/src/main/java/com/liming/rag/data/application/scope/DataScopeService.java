package com.liming.rag.data.application.scope;

import com.liming.rag.data.api.DataAccess;
import com.liming.rag.data.api.ScopeDecision;
import com.liming.rag.data.application.DataException;
import com.liming.rag.data.application.DataGrantStore;
import com.liming.rag.data.application.Grants;
import com.liming.rag.data.application.ScopeUnit;
import com.liming.rag.data.application.UnitMaster;
import com.liming.rag.identity.api.UserDirectory;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 工具调用的数据范围判定，全系统只在这里写一处（D-056）。可用范围 = 用户本人的授权 ∪ 所属角色的授权（D-059），
 * 区域按调用时的站点主数据展开，新增站点自动在范围内。没有授权就是空范围，直接拒绝；
 * 不看是不是管理员，也不看模型填了什么：参数里指定的站点、区域必须全部落在范围内，否则整体拒绝，不悄悄缩小。
 * 站点主数据取不到时，区域授权展开不出站点，按没有处理（宁可拒绝，不放宽）。
 */
@Service
public class DataScopeService implements DataAccess {

    private static final Logger log = LoggerFactory.getLogger(DataScopeService.class);

    private final DataGrantStore grants;
    private final UserDirectory users;
    private final UnitMaster master;

    public DataScopeService(DataGrantStore grants, UserDirectory users, UnitMaster master) {
        this.grants = grants;
        this.users = users;
        this.master = master;
    }

    @Override
    public ScopeDecision decide(TenantId tenantId, UserId userId, String tool, Set<String> units,
            Set<String> regions) {
        var granted = grants.findEffective(tenantId, userId, users.roleIds(tenantId, userId));
        if (!granted.tools().contains(tool)) {
            return denied(userId, "tool_not_granted", "没有使用该工具的授权");
        }
        var needsMaster = !granted.regions().isEmpty() || !regions.isEmpty();
        var known = needsMaster ? masterUnits(tenantId) : List.<ScopeUnit>of();
        var authorized = authorized(granted, known);
        if (authorized.isEmpty()) {
            return denied(userId, "empty_scope", "没有被授权查看任何站点的数据");
        }
        if (units.isEmpty() && regions.isEmpty()) {
            return ScopeDecision.allow(authorized);
        }
        var requested = new HashSet<>(units);
        known.stream().filter(s -> regions.contains(s.region())).forEach(s -> requested.add(s.id()));
        if (requested.isEmpty() || !authorized.containsAll(requested)) {
            return denied(userId, "out_of_scope", "无权查看所请求的站点的数据");
        }
        return ScopeDecision.allow(requested);
    }

    /**
     * 提问人可用的工具和可查看的站点，规则同 {@link #decide}（本人 ∪ 角色，区域按主数据展开，取不到主数据时区域不算）。
     * 只用来告诉模型有哪些可选，每次调用仍要经 {@link #decide} 判定。
     */
    public AvailableScope available(TenantId tenantId, UserId userId) {
        var granted = grants.findEffective(tenantId, userId, users.roleIds(tenantId, userId));
        if (granted.tools().isEmpty()) {
            return new AvailableScope(Set.of(), Set.of());
        }
        var known = granted.regions().isEmpty() ? List.<ScopeUnit>of() : masterUnits(tenantId);
        return new AvailableScope(granted.tools(), authorized(granted, known));
    }

    /** 直接授权的站点，加上授权区域里的站点。 */
    private static Set<String> authorized(Grants granted, List<ScopeUnit> known) {
        var authorized = new HashSet<>(granted.units());
        known.stream().filter(s -> granted.regions().contains(s.region())).forEach(s -> authorized.add(s.id()));
        return authorized;
    }

    private List<ScopeUnit> masterUnits(TenantId tenantId) {
        try {
            return master.units(tenantId);
        } catch (DataException e) {
            log.warn("event=data_unit_master_unavailable");
            return List.of();
        }
    }

    private static ScopeDecision denied(UserId userId, String code, String reason) {
        log.info("event=data_scope_denied user={} code={}", userId.value(), code);
        return ScopeDecision.deny(reason);
    }
}
