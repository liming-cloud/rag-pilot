package com.liming.rag.evaluation.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** eval_run 的一行。 */
@TableName("eval_run")
@NullUnmarked
final class EvalRunDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long setId;
    private Long runnerId;
    private String status;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

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

    Long getSetId() {
        return setId;
    }

    void setSetId(Long setId) {
        this.setId = setId;
    }

    Long getRunnerId() {
        return runnerId;
    }

    void setRunnerId(Long runnerId) {
        this.runnerId = runnerId;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    OffsetDateTime getStartedAt() {
        return startedAt;
    }

    void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
