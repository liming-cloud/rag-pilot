package com.liming.rag.data.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.data.application.DataGrantStore;
import com.liming.rag.data.application.Grants;
import com.liming.rag.data.application.GranteeType;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link DataGrantStore} 的 MyBatis-Plus 实现：两张表，整体替换时先删后插，由调用方的事务保证一致。 */
@Repository
class MybatisDataGrantStore implements DataGrantStore {

    private static final String UNIT = "UNIT";
    private static final String REGION = "REGION";

    private final DataToolGrantMapper tools;
    private final DataScopeGrantMapper scopes;

    MybatisDataGrantStore(DataToolGrantMapper tools, DataScopeGrantMapper scopes) {
        this.tools = tools;
        this.scopes = scopes;
    }

    @Override
    public Grants find(TenantId tenantId, GranteeType type, long granteeId) {
        return load(tenantId, type.name(), List.of(granteeId));
    }

    @Override
    public Grants findEffective(TenantId tenantId, UserId userId, Collection<Long> roleIds) {
        var own = load(tenantId, GranteeType.USER.name(), List.of(userId.value()));
        if (roleIds.isEmpty()) {
            return own;
        }
        var viaRoles = load(tenantId, GranteeType.ROLE.name(), roleIds);
        return new Grants(union(own.tools(), viaRoles.tools()), union(own.units(), viaRoles.units()),
                union(own.regions(), viaRoles.regions()));
    }

    @Override
    @Transactional
    public void replace(TenantId tenantId, GranteeType type, long granteeId, Grants grants) {
        tools.delete(new LambdaQueryWrapper<DataToolGrantDO>().eq(DataToolGrantDO::getTenantId, tenantId.value())
                .eq(DataToolGrantDO::getGranteeType, type.name()).eq(DataToolGrantDO::getGranteeId, granteeId));
        scopes.delete(new LambdaQueryWrapper<DataScopeGrantDO>().eq(DataScopeGrantDO::getTenantId, tenantId.value())
                .eq(DataScopeGrantDO::getGranteeType, type.name()).eq(DataScopeGrantDO::getGranteeId, granteeId));
        grants.tools().forEach(tool -> tools.insert(toolRow(tenantId, type.name(), granteeId, tool)));
        grants.units().forEach(v -> scopes.insert(scopeRow(tenantId, type.name(), granteeId, UNIT, v)));
        grants.regions().forEach(v -> scopes.insert(scopeRow(tenantId, type.name(), granteeId, REGION, v)));
    }

    @Override
    @Transactional
    public void removeRole(TenantId tenantId, long roleId) {
        tools.delete(new LambdaQueryWrapper<DataToolGrantDO>().eq(DataToolGrantDO::getTenantId, tenantId.value())
                .eq(DataToolGrantDO::getGranteeType, GranteeType.ROLE.name()).eq(DataToolGrantDO::getGranteeId, roleId));
        scopes.delete(new LambdaQueryWrapper<DataScopeGrantDO>().eq(DataScopeGrantDO::getTenantId, tenantId.value())
                .eq(DataScopeGrantDO::getGranteeType, GranteeType.ROLE.name()).eq(DataScopeGrantDO::getGranteeId, roleId));
    }

    private Grants load(TenantId tenantId, String type, Collection<Long> granteeIds) {
        var toolNames = new HashSet<String>();
        tools.selectList(new LambdaQueryWrapper<DataToolGrantDO>().eq(DataToolGrantDO::getTenantId, tenantId.value())
                .eq(DataToolGrantDO::getGranteeType, type).in(DataToolGrantDO::getGranteeId, granteeIds))
                .forEach(row -> toolNames.add(row.getTool()));
        var storeIds = new HashSet<String>();
        var regionIds = new HashSet<String>();
        scopes.selectList(new LambdaQueryWrapper<DataScopeGrantDO>().eq(DataScopeGrantDO::getTenantId, tenantId.value())
                .eq(DataScopeGrantDO::getGranteeType, type).in(DataScopeGrantDO::getGranteeId, granteeIds))
                .forEach(row -> (UNIT.equals(row.getDimension()) ? storeIds : regionIds).add(row.getValue()));
        return new Grants(toolNames, storeIds, regionIds);
    }

    private static DataToolGrantDO toolRow(TenantId tenantId, String type, long granteeId, String tool) {
        var row = new DataToolGrantDO();
        row.tenantId = tenantId.value();
        row.granteeType = type;
        row.granteeId = granteeId;
        row.tool = tool;
        return row;
    }

    private static DataScopeGrantDO scopeRow(TenantId tenantId, String type, long granteeId, String dimension,
            String value) {
        var row = new DataScopeGrantDO();
        row.tenantId = tenantId.value();
        row.granteeType = type;
        row.granteeId = granteeId;
        row.dimension = dimension;
        row.value = value;
        return row;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
