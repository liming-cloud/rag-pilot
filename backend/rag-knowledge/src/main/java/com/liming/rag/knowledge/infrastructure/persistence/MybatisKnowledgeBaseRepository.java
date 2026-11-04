package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MybatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper mapper;
    private final KnowledgeBaseMemberMapper members;

    MybatisKnowledgeBaseRepository(KnowledgeBaseMapper mapper, KnowledgeBaseMemberMapper members) {
        this.mapper = mapper;
        this.members = members;
    }

    @Override
    public KnowledgeBase save(KnowledgeBase knowledgeBase) {
        var row = new KnowledgeBaseDO();
        row.setId(knowledgeBase.id());
        row.setTenantId(knowledgeBase.tenantId().value());
        row.setName(knowledgeBase.name());
        if (row.getId() == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        long tenant = knowledgeBase.tenantId().value();
        long id = row.getId();
        knowledgeBase.addedMembers().forEach(user -> members.insert(tenant, id, user.value()));
        knowledgeBase.removedMembers().forEach(user -> members.delete(tenant, id, user.value()));
        knowledgeBase.addedRoles().forEach(role -> members.insertRoleGrant(tenant, id, role));
        knowledgeBase.removedRoles().forEach(role -> members.deleteRoleGrant(tenant, id, role));
        return KnowledgeBase.restore(id, knowledgeBase.tenantId(), knowledgeBase.name(), knowledgeBase.members(),
                knowledgeBase.roleGrants());
    }

    @Override
    public Optional<KnowledgeBase> find(TenantId tenantId, long id) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getId, id)
                        .eq(KnowledgeBaseDO::getTenantId, tenantId.value())))
                .map(row -> KnowledgeBase.restore(row.getId(), tenantId, row.getName(),
                        members.members(tenantId.value(), id).stream().map(UserId::new).toList(),
                        members.roleGrants(tenantId.value(), id)));
    }

    @Override
    public boolean existsByName(TenantId tenantId, String name) {
        return mapper.exists(new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getTenantId, tenantId.value())
                .eq(KnowledgeBaseDO::getName, name));
    }

    @Override
    public void removeGrantsOfRole(TenantId tenantId, long roleId) {
        members.deleteGrantsOfRole(tenantId.value(), roleId);
    }
}
