package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import com.liming.rag.knowledge.domain.KnowledgeBase;
import com.liming.rag.knowledge.domain.KnowledgeBaseRepository;
import java.util.List;
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
        return knowledgeBase.withId(row.getId());
    }

    @Override
    public Optional<KnowledgeBase> find(TenantId tenantId, long id) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getId, id)
                        .eq(KnowledgeBaseDO::getTenantId, tenantId.value())))
                .map(MybatisKnowledgeBaseRepository::toDomain);
    }

    @Override
    public List<KnowledgeBase> listAccessible(TenantId tenantId, UserId userId) {
        return members.accessible(tenantId.value(), userId.value()).stream()
                .map(MybatisKnowledgeBaseRepository::toDomain).toList();
    }

    @Override
    public void addMember(TenantId tenantId, long knowledgeBaseId, UserId userId) {
        members.insert(tenantId.value(), knowledgeBaseId, userId.value());
    }

    @Override
    public void removeMember(TenantId tenantId, long knowledgeBaseId, UserId userId) {
        members.delete(tenantId.value(), knowledgeBaseId, userId.value());
    }

    @Override
    public List<UserId> members(TenantId tenantId, long knowledgeBaseId) {
        return members.members(tenantId.value(), knowledgeBaseId).stream().map(UserId::new).toList();
    }

    @Override
    public boolean existsByName(TenantId tenantId, String name) {
        return mapper.exists(new LambdaQueryWrapper<KnowledgeBaseDO>()
                .eq(KnowledgeBaseDO::getTenantId, tenantId.value())
                .eq(KnowledgeBaseDO::getName, name));
    }

    private static KnowledgeBase toDomain(KnowledgeBaseDO row) {
        return KnowledgeBase.restore(row.getId(), new TenantId(row.getTenantId()), row.getName());
    }
}
