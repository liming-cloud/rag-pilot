package com.liming.rag.data.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NullUnmarked;

/** data_scope_grant 的一行：授权给用户或角色的站点或区域（dimension 为 UNIT 或 REGION）。 */
@TableName("data_scope_grant")
@NullUnmarked
final class DataScopeGrantDO {

    Long tenantId;
    String granteeType;
    Long granteeId;
    String dimension;
    String value;
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

    String getDimension() {
        return dimension;
    }

    String getValue() {
        return value;
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
