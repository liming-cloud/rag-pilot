package com.liming.rag.data.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** data_tool_grant 的一行：授权给用户或角色的工具（主键是四列，没有 id）。 */
@TableName("data_tool_grant")
@NullUnmarked
final class DataToolGrantDO {

    Long tenantId;
    String granteeType;
    Long granteeId;
    String tool;
    OffsetDateTime createdAt;

    Long getTenantId() {
        return tenantId;
    }

    String getGranteeType() {
        return granteeType;
    }

    Long getGranteeId() {
        return granteeId;
    }

    String getTool() {
        return tool;
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
