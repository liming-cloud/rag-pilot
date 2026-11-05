package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 知识库和两张授权表都是单表读写；授权用 XML 里的“已有时什么也不做”的插入。 */
@Repository
class MybatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper mapper;
    private final KnowledgeBaseMemberMapper members;
    private final KnowledgeBaseRoleGrantMapper roleGrants;

    MybatisKnowledgeBaseRepository(KnowledgeBaseMapper mapper, KnowledgeBaseMemberMapper members,
            KnowledgeBaseRoleGrantMapper roleGrants) {
        this.mapper = mapper;
        this.members = members;
        this.roleGrants = roleGrants;
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
        knowledgeBase.addedMembers().forEach(user -> members.grant(tenant, id, user.value()));
        knowledgeBase.removedMembers().forEach(user -> revokeMember(tenant, id, user.value()));
        knowledgeBase.addedRoles().forEach(role -> roleGrants.grant(tenant, id, role));
        knowledgeBase.removedRoles().forEach(role -> revokeRole(tenant, id, role));
        return KnowledgeBase.restore(id, knowledgeBase.tenantId(), knowledgeBase.name(), knowledgeBase.members(),
                knowledgeBase.roleGrants());
    }

    @Override
    public Optional<KnowledgeBase> find(TenantId tenantId, long id) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getId, id)
                        .eq(KnowledgeBaseDO::getTenantId, tenantId.value())))
                .map(row -> KnowledgeBase.restore(row.getId(), tenantId, row.getName(),
                        membersOf(tenantId.value(), id), roleGrantsOf(tenantId.value(), id)));
    }

    @Override
    public boolean existsByName(TenantId tenantId, String name) {
        return mapper.exists(new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getTenantId, tenantId.value())
                .eq(KnowledgeBaseDO::getName, name));
    }

    @Override
    public void removeGrantsOfRole(TenantId tenantId, long roleId) {
        roleGrants.delete(new LambdaQueryWrapper<KnowledgeBaseRoleGrantDO>()
                .eq(KnowledgeBaseRoleGrantDO::getTenantId, tenantId.value())
                .eq(KnowledgeBaseRoleGrantDO::getRoleId, roleId));
    }

    private void revokeMember(long tenantId, long knowledgeBaseId, long userId) {
        members.delete(new LambdaQueryWrapper<KnowledgeBaseMemberDO>()
                .eq(KnowledgeBaseMemberDO::getTenantId, tenantId)
                .eq(KnowledgeBaseMemberDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeBaseMemberDO::getUserId, userId));
    }

    private void revokeRole(long tenantId, long knowledgeBaseId, long roleId) {
        roleGrants.delete(new LambdaQueryWrapper<KnowledgeBaseRoleGrantDO>()
                .eq(KnowledgeBaseRoleGrantDO::getTenantId, tenantId)
                .eq(KnowledgeBaseRoleGrantDO::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeBaseRoleGrantDO::getRoleId, roleId));
    }

    /** 按授权先后排列。 */
    private List<UserId> membersOf(long tenantId, long knowledgeBaseId) {
        return members.selectList(new LambdaQueryWrapper<KnowledgeBaseMemberDO>()
                        .eq(KnowledgeBaseMemberDO::getTenantId, tenantId)
                        .eq(KnowledgeBaseMemberDO::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByAsc(KnowledgeBaseMemberDO::getCreatedAt, KnowledgeBaseMemberDO::getUserId))
                .stream().map(row -> new UserId(row.userId)).toList();
    }

    private List<Long> roleGrantsOf(long tenantId, long knowledgeBaseId) {
        return roleGrants.selectList(new LambdaQueryWrapper<KnowledgeBaseRoleGrantDO>()
                        .eq(KnowledgeBaseRoleGrantDO::getTenantId, tenantId)
                        .eq(KnowledgeBaseRoleGrantDO::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByAsc(KnowledgeBaseRoleGrantDO::getCreatedAt, KnowledgeBaseRoleGrantDO::getRoleId))
                .stream().map(row -> row.roleId).toList();
    }
}
