package com.liming.rag.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** knowledge_base 表的持久化对象，只在 infrastructure 包内使用。 */
@TableName("knowledge_base")
public class KnowledgeBaseDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
