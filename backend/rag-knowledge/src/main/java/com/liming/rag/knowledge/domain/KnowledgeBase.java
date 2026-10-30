package com.liming.rag.knowledge.domain;

import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.TenantId;
import java.util.Objects;

/**
 * 知识库聚合：一组文档和它的访问范围。成员授权在第 4 周加入。
 */
public final class KnowledgeBase implements AggregateRoot {

    /** 名称长度上限，与数据库列宽一致。 */
    public static final int MAX_NAME_LENGTH = 100;

    private final Long id;
    private final TenantId tenantId;
    private final String name;

    private KnowledgeBase(Long id, TenantId tenantId, String name) {
        this.id = id;
        this.tenantId = Objects.requireNonNull(tenantId, "租户不能为空");
        this.name = name;
    }

    /**
     * 新建知识库。
     *
     * @throws IllegalArgumentException 当名称为空或超过 {@value #MAX_NAME_LENGTH} 个字符时
     */
    public static KnowledgeBase create(TenantId tenantId, String name) {
        var trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("知识库名称必须是 1 到 " + MAX_NAME_LENGTH + " 个字符");
        }
        return new KnowledgeBase(null, tenantId, trimmed);
    }

    /** 仓储从数据库恢复聚合时使用，不做业务校验。 */
    public static KnowledgeBase restore(long id, TenantId tenantId, String name) {
        return new KnowledgeBase(id, tenantId, name);
    }

    public KnowledgeBase withId(long newId) {
        return new KnowledgeBase(newId, tenantId, name);
    }

    public Long id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }
}
