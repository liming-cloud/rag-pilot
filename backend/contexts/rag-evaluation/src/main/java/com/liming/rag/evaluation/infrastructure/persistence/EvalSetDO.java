package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** eval_set 的一行。 */
@TableName("eval_set")
@NullUnmarked
final class EvalSetDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    Long getTenantId() {
        return tenantId;
    }

    void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    String getDescription() {
        return description;
    }

    void setDescription(String description) {
        this.description = description;
    }

    Long getCreatedBy() {
        return createdBy;
    }

    void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
